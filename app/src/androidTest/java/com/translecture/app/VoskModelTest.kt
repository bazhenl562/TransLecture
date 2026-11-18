package com.translecture.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VoskModelTest {
    @Test
    fun bundledRussianModelUnpacksAndCreatesRecognizer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelReference = AtomicReference<Model?>()
        val errorReference = AtomicReference<IOException?>()
        val completed = CountDownLatch(1)

        StorageService.unpack(
            context,
            "model-ru",
            "model-ru",
            { model ->
                modelReference.set(model)
                completed.countDown()
            },
            { error ->
                errorReference.set(error)
                completed.countDown()
            }
        )

        check(completed.await(5, TimeUnit.MINUTES)) {
            "Timed out while unpacking the bundled Russian speech model."
        }
        errorReference.get()?.let { throw it }

        val model = checkNotNull(modelReference.get()) {
            "Vosk did not return the unpacked Russian model."
        }
        try {
            val recognizer = Recognizer(model, 16_000.0f)
            try {
                assertNotNull(recognizer.getPartialResult())
            } finally {
                recognizer.close()
            }
        } finally {
            model.close()
        }
    }
}
