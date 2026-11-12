/*
 * UniTool Android USB broker backend for libusb's Linux usbfs backend.
 * Only UsbManager-authorized UsbDeviceConnection descriptors are exposed.
 */
#include "config.h"
#include "libusbi.h"
#include "linux_usbfs.h"
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define UNITOOL_MAX_DEVICES 256
#define UNITOOL_LIST_BUFFER_SIZE 16384
#define UNITOOL_DEVICE_PATH_SIZE 64
#define UNITOOL_HOTPLUG_INTERVAL_MS 500

extern int unitool_usb_list_devices(char *buffer, size_t capacity);
extern int unitool_usb_get_device_fd(const char *device_name);

struct unitool_device_snapshot {
	size_t count;
	char names[UNITOOL_MAX_DEVICES][UNITOOL_DEVICE_PATH_SIZE];
};

static pthread_t monitor_thread;
static atomic_int monitor_stop;
static int monitor_started;

static int read_snapshot(struct unitool_device_snapshot *snapshot)
{
	char buffer[UNITOOL_LIST_BUFFER_SIZE];
	const int length = unitool_usb_list_devices(buffer, sizeof(buffer));
	if (length < 0 || (size_t)length >= sizeof(buffer))
		return -1;

	buffer[length] = '\0';
	snapshot->count = 0;
	char *cursor = buffer;
	char *end = buffer + length;
	while (cursor < end) {
		char *newline = memchr(cursor, '\n', (size_t)(end - cursor));
		size_t name_length;
		if (!newline)
			return -1;
		name_length = (size_t)(newline - cursor);
		if (name_length == 0 || name_length >= UNITOOL_DEVICE_PATH_SIZE ||
		    snapshot->count >= UNITOOL_MAX_DEVICES)
			return -1;
		memcpy(snapshot->names[snapshot->count], cursor, name_length);
		snapshot->names[snapshot->count][name_length] = '\0';
		snapshot->count++;
		cursor = newline + 1;
	}
	return 0;
}

static int snapshot_contains(const struct unitool_device_snapshot *snapshot,
			     const char *name)
{
	size_t i;
	for (i = 0; i < snapshot->count; ++i)
		if (strcmp(snapshot->names[i], name) == 0)
			return 1;
	return 0;
}

static int parse_device_path(const char *name, uint8_t *busnum, uint8_t *devaddr)
{
	unsigned int bus, address;
	int consumed = 0;
	if (sscanf(name, "/dev/bus/usb/%u/%u%n", &bus, &address, &consumed) != 2 ||
	    name[consumed] != '\0' || bus > UINT8_MAX || address > UINT8_MAX)
		return 0;
	*busnum = (uint8_t)bus;
	*devaddr = (uint8_t)address;
	return 1;
}

int unitool_android_helper_get_usbfs_fd(const struct libusb_device *dev, int silent)
{
	char name[UNITOOL_DEVICE_PATH_SIZE];
	int fd;
	if (!dev)
		return LIBUSB_ERROR_INVALID_PARAM;
	snprintf(name, sizeof(name), "/dev/bus/usb/%03u/%03u",
		 dev->bus_number, dev->device_address);
	fd = unitool_usb_get_device_fd(name);
	if (fd < 0) {
		if (!silent)
			usbi_dbg(DEVICE_CTX(dev), "UniTool broker could not provide %s", name);
		return LIBUSB_ERROR_NO_DEVICE;
	}

	/* Android UsbDeviceConnection FD position can be shared across dup/SCM_RIGHTS. */
	if (lseek(fd, 0, SEEK_SET) < 0) {
		const int saved_errno = errno;
		close(fd);
		errno = saved_errno;
		if (!silent)
			usbi_err(DEVICE_CTX(dev), "UniTool broker FD rewind failed for %s, errno=%d",
				 name, errno);
		return LIBUSB_ERROR_IO;
	}
	return fd;
}

int unitool_android_helper_scan_devices(struct libusb_context *ctx)
{
	struct unitool_device_snapshot snapshot;
	size_t i;
	if (read_snapshot(&snapshot) < 0) {
		usbi_dbg(ctx, "UniTool USB broker unavailable during initial scan");
		return LIBUSB_SUCCESS;
	}
	for (i = 0; i < snapshot.count; ++i) {
		uint8_t busnum, devaddr;
		if (!parse_device_path(snapshot.names[i], &busnum, &devaddr)) {
			usbi_warn(ctx, "Ignoring invalid broker path: %s", snapshot.names[i]);
			continue;
		}
		(void)linux_enumerate_device(ctx, busnum, devaddr, NULL);
	}
	return LIBUSB_SUCCESS;
}

static void *monitor_main(void *unused)
{
	struct unitool_device_snapshot previous = { 0 };
	(void)unused;
	(void)read_snapshot(&previous);
	while (!atomic_load(&monitor_stop)) {
		struct unitool_device_snapshot current;
		if (read_snapshot(&current) == 0) {
			size_t i;
			for (i = 0; i < current.count; ++i) {
				if (!snapshot_contains(&previous, current.names[i])) {
					uint8_t busnum, devaddr;
					if (parse_device_path(current.names[i], &busnum, &devaddr))
						linux_hotplug_enumerate(busnum, devaddr, NULL);
				}
			}
			for (i = 0; i < previous.count; ++i) {
				if (!snapshot_contains(&current, previous.names[i])) {
					uint8_t busnum, devaddr;
					if (parse_device_path(previous.names[i], &busnum, &devaddr))
						linux_device_disconnected(busnum, devaddr);
				}
			}
			previous = current;
		}
		(void)poll(NULL, 0, UNITOOL_HOTPLUG_INTERVAL_MS);
	}
	return NULL;
}

int unitool_android_helper_start_event_monitor(void)
{
	int result;
	if (monitor_started)
		return LIBUSB_SUCCESS;
	atomic_store(&monitor_stop, 0);
	result = pthread_create(&monitor_thread, NULL, monitor_main, NULL);
	if (result != 0) {
		usbi_err(NULL, "Could not start UniTool USB broker monitor (%d)", result);
		return LIBUSB_ERROR_OTHER;
	}
	monitor_started = 1;
	return LIBUSB_SUCCESS;
}

int unitool_android_helper_stop_event_monitor(void)
{
	if (!monitor_started)
		return LIBUSB_SUCCESS;
	atomic_store(&monitor_stop, 1);
	if (pthread_join(monitor_thread, NULL) != 0)
		usbi_warn(NULL, "Could not join UniTool USB broker monitor");
	monitor_started = 0;
	return LIBUSB_SUCCESS;
}
