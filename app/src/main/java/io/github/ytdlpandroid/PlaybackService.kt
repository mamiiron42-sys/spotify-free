package io.github.ytdlpandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.random.Random

data class PlaybackTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artwork: Bitmap?,
)

data class PlaybackSnapshot(
    val track: PlaybackTrack?,
    val index: Int,
    val queueSize: Int,
    val positionMs: Int,
    val durationMs: Int,
    val isPlaying: Boolean,
    val isPrepared: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val speed: Float,
    val sleepMinutesRemaining: Int?,
    val equalizerEnabled: Boolean,
    val equalizerLevel: Int,
    val message: String?,
)

class PlaybackService : Service() {
    interface Listener {
        fun onPlaybackChanged(snapshot: PlaybackSnapshot)
        fun onPlaybackHistoryChanged() {}
        fun onPlaybackHistoryError() {}
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService
            get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val historyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val playlistStore by lazy { PlaylistStore(this) }
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var listener: Listener? = null
    private var player: MediaPlayer? = null
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false
    private var queue: List<PlaybackTrack> = emptyList()
    private var currentIndex = -1
    private var historyRecordedForCurrentTrack = false
    private var prepared = false
    private var playing = false
    private var shuffled = false
    private var repeat = REPEAT_OFF
    private var playbackSpeed = 1f
    private var equalizerRequested = false
    private var equalizerLevel = DEFAULT_EQUALIZER_LEVEL
    private var sleepDeadlineElapsed: Long? = null
    private var sleepTask: Runnable? = null
    private var lastSleepMinutes: Int? = null
    private var message: String? = null
    @Volatile private var snapshot = emptySnapshot()
    private val playbackPrefs by lazy {
        applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private var restoringPersistedState = false
    private var pendingResumeIndex = -1
    private var pendingResumePlaying = false
    private var resumePositionMs: Int? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || player == null) return
            publishSnapshot()
            mainHandler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    startPlayer()
                } else {
                    updatePlaybackState()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (playing) {
                    resumeOnFocusGain = true
                    pausePlayer(abandonFocus = false)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                if (playing) pausePlayer(abandonFocus = false)
                abandonAudioFocus()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createMediaSession()
        restorePersistedState()
        publishSnapshot()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && queue.isEmpty()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PREVIOUS -> playPrevious()
            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> playNext()
            else -> {
                ensureForeground()
                resumePersistedState()
                publishSnapshot()
            }
        }
        return if (queue.isEmpty()) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        persistPlaybackState()
        mainHandler.removeCallbacks(ticker)
        cancelSleepTimer()
        historyExecutor.shutdown()
        releasePlayer()
        abandonAudioFocus()
        stopForegroundCompat()
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        listener = null
        super.onDestroy()
    }

    fun setListener(listener: Listener?) {
        dispatchToMain {
            this.listener = listener
            listener?.onPlaybackChanged(snapshot)
        }
    }

    fun getSnapshot(): PlaybackSnapshot = snapshot

    fun playQueue(tracks: List<PlaybackTrack>, index: Int) {
        dispatchToMain {
            if (tracks.isEmpty() || index !in tracks.indices) {
                message = "Cannot play an empty queue or invalid track."
                publishSnapshot()
                return@dispatchToMain
            }
            startService(Intent(this, PlaybackService::class.java).setAction(ACTION_START))
            ensureForeground()
            queue = tracks.toList()
            currentIndex = index
            message = null
            resumeOnFocusGain = false
            startTrack(currentIndex)
        }
    }

    fun getQueue(): List<PlaybackTrack> = queue.toList()

    fun playQueueIndex(index: Int) {
        dispatchToMain {
            if (index !in queue.indices) {
                message = "That track is no longer in the queue."
                publishSnapshot()
                return@dispatchToMain
            }
            startService(Intent(this, PlaybackService::class.java).setAction(ACTION_START))
            ensureForeground()
            message = null
            resumeOnFocusGain = false
            startTrack(index)
        }
    }

