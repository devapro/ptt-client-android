package com.github.devapro.pttdroid.history

/**
 * Raw byte storage for history files, so the store and the capture stay in commonMain and only
 * the implementation knows what a directory is. Bindings live in the platform DI modules —
 * `JvmHistoryFileStorage` for Android/desktop, an `NSData` one for iOS.
 */
interface HistoryFileStorage {
    /** Writes [bytes] under [name], replacing any existing file. False on failure. */
    suspend fun write(name: String, bytes: ByteArray): Boolean

    /** The file's bytes, or null when it is missing or unreadable. */
    suspend fun read(name: String): ByteArray?

    suspend fun delete(name: String)

    /** Every file name in the directory, for orphan pruning. Empty when it cannot be listed. */
    suspend fun list(): List<String>
}
