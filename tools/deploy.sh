#!/usr/bin/env bash
# Build artifact -> install on watch. Usage: tools/deploy.sh [serial]
set -euo pipefail
SERIAL="${1:-10.71.46.80:45741}"
REPO="grabbitz59-cmyk/opw2-probe"
TMP="/tmp/opencode/deploy"
mkdir -p "$TMP"

api() { curl -s -H "Authorization: Bearer $GH_TOKEN" "$@"; }

RUN=$(api "https://api.github.com/repos/$REPO/actions/runs?branch=main&per_page=1")
RUN_ID=$(echo "$RUN" | python3 -c 'import json,sys;print(json.load(sys.stdin)["workflow_runs"][0]["id"])')
STATUS=$(echo "$RUN" | python3 -c 'import json,sys;r=json.load(sys.stdin)["workflow_runs"][0];print(r["status"],r["conclusion"])')
echo "run $RUN_ID: $STATUS"
case "$STATUS" in
  "completed success") ;;
  *) echo "build not ready: $STATUS"; exit 2;;
esac

ART=$(api "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts")
ART_ID=$(echo "$ART" | python3 -c 'import json,sys;print(json.load(sys.stdin)["artifacts"][0]["id"])')
echo "artifact $ART_ID"
curl -sL -H "Authorization: Bearer $GH_TOKEN" -o "$TMP/art.zip" \
  "https://api.github.com/repos/$REPO/actions/artifacts/$ART_ID/zip"
rm -rf "$TMP/x" && mkdir -p "$TMP/x"
unzip -o -q "$TMP/art.zip" -d "$TMP/x"
APK="$TMP/x/opw2-probe.apk"
ls -l "$APK"

adb connect "$SERIAL" >/dev/null 2>&1 || true
adb -s "$SERIAL" install -r -g "$APK" | tail -2
adb -s "$SERIAL" shell dumpsys package io.github.smartalarm | grep -m1 versionName
cp "$APK" "/sdcard/Download/AndCode/smart-alarm/opw2-probe.apk"
echo "installed to $SERIAL"
