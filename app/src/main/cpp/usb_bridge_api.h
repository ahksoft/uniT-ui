#pragma once
#include <string>
#include <vector>

namespace usb_bridge {
bool get_devices_checked(std::vector<std::string>& result);
std::vector<std::string> get_devices();
int get_fd(const std::string& device_name);
std::string get_serial(const std::string& device_name);
}  // namespace usb_bridge
