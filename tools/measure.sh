#!/usr/bin/env bash
# measure.sh — checks the Stelnet Tools performance budgets (helper RSS, CPU, wakeups; app PSS and wakelocks).
#
#   tools/measure.sh [seconds]     sample for N seconds (default 10)
#
# PC: the ii_link helper's RSS, threads, CPU and wakeups (context switches) over the
# window, plus the shell's RSS for comparison. Phone (over adb, if attached): the
# app's PSS, whether its process is alive and any wakelock it holds.
set -uo pipefail
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
if adb get-state >/dev/null 2>&1; then
    pid=$(adb shell pidof net.stelnet.tools 2>/dev/null | tr -d '\r')
    if [[ -n "$pid" ]]; then
        pss=$(adb shell dumpsys meminfo net.stelnet.tools 2>/dev/null | awk '/TOTAL PSS:/ {printf "%.1f", $3 / 1024; exit}')
        echo "app       pid $pid  pss ${pss:-?} MB"
    else
        echo "app       not running"
    fi
    locks=$(adb shell dumpsys power 2>/dev/null | grep -i 'net.stelnet.tools' | grep -i 'wake_lock\|WAKE_LOCK' || true)
    echo "wakelocks ${locks:-none}"
else
    echo "no adb device"
fi
