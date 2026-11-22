package com.voicereply.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Downloads the AI model once (free, no account) and keeps it on the phone. */
class ModelStore(private val context: Context) {

    companion object {
        // Open model, downloadable without any login. See README for details.
        const val MODEL_URL =
            "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"
        const val MODEL_NAME = "Gemma 3 1B"
        const val MODEL_SIZE_TEXT = "about 800 MB"
        private const val FILE_NAME = "model.gguf"
        private const val PART_NAME = "model.gguf.part"
        private const val KEY_DOWNLOAD_ID = "download_id"
    }

    sealed class Progress {
        data class Running(val done: Long, val total: Long) : Progress()
        object Waiting : Progress()
        object Done : Progress()
        data class Failed(val reason: String) : Progress()
    }

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("model", Context.MODE_PRIVATE)
    private val dir: File get() = context.getExternalFilesDir(null) ?: context.filesDir

    val modelFile: File get() = File(dir, FILE_NAME)

    fun isReady(): Boolean = modelFile.exists() && looksLikeModel(modelFile)

    fun isDownloading(): Boolean = prefs.getLong(KEY_DOWNLOAD_ID, -1L) != -1L

    fun startDownload() {
        cancel()
        File(dir, PART_NAME).delete()
        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("VoiceReply AI model")
            .setDescription("$MODEL_NAME, $MODEL_SIZE_TEXT")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(File(dir, PART_NAME)))
        prefs.edit().putLong(KEY_DOWNLOAD_ID, dm.enqueue(request)).apply()
    }

    fun cancel() {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id != -1L) dm.remove(id)
        prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
    }

    /** Check the download; when it is finished, move the file into place. */
    fun poll(): Progress {
        val id = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (id == -1L) return if (isReady()) Progress.Done else Progress.Failed("Download not started.")

        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
                return Progress.Failed("The download was cancelled.")
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> finish()
                DownloadManager.STATUS_FAILED -> {
                    cancel()
                    Progress.Failed(
                        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "Not enough free storage (needs $MODEL_SIZE_TEXT)."
                        else "The download failed (code $reason). Check your internet and try again."
                    )
                }
                DownloadManager.STATUS_PAUSED -> Progress.Waiting
                else -> Progress.Running(done, total)
            }
        }
    }

    private fun finish(): Progress {
        prefs.edit().remove(KEY_DOWNLOAD_ID).apply()
        val part = File(dir, PART_NAME)
        if (!looksLikeModel(part)) {
            part.delete()
            return Progress.Failed("The downloaded file is damaged. Please download again.")
        }
        modelFile.delete()
        if (!part.renameTo(modelFile)) {
            return Progress.Failed("Could not save the AI model.")
        }
        return Progress.Done
    }

    /** Every GGUF model file starts with the bytes "GGUF". */
    private fun looksLikeModel(file: File): Boolean = try {
        file.length() > 100_000_000 &&
            file.inputStream().use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 && String(head, Charsets.US_ASCII) == "GGUF"
            }
    } catch (e: Exception) {
        false
    }
}
