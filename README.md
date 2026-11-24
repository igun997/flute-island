# Flute Island

A light, fast **dynamic island** for Android, built to look good and stay cheap on low-end phones (developed on a
Samsung Galaxy A22). It hugs the front-camera cut-out, shows notifications, music, weather and Jira tickets, and
gets out of the way in games and fullscreen apps.

## What it does

| | |
|---|---|
| **Notifications** | WhatsApp (own accent), Telegram and every other app, per-app on/off. Optional *Take over* removes the notification from the shade once the island showed it (tapping the island still opens the right chat); *Every app uses the island* goes further: the block list is ignored, quiet notifications go straight to Recent, and only live controls (calls, players, downloads) stay in the shade. |
| **Now playing** | Spotify and any media session: album art, equalizer, progress, previous / play-pause / next. |
| **Gestures** | Tap opens a panel, hold opens the control room (a line fills while you hold), swipe left/right switches panels, swipe up closes. The island shows a one-time tip, and the pill squashes and lights up under your finger. |
| **Tools mode** | Switch it in the control room. Polls Jira and pops up a ticket the moment it is reopened; the panel lists them, one tap opens the ticket. |
| **Weather** | Open-Meteo (no API key). Icon + temperature next to the camera, animated panel on tap, city search. |
| **Theme** | Follows the phone's dark/light mode, or flip it with the sun/moon button (long-press = back to auto). The small pill stays black so it merges with the lens. |
| **Stay out of the way** | Hidden (and alerts held, stale ones dropped) while a game or any fullscreen app is in front. |
| **First-run intro** | A Lottie logo reveal (the island drops in, a palm island rises) with staggered title and feature chips, once after install. |
| **Cover the status bar** | Optional. The island draws its own black bar over the system one (clock, wifi, signal for each SIM, battery) and merges with it: no outline, roomier slots, a 7-bar equalizer and a pulsing album ring. Apps with something waiting in Recent show their icons in the bar, where the system notification icons used to be. The bar ignores touches, so a swipe down pulls the shade exactly as usual; with the shade open it grows, rounds its corners, hides the shade's own clock row and shows each SIM's carrier. Per-SIM signal needs the optional *phone state* permission, otherwise only the default SIM shows. |
| **No double notifications** | With take-over on, the island clears the original notification. Samsung still shows its own pop-up ~1.5 s later, even for a cancelled notification, so the island swipes that pop-up away like a finger would (accessibility gesture permission; it reads only window geometry and the owner's package, never text). Switch: *Hide the original pop-up too*. |
| **Self-healing** | If the app crashes Android marks the accessibility service as crashed and never restarts it, so the island would just vanish. The control room now says *Island stopped* with a Restart button and the last crash reason. For automatic recovery run once: `adb shell pm grant dev.igun997.fluteisland android.permission.WRITE_SECURE_SETTINGS`; the app then switches its own service off and on again (also after boot and updates). |
| **Control room** | Fluid Compose UI: live preview of the real island, setup checklist, mode, alerts, weather, Jira, look & feel. Adaptive grid for wider screens. |

## Why an accessibility service?

Android draws the status bar *above* ordinary overlay windows and sends touches to it first, so a normal overlay
can neither cover the camera area nor be tapped there. An **accessibility overlay** is the one window type that sits
above the status bar and receives touches. `IslandAccessibilityService` therefore hosts the island. It asks for the
minimum: window-change events only (no gestures, no keys, no touch exploration). From them it reads two facts: the
foreground app's package (is it a game?) and the geometry of system windows (is the status bar showing?). It never
reads what is drawn inside a window. `IslandListenerService` (notification access) feeds notifications and media.

On Android 13+, a sideloaded APK needs *App info > ⋮ > Allow restricted settings* before the accessibility switch
can be turned on (the control room says so).

The system status bar itself cannot be hidden or replaced by a normal app (that needs root).

## Architecture

```
core/      pure Kotlin: IslandController (state machine), NotificationMapper, Weather/Jira/OAuth parsers   <- unit tested
data/      SettingsStore, Secrets (Keystore AES-GCM), Http, WeatherRepo, JiraSession (OAuth + API token)
service/   IslandAccessibilityService (host) + OverlayHost, IslandListenerService, MediaWatcher, Weather/Jira engines
ui/island  IslandSurface and its content: one pill whose size springs between states
ui/control the control room
backend/   jira-auth-proxy: Cloudflare Worker that keeps the Atlassian client secret off the phone
```

Performance choices: idle costs zero frames; one spring animates the pill while content is laid out at its final
size and revealed; the always-visible equalizer is a 30 fps canvas, not a looping Lottie; the overlay window is only
as big as the pill (so it never swallows touches) and is resized *before* a grow and *after* a shrink settles, always
around the camera's centre so nothing slides sideways.

## Jira sign-in (OAuth, no tokens to paste)

Atlassian's OAuth needs a client secret to redeem a code and an app cannot keep a secret, so `backend/jira-auth-proxy`
keeps it. One-time setup is in [`backend/jira-auth-proxy/README.md`](backend/jira-auth-proxy/README.md); then put the
client id and the worker URL in `gradle.properties` (`fluteJiraClientId`, `fluteJiraAuthProxy`). Until then the app
offers the manual API-token path (stored encrypted with the Android Keystore).

## Build, test

Builds and tests run on a remote machine through [rkotlin](https://github.com/igun997/rkotlin) (Daytona sandbox `dt`);
never run Gradle locally.

```sh
rkotlin test                          # unit tests (controller, mapper, parsers, OAuth)
rkotlin --install assembleStaging     # release-grade build + the debug remote control, installs on your phone
rkotlin assembleRelease               # shippable build (sign with your own keystore first)
( cd backend/jira-auth-proxy && npm test )
```

`staging` is minified and not debuggable (performs like release) but includes `src/devtools`, the debug-only remote
control used below. `debug` includes it too.

## End-to-end tests (real phone)

```sh
scripts/dev.sh grant                  # enable the two services over adb (keeps your other accessibility services)
python3 e2e/e2e.py                    # ~5 min; screenshots in e2e-out/
python3 e2e/e2e.py -k jira            # one section
scripts/jank.sh                       # frame-time stats for a fixed workload
```

It drives the real overlay (window geometry from `dumpsys window`), the real notification pipeline
(`cmd notification post`), a real `MediaSession`, take-over, theme switching, a fullscreen app fixture, a mock Jira over
`adb reverse` (`e2e/mock_jira.mjs`) and the control room UI. It wipes the app's data first.

## Assets

* Launcher icon: adaptive vector (+ monochrome for themed icons), `tools/make_art.sh icon` re-renders previews.
* Lottie: generated procedurally (`design/lottie/gen.mjs`), previewed in the official text-to-lottie player
  (`design/lottie-player`), exported to `app/src/main/res/raw`. See `design/lottie/README.md`.
* Hero / empty-state art: generated through the image endpoint with `tools/genimg.py` (credentials stay in the
  other project's `.env`, never copied), shipped as small WebP.
