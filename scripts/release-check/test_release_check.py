# Unit tests for release_check.py's parsing (no emulator, no build).
# Run with: python3 -I -B -m unittest discover -s scripts/release-check
import io
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_check as rc  # noqa: E402

SEPARATOR = "========================================"


def dropbox(*entries):
    """`dumpsys dropbox --print <tag>` output with these (time, process, body) entries."""
    text = "Drop box contents: 3 entries\nMax entries: 1000\nSearching for: data_app_crash\n\n"
    for when, process, body in entries:
        text += (f"{SEPARATOR}\n{when} data_app_crash (text, 1214 bytes)\nProcess: {process}\nPID: 4470\n"
                 f"Package: com.tortugapower.audiobookplayer v25 (1.3.0)\n\n{body}\n\n")
    return text


class DropboxTests(unittest.TestCase):
    def test_keeps_the_apps_entries_since_the_step_started(self):
        text = dropbox(("2026-10-28 22:37:40", rc.PACKAGE, "old"),
                       ("2026-10-28 22:37:42", rc.PACKAGE, "java.lang.IllegalStateException: boom"),
                       ("2026-10-28 22:37:43", f"{rc.PACKAGE}:wear", "in another of its processes"),
                       ("2026-10-28 22:37:44", "com.android.systemui", "someone else"),
                       ("2026-10-28 22:37:45", f"{rc.PACKAGE}.other", "a package with a longer name"))
        entries = rc.dropbox_entries(text, "2026-10-28 22:37:42")
        self.assertEqual(len(entries), 2)
        self.assertIn("boom", entries[0])
        self.assertIn("another of its processes", entries[1])

    def test_no_entries(self):
        self.assertEqual(rc.dropbox_entries("Drop box contents: 0 entries\n", "2026-10-28 00:00:00"), [])

    def summary(self, body):
        entry = rc.dropbox_entries(dropbox(("2026-10-28 22:37:42", rc.PACKAGE, body)), "2026-10-28 00:00:00")[0]
        return rc.crash_summary("data_app_crash", entry, mapping=None).splitlines()

    def test_java_crash_summary_has_the_exception_and_its_frames(self):
        self.assertEqual(self.summary("java.lang.IllegalStateException: boom\n\tat a.b.c(SourceFile:1)"), [
            "crash: java.lang.IllegalStateException: boom", "Package: com.tortugapower.audiobookplayer v25 (1.3.0)",
            "java.lang.IllegalStateException: boom", "\tat a.b.c(SourceFile:1)"])

    def test_the_root_cause_leads_and_keeps_its_frames(self):
        frames = "".join(f"\n\tat android.Frame{i}(Source:{i})" for i in range(10))
        lines = self.summary("java.lang.RuntimeException: Unable to create application" + frames +
                             "\nCaused by: java.lang.IllegalStateException: boom\n\tat a.b.App.onCreate(App.kt:82)")
        self.assertEqual(lines[0], "crash: java.lang.IllegalStateException: boom")
        self.assertEqual(lines[-2:], ["Caused by: java.lang.IllegalStateException: boom", "\tat a.b.App.onCreate(App.kt:82)"])
        self.assertEqual(len(lines), len(set(lines[1:])) + 1)  # no frame twice


class MediaSessionTests(unittest.TestCase):
    def playback(self, output):
        emulator = rc.Emulator.__new__(rc.Emulator)
        with mock.patch.object(rc.Emulator, "shell", return_value=output):
            return emulator.playback()

    def test_named_and_numeric_states(self):
        # API 36 names the state, API 28 doesn't
        for state in ("PLAYING(3)", "3"):
            output = ("    package=com.android.server.telecom\n      state=PlaybackState {state=PAUSED(2), position=0\n"
                      f"      package={rc.PACKAGE}\n      active=true\n"
                      f"      state=PlaybackState {{state={state}, position=6005, buffered position=30000}}\n")
            self.assertEqual(self.playback(output), (3, 6005))

    def test_no_session(self):
        self.assertIsNone(self.playback("    package=com.android.server.telecom\n      state=PlaybackState {state=0, position=0\n"))


