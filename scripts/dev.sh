#!/usr/bin/env bash
# Small helpers for driving the debug/staging build on a connected phone.
#   scripts/dev.sh grant      switch on the notification listener + accessibility service via adb (dev convenience;
#                             normal users do this in the control room). Keeps any other accessibility services.
#   scripts/dev.sh shot NAME  screenshot to /tmp/e2e/NAME.png
#   scripts/dev.sh inject ... send a debug INJECT broadcast (see InjectReceiver)
set -euo pipefail
P=dev.igun997.fluteisland
LISTENER=$P/$P.service.IslandListenerService
HOST=$P/$P.service.IslandAccessibilityService
case "${1:-}" in
  grant)
    adb shell cmd notification disallow_listener $LISTENER >/dev/null || true
    adb shell cmd notification allow_listener $LISTENER
    cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
    case "$cur" in
      *"$HOST"*) ;;
      ""|null) adb shell settings put secure enabled_accessibility_services "$HOST" ;;
      *) adb shell settings put secure enabled_accessibility_services "$cur:$HOST" ;;
    esac
    adb shell settings put secure accessibility_enabled 1 ;;
  shot)
    mkdir -p /tmp/e2e; adb exec-out screencap -p > "/tmp/e2e/${2:?name}.png"; echo "/tmp/e2e/$2.png" ;;
  inject)
    shift; adb shell am broadcast -n $P/.debug.InjectReceiver -a $P.debug.INJECT "$@" >/dev/null ;;
  *) echo "usage: $0 grant|shot NAME|inject --es kind ..." >&2; exit 2 ;;
esac
