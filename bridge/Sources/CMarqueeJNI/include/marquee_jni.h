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

#endif
