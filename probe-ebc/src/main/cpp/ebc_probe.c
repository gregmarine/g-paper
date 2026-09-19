// Thin syscall pass-through. No policy, no interpretation: Kotlin decides what to send,
// C only hands it to the kernel and reports ret/errno back. Every failure returns
// -errno so the caller can print strerror.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_open(JNIEnv *env, jclass c, jstring path, jint flags) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, flags);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd >= 0 ? fd : -err;
}

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_close(JNIEnv *env, jclass c, jint fd) {
    return close(fd) == 0 ? 0 : -errno;
}

// ioctl(fd, req, buf) with a direct ByteBuffer as the argument pointer (NULL if buf is null).
JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_ioctl(JNIEnv *env, jclass c, jint fd, jlong req, jobject buf) {
    void *p = buf ? (*env)->GetDirectBufferAddress(env, buf) : NULL;
    int r = ioctl(fd, (unsigned long) req, p);
    return r >= 0 ? r : -errno;
}

// ioctl(fd, req, intValue) — for requests that take a plain integer argument.
JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_ioctlInt(JNIEnv *env, jclass c, jint fd, jlong req, jint value) {
    int r = ioctl(fd, (unsigned long) req, (long) value);
    return r >= 0 ? r : -errno;
}

// mmap(NULL, len, PROT_READ|PROT_WRITE, MAP_SHARED, fd, off) wrapped as a direct ByteBuffer;
// null on failure (errno via lastErrno()).
static int g_errno = 0;

JNIEXPORT jobject JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_mmap(JNIEnv *env, jclass c, jint fd, jlong len, jlong off) {
    void *p = mmap(NULL, (size_t) len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t) off);
    if (p == MAP_FAILED) { g_errno = errno; return NULL; }
    return (*env)->NewDirectByteBuffer(env, p, len);
}

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_munmap(JNIEnv *env, jclass c, jobject buf, jlong len) {
    void *p = (*env)->GetDirectBufferAddress(env, buf);
    return munmap(p, (size_t) len) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_lastErrno(JNIEnv *env, jclass c) { return g_errno; }

JNIEXPORT jstring JNICALL
Java_com_symmetricalpalmtree_gpaper_probe_Native_strerror(JNIEnv *env, jclass c, jint err) {
    return (*env)->NewStringUTF(env, strerror(err));
}
