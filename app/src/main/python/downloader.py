import json
import logging
import math
import os
import threading
import time
from collections import OrderedDict
from importlib import import_module
from typing import Any
from urllib.parse import urlsplit

YoutubeDL: Any = import_module("yt_dlp").YoutubeDL
LOGGER = logging.getLogger(__name__)

MAX_URL_COUNT = 100
MAX_URL_BYTES = 8192
MAX_BATCH_BYTES = 65536
SAFE_THUMBNAIL_HOSTS = {"i.ytimg.com"}
MAX_SEARCH_SESSIONS = 4


class DownloadCancelled(Exception):
    """Raised when the active yt-dlp operation is canceled."""


class EventLogger:
    def __init__(self, callback):
        self.callback = callback

    def debug(self, message):
        LOGGER.debug("%s", message)

    def info(self, message):
        LOGGER.info("%s", message)

    def warning(self, message):
        self._emit("warning", message)

    def error(self, message):
        self._emit("error", message)

    def _emit(self, event, message):
        _emit_event(self.callback, event, message=str(message))


def _emit_event(callback, event, **details):
    try:
        callback.accept(json.dumps({"event": event, **details}, ensure_ascii=False, allow_nan=False))
    except Exception as error:  # noqa: BLE001 - notification callback failures must not abort a download
        LOGGER.warning("Unable to deliver yt-dlp %s event: %s", event, error)


