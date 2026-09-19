// The Supernote panel driver, reached directly (Phase 28).
//
// Thin syscall pass-through, and deliberately nothing more: no policy, no interpretation,
// no knowledge of what /dev/ebc is. Kotlin decides what to send and what the bytes mean
// (EbcPanel, EbcDisplayArg, EbcGeometry — all testable); this file only hands the call to
// the kernel and reports ret/errno back. Every failure returns -errno so the caller can
// print strerror. It is the same shape as the probe app the measurements were taken with
// (probe-ebc/src/main/cpp/ebc_probe.c) — deliberately, so nothing about the proven call
// sequence had to be re-derived — but the two share no code: the probe depends on nothing
// in g-paper and this depends on nothing in the probe.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_ratta_EbcNative_open(JNIEnv *env, jclass c, jstring path, jint flags) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, flags);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd >= 0 ? fd : -err;
}

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_ratta_EbcNative_close(JNIEnv *env, jclass c, jint fd) {
    return close(fd) == 0 ? 0 : -errno;
}

// ioctl(fd, req, buf) with a direct ByteBuffer as the argument pointer (NULL if buf is null).
JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_ratta_EbcNative_ioctl(JNIEnv *env, jclass c, jint fd, jlong req, jobject buf) {
    void *p = buf ? (*env)->GetDirectBufferAddress(env, buf) : NULL;
    int r = ioctl(fd, (unsigned long) req, p);
    return r >= 0 ? r : -errno;
}

// mmap(NULL, len, PROT_READ|PROT_WRITE, MAP_SHARED, fd, off) wrapped as a direct ByteBuffer;
// null on failure (errno via lastErrno()).
static int g_errno = 0;

JNIEXPORT jobject JNICALL
Java_com_symmetricalpalmtree_gpaper_ratta_EbcNative_mmap(JNIEnv *env, jclass c, jint fd, jlong len, jlong off) {
    void *p = mmap(NULL, (size_t) len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t) off);
    if (p == MAP_FAILED) { g_errno = errno; return NULL; }
    return (*env)->NewDirectByteBuffer(env, p, len);
}

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_ratta_EbcNative_munmap(JNIEnv *env, jclass c, jobject buf, jlong len) {
    void *p = (*env)->GetDirectBufferAddress(env, buf);
    return munmap(p, (size_t) len) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_symmetricalpalmtree_gpaper_ratta_EbcNative_lastErrno(JNIEnv *env, jclass c) { return g_errno; }

JNIEXPORT jstring JNICALL
Java_com_symmetricalpalmtree_gpaper_ratta_EbcNative_strerror(JNIEnv *env, jclass c, jint err) {
    return (*env)->NewStringUTF(env, strerror(err));
}
