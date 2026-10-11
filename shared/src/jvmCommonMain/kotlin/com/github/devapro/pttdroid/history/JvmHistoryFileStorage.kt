package com.github.devapro.pttdroid.history

import java.io.File

/**
 * [HistoryFileStorage] over plain `java.io` in one app-private directory, shared by the Android
 * and desktop targets (`jvmCommonMain`). Writes are whole-file because the capture hands over
 * one finished WAV at a time — there is no streaming write to get wrong.
 *
 * The directory is bound per platform (`di/SharedDiAndroid.kt`, `di/SharedDiDesktop.kt`):
 * Android's is app-private `filesDir/history`, desktop's is the same config home the settings
 * store already lives in.
 */
class JvmHistoryFileStorage(private val directory: File) : HistoryFileStorage {

    override suspend fun write(name: String, bytes: ByteArray): Boolean = runCatching {
        directory.mkdirs()
        File(directory, name).writeBytes(bytes)
    }.isSuccess

    override suspend fun read(name: String): ByteArray? = runCatching {
        val file = File(directory, name)
        if (file.isFile) file.readBytes() else null
    }.getOrNull()

    override suspend fun delete(name: String) {
        runCatching { File(directory, name).delete() }
    }

    override suspend fun list(): List<String> =
        directory.list()?.toList().orEmpty()
}
