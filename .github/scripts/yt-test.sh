#!/bin/bash
# Plays YouTube videos in the real app on the Android TV emulator, with Shields off (baseline) and on.
set -x
PKG=com.example.webtv
mkdir -p results
adb install -r apk/WebTV-debug.apk
VIDEOS="dQw4w9WgXcQ kJQP7kiw5Fk JGwWNGJdvx8"
for MODE in off on; do
  for V in $VIDEOS; do
    adb shell am force-stop $PKG
    adb shell pm clear $PKG
    URL="https://www.youtube.com/watch?v=$V"
    printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<string name=\"url\">%s</string>\n<boolean name=\"hinted\" value=\"true\" />\n</map>\n" "$URL" > /tmp/webtv.xml
    if [ $MODE = off ]; then ON=false; else ON=true; fi
    printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n<boolean name=\"on\" value=\"%s\" />\n<long name=\"updated\" value=\"%s\" />\n</map>\n" "$ON" "$(date +%s)000" > /tmp/shields.xml
    adb push /tmp/webtv.xml /tmp/shields.xml /data/local/tmp/
    adb shell chmod 644 /data/local/tmp/webtv.xml /data/local/tmp/shields.xml
    adb shell "run-as $PKG mkdir -p shared_prefs"
    adb shell "run-as $PKG cp /data/local/tmp/webtv.xml shared_prefs/webtv.xml"
    adb shell "run-as $PKG cp /data/local/tmp/shields.xml shared_prefs/shields.xml"
    adb logcat -c
    adb shell am start -n $PKG/.MainActivity
    sleep 12
    PID=$(adb shell pidof $PKG | tr -d '\r')
    adb forward --remove-all
    adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
    node .github/scripts/yt-probe.js results/yt-$MODE-$V.json 75 &
    PROBE=$!
    sleep 20; adb exec-out screencap -p > results/yt-$MODE-$V-a.png
    sleep 25; adb exec-out screencap -p > results/yt-$MODE-$V-b.png
    wait $PROBE
    adb logcat -d | grep -E "WebTV|WebTvShields" > results/yt-$MODE-$V-log.txt
  done
done
true
