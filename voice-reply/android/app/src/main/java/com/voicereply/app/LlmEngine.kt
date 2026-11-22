package com.voicereply.app

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * The offline AI (llama.cpp). Lives for the whole app process, so the model
 * stays loaded when the screen rotates. All native calls run on one thread.
 */
object LlmEngine {

    // Instructions for the AI. Same file is used by the CI test (desktop_test.cpp).
    private var systemPrompt = ""

    private const val CONTEXT_TOKENS = 2048
    private const val MAX_REPLY_TOKENS = 160

    private val aiThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voicereply-ai")
    }.asCoroutineDispatcher()

    private var nativeReady = false
    private var handle = 0L

    /** What was said so far, for the screen. The engine keeps its own copy. */
    val conversation = mutableListOf<Pair<Boolean, String>>() // (isUser, text)

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load(context: Context, model: File) = withContext(aiThread) {
        if (handle != 0L) return@withContext
        systemPrompt = context.assets.open("system_prompt.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (!nativeReady) {
            System.loadLibrary("voicereply")
            nativeInit(context.applicationInfo.nativeLibraryDir)
            nativeReady = true
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = (cores - 2).coerceIn(1, 4)
        val h = nativeLoad(model.absolutePath, CONTEXT_TOKENS, threads)
        check(h != 0L) {
            "The AI could not start. Close other apps to free memory, then try again."
        }
        nativeReset(h, systemPrompt.toByteArray(Charsets.UTF_8))
        conversation.clear()
        handle = h
    }

    suspend fun reply(userText: String): String = withContext(aiThread) {
        check(handle != 0L) { "The AI is not loaded yet." }
        val bytes = nativeReply(handle, userText.toByteArray(Charsets.UTF_8), MAX_REPLY_TOKENS)
        String(bytes, Charsets.UTF_8)
    }

    suspend fun newConversation() = withContext(aiThread) {
        if (handle != 0L) nativeReset(handle, systemPrompt.toByteArray(Charsets.UTF_8))
        conversation.clear()
    }

    private external fun nativeInit(backendDir: String)
    private external fun nativeLoad(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeReset(handle: Long, systemPrompt: ByteArray)
    private external fun nativeReply(handle: Long, userText: ByteArray, maxTokens: Int): ByteArray
    @Suppress("unused")
    private external fun nativeFree(handle: Long)
}
