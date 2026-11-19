#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "MBJavaScript.h"

#define JNI_METHOD(name) Java_com_xtreeroot_mb_plugin_QuickJsContext_##name

typedef struct {
    JavaVM *vm;
    jobject host;
    jmethodID call;
    MBJSRuntime *runtime;
} Bridge;

static Bridge *bridge_at(jlong handle) {
    return (Bridge *)(intptr_t)handle;
}

static char *from_java(JNIEnv *env, jbyteArray value) {
    if (!value) return NULL;
    jsize size = (*env)->GetArrayLength(env, value);
    char *text = malloc((size_t)size + 1);
    if (!text) return NULL;
    (*env)->GetByteArrayRegion(env, value, 0, size, (jbyte *)text);
    if ((*env)->ExceptionCheck(env) || memchr(text, '\0', size)) {
        free(text);
        return NULL;
    }
    text[size] = '\0';
    return text;
}

static jbyteArray to_java(JNIEnv *env, const char *text) {
    if (!text) return NULL;
    jsize size = (jsize)strlen(text);
    jbyteArray value = (*env)->NewByteArray(env, size);
    if (value) (*env)->SetByteArrayRegion(env, value, 0, size, (const jbyte *)text);
    return value;
}

static char *call_host(void *opaque, const char *operation, const char *arguments) {
    Bridge *bridge = opaque;
    JNIEnv *env = NULL;
    if ((*bridge->vm)->GetEnv(bridge->vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK ||
        (*env)->PushLocalFrame(env, 4) < 0) return NULL;
    jbyteArray name = to_java(env, operation);
    jbyteArray args = name ? to_java(env, arguments) : NULL;
    char *response = NULL;
    if (args && !(*env)->ExceptionCheck(env)) {
        jbyteArray value = (*env)->CallObjectMethod(env, bridge->host, bridge->call, name, args);
        if (!(*env)->ExceptionCheck(env)) response = from_java(env, value);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->PopLocalFrame(env, NULL);
    return response;
}

JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreate)(JNIEnv *env, jobject owner, jobject host) {
    Bridge *bridge = calloc(1, sizeof(*bridge));
    if (!bridge) return 0;
    jclass type = (*env)->GetObjectClass(env, host);
    bridge->call = type ? (*env)->GetMethodID(env, type, "call", "([B[B)[B") : NULL;
    if (bridge->call && (*env)->GetJavaVM(env, &bridge->vm) == JNI_OK) {
        bridge->host = (*env)->NewGlobalRef(env, host);
        if (bridge->host) bridge->runtime = mbjs_create(call_host, bridge, 64 * 1024 * 1024);
    }
    if (bridge->runtime) return (jlong)(intptr_t)bridge;
    (*env)->DeleteGlobalRef(env, bridge->host);
    free(bridge);
    return 0;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeDestroy)(JNIEnv *env, jobject owner, jlong handle) {
    Bridge *bridge = bridge_at(handle);
    mbjs_destroy(bridge->runtime);
    (*env)->DeleteGlobalRef(env, bridge->host);
    free(bridge);
}

JNIEXPORT void JNICALL JNI_METHOD(nativeCancel)(JNIEnv *env, jobject owner, jlong handle) {
    mbjs_cancel(bridge_at(handle)->runtime);
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeEvaluate)(JNIEnv *env, jobject owner, jlong handle,
                                                 jbyteArray bytes, jbyteArray filename,
                                                 jboolean module, jint budget) {
    char *source = from_java(env, bytes);
    char *name = source ? from_java(env, filename) : NULL;
    int status = name ? mbjs_evaluate(bridge_at(handle)->runtime, source, name, module, budget) : -2;
    free(source);
    free(name);
    return status;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeStart)(JNIEnv *env, jobject owner, jlong handle,
                                              jbyteArray export_name, jbyteArray arguments, jint budget) {
    char *name = from_java(env, export_name);
    char *json = name ? from_java(env, arguments) : NULL;
    int status = json ? mbjs_start(bridge_at(handle)->runtime, name, json, budget) : -2;
    free(name);
    free(json);
    return status;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeSettle)(JNIEnv *env, jobject owner, jlong handle, jint request,
                                               jboolean success, jbyteArray value, jint budget) {
    char *json = from_java(env, value);
    int status = json ? mbjs_settle(bridge_at(handle)->runtime, request, success, json, budget) : -2;
    free(json);
    return status;
}

JNIEXPORT jboolean JNICALL JNI_METHOD(nativeHasExport)(JNIEnv *env, jobject owner, jlong handle, jbyteArray export_name) {
    char *name = from_java(env, export_name);
    jboolean found = name && mbjs_has_export(bridge_at(handle)->runtime, name);
    free(name);
    return found;
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(nativeRead)(JNIEnv *env, jobject owner, jlong handle, jboolean event) {
    MBJSRuntime *runtime = bridge_at(handle)->runtime;
    char *text = event ? mbjs_take_event(runtime) : mbjs_result(runtime);
    jbyteArray value = to_java(env, text);
    mbjs_free_string(text);
    return value;
}
