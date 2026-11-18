package com.translecture.app

import android.media.MediaExtractor
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OfflineAudioPipelineTest {
    @Test
    fun recordsMicrophoneAudioIntoPlayableM4a() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO"
        ).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }

        val modelReference = AtomicReference<Model?>()
        val modelError = AtomicReference<IOException?>()
        val modelReady = CountDownLatch(1)
        StorageService.unpack(
            context,
            "model-ru",
            "pipeline-test-model",
            { model ->
                modelReference.set(model)
                modelReady.countDown()
            },
            { error ->
                modelError.set(error)
                modelReady.countDown()
            }
        )
        assertTrue("Timed out while loading the speech model.", modelReady.await(5, TimeUnit.MINUTES))
        modelError.get()?.let { throw it }

        val outputFile = File(context.cacheDir, "pipeline-test-${System.nanoTime()}.m4a")
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val pipelineError = AtomicReference<Exception?>()
        val pipeline = OfflineAudioPipeline(
            context = context,
            outputPath = outputFile.absolutePath,
            model = checkNotNull(modelReference.get()),
            listener = object : OfflineAudioPipeline.Listener {
                override fun onStarted() {
                    started.countDown()
                }

                override fun onPartialResult(text: String) = Unit

                override fun onFinalResult(text: String) = Unit

                override fun onStopped() {
                    stopped.countDown()
                }

                override fun onError(error: Exception) {
                    pipelineError.set(error)
                    stopped.countDown()
                }
            }
        )

        var pipelineLaunched = false
        try {
            pipeline.start()
            pipelineLaunched = true
            assertTrue("Timed out while starting microphone capture.", started.await(30, TimeUnit.SECONDS))
            Thread.sleep(2_000)
            pipeline.stop()
            assertTrue("Timed out while finalizing the audio file.", stopped.await(30, TimeUnit.SECONDS))
            pipelineError.get()?.let { throw it }

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(outputFile.absolutePath)
                assertTrue(
                    "The M4A file has no audio track.",
                    (0 until extractor.trackCount).any { index ->
                        extractor.getTrackFormat(index).getString("mime") == "audio/mp4a-latm"
                    }
                )
            } finally {
                extractor.release()
            }
        } finally {
            if (pipelineLaunched) {
                pipeline.stop()
                stopped.await(30, TimeUnit.SECONDS)
            } else {
                modelReference.get()?.close()
            }
            outputFile.delete()
        }
    }
}
