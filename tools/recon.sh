#!/usr/bin/env bash
# Read-only recon of an OPW2 over adb. Nothing is modified on the watch.
# Usage: ./tools/recon.sh [serial]
set -u
SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

run() {
  echo
  echo "### $*"
  "${ADB[@]}" shell "$@" 2>&1 | head -60
}

echo "== device =="
"${ADB[@]}" devices -l

run getprop ro.product.model
run getprop ro.build.version.release
run getprop ro.build.version.sdk
run getprop ro.build.type
run getprop ro.debuggable
run getprop ro.secure
run getprop ro.boot.verifiedbootstate

echo
echo "### root check"
"${ADB[@]}" root 2>&1 | head -5
"${ADB[@]}" shell id 2>&1

echo
echo "### BES / MCU / RTOS mentions in properties"
"${ADB[@]}" shell 'getprop | grep -iE "bes|rtos|mcu|health|oplus|oneplus|coproc|dual"' 2>&1 | head -80

echo
echo "### vendor / system binaries worth a look"
"${ADB[@]}" shell 'ls -l /vendor/bin 2>/dev/null | head -80' 2>&1
"${ADB[@]}" shell 'ls -l /system_ext/bin /odm/bin 2>/dev/null | head -60' 2>&1

echo
echo "### init services referencing bes/mcu/health"
"${ADB[@]}" shell 'grep -rilE "bes|mcu|rtos|health" /vendor/etc/init /odm/etc/init /system/etc/init 2>/dev/null | head -40' 2>&1

echo
echo "### running processes"
"${ADB[@]}" shell 'ps -A -o USER,PID,NAME 2>/dev/null | grep -iE "bes|mcu|rtos|health|oplus|oneplus" | head -40' 2>&1

echo
echo "### device nodes"
"${ADB[@]}" shell 'ls -l /dev 2>/dev/null | grep -iE "bes|mcu|spi|hifi|sensor|health" | head -40' 2>&1

echo
echo "### vendor libraries"
"${ADB[@]}" shell 'ls /vendor/lib64 /odm/lib64 2>/dev/null | grep -iE "bes|mcu|rtos|health|oplus|sensor" | head -60' 2>&1

echo
echo "### packages (vendor health / oem)"
"${ADB[@]}" shell 'pm list packages -f 2>/dev/null | grep -iE "health|oplus|oneplus|wear|fit|sleep" | head -40' 2>&1

echo
echo "### binder services"
"${ADB[@]}" shell 'service list 2>/dev/null | grep -iE "bes|mcu|rtos|health|sensor|oplus" | head -40' 2>&1

echo
echo "### cmd / dumpsys namespaces"
"${ADB[@]}" shell 'cmd -l 2>/dev/null | head -60' 2>&1

echo
echo "### engineering / diag props"
"${ADB[@]}" shell 'getprop | grep -iE "engineer|diag|factory|atcmd|debug"' 2>&1 | head -40

echo
echo "### alarm capability check (system alarm app)"
"${ADB[@]}" shell 'pm list packages | grep -iE "deskclock|clock|alarm"' 2>&1
"${ADB[@]}" shell 'dumpsys alarm 2>/dev/null | head -20' 2>&1

echo
echo "done"
