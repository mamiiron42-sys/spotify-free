from dataclasses import dataclass, field  # noqa: I001
import importlib.util
import json
import pathlib
import sys
import tempfile
import types
from typing import Any
import unittest
from unittest.mock import patch


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
DOWNLOADER_PATH = PROJECT_ROOT / "app" / "src" / "main" / "python" / "downloader.py"


@dataclass
class FakeState:
    options: dict[str, Any] | None = None
    result_code: int = 0
    fail_urls: set[str] = field(default_factory=set)
    escaped_path: str | None = None
    calls: list[list[str]] = field(default_factory=list)
    options_history: list[dict[str, Any]] = field(default_factory=list)
    track_info: dict[str, Any] = field(default_factory=dict)
    search_info: dict[str, Any] | None = None
    search_calls: list[str] = field(default_factory=list)


FAKE_STATE = FakeState()


class FakeCallback:
    def __init__(self):
        self.events: list[dict[str, Any]] = []

    def accept(self, value: str) -> None:
        self.events.append(json.loads(value))


class FakeCancelSignal:
    def __init__(self, canceled: bool | list[bool] = False):
        self.canceled = canceled
        self.check_count = 0

    def getAsBoolean(self) -> bool:
        if isinstance(self.canceled, list):
            index = min(self.check_count, len(self.canceled) - 1)
            self.check_count += 1
            return self.canceled[index]
        return self.canceled


class FakeYoutubeDL:
    def __init__(self, options: dict[str, Any]):
        self.options = options
        FAKE_STATE.options = options
        FAKE_STATE.options_history.append(options)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def download(self, urls: list[str]) -> int:
        FAKE_STATE.calls.append(list(urls))
        output_dir = pathlib.Path(self.options["outtmpl"]).parent
        for index, url in enumerate(urls):
            if url in FAKE_STATE.fail_urls:
                self.options["logger"].error("simulated extractor failure")
                continue
            if FAKE_STATE.escaped_path is not None:
                filename = FAKE_STATE.escaped_path
            else:
                filename = str(output_dir / f"item-{index}.mp4")
                pathlib.Path(filename).write_bytes(b"test media")
            info = {"title": f"item-{index}", **FAKE_STATE.track_info}
            for hook in self.options["progress_hooks"]:
                hook({
                    "status": "downloading",
                    "downloaded_bytes": 5,
                    "total_bytes": 10,
                    "speed": 2.0,
                    "eta": 3,
                    "info_dict": info,
                })
                hook({"status": "finished", "filename": filename, "info_dict": info})
        return FAKE_STATE.result_code

    def extract_info(self, url: str, download: bool = False, process: bool = True) -> dict[str, Any] | None:
        FAKE_STATE.search_calls.append(url)
        if FAKE_STATE.search_info is None:
            return None
        # Mirror yt-dlp's lazy search: entries arrive as a one-shot iterator.
        return {**FAKE_STATE.search_info, "entries": iter(FAKE_STATE.search_info.get("entries") or [])}

    def close(self) -> None:
        pass


