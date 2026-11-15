#!/usr/bin/env bash
#set -u

print_row() {
    printf "%-17s : %s\n" "$1" "$2"
}

adb wait-for-device
adb devices | grep -v "List of devices attached" | grep -v "^$"

get_prop() {
    val=$(adb shell getprop "$1" | tr -d '\r')
    echo "${val:-N/A}"
}

print_row "Brand" "$(get_prop ro.product.brand)"
print_row "Model" "$(get_prop ro.product.model)"
print_row "Product" "$(get_prop ro.product.name)"
print_row "Marketname" "$(get_prop ro.product.marketname)"
print_row "Boot mode" "$(get_prop ro.bootmode)"
print_row "Hardware" "$(get_prop ro.hardware)"
print_row "Soc manufacturer" "$(get_prop ro.soc.manufacturer)"
print_row "Soc model" "$(get_prop ro.soc.model)"
print_row "Serialno" "$(adb get-serialno | tr -d '\r')"

# Network MACs
BTMAC=$(adb shell settings get secure bluetooth_address | tr -d '\r')
[ "$BTMAC" == "null" ] || [ -z "$BTMAC" ] && BTMAC=$(adb shell "su -c 'cat /sys/class/bluetooth/hci0/address'" 2>/dev/null | tr -d '\r')
print_row "BTMAC" "${BTMAC:-Restricted/N/A}"

WIFIMAC=$(adb shell "ip addr show wlan0 2>/dev/null | grep 'link/ether' | awk '{print \$2}'" | tr -d '\r')
print_row "WIFIMAC" "${WIFIMAC:-Restricted/N/A}"

# OS, Kernel & CPU Info
print_row "Android Version" "$(get_prop ro.build.version.release)"
print_row "SDK Version" "$(get_prop ro.build.version.sdk)"
print_row "Kernel version" "$(adb shell uname -r | tr -d '\r')"
print_row "CPU Architecture" "$(get_prop ro.product.cpu.abi)"
print_row "CPU Type" "$(get_prop ro.board.platform)"
print_row "Board name" "$(get_prop ro.product.board)"
print_row "Security patch" "$(get_prop ro.build.version.security_patch)"
print_row "Software version" "$(get_prop ro.build.display.id)"

# Region & Time Zone
REGION=$(get_prop ro.csc.country_code)
[ "$REGION" == "N/A" ] && REGION=$(get_prop ro.boot.hwc)
[ "$REGION" == "N/A" ] && REGION=$(get_prop ro.product.locale.region)
[ "$REGION" == "N/A" ] && REGION=$(get_prop persist.sys.country)
print_row "Region" "$REGION"
print_row "Time Zone" "$(get_prop persist.sys.timezone)"
print_row "Selinux State" "$(adb shell getenforce | tr -d '\r')"

# Root Status & Manager Detection
ROOT_VER=$(adb shell "su -v" 2>/dev/null | tr -d '\r')
SU_PATH=$(adb shell "which su" 2>/dev/null | tr -d '\r')

if [ -n "$ROOT_VER" ] || [ -n "$SU_PATH" ]; then
    print_row "Root Status" "Rooted (Allowed)"
    print_row "Root version" "${ROOT_VER:-Detected ($SU_PATH)}"
    if [[ "$ROOT_VER" == *"MAGISK"* ]]; then
        MGR="Magisk"
    elif [[ "$ROOT_VER" == *"KernelSU"* ]] || [[ "$ROOT_VER" == *"KSU"* ]]; then
        MGR="KernelSU"
    elif [[ "$ROOT_VER" == *"APatch"* ]]; then
        MGR="APatch"
    else
        PKG=$(adb shell "pm list packages" | grep -E "magisk|kernelsu|apatch|supersu" | head -n 1 | cut -d':' -f2 | tr -d '\r')
        MGR="${PKG:-Unknown / Hidden Manager}"
    fi
    print_row "Root Manager" "$MGR"
