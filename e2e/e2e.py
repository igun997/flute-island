#!/usr/bin/env python3
"""End-to-end run against a real phone over adb (uses only the standard library).

Needs the `staging` or `debug` build installed (they carry the remote-control receiver), one device attached, and node
for the mock Jira. It resets the app's data, grants the two permissions via adb, then drives the real overlay:
window geometry (dumpsys window), the real notification pipeline (cmd notification post), a real MediaSession, a
mock Jira over `adb reverse`, theme switching and the control room UI (uiautomator). Screenshots land in e2e-out/.

    python3 e2e/e2e.py            # everything
    python3 e2e/e2e.py -k jira    # only checks whose section name contains "jira"
"""
import argparse, json, re, shlex, subprocess, sys, time, urllib.request, xml.etree.ElementTree as ET
from pathlib import Path

PKG = "dev.igun997.fluteisland"
SERVICE = f"{PKG}/{PKG}.service.IslandListenerService"
HOST = f"{PKG}/{PKG}.service.IslandAccessibilityService"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "e2e-out"
MOCK_PORT = 8089

results = []
section = ""


def adb(*args, check=True, timeout=60):
    p = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def inject(**kw):
    # `adb shell` re-joins its arguments with spaces, so values containing spaces must be quoted for the device shell.
    parts = ["am", "broadcast", "-n", f"{PKG}/.debug.InjectReceiver", "-a", f"{PKG}.debug.INJECT"]
    for k, v in kw.items():
        parts += ["--es", k, shlex.quote(str(v))]
    adb("shell", " ".join(parts))


def shot(name):
    OUT.mkdir(exist_ok=True)
    path = OUT / f"{name}.png"
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout)
    return path


def check(name, ok, detail=""):
    results.append((section, name, bool(ok), detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail and not ok else ""))
    return ok


def selected(sec, only):
    return not only or only.lower() in sec.lower()


# --- device helpers --------------------------------------------------------------------------

def density():
    return int(re.search(r"(\d+)", sh("wm density")).group(1)) / 160.0


def screen():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return int(m.group(1)), int(m.group(2))


def island_window():
    """(left, top, right, bottom) in px of the overlay window, or None when it is not on screen."""
    out = sh("dumpsys window windows")
    m = re.search(r"Window\{[0-9a-f]+ u0 FluteIsland\}:(.*?)(?=\n  Window #|\Z)", out, re.S)
    if not m:
        return None
    f = re.search(r"Frames:.*?frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", m.group(1), re.S)
    return tuple(int(x) for x in f.groups()) if f else None


def wait_window(pred, timeout=6.0, step=0.25):
    end = time.time() + timeout
    win = None
    while time.time() < end:
        win = island_window()
        if pred(win):
            return win
        time.sleep(step)
    return win


def size(win):
    return (0, 0) if not win else (win[2] - win[0], win[3] - win[1])


def ui_dump():
    sh("uiautomator dump --windows /sdcard/e2e_ui.xml >/dev/null 2>&1 || uiautomator dump /sdcard/e2e_ui.xml >/dev/null 2>&1")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/e2e_ui.xml"))


def nodes(root):
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            yield n, tuple(int(x) for x in m.groups())


def find_node(root, text=None, desc=None, contains=False):
    for n, b in nodes(root):
        t, d = n.get("text", ""), n.get("content-desc", "")
        if text is not None and (text in t if contains else t == text):
            return b
        if desc is not None and (desc in d if contains else d == desc):
            return b
    return None


def texts(root):
    return {n.get("text") for n, _ in nodes(root) if n.get("text")} | {n.get("content-desc") for n, _ in nodes(root) if n.get("content-desc")}


def tap_box(b):
    sh(f"input tap {(b[0] + b[2]) // 2} {(b[1] + b[3]) // 2}")


def scroll_top():
    w, h = screen()
    for _ in range(5):
        sh(f"input swipe {w // 2} {int(h * 0.3)} {w // 2} {int(h * 0.85)} 250")
        time.sleep(0.25)
    time.sleep(0.6)


def scroll_find(text, tries=8, contains=False, from_top=True):
    w, h = screen()
    if from_top:
        scroll_top()
    for _ in range(tries):
        b = find_node(ui_dump(), text=text, contains=contains)
        if b:
            return b
        sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.3)} 350")
        time.sleep(0.5)
    return None


def shade_has(tag):
    """Is a notification with this tag in the live notification list (not the archive of removed ones)?"""
    dump = sh("dumpsys notification --noredact")
    m = re.search(r"^  Notification List:\n(.*?)^  \S", dump, re.S | re.M)
    return bool(m) and tag in m.group(1)