class RowTests(unittest.TestCase):
    def test_progress_from_the_rows_label(self):
        row = rc.Node("", "Release Check Book. Unknown author. 2m 0s. 17% completed", rc.PACKAGE, (0, 304, 1080, 535))
        self.assertEqual(rc.progress(row), 17)
        self.assertIs(rc.book_row([rc.Node("Library", "", rc.PACKAGE, (0, 0, 1, 1)), row]), row)

    def test_a_row_without_progress_is_a_failure(self):
        with self.assertRaises(rc.StepError):
            rc.progress(rc.Node("", "Release Check Book. Unknown author.", rc.PACKAGE, (0, 0, 1, 1)))

    def test_only_the_apps_nodes_count(self):
        self.assertIsNone(rc.app_node([rc.Node("Library", "", "com.android.launcher", (0, 0, 1, 1))], text="Library"))


class PropertiesTests(unittest.TestCase):
    def test_keys_end_at_equals_colon_or_whitespace(self):
        for line in ("SENTRY_AUTH_TOKEN=abc", "SENTRY_AUTH_TOKEN = abc", "SENTRY_AUTH_TOKEN: abc",
                     "SENTRY_AUTH_TOKEN abc", "  SENTRY_AUTH_TOKEN=abc"):
            self.assertEqual(rc.property_entry(line), ("SENTRY_AUTH_TOKEN", "abc"), line)
        for line in ("", "   ", "# SENTRY_AUTH_TOKEN=abc", "! SENTRY_AUTH_TOKEN=abc"):
            self.assertIsNone(rc.property_entry(line), line)

    def test_the_build_worktree_never_gets_the_sentry_token(self):
        text = "\n".join(["sdk.dir=/sdk", "SENTRY_AUTH_TOKEN: secret", "SENTRY_AUTH_TOKEN secret",
                          *(f"{key}={key.lower()}" for key in rc.RELEASE_KEYS)])
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "local.properties").write_text(text)
            with mock.patch.object(rc, "main_checkout", return_value=Path(folder)):
                copied = rc.release_properties()
        self.assertNotIn("secret", copied)
        self.assertIn("sdk.dir=/sdk", copied)

    def test_a_missing_release_key_is_named(self):
        with tempfile.TemporaryDirectory() as folder:
            (Path(folder) / "local.properties").write_text("PROD_BASE_URL: https://api\nSENTRY_DSN=\n")
            with mock.patch.object(rc, "main_checkout", return_value=Path(folder)):
                with self.assertRaisesRegex(rc.SetupError, "SENTRY_DSN, REVENUECAT_API_KEY, GOOGLE_CLIENT_ID"):
                    rc.release_properties()


class LeftoverTests(unittest.TestCase):
    def emulator(self):
        return rc.Emulator(rc.Device("watch", 36, ("android-wear",), "wearos_small_round", "2048M", "2G"), 5606, Path("."))

    def adb(self, listings):
        """A fake `run` for adb: `devices` answers from `listings` in turn, `emu avd name` fails (offline)."""
        def run(cmd, timeout=None, **kwargs):
            if cmd[1:] == ["devices"]:
                return "List of devices attached\n" + (listings.pop(0) if len(listings) > 1 else listings[0])
            raise rc.subprocess.CalledProcessError(1, cmd, "", "error: device offline")
        return run

    def test_a_just_killed_emulator_still_listed_offline_is_waited_out(self):
        listings = ["emulator-5606\toffline\n", "emulator-5606\toffline\n", "emulator-5554\tdevice\n"]
        with mock.patch.object(rc, "run", self.adb(listings)), mock.patch.object(rc.time, "sleep"):
            self.emulator().stop_leftovers()

    def test_a_port_that_stays_taken_stops_the_check(self):
        clock = iter(range(0, 1000, 5))
        with mock.patch.object(rc, "run", self.adb(["emulator-5606\toffline\n"])), \
                mock.patch.object(rc.time, "sleep"), mock.patch.object(rc.time, "time", lambda: next(clock)):
            with self.assertRaisesRegex(rc.SetupError, "port 5606 is taken by another emulator"):
                self.emulator().stop_leftovers()


