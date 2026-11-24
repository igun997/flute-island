# Flute Island

Dynamic Island for low-end Android phones. Goal: good-looking **and** fast on weak hardware.

## Scope

- **Notifications**: WhatsApp, Spotify (now playing / controls) and other apps, shown in the island.
- **Tools mode**: Jira integration, so a reopened ticket surfaces in the island.
- **Weather indicator** in the island.
- **Theme**: automatic, follows the phone's dark / light mode.
- **Animations**: Lottie JSON authored with the text-to-lottie workflow (Skottie-compatible).
- **Generated images / theme art**: image generator using the credentials in `~/FlutterProjects/kereta-api/.env` (`cx/*`). Keys stay in that file, never copied into this repo.
- **Control room** (settings UI): fluid, attractive design that stays flexible (adapts to screen size and content, smooth motion). Must stay light on low-end phones.

## Build

Builds and tests run remotely through [rkotlin](https://github.com/igun997/rkotlin) on a Daytona sandbox (`dt`). Never run Gradle locally.

```sh
rkotlin test
rkotlin build
rkotlin --install assembleDebug   # installs the apk on your phone with local adb
```
