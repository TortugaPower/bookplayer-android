#!/usr/bin/env python3
"""Release check: the release builds of the phone and watch apps on the oldest and newest Android they support.

Run by the prepare-release skill before anything is committed or pushed, never by CI:

    python3 -I scripts/release-check/release_check.py --version 1.3.1

It builds what CI ships, from HEAD's commit (uncommitted changes aren't part of it): the prodRelease bundles of
:app and :wear, minified by R8, turned into APKs the way Play does (bundletool's universal APK) and signed with
the debug key. The build runs in a temporary worktree whose local.properties is the main checkout's without
SENTRY_AUTH_TOKEN, so a check build never uploads an R8 mapping or creates the Sentry release before CI does.
The builds are otherwise the shipped ones: on the emulators they report to Sentry like any install, marked
sideloaded.

Before any emulator, the APKs are checked without one: every 64-bit native library is laid out for 16 KB memory
pages, and scripts/audit-mapping.sh finds every class another process looks up by name unrenamed.

Then, on emulators it creates and wipes itself ("bp-release-check-<phone|watch>-<API>"):

  phone, at minSdk and at targetSdk (a 16 KB page image)
    fresh     empty install: the Library shows and the app stays up
    import    a chaptered test book opened with the app → Accept → Library → its row
    playback  tap the row → the media session plays and its position moves
    upgrade   the previous release imports and plays the same book, then this build is installed over it: the
              row keeps its progress and the book still plays
  watch, at minSdk and at targetSdk
    fresh     the app shows its first screen and stays up
    upgrade   the previous release runs, then this build is installed over it and does the same

Every step fails on a crash of the app (Java or native) in the emulator's dropbox; the report shows its first
frames, de-obfuscated with that build's R8 mapping. A crash fails the check at once. Any other failure, an ANR
(app not responding) included, gets one retry on a wiped emulator: the check fails only if it fails again, and a
scenario that passes the second time is reported as "passed on retry" with the first failure, so nothing is
hidden. If the previous release itself crashes on a device, the upgrade starts from the release before it; if
that one crashes too, the upgrade is reported as not tested there instead of failing this build.

The previous release's APKs and mappings are cached in ~/Library/Caches/BookPlayerReleaseCheckAndroid/<tag>/;
when they're missing, the tag is built once the same way. A passing run caches this build under --version.
The skill runs the check before its version bump, so this build usually has the previous release's versionCode
and installs over it as an update of the same version; nothing in the app keys off the versionCode.

Exit code 0 = passed; 1 = a check failed; 2 = the check couldn't run (setup problem).
The report is written to <out>/report.md (and report.json).
"""

import argparse
import datetime
import json
import os
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BOOK_FIXTURE = Path(__file__).resolve().parent / "release-check-book.m4b"
PACKAGE = "com.tortugapower.audiobookplayer"
CACHE = Path.home() / "Library/Caches/BookPlayerReleaseCheckAndroid"
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")
ADB = SDK / "platform-tools/adb"
EMULATOR = SDK / "emulator/emulator"
CMDLINE_TOOLS = SDK / "cmdline-tools/latest/bin"
AVD_HOME = Path(os.environ.get("ANDROID_AVD_HOME") or Path.home() / ".android/avd")
DEBUG_KEYSTORE = Path.home() / ".android/debug.keystore"
ABI = "arm64-v8a" if platform.machine() == "arm64" else "x86_64"
AVD_PREFIX = "bp-release-check"
# Android Studio starts emulators at 5554; these ports keep the check away from a developer's own
FIRST_PORT = 5600
GRADLE_TASKS = [":app:packageProdReleaseUniversalApk", ":wear:packageProdReleaseUniversalApk"]
# local.properties keys the release build reads (CI refuses to build without them)
RELEASE_KEYS = ("PROD_BASE_URL", "SENTRY_DSN", "REVENUECAT_API_KEY", "GOOGLE_CLIENT_ID")

# Time limits, in seconds
ADB_TIMEOUT = 60
INSTALL_TIMEOUT = 180
BOOT_TIMEOUT = 300
UI_TIMEOUT = 45
STAY_UP = 10
CRASH_GRACE = 5

# The test book as the app shows it: its title metadata and the file name it's opened as
BOOK = "Release Check Book"
BOOK_FILE = f"{BOOK}.m4b"

PLAYING, PAUSED = 3, 2


class SetupError(Exception):
    """The check couldn't run; nothing was tested."""


class StepError(Exception):
    """A step didn't see what it expected (not a crash)."""


def log(message):
    print(f"[release-check] {message}", flush=True)


def run(cmd, timeout=None, **kwargs):
    return subprocess.run(cmd, check=True, text=True, capture_output=True, timeout=timeout, **kwargs).stdout


def git(*args):
    return run(["git", "-C", str(REPO), *args]).strip()


# MARK: - Inputs


class Device:
    """One emulator the check runs on."""

    def __init__(self, kind, api, tags, profile, ram, disk, note=""):
        self.kind, self.api, self.tags, self.profile, self.ram, self.disk = kind, api, tags, profile, ram, disk
        self.label = f"{kind.capitalize()} API {api}" + (f" ({note})" if note else "")
        self.avd = f"{AVD_PREFIX}-{kind}-{api}"
        self.tag = None  # the system image tag found installed

    def image(self):
        """The installed system image for this device, or SetupError saying what to install."""
        for tag in self.tags:
            if (SDK / f"system-images/android-{self.api}/{tag}/{ABI}").is_dir():
                self.tag = tag
                return f"system-images;android-{self.api};{tag};{ABI}"
        package = f"system-images;android-{self.api};{self.tags[0]};{ABI}"
        raise SetupError(f"no emulator image for {self.label} is installed. Install it with "
                         f"`{CMDLINE_TOOLS / 'sdkmanager'} --install '{package}'`, then run the check again")


def sdk_levels(module):
    text = (REPO / module / "build.gradle.kts").read_text()
    levels = []
    for key in ("minSdk", "targetSdk"):
        match = re.search(rf"^\s*{key}\s*=\s*(\d+)", text, re.M)
        if not match:
            raise SetupError(f"couldn't read {key} from {module}/build.gradle.kts")
        levels.append(int(match.group(1)))
    return levels


