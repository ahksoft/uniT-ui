#!/usr/bin/env bash
set -u
print_row() { printf "%-20s : %s\n" "$1" "\${2:-N/A}"; }
get_prop() {
    local value
    value="$(adb shell getprop "$1" 2>/dev/null | tr -d '\r')"
    printf '%s' "\${value:-N/A}"
}
echo "========== ADB DEVICE INFORMATION =========="
adb wait-for-device || { echo "ADB device did not become available."; exit 1; }
adb devices -l
echo
echo "[IDENTITY]"
print_row "Brand" "$(get_prop ro.product.brand)"
print_row "Model" "$(get_prop ro.product.model)"
print_row "Product" "$(get_prop ro.product.name)"
print_row "Market name" "$(get_prop ro.product.marketname)"
print_row "Boot mode" "$(get_prop ro.bootmode)"
print_row "Hardware" "$(get_prop ro.hardware)"
print_row "SoC manufacturer" "$(get_prop ro.soc.manufacturer)"
print_row "SoC model" "$(get_prop ro.soc.model)"
echo
echo "[ANDROID / KERNEL]"
print_row "Android version" "$(get_prop ro.build.version.release)"
print_row "SDK version" "$(get_prop ro.build.version.sdk)"
print_row "Kernel version" "$(adb shell uname -r 2>/dev/null | tr -d '\r')"
print_row "CPU architecture" "$(get_prop ro.product.cpu.abi)"
print_row "CPU platform" "$(get_prop ro.board.platform)"
print_row "Board" "$(get_prop ro.product.board)"
print_row "Security patch" "$(get_prop ro.build.version.security_patch)"
print_row "Build ID" "$(get_prop ro.build.display.id)"
print_row "Region" "$(get_prop ro.csc.country_code)"
print_row "Time zone" "$(get_prop persist.sys.timezone)"
print_row "SELinux" "$(adb shell getenforce 2>/dev/null | tr -d '\r')"
echo
echo "[BOOT / SECURITY STATE]"
flash_locked="$(get_prop ro.boot.flash.locked)"
verified_state="$(get_prop ro.boot.verifiedbootstate)"
bootloader="Locked"
if [[ "$flash_locked" == "0" || "$verified_state" == "orange" ]]; then bootloader="Unlocked"; fi
print_row "Bootloader" "$bootloader"
print_row "Crypto state" "$(get_prop ro.crypto.state)"
anti_crack="$(get_prop ro.boot.anticrack)"
[[ "$anti_crack" == "N/A" ]] && anti_crack="$(get_prop ro.build.anti_crack)"
case "$anti_crack" in
    1|true) anti_crack="Enabled" ;;
    0|false) anti_crack="Disabled" ;;
    *) anti_crack="Not reported" ;;
esac
print_row "Anti-crack property" "$anti_crack"
print_row "FRP property" "$(get_prop ro.frp.pst)"
print_row "Active slot" "$(get_prop ro.boot.slot_suffix | tr -d '_')"
echo
echo "[ROOT STATUS]"
root_version="$(adb shell su -v 2>/dev/null | tr -d '\r' | head -n 1)"
su_path="$(adb shell which su 2>/dev/null | tr -d '\r' | head -n 1)"
if [[ -n "$root_version" || -n "$su_path" ]]; then
    print_row "Root status" "Root interface detected"
    print_row "Root version" "\${root_version:-Detected at $su_path}"
else
    print_row "Root status" "Not detected / access denied"
    print_row "Root version" "N/A"
fi
echo
echo "[UPTIME]"
uptime_sec="$(adb shell cat /proc/uptime 2>/dev/null | awk '{print int($1)}' | tr -d '\r')"
if [[ "$uptime_sec" =~ ^[0-9]+$ ]]; then
    hours=$((uptime_sec / 3600))
    minutes=$(((uptime_sec % 3600) / 60))
    seconds=$((uptime_sec % 60))
    print_row "Device uptime" "$(printf '%02d:%02d:%02d' "$hours" "$minutes" "$seconds")"
else
    print_row "Device uptime" "N/A"
fi
echo
echo "[BATTERY]"
battery="$(adb shell dumpsys battery 2>/dev/null | tr -d '\r')"
level="$(printf '%s\n' "$battery" | awk '/^[[:space:]]*level:/ {print $2; exit}')"
temp="$(printf '%s\n' "$battery" | awk '/^[[:space:]]*temperature:/ {print $2; exit}')"
voltage="$(printf '%s\n' "$battery" | awk '/^[[:space:]]*voltage:/ {print $2; exit}')"
health_code="$(printf '%s\n' "$battery" | awk '/^[[:space:]]*health:/ {print $2; exit}')"
case "$health_code" in
    2) health="Good" ;;
    3) health="Overheat" ;;
    4) health="Dead" ;;
    5) health="Over voltage" ;;
    6) health="Unspecified failure" ;;
    7) health="Cold" ;;
    *) health="Unknown (\${health_code:-N/A})" ;;
esac
if [[ "$temp" =~ ^[0-9]+$ ]]; then temp_c="$(awk "BEGIN {printf \"%.1f\", $temp/10}")"; else temp_c="N/A"; fi
print_row "Battery level" "\${level:-N/A}%"
print_row "Battery health" "$health"
print_row "Battery temperature" "\${temp_c}°C"
print_row "Battery voltage" "\${voltage:-N/A} mV"
echo
echo "Note: IMEI, serial number and network MAC addresses are intentionally not collected."