def _finite_number(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    try:
        if not math.isfinite(value):
            return None
    except OverflowError:
        return value if isinstance(value, int) else None
    return value


def _safe_text(value, max_length=1024):
    if not isinstance(value, str):
        return None
    normalized = "".join(character for character in value if character.isprintable()).strip()
    return normalized[:max_length] or None


def _safe_thumbnail_url(value):
    if not isinstance(value, str) or not value:
        return None
    value = value.strip()
    if any(not character.isprintable() or character.isspace() for character in value):
        return None
    try:
        parsed = urlsplit(value)
        if (
            parsed.scheme.lower() != "https"
            or parsed.hostname not in SAFE_THUMBNAIL_HOSTS
            or parsed.username is not None
            or parsed.password is not None
            or parsed.port not in (None, 443)
        ):
            return None
    except ValueError:
        return None
    return value


def _thumbnail_from_info(info):
    thumbnail = _safe_thumbnail_url(info.get("thumbnail"))
    if thumbnail:
        return thumbnail

    thumbnails = info.get("thumbnails")
    if not isinstance(thumbnails, list):
        return None
    candidates = []
    for index, item in enumerate(thumbnails):
        if not isinstance(item, dict):
            continue
        url = _safe_thumbnail_url(item.get("url"))
        if not url:
            continue
        width = _finite_number(item.get("width"))
        height = _finite_number(item.get("height"))
        resolution = width * height if width and height and width > 0 and height > 0 else 0
        candidates.append((resolution, index, url))
    return max(candidates, default=(0, 0, None))[2]


def _track_metadata(info):
    info = info if isinstance(info, dict) else {}
    title = _safe_text(info.get("track")) or _safe_text(info.get("track_title")) or _safe_text(info.get("title"))
    artist = _safe_text(info.get("artist"))
    if not artist:
        artists = info.get("artists")
        if isinstance(artists, list):
            artist = _safe_text(", ".join(value for value in artists if isinstance(value, str)))
    artist = artist or _safe_text(info.get("uploader")) or _safe_text(info.get("channel"))
    return {
        "title": title,
        "artist": artist,
        "album": _safe_text(info.get("album")),
        "duration": _finite_number(info.get("duration")),
        "thumbnail": _thumbnail_from_info(info),
    }


def _validate_urls(urls_json):
    try:
        urls = json.loads(urls_json)
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError("The URL batch is invalid") from error

    if not isinstance(urls, list) or not urls:
        raise ValueError("Enter at least one URL")
    if len(urls) > MAX_URL_COUNT:
        raise ValueError(f"A batch can contain at most {MAX_URL_COUNT} URLs")
    if any(not isinstance(url, str) for url in urls):
        raise TypeError("Every URL must be text")

    urls = [url.strip() for url in urls]
    byte_lengths = [len(url.encode("utf-8")) for url in urls]
    if any(size == 0 or size > MAX_URL_BYTES for size in byte_lengths):
        raise ValueError(f"Each URL must be between 1 and {MAX_URL_BYTES} bytes")
    if sum(byte_lengths) > MAX_BATCH_BYTES:
        raise ValueError("The total URL batch is too large")

    for url in urls:
        try:
            parsed = urlsplit(url)
            hostname = parsed.hostname
        except ValueError as error:
            raise ValueError("One or more URLs are malformed") from error
        if parsed.scheme not in ("https", "http") or not hostname:
            raise ValueError("Each entry must be an http:// or https:// URL")
        if parsed.username is not None or parsed.password is not None:
            raise ValueError("URLs must not include embedded usernames or passwords")
        try:
            port = parsed.port
        except ValueError as error:
            raise ValueError("One or more URLs have an invalid port") from error
        if port is not None and not 1 <= port <= 65535:
            raise ValueError("One or more URLs have an invalid port")

    return urls


def _is_playable_video(entry):
    """True when a flat search entry is a YouTube video we can actually play.

    Search pages mix in channels and playlists, so paging has to skip them; doing it here
    keeps the offset/limit window meaningful.
    """
    if not isinstance(entry, dict):
        return False
    if entry.get("ie_key") not in (None, "Youtube"):
        return False
    video_id = entry.get("id")
    title = entry.get("title")
    if not isinstance(video_id, str) or not video_id:
        return False
    if not isinstance(title, str) or not title:
        return False
    webpage_url = entry.get("webpage_url") or f"https://www.youtube.com/watch?v={video_id}"
    try:
        parsed = urlsplit(webpage_url)
    except ValueError:
        return False
    return parsed.scheme == "https" and parsed.hostname in {
        "www.youtube.com", "youtube.com", "music.youtube.com",
    }


class _SearchSession:
    """An open YouTube search whose result stream is consumed incrementally.

    Re-running ``ytsearchN`` for every page re-downloads all earlier results, so page N costs
    N requests. Keeping the lazy ``ytsearchall`` iterator alive means each further page only
    fetches YouTube's next continuation, which is what makes endless scrolling feel instant.
    """

    def __init__(self, query, callback):
        self.lock = threading.Lock()
        self.entries = []
        self.exhausted = False
        self.ydl = YoutubeDL({
            "extract_flat": True,
            "skip_download": True,
            "quiet": True,
            "no_warnings": False,
            "logger": EventLogger(callback),
        })
        info = self.ydl.extract_info(f"ytsearchall:{query}", download=False, process=False)
        self.iterator = iter((info or {}).get("entries") or [])

    def fill(self, count):
        """Collect the next playable results until at least [count] are buffered.

        Only entries that are playable videos are buffered. Filtering while filling rather
        than after slicing is what keeps paging alive: a page of channels or playlists used
        to consume an offset and return nothing, which looked like the end of the results.
        """
        while len(self.entries) < count and not self.exhausted:
            try:
                entry = next(self.iterator)
            except StopIteration:
                self.exhausted = True
                break
            if _is_playable_video(entry):
                self.entries.append(entry)
        return self.entries

    def close(self):
        close = getattr(self.ydl, "close", None)
        if callable(close):
            try:
                close()
            except Exception:  # noqa: BLE001 - closing is best effort
                pass


_search_sessions = OrderedDict()
_search_sessions_lock = threading.Lock()


def _search_session(query, callback, fresh):
    with _search_sessions_lock:
        session = _search_sessions.get(query)
        if session is not None and not fresh:
            _search_sessions.move_to_end(query)
            return session
        if session is not None:
            session.close()
            del _search_sessions[query]
    session = _SearchSession(query, callback)
    with _search_sessions_lock:
        _search_sessions[query] = session
        while len(_search_sessions) > MAX_SEARCH_SESSIONS:
            _, evicted = _search_sessions.popitem(last=False)
            evicted.close()
    return session


def search_music(query, callback, offset=0, limit=20):
    """Search YouTube with a bounded offset/limit and return normalized results."""
    if not isinstance(query, str):
        raise TypeError("Search query must be text")
    query = query.strip()
    if not query or len(query.encode("utf-8")) > 512:
        raise ValueError("Enter a search query no longer than 512 bytes")
    if isinstance(offset, bool) or not isinstance(offset, int) or offset < 0:
        raise ValueError("Search offset must be a non-negative integer")
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 50:
        raise ValueError("Search page size must be between 1 and 50")
    end = offset + limit

    results = []
    # Offset 0 is a new search: start a fresh session so results are current.
    session = _search_session(query, callback, fresh=offset == 0)
    with session.lock:
        entries = list(session.fill(end)[offset:end])
    for entry in entries:
        # Already filtered while filling; normalise for JSON.
        webpage_url = entry.get("webpage_url") or f"https://www.youtube.com/watch?v={entry['id']}"
        results.append({
            "id": entry["id"],
            "title": entry["title"],
            "url": webpage_url,
            "uploader": entry.get("uploader") or entry.get("channel") or "",
            "duration": _finite_number(entry.get("duration")),
            "thumbnail": _thumbnail_from_info(entry),
        })
    return json.dumps(results, ensure_ascii=False, allow_nan=False)


def download_urls(urls_json, output_dir, job_id, format_mode, allow_playlist, callback, cancel_signal, pause_signal):
    """Download URL batches with yt-dlp and report progress as JSON events."""
    urls = _validate_urls(urls_json)
    formats = {
        "best": "best[acodec!=none][vcodec!=none]/best",
        "1080p": "best[height<=1080][acodec!=none][vcodec!=none]",
        "720p": "best[height<=720][acodec!=none][vcodec!=none]",
        "480p": "best[height<=480][acodec!=none][vcodec!=none]",
        "audio": "bestaudio",
        "audio_mp3": "bestaudio",
    }
    if format_mode not in formats:
        raise ValueError("Unknown format selection")
    if not isinstance(allow_playlist, bool):
        raise TypeError("Playlist setting must be boolean")

    output_dir = os.path.realpath(os.fspath(output_dir))
    os.makedirs(output_dir, exist_ok=True)
    completed_downloads = []

    def emit(event, **details):
        _emit_event(callback, event, **details)

    def check_cancelled():
        if cancel_signal.getAsBoolean():
            raise DownloadCancelled("Download canceled")

    def wait_if_paused():
        was_paused = False
        while pause_signal.getAsBoolean():
            check_cancelled()
            if not was_paused:
                emit("paused")
                was_paused = True
            time.sleep(0.2)
        check_cancelled()
        if was_paused:
            emit("resumed")

    def progress_hook(data):
        check_cancelled()
        wait_if_paused()
        status = data.get("status")
        if status == "downloading":
            downloaded = data.get("downloaded_bytes") or 0
            total = data.get("total_bytes") or data.get("total_bytes_estimate")
            downloaded = _finite_number(downloaded)
            total = _finite_number(total)
            percent = None
            if downloaded is not None and total:
                try:
                    if downloaded >= total:
                        percent = 100.0
                    elif isinstance(downloaded, int) and isinstance(total, int):
                        percent = downloaded * 100.0 / total
                    else:
                        percent = downloaded * 100.0 / total
                    percent = min(100.0, max(0.0, percent))
                except (OverflowError, ZeroDivisionError):
                    percent = None
            info = data.get("info_dict") or {}
            title = info.get("title")
            emit(
                "progress",
                downloaded=max(0, min(int(downloaded or 0), 2**63 - 1)),
                total=max(0, min(int(total), 2**63 - 1)) if total is not None else None,
                percent=percent,
                speed=_finite_number(data.get("speed")),
                eta=_finite_number(data.get("eta")),
                title=title if isinstance(title, str) else None,
            )
        elif status == "finished":
            filename = data.get("filename")
            if filename:
                completed_downloads.append((os.fspath(filename), _track_metadata(data.get("info_dict"))))
            emit("processing", message="Finishing download…")

    options = {
        # Keep outputs directly downloadable without depending on ffmpeg.
        "format": formats[format_mode],
        "noplaylist": not allow_playlist,
        "outtmpl": os.path.join(output_dir, f"%(title).180B [%(id)s] [job-{int(job_id)}].%(ext)s"),
        "continuedl": True,
        "overwrites": False,
        "concurrent_fragment_downloads": 4,
        "buffersize": 1024 * 1024,
        "noprogress": True,
        "progress_delta": 1.0,
        "quiet": True,
        "no_warnings": False,
        "logger": EventLogger(callback),
        "progress_hooks": [progress_hook],
    }

    try:
        with YoutubeDL(options) as ydl:
            check_cancelled()
            wait_if_paused()
            error_count = ydl.download(urls)
    except Exception:
        if cancel_signal.getAsBoolean():
            raise DownloadCancelled("Download canceled")
        raise

    check_cancelled()
    safe_paths = []
    tracks = []
    for filename, metadata in completed_downloads:
        filepath = os.path.realpath(filename)
        try:
            is_inside_output = os.path.commonpath((output_dir, filepath)) == output_dir
        except ValueError:
            is_inside_output = False
        if not is_inside_output:
            raise RuntimeError("yt-dlp returned a file outside the download directory")
        if os.path.isfile(filepath) and filepath not in safe_paths:
            safe_paths.append(filepath)
            tracks.append({"path": filepath, **metadata})

    if not safe_paths:
        if error_count:
            raise RuntimeError(f"yt-dlp could not download any URLs ({error_count} error(s))")
        raise RuntimeError("yt-dlp finished without producing files")
    return json.dumps({"files": safe_paths, "errors": int(error_count or 0), "tracks": tracks}, allow_nan=False)