def devices():
    phone_min, phone_target = sdk_levels("app")
    watch_min, watch_target = sdk_levels("wear")
    # google_apis images run adb as root, which the import needs (a release build has no run-as). The newest
    # phone uses 16 KB memory pages: a native library that isn't built for them crashes the app at launch.
    phones = [Device("phone", phone_min, ("google_apis",), "pixel_7", "4096M", "4G"),
              Device("phone", phone_target, ("google_apis_ps16k",), "pixel_7", "4096M", "4G", "16 KB pages")]
    watch_tags = ("android-wear-signed", "android-wear")
    watches = [Device("watch", api, watch_tags, "wearos_small_round", "2048M", "2G")
               for api in sorted({watch_min, watch_target})]
    return phones + watches


def previous_tag():
    """The latest release tag reachable from HEAD that isn't on HEAD itself.

    Normally HEAD isn't tagged (the skill checks before the version bump). When the check is run by hand on a
    released commit, HEAD's own tag is the release being checked, so the upgrade starts from the one before it.
    """
    cmd = ["git", "-C", str(REPO), "describe", "--tags", "--abbrev=0", "--match", "[0-9]*"]
    for own_tag in run(["git", "-C", str(REPO), "tag", "--points-at", "HEAD"]).split():
        cmd += ["--exclude", own_tag]
    try:
        return run(cmd + ["HEAD"]).strip()
    except subprocess.CalledProcessError:
        raise SetupError("no earlier release tag reachable from HEAD; pass --previous")


def older_tag(tag):
    """The release tag before `tag`, or None."""
    try:
        return run(["git", "-C", str(REPO), "describe", "--tags", "--abbrev=0", "--match", "[0-9]*", f"{tag}^"]).strip()
    except subprocess.CalledProcessError:
        return None


def main_checkout():
    """The repository's main worktree (the skill runs the check from a temporary one)."""
    for line in git("worktree", "list", "--porcelain").splitlines():
        if line.startswith("worktree "):
            return Path(line[len("worktree "):])
    raise SetupError("couldn't find the main checkout")


def release_properties():
    """The main checkout's local.properties without SENTRY_AUTH_TOKEN, for the build worktrees.

    The values are never printed; only missing keys are named."""
    source = main_checkout() / "local.properties"
    if not source.exists():
        raise SetupError(f"{source} doesn't exist: the release build reads its keys from it")
    lines = source.read_text().splitlines()
    values = {}
    for line in lines:
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    missing = [key for key in RELEASE_KEYS if not values.get(key)]
    if missing:
        raise SetupError(f"{source} has no {', '.join(missing)}: the release build needs them")
    return "\n".join(line for line in lines if line.split("=", 1)[0].strip() != "SENTRY_AUTH_TOKEN") + "\n"


# MARK: - Building


def build_tool(name):
    """`name` from the newest build-tools that has it."""
    for folder in sorted((SDK / "build-tools").glob("*"), key=lambda p: [int(x) for x in re.findall(r"\d+", p.name)],
                         reverse=True):
        if (folder / name).exists():
            return folder / name
    raise SetupError(f"no build-tools with {name} under {SDK}/build-tools")


def build(ref, work, name, audit=False):
    """Builds `ref`'s prodRelease phone and watch APKs (unsigned) in a temporary worktree.

    Returns (folder with phone.apk, watch.apk and their mappings, audit results when `audit`)."""
    log(f"building {name} (prodRelease, phone and watch)…")
    worktree = work / f"wt-{name}"
    shutil.rmtree(worktree, ignore_errors=True)
    subprocess.run(["git", "-C", str(REPO), "worktree", "prune"], capture_output=True)
    git("worktree", "add", "--detach", str(worktree), ref)
    try:
        (worktree / "local.properties").write_text(release_properties())
        # The build reads these from the environment too: never sign with the release key or upload to Sentry
        env = {k: v for k, v in os.environ.items() if k != "SENTRY_AUTH_TOKEN" and not k.startswith("RELEASE_")}
        build_log = work / f"build-{name}.log"
        with open(build_log, "w") as out:
            code = subprocess.run(["./gradlew", "--console=plain", *GRADLE_TASKS], cwd=worktree, env=env, stdout=out,
                                  stderr=subprocess.STDOUT).returncode
        if code != 0:
            errors = [line for line in build_log.read_text().splitlines()
                      if line.startswith(("e: ", "> ")) or "What went wrong" in line][:6]
            raise SetupError(f"build of {name} failed (see {build_log}):\n" + "\n".join(errors))
        products = work / f"apks-{name}"
        shutil.rmtree(products, ignore_errors=True)
        products.mkdir(parents=True)
        for kind, module in (("phone", "app"), ("watch", "wear")):
            apks = sorted((worktree / module / "build/outputs/apk_from_bundle/prodRelease").glob("*universal*.apk"))
            mapping = worktree / module / "build/outputs/mapping/prodRelease/mapping.txt"
            if not apks or not mapping.exists():
                raise SetupError(f"the build of {name} produced no universal APK or mapping for :{module}")
            shutil.copy(apks[0], products / f"{kind}.apk")
            shutil.copy(mapping, products / f"{kind}-mapping.txt")
        audits = []
        if audit:
            for module in ("app", "wear"):
                result = subprocess.run(["bash", "scripts/audit-mapping.sh", module], cwd=worktree, text=True,
                                        capture_output=True)
                audits.append((module, result.returncode == 0, (result.stdout + result.stderr).strip()))
        return products, audits
    finally:
        subprocess.run(["git", "-C", str(REPO), "worktree", "remove", "--force", str(worktree)], capture_output=True)


def only_version_bump(old, new):
    """True when `new` is `old` plus nothing but the release skill's version bump: versionCode/versionName in the
    two build files, and the Play release notes."""
    if old == new:
        return True
    if subprocess.run(["git", "-C", str(REPO), "merge-base", "--is-ancestor", old, new]).returncode != 0:
        return False
    for path in git("diff", "--name-only", old, new).splitlines():
        if path not in ("app/build.gradle.kts", "wear/build.gradle.kts") and not path.startswith("distribution/whatsnew/"):
            return False
    changed = [line for line in git("diff", "-U0", old, new, "--", "app/build.gradle.kts", "wear/build.gradle.kts").splitlines()
               if line[:1] in "+-" and not line.startswith(("+++", "---"))]
    return all(re.match(r"^[+-]\s*version(Code|Name)\s*=", line) for line in changed)