class BootTests(unittest.TestCase):
    def test_a_failed_preparation_is_retried_and_named(self):
        emulator = LeftoverTests().emulator()
        failure = rc.subprocess.CalledProcessError(1, ["adb", "-s", "emulator-5606", "root"], "",
                                                   "adbd cannot run as root in production builds")
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch.object(rc.Emulator, "stop_leftovers"), mock.patch.object(rc.Emulator, "kill"), \
                mock.patch.object(rc.Emulator, "wait_booted", return_value=True), \
                mock.patch.object(rc.Emulator, "prepare", side_effect=failure) as prepare, \
                mock.patch.object(rc.subprocess, "Popen"):
            emulator.logs = Path(folder)
            with self.assertRaisesRegex(rc.SetupError, "preparing it: adb root failed: adbd cannot run as root"):
                emulator.boot()
        self.assertEqual(prepare.call_count, 2)

    def test_adb_errors_name_the_command_without_the_serial(self):
        self.assertEqual(rc.adb_error(rc.subprocess.TimeoutExpired("adb root", 300)), "adb root timed out after 300 s")
        self.assertEqual(rc.adb_error(rc.subprocess.CalledProcessError(1, ["adb", "-s", "emulator-5600", "shell", "ls"],
                                                                        b"", b"no such file")),
                         "adb shell ls failed: no such file")


class OutcomeTests(unittest.TestCase):
    def test_a_failure_wins_over_a_device_that_could_not_run(self):
        failed = [("Phone API 28", "fresh", "failed", "crash: boom"), ("Watch API 30", "fresh", "not run", "")]
        self.assertEqual(rc.outcome(failed, could_not_run=True), (1, "FAILED (some devices could not run)"))
        self.assertEqual(rc.outcome(failed, could_not_run=False), (1, "FAILED"))

    def test_could_not_run_then_warnings_then_pass(self):
        passed = [("Phone API 28", "fresh", "passed", "")]
        self.assertEqual(rc.outcome(passed, could_not_run=True), (2, "COULD NOT RUN (partial results)"))
        self.assertEqual(rc.outcome(passed + [("Phone API 28", "upgrade", "retried", "x")], could_not_run=False),
                         (0, "PASSED (with warnings)"))
        self.assertEqual(rc.outcome(passed, could_not_run=False), (0, "PASSED"))


def elf(align):
    """A minimal 64-bit ELF with one PT_LOAD segment aligned to `align`."""
    header = bytearray(64)
    header[:5] = b"\x7fELF\x02"
    struct.pack_into("<Q", header, 0x20, 64)  # e_phoff
    struct.pack_into("<HH", header, 0x36, 56, 1)  # e_phentsize, e_phnum
    program = bytearray(56)
    struct.pack_into("<I", program, 0, 1)  # PT_LOAD
    struct.pack_into("<Q", program, 0x30, align)
    return bytes(header + program)


class NativeLibraryTests(unittest.TestCase):
    def problems(self, libraries):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name, data in libraries.items():
                    archive.writestr(name, data)
            ok = mock.Mock(returncode=0, stdout="")
            with mock.patch.object(rc, "build_tool", return_value=Path("zipalign")), \
                    mock.patch.object(rc.subprocess, "run", return_value=ok):
                return rc.native_library_problems(apk)

    def test_16_kb_segments_pass(self):
        self.assertEqual(self.problems({"lib/arm64-v8a/libok.so": elf(16384)}), [])

    def test_4_kb_segments_fail_on_64_bit_only(self):
        problems = self.problems({"lib/arm64-v8a/libold.so": elf(4096), "lib/x86_64/libold.so": elf(4096),
                                  "lib/armeabi-v7a/libold.so": elf(4096)})
        self.assertEqual(problems, ["lib/arm64-v8a/libold.so: LOAD segment aligned to 4096 bytes",
                                    "lib/x86_64/libold.so: LOAD segment aligned to 4096 bytes"])


if __name__ == "__main__":
    unittest.main()
