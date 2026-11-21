#include <jni.h>
#include <pthread.h>
#include <stdlib.h>

#include "marquee_jni.h"

// Implemented in Swift (Exports.swift). Each takes ownership of the malloc'd buffers it is given.
void marquee_swift_call(int64_t id, const char *method, uint8_t *payload, size_t length);
void marquee_swift_cancel(int64_t id);
void marquee_swift_transport_response(int64_t id, int32_t status, uint8_t *headers, size_t headersLength,
                                      uint8_t *body, size_t bodyLength);
void marquee_swift_transport_failure(int64_t id, int32_t failure);

static JavaVM *vm;
static jclass native_core;
static jmethodID on_result;
static jmethodID transport_send;
static jmethodID transport_cancel;
static pthread_key_t attached_thread;

static void detach_thread(void *env) {
    (void)env;
    (*vm)->DetachCurrentThread(vm);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *loaded, void *reserved) {
    (void)reserved;
    vm = loaded;
    // ART aborts when an attached thread exits without detaching; the key's destructor detaches
    // Swift's executor threads as they exit.
    if (pthread_key_create(&attached_thread, detach_thread) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}

static JNIEnv *thread_env(void) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "MarqueeCore", NULL};
    if ((*vm)->AttachCurrentThread(vm, &env, &args) != JNI_OK) return NULL;
    pthread_setspecific(attached_thread, env);
    return env;
}

/// Copies a Java byte array into a malloc'd buffer the caller owns. Null for a null array.
static uint8_t *copy_bytes(JNIEnv *env, jbyteArray array, size_t *length) {
    *length = 0;
    if (array == NULL) return NULL;
    jsize count = (*env)->GetArrayLength(env, array);
    uint8_t *bytes = malloc(count > 0 ? (size_t)count : 1);
    if (bytes == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, count, (jbyte *)bytes);
    *length = (size_t)count;
    return bytes;
}

static jbyteArray new_bytes(JNIEnv *env, const uint8_t *bytes, size_t length) {
    jbyteArray array = (*env)->NewByteArray(env, (jsize)length);
    if (array != NULL && length > 0) (*env)->SetByteArrayRegion(env, array, 0, (jsize)length, (const jbyte *)bytes);
    return array;
}

/// A Kotlin callback that threw must not leave the exception pending on a native thread.
static void report_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

// MARK: Kotlin to Swift

JNIEXPORT void JNICALL Java_com_nphil_marquee_core_NativeCore_nativeInit(JNIEnv *env, jclass type) {
    // Looked up here, on a Java thread: FindClass from an attached native thread only sees the
    // boot class loader, not the app's classes.
    native_core = (*env)->NewGlobalRef(env, type);
    on_result = (*env)->GetStaticMethodID(env, type, "onResult", "(J[B)V");
    if (on_result == NULL) return;
    transport_send = (*env)->GetStaticMethodID(env, type, "transportSend", "(J[B[B)V");
    if (transport_send == NULL) return;
    transport_cancel = (*env)->GetStaticMethodID(env, type, "transportCancel", "(J)V");
}

JNIEXPORT void JNICALL Java_com_nphil_marquee_core_NativeCore_nativeCall(JNIEnv *env, jclass type, jlong id,
                                                                       jstring method, jbyteArray payload) {
    (void)type;
    const char *name = (*env)->GetStringUTFChars(env, method, NULL);
    if (name == NULL) return;
    size_t length;
    uint8_t *bytes = copy_bytes(env, payload, &length);
    marquee_swift_call(id, name, bytes, length);
    (*env)->ReleaseStringUTFChars(env, method, name);
}

JNIEXPORT void JNICALL Java_com_nphil_marquee_core_NativeCore_nativeCancel(JNIEnv *env, jclass type, jlong id) {
    (void)env;
    (void)type;
    marquee_swift_cancel(id);
}

JNIEXPORT void JNICALL Java_com_nphil_marquee_core_NativeCore_transportResponse(JNIEnv *env, jclass type, jlong id,
                                                                              jint status, jbyteArray headers,
                                                                              jbyteArray body) {
    (void)type;
    size_t headers_length, body_length;
    uint8_t *header_bytes = copy_bytes(env, headers, &headers_length);
    uint8_t *body_bytes = copy_bytes(env, body, &body_length);
    marquee_swift_transport_response(id, status, header_bytes, headers_length, body_bytes, body_length);
}

JNIEXPORT void JNICALL Java_com_nphil_marquee_core_NativeCore_transportFailure(JNIEnv *env, jclass type, jlong id,
                                                                             jint failure) {
    (void)env;
    (void)type;
    marquee_swift_transport_failure(id, failure);
}

// MARK: Swift to Kotlin

void marquee_deliver_result(int64_t id, const uint8_t *bytes, size_t length) {
    JNIEnv *env = thread_env();
    if (env == NULL) return;
    jbyteArray array = new_bytes(env, bytes, length);
    if (array == NULL) {
        report_exception(env);
        return;
    }
    (*env)->CallStaticVoidMethod(env, native_core, on_result, (jlong)id, array);
    report_exception(env);
    (*env)->DeleteLocalRef(env, array);
}

void marquee_transport_send(int64_t id, const uint8_t *request, size_t requestLength,
                            const uint8_t *body, size_t bodyLength, bool hasBody) {
    JNIEnv *env = thread_env();
    if (env == NULL) return;
    jbyteArray request_array = new_bytes(env, request, requestLength);
    jbyteArray body_array = hasBody ? new_bytes(env, body, bodyLength) : NULL;
    if (request_array == NULL || (hasBody && body_array == NULL)) {
        report_exception(env);
        marquee_swift_transport_failure(id, 0);
    } else {
        (*env)->CallStaticVoidMethod(env, native_core, transport_send, (jlong)id, request_array, body_array);
        report_exception(env);
    }
    if (request_array != NULL) (*env)->DeleteLocalRef(env, request_array);
    if (body_array != NULL) (*env)->DeleteLocalRef(env, body_array);
}

void marquee_transport_cancel(int64_t id) {
    JNIEnv *env = thread_env();
    if (env == NULL) return;
    (*env)->CallStaticVoidMethod(env, native_core, transport_cancel, (jlong)id);
    report_exception(env);
}
