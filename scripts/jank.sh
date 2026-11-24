#!/usr/bin/env bash
# Drives a fixed island workload and prints frame-time stats from gfxinfo (lower = smoother).
set -euo pipefail
P=dev.igun997.fluteisland
S="$(dirname "$0")/dev.sh"
adb shell dumpsys gfxinfo $P reset >/dev/null
for i in 1 2 3; do
  $S inject --es kind notice --es app whatsapp --es title Rina --es text "Halo_besok_jadi_$i"; sleep 7
  $S inject --es kind jira --es key OPS-4$i --es summary "Checkout_fails"; sleep 9
done
$S inject --es kind fakemedia; sleep 3
for i in 1 2 3; do adb shell input tap 360 30; sleep 2.5; adb shell input tap 360 30; sleep 2; done
$S inject --es kind stopfakemedia
adb shell dumpsys gfxinfo $P | grep -E "Total frames rendered|Janky frames|50th|90th|95th|99th|Number Missed Vsync|Number High input latency|Number Slow UI|Number Slow bitmap|Number Slow issue draw"
