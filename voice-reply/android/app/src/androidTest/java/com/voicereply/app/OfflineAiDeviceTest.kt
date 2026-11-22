package com.voicereply.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on an Android emulator in CI: does exactly what the app does on first
 * start (download the model with DownloadManager, load it through JNI) and
 * then holds a short German conversation.
 */
@RunWith(AndroidJUnit4::class)
class OfflineAiDeviceTest {

    @Test
    fun downloadLoadAndAnswerInGerman() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ModelStore(context)

        if (!models.isReady()) {
            models.startDownload()
            val deadline = System.currentTimeMillis() + 20 * 60 * 1000
            while (true) {
                when (val p = models.poll()) {
                    ModelStore.Progress.Done -> break
                    is ModelStore.Progress.Failed -> fail("download failed: ${p.reason}")
                    is ModelStore.Progress.Running -> Log.i(TAG, "download ${p.done / 1_000_000}/${p.total / 1_000_000} MB")
                    ModelStore.Progress.Waiting -> Log.i(TAG, "download waiting")
                }
                if (System.currentTimeMillis() > deadline) fail("download timed out")
                Thread.sleep(2000)
            }
        }
        assertTrue("model file missing after download", models.isReady())

        LlmEngine.load(context, models.modelFile)

        val turns = listOf("Hallo! Ich heiße Max. Wie geht es dir?", "Wie heiße ich?")
        for (text in turns) {
            val start = System.currentTimeMillis()
            val reply = LlmEngine.reply(text)
            Log.i(TAG, "USER: $text | REPLY: $reply | ${System.currentTimeMillis() - start} ms")
            println("USER: $text | REPLY: $reply")
            assertTrue("empty reply to: $text", reply.isNotBlank())
        }
        LlmEngine.newConversation()
        val after = LlmEngine.reply("Guten Morgen!")
        Log.i(TAG, "after reset: $after")
        assertTrue("empty reply after reset", after.isNotBlank())
    }

    private companion object {
        const val TAG = "OfflineAiDeviceTest"
    }
}
