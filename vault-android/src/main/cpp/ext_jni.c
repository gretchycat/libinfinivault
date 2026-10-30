#include "ext_backend.h"
#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>

struct java_io {
    JNIEnv *env;
    jobject device;
    jmethodID read, write, flush;
    jbyteArray buffer;
    jthrowable failure;
};
static int capture(struct java_io *io) {
    if ((*io->env)->ExceptionCheck(io->env)) {
        if (!io->failure) io->failure = (*io->env)->ExceptionOccurred(io->env);
        (*io->env)->ExceptionClear(io->env);
        return EIO;
    }
    return io->failure ? EIO : 0;
}
static int java_read(void *context, uint64_t offset, void *data, size_t length) {
    struct java_io *io = context;
    if (io->failure) return EIO;
    while (length) {
        jint count = (jint)(length > 65536 ? 65536 : length);
        jint n = (*io->env)->CallIntMethod(io->env, io->device, io->read, (jlong)offset, io->buffer, 0, count);
        if (capture(io) || n <= 0 || n > count) return EIO;
        (*io->env)->GetByteArrayRegion(io->env, io->buffer, 0, n, data);
        if (capture(io)) return EIO;
        offset += n; data = (char *)data + n; length -= n;
    }
    return 0;
}
static int java_write(void *context, uint64_t offset, const void *data, size_t length) {
    struct java_io *io = context;
    if (io->failure) return EIO;
    while (length) {
        jint n = (jint)(length > 65536 ? 65536 : length);
        (*io->env)->SetByteArrayRegion(io->env, io->buffer, 0, n, data);
        if (capture(io)) return EIO;
        (*io->env)->CallVoidMethod(io->env, io->device, io->write, (jlong)offset, io->buffer, 0, n);
        if (capture(io)) return EIO;
        offset += n; data = (const char *)data + n; length -= n;
    }
    return 0;
}
static int java_flush(void *context) {
    struct java_io *io = context;
    if (io->failure) return EIO;
    (*io->env)->CallVoidMethod(io->env, io->device, io->flush);
    return capture(io);
}
/* Byte arrays preserve standard UTF-8, including supplementary-plane names;
 * GetStringUTFChars uses modified UTF-8 and is wrong for filesystem names. */
static char *utf8(JNIEnv *env, jbyteArray bytes) {
    if (!bytes) return NULL;
    jsize n = (*env)->GetArrayLength(env, bytes);
    if (n > 65536) return NULL;
    char *s = malloc((size_t)n + 1);
    if (!s) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte *)s);
    if ((*env)->ExceptionCheck(env) || memchr(s, 0, n)) { free(s); return NULL; }
    s[n] = 0; return s;
}

JNIEXPORT jlongArray JNICALL Java_com_infinifolder_archive_ExtNative_execute(
    JNIEnv *env, jobject self, jobject device, jlong size, jint action,
    jbyteArray path_bytes, jbyteArray target_bytes, jbyteArray content_bytes) {
    (void)self;
    struct java_io io = { .env = env, .device = device };
    jclass cls = (*env)->GetObjectClass(env, device);
    io.read = (*env)->GetMethodID(env, cls, "readAt", "(J[BII)I");
    io.write = (*env)->GetMethodID(env, cls, "writeAt", "(J[BII)V");
    io.flush = (*env)->GetMethodID(env, cls, "flush", "()V");
    if ((*env)->ExceptionCheck(env)) return NULL;
    io.buffer = (*env)->NewByteArray(env, 65536);
    if (!io.buffer) return NULL;
    char *path = utf8(env, path_bytes), *target = utf8(env, target_bytes), *content = utf8(env, content_bytes);
    uint64_t result[6];
    char error[512] = "Invalid UTF-8 path, embedded NUL or out of memory";
    int r = EINVAL;
    if (path && (!target_bytes || target) && (!content_bytes || content)) {
        struct ifext_io bridge = { .context = &io, .size = (uint64_t)size,
            .read = java_read, .write = action == IFEXT_PROBE || action == IFEXT_EXPORT_METADATA ? NULL : java_write, .flush = java_flush };
        r = ifext_run(&bridge, action, path, target, content, result, error, sizeof(error));
    }
    free(path); free(target); free(content);
    if (io.failure) { (*env)->Throw(env, io.failure); return NULL; }
    if ((*env)->ExceptionCheck(env)) return NULL;
    if (r) {
        jclass exception = (*env)->FindClass(env, "java/io/IOException");
        if (exception) (*env)->ThrowNew(env, exception, error);
        return NULL;
    }
    jlongArray values = (*env)->NewLongArray(env, 6);
    if (values) (*env)->SetLongArrayRegion(env, values, 0, 6, (const jlong *)result);
    return values;
}
