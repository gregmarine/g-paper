package com.symmetricalpalmtree.gpaper.probe

import java.nio.ByteBuffer

/** Raw syscalls. Negative returns are `-errno`. */
object Native {
    init { System.loadLibrary("ebcprobe") }

    const val O_RDONLY = 0
    const val O_RDWR = 2

    @JvmStatic external fun open(path: String, flags: Int): Int
    @JvmStatic external fun close(fd: Int): Int
    @JvmStatic external fun ioctl(fd: Int, req: Long, buf: ByteBuffer?): Int
    @JvmStatic external fun ioctlInt(fd: Int, req: Long, value: Int): Int
    @JvmStatic external fun mmap(fd: Int, len: Long, off: Long): ByteBuffer?
    @JvmStatic external fun munmap(buf: ByteBuffer, len: Long): Int
    @JvmStatic external fun lastErrno(): Int
    @JvmStatic external fun strerror(err: Int): String
}