def state():
    """The island's state machine as the debug receiver reports it (panel, current alert, tickets, media...)."""
    adb("logcat", "-c")
    inject(kind="dump")
    time.sleep(0.6)
    line = adb("logcat", "-d", "-s", "E2E:I").strip().splitlines()[-1:] or [""]
    st = dict(re.findall(r"(\w+)=(.*?)(?= \w+=|$)", line[0].split("state ", 1)[-1]))
    return st


def pixel(path, x, y):
    out = subprocess.run(["convert", str(path), "-crop", "1x1+%d+%d" % (x, y), "-format", "%[fx:int(255*r)] %[fx:int(255*g)] %[fx:int(255*b)]", "info:"],
                         capture_output=True, text=True).stdout.split()
    return tuple(int(v) for v in out)


def luminance(rgb):
    return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]


def launch_home():
    sh("input keyevent KEYCODE_HOME")
    time.sleep(0.8)


def start_app():
    sh(f"am start -n {PKG}/.MainActivity")
    time.sleep(2.5)


# --- sections --------------------------------------------------------------------------------

def sec_setup():
    sh(f"am force-stop {PKG}")
    sh(f"pm clear {PKG}")
    # Granting a runtime permission restarts the app process, so do it now, before anything is running.
    sh(f"pm grant {PKG} android.permission.READ_PHONE_STATE")
    sh(f"pm grant {PKG} android.permission.WRITE_SECURE_SETTINGS")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb("shell", "cmd", "notification", "disallow_listener", SERVICE, check=False)
    adb("shell", "cmd", "notification", "allow_listener", SERVICE)
    cur = sh("settings get secure enabled_accessibility_services").strip()
    if HOST not in cur:  # append, never clobber the user's other accessibility services
        sh(f"settings put secure enabled_accessibility_services {HOST if cur in ('', 'null') else cur + ':' + HOST}")
    sh("settings put secure accessibility_enabled 1")
    sh("settings put system screen_off_timeout 600000")
    sh("input keyevent KEYCODE_WAKEUP")
    sh(f"am start -n {PKG}/.MainActivity")
    # first launch after install: the animated intro, with a call to action once the animation has played
    cta = None
    end = time.time() + 12
    while time.time() < end and cta is None:
        time.sleep(0.8)
        cta = find_node(ui_dump(), text="Get started") or find_node(ui_dump(), desc="Get started")
        if cta is None:
            shot("00_intro_playing")
    check("first launch shows the intro with a Get started button", cta is not None)
    shot("00_intro_done")
    if cta:
        tap_box(cta)
        time.sleep(2.0)
    root = ui_dump()
    check("tapping Get started leads to the control room", "Live preview" in texts(root) or "Flute Island" in texts(root), str(sorted(texts(root)))[:160])
    end = time.time() + 15
    bound = False
    while time.time() < end and not bound:
        bound = SERVICE.split("/")[1] in re.sub(r"\s", "", sh("dumpsys notification | sed -n '/Live notification listeners/,/Snoozed/p'")) \
            or PKG in sh("dumpsys notification | sed -n '/Live notification listeners/,/Snoozed/p'")
        time.sleep(1)
    check("notification listener is bound by the system", bound)
    root = ui_dump()
    t = texts(root)
    check("control room opens with the brand title", "Flute Island" in t, str(sorted(t))[:200])
    check("setup is complete (permissions granted)", "Island is live" in t)
    time.sleep(2.5)
    check("the one-time gesture tip was posted by the island", int(state().get("recent", "0") or 0) >= 1, str(state()))
    shot("01_control_room_top")


def sec_island_geometry():
    d = density()
    sw, _ = screen()
    inject(kind="clear")
    inject(kind="weather", temp="27.5", code="2")
    win = wait_window(lambda w: w is not None, 6)
    check("idle pill is shown for the weather chip", win is not None)
    w, h = size(win)
    check("idle pill hugs the camera cut-out (wider than the lens, about 28-38dp tall)", 200 <= w <= 300 and 50 <= h <= 72, f"{w}x{h}px")
    cx = (win[0] + win[2]) / 2 if win else 0
    check("pill is centred on the screen's camera position", abs(cx - sw / 2) <= 8, f"centre {cx} vs {sw / 2}")
    check("pill sits at the very top (over the cut-out)", win is not None and win[1] <= 2, str(win))
    shot("02_idle_weather")

    inject(kind="notice", app="whatsapp", title="Angga Kuda", text="Besok jadi ketemu jam 10?")
    win = wait_window(lambda w: w and size(w)[0] > 600, 4)
    aw, ah = size(win)
    check("alert card expands to a wide card", aw >= 640, f"{aw}x{ah}px")
    check("alert card starts below the status bar (no overlap with its icons)", win and 58 <= (win[1] + ah) and ah >= 150, str(win))
    time.sleep(0.6)
    shot("03_alert_whatsapp")
    time.sleep(7)
    win = wait_window(lambda w: w and size(w)[0] < 320, 4)
    check("alert collapses back to the compact pill", win is not None and size(win)[0] < 320, str(size(win)))
    check("pill is still centred after collapsing (no sideways drift)", win is not None and abs((win[0] + win[2]) / 2 - sw / 2) <= 8, str(win))

    inject(kind="jira", key="OPS-42", summary="Checkout fails on Samsung A-series")
    win = wait_window(lambda w: w and size(w)[0] > 600, 4)
    check("jira alert uses the wide card", win is not None and size(win)[0] >= 640)
    time.sleep(1.2)
    shot("04_alert_jira")
    inject(kind="clear")
    time.sleep(1.5)