else
    print_row "Root Status" "Unrooted / Denied"
    print_row "Root version" "N/A"
    print_row "Root Manager" "None"
fi

# IMEI Extraction
IMEI1=$(adb shell "service call iphonesubinfo 1 s16 com.android.shell | grep -oE \"'[0-9.]+'\" | tr -d \"'.\" | tr -d '\n'")
IMEI2=$(adb shell "service call iphonesubinfo 3 i32 1 s16 com.android.shell | grep -oE \"'[0-9.]+'\" | tr -d \"'.\" | tr -d '\n'")
print_row "IMEIA" "${IMEI1:-Restricted/N/A}"
print_row "IMEIB" "${IMEI2:-Restricted/N/A}"

# Security, FRP, Bootloader & Anti-Crack
FRP_PST=$(get_prop ro.frp.pst)
FLASH_LOCKED=$(get_prop ro.boot.flash.locked)
BL_STATE="locked"
if [ "$FLASH_LOCKED" == "0" ] || [ "$(get_prop ro.boot.verifiedbootstate)" == "orange" ]; then
    BL_STATE="unlocked"
fi

ANTI_CRACK=$(get_prop ro.boot.anticrack)
[ "$ANTI_CRACK" == "N/A" ] && ANTI_CRACK=$(get_prop ro.build.anti_crack)
if [ "$ANTI_CRACK" == "1" ] || [ "$ANTI_CRACK" == "true" ]; then
    AC_STATUS="Enabled"
elif [ "$ANTI_CRACK" == "0" ] || [ "$ANTI_CRACK" == "false" ]; then
    AC_STATUS="Disabled"
else
    AC_STATUS="Disabled / Not Present"
fi

FRP_STR=$([ "$FLASH_LOCKED" == "0" ] && echo "Not Triggered" || echo "Check Device")

print_row "FRP Lock" "$FRP_STR"
print_row "FRP PST" "$FRP_PST"
print_row "Bootloader state" "$BL_STATE"
print_row "Crypto State" "$(get_prop ro.crypto.state)"
print_row "Anti Crack" "$AC_STATUS"
print_row "Activated slot" "$(get_prop ro.boot.slot_suffix | tr -d '_')"

# Device Uptime
UPTIME_SEC=$(adb shell "cat /proc/uptime | awk '{print int(\$1)}'" | tr -d '\r')
UPTIME_MIN=$(awk "BEGIN {printf \"%.2f\", ${UPTIME_SEC:-0}/60}")
HRS=$((UPTIME_SEC / 3600))
MINS=$(((UPTIME_SEC % 3600) / 60))
SECS=$((UPTIME_SEC % 60))
UPTIME_FMT=$(printf "%02d:%02d:%02d (%s mins)" $HRS $MINS $SECS "$UPTIME_MIN")
print_row "Device Uptime" "$UPTIME_FMT"

# Enhanced Battery Diagnostics
BAT_DUMP=$(adb shell dumpsys battery)
BAT_LVL=$(echo "$BAT_DUMP" | grep -E "^[[:space:]]*level:" | head -n 1 | awk '{print $2}' | tr -d '\r')
BAT_TEMP=$(echo "$BAT_DUMP" | grep -E "^[[:space:]]*temperature:" | head -n 1 | awk '{print $2}' | tr -d '\r')
BAT_TEMP_C=$(awk "BEGIN {printf \"%.1f\", ${BAT_TEMP:-0}/10}")
BAT_VOLT=$(echo "$BAT_DUMP" | grep -E "^[[:space:]]*voltage:" | head -n 1 | awk '{print $2}' | tr -d '\r')
BAT_HEALTH_CODE=$(echo "$BAT_DUMP" | grep -E "^[[:space:]]*health:" | head -n 1 | awk '{print $2}' | tr -d '\r')
CHARGE_COUNTER=$(echo "$BAT_DUMP" | grep -iE "^[[:space:]]*Charge counter:" | head -n 1 | awk '{print $3}' | tr -d '\r')