    fun removeFromQueue(index: Int): Boolean {
        if (index !in queue.indices) return false
        dispatchToMain { removeFromQueueOnMain(index) }
        return true
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex == toIndex) return false
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return false
        dispatchToMain { moveQueueItemOnMain(fromIndex, toIndex) }
        return true
    }

    fun clearUpcoming() {
        dispatchToMain {
            if (currentIndex !in queue.indices) {
                message = "No track is selected."
                publishSnapshot()
                return@dispatchToMain
            }
            if (currentIndex >= queue.lastIndex) {
                message = "There is nothing queued after the current track."
                publishSnapshot()
                return@dispatchToMain
            }
            queue = queue.take(currentIndex + 1)
            message = null
            persistPlaybackState()
            publishSnapshot()
        }
    }

    fun togglePlayback() {
        dispatchToMain {
            if (playing) {
                pausePlayer()
            } else if (prepared && player != null) {
                startPlayer()
            } else if (currentIndex in queue.indices) {
                startTrack(currentIndex)
            } else {
                message = "No track is selected."
                publishSnapshot()
            }
        }
    }

    fun playPrevious() {
        dispatchToMain {
            if (queue.isEmpty()) {
                message = "No track is selected."
                publishSnapshot()
                return@dispatchToMain
            }
            if (prepared && safePosition() > RESTART_THRESHOLD_MS) {
                seekInternal(0)
                return@dispatchToMain
            }
            val previous = when {
                shuffled && queue.size > 1 -> randomIndexExcept(currentIndex)
                currentIndex > 0 -> currentIndex - 1
                repeat == REPEAT_ALL -> queue.lastIndex
                else -> currentIndex
            }
            startTrack(previous)
        }
    }

    fun playNext() {
        dispatchToMain { advanceToNext(manual = true) }
    }

    fun seekTo(positionMs: Int) {
        dispatchToMain {
            if (!prepared || player == null) return@dispatchToMain
            seekInternal(positionMs.coerceAtLeast(0))
        }
    }

    fun setShuffle(enabled: Boolean) {
        dispatchToMain {
            shuffled = enabled
            message = null
            persistPlaybackState()
            publishSnapshot()
        }
    }

    fun setRepeatMode(mode: Int) {
        dispatchToMain {
            if (mode !in REPEAT_OFF..REPEAT_ONE) {
                message = "Repeat mode must be 0 (off), 1 (all), or 2 (one)."
            } else {
                repeat = mode
                message = null
            }
            persistPlaybackState()
            publishSnapshot()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        dispatchToMain {
            if (!speed.isFinite()) {
                message = "Playback speed must be a finite number."
                publishSnapshot()
                return@dispatchToMain
            }
            playbackSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
            message = null
            if (prepared && player != null) applyPlaybackSpeed()
            persistPlaybackState()
            publishSnapshot()
        }
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        dispatchToMain {
            if (minutes != null && minutes !in 1..MAX_SLEEP_MINUTES) {
                message = "Sleep timer must be between 1 and $MAX_SLEEP_MINUTES minutes."
                publishSnapshot()
                return@dispatchToMain
            }
            cancelSleepTimer()
            if (minutes != null) {
                sleepDeadlineElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
                lastSleepMinutes = minutes
                val task = Runnable {
                    sleepTask = null
                    sleepDeadlineElapsed = null
                    lastSleepMinutes = null
                    pausePlayer()
                    releasePlayer()
                    message = "Sleep timer finished; playback was stopped."
                    stopForegroundCompat()
                    publishSnapshot()
                    stopSelf()
                }
                sleepTask = task
                mainHandler.postDelayed(task, minutes * 60_000L)
                message = null
            } else {
                message = "Sleep timer cancelled."
            }
            publishSnapshot()
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        dispatchToMain {
            equalizerRequested = enabled
            if (enabled && prepared) initializeEqualizer()
            equalizer?.let {
                try {
                    it.enabled = enabled
                    message = null
                } catch (_: Exception) {
                    disableUnsupportedEqualizer()
                }
            } ?: run {
                if (enabled && prepared) disableUnsupportedEqualizer()
                else message = null
            }
            publishSnapshot()
        }
    }

    fun setEqualizerLevel(level: Int) {
        dispatchToMain {
            equalizerLevel = level.coerceIn(0, 100)
            if (equalizer != null) applyEqualizerLevel()
            else if (prepared && equalizerRequested) initializeEqualizer()
            message = if (equalizerRequested && equalizer == null && prepared) {
                message ?: "Equalizer is not supported on this device."
            } else {
                null
            }
            publishSnapshot()
        }
    }

    private fun createMediaSession() {
        val session = MediaSession(this, SESSION_TAG)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                dispatchToMain {
                    if (!playing) {
                        if (prepared && player != null) startPlayer()
                        else if (currentIndex in queue.indices) startTrack(currentIndex)
                    }
                }
            }
            override fun onPause() {
                dispatchToMain {
                    if (playing) pausePlayer()
                }
            }
            override fun onSkipToPrevious() = playPrevious()
            override fun onSkipToNext() = playNext()
            override fun onSeekTo(pos: Long) = seekTo(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }, mainHandler)
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        mediaSession = session
        session.isActive = true
    }

    private fun startTrack(index: Int, autoStart: Boolean = true) {
        if (index !in queue.indices) {
            message = "No playable track is selected."
            publishSnapshot()
            return
        }
        releasePlayer()
        currentIndex = index
        historyRecordedForCurrentTrack = false
        prepared = false
        playing = false
        message = null
        pendingResumeIndex = -1
        pendingResumePlaying = false
        persistPlaybackState(positionMs = 0)
        val track = queue[index]
        val parsedUri = runCatching { Uri.parse(track.uri) }.getOrNull()
        if (track.uri.isBlank() || parsedUri?.scheme.isNullOrBlank()) {
            message = "Invalid track URI."
            publishSnapshot()
            return
        }
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            updateMediaMetadata()
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mediaPlayer.setOnPreparedListener { preparedPlayer ->
                if (player !== preparedPlayer) return@setOnPreparedListener
                prepared = true
                message = null
                updateMediaMetadata()
                initializeEqualizerIfRequested()
                applyPlaybackSpeed()
                val pendingSeek = resumePositionMs
                resumePositionMs = null
                if (pendingSeek != null && pendingSeek > 0) {
                    runCatching { preparedPlayer.seekTo(pendingSeek) }
                }
                if (autoStart) {
                    requestAudioFocusAndPlay()
                } else {
                    playing = false
                    updatePlaybackState()
                }
                publishSnapshot()
            }
            mediaPlayer.setOnCompletionListener { completed ->
                if (player === completed) onTrackCompleted()
            }
            mediaPlayer.setOnErrorListener { failed, what, extra ->
                if (player === failed) {
                    message = "Playback failed (error $what, $extra)."
                    playing = false
                    prepared = false
                    releasePlayer()
                    stopTicker()
                    abandonAudioFocus()
                    publishSnapshot()
                }
                true
            }
            mediaPlayer.setDataSource(this, parsedUri!!)
            mediaPlayer.prepareAsync()
            publishSnapshot()
        } catch (error: Exception) {
            message = "Unable to open track: ${error.localizedMessage ?: error.javaClass.simpleName}"
            releasePlayer()
            prepared = false
            playing = false
            abandonAudioFocus()
            publishSnapshot()
        }
    }

    private fun onTrackCompleted() {
        when (repeat) {
            REPEAT_ONE -> {
                val activePlayer = player ?: return
                try {
                    activePlayer.seekTo(0)
                    startPlayer()
                    persistPlaybackState(positionMs = 0)
                } catch (error: Exception) {
                    handlePlaybackFailure("Unable to repeat track", error)
                }
            }
            else -> advanceToNext(manual = false)
        }
    }

    private fun advanceToNext(manual: Boolean) {
        if (queue.isEmpty()) {
            if (manual) {
                message = "No track is selected."
                publishSnapshot()
            }
            return
        }
        val next = when {
            shuffled && queue.size > 1 -> randomIndexExcept(currentIndex)
            currentIndex + 1 < queue.size -> currentIndex + 1
            repeat == REPEAT_ALL -> 0
            else -> -1
        }
        if (next < 0) {
            playing = false
            prepared = false
            releasePlayer()
            stopTicker()
            abandonAudioFocus()
            message = if (manual) "End of queue." else "Playback finished."
            stopForegroundCompat()
            persistPlaybackState(positionMs = 0)
            publishSnapshot()
            stopSelf()
            return
        }
        startTrack(next)
    }

    /**
     * Removes [index] from the queue. Runs on the main thread, like every other queue mutation.
     * When the removed entry is the current track, playback continues on the following stored entry
     * (or a random one while shuffled, or index 0 with [REPEAT_ALL]) and stops coherently when the
     * queue runs out. An emptied queue releases the player instead of crashing.
     */
    private fun removeFromQueueOnMain(index: Int) {
        if (index !in queue.indices) return
        val removingCurrent = index == currentIndex
        val updated = queue.toMutableList().also { it.removeAt(index) }
        if (updated.isEmpty()) {
            queue = emptyList()
            currentIndex = -1
            releasePlayer()
            stopTicker()
            abandonAudioFocus()
            message = "The queue is empty."
            stopForegroundCompat()
            persistPlaybackState()
            publishSnapshot()
            return
        }
        queue = updated
        if (!removingCurrent) {
            if (index < currentIndex) currentIndex -= 1
            currentIndex = currentIndex.coerceIn(0, updated.lastIndex)
            persistPlaybackState()
            publishSnapshot()
            return
        }
        val wasPlaying = playing
        val next = when {
            shuffled && updated.size > 1 -> Random.nextInt(updated.size)
            index < updated.size -> index
            repeat == REPEAT_ALL -> 0
            else -> -1
        }
        if (next < 0) {
            currentIndex = updated.lastIndex
            releasePlayer()
            stopTicker()
            abandonAudioFocus()
            message = "End of queue."
            stopForegroundCompat()
            persistPlaybackState()
            publishSnapshot()
            return
        }
        currentIndex = next
        message = null
        resumeOnFocusGain = false
        startTrack(next, autoStart = wasPlaying)
    }

    /**
     * Moves the entry at [fromIndex] to [toIndex] in stored order, dragging [currentIndex] along
     * when the moved entry is the one that is playing.
     */
    private fun moveQueueItemOnMain(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        val updated = queue.toMutableList()
        val moved = updated.removeAt(fromIndex)
        updated.add(toIndex, moved)
        queue = updated
        currentIndex = when (currentIndex) {
            fromIndex -> toIndex
            else -> {
                var shifted = if (fromIndex < currentIndex) currentIndex - 1 else currentIndex
                if (shifted >= toIndex) shifted += 1
                shifted
            }
        }.coerceIn(0, updated.lastIndex)
        persistPlaybackState()
        publishSnapshot()
    }

    private fun requestAudioFocusAndPlay() {
        startPlayer()
    }

    private fun startPlayer() {
        val activePlayer = player ?: return
        if (!prepared) return
        if (!hasAudioFocus && !requestFocusOnly()) return
        try {
            activePlayer.start()
            playing = true
            message = null
            if (!historyRecordedForCurrentTrack) {
                historyRecordedForCurrentTrack = true
                recordPlaybackHistory(queue[currentIndex])
            }
            ensureForeground()
            startTicker()
            publishSnapshot()
        } catch (error: Exception) {
            handlePlaybackFailure("Unable to start playback", error)
        }
    }

    private fun recordPlaybackHistory(track: PlaybackTrack) {
        val playedAt = System.currentTimeMillis()
        historyExecutor.execute {
            try {
                playlistStore.recordPlayback(
                    trackUri = track.uri,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    playedAt = playedAt,
                )
                mainHandler.post { listener?.onPlaybackHistoryChanged() }
            } catch (error: Exception) {
                android.util.Log.e("PlaybackService", "Could not save playback history", error)
                mainHandler.post { listener?.onPlaybackHistoryError() }
            }
        }
    }

    private fun requestFocusOnly(): Boolean {
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(focusListener, mainHandler)
                    .build()
                    .also { focusRequest = it }
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        } catch (error: Exception) {
            hasAudioFocus = false
            message = "Unable to request audio focus: ${error.localizedMessage ?: error.javaClass.simpleName}"
            publishSnapshot()
            return false
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            message = "Audio focus is unavailable; playback is paused."
            publishSnapshot()
        }
        return hasAudioFocus
    }

    private fun pausePlayer(abandonFocus: Boolean = true) {
        val activePlayer = player
        if (playing && activePlayer != null) {
            runCatching { activePlayer.pause() }.onFailure {
                message = "Unable to pause playback: ${it.localizedMessage ?: it.javaClass.simpleName}"
            }
        }
        playing = false
        stopTicker()
        if (abandonFocus) {
            resumeOnFocusGain = false
            abandonAudioFocus()
        }
        persistPlaybackState()
        publishSnapshot()
    }

    private fun seekInternal(positionMs: Int) {
        val activePlayer = player ?: return
        try {
            val duration = runCatching { activePlayer.duration }.getOrDefault(Int.MAX_VALUE)
            activePlayer.seekTo(positionMs.coerceAtMost(duration.coerceAtLeast(0)))
            message = null
            publishSnapshot()
        } catch (error: Exception) {
            handlePlaybackFailure("Unable to seek", error)
        }
    }

    private fun applyPlaybackSpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val activePlayer = player ?: return
        if (!prepared) return
        try {
            val params = PlaybackParams().setSpeed(playbackSpeed)
            activePlayer.playbackParams = params
            message = null
        } catch (error: Exception) {
            message = "Playback speed is unavailable: ${error.localizedMessage ?: error.javaClass.simpleName}"
        }
    }

    private fun initializeEqualizerIfRequested() {
        if (equalizerRequested) initializeEqualizer()
    }

    private fun initializeEqualizer() {
        val sessionId = player?.audioSessionId ?: return
        if (equalizer != null) return
        try {
            val effect = Equalizer(0, sessionId)
            equalizer = effect
            applyEqualizerLevel()
            effect.enabled = equalizerRequested
            message = null
        } catch (_: Exception) {
            disableUnsupportedEqualizer()
        } catch (_: LinkageError) {
            disableUnsupportedEqualizer()
        }
    }

    private fun applyEqualizerLevel() {
        val effect = equalizer ?: return
        try {
            val range = effect.bandLevelRange
            val target = range[0] + ((range[1] - range[0]) * equalizerLevel / 100f).toInt()
            for (band in 0 until effect.numberOfBands) effect.setBandLevel(band.toShort(), target.toShort())
        } catch (error: Exception) {
            message = "Unable to adjust equalizer: ${error.localizedMessage ?: error.javaClass.simpleName}"
        }
    }

    private fun disableUnsupportedEqualizer() {
        equalizerRequested = false
        runCatching { equalizer?.release() }
        equalizer = null
        message = "Equalizer is not supported on this device."
    }

    private fun updateMediaMetadata() {
        val track = queue.getOrNull(currentIndex) ?: return
        mediaSession?.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(
                    android.media.MediaMetadata.METADATA_KEY_DURATION,
                    if (prepared) player?.duration?.toLong() ?: track.durationMs else track.durationMs,
                )
                .apply {
                    track.artwork?.let {
                        putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, it)
                        putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                    }
                }
                .build(),
        )
    }

    private fun updatePlaybackState() {
        val actions =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SEEK_TO
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            prepared -> PlaybackState.STATE_PAUSED
            currentIndex in queue.indices -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_STOPPED
        }
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, safePosition().toLong(), if (playing) playbackSpeed else 0f)
                .build(),
        )
    }

    private fun ensureForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(): Notification {
        val track = queue.getOrNull(currentIndex)
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingIntentFlags(),
        )
        val previousIntent = servicePendingIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS)
        val toggleIntent = servicePendingIntent(ACTION_TOGGLE, REQUEST_TOGGLE)
        val nextIntent = servicePendingIntent(ACTION_NEXT, REQUEST_NEXT)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(track?.artist ?: message ?: "Playback")
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                "Previous",
                previousIntent,
            ).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                ),
                if (playing) "Pause" else "Play",
                toggleIntent,
            ).build())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                "Next",
                nextIntent,
            ).build())
        track?.artwork?.let { builder.setLargeIcon(it) }
        builder.setStyle(
            Notification.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2),
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            pendingIntentFlags(),
        )

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Media playback controls" },
            )
        }
    }

    private fun publishSnapshot() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { publishSnapshot() }
            return
        }
        val activePlayer = player
        val duration = if (prepared && activePlayer != null) {
            runCatching { activePlayer.duration }.getOrDefault(0)
        } else {
            queue.getOrNull(currentIndex)?.durationMs?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        }
        val position = if (prepared && activePlayer != null) {
            runCatching { activePlayer.currentPosition }.getOrDefault(0)
        } else {
            0
        }
        val minutesRemaining = sleepDeadlineElapsed?.let {
            ceil((it - SystemClock.elapsedRealtime()).coerceAtLeast(0) / 60_000.0)
                .toInt()
                .coerceAtLeast(0)
        }
        snapshot = PlaybackSnapshot(
            track = queue.getOrNull(currentIndex),
            index = currentIndex,
            queueSize = queue.size,
            positionMs = position.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            isPlaying = playing,
            isPrepared = prepared,
            shuffle = shuffled,
            repeatMode = repeat,
            speed = playbackSpeed,
            sleepMinutesRemaining = minutesRemaining,
            equalizerEnabled = runCatching { equalizer?.enabled == true }.getOrDefault(false),
            equalizerLevel = equalizerLevel,
            message = message,
        )
        updatePlaybackState()
        runCatching { listener?.onPlaybackChanged(snapshot) }
        if (isForeground) {
            runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification()) }
        }
        if (minutesRemaining != lastSleepMinutes) lastSleepMinutes = minutesRemaining
    }

    private fun safePosition(): Int =
        if (prepared) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0).coerceAtLeast(0) else 0

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(ticker)
    }

    private fun cancelSleepTimer() {
        sleepTask?.let(mainHandler::removeCallbacks)
        sleepTask = null
        sleepDeadlineElapsed = null
        lastSleepMinutes = null
    }

    private fun releasePlayer() {
        stopTicker()
        equalizer?.let { runCatching { it.release() } }
        equalizer = null
        player?.let { active ->
            active.setOnPreparedListener(null)
            active.setOnCompletionListener(null)
            active.setOnErrorListener(null)
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        player = null
        prepared = false
        playing = false
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
        hasAudioFocus = false
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundStarted = false
    }

    private fun handlePlaybackFailure(prefix: String, error: Exception) {
        message = "$prefix: ${error.localizedMessage ?: error.javaClass.simpleName}"
        releasePlayer()
        abandonAudioFocus()
        publishSnapshot()
    }

    private fun dispatchToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Writes queue, index, position, shuffle, repeat and speed to SharedPreferences.
     * Never runs while a restore is in progress, and never from the position ticker.
     */
    private fun persistPlaybackState(positionMs: Int = safePosition()) {
        if (restoringPersistedState) return
        val position = positionMs.coerceAtLeast(0)
        playbackPrefs.edit()
            .putString(KEY_QUEUE_URIS, queue.joinToString(QUEUE_URI_SEPARATOR) { it.uri })
            .putInt(KEY_QUEUE_SIZE, queue.size)
            .putInt(KEY_CURRENT_INDEX, currentIndex)
            .putInt(KEY_POSITION_MS, position)
            .putBoolean(KEY_SHUFFLE, shuffled)
            .putInt(KEY_REPEAT_MODE, repeat)
            .putFloat(KEY_PLAYBACK_SPEED, playbackSpeed)
            .putBoolean(KEY_WAS_PLAYING, playing)
            .apply()
    }

    /**
     * Rebuilds the queue, index, position, shuffle, repeat and speed after a service restart or
     * process death. A launch with no persisted queue stays empty, so [onStartCommand] keeps its
     * "no queue -> stop" behaviour. Playback itself is left to [resumePersumedState], which
     * prepares the restored track once and seeks to the persisted position.
     */
    private fun restorePersistedState() {
        restoringPersistedState = true
        try {
            if (!playbackPrefs.contains(KEY_QUEUE_SIZE)) return
            val persistedSize = playbackPrefs.getInt(KEY_QUEUE_SIZE, 0)
            val persistedUris = playbackPrefs.getString(KEY_QUEUE_URIS, null)
                ?.split(QUEUE_URI_SEPARATOR)
                ?.filter { it.isNotBlank() }
                ?: return
            val persistedIndex = playbackPrefs.getInt(KEY_CURRENT_INDEX, -1)
            if (persistedSize <= 0 || persistedSize != persistedUris.size) return
            if (persistedIndex !in persistedUris.indices) {
                playbackPrefs.edit().remove(KEY_QUEUE_URIS).remove(KEY_QUEUE_SIZE).apply()
                return
            }
            queue = persistedUris.map { uri ->
                PlaybackTrack(
                    uri = uri,
                    title = uri.substringAfterLast('/').ifBlank { uri },
                    artist = "",
                    album = "",
                    durationMs = 0L,
                    artwork = null,
                )
            }
            currentIndex = persistedIndex
            shuffled = playbackPrefs.getBoolean(KEY_SHUFFLE, false)
            repeat = playbackPrefs.getInt(KEY_REPEAT_MODE, REPEAT_OFF).coerceIn(REPEAT_OFF, REPEAT_ONE)
            playbackSpeed = playbackPrefs.getFloat(KEY_PLAYBACK_SPEED, 1f).coerceIn(MIN_SPEED, MAX_SPEED)
            resumePositionMs = playbackPrefs.getInt(KEY_POSITION_MS, 0)
                .coerceIn(0, Int.MAX_VALUE).takeIf { it > 0 }
            pendingResumeIndex = persistedIndex
            pendingResumePlaying = playbackPrefs.getBoolean(KEY_WAS_PLAYING, false)
            message = null
        } finally {
            restoringPersistedState = false
        }
    }

    /**
     * Prepares the restored track once, seeking to the persisted position. Playback only starts
     * when the service was playing before it was destroyed; otherwise the player stays paused at
     * the restored position.
     */
    private fun resumePersistedState() {
        if (pendingResumeIndex !in queue.indices) {
            pendingResumeIndex = -1
            pendingResumePlaying = false
            return
        }
        val index = pendingResumeIndex
        val autoStart = pendingResumePlaying
        pendingResumeIndex = -1
        pendingResumePlaying = false
        startTrack(index, autoStart = autoStart)
    }

    private fun randomIndexExcept(excluded: Int): Int {
        if (queue.size <= 1) return currentIndex.coerceAtLeast(0)
        var choice: Int
        do {
            choice = Random.nextInt(queue.size)
        } while (choice == excluded)
        return choice
    }

    private val isForeground: Boolean
        get() = foregroundStarted

    private var foregroundStarted = false

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val SESSION_TAG = "ytdlp-playback"
        private const val NOTIFICATION_ID = 4102
        private const val REQUEST_CONTENT = 4103
        private const val REQUEST_PREVIOUS = 4104
        private const val REQUEST_TOGGLE = 4105
        private const val REQUEST_NEXT = 4106
        private const val ACTION_START = "io.github.ytdlpandroid.playback.START"
        private const val ACTION_PREVIOUS = "io.github.ytdlpandroid.playback.PREVIOUS"
        private const val ACTION_TOGGLE = "io.github.ytdlpandroid.playback.TOGGLE"
        private const val ACTION_NEXT = "io.github.ytdlpandroid.playback.NEXT"
        private const val TICK_INTERVAL_MS = 500L
        private const val RESTART_THRESHOLD_MS = 3_000
        private const val REPEAT_OFF = 0
        private const val REPEAT_ALL = 1
        private const val REPEAT_ONE = 2
        private const val DEFAULT_EQUALIZER_LEVEL = 50
        private const val MAX_SLEEP_MINUTES = 240
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 2f
        private const val PREFS_NAME = "playback_state"
        private const val QUEUE_URI_SEPARATOR = "\n"
        private const val KEY_QUEUE_URIS = "queue_uris"
        private const val KEY_QUEUE_SIZE = "queue_size"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_WAS_PLAYING = "was_playing"

        private fun emptySnapshot() = PlaybackSnapshot(
            track = null,
            index = -1,
            queueSize = 0,
            positionMs = 0,
            durationMs = 0,
            isPlaying = false,
            isPrepared = false,
            shuffle = false,
            repeatMode = REPEAT_OFF,
            speed = 1f,
            sleepMinutesRemaining = null,
            equalizerEnabled = false,
            equalizerLevel = DEFAULT_EQUALIZER_LEVEL,
            message = null,
        )
    }
}
