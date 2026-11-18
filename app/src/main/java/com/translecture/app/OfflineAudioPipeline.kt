package com.translecture.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException

internal class OfflineAudioPipeline(
    private val context: Context,
    private val outputPath: String,
    private val model: Model,
    private val listener: Listener
) {
    interface Listener {
        fun onStarted()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onStopped()
        fun onError(error: Exception)
    }

    private val stateLock = Object()
    @Volatile
    private var stopRequested = false
    @Volatile
    private var paused = false
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    fun start() {
        check(worker == null) { "Audio pipeline has already been started." }
        worker = Thread(::runPipeline, "lecture-audio-pipeline").apply { start() }
    }

    fun pause() {
        synchronized(stateLock) {
            if (stopRequested || paused) return
            paused = true
            audioRecord?.stop()
            stateLock.notifyAll()
        }
    }

    fun resume() {
        synchronized(stateLock) {
            if (stopRequested || !paused) return
            audioRecord?.startRecording()
            paused = false
            stateLock.notifyAll()
        }
    }

    fun stop() {
        stopRequested = true
        synchronized(stateLock) {
            stateLock.notifyAll()
            audioRecord?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
        }
    }

    private fun runPipeline() {
        var recorder: AudioRecord? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var recognizer: Recognizer? = null
        var muxerStarted = false
        var successful = false
        var trackIndex = -1
        var failure: Exception? = null

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val outputFile = File(outputPath)
            outputFile.parentFile?.let { directory ->
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Could not create the audio output directory.")
                }
            }
            if (outputFile.exists() && !outputFile.delete()) {
                throw IOException("Could not replace the previous audio file.")
            }

            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            encoder = createEncoder()
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            recorder = createAudioRecord()
            audioRecord = recorder
            recorder.startRecording()
            listener.onStarted()

            val samples = ShortArray(READ_BUFFER_SAMPLES)
            var sampleCount = 0L
            while (!stopRequested) {
                awaitResume()
                if (stopRequested) break

                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    if (paused || stopRequested) continue
                    throw IOException("AudioRecord failed with error $count.")
                }
                if (count == 0) continue

                val hasFinalResult = recognizer.acceptWaveForm(samples, count)
                val json = if (hasFinalResult) {
                    recognizer.result
                } else {
                    recognizer.partialResult
                }
                val text = JSONObject(json).optString(if (hasFinalResult) "text" else "partial").trim()
                if (hasFinalResult) {
                    if (text.isNotEmpty()) listener.onFinalResult(text)
                } else {
                    listener.onPartialResult(text)
                }

                val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_MICROS)
                if (inputIndex < 0) {
                    throw IOException("AAC encoder did not provide an input buffer.")
                }
                val inputBuffer = checkNotNull(encoder.getInputBuffer(inputIndex))
                inputBuffer.clear()
                if (inputBuffer.remaining() < count * BYTES_PER_SAMPLE) {
                    throw IOException("AAC encoder input buffer is too small.")
                }
                for (index in 0 until count) {
                    inputBuffer.putShort(samples[index])
                }
                val presentationTimeUs = sampleCount * MICROSECONDS_PER_SECOND / SAMPLE_RATE
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    count * BYTES_PER_SAMPLE,
                    presentationTimeUs,
                    0
                )
                sampleCount += count
                trackIndex = drainEncoder(encoder, muxer, trackIndex) { started ->
                    muxerStarted = muxerStarted || started
                }
            }

            if (!stopRequested) {
                throw IOException("Audio capture ended unexpectedly.")
            }
            finishEncoder(encoder, sampleCount)
            trackIndex = drainEncoder(encoder, muxer, trackIndex, endOfStream = true) { started ->
                muxerStarted = muxerStarted || started
            }
            if (!muxerStarted || trackIndex < 0) {
                throw IOException("The audio recording contains no encoded audio.")
            }

            val finalText = JSONObject(recognizer.finalResult).optString("text").trim()
            if (finalText.isNotEmpty()) listener.onFinalResult(finalText)
            successful = true
        } catch (error: Exception) {
            Log.e(TAG, "Offline audio capture failed.", error)
            failure = error
        } finally {
            audioRecord = null
            recorder?.let { current ->
                try {
                    if (current.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        current.stop()
                    }
                } catch (error: RuntimeException) {
                    failure = failure ?: error
                    Log.e(TAG, "Could not stop microphone capture.", error)
                }
                try {
                    current.release()
                } catch (error: RuntimeException) {
                    failure = failure ?: error
                    Log.e(TAG, "Could not release microphone capture.", error)
                }
            }
            try {
                recognizer?.close()
            } catch (error: RuntimeException) {
                failure = failure ?: error
                Log.e(TAG, "Could not release the Vosk recognizer.", error)
            }
            encoder?.let { current ->
                try {
                    current.stop()
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "AAC encoder was already stopped.", error)
                }
                try {
                    current.release()
                } catch (error: RuntimeException) {
                    failure = failure ?: error
                    Log.e(TAG, "Could not release the AAC encoder.", error)
                }
            }
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (error: IllegalStateException) {
                    Log.e(TAG, "Could not finalize the M4A container.", error)
                    failure = failure ?: error
                }
            }
            try {
                muxer?.release()
            } catch (error: RuntimeException) {
                failure = failure ?: error
                Log.e(TAG, "Could not release the M4A muxer.", error)
            }
            try {
                model.close()
            } catch (error: RuntimeException) {
                failure = failure ?: error
                Log.e(TAG, "Could not release the speech model.", error)
            }

            if (successful && failure == null) {
                listener.onStopped()
            } else {
                File(outputPath).delete()
                listener.onError(
                    failure ?: IOException("The audio recording could not be finalized.")
                )
            }
        }
    }

    private fun awaitResume() {
        synchronized(stateLock) {
            while (paused && !stopRequested) {
                stateLock.wait()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission has not been granted.")
        }
        val minimumBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBufferSize <= 0) {
            throw IOException("The device does not support 16 kHz microphone capture.")
        }
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBufferSize, READ_BUFFER_SAMPLES * BYTES_PER_SAMPLE * 2)
        ).also { recorder ->
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IOException("Could not initialize the microphone.")
            }
        }
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_BUFFER_SAMPLES * BYTES_PER_SAMPLE)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun finishEncoder(encoder: MediaCodec, sampleCount: Long) {
        val deadline = System.nanoTime() + ENCODER_FINISH_TIMEOUT_NANOS
        while (true) {
            val inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_MICROS)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    sampleCount * MICROSECONDS_PER_SECOND / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                return
            }
            if (System.nanoTime() >= deadline) {
                throw IOException("Timed out while finishing the audio encoder.")
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        currentTrackIndex: Int,
        endOfStream: Boolean = false,
        onMuxerStarted: (Boolean) -> Unit
    ): Int {
        var trackIndex = currentTrackIndex
        val deadline = System.nanoTime() + ENCODER_FINISH_TIMEOUT_NANOS
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(
                bufferInfo,
                if (endOfStream) CODEC_TIMEOUT_MICROS else 0
            )
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return trackIndex
                    if (System.nanoTime() >= deadline) {
                        throw IOException("Timed out while draining the audio encoder.")
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex >= 0) {
                        throw IOException("AAC encoder changed its output format more than once.")
                    }
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    onMuxerStarted(true)
                }

                outputIndex >= 0 -> {
                    val outputBuffer = checkNotNull(encoder.getOutputBuffer(outputIndex))
                    if (bufferInfo.size > 0) {
                        if (trackIndex < 0) {
                            throw IOException("AAC data arrived before the output format.")
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    val reachedEndOfStream =
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (reachedEndOfStream) return trackIndex
                }
            }
        }
    }

    private companion object {
        const val TAG = "OfflineAudioPipeline"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val AUDIO_BIT_RATE = 64_000
        const val BYTES_PER_SAMPLE = 2
        const val READ_BUFFER_SAMPLES = 2_048
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val CODEC_TIMEOUT_MICROS = 10_000L
        const val ENCODER_FINISH_TIMEOUT_NANOS = 10_000_000_000L
    }
}