case $BAT_HEALTH_CODE in
    2) BAT_HEALTH="Good" ;;
    3) BAT_HEALTH="Overheat" ;;
    4) BAT_HEALTH="Dead" ;;
    5) BAT_HEALTH="Over Voltage" ;;
    6) BAT_HEALTH="Unspecified Failure" ;;
    7) BAT_HEALTH="Cold" ;;
    *) BAT_HEALTH="Unknown ($BAT_HEALTH_CODE)" ;;
esac

read_bat_node() {
    val=$(adb shell "cat /sys/class/power_supply/battery/$1 2>/dev/null || su -c 'cat /sys/class/power_supply/battery/$1' 2>/dev/null" | tr -d '\r')
    echo "$val"
}

BAT_CHARGE_FULL=$(read_bat_node "charge_full")
BAT_DESIGN_FULL=$(read_bat_node "charge_full_design")
CYCLE_COUNT=$(read_bat_node "cycle_count")

CAP_OUTPUT="N/A"
HEALTH_PCT_STR=""

if [[ "$BAT_CHARGE_FULL" =~ ^[0-9]+$ ]] && [[ "$BAT_DESIGN_FULL" =~ ^[0-9]+$ ]] && [ "$BAT_DESIGN_FULL" -gt 0 ]; then
    [ "$BAT_CHARGE_FULL" -gt 100000 ] && CAP_MAH=$((BAT_CHARGE_FULL / 1000)) || CAP_MAH=$BAT_CHARGE_FULL
    [ "$BAT_DESIGN_FULL" -gt 100000 ] && DES_MAH=$((BAT_DESIGN_FULL / 1000)) || DES_MAH=$BAT_DESIGN_FULL
    WEAR_PCT=$((CAP_MAH * 100 / DES_MAH))
    CAP_OUTPUT="${CAP_MAH} mAh / ${DES_MAH} mAh"
    HEALTH_PCT_STR=" (${WEAR_PCT}%)"
elif [[ "$CHARGE_COUNTER" =~ ^[0-9]+$ ]] && [ "$CHARGE_COUNTER" -gt 0 ] && [[ "$BAT_LVL" =~ ^[0-9]+$ ]] && [ "$BAT_LVL" -gt 0 ]; then
    [ "$CHARGE_COUNTER" -gt 100000 ] && CURR_MAH=$((CHARGE_COUNTER / 1000)) || CURR_MAH=$CHARGE_COUNTER
    EST_FULL_MAH=$((CURR_MAH * 100 / BAT_LVL))
    CAP_OUTPUT="~${EST_FULL_MAH} mAh (Current: ${CURR_MAH} mAh)"
    if [[ "$BAT_DESIGN_FULL" =~ ^[0-9]+$ ]] && [ "$BAT_DESIGN_FULL" -gt 0 ]; then
        [ "$BAT_DESIGN_FULL" -gt 100000 ] && DES_MAH=$((BAT_DESIGN_FULL / 1000)) || DES_MAH=$BAT_DESIGN_FULL
        WEAR_PCT=$((EST_FULL_MAH * 100 / DES_MAH))
        CAP_OUTPUT="~${EST_FULL_MAH} mAh / ${DES_MAH} mAh"
        HEALTH_PCT_STR=" (${WEAR_PCT}%)"
    fi
fi

print_row "Battery Level" "${BAT_LVL:-N/A}%"
print_row "Battery Health" "${BAT_HEALTH}${HEALTH_PCT_STR}"
print_row "Battery Capacity" "${CAP_OUTPUT}"
[ -n "$CYCLE_COUNT" ] && print_row "Battery Cycles" "$CYCLE_COUNT"
print_row "Battery Temp" "${BAT_TEMP_C}°C"
print_row "Battery Voltage" "${BAT_VOLT:-N/A} mV"