class DownloaderTests(unittest.TestCase):
    def setUp(self):
        FAKE_STATE.options = None
        FAKE_STATE.result_code = 0
        FAKE_STATE.fail_urls.clear()
        FAKE_STATE.escaped_path = None
        FAKE_STATE.calls.clear()
        FAKE_STATE.options_history.clear()
        FAKE_STATE.track_info.clear()
        FAKE_STATE.search_info = None
        FAKE_STATE.search_calls.clear()

        module_patch = patch.dict(sys.modules, {"yt_dlp": types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)})
        module_patch.start()
        self.addCleanup(module_patch.stop)

        spec = importlib.util.spec_from_file_location("tested_downloader", DOWNLOADER_PATH)
        if spec is None or spec.loader is None:
            raise RuntimeError("Could not load downloader module for tests")
        self.downloader: Any = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.downloader)
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)

    def options(self) -> dict[str, Any]:
        if FAKE_STATE.options is None:
            self.fail("fake yt-dlp options were not set")
        return FAKE_STATE.options

    def run_download(
        self,
        urls: list[Any],
        format_mode: str = "best",
        playlist: bool = False,
        canceled: bool = False,
        callback: FakeCallback | None = None,
        pause_signal: FakeCancelSignal | None = None,
    ) -> tuple[dict[str, Any], FakeCallback]:
        callback = callback or FakeCallback()
        result = self.downloader.download_urls(
            json.dumps(urls),
            self.tempdir.name,
            42,
            format_mode,
            playlist,
            callback,
            FakeCancelSignal(canceled),
            pause_signal or FakeCancelSignal(),
        )
        return json.loads(result), callback

    def test_download_batch_reports_progress_and_selected_options(self):
        urls = ["https://example.org/one", "https://example.org/two"]
        result, callback = self.run_download(urls, format_mode="720p", playlist=True)

        self.assertEqual(len(result["files"]), 2)
        self.assertEqual(result["errors"], 0)
        self.assertEqual(FAKE_STATE.calls, [urls])
        self.assertFalse(self.options()["noplaylist"])
        self.assertEqual(
            self.options()["format"],
            "best[height<=720][acodec!=none][vcodec!=none]",
        )
        self.assertEqual([event["event"] for event in callback.events], [
            "progress", "processing", "progress", "processing",
        ])
        self.assertEqual(callback.events[0]["percent"], 50.0)
        self.assertEqual([track["path"] for track in result["tracks"]], result["files"])

    def test_download_uses_bounded_fragment_parallelism_and_larger_buffers(self):
        self.run_download(["https://example.org/video"])

        self.assertEqual(self.options()["concurrent_fragment_downloads"], 4)
        self.assertEqual(self.options()["buffersize"], 1024 * 1024)
        self.assertEqual(self.options()["progress_delta"], 1.0)

    def test_download_result_includes_sanitized_track_metadata(self):
        FAKE_STATE.track_info.update({
            "track": "  Playlist song \n",
            "artist": "  Artist \tName ",
            "uploader": "Ignored uploader",
            "album": " Album ",
            "duration": 187.5,
            "thumbnail": "https://i.ytimg.com/vi/example/hqdefault.jpg",
        })
        result, _ = self.run_download(["https://example.org/audio"])

        self.assertEqual(result["tracks"], [{
            "path": result["files"][0],
            "title": "Playlist song",
            "artist": "Artist Name",
            "album": "Album",
            "duration": 187.5,
            "thumbnail": "https://i.ytimg.com/vi/example/hqdefault.jpg",
        }])

    def test_download_result_uses_uploader_fallback_and_rejects_unsafe_thumbnail(self):
        FAKE_STATE.track_info.update({
            "title": "Video title",
            "channel": "Channel name",
            "duration": float("nan"),
            "thumbnail": "https://attacker.example/image.jpg",
        })
        result, _ = self.run_download(["https://example.org/video"])

        track = result["tracks"][0]
        self.assertEqual(track["title"], "Video title")
        self.assertEqual(track["artist"], "Channel name")
        self.assertIsNone(track["duration"])
        self.assertIsNone(track["thumbnail"])

    def test_search_results_include_only_safe_youtube_thumbnails(self):
        FAKE_STATE.search_info = {"entries": [
            {
                "id": "safe",
                "title": "Safe",
                "thumbnail": "https://i.ytimg.com/vi/safe/hqdefault.jpg",
            },
            {
                "id": "list",
                "title": "List",
                "thumbnails": [
                    {"url": "https://evil.example/image.jpg", "width": 1000, "height": 1000},
                    {"url": "https://i.ytimg.com/vi/list/mqdefault.jpg", "width": 320, "height": 180},
                    {"url": "https://i.ytimg.com/vi/list/hqdefault.jpg", "width": 480, "height": 360},
                ],
            },
            {
                "id": "unsafe",
                "title": "Unsafe",
                "thumbnail": "http://i.ytimg.com/vi/unsafe/hqdefault.jpg",
            },
        ]}

        results = json.loads(self.downloader.search_music("songs", FakeCallback()))

        # The thumbnail is now derived from the video id, so the scraped entry data
        # (including the untrusted "unsafe" host) never reaches the image loader at all.
        self.assertEqual([result["thumbnail"] for result in results], [
            "https://i.ytimg.com/vi/safe/hqdefault.jpg",
            "https://i.ytimg.com/vi/list/hqdefault.jpg",
            "https://i.ytimg.com/vi/unsafe/hqdefault.jpg",
        ])
        self.assertTrue(all("evil.example" not in r["thumbnail"] for r in results))

    def test_search_results_return_requested_page(self):
        FAKE_STATE.search_info = {"entries": [
            {"id": f"video-{index}", "title": f"Song {index}"}
            for index in range(25)
        ]}

        results = json.loads(self.downloader.search_music("songs", FakeCallback(), 20, 20))

        self.assertEqual(len(results), 5)
        self.assertEqual(results[0]["title"], "Song 20")
        self.assertEqual(results[-1]["title"], "Song 24")
        self.assertEqual(FAKE_STATE.search_calls, ["ytsearchall:songs"])

    def test_search_pages_reuse_one_session_until_new_search(self):
        FAKE_STATE.search_info = {"entries": [
            {"id": f"video-{index}", "title": f"Song {index}"}
            for index in range(45)
        ]}

        first = json.loads(self.downloader.search_music("songs", FakeCallback(), 0, 20))
        second = json.loads(self.downloader.search_music("songs", FakeCallback(), 20, 20))
        third = json.loads(self.downloader.search_music("songs", FakeCallback(), 40, 20))
        self.assertEqual([len(first), len(second), len(third)], [20, 20, 5])
        self.assertEqual(second[0]["title"], "Song 20")
        # Later pages continue the open stream instead of re-running the search.
        self.assertEqual(FAKE_STATE.search_calls, ["ytsearchall:songs"])

        self.downloader.search_music("songs", FakeCallback(), 0, 20)
        self.assertEqual(len(FAKE_STATE.search_calls), 2)

    def test_search_paging_survives_non_video_entries(self):
        # Channels and playlists used to be dropped after slicing, so a page full of them
        # consumed an offset and returned nothing, which ended scrolling early.
        entries = []
        for index in range(20):
            entries.append({"id": f"video-{index}", "title": f"Song {index}"})
            entries.append({"id": f"UC{index}", "title": f"Channel {index}", "ie_key": "YoutubeTab"})
        entries.append({"id": "video-20", "title": "Song 20"})
        FAKE_STATE.search_info = {"entries": entries}

        first = json.loads(self.downloader.search_music("songs", FakeCallback(), 0, 20))
        second = json.loads(self.downloader.search_music("songs", FakeCallback(), 20, 20))

        self.assertEqual(len(first), 20)
        self.assertEqual(first[0]["title"], "Song 0")
        self.assertEqual([result["id"] for result in second], ["video-20"])

    def test_search_skips_channel_and_playlist_entries(self):
        FAKE_STATE.search_info = {"entries": [
            {"id": "UCchannel", "title": "A channel", "ie_key": "YoutubeTab"},
            {"id": "video-1", "title": "Song", "ie_key": "Youtube"},
        ]}

        results = json.loads(self.downloader.search_music("songs", FakeCallback()))

        self.assertEqual([result["id"] for result in results], ["video-1"])

    def test_search_rejects_invalid_page_bounds(self):
        for offset, limit in ((-1, 20), (0, 0), (0, 51), (True, 20)):
            with self.subTest(offset=offset, limit=limit):
                with self.assertRaises(ValueError):
                    self.downloader.search_music("songs", FakeCallback(), offset, limit)

    def test_format_choices_are_explicit_for_source_audio_and_mp3_pipeline(self):
        for mode, expected in (
            ("best", "best[acodec!=none][vcodec!=none]/best"),
            ("1080p", "best[height<=1080][acodec!=none][vcodec!=none]"),
            ("480p", "best[height<=480][acodec!=none][vcodec!=none]"),
            ("audio", "bestaudio"),
            ("audio_mp3", "bestaudio"),
        ):
            with self.subTest(mode=mode):
                self.run_download(["https://example.org/video"], format_mode=mode)
                self.assertEqual(self.options()["format"], expected)

    def test_audio_mp3_uses_bestaudio_without_yt_dlp_postprocessing(self):
        self.run_download(["https://example.org/audio"], format_mode="audio_mp3")

        self.assertEqual(self.options()["format"], "bestaudio")
        self.assertEqual(len(FAKE_STATE.options_history), 1)
        options = FAKE_STATE.options_history[0]
        self.assertNotIn("postprocessors", options)
        self.assertNotIn("FFmpegLocation", options)
        self.assertNotIn("ffmpeg_location", options)

    def test_pause_signal_waits_then_resumes_and_reports_state(self):
        pause_signal = FakeCancelSignal([False, True, False])
        _, callback = self.run_download(
            ["https://example.org/video"],
            pause_signal=pause_signal,
        )
        states = [event["event"] for event in callback.events]
        self.assertIn("paused", states)
        self.assertIn("resumed", states)

    def test_cancel_while_paused_does_not_emit_a_resume_or_continue(self):
        callback = FakeCallback()
        with self.assertRaises(self.downloader.DownloadCancelled):
            self.downloader.download_urls(
                json.dumps(["https://example.org/video"]),
                self.tempdir.name,
                42,
                "best",
                False,
                callback,
                FakeCancelSignal([False, False, True]),
                FakeCancelSignal(True),
            )
        self.assertEqual([event["event"] for event in callback.events], ["paused"])
        self.assertEqual(FAKE_STATE.calls, [])

    def test_partial_batch_failure_returns_completed_files_and_error_count(self):
        good = "https://example.org/good"
        bad = "https://example.org/bad"
        FAKE_STATE.fail_urls.add(bad)
        FAKE_STATE.result_code = 1

        result, callback = self.run_download([good, bad])

        self.assertEqual(len(result["files"]), 1)
        self.assertEqual(result["errors"], 1)
        self.assertTrue(any(event["event"] == "error" for event in callback.events))

    def test_rejects_invalid_batches_and_format(self):
        invalid_batches = (
            [],
            [""],
            ["file:///tmp/video"],
            ["https:///missing-host"],
            ["https://user:pass@example.org/video"],
            ["https://example.org:99999/video"],
        )
        for urls in invalid_batches:
            with self.subTest(urls=urls), self.assertRaises(ValueError):
                self.run_download(urls)
        with self.assertRaises(TypeError):
            self.run_download([12])
        with self.assertRaises(ValueError):
            self.downloader.download_urls(
                "not json", self.tempdir.name, 42, "best", False, FakeCallback(), FakeCancelSignal(), FakeCancelSignal(),
            )
        with self.assertRaises(ValueError):
            self.run_download(["https://example.org/video"], format_mode="not-a-format")
        with self.assertRaises(TypeError):
            self.downloader.download_urls(
                json.dumps(["https://example.org/video"]), self.tempdir.name,
                42, "best", "false", FakeCallback(), FakeCancelSignal(), FakeCancelSignal(),
            )
        with self.assertRaises(ValueError):
            self.run_download(["https://example.org/" + "x" * 8200])
        with self.assertRaises(ValueError):
            self.run_download(["https://example.org/video"] * 101)

    def test_progress_values_are_clamped_to_android_safe_ranges(self):
        class HugeProgressYoutubeDL(FakeYoutubeDL):
            def download(self, urls):
                output_dir = pathlib.Path(self.options["outtmpl"]).parent
                filename = output_dir / "huge.mp4"
                filename.write_bytes(b"test media")
                self.options["progress_hooks"][0]({
                    "status": "downloading",
                    "downloaded_bytes": 10**1000,
                    "total_bytes": 10**1000,
                    "info_dict": {},
                })
                self.options["progress_hooks"][0]({"status": "finished", "filename": str(filename)})
                return 0

        self.downloader.YoutubeDL = HugeProgressYoutubeDL
        result, callback = self.run_download(["https://example.org/video"])
        self.assertEqual(len(result["files"]), 1)
        progress = next(event for event in callback.events if event["event"] == "progress")
        self.assertEqual(progress["downloaded"], 2**63 - 1)
        self.assertEqual(progress["total"], 2**63 - 1)
        self.assertEqual(progress["percent"], 100.0)

    def test_cancellation_is_checked_before_downloading(self):
        with self.assertRaises(self.downloader.DownloadCancelled):
            self.run_download(["https://example.org/video"], canceled=True)
        self.assertEqual(FAKE_STATE.calls, [])

    def test_cancellation_during_download_is_propagated(self):
        signal = FakeCancelSignal([False, True])
        with self.assertRaises(self.downloader.DownloadCancelled):
            self.downloader.download_urls(
                json.dumps(["https://example.org/video"]),
                self.tempdir.name,
                42,
                "best",
                False,
                FakeCallback(),
                signal,
                FakeCancelSignal(),
            )

    def test_callback_failure_does_not_abort_media_transfer(self):
        class BrokenCallback:
            def accept(self, value):
                raise RuntimeError("activity is no longer available")

        with self.assertLogs("tested_downloader", level="WARNING"):
            result = self.downloader.download_urls(
                json.dumps(["https://example.org/video"]),
                self.tempdir.name,
                42,
                "best",
                False,
                BrokenCallback(),
                FakeCancelSignal(),
                FakeCancelSignal(),
            )
        self.assertEqual(len(json.loads(result)["files"]), 1)

    def test_rejects_output_paths_outside_private_download_directory(self):
        FAKE_STATE.escaped_path = str(pathlib.Path(self.tempdir.name).parent / "outside.mp4")
        with self.assertRaisesRegex(RuntimeError, "outside the download directory"):
            self.run_download(["https://example.org/video"])

    def test_rejects_output_symlink_resolving_outside_private_download_directory(self):
        outside_dir = tempfile.TemporaryDirectory()
        self.addCleanup(outside_dir.cleanup)
        outside_file = pathlib.Path(outside_dir.name) / "outside.mp4"
        outside_file.write_bytes(b"test media")
        symlink_path = pathlib.Path(self.tempdir.name) / "escaped.mp4"
        try:
            symlink_path.symlink_to(outside_file)
        except (NotImplementedError, OSError) as error:
            self.skipTest(f"Symlinks are unavailable: {error}")
        FAKE_STATE.escaped_path = str(symlink_path)

        with self.assertRaisesRegex(RuntimeError, "outside the download directory"):
            self.run_download(["https://example.org/video"])


if __name__ == "__main__":
    unittest.main()