def listens_to_real_notifications(fn):
    """Sections that post through the real notification service need the listener on; the rest keep it off."""
    def wrapped():
        inject(kind="realnotifs", value="true")
        try:
            fn()
        finally:
            inject(kind="realnotifs", value="false")
    wrapped.__name__ = fn.__name__
    return wrapped


@listens_to_real_notifications
def sec_real_notification():
    inject(kind="clear")
    sh('cmd notification post -S bigtext -t "E2E real path" e2e_tag "Posted through the real notification service"')
    win = wait_window(lambda w: w and size(w)[0] > 600, 5)
    check("a real posted notification reaches the island", win is not None and size(win)[0] >= 640, str(win))
    time.sleep(0.8)
    shot("05_real_notification")

    # take-over: the island becomes the only surface; the shade copy is removed.
    inject(kind="clear")
    time.sleep(7)
    inject(kind="takeover", value="true")
    tag = f"e2e_take_{int(time.time())}"
    sh(f'cmd notification post -S bigtext -t "Take over" {tag} "Should vanish from the shade"')
    win = wait_window(lambda w: w and size(w)[0] > 600, 5)
    check("take-over: notification still shows in the island", win is not None and size(win)[0] >= 640)
    time.sleep(1.0)
    # positive control first: a notification posted without take-over must be listed in the live shade, so "absent"
    # means something. (The dump's archive of recently removed notifications is deliberately not counted.)
    inject(kind="takeover", value="false")
    ctl = f"e2e_ctl_{int(time.time())}"
    sh(f'cmd notification post -S bigtext -t "Control" {ctl} "Stays in the shade"')
    time.sleep(1.5)
    check("control: without take-over the notification stays in the shade", shade_has(ctl))
    check("take-over: notification was removed from the shade", not shade_has(tag))
    inject(kind="cleanshell")
    time.sleep(0.8)
    shot("06_takeover")
    inject(kind="takeover", value="true")  # leave the phone on the default (the run must not change the user's settings)
    inject(kind="clear")
    time.sleep(1.5)


def sec_inbox():
    """With take-over on, shown notifications live on in the island's Recent panel."""
    sw, _ = screen()
    inject(kind="clear")
    inject(kind="clearrecent")
    inject(kind="weather", temp="27", code="2")
    time.sleep(1.0)
    inject(kind="notice", app="whatsapp", title="Angga Kuda", text="First")
    time.sleep(1.0)
    inject(kind="notice", app="other", title="Budi", text="Second")
    time.sleep(1.0)
    # a real notification on the phone may land in the inbox too
    check("both alerts are in the inbox", int(state().get("recent") or 0) >= 2, str(state()))
    inject(kind="clear")
    time.sleep(1.5)
    sh(f"input tap {sw // 2} 28")
    win = wait_window(lambda w: w and size(w)[1] > 200, 4)
    check("tapping the idle pill opens the Recent panel", state().get("panel") == "INBOX", str(state()))
    time.sleep(0.8)
    shot("22_inbox_panel")
    sh(f"input swipe {int(sw * 0.8)} 220 {int(sw * 0.2)} 220 200")
    time.sleep(1.0)
    check("sideways swipe moves to the next panel (weather)", state().get("panel") == "WEATHER", str(state()))
    shot("23_swiped_to_weather")
    sh(f"input swipe {int(sw * 0.2)} 220 {int(sw * 0.8)} 220 200")
    time.sleep(1.0)
    check("swiping back returns to Recent", state().get("panel") == "INBOX", str(state()))
    inject(kind="clearrecent")
    time.sleep(1.2)
    check("clearing the inbox closes it", state().get("recent") == "0" and state().get("panel") == "NONE", str(state()))


