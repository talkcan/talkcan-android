package io.talkcan.model

/**
 * Snapshot of one model file's download progress, rendered by the setup
 * screen as a progress bar. [totalBytes] is `-1` when the server does not
 * report a content length.
 */
data class DownloadProgress(
    val currentFile: String = "",
    val bytesRead: Long = 0,
    val totalBytes: Long = 0,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
)



const val TARGET_DEVICE_NAME = "B02PTT-FF01"
val SPP_UUID: java.util.UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
