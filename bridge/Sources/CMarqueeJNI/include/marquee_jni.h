#ifndef MARQUEE_JNI_H
#define MARQUEE_JNI_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

// Upcalls into NativeCore (Kotlin). Callable from any thread: a thread that is not yet known
// to the JVM is attached on first use and detached when it exits.

/// Hands a call's JSON reply to NativeCore.onResult.
void marquee_deliver_result(int64_t id, const uint8_t *bytes, size_t length);

/// Starts an HTTP request in Kotlin. `request` is the JSON description, `body` is sent only
/// when `hasBody` is set.
void marquee_transport_send(int64_t id, const uint8_t *request, size_t requestLength,
                            const uint8_t *body, size_t bodyLength, bool hasBody);

/// Cancels a request started with `marquee_transport_send`.
void marquee_transport_cancel(int64_t id);

// Thin POSIX socket adapter for LumaRemoteCore on platforms without Network.framework.
int marquee_remote_connect(const char *host, uint16_t port, bool datagram);
int marquee_link_listener(bool loopback, uint16_t *port);
int marquee_remote_accept(int fd);
#if !defined(__ANDROID__)
int marquee_remote_listener(uint16_t *port);
#endif
ptrdiff_t marquee_remote_read(int fd, uint8_t *bytes, size_t length);
bool marquee_remote_send(int fd, const uint8_t *bytes, size_t length, bool datagram);
void marquee_remote_stop_reading(int fd);
void marquee_remote_shutdown(int fd);
void marquee_remote_close(int fd);

#endif