def sec_media():
    d = density()
    sw, _ = screen()
    inject(kind="clear")
    inject(kind="fakemedia", title="Sunset Over Lagoon", artist="Flute Island")
    time.sleep(2.5)
    check("real MediaSession is picked up", state().get("media") == "Sunset Over Lagoon", str(state()))
    # tap the compact pill: the media panel opens
    sh(f"input tap {sw // 2} 28")
    win = wait_window(lambda w: w and size(w)[1] > 330, 4)
    check("tapping the pill opens the media panel", win is not None and size(win)[1] > 330, str(size(win)))
    check("state machine agrees (panel=MEDIA)", state().get("panel") == "MEDIA")
    time.sleep(0.8)
    shot("07_media_panel")
    win = island_window()
    if win:
        y = int(win[3] - (10 + 24) * d)          # controls row sits 10dp above the card's bottom edge
        sh(f"input tap {sw // 2} {y}")           # pause
        time.sleep(1.2)
        sessions = sh("dumpsys media_session")
        paused = re.search(r"fluteisland-fake.*?state=PlaybackState \{state=(\w+)\((\d+)\)", sessions, re.S)
        check("pause button controls the real session", paused is not None and paused.group(2) == "2", paused.group(0)[-60:] if paused else "no session")
        sh(f"input tap {int(sw // 2 + 66 * d)} {y}")  # next
        time.sleep(1.2)
        check("next button skips the track", "Next Track" in sh("dumpsys media_session"))
    # swipe up on the card closes the panel
    sh(f"input swipe {sw // 2} 330 {sw // 2} 40 220")
    time.sleep(1.2)
    check("swipe up closes the panel", state().get("panel") == "NONE", str(state()))
    inject(kind="stopfakemedia")
    time.sleep(1.0)


