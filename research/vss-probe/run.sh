#!/bin/sh
# Runs the installed SDK 27 probe with a command list and prints its log.
# Usage: research/vss-probe/run.sh "info,init,info"
adb logcat -c
adb shell am broadcast -n id.poncol.vssprobe27/id.poncol.vssprobe.Main --es cmds "$1" >/dev/null
adb shell sleep "${WAIT:-3}"
adb logcat -d -s 'VssProbe:*' | grep -v '^-' | cut -c32-