CACHED_FILES = ("phone.apk", "watch.apk", "phone-mapping.txt", "watch-mapping.txt")


def cache_build(products, version, commit):
    """Copies a build into the cache for `version`; the source record is written last, so a copy that stopped
    partway is never trusted."""
    entry = CACHE / version
    shutil.rmtree(entry, ignore_errors=True)
    entry.mkdir(parents=True)
    for name in CACHED_FILES:
        shutil.copy(products / name, entry / name)
    (entry / "source.json").write_text(json.dumps({"commit": commit}))


def cached_build(tag):
    """The cached build of `tag`, or None (with the reason logged) when it can't be trusted as that release."""
    entry = CACHE / tag
    if not entry.exists():
        return None
    try:
        recorded = json.loads((entry / "source.json").read_text())
    except (OSError, ValueError):
        log(f"previous release {tag}: cache has no source record (incomplete copy?), rebuilding")
        return None
    if not all((entry / name).exists() for name in CACHED_FILES):
        log(f"previous release {tag}: cached build is incomplete, rebuilding")
        return None
    if not only_version_bump(recorded.get("commit", ""), git("rev-parse", f"{tag}^{{commit}}")):
        log(f"previous release {tag}: cached build ({recorded.get('commit', '?')[:8]}) isn't what {tag} tagged, rebuilding")
        return None
    return entry


def previous_build(tag, work):
    entry = cached_build(tag)
    if entry:
        log(f"previous release {tag}: cached")
        return entry
    log(f"previous release {tag}: building the tag once…")
    products, _ = build(tag, work, tag)
    cache_build(products, tag, git("rev-parse", f"{tag}^{{commit}}"))
    shutil.rmtree(products, ignore_errors=True)
    return CACHE / tag


def sign(unsigned, signed):
    """Signs with the debug key: every build the check installs shares it, so one installs over another."""
    if not DEBUG_KEYSTORE.exists():
        raise SetupError(f"no debug keystore at {DEBUG_KEYSTORE} (Android Studio creates it on its first build)")
    run([str(build_tool("apksigner")), "sign", "--ks", str(DEBUG_KEYSTORE), "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--out", str(signed), str(unsigned)])
    return signed


# MARK: - Bundle checks


def native_library_problems(apk):
    """64-bit native libraries that can't load with 16 KB memory pages: an ELF LOAD segment aligned below 16 KB,
    or (stored uncompressed, as Play installs them) a library not at a 16 KB boundary inside the APK."""
    problems = []
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            parts = info.filename.split("/")
            if len(parts) != 3 or parts[0] != "lib" or parts[1] not in ("arm64-v8a", "x86_64") or not parts[2].endswith(".so"):
                continue
            data = archive.read(info)
            if data[:4] != b"\x7fELF" or data[4] != 2:  # ELFCLASS64
                continue
            phoff = struct.unpack_from("<Q", data, 0x20)[0]
            phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
            aligns = [struct.unpack_from("<Q", data, phoff + i * phentsize + 0x30)[0] for i in range(phnum)
                      if struct.unpack_from("<I", data, phoff + i * phentsize)[0] == 1]  # PT_LOAD
            if aligns and min(aligns) < 16384:
                problems.append(f"{info.filename}: LOAD segment aligned to {min(aligns)} bytes")
    zip_check = subprocess.run([str(build_tool("zipalign")), "-c", "-P", "16", "4", str(apk)], text=True,
                               capture_output=True)
    if zip_check.returncode != 0:
        problems += [line for line in zip_check.stdout.splitlines() if "BAD" in line][:5] or ["zipalign -c -P 16 failed"]
    return problems


# MARK: - Emulators