def sec_theme():
    sw, _ = screen()
    lum = {}
    for mode in ("light", "dark"):
        inject(kind="theme", value=mode)
        inject(kind="clear")
        time.sleep(1.2)
        inject(kind="notice", app="other", title="Theme probe", text="Colour check")
        wait_window(lambda w: w and size(w)[0] > 600, 4)
        time.sleep(1.2)
        p = shot(f"08_theme_{mode}")
        lum[mode] = luminance(pixel(p, sw - 120, 140))
        inject(kind="clear")
        time.sleep(1.5)
    check("light theme card is light and dark theme card is dark", lum["light"] > 200 and lum["dark"] < 60, str(lum))
    inject(kind="theme", value="auto")

    # compact pill stays black in both themes so it merges with the lens
    inject(kind="weather", temp="27", code="1")
    time.sleep(1.5)
    inject(kind="theme", value="light")
    time.sleep(1.5)
    p = shot("09_compact_light_theme")
    win = island_window()
    if win:
        c = pixel(p, win[0] + 14, (win[1] + win[3]) // 2)
        check("compact pill stays black in the light theme (blends with the camera)", luminance(c) < 40, str(c))
    inject(kind="theme", value="auto")


def sec_jira():
    mock = subprocess.Popen(["node", str(ROOT / "e2e" / "mock_jira.mjs"), str(MOCK_PORT)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(1.0)
        adb("reverse", f"tcp:{MOCK_PORT}", f"tcp:{MOCK_PORT}")
        inject(kind="clear")
        inject(kind="jiratoken", base=f"http://localhost:{MOCK_PORT}", email="e2e@example.com", token="token123")
        inject(kind="mode", value="tools")
        time.sleep(1.0)

        def log():
            return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{MOCK_PORT}/__log", timeout=5).read())

        end = time.time() + 15
        while time.time() < end and not log():
            time.sleep(0.7)
        entries = log()
        check("tools mode polls the Jira search endpoint", bool(entries))
        check("request is authenticated with the stored token", entries and entries[0]["auth"] is True)
        check("request carries the reopened-tickets JQL", entries and "Reopened" in (entries[0]["jql"] or ""), str(entries[:1]))

        sw, _ = screen()
        win = wait_window(lambda w: w is not None, 4)
        check("tools-mode idle pill is shown", win is not None)
        shot("10_tools_idle")
        # tap pill -> tools panel listing the reopened ticket
        sh(f"input tap {sw // 2} 28")
        win = wait_window(lambda w: w and size(w)[1] > 200, 4)
        check("tapping the pill opens the tools panel", win is not None and size(win)[1] > 200, str(size(win)))
        time.sleep(0.8)
        shot("11_tools_panel")
        st = state()
        check("tools panel is open and holds the reopened ticket", st.get("panel") == "TOOLS" and "OPS-41" in st.get("tickets", ""), str(st))

        # a new ticket arrives -> alert after the refresh button
        urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{MOCK_PORT}/__tickets", method="POST",
                               data=json.dumps([{"key": "OPS-41", "summary": "Login loops on retry"}, {"key": "OPS-77", "summary": "Invoice PDF is blank"}]).encode()), timeout=5)
        d = density()
        win = island_window()
        if win:
            card_h = (52 + 58 * 1 + 14) * d                 # one ticket row
            card_top = win[3] - card_h
            sh(f"input tap {int(win[2] - (16 + 14) * d)} {int(card_top + (8 + 18) * d)}")   # refresh button in the header
        time.sleep(3.0)
        shot("12_tools_after_refresh")
        n_after = len(log())
        check("refresh button triggers another poll", n_after >= 2, str(n_after))
        check("panel now lists both tickets", "OPS-77" in state().get("tickets", ""), str(state()))
        sh(f"input swipe {sw // 2} 330 {sw // 2} 40 220")
        time.sleep(1.5)
        # the new ticket is announced once the panel is closed
        win = wait_window(lambda w: w and size(w)[0] > 600, 6)
        check("a newly reopened ticket pops up as an alert", win is not None and size(win)[0] >= 640, str(win))
        time.sleep(1.0)
        shot("13_jira_alert_new_ticket")
    finally:
        inject(kind="mode", value="normal")
        inject(kind="jirasignout")
        inject(kind="clear")
        inject(kind="cleanshell")
        adb("reverse", "--remove", f"tcp:{MOCK_PORT}", check=False)
        mock.terminate()


def sec_control_room():
    launch_home()
    start_app()
    sh("input swipe 360 1200 360 400 300")  # scroll to reveal the cards
    time.sleep(0.8)
    shot("14_control_room_scrolled")
    for label in ("Mode", "Alerts", "Weather", "Jira", "Look & feel", "Try it"):
        found = scroll_find(label, from_top=False)
        check(f"control room has the '{label}' card", found is not None)
    start_app()

    # sub screens
    b = scroll_find("Apps")
    check("Apps row is reachable", b is not None)
    if b:
        tap_box(b)
        time.sleep(1.2)
        t = texts(ui_dump())
        check("Apps screen lists installed apps with a search box", "Search apps" in t or any("Search apps" in (x or "") for x in t))
        shot("15_apps_screen")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)
    b = scroll_find("City")
    check("City row is reachable", b is not None)
    if b:
        tap_box(b)
        time.sleep(1.2)
        field = find_node(ui_dump(), text="Search a city")
        if field:
            tap_box(field)
            time.sleep(0.6)
        sh("input text Bandung")
        found = False
        for _ in range(8):  # the geocoding call is a real network request; give it up to ~10 s
            time.sleep(1.4)
            if any("Bandung," in (x or "") for x in texts(ui_dump())):
                found = True
                break
        check("City search returns results", found)
        shot("16_city_screen")
        sh("input keyevent KEYCODE_BACK")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)
    start_app()
    b = scroll_find("Not connected")  # the Jira card's status line (a bare "Jira" would match the preview chip)
    check("Jira card is reachable", b is not None)
    if b:
        tap_box(b)
        time.sleep(1.2)
        t = texts(ui_dump())
        check("Jira screen offers sign-in or API token", any("API token" in (x or "") for x in t))
        shot("17_jira_screen")
        sh("input keyevent KEYCODE_BACK")
        time.sleep(0.8)

    # theme flip button in the hero
    start_app()
    scroll_top()
    b = find_node(ui_dump(), desc="Flip light and dark theme")
    check("hero has a light/dark flip button", b is not None)
    if b:
        before = luminance(pixel(shot("18_before_flip"), 40, 900))
        tap_box(b)
        time.sleep(1.0)
        after = luminance(pixel(shot("19_after_flip"), 40, 900))
        check("flip button switches the control room theme", abs(before - after) > 80, f"{before:.0f} -> {after:.0f}")
        # long press returns to following the phone
        b = find_node(ui_dump(), desc="Flip light and dark theme")
        if b:
            x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            sh(f"input swipe {x} {y} {x} {y} 700")
        time.sleep(1.0)
    inject(kind="theme", value="auto")


