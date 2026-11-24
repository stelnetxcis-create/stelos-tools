#!/usr/bin/env bash
# measure.sh — checks the Stelnet Tools performance budgets (helper RSS, CPU, wakeups; app PSS and wakelocks).
#
#   tools/measure.sh [--fresh] [seconds]     sample for N seconds (default 10)
#
# PC: the ii_link helper's RSS, threads, CPU and wakeups (context switches) over the
# window, plus the shell's RSS for comparison. Phone (over adb, if attached; set
# ANDROID_SERIAL when several are): the app's memory (PSS, swapped, native and Java heap,
# threads, GPU buffers, whether a screen ran in this process), its battery since the last
# charge (CPU, Wi-Fi bytes, wakelocks, estimated mAh), the link's connect/drop counts and
# any wakelock it holds now.
#
# --fresh restarts the app without opening a screen (force-stop, then the notification
# listener binds it again) and waits 20 s: the background-only figure, apart from what a
# screen leaves behind. It drops the link for a few seconds.
#
# The native heap in detail (release builds are profileable): Perfetto's heap_profile,
#   tools/heap_profile -n net.stelnet.tools   (https://perfetto.dev/docs/data-sources/native-heap-profiler)
set -uo pipefail
FRESH=0
if [[ "${1:-}" == "--fresh" ]]; then FRESH=1; shift; fi
WINDOW="${1:-10}"
HZ=$(getconf CLK_TCK)

cpu_ticks() { awk '{print $14 + $15}' "/proc/$1/stat" 2>/dev/null; }
switches() { awk '/ctxt_switches/ {s += $2} END {print s}' "/proc/$1/status" 2>/dev/null; }
rss_mb() { awk '/VmRSS/ {printf "%.1f", $2 / 1024}' "/proc/$1/status" 2>/dev/null; }

echo "== PC (window ${WINDOW}s)"
LINK=$(pgrep -x ii_link | head -1)
QS=$(pgrep -x quickshell | head -1 || pgrep -f 'qs .*-c ii' | head -1)
if [[ -n "$LINK" ]]; then
    t0=$(cpu_ticks "$LINK"); w0=$(switches "$LINK")
    sleep "$WINDOW"
    t1=$(cpu_ticks "$LINK"); w1=$(switches "$LINK")
    cpu=$(awk -v a="$t0" -v b="$t1" -v hz="$HZ" -v w="$WINDOW" 'BEGIN {printf "%.2f", (b - a) / hz / w * 100}')
    threads=$(awk '/Threads/ {print $2}' "/proc/$LINK/status")
    rss=$(rss_mb "$LINK")
    verdict=$(awk -v r="$rss" 'BEGIN {print (r <= 8) ? "ok" : "OVER BUDGET"}')
    echo "ii_link   pid $LINK  rss ${rss} MB ($verdict, budget 8)  threads $threads  cpu ${cpu}%  wakeups $((w1 - w0)) in ${WINDOW}s"
else
    echo "ii_link   not running (no phone paired, or the link is off) — costs nothing"
fi
[[ -n "$QS" ]] && echo "shell     pid $QS  rss $(rss_mb "$QS") MB"

echo "== Phone"
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    # Several devices (USB and wireless debugging): the first USB one.
    ANDROID_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /:/ {print $1; exit}')
    [[ -z "$ANDROID_SERIAL" ]] && ANDROID_SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')
    export ANDROID_SERIAL
fi
if [[ -n "${ANDROID_SERIAL:-}" ]] && adb get-state >/dev/null 2>&1; then
    PKG=net.stelnet.tools
    if (( FRESH )); then
        adb shell am force-stop $PKG
        adb shell cmd notification disallow_listener $PKG/$PKG.live.LiveListener
        adb shell cmd notification allow_listener $PKG/$PKG.live.LiveListener
        echo "restarted without a screen; waiting 20 s"
        sleep 20
    fi
    pid=$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')
    if [[ -n "$pid" ]]; then
        mem=$(adb shell dumpsys meminfo $PKG 2>/dev/null | tr -d '\r')
        echo "$mem" | awk -v pid="$pid" '
            /^ *Native Heap / && !n {nat = $9; n = 1}
            /^ *Dalvik Heap / && !d {dal = $9; d = 1}
            /TOTAL PSS:/ {pss = $3; swap = $NF}
            /Activities:/ {act = $NF}
            END {printf "app       pid %s  pss %.1f MB + %.1f MB swapped  native heap %.1f MB  java heap %.1f MB  activities %s\n", pid, pss / 1024, swap / 1024, nat / 1024, dal / 1024, act}'
        threads=$(adb shell "ls /proc/$pid/task | wc -l" 2>/dev/null | tr -d '\r')
        ui=$(adb shell dumpsys activity processes $PKG 2>/dev/null | grep -o 'hasShownUi=[a-z]*' | head -1)
        gpu=$(adb shell dumpsys gfxinfo $PKG 2>/dev/null | awk '/Total allocated by GraphicBufferAllocator/ {print $(NF-1), $NF}')
        render=$(adb shell "for t in /proc/$pid/task/*; do cat \$t/comm; done" 2>/dev/null | grep -c -E 'RenderThread|GrallocUpload')
        echo "          threads $threads  ${ui:-hasShownUi=false}  gpu buffers ${gpu:-none}  render threads $render"
    else
        echo "app       not running"
    fi
    appid=$(adb shell dumpsys package $PKG 2>/dev/null | grep -m1 -o 'appId=[0-9]*' | cut -d= -f2)
    if [[ -n "$appid" ]]; then
        uid="u0a$((appid - 10000))"
        bs=$(adb shell dumpsys batterystats --charged $PKG 2>/dev/null | tr -d '\r')
        echo "$bs" | awk -v uid="$uid" '
            $0 ~ "^ +UID " uid ":" {mah = $3}
            $0 ~ "^  " uid ":$" {on = 1; next}
            on && /^  [a-z0-9]+:$/ {on = 0}
            on && /Wi-Fi network:/ {sub(/^ +/, ""); wifi = $0}
            on && /TOTAL wake:/ {sub(/^ +/, ""); wake = $0}
            on && /Total cpu time:/ {sub(/^ +/, ""); cpu = $0}
            /Time on battery:/ && !t {sub(/^ +/, ""); split($0, a, "realtime"); batt = a[1]; t = 1}
            END {
                printf "battery   since charge (%s): %s mAh\n", batt, (mah == "" ? "?" : mah)
                if (cpu) print "          " cpu
                if (wifi) print "          " wifi
                if (wake) print "          " wake
            }'
    fi
    echo "== Link (phone side)"
    adb shell dumpsys activity service $PKG/.link.LinkService 2>/dev/null | tr -d '\r' | sed -n '/status:/,$p' | sed 's/^ */  /'
    locks=$(adb shell dumpsys power 2>/dev/null | grep -i "$PKG" | grep -i 'wake_lock\|WAKE_LOCK' || true)
    echo "wakelocks ${locks:-none}"
else
    echo "no adb device"
fi