class Emulator:
    """One device's emulator, booted from a wiped AVD; resetting it boots it wiped again."""

    def __init__(self, device, port, logs):
        self.device, self.port, self.logs = device, port, logs
        self.serial = f"emulator-{port}"
        self.process = None
        self.size = None

    # adb

    def adb(self, *args, timeout=ADB_TIMEOUT):
        return run([str(ADB), "-s", self.serial, *args], timeout=timeout)

    def shell(self, command, timeout=ADB_TIMEOUT):
        """One shell command line on the device (a single argument, so quoting reaches the device's shell)."""
        return self.adb("shell", command, timeout=timeout)

    def now(self):
        return self.shell("date '+%Y-%m-%d %H:%M:%S'").strip()

    # lifecycle

    def ensure_avd(self):
        image = self.device.image()
        config = AVD_HOME / f"{self.device.avd}.avd/config.ini"

        def key(line):  # avdmanager writes `key=value`, the emulator rewrites it as `key = value`
            return line.split("=", 1)[0].strip()

        def value(line):
            return line.split("=", 1)[1].strip() if "=" in line else ""

        sysdir = f"system-images/android-{self.device.api}/{self.device.tag}/{ABI}/"
        lines = config.read_text().splitlines() if config.exists() else []
        if not any(key(line) == "image.sysdir.1" and value(line) == sysdir for line in lines):
            log(f"{self.device.label}: creating AVD {self.device.avd}")
            subprocess.run([str(CMDLINE_TOOLS / "avdmanager"), "create", "avd", "-n", self.device.avd, "-k", image,
                            "-d", self.device.profile, "--force"], input="no\n", text=True, capture_output=True,
                           timeout=ADB_TIMEOUT, check=True)
            lines = config.read_text().splitlines()
        settings = {"hw.ramSize": self.device.ram, "disk.dataPartition.size": self.device.disk, "hw.keyboard": "yes"}
        lines = [line for line in lines if key(line) not in settings]
        config.write_text("\n".join(lines + [f"{k}={v}" for k, v in settings.items()]) + "\n")

    def stop_leftovers(self):
        """Stops this AVD wherever it runs, and any of the check's AVDs on this port (a previous run that was
        interrupted); an emulator that isn't the check's on the port stops the check."""
        for line in run([str(ADB), "devices"], timeout=ADB_TIMEOUT).splitlines()[1:]:
            serial = line.split("\t")[0]
            if not serial.startswith("emulator-"):
                continue
            try:
                name = run([str(ADB), "-s", serial, "emu", "avd", "name"], timeout=10).splitlines()[0].strip()
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, IndexError):
                name = ""
            if name == self.device.avd or (serial == self.serial and name.startswith(f"{AVD_PREFIX}-")):
                log(f"{self.device.label}: stopping a leftover {name} on {serial}")
                subprocess.run([str(ADB), "-s", serial, "emu", "kill"], capture_output=True, timeout=30)
                time.sleep(3)
            elif serial == self.serial:
                raise SetupError(f"port {self.port} is taken by another emulator ({name or 'unknown'}); stop it "
                                 "and run the check again")

    def boot(self):
        """Boots the AVD wiped. One that doesn't come up is killed and booted once more."""
        for attempt in (1, 2):
            self.stop_leftovers()
            with open(self.logs / f"emulator-attempt{attempt}.log", "a") as out:
                self.process = subprocess.Popen(
                    [str(EMULATOR), "-avd", self.device.avd, "-port", str(self.port), "-no-window", "-no-boot-anim",
                     "-no-snapshot", "-wipe-data", "-gpu", "swiftshader_indirect"],
                    stdout=out, stderr=subprocess.STDOUT)
            deadline = time.time() + BOOT_TIMEOUT
            while time.time() < deadline and self.process.poll() is None:
                try:
                    if self.shell("getprop sys.boot_completed", timeout=10).strip() == "1":
                        self.prepare()
                        return
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                    pass
                time.sleep(2)
            log(f"{self.device.label}: emulator didn't boot (attempt {attempt})")
            self.kill()
        raise SetupError(f"{self.device.label}: the emulator didn't boot within {BOOT_TIMEOUT} s, twice "
                         f"(see {self.logs}/emulator-attempt*.log)")

    def prepare(self):
        if self.device.kind == "phone":
            # root: the import copies the test book into the app's own storage
            self.adb("root")
            time.sleep(2)
            self.adb("wait-for-device", timeout=BOOT_TIMEOUT)
            deadline = time.time() + BOOT_TIMEOUT
            while self.shell("getprop sys.boot_completed").strip() != "1":
                if time.time() > deadline:
                    raise subprocess.TimeoutExpired("adb root", BOOT_TIMEOUT)
                time.sleep(2)
        else:
            # a watch on the virtual charger shows the charging screen over every app
            self.shell("dumpsys battery unplug")
        for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            self.shell(f"settings put global {key} 0")
        self.shell("svc power stayon true")
        self.shell("input keyevent KEYCODE_WAKEUP")
        self.shell("input keyevent 82")  # dismisses the keyguard
        sizes = re.findall(r"(\d+)x(\d+)", self.shell("wm size"))
        self.size = tuple(map(int, sizes[-1]))  # an override size comes last

    def kill(self):
        try:
            subprocess.run([str(ADB), "-s", self.serial, "emu", "kill"], capture_output=True, timeout=30)
        except subprocess.TimeoutExpired:
            pass
        if self.process:
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            self.process = None

    def reset(self):
        self.kill()
        self.boot()

    def screenshot(self, path):
        try:
            with open(path, "wb") as out:
                subprocess.run([str(ADB), "-s", self.serial, "exec-out", "screencap", "-p"], stdout=out, timeout=30)
        except (subprocess.TimeoutExpired, OSError):
            pass

    def save_diagnostics(self, stem):
        """A screenshot, the screen's accessibility tree and logcat, for a failed step."""
        self.screenshot(self.logs / f"{stem}.png")
        try:
            (self.logs / f"{stem}-ui.xml").write_text(self.ui_xml())
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        try:
            (self.logs / f"{stem}-logcat.txt").write_text(self.adb("logcat", "-d", "-v", "time"))
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass

    # the app

    def uninstall(self):
        subprocess.run([str(ADB), "-s", self.serial, "uninstall", PACKAGE], capture_output=True, timeout=ADB_TIMEOUT)

    def install(self, apk, replace=False):
        self.adb("install", *(["-r"] if replace else []), "--no-incremental", str(apk), timeout=INSTALL_TIMEOUT)

    def launch(self):
        component = self.shell(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {PACKAGE}")
        component = component.strip().splitlines()[-1]
        if "/" not in component:
            raise StepError(f"the app has no launcher activity ({component})")
        result = self.shell(f"am start -W -n {component}", timeout=UI_TIMEOUT)
        if "Error" in result:
            raise StepError(f"launching {component}: {result.strip()}")

    def pid(self):
        try:
            return self.shell(f"pidof {PACKAGE}").strip()
        except subprocess.CalledProcessError:  # pidof exits 1 when nothing runs
            return ""

    def stay_up(self, seconds=STAY_UP):
        before = self.pid()
        if not before:
            raise StepError("the app isn't running")
        time.sleep(seconds)
        after = self.pid()
        if after != before:
            raise StepError(f"the app's process {before} stopped within {seconds} s" +
                            (f" (now {after})" if after else ""))

    # the screen

    def ui_xml(self):
        self.shell("rm -f /sdcard/release-check-ui.xml; uiautomator dump /sdcard/release-check-ui.xml")
        return self.adb("exec-out", "cat", "/sdcard/release-check-ui.xml")

    def nodes(self):
        """The screen's accessibility nodes (empty when the dump failed, e.g. mid-animation)."""
        try:
            root = ET.fromstring(self.ui_xml())
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
            return []
        found = []
        for node in root.iter("node"):
            bounds = tuple(map(int, re.findall(r"-?\d+", node.get("bounds", "")))) or (0, 0, 0, 0)
            found.append(Node(node.get("text") or "", node.get("content-desc") or "", node.get("package") or "", bounds))
        return found

    def wait_for(self, find, what, timeout=UI_TIMEOUT):
        """The first non-empty result of `find(nodes)` within `timeout`, else StepError."""
        deadline = time.time() + timeout
        while True:
            result = find(self.nodes())
            if result:
                return result
            if time.time() > deadline:
                raise StepError(f"never saw {what} within {timeout} s")
            time.sleep(1)

    def tap(self, node):
        self.shell(f"input tap {node.x} {node.y}")

    def swipe_down(self):
        width, height = self.size
        self.shell(f"input swipe {width // 2} {height // 4} {width // 2} {height * 9 // 10} 300")

    # playback

    def playback(self):
        """(state, position in ms) of the app's media session, or None."""
        owner = False
        for line in self.shell("dumpsys media_session").splitlines():
            if "package=" in line:
                owner = line.strip() == f"package={PACKAGE}"
            elif owner:
                match = SESSION_STATE.search(line)
                if match:
                    return int(match.group(1)), int(match.group(2))
        return None

    def wait_playing(self):
        """Waits until the session plays and its position moves; returns the position."""
        deadline, first, state = time.time() + UI_TIMEOUT, None, None
        while time.time() < deadline:
            state = self.playback()
            if state and state[0] == PLAYING:
                if first is None:
                    first = (time.time(), state[1])
                elif state[1] > first[1]:
                    return state[1]
            else:
                first = None
            time.sleep(1)
        raise StepError(f"playback didn't start and move within {UI_TIMEOUT} s (media session: {state})")

    def wait_paused(self):
        deadline = time.time() + 15
        while time.time() < deadline:
            state = self.playback()
            if state and state[0] == PAUSED:
                return
            time.sleep(1)
        raise StepError(f"playback didn't pause (media session: {self.playback()})")

    # crashes

    def app_failures(self, since):
        """The app's dropbox entries since `since` (device time): ([crash], [anr]), each (tag, text)."""
        crashes, anrs = [], []
        for tag in ("data_app_crash", "data_app_native_crash", "data_app_anr"):
            for entry in dropbox_entries(self.shell(f"dumpsys dropbox --print {tag}"), since):
                (anrs if tag == "data_app_anr" else crashes).append((tag, entry))
        return crashes, anrs


SESSION_STATE = re.compile(r"state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)\)?, position=(\d+)")
DROPBOX_HEADER = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) \S+ \(")


def dropbox_entries(text, since):
    """Entries of `dumpsys dropbox --print <tag>` output written at or after `since` by the app's processes."""
    entries = []
    for chunk in text.split("========================================")[1:]:
        lines = chunk.strip("\n").splitlines()
        match = DROPBOX_HEADER.match(lines[0]) if lines else None
        if not match or match.group(1) < since:
            continue
        if any(line.startswith("Process: ") and line[len("Process: "):].split(":")[0] == PACKAGE for line in lines[:12]):
            entries.append("\n".join(lines))
    return entries


class Node:
    def __init__(self, text, desc, package, bounds):
        self.text, self.desc, self.package = text, desc, package
        x1, y1, x2, y2 = bounds
        self.x, self.y, self.top = (x1 + x2) // 2, (y1 + y2) // 2, y1


def app_node(nodes, text=None, desc=None, desc_prefix=None):
    for node in nodes:
        if node.package != PACKAGE:
            continue
        if (text is not None and node.text == text) or (desc is not None and node.desc == desc) or \
                (desc_prefix is not None and node.desc.startswith(desc_prefix)):
            return node
    return None


def book_row(nodes):
    # A library row reads "<title>. <author>. <duration>. <n>% completed"
    return app_node(nodes, desc_prefix=f"{BOOK}.")


def progress(row):
    match = re.search(r"(\d+)% completed", row.desc)
    if not match:
        raise StepError(f"the book's row shows no progress: {row.desc!r}")
    return int(match.group(1))


# MARK: - Crash summaries


CRASH_KINDS = {"data_app_crash": "crash", "data_app_native_crash": "native crash", "data_app_anr": "ANR"}


def crash_summary(tag, text, mapping):
    """What crashed and its first frames, de-obfuscated with `mapping` (a Java crash or ANR). The first line says
    what happened: the exception (its root cause when there is one), the ANR's subject or the native signal."""
    lines = text.splitlines()
    head = [line for line in lines[:15] if line.startswith(("Package:", "Subject:"))][:2]
    if tag == "data_app_native_crash":
        body = [line.strip() for line in lines if re.match(r"\s*(signal |Abort message|#\d\d pc )", line)][:12]
        first = next((line for line in body if line.startswith("signal ")), "")
        return "\n".join([f"{CRASH_KINDS[tag]}: {first}".rstrip()] + head + body)
    if tag == "data_app_anr":
        start = next((i for i, line in enumerate(lines) if line.startswith('"main"')), None)
        body = [line for line in lines[start + 1:] if line.lstrip().startswith("at ")][:12] if start is not None else []
        first = next((line[len("Subject: "):] for line in head if line.startswith("Subject: ")), "")
        return "\n".join([f"{CRASH_KINDS[tag]}: {first}".rstrip()] + head + retrace(body, mapping))
    start = lines.index("") if "" in lines else 0
    trace = [line for line in lines[start + 1:] if line.strip()]
    causes = [line for line in trace if line.startswith("Caused by: ")]
    first = causes[-1][len("Caused by: "):] if causes else (trace[0] if trace else "")
    # The root cause's own frames matter most: keep the top of the trace and the last "Caused by" block
    body = trace[:8] + (trace[max(8, trace.index(causes[-1])):][:8] if causes else trace[8:14])
    return "\n".join([f"{CRASH_KINDS[tag]}: {retrace([first], mapping)[0] if first else ''}".rstrip()] + head +
                     retrace(body, mapping))


def retrace(lines, mapping):
    tool = CMDLINE_TOOLS / "retrace"
    if not lines or not mapping or not Path(mapping).exists() or not tool.exists():
        return lines
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as trace:
        trace.write("\n".join(lines) + "\n")
    try:
        result = subprocess.run([str(tool), str(mapping), trace.name], text=True, capture_output=True, timeout=120)
        return result.stdout.splitlines()[:16] if result.returncode == 0 and result.stdout.strip() else lines
    except subprocess.TimeoutExpired:
        return lines
    finally:
        os.unlink(trace.name)


# MARK: - Scenarios


def copy_book(e):
    """Copies the test book into the app's private files (as root: a release build has no run-as), owned by the
    app and labelled like its other files, so the app can open it."""
    e.adb("push", str(BOOK_FIXTURE), "/data/local/tmp/release-check.m4b")
    app_dir = f"/data/data/{PACKAGE}"
    context = e.shell(f"ls -dZ {app_dir}").split()[0]
    owner = e.shell(f"stat -c %u:%g {app_dir}").strip()
    folder = f"{app_dir}/files/release-check"
    e.shell(f"rm -rf {folder} && mkdir -p {folder} && cp /data/local/tmp/release-check.m4b '{folder}/{BOOK_FILE}' && "
            f"chown {owner} {app_dir}/files {folder} '{folder}/{BOOK_FILE}' && "
            f"chcon {context} {app_dir}/files {folder} '{folder}/{BOOK_FILE}'")
    return f"file://{folder}/{BOOK_FILE.replace(' ', '%20')}"


def import_book(e):
    """Opens the test book with the app ("Open with") → Accept → Library → its row."""
    uri = copy_book(e)
    result = e.shell(f"am start -W -a android.intent.action.VIEW -d '{uri}' -t audio/mp4 -p {PACKAGE}",
                     timeout=UI_TIMEOUT)
    if "Error" in result:
        raise StepError(f"opening the book with the app: {result.strip()}")
    e.tap(e.wait_for(lambda nodes: app_node(nodes, desc="Accept"), "the import sheet's Accept"))
    title = e.wait_for(lambda nodes: app_node(nodes, text="Import Complete"), "the Import Complete dialog")

    def library_option(nodes):
        # the dialog's "Library" choice, below its title (the screen's own "Library" title is above)
        return next((n for n in nodes if n.package == PACKAGE and n.text == "Library" and n.top > title.top), None)

    e.tap(e.wait_for(library_option, "the dialog's Library option"))
    e.wait_for(book_row, f"the {BOOK} row in the Library")


def play_book(e):
    """Taps the book's row, waits until it plays, then pauses it."""
    e.tap(e.wait_for(book_row, f"the {BOOK} row"))
    e.wait_playing()
    e.tap(e.wait_for(lambda nodes: app_node(nodes, desc="Pause"), "the player's Pause"))
    e.wait_paused()


def show_library(e):
    """The library with the book's row; minimizes the player when the app opened on it."""
    def library_or_player(nodes):
        return nodes if book_row(nodes) or app_node(nodes, desc="Chapters") else None

    if not book_row(e.wait_for(library_or_player, "the library or the player")):
        e.swipe_down()
    return e.wait_for(book_row, f"the {BOOK} row in the Library")


def phone_chain(e, apk):
    def fresh(e):
        e.uninstall()
        e.install(apk)
        e.launch()
        e.wait_for(lambda nodes: app_node(nodes, text="Library"), "the Library")
        e.stay_up()

    def playback(e):
        play_book(e)
        e.stay_up(3)

    return [("fresh", [("candidate", fresh)]), ("import", [("candidate", import_book)]),
            ("playback", [("candidate", playback)])]


def phone_upgrade(previous, previous_apk, apk):
    seeded = {}

    def seed(e):
        e.uninstall()
        e.install(previous_apk)
        e.launch()
        e.wait_for(lambda nodes: app_node(nodes, text="Library"), "the Library")
        import_book(e)
        play_book(e)
        e.swipe_down()
        seeded["progress"] = progress(e.wait_for(book_row, f"the {BOOK} row after playing"))
        if not seeded["progress"]:
            raise StepError("the book played but its row shows 0% completed")

    def upgrade(e):
        e.install(apk, replace=True)
        e.launch()
        after = progress(show_library(e))
        if after != seeded["progress"]:
            raise StepError(f"the book was {seeded['progress']}% completed before the upgrade and {after}% after")
        play_book(e)

    return [("upgrade", [(previous, seed), ("candidate", upgrade)])]


def watch_shows_app(e):
    e.wait_for(lambda nodes: next((n for n in nodes if n.package == PACKAGE), None), "the app on screen")


def watch_chain(e, apk):
    def fresh(e):
        e.uninstall()
        e.install(apk)
        e.launch()
        watch_shows_app(e)
        e.stay_up()
        watch_shows_app(e)

    return [("fresh", [("candidate", fresh)])]


def watch_upgrade(previous, previous_apk, apk):
    def seed(e):
        e.uninstall()
        e.install(previous_apk)
        e.launch()
        watch_shows_app(e)
        e.stay_up()

    def upgrade(e):
        e.install(apk, replace=True)
        e.launch()
        watch_shows_app(e)
        e.stay_up()
        watch_shows_app(e)

    return [("upgrade", [(previous, seed), ("candidate", upgrade)])]


# MARK: - Running scenarios


class StepFailure:
    def __init__(self, message, crashed, previous=None):
        # previous: the previous release's tag when the step ran on it rather than on this build
        self.message, self.crashed, self.previous = message, crashed, previous


def run_step(e, build, step, mappings, stem):
    """Runs one step; returns None when it passed, else a StepFailure."""
    since = e.now()
    error = None
    try:
        step(e)
    except StepError as err:
        error = str(err)
    except subprocess.CalledProcessError as err:
        error = f"adb {' '.join(map(str, err.cmd[3:]))} failed: {(err.stderr or err.stdout or '').strip()[:500]}"
    except subprocess.TimeoutExpired as err:
        error = f"adb {' '.join(map(str, err.cmd[3:]))} timed out after {err.timeout} s"
    crashes, anrs = e.app_failures(since)
    if error and not crashes:
        # A crash can land in the dropbox a moment after the step gave up; a crash must never be retried
        time.sleep(CRASH_GRACE)
        crashes, anrs = e.app_failures(since)
    if not error and not crashes and not anrs:
        return None
    # The crash first (the report's table shows the first line), then what the step saw; a crash the system
    # repeated (it relaunches a crashed app's activity) once, with a count
    summaries = [crash_summary(tag, text, mappings.get(build)) for tag, text in crashes + anrs]
    reasons = [summary + (f"\n(×{summaries.count(summary)})" if summaries.count(summary) > 1 else "")
               for i, summary in enumerate(summaries) if summary not in summaries[:i]]
    if error:
        reasons.append(f"step: {error}" if reasons else error)
    e.save_diagnostics(stem)
    return StepFailure("\n".join(reasons), crashed=bool(crashes))


def run_chain(e, chain, mappings, attempt):
    """Runs scenarios in order, stopping at the first failure; a retry starts on a wiped emulator.

    `chain` is [(scenario, [(build, step), …])], where build is "candidate" or the previous release's tag.
    Returns {scenario: None (passed) | StepFailure | "not run"}.
    """
    if attempt > 1:
        e.reset()
    outcome, failed = {}, False
    for scenario, steps in chain:
        if failed:
            outcome[scenario] = "not run"
            continue
        for build, step in steps:
            previous = build if build != "candidate" else None
            failure = run_step(e, build, step, mappings,
                               f"{scenario}{'-' + previous if previous else ''}-failed-attempt{attempt}")
            if failure:
                if previous:
                    failure.previous = previous
                    failure.message = f"previous release {previous}: {failure.message}"
                outcome[scenario] = failure
                failed = True
                break
        else:
            e.screenshot(e.logs / f"{scenario}.png")
            outcome[scenario] = None
    return outcome


def describe(label, outcome):
    rows = []
    for scenario, result in outcome.items():
        if result is None:
            rows.append((label, scenario, "passed", ""))
        elif result == "not run":
            rows.append((label, scenario, "not run", "an earlier scenario failed"))
        else:
            rows.append((label, scenario, "failed", result.message))
    return rows


def run_with_retry(label, e, chain, mappings):
    """One attempt; a failure without a crash gets one more on a wiped emulator.

    Returns ([(label, scenario, status, message)], outcome), with status "passed" | "retried" | "failed" |
    "not run", and the run_chain outcome of the attempt that decided it.
    """
    first = run_chain(e, chain, mappings, attempt=1)
    failures = [f for f in first.values() if isinstance(f, StepFailure)]
    if not failures:
        return [(label, scenario, "passed", "") for scenario in first], first
    if any(f.crashed for f in failures):
        log(f"{label}: crashed, no retry")
        return describe(label, first), first
    log(f"{label}: failed without a crash, retrying once on a wiped emulator")
    second = run_chain(e, chain, mappings, attempt=2)
    results = []
    for scenario, result in second.items():
        earlier = first.get(scenario)
        if result is None and isinstance(earlier, StepFailure):
            results.append((label, scenario, "retried", f"passed on retry; first attempt: {earlier.message}"))
        elif isinstance(result, StepFailure) and isinstance(earlier, StepFailure):
            results.append((label, scenario, "failed",
                            f"failed twice; second attempt: {result.message}\nfirst attempt: {earlier.message}"))
        else:
            results += describe(label, {scenario: result})
    return results, second


def run_upgrade(label, e, device, previous_tags, previous_apks, apk, mappings):
    """The upgrade scenario, seeded with the newest of `previous_tags` that doesn't crash on this device.

    A previous release that crashes here can't have left its users any data to upgrade, so the release before it
    is the real starting point. A crash of THIS build still fails.
    """
    crashed = []
    for tag in previous_tags:
        previous_apk = previous_apks(tag, device.kind)
        chain = (phone_upgrade if device.kind == "phone" else watch_upgrade)(tag, previous_apk, apk)
        rows, outcome = run_with_retry(label, e, chain, mappings)
        failure = outcome.get("upgrade")
        if isinstance(failure, StepFailure) and failure.crashed and failure.previous:
            log(f"{label}: previous release {tag} crashed, upgrading from the release before it")
            crashed.append(tag)
            continue
        if crashed:
            origin = f"from {tag} ({', '.join(crashed)} crashed on {label})"
            rows = [(l, s, st, f"{origin}; {m}" if m else origin) for l, s, st, m in rows]
        return rows
    return [(label, "upgrade", "skipped",
             f"not tested: previous release(s) {', '.join(crashed)} crashed on {label} (--previous can pick an older one)")]


# MARK: - Main


def check(args):
    started = datetime.datetime.now()
    out = Path(args.out or tempfile.mkdtemp(prefix="bookplayer-release-check-"))
    out.mkdir(parents=True, exist_ok=True)
    work = out / "work"
    work.mkdir(exist_ok=True)
    results, notes = [], []

    for tool in (ADB, EMULATOR, CMDLINE_TOOLS / "avdmanager"):
        if not tool.exists():
            raise SetupError(f"{tool} is missing (Android SDK at {SDK}; set ANDROID_HOME if it's elsewhere)")
    plan = devices()
    for device in plan:
        device.image()  # every image installed before anything is built
    log("testing on " + ", ".join(device.label for device in plan))

    if args.version and subprocess.run(["git", "-C", str(REPO), "rev-parse", "-q", "--verify", f"refs/tags/{args.version}"],
                                       capture_output=True).returncode == 0:
        raise SetupError(f"tag {args.version} already exists; --version is the release being prepared, which isn't "
                         "tagged yet (to recheck a shipped build, run without --version)")
    tag = args.previous or previous_tag()
    previous_tags = [t for t in (tag, older_tag(tag)) if t]
    commit = git("rev-parse", "HEAD")
    if git("status", "--porcelain", "--untracked-files=no"):
        notes.append(f"{REPO} has uncommitted changes; the check builds HEAD's commit ({commit[:8]}) without them")

    candidate, audits = build(commit, work, "candidate", audit=True)
    apks = out / "apks"
    apks.mkdir(exist_ok=True)
    signed = {kind: sign(candidate / f"{kind}.apk", apks / f"{kind}.apk") for kind in ("phone", "watch")}

    # Before any emulator: a library that can't load with 16 KB pages, or a by-name class R8 renamed, breaks the
    # app on every such device, and no scenario reaches every library or every by-name surface
    for kind in ("phone", "watch"):
        problems = native_library_problems(candidate / f"{kind}.apk")
        results.append(("build", f"{kind} native libraries", "failed" if problems else "passed",
                        "\n".join(problems) if problems else "every 64-bit library loads with 16 KB pages"))
    for module, passed, output in audits:
        results.append(("build", f"{module} R8 by-name classes", "passed" if passed else "failed",
                        output.splitlines()[-1] if passed and output else output))
    for row in results:
        log(f"{row[0]} {row[1]}: {row[2]}")
    if any(status == "failed" for _, _, status, _ in results):
        notes.append("emulator scenarios not run: fix the build first")
        write_report(out, started, tag, results, notes, passed=False)
        return 1

    previous_signed = {}

    def previous_apks(previous, kind):
        # Built (or taken from the cache) only when a device needs it
        if (previous, kind) not in previous_signed:
            entry = previous_build(previous, work)
            for each in ("phone", "watch"):
                previous_signed[(previous, each)] = sign(entry / f"{each}.apk", apks / f"{previous}-{each}.apk")
        return previous_signed[(previous, kind)]

    previous_apks(tag, "phone")  # fail early (exit 2) if the previous release can't be built
    setup_errors = []
    for index, device in enumerate(plan):
        label = device.label
        logs = out / device.avd.replace(f"{AVD_PREFIX}-", "")
        logs.mkdir(exist_ok=True)
        mappings = {"candidate": candidate / f"{device.kind}-mapping.txt"}
        for previous in previous_tags:
            mappings[previous] = CACHE / previous / f"{device.kind}-mapping.txt"
        e = Emulator(device, FIRST_PORT + 2 * index, logs)
        try:
            e.ensure_avd()
            log(f"{label}: booting {device.avd}")
            e.boot()
            chain = (phone_chain if device.kind == "phone" else watch_chain)(e, signed[device.kind])
            rows, _ = run_with_retry(label, e, chain, mappings)
            rows += run_upgrade(label, e, device, previous_tags, previous_apks, signed[device.kind], mappings)
            for row in rows:
                log(f"{row[0]} {row[1]}: {row[2]}")
            results += rows
        except (SetupError, subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
            # Keep what already ran and carry on with the other devices; the report says what couldn't run
            setup_errors.append(f"{label} could not run: {error}")
            log(setup_errors[-1])
            ran = {scenario for row_label, scenario, _, _ in results if row_label == label}
            scenarios = ("fresh", "import", "playback", "upgrade") if device.kind == "phone" else ("fresh", "upgrade")
            results += [(label, scenario, "not run", "could not run (see notes)")
                        for scenario in scenarios if scenario not in ran]
        finally:
            e.kill()

    notes += setup_errors
    passed = not setup_errors and all(status in ("passed", "retried", "skipped") for _, _, status, _ in results)
    if passed and args.version:
        # Reused as the next release's "previous" only if <version>'s tag turns out to be this commit plus the
        # version bump (see cached_build)
        cache_build(candidate, args.version, commit)
        prune_cache(keep=[args.version, *previous_tags])
    if not args.keep_build:
        shutil.rmtree(work, ignore_errors=True)

    write_report(out, started, tag, results, notes, passed, could_not_run=bool(setup_errors))
    return 2 if setup_errors else (0 if passed else 1)


def prune_cache(keep):
    for entry in CACHE.iterdir() if CACHE.exists() else []:
        if entry.name not in keep:
            shutil.rmtree(entry, ignore_errors=True)


ICONS = {"passed": "✅", "retried": "⚠️", "skipped": "⚠️", "failed": "❌", "not run": "➖"}


def write_report(out, started, tag, results, notes, passed, could_not_run=False):
    minutes = (datetime.datetime.now() - started).total_seconds() / 60
    warnings = any(status in ("retried", "skipped") for _, _, status, _ in results)
    if could_not_run:
        verdict = "COULD NOT RUN (partial results)"
    else:
        verdict = "FAILED" if not passed else ("PASSED (with warnings)" if warnings else "PASSED")
    lines = [f"# Release check: {verdict}", "",
             f"Previous release for the upgrade scenario: {tag}. Took {minutes:.1f} min.", ""]
    lines += [f"- {note}" for note in notes] + ([""] if notes else [])
    lines += ["| Device | Scenario | Result |", "|---|---|---|"]
    for label, scenario, status, message in results:
        first = message.splitlines()[0] if message else status
        lines.append(f"| {label} | {scenario} | {ICONS[status]} {first} |")
    for label, scenario, status, message in results:
        if status in ("failed", "retried", "skipped") and message:
            lines += ["", f"## {label} · {scenario} ({status})", "", "```", message, "```"]
    (out / "report.md").write_text("\n".join(lines) + "\n")
    (out / "report.json").write_text(json.dumps({
        "passed": passed, "could_not_run": could_not_run, "previous": tag, "notes": notes,
        "results": [{"device": l, "scenario": s, "status": st, "message": m} for l, s, st, m in results],
    }, indent=2))
    print("\n".join(lines))
    print(f"\nReport, logs and screenshots: {out}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", help="version being released; a passing build is cached under it")
    parser.add_argument("--previous", help="previous release tag (default: the latest tag reachable from HEAD)")
    parser.add_argument("--out", help="folder for the report, logs and screenshots (default: a new temp folder)")
    parser.add_argument("--keep-build", action="store_true", help="keep the build folder (work/)")
    args = parser.parse_args()
    try:
        sys.exit(check(args))
    except SetupError as error:
        print(f"\nRelease check could not run: {error}", file=sys.stderr)
        sys.exit(2)
    except subprocess.CalledProcessError as error:
        print(f"\nRelease check could not run: {' '.join(map(str, error.cmd))} failed:\n{error.stderr}", file=sys.stderr)
        sys.exit(2)
    except subprocess.TimeoutExpired as error:
        print(f"\nRelease check could not run: {' '.join(map(str, error.cmd))} timed out", file=sys.stderr)
        sys.exit(2)
    except Exception:  # noqa: BLE001 — a bug in this script must never read as a failed release (exit 1)
        traceback.print_exc()
        print("\nRelease check could not run: unexpected error in release_check.py (traceback above)", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