def sec_motion():
    """The collapse animation must keep the pill centred: no sliding in from a side after the card closes."""
    sw, _ = screen()
    inject(kind="clear")
    inject(kind="weather", temp="27", code="2")
    inject(kind="theme", value="light")
    sh("am start -a android.settings.SETTINGS")  # a white screen: the black compact pill is easy to measure
    time.sleep(2.5)
    sh("rm -f /sdcard/e2e_rec.mp4")
    rec = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "12", "--size", f"{sw}x1600", "--bit-rate", "10000000", "/sdcard/e2e_rec.mp4"])
    time.sleep(1.5)
    inject(kind="notice", app="whatsapp", title="Angga Kuda", text="Halo_besok_jadi")
    rec.wait()
    OUT.mkdir(exist_ok=True)
    adb("pull", "/sdcard/e2e_rec.mp4", str(OUT / "motion.mp4"))
    frames = OUT / "motion_frames"
    subprocess.run(["rm", "-rf", str(frames)])
    frames.mkdir()
    subprocess.run(["ffmpeg", "-v", "error", "-i", str(OUT / "motion.mp4"), "-vf", f"fps=30,crop={sw}:80:0:0", str(frames / "f_%04d.png")], check=True)
    from PIL import Image

    def pill(path):
        im = Image.open(path).convert("RGB")
        w, _ = im.size
        px = im.load()
        cols = [sum(1 for y in range(8, 52, 3) if sum(px[x, y]) < 150) >= 8 for x in range(w)]
        best, start, last = (0, 0), None, None
        for x, on in enumerate(cols):
            if on:
                if start is None:
                    start = x
                elif x - last > 45:
                    best = (start, last) if last - start > best[1] - best[0] else best
                    start = x
                last = x
        if start is not None and last - start > best[1] - best[0]:
            best = (start, last)
        return best

    seen, off = 0, []
    for f in sorted(frames.glob("f_*.png")):
        a, b = pill(f)
        if b - a > 100:
            seen += 1
            if abs((a + b) // 2 - sw // 2) > 6:
                off.append((f.name, (a + b) // 2, b - a))
    check("the pill was visible in the recording", seen > 20, f"{seen} frames")
    check("the pill never slides in from a side (always centred on the camera)", not off, str(off[:6]))
    inject(kind="theme", value="auto")
    launch_home()


@listens_to_real_notifications
def sec_gestures():
    """Hold opens the control room; every app can be routed through the island."""
    sw, _ = screen()
    inject(kind="clear")
    inject(kind="weather", temp="27", code="2")
    launch_home()
    time.sleep(1.5)
    sh(f"input swipe {sw // 2} 28 {sw // 2} 28 800")  # a long press on the pill
    time.sleep(2.0)
    focus = sh("dumpsys window | grep mCurrentFocus")
    check("long-pressing the pill opens the control room", "MainActivity" in focus, focus.strip()[:120])
    launch_home()

    # every-app mode: a blocked app is ignored normally, and used (and removed from the shade) when the mode is on
    inject(kind="block", value="com.android.shell")
    inject(kind="takeover", value="false")
    inject(kind="allapps", value="false")
    inject(kind="clear")
    time.sleep(7)
    t1 = f"e2e_off_{int(time.time())}"
    sh(f'cmd notification post -S bigtext -t "Blocked app" {t1} "should only live in the shade"')
    time.sleep(2.5)
    st = state()
    # a real notification of the phone owner may be on screen, so only this test's own title counts
    check("blocked app: no island alert", st.get("current") != "Blocked app", str(st))
    check("blocked app: notification stays in the shade", shade_has(t1))

    inject(kind="allapps", value="true")
    inject(kind="clear")
    time.sleep(1)
    t2 = f"e2e_all_{int(time.time())}"
    sh(f'cmd notification post -S bigtext -t "Every app" {t2} "now through the island"')
    time.sleep(2.5)
    st = state()
    check("every-app mode: the blocked app now shows in the island", st.get("current") == "Every app", str(st))
    check("every-app mode: nothing is left in the shade", not shade_has(t2))
    inject(kind="allapps", value="false")
    inject(kind="takeover", value="true")
    inject(kind="unblock", value="com.android.shell")
    inject(kind="cleanshell")
    inject(kind="clear")


def cover_window():
    """(left, top, right, bottom) of the status-bar cover window, or None."""
    out = sh("dumpsys window windows")
    m = re.search(r"Window\{[0-9a-f]+ u0 FluteCover\}:(.*?)(?=\n  Window #|\Z)", out, re.S)
    if not m:
        return None
    f = re.search(r"Frames:.*?frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", m.group(1), re.S)
    return tuple(int(x) for x in f.groups()) if f else None


def sec_cover():
    """The optional full cover of the status bar: it replaces the system icons but never steals a swipe."""
    sw, sh_ = screen()
    launch_home()
    inject(kind="clear")
    inject(kind="clearrecent")
    inject(kind="weather", temp="27", code="2")
    inject(kind="cover", value="true")
    time.sleep(2.5)
    win = cover_window()
    check("cover window spans the whole top of the screen", win is not None and win[2] - win[0] >= sw - 2 and win[1] <= 0 and 40 <= win[3] <= 140, str(win))
    p = shot("30_cover_on")
    from PIL import Image
    im = Image.open(p).convert("RGB")
    left_black = all(sum(im.getpixel((x, 30))) < 90 for x in (110, 150, 200))
    right_has_content = any(sum(im.getpixel((x, 30))) > 450 for x in range(sw - 190, sw - 20))
    check("the cover is black (system icons hidden), with indicators on the right", left_black and right_has_content, f"left_black={left_black} right={right_has_content}")
    def shade_open():
        return "NotificationShade" in sh("dumpsys window | grep mCurrentFocus")

    # a swipe down from the very top edge (outside the pill) must pull the shade, exactly like normal
    sh("input swipe 80 4 80 900 250")
    time.sleep(1.6)
    retried = False
    if not shade_open():  # the very first injected swipe after a fresh setup is occasionally eaten by the launcher
        retried = True
        sh("cmd statusbar collapse")
        time.sleep(1.0)
        sh("input swipe 80 4 80 900 250")
        time.sleep(1.6)
    check("swiping down from the top still opens the shade" + (" (second try)" if retried else ""), shade_open())
    p2 = shot("31_shade_with_cover")
    im2 = Image.open(p2).convert("RGB")
    check("the cover stays over the bar while the shade is open (the island keeps being the top bar)", luminance(im2.getpixel((110, 30))) < 40)
    check("the shade itself is visible below the cover", luminance(im2.getpixel((110, 300))) > 120)
    sh("cmd statusbar collapse")
    time.sleep(1.2)
    # swipe down on the island pill itself also opens the shade (the pill would otherwise swallow it)
    sh(f"input swipe {sw // 2} 25 {sw // 2} 700 250")
    time.sleep(1.6)
    check("swiping down on the pill opens the shade too", shade_open())
    sh("cmd statusbar collapse")
    # the covered bar shows which apps have something waiting
    def colourful(path):
        im = Image.open(path).convert("RGB")
        n = 0
        for x in range(130, 320, 2):
            for y in range(8, 50, 2):
                r, g, b = im.getpixel((x, y))
                if max(r, g, b) - min(r, g, b) > 70 and max(r, g, b) > 90:
                    n += 1
        return n
    before = colourful(shot("32_cover_no_apps"))
    inject(kind="notice", app="whatsapp", title="Angga Kuda", text="Halo dari e2e")
    time.sleep(7)
    after = colourful(shot("33_cover_app_icon"))
    check("the covered bar shows the icon of an app with a waiting notification", after > before + 15, f"before={before} after={after}")
    inject(kind="clearrecent")
    time.sleep(1.2)
    check("the app icon leaves the bar when the inbox is cleared", colourful(shot("34_cover_cleared")) < after - 15)
    inject(kind="cover", value="false")
    time.sleep(1.5)
    check("switching the cover off removes it", cover_window() is None)


def sec_repair():
    """A crash makes Android flag the accessibility service as crashed and never restart it; the app must revive itself."""
    launch_home()
    inject(kind="weather", temp="27", code="2")
    time.sleep(2)
    check("island is up before the crash", island_window() is not None)
    inject(kind="crash")
    crashed = False
    for _ in range(12):  # Android lists the service as crashed for about three seconds
        time.sleep(0.3)
        crashed = "Crashed services:{{dev.igun997.fluteisland" in sh("dumpsys accessibility | grep 'Crashed services'")
        if crashed:
            break
    check("the crash left the accessibility service flagged as crashed (the real failure mode)", crashed)
    # nothing but the app's own wake-up should be needed: the listener comes back and revives the host
    ok = wait_window(lambda w: w is not None, timeout=40, step=1.0) is not None
    check("the island came back by itself after the crash", ok)
    time.sleep(1)
    check("the service is no longer flagged as crashed", "Crashed services:{{dev.igun997.fluteisland" not in sh("dumpsys accessibility | grep 'Crashed services'"))


def sec_popup():
    """Samsung shows its own pop-up ~1.5 s after a notification, even if it was cancelled meanwhile: the island sweeps it."""
    from PIL import Image
    launch_home()
    inject(kind="clear")
    inject(kind="cover", value="false")
    inject(kind="quietoriginals", value="true")
    def popup_pixels(name):
        im = Image.open(shot(name)).convert("RGB")
        return sum(1 for x in range(200, 540, 10) for y in range(70, 120, 5) if luminance(im.getpixel((x, y))) > 200)
    base = popup_pixels("40_popup_none")  # the lock-screen clock also has white pixels up here
    # control: without the sweep the system pop-up is there (Samsung occasionally holds one back, so allow a second try)
    seen = 0
    for attempt in range(2):
        inject(kind="hupost", cancel_ms="60")
        for _ in range(6):  # the pop-up's delay varies a little
            time.sleep(0.6)
            seen = max(seen, popup_pixels("40_popup_control"))
        if seen > base + 30:
            break
        time.sleep(12)
    check("control: without the sweep the system pop-up shows even though the notification was cancelled", seen > base + 30, f"white pixels={seen} (baseline {base})")
    time.sleep(5)
    inject(kind="hupost", cancel_ms="60", sweep="true")
    gone = None
    for _ in range(8):
        time.sleep(0.6)
        gone = popup_pixels("41_popup_swept")
    check("with the sweep the system pop-up is swiped away", gone is not None and gone <= base + 6, f"white pixels={gone} (baseline {base})")


def sec_quiet():
    """Games and fullscreen apps: the island must stay out of the way and hold alerts until they end."""
    sw, _ = screen()
    inject(kind="quiet", value="true")
    inject(kind="clear")
    inject(kind="weather", temp="27", code="2")
    launch_home()
    time.sleep(2)
    win = island_window()
    check("island is visible on the launcher, above the status bar", win is not None and size(win)[0] > 100 and win[1] <= 2, str(win))

    sh(f"am start -n {PKG}/.debug.FullscreenActivity")
    time.sleep(3.5)
    w, h = size(island_window())
    check("island hides itself in a fullscreen app (no window, only an untouchable dot)", w <= 2 and h <= 2, f"{w}x{h}")
    shot("20_fullscreen_hidden")
    inject(kind="notice", app="whatsapp", title="Held alert", text="Arrives after the game")
    time.sleep(2.5)
    w, h = size(island_window())
    check("alerts do not appear over a fullscreen app", w <= 2 and h <= 2, f"{w}x{h}")
    sh("input keyevent KEYCODE_BACK")
    win = wait_window(lambda w: w and size(w)[0] > 600, 8)
    check("the held alert shows once the fullscreen app ends", win is not None and size(win)[0] >= 640, str(win))
    shot("21_held_alert_after_fullscreen")
    time.sleep(7)

    # with quiet mode off the island ignores fullscreen
    inject(kind="quiet", value="false")
    sh(f"am start -n {PKG}/.debug.FullscreenActivity")
    time.sleep(3.5)
    check("quiet mode off: island stays visible in fullscreen", island_window() is not None and size(island_window())[0] > 100)
    sh("input keyevent KEYCODE_BACK")
    inject(kind="quiet", value="true")
    time.sleep(1.0)


SECTIONS = [
    ("setup", sec_setup),
    ("island geometry and alerts", sec_island_geometry),
    ("real notification and take-over", sec_real_notification),
    ("animation continuity", sec_motion),
    ("gestures", sec_gestures),
    ("status bar cover", sec_cover),
    ("system pop-up", sec_popup),
    ("self repair", sec_repair),
    ("quiet mode", sec_quiet),
    ("inbox", sec_inbox),
    ("media", sec_media),
    ("theme", sec_theme),
    ("jira tools mode", sec_jira),
    ("control room", sec_control_room),
]


def main():
    global section
    ap = argparse.ArgumentParser()
    ap.add_argument("-k", default="", help="only run sections whose name contains one of these (comma separated)")
    only = [k.strip().lower() for k in ap.parse_args().k.split(",") if k.strip()]
    devices = [l for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    if len(devices) != 1:
        sys.exit(f"expected exactly one adb device, found {len(devices)}")
    if PKG + "/.debug.InjectReceiver" not in sh(f"dumpsys package {PKG}") and ".debug.InjectReceiver" not in sh(f"dumpsys package {PKG}"):
        sys.exit("install the staging or debug build first (it carries the e2e remote control)")
    launch_home()
    for name, fn in SECTIONS:
        if name != "setup" and only and not any(k in name.lower() for k in only):
            continue
        section = name
        print(f"\n== {name}")
        try:
            fn()
        except Exception as e:  # a crashed section fails loudly instead of hiding the rest
            check("section completed without an error", False, repr(e))
        if name == "setup":
            # the owner's real notifications must not leak into the tests; only the real-notification section listens
            inject(kind="realnotifs", value="false")
    inject(kind="realnotifs", value="true")  # back to the default
    failed = [r for r in results if not r[2]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed; screenshots in {OUT}")
    for s, n, _, d in failed:
        print(f"  FAILED [{s}] {n} {d}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
