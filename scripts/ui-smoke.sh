#!/bin/bash
# Prova dell'app su un telefono virtuale: installa, riempie l'app con dati finti, fa suonare un file vero (silenzio),
# apre le schermate e fa tocchi casuali.
set -u
APK=${1:-app-debug.apk}
PKG=com.micmax.ascolta
OUT=ui-shots
mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g "$APK" || exit 1
adb logcat -c
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "schermata: $1"; }
alive() { adb shell pidof $PKG >/dev/null; }
why() { adb logcat -d -s AndroidRuntime:E | tail -60; adb logcat -d -s AndroidRuntime:E | tail -60 > "$OUT/crash.txt" || true; }
start() { adb shell am start -W -n $PKG/.MainActivity "$@" >/dev/null; }

start
shot 01-home 6
alive || { echo "::error::L'app si e' chiusa all'avvio"; why; exit 1; }

start --es action backup
for i in $(seq 1 15); do sleep 2; adb logcat -d -s ASCOLTA_SELFTEST:* | grep -q 'BACKUP' && break; done
BK=$(adb logcat -d -s ASCOLTA_SELFTEST:* | grep -o 'BACKUP.*' | tail -1)
echo "Dati: $BK"
case "$BK" in
  BACKUP\ bytes=*\ json=0\ podcast,\ 0\ radio,\ 1\ in\ coda\ opml=0\ podcast\ aggiunti*) ;;
  *) echo "::error::Esporta/importa non funziona ($BK)"; adb logcat -d -s AndroidRuntime:E | tail -30; exit 1;;
esac

# il lettore: un file vero (silenzio) passa dal servizio di riproduzione
start --es action play
for i in $(seq 1 30); do sleep 2; adb logcat -d -s ASCOLTA_SELFTEST:* | grep -q 'PLAY' && break; done
PL=$(adb logcat -d -s ASCOLTA_SELFTEST:* | grep -o 'PLAY.*' | tail -1)
echo "Lettore: $PL"
case "$PL" in
  PLAY\ connesso=true*voci=1*) ;;
  *) echo "::error::Il lettore non si collega al servizio ($PL)"; adb logcat -d -s AndroidRuntime:E | tail -30; exit 1;;
esac
shot 02-lettore 4
alive || { echo "::error::L'app si e' chiusa nel lettore"; why; exit 1; }
adb shell input keyevent KEYCODE_BACK

for a in home podcast radio episodes queue settings alarm music; do
  start --es action $a
  shot "03-$a" 6
  alive || { echo "::error::L'app si e' chiusa nella schermata $a"; why; exit 1; }
  adb shell input keyevent KEYCODE_BACK
  sleep 1
done

adb shell monkey -p $PKG --throttle 150 --ignore-timeouts --pct-syskeys 0 --pct-appswitch 0 -s 11 250 >"$OUT/monkey.txt" 2>&1
tail -3 "$OUT/monkey.txt"
alive || { echo "::error::L'app si e' chiusa durante i tocchi casuali"; why; exit 1; }
echo "Prova completata"
