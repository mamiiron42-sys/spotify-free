package io.github.ytdlpandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.system.Os
import android.webkit.MimeTypeMap
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import java.util.function.Consumer

data class MusicSearchResult(
    val title: String,
    val url: String,
    val uploader: String,
    val durationSeconds: Int?,
    val thumbnailUrl: String? = null,
)

class DownloadService : Service() {
    interface Listener {
        fun onDownloadState(
            message: String,
            active: Boolean,
            paused: Boolean,
            canPause: Boolean,
            canCancel: Boolean,
            percent: Int?,
        )

        fun onQueueChanged(jobs: List<DownloadJob>)
    }

    inner class LocalBinder : Binder() {
        val service: DownloadService get() = this@DownloadService
    }

    private class JobControl(val jobId: Long) {
        enum class PauseOrigin { USER, WIFI_POLICY }

        val cancelRequested = AtomicBoolean(false)
        val pauseRequested = AtomicBoolean(false)
        @Volatile var pauseOrigin: PauseOrigin? = null
        @Volatile var ffmpegSessionId: Long? = null
        @Volatile var canPause = true
        @Volatile var canCancel = true
        @Volatile var message: String = "Starting…"
        @Volatile var percent: Int? = null
    }

    private val binder = LocalBinder()
    private val executor = ThreadPoolExecutor(
        DEFAULT_MAX_CONCURRENT_DOWNLOADS,
        MAX_CONCURRENT_DOWNLOADS,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_CONCURRENT_DOWNLOADS),
    )
    private val searchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val queueLock = java.lang.Object()
    private val pythonStartLock = Any()
    private val activeJobs = LinkedHashMap<Long, JobControl>()
    private val shuttingDown = AtomicBoolean(false)
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private val queueStore by lazy { DownloadQueueStore(this) }
    private val playlistStore by lazy { PlaylistStore(this) }
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    @Volatile
    private var wifiAvailable = false

    @Volatile
    private var foregroundStarted = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkPolicyEvaluation()

        override fun onLost(network: Network) = scheduleNetworkPolicyEvaluation()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            scheduleNetworkPolicyEvaluation()
    }

    @Volatile
    private var networkCallbackRegistered = false

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var workerActive = false

    @Volatile
    private var latestStartId = 0

    @Volatile
    private var status = "Ready"

    @Volatile
    private var percent: Int? = null

    override fun onCreate() {
        super.onCreate()
        executor.corePoolSize = getMaxConcurrentDownloads()
        queueStore.recoverInterrupted()
        wifiAvailable = hasWifiTransport()
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
            networkCallbackRegistered = false
        }
        scheduleNetworkPolicyEvaluation()
        // Starting the interpreter and building yt-dlp's extractor registry takes about a
        // second. Paying it here, off the interactive path, keeps the first search fast.
        searchExecutor.execute {
            try {
                ensurePythonStarted()
                Python.getInstance().getModule("downloader").callAttr("warm_up")
            } catch (_: Exception) {
                // A failed warm-up only costs the user's first search some time.
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setListener(newListener: Listener?) {
        listener = newListener
        notifyState()
        notifyQueue()
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        val boundedLimit = limit.coerceIn(1, MAX_CONCURRENT_DOWNLOADS)
        preferences.edit().putInt(PREFERENCE_MAX_CONCURRENT_DOWNLOADS, boundedLimit).apply()
        synchronized(queueLock) {
            executor.corePoolSize = boundedLimit
            if (!workerActive && queueStore.hasQueued()) {
                enterForeground("Starting queued downloads…")
            }
            pumpQueueLocked()
        }
        notifyQueue()
    }

    fun getMaxConcurrentDownloads(): Int =
        preferences.getInt(PREFERENCE_MAX_CONCURRENT_DOWNLOADS, DEFAULT_MAX_CONCURRENT_DOWNLOADS)
            .coerceIn(1, MAX_CONCURRENT_DOWNLOADS)

    fun setWifiOnly(enabled: Boolean) {
        preferences.edit().putBoolean(PREFERENCE_WIFI_ONLY, enabled).apply()
        scheduleNetworkPolicyEvaluation()
    }

    fun getWifiOnly(): Boolean = preferences.getBoolean(PREFERENCE_WIFI_ONLY, false)

    fun moveJobToFront(id: Long): Boolean {
        val moved = synchronized(queueLock) {
            val didMove = queueStore.moveToFront(id)
            if (didMove) pumpQueueLocked()
            didMove
        }
        if (moved) notifyQueue()
        return moved
    }

    fun resumeQueuedJobs() {
        if (queueStore.hasQueued() || queueStore.hasNetworkPaused()) {
            startServiceIntent(Intent(this, DownloadService::class.java).setAction(ACTION_RESUME_QUEUE))
        }
    }

    fun enqueueUrls(urlsJson: String, format: String, allowPlaylist: Boolean) {
        startServiceIntent(
            Intent(this, DownloadService::class.java)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_URLS, urlsJson)
                .putExtra(EXTRA_FORMAT, format)
                .putExtra(EXTRA_PLAYLIST, allowPlaylist),
        )
    }

    fun clearFinishedJobs(): Int {
        val removed = queueStore.clearFinished()
        if (removed > 0) notifyQueue()
        return removed
    }

    fun searchMusic(query: String, callback: (Result<List<MusicSearchResult>>) -> Unit) {
        searchMusic(query, 0, 20, callback)
    }

    fun searchMusic(
        query: String,
        offset: Int,
        limit: Int,
        callback: (Result<List<MusicSearchResult>>) -> Unit,
    ) {
        val cacheKey = "$query|$offset|$limit"
        cachedSearchPage(cacheKey)?.let { cached ->
            // A repeated query, or a page the user scrolled back to, costs nothing.
            Handler(Looper.getMainLooper()).post {
                callback(Result.success(decodeCachedResults(cached)))
            }
            return
        }
        searchExecutor.execute {
            try {
                ensurePythonStarted()
                val warningCount = AtomicInteger(0)
                val resultJson = Python.getInstance().getModule("downloader")
                    .callAttr(
                        "search_music",
                        query,
                        Consumer<String> { _ -> warningCount.incrementAndGet() },
                        offset,
                        limit,
                    )
                    .toString()
                val results = org.json.JSONArray(resultJson).let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        val url = item.optString("url")
                        if (!url.startsWith("https://")) return@mapNotNull null
                        MusicSearchResult(
                            title = item.optString("title"),
                            url = url,
                            uploader = item.optString("uploader"),
                            durationSeconds = if (item.isNull("duration")) null else item.optDouble("duration").toInt(),
                            thumbnailUrl = trustedThumbnailUrl(item.optString("thumbnail").takeIf(String::isNotBlank)),
                        )
                    }
                }
                cacheSearchResult(cacheKey, resultJson)
                Handler(Looper.getMainLooper()).post { callback(Result.success(results)) }
            } catch (error: Exception) {
                Handler(Looper.getMainLooper()).post { callback(Result.failure(error)) }
            }
        }
    }

    /**
     * Recently served search pages, newest first. Re-searching a query or scrolling back
     * over results the user already saw is then free instead of another yt-dlp round trip.
     */
    private val searchResultCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > SEARCH_CACHE_SIZE
    }

    /** Synchronized because the read happens on the caller's thread and the write on the executor. */
    @Synchronized
    private fun cachedSearchPage(key: String): String? = searchResultCache[key]

    @Synchronized
    private fun cacheSearchResult(key: String, json: String) {
        if (json.length in 1 until SEARCH_CACHE_MAX_ENTRY_CHARS) searchResultCache[key] = json
    }

    @Synchronized
    private fun decodeCachedResults(json: String): List<MusicSearchResult> =
        org.json.JSONArray(json).let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url")
                if (!url.startsWith("https://")) return@mapNotNull null
                MusicSearchResult(
                    title = item.optString("title"),
                    url = url,
                    uploader = item.optString("uploader"),
                    durationSeconds = if (item.isNull("duration")) null else item.optDouble("duration").toInt(),
                    thumbnailUrl = trustedThumbnailUrl(item.optString("thumbnail").takeIf(String::isNotBlank)),
                )
            }
        }

    fun cancelJob(id: Long) {
        val control = synchronized(queueLock) {
            activeJobs[id]?.also {
                it.cancelRequested.set(true)
                it.pauseRequested.set(false)
                it.pauseOrigin = null
                queueStore.update(id, DownloadQueueStore.STATE_CANCELED, "Canceling…", it.percent)
                queueLock.notifyAll()
            }
        }
        if (control != null) {
            control.ffmpegSessionId?.let { sessionId -> runCatching { FFmpegKit.cancel(sessionId) } }
            publishForJob(control, "Canceling job #$id…", control.percent)
        } else if (queueStore.cancel(id)) {
            notifyQueue()
            refreshForegroundState("Job #$id canceled")
        }
    }

    fun retryJob(id: Long) {
        if (queueStore.retry(id)) {
            notifyQueue()
            startServiceIntent(Intent(this, DownloadService::class.java).setAction(ACTION_RESUME_QUEUE))
        }
    }

    fun resumeJob(id: Long) {
        var resumedQueuedJob = false
        val result = synchronized(queueLock) {
            val control = activeJobs[id]
            if (control != null) {
                if (control.cancelRequested.get() || control.pauseOrigin == null) return@synchronized null
                if (getWifiOnly() && !hasWifiTransport()) {
                    control.pauseOrigin = JobControl.PauseOrigin.WIFI_POLICY
                    control.pauseRequested.set(true)
                    queueStore.update(
                        id,
                        DownloadQueueStore.STATE_PAUSED,
                        "Waiting for Wi-Fi",
                        control.percent,
                        DownloadQueueStore.PAUSE_ORIGIN_WIFI_POLICY,
                    )
                    return@synchronized control to false
                }
                control.pauseOrigin = null
                control.pauseRequested.set(false)
                queueStore.update(id, DownloadQueueStore.STATE_RUNNING, "Resuming…", control.percent)
                queueLock.notifyAll()
                control to true
            } else {
                val resumed = when {
                    queueStore.resume(id) -> true
                    (!getWifiOnly() || hasWifiTransport()) && queueStore.resumeNetworkPaused(id) > 0 -> true
                    else -> false
                }
                if (resumed) {
                    resumedQueuedJob = true
                    if (!foregroundStarted) enterForeground("Resuming download queue…")
                    pumpQueueLocked()
                }
                null
            }
        }
        if (result != null) {
            val (control, resumed) = result
            if (resumed) publishForJob(control, "Resuming job #$id…", control.percent)
            else publishForJob(control, "Waiting for Wi-Fi", control.percent)
        } else if (resumedQueuedJob) {
            notifyQueue()
        }
    }

    fun pauseJob(id: Long) {
        val control = synchronized(queueLock) {
            val active = activeJobs[id] ?: return
            if (!active.canPause || active.cancelRequested.get()) return
            active.pauseOrigin = JobControl.PauseOrigin.USER
            active.pauseRequested.set(true)
            queueStore.update(
                id,
                DownloadQueueStore.STATE_PAUSED,
                "Paused",
                active.percent,
                DownloadQueueStore.PAUSE_ORIGIN_USER,
            )
            active
        }
        publishForJob(control, "Paused job #$id", control.percent)
    }

    fun pauseCurrent() {
        val id = synchronized(queueLock) {
            activeJobs.values.lastOrNull {
                it.canPause && it.pauseOrigin != JobControl.PauseOrigin.USER
            }?.jobId
        } ?: return
        pauseJob(id)
    }

    fun resumeCurrent() {
        val id = synchronized(queueLock) {
            activeJobs.values.lastOrNull { it.pauseOrigin != null }?.jobId
        } ?: return
        resumeJob(id)
    }

    fun cancelCurrent() {
        val id = synchronized(queueLock) { activeJobs.values.lastOrNull { it.canCancel }?.jobId } ?: return
        cancelJob(id)
    }

    private fun scheduleNetworkPolicyEvaluation() {
        try {
            networkExecutor.execute { applyNetworkPolicy() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The service is shutting down.
        }
    }

    private fun applyNetworkPolicy() {
        wifiAvailable = hasWifiTransport()
        val pauseForPolicy = getWifiOnly() && !wifiAvailable
        val changedControls = mutableListOf<Pair<JobControl, Boolean>>()
        synchronized(queueLock) {
            activeJobs.values.forEach { control ->
                if (control.cancelRequested.get()) return@forEach
                when {
                    pauseForPolicy && control.pauseOrigin == null -> {
                        control.pauseOrigin = JobControl.PauseOrigin.WIFI_POLICY
                        control.pauseRequested.set(true)
                        queueStore.update(
                            control.jobId,
                            DownloadQueueStore.STATE_PAUSED,
                            "Waiting for Wi-Fi",
                            control.percent,
                            DownloadQueueStore.PAUSE_ORIGIN_WIFI_POLICY,
                        )
                        changedControls.add(control to true)
                    }

                    !pauseForPolicy && control.pauseOrigin == JobControl.PauseOrigin.WIFI_POLICY -> {
                        control.pauseOrigin = null
                        control.pauseRequested.set(false)
                        queueStore.update(
                            control.jobId,
                            DownloadQueueStore.STATE_RUNNING,
                            "Resuming…",
                            control.percent,
                        )
                        changedControls.add(control to false)
                    }
                }
            }
            if (!pauseForPolicy) {
                queueStore.resumeNetworkPaused()
                queueLock.notifyAll()
            }
            pumpQueueLocked()
        }
        changedControls.forEach { (control, paused) ->
            publishForJob(
                control,
                if (paused) "Waiting for Wi-Fi" else "Resuming job #${control.jobId}…",
                control.percent,
            )
        }
        notifyQueue()
    }

    private fun hasWifiTransport(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) {
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val command = intent ?: return START_NOT_STICKY
        when (command.action) {
            ACTION_ENQUEUE -> enqueueFrom(command, startId)
            ACTION_RESUME_QUEUE -> resumeQueue(startId)
            ACTION_PAUSE_CURRENT -> pauseCurrent()
            ACTION_RESUME_CURRENT -> resumeCurrent()
            ACTION_CANCEL_CURRENT -> cancelCurrent()
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun enqueueFrom(command: Intent, startId: Int) {
        enterForeground("Adding download to queue…")
        try {
            val urlsJson = command.getStringExtra(EXTRA_URLS).orEmpty()
            val format = command.getStringExtra(EXTRA_FORMAT) ?: "best"
            val allowPlaylist = command.getBooleanExtra(EXTRA_PLAYLIST, false)
            val id = synchronized(queueLock) {
                val queuedId = queueStore.enqueue(urlsJson, format, allowPlaylist)
                pumpQueueLocked()
                queuedId
            }
            publish("Queued download #$id", workerActive, null)
            notifyQueue()
        } catch (error: Exception) {
            publish(error.message ?: "Could not add the download to the queue", workerActive, percent)
            if (!workerActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelfResult(startId)
            }
        }
    }

    private fun resumeQueue(startId: Int) {
        if (!queueStore.hasQueued() && !queueStore.hasNetworkPaused()) {
            if (!workerActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelfResult(startId)
            }
            return
        }
        enterForeground("Resuming download queue…")
        synchronized(queueLock) { pumpQueueLocked() }
    }

    private fun enterForeground(message: String) {
        createNotificationChannel()
        status = message
        startForeground(NOTIFICATION_ID, notification(message, false))
        foregroundStarted = true
    }

    private fun startServiceIntent(intent: Intent) {
        startForegroundService(intent)
    }

    private fun ensurePythonStarted() {
        synchronized(pythonStartLock) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        }
    }

    private fun pumpQueueLocked() {
        if (shuttingDown.get()) return
        val limit = getMaxConcurrentDownloads()
        wifiAvailable = hasWifiTransport()
        val mayStartJobs = !getWifiOnly() || wifiAvailable
        if (!foregroundStarted && queueStore.hasQueued()) {
            enterForeground(if (mayStartJobs) "Starting queued downloads…" else "Waiting for Wi-Fi…")
        }
        while (mayStartJobs && activeJobs.size < limit) {
            val job = queueStore.claimNextQueued() ?: break
            val control = JobControl(job.id)
            activeJobs[job.id] = control
            try {
                executor.execute { processJob(job, control) }
            } catch (error: Exception) {
                activeJobs.remove(job.id)
                queueStore.update(job.id, DownloadQueueStore.STATE_QUEUED, "Waiting")
                publish("Could not start job #${job.id}: ${error.message ?: "worker pool rejected the job"}", true, null)
                break
            }
        }
        workerActive = activeJobs.isNotEmpty()
        val hasQueued = queueStore.hasQueued()
        val waitingForWifi = queueStore.hasNetworkPaused()
        if (workerActive || hasQueued || waitingForWifi) {
            if (!foregroundStarted && (hasQueued || waitingForWifi)) {
                enterForeground(
                    if (mayStartJobs) "Starting queued downloads…"
                    else "Waiting for Wi-Fi…",
                )
            }
            refreshForegroundState(status)
        } else {
            status = "Queue idle"
            percent = null
            notifyState()
            notifyQueue()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelfResult(latestStartId)
        }
    }

    private fun processJob(job: DownloadJob, control: JobControl) {
        publishForJob(control, "Downloading job #${job.id}…", null)
        notifyQueue()

        val savedNames = mutableListOf<String>()
        val optionalWarnings = mutableListOf<String>()
        try {
            if (control.cancelRequested.get()) throw CancellationException("Job #${job.id} canceled")
            ensurePythonStarted()
            val module = Python.getInstance().getModule("downloader")
            val progressCallback = Consumer<String> { json -> onPythonEvent(control, json) }
            val cancellationSignal = BooleanSupplier { control.cancelRequested.get() }
            val pauseSignal = BooleanSupplier { control.pauseRequested.get() }
            val outputDir = File(filesDir, "downloads").apply { mkdirs() }
            val resultJson = module.callAttr(
                "download_urls",
                job.urlsJson,
                outputDir.absolutePath,
                job.id,
                job.format,
                job.allowPlaylist,
                progressCallback,
                cancellationSignal,
                pauseSignal,
            ).toString()
            control.canPause = true
            val result = JSONObject(resultJson)
            val paths = result.getJSONArray("files")
            val trackMetadataByPath = parseTrackMetadata(result, optionalWarnings, control)
            publishForJob(control, "Saving files for job #${job.id}…", 100)
            for (index in 0 until paths.length()) {
                awaitIfPaused(control)
                if (control.cancelRequested.get()) break
                val sourcePath = paths.getString(index)
                val source = File(sourcePath)
                val track = trackMetadataByPath[sourcePath] ?: trackMetadataByPath[source.absolutePath]
                val durationMs = track?.durationMs
                val artwork = track?.thumbnailUrl?.let { thumbnailUrl ->
                    try {
                        fetchArtwork(thumbnailUrl, control)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        reportOptionalWarning(
                            control,
                            optionalWarnings,
                            "Artwork unavailable for ${track.title.ifBlank { source.name }}: ${error.message ?: "thumbnail could not be saved"}",
                        )
                        null
                    }
                }
                if (job.format == FORMAT_AUDIO_MP3) {
                    val converted = File.createTempFile("ytdlp-${job.id}-", ".mp3", cacheDir)
                    try {
                        publishForJob(control, "Converting ${source.name} to MP3…", null)
                        awaitIfPaused(control)
                        control.canPause = false
                        convertToMp3(source, converted, track, artwork, optionalWarnings, control)
                        if (control.cancelRequested.get()) throw CancellationException("Conversion canceled")
                        control.canPause = true
                        val displayName = "${source.nameWithoutExtension}.mp3"
                        val published = copyIntoDownloads(converted, displayName, track, durationMs, control)
                        savedNames.add(published.displayName)
                        persistPublishedMetadata(published, track, durationMs, artwork, optionalWarnings, control)
                        source.delete()
                    } finally {
                        converted.delete()
                    }
                } else {
                    val published = copyIntoDownloads(source, metadata = track, durationMs = durationMs, control = control)
                    savedNames.add(published.displayName)
                    persistPublishedMetadata(published, track, durationMs, artwork, optionalWarnings, control)
                }
            }
            val failedCount = result.optInt("errors", 0)
            val jobState = when {
                control.cancelRequested.get() -> DownloadQueueStore.STATE_CANCELED
                failedCount > 0 -> DownloadQueueStore.STATE_PARTIAL
                else -> DownloadQueueStore.STATE_COMPLETE
            }
            val message = when (jobState) {
                DownloadQueueStore.STATE_CANCELED -> if (savedNames.isEmpty()) "Canceled" else
                    "Saved ${savedNames.size} file(s) before canceling"

                DownloadQueueStore.STATE_PARTIAL -> "Saved ${savedNames.size} file(s); $failedCount failed"
                else -> if (savedNames.size == 1) {
                    "Saved to Downloads/yt-dlp/${savedNames.single()}"
                } else {
                    "Saved ${savedNames.size} files to Downloads/yt-dlp"
                }
            }
            val finalMessage = withOptionalWarnings(message, optionalWarnings)
            queueStore.update(
                job.id,
                jobState,
                finalMessage,
                if (jobState == DownloadQueueStore.STATE_COMPLETE) 100 else null
            )
            publishForJob(control, "Job #${job.id}: $finalMessage", if (jobState == DownloadQueueStore.STATE_COMPLETE) 100 else null)
        } catch (error: Exception) {
            control.canPause = false
            val canceled = control.cancelRequested.get()
            if (shuttingDown.get()) {
                queueStore.update(
                    job.id,
                    DownloadQueueStore.STATE_QUEUED,
                    "Interrupted; queued to resume",
                )
                return
            }
            val jobState = when {
                canceled -> DownloadQueueStore.STATE_CANCELED
                savedNames.isNotEmpty() -> DownloadQueueStore.STATE_PARTIAL
                else -> DownloadQueueStore.STATE_FAILED
            }
            val detail = error.message?.takeIf { it.isNotBlank() } ?: "Download failed"
            val message = when {
                canceled && savedNames.isNotEmpty() -> "Saved ${savedNames.size} file(s) before canceling"
                canceled -> "Canceled"
                savedNames.isNotEmpty() -> "Saved ${savedNames.size} file(s); $detail"
                else -> detail
            }
            val finalMessage = withOptionalWarnings(message, optionalWarnings)
            queueStore.update(job.id, jobState, finalMessage)
            publishForJob(control, "Job #${job.id}: $finalMessage", null)
        } finally {
            control.canPause = false
            control.canCancel = false
            control.ffmpegSessionId = null
            synchronized(queueLock) {
                activeJobs.remove(job.id)
                pumpQueueLocked()
            }
        }
    }

    private fun convertToMp3(
        source: File,
        destination: File,
        metadata: TrackMetadata?,
        artwork: File?,
        warnings: MutableList<String>,
        control: JobControl,
    ) {
        check(source.isFile && source.length() > 0L) { "Audio source is missing or empty" }
        fun arguments(includeArtwork: Boolean) = mutableListOf(
            "-hide_banner",
            "-nostdin",
            "-y",
            "-i", source.absolutePath,
            "-map", "0:a:0",
            "-codec:a", "libmp3lame",
            "-q:a", "2",
            "-id3v2_version", "3",
        ).apply {
            if (includeArtwork && artwork != null) {
                addAll(
                    listOf(
                        "-i", artwork.absolutePath,
                        "-map", "1:v:0",
                        "-c:v", "mjpeg",
                        "-disposition:v:0", "attached_pic",
                    ),
                )
            } else {
                add("-vn")
            }
            addMetadataArguments(this, metadata)
            if (includeArtwork && artwork != null) {
                addAll(listOf("-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)"))
            }
            add(destination.absolutePath)
        }
        try {
            runFfmpeg(arguments(artwork != null).toTypedArray(), destination, control)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (artwork == null) throw error
            destination.delete()
            reportOptionalWarning(
                control,
                warnings,
                "Could not embed cover art for ${metadata?.title?.ifBlank { source.name } ?: source.name}: ${error.message ?: "MP3 artwork embedding failed"}",
            )
            runFfmpeg(arguments(includeArtwork = false).toTypedArray(), destination, control)
        }
    }

    private fun addMetadataArguments(arguments: MutableList<String>, metadata: TrackMetadata?) {
        metadata?.title?.takeIf(String::isNotBlank)?.let { arguments.addAll(listOf("-metadata", "title=$it")) }
        metadata?.artist?.takeIf(String::isNotBlank)?.let { arguments.addAll(listOf("-metadata", "artist=$it")) }
        metadata?.album?.takeIf(String::isNotBlank)?.let { arguments.addAll(listOf("-metadata", "album=$it")) }
    }

    private fun runFfmpeg(arguments: Array<String>, destination: File, control: JobControl) {
        val completed = CountDownLatch(1)
        val result = AtomicReference<com.arthenica.ffmpegkit.FFmpegSession?>()
        val session = FFmpegKit.executeWithArgumentsAsync(arguments) { finished ->
            result.set(finished)
            completed.countDown()
        }
        control.ffmpegSessionId = session.sessionId

        try {
            var cancelSent = false
            while (true) {
                if ((control.cancelRequested.get() || Thread.currentThread().isInterrupted) && !cancelSent) {
                    FFmpegKit.cancel(session.sessionId)
                    cancelSent = true
                }
                if (completed.await(250, TimeUnit.MILLISECONDS)) break
            }
        } catch (interrupted: InterruptedException) {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            Thread.interrupted()
            try {
                completed.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // Preserve the interrupted state after the bounded cleanup wait.
            } finally {
                Thread.currentThread().interrupt()
            }
            throw interrupted
        } finally {
            control.ffmpegSessionId = null
        }

        if (control.cancelRequested.get()) throw CancellationException("MP3 conversion canceled")
        val finished = result.get() ?: error("FFmpeg did not return a conversion result")
        check(ReturnCode.isSuccess(finished.returnCode)) {
            val detail = finished.failStackTrace?.takeIf { it.isNotBlank() }
                ?: finished.output?.takeLast(1200)?.takeIf { it.isNotBlank() }
                ?: "No FFmpeg diagnostic was provided"
            "FFmpeg processing failed: $detail"
        }
        check(destination.isFile && destination.length() > 0L) {
            "FFmpeg completed without producing a nonempty MP3 file"
        }
    }

    private fun onPythonEvent(control: JobControl, json: String) {
        val jobId = control.jobId
        try {
            val event = JSONObject(json)
            when (event.optString("event")) {
                "progress" -> {
                    val value = if (event.has("percent") && !event.isNull("percent")) {
                        event.getDouble("percent").toInt().coerceIn(0, 100)
                    } else null
                    val downloaded = formatBytes(event.optLong("downloaded", 0))
                    val total = if (!event.isNull("total")) formatBytes(event.optLong("total")) else "?"
                    val speed = if (!event.isNull("speed")) " · ${formatBytes(event.optLong("speed"))}/s" else ""
                    val eta = if (!event.isNull("eta")) " · ${event.optInt("eta")}s left" else ""
                    val title = event.optString("title").takeIf { it.isNotBlank() }
                    val label = title?.let { "$it · " }.orEmpty()
                    val message = "$label${value?.let { "$it% · " } ?: ""}$downloaded / $total$speed$eta"
                    control.percent = value
                    synchronized(queueLock) {
                        if (!control.cancelRequested.get()) {
                            val origin = control.pauseOrigin
                            queueStore.update(
                                jobId,
                                if (origin != null) DownloadQueueStore.STATE_PAUSED else DownloadQueueStore.STATE_RUNNING,
                                if (origin == JobControl.PauseOrigin.WIFI_POLICY) "Waiting for Wi-Fi" else message,
                                value,
                                when (origin) {
                                    JobControl.PauseOrigin.USER -> DownloadQueueStore.PAUSE_ORIGIN_USER
                                    JobControl.PauseOrigin.WIFI_POLICY -> DownloadQueueStore.PAUSE_ORIGIN_WIFI_POLICY
                                    null -> null
                                },
                            )
                        }
                    }
                    val statusMessage = when (control.pauseOrigin) {
                        JobControl.PauseOrigin.WIFI_POLICY -> "Waiting for Wi-Fi"
                        JobControl.PauseOrigin.USER -> "Paused job #$jobId"
                        null -> message
                    }
                    publishForJob(control, statusMessage, value)
                }

                "processing" -> publishForJob(control, event.optString("message", "Finishing download…"), control.percent)
                "paused" -> publishForJob(control, "Paused job #$jobId", control.percent)
                "resumed" -> publishForJob(control, "Resuming job #$jobId", control.percent)
                "warning" -> publishForJob(control, "Warning: ${event.optString("message")}", control.percent)
                "error" -> publishForJob(control, event.optString("message", "yt-dlp reported an error"), control.percent)
            }
        } catch (_: Exception) {
            publishForJob(control, "Downloading job #$jobId…", control.percent)
        }
    }

    private data class TrackMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long?,
        val thumbnailUrl: String?,
    )

    private data class PublishedMedia(val displayName: String, val uri: String)

    private fun parseTrackMetadata(
        result: JSONObject,
        warnings: MutableList<String>,
        control: JobControl,
    ): Map<String, TrackMetadata> {
        val tracks = result.optJSONArray("tracks") ?: run {
            if (result.has("tracks") && !result.isNull("tracks")) {
                reportOptionalWarning(control, warnings, "Download metadata was not in the expected format")
            }
            return emptyMap()
        }
        val metadataByPath = mutableMapOf<String, TrackMetadata>()
        for (index in 0 until tracks.length()) {
            val item = tracks.optJSONObject(index) ?: run {
                reportOptionalWarning(control, warnings, "Metadata for downloaded file #${index + 1} was malformed")
                continue
            }
            val path = item.optString("path").takeIf(String::isNotBlank)
            if (path == null) {
                reportOptionalWarning(control, warnings, "A downloaded track had no matching file path; its metadata was skipped")
                continue
            }
            val durationSeconds = if (item.isNull("duration")) null else item.optDouble("duration", Double.NaN)
            val durationMs = durationSeconds
                ?.takeIf { it.isFinite() && it > 0.0 && it <= Long.MAX_VALUE / 1000.0 }
                ?.let { (it * 1000.0).toLong() }
            val thumbnailValue = item.optString("thumbnail").takeIf(String::isNotBlank)
            val thumbnailUrl = trustedThumbnailUrl(thumbnailValue)
            if (thumbnailValue != null && thumbnailUrl == null) {
                reportOptionalWarning(
                    control,
                    warnings,
                    "Untrusted YouTube thumbnail URL was ignored for ${item.optString("title", "a track")}",
                )
            }
            metadataByPath[path] = TrackMetadata(
                title = item.optString("title").trim(),
                artist = item.optString("artist").trim(),
                album = item.optString("album").trim(),
                durationMs = durationMs,
                thumbnailUrl = thumbnailUrl,
            )
        }
        return metadataByPath
    }

    private fun persistPublishedMetadata(
        published: PublishedMedia,
        metadata: TrackMetadata?,
        durationMs: Long?,
        artwork: File?,
        warnings: MutableList<String>,
        control: JobControl,
    ) {
        if (metadata == null) return
        try {
            playlistStore.saveTrackMetadata(
                trackUri = published.uri,
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = durationMs,
                artworkPath = artwork?.absolutePath,
            )
        } catch (error: Exception) {
            reportOptionalWarning(
                control,
                warnings,
                "Could not save metadata for ${metadata.title.ifBlank { published.displayName }}: ${error.message ?: "metadata storage failed"}",
            )
        }
    }

    private fun reportOptionalWarning(control: JobControl, warnings: MutableList<String>, warning: String) {
        warnings.add(warning)
        publishForJob(control, "Warning: $warning", control.percent)
    }

    private fun withOptionalWarnings(message: String, warnings: List<String>): String =
        if (warnings.isEmpty()) message
        else "$message · ${warnings.size} metadata/artwork warning(s): ${warnings.first()}"

    private fun copyIntoDownloads(
        source: File,
        displayName: String = source.name,
        metadata: TrackMetadata? = null,
        durationMs: Long? = null,
        control: JobControl,
    ): PublishedMedia {
        require(source.isFile) { "Downloaded file was not found" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(displayName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/yt-dlp/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (mimeType(displayName).startsWith("audio/")) {
                metadata?.title?.takeIf(String::isNotBlank)?.let { put(MediaStore.Audio.Media.TITLE, it) }
                metadata?.artist?.takeIf(String::isNotBlank)?.let { put(MediaStore.Audio.Media.ARTIST, it) }
                metadata?.album?.takeIf(String::isNotBlank)?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                durationMs?.takeIf { it > 0L }?.let { put(MediaStore.Audio.Media.DURATION, it) }
            }
        }
        val destination = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create a file in Downloads")

        try {
            val totalBytes = source.length()
            var copiedBytes = 0L
            var lastUpdate = 0L
            source.inputStream().buffered(COPY_BUFFER_SIZE).use { input ->
                contentResolver.openOutputStream(destination, "w")?.buffered(COPY_BUFFER_SIZE)?.use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        awaitIfPaused(control)
                        if (control.cancelRequested.get()) throw CancellationException("Download canceled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copiedBytes += count
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 500) {
                            val copyPercent = if (totalBytes > 0) (copiedBytes * 100 / totalBytes).toInt() else null
                            publishForJob(
                                control,
                                "Saving job #${control.jobId}: ${source.name}… ${copyPercent?.let { "$it%" } ?: ""}".trim(),
                                copyPercent,
                            )
                            lastUpdate = now
                        }
                    }
                } ?: error("Could not open the destination file")
            }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(contentResolver.update(destination, ready, null, null) == 1) {
                "Could not finish publishing the downloaded file"
            }
            val savedName = contentResolver.query(
                destination,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: source.name
            source.delete()
            return PublishedMedia(savedName, destination.toString())
        } catch (error: Exception) {
            contentResolver.delete(destination, null, null)
            throw error
        }
    }

    private fun fetchArtwork(thumbnailUrl: String, control: JobControl): File {
        val trustedUrl = trustedThumbnailUrl(thumbnailUrl)
            ?: throw IllegalArgumentException("Thumbnail URL is not a trusted HTTPS YouTube image")
        if (control.cancelRequested.get()) throw CancellationException("Download canceled")
        val directory = File(filesDir, ARTWORK_DIRECTORY).apply {
            check(isDirectory || mkdirs()) { "Could not create private artwork storage" }
        }
        val cacheKey = MessageDigest.getInstance("SHA-256")
            .digest(trustedUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        val cachedFile = directory.listFiles { file, name ->
            name.startsWith("$cacheKey.") && file.isFile && file.length() > 0L
        }?.firstOrNull()
        if (cachedFile != null) return cachedFile

        val temporary = File.createTempFile("thumbnail-", ".part", directory)
        val connection = URL(trustedUrl).openConnection() as? HttpURLConnection
            ?: throw IllegalArgumentException("Thumbnail URL did not use HTTP")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = THUMBNAIL_CONNECT_TIMEOUT_MS
            connection.readTimeout = THUMBNAIL_READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.8")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Thumbnail server returned HTTP ${connection.responseCode}"
            }
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            check(contentType.startsWith("image/") && contentType != "image/svg+xml") {
                "Thumbnail response was not a supported image"
            }
            val extension = when (contentType) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/bmp" -> "bmp"
                else -> "image"
            }
            val cached = File(directory, "$cacheKey.$extension")
            val contentLength = connection.contentLengthLong
            check(contentLength <= MAX_THUMBNAIL_BYTES) { "Thumbnail exceeded the size limit" }
            var totalBytes = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        awaitIfPaused(control)
                        if (control.cancelRequested.get()) throw CancellationException("Download canceled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        check(totalBytes <= MAX_THUMBNAIL_BYTES) { "Thumbnail exceeded the size limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(totalBytes > 0L) { "Thumbnail image was empty" }
            Os.rename(temporary.absolutePath, cached.absolutePath)
            return cached
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun mimeType(filename: String): String =
        when (filename.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "flv" -> "video/x-flv"
            "ts" -> "video/mp2t"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "aiff", "aif" -> "audio/aiff"
            else -> MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
        }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unit = -1
        do {
            size /= 1024.0
            unit++
        } while (size >= 1024 && unit < units.lastIndex)
        return String.format(Locale.ROOT, "%.1f %s", size, units[unit])
    }

    private fun publish(message: String, active: Boolean, progress: Int?) {
        status = message
        percent = progress
        val control = latestActiveControl()
        listener?.onDownloadState(
            message,
            active || workerActive,
            control?.pauseRequested?.get() ?: false,
            control?.canPause ?: false,
            control?.canCancel ?: false,
            progress,
        )
        notifyQueue()
        if (active) {
            refreshForegroundState(message)
        }
    }

    private fun publishForJob(control: JobControl, message: String, progress: Int?) {
        val labeledMessage = if (message.startsWith("Job #${control.jobId}")) {
            message
        } else {
            "Job #${control.jobId}: $message"
        }
        control.message = labeledMessage
        control.percent = progress
        status = labeledMessage
        percent = progress
        listener?.onDownloadState(
            labeledMessage,
            true,
            control.pauseRequested.get(),
            control.canPause,
            control.canCancel,
            progress,
        )
        notifyQueue()
        refreshForegroundState(labeledMessage)
    }

    private fun notifyState() {
        val control = latestActiveControl()
        listener?.onDownloadState(
            control?.message ?: status,
            workerActive,
            control?.pauseRequested?.get() ?: false,
            control?.canPause ?: false,
            control?.canCancel ?: false,
            control?.percent ?: percent,
        )
    }

    private fun latestActiveControl(): JobControl? =
        synchronized(queueLock) { activeJobs.values.lastOrNull() }

    private fun refreshForegroundState(message: String) {
        status = message
        val counts = synchronized(queueLock) {
            val paused = queueStore.countPaused()
            Triple(activeJobs.size, paused, queueStore.countQueued())
        }
        workerActive = counts.first > 0
        val summary = "Active: ${counts.first} · Paused: ${counts.second} · Queued: ${counts.third}"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification("$message\n$summary", counts.first > 0))
    }

    private fun awaitIfPaused(control: JobControl) {
        while (control.pauseRequested.get()) {
            if (control.cancelRequested.get()) throw CancellationException("Job #${control.jobId} canceled")
            synchronized(queueLock) {
                if (control.pauseRequested.get()) queueLock.wait(250L)
            }
        }
        if (control.cancelRequested.get()) throw CancellationException("Job #${control.jobId} canceled")
    }

    private fun notifyQueue() {
        listener?.onQueueChanged(queueStore.getJobs())
    }

    private fun notification(message: String, hasCurrentJob: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("yt-dlp")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val controls = synchronized(queueLock) { activeJobs.values.toList() }
        val pauseControl = controls.lastOrNull { it.canPause }
        val cancelControl = controls.lastOrNull { it.canCancel }
        if (hasCurrentJob && pauseControl != null) {
            val isPaused = pauseControl.pauseRequested.get()
            val pauseAction = if (isPaused) ACTION_RESUME_CURRENT else ACTION_PAUSE_CURRENT
            val pauseLabel = if (isPaused) "Resume" else "Pause"
            val pauseIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, DownloadService::class.java).setAction(pauseAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    pauseLabel,
                    pauseIntent,
                )
                    .build(),
            )
        }
        if (hasCurrentJob && cancelControl != null) {
            val cancelIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_CURRENT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_menu_close_clear_cancel,
                    ),
                    "Cancel",
                    cancelIntent,
                )
                    .build(),
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        shuttingDown.set(true)
        if (networkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
                // The callback may already have been unregistered by the system.
            }
            networkCallbackRegistered = false
        }
        networkExecutor.shutdown()
        val controls = synchronized(queueLock) { activeJobs.values.toList() }
        controls.forEach { control ->
            control.cancelRequested.set(true)
            control.ffmpegSessionId?.let { runCatching { FFmpegKit.cancel(it) } }
        }
        executor.shutdown()
        searchExecutor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
            if (!searchExecutor.awaitTermination(2, TimeUnit.SECONDS)) searchExecutor.shutdownNow()
            if (!networkExecutor.awaitTermination(2, TimeUnit.SECONDS)) networkExecutor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            searchExecutor.shutdownNow()
            networkExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENQUEUE = "io.github.ytdlpandroid.action.ENQUEUE"
        private const val FORMAT_AUDIO_MP3 = "audio_mp3"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val SEARCH_CACHE_SIZE = 48
        private const val SEARCH_CACHE_MAX_ENTRY_CHARS = 128 * 1024

        const val ACTION_RESUME_QUEUE = "io.github.ytdlpandroid.action.RESUME_QUEUE"
        const val ACTION_PAUSE_CURRENT = "io.github.ytdlpandroid.action.PAUSE_CURRENT"
        const val ACTION_RESUME_CURRENT = "io.github.ytdlpandroid.action.RESUME_CURRENT"
        const val ACTION_CANCEL_CURRENT = "io.github.ytdlpandroid.action.CANCEL_CURRENT"
        const val EXTRA_URLS = "io.github.ytdlpandroid.extra.URLS"
        const val EXTRA_FORMAT = "io.github.ytdlpandroid.extra.FORMAT"
        const val EXTRA_PLAYLIST = "io.github.ytdlpandroid.extra.PLAYLIST"
        private const val CHANNEL_ID = "yt_dlp_downloads"
        private const val NOTIFICATION_ID = 1
        private const val PREFERENCES_NAME = "download-settings"
        private const val PREFERENCE_MAX_CONCURRENT_DOWNLOADS = "max_concurrent_downloads"
        private const val PREFERENCE_WIFI_ONLY = "wifi_only"
        private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3
        private const val MAX_CONCURRENT_DOWNLOADS = 10
        private const val ARTWORK_DIRECTORY = "youtube-artwork"
        private const val THUMBNAIL_CONNECT_TIMEOUT_MS = 8_000
        private const val THUMBNAIL_READ_TIMEOUT_MS = 10_000
        private const val MAX_THUMBNAIL_BYTES = 5L * 1024L * 1024L
    }
}
