package com.translecture.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteException
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException
import java.util.Locale

enum class RecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    STOPPED,
    ERROR
}

data class RecordingStatus(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val lectureId: Long? = null,
    val filePath: String? = null,
    val elapsedMillis: Long = 0,
    val errorMessage: String? = null,
    val recognizedText: String = "",
    val rawRecognizedText: String = "",
    val partialText: String = ""
)

class RecordingService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val mutableRecordingStatus = MutableStateFlow(RecordingStatus())
    val recordingStatus: StateFlow<RecordingStatus> = mutableRecordingStatus.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var audioPipeline: OfflineAudioPipeline? = null
    private var stopRequestedDuringModelLoad = false
    private var transcriptPrefix = ""
    private var elapsedBeforeResume = 0L
    private var recordingSegmentStartedAt = 0L

    private val updateElapsedTime = object : Runnable {
        override fun run() {
            val status = mutableRecordingStatus.value
            if (status.phase == RecordingPhase.RECORDING) {
                val elapsed = elapsedBeforeResume +
                    SystemClock.elapsedRealtime() - recordingSegmentStartedAt
                mutableRecordingStatus.value = status.copy(elapsedMillis = elapsed)
                handler.postDelayed(this, TIMER_UPDATE_INTERVAL_MILLIS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            else -> {
                Log.e(TAG, "Received an unsupported recording service action.")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    fun pauseRecording() {
        val pipeline = audioPipeline ?: return
        val elapsed = currentElapsedTime()
        try {
            pipeline.pause()
            handler.removeCallbacks(updateElapsedTime)
            elapsedBeforeResume = elapsed
            mutableRecordingStatus.value = mutableRecordingStatus.value.copy(
                phase = RecordingPhase.PAUSED,
                elapsedMillis = elapsedBeforeResume
            )
        } catch (error: RuntimeException) {
            failRecording("Не удалось приостановить запись.", error)
        }
    }

    fun resumeRecording() {
        val pipeline = audioPipeline ?: return
        try {
            pipeline.resume()
            recordingSegmentStartedAt = SystemClock.elapsedRealtime()
            mutableRecordingStatus.value = mutableRecordingStatus.value.copy(
                phase = RecordingPhase.RECORDING
            )
            handler.post(updateElapsedTime)
        } catch (error: RuntimeException) {
            failRecording("Не удалось продолжить запись.", error)
        }
    }

    fun stopRecording() {
        val status = mutableRecordingStatus.value
        if (status.phase == RecordingPhase.STARTING && audioPipeline == null) {
            stopRequestedDuringModelLoad = true
            status.filePath?.let(::deletePartialAudioFile)
            mutableRecordingStatus.value = status.copy(phase = RecordingPhase.STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val pipeline = audioPipeline
        if (pipeline == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        handler.removeCallbacks(updateElapsedTime)
        pipeline.stop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateElapsedTime)
        audioPipeline?.stop()
        super.onDestroy()
    }

    private fun startRecording(intent: Intent) {
        val lectureId = intent.getLongExtra(EXTRA_LECTURE_ID, INVALID_LECTURE_ID)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val suppressRepeatedText = intent.getBooleanExtra(EXTRA_SUPPRESS_REPEATS, true)
        if (lectureId == INVALID_LECTURE_ID || filePath.isNullOrBlank()) {
            Log.e(TAG, "Recording start request is missing its lecture or output path.")
            stopSelf()
            return
        }
        if (audioPipeline != null ||
            mutableRecordingStatus.value.phase == RecordingPhase.STARTING
        ) {
            Log.e(TAG, "A recording is already active.")
            return
        }
        stopRequestedDuringModelLoad = false
        mutableRecordingStatus.value = RecordingStatus(
            phase = RecordingPhase.STARTING,
            lectureId = lectureId,
            filePath = filePath
        )

        try {
            startForegroundRecordingNotification()
            transcriptPrefix = loadTranscript(lectureId)
            StorageService.unpack(
                this,
                MODEL_ASSET_DIRECTORY,
                MODEL_OUTPUT_DIRECTORY,
                { loadedModel ->
                    onModelLoaded(lectureId, filePath, suppressRepeatedText, loadedModel)
                },
                { error ->
                    failStart(
                        lectureId,
                        filePath,
                        "Не удалось загрузить офлайн-модель распознавания.",
                        error
                    )
                }
            )
        } catch (error: RuntimeException) {
            failStart(
                lectureId,
                filePath,
                "Не удалось подготовить запись и распознавание.",
                error
            )
        }
    }

    private fun onModelLoaded(
        lectureId: Long,
        filePath: String,
        suppressRepeatedText: Boolean,
        model: Model
    ) {
        if (stopRequestedDuringModelLoad) {
            model.close()
            return
        }

        val pipeline = OfflineAudioPipeline(
            context = this,
            outputPath = filePath,
            model = model,
            listener = object : OfflineAudioPipeline.Listener {
                override fun onStarted() {
                    handler.post {
                        elapsedBeforeResume = 0
                        recordingSegmentStartedAt = SystemClock.elapsedRealtime()
                        mutableRecordingStatus.value = mutableRecordingStatus.value.copy(
                            phase = RecordingPhase.RECORDING
                        )
                        handler.post(updateElapsedTime)
                    }
                }

                override fun onPartialResult(text: String) {
                    val status = mutableRecordingStatus.value
                    if (status.lectureId == lectureId && status.phase == RecordingPhase.RECORDING) {
                        mutableRecordingStatus.value = status.copy(partialText = text)
                    }
                }

                override fun onFinalResult(text: String) {
                    val status = mutableRecordingStatus.value
                    if (status.lectureId == lectureId && text.isNotBlank()) {
                        val displayContext = SpeechTextFormatter.format(
                            appendTranscript(transcriptPrefix, status.recognizedText)
                        )
                        val displayText = TranscriptCleaner.displayText(
                            displayContext,
                            SpeechTextFormatter.format(text),
                            suppressRepeatedText
                        )
                        val timestampedDisplayText = displayText
                            .takeIf(String::isNotBlank)
                            ?.let { "[${formatTimestamp(currentElapsedTime())}] $it" }
                        val recognizedText = appendDisplayText(
                            status.recognizedText,
                            timestampedDisplayText
                        )
                        val rawRecognizedText = appendTranscript(status.rawRecognizedText, text)
                        mutableRecordingStatus.value = status.copy(
                            recognizedText = recognizedText,
                            rawRecognizedText = rawRecognizedText,
                            partialText = ""
                        )
                        try {
                            appendRecognizedSegment(lectureId, text, timestampedDisplayText)
                        } catch (error: SQLiteException) {
                            Log.e(TAG, "Could not persist recognized speech.", error)
                            mutableRecordingStatus.value = mutableRecordingStatus.value.copy(
                                errorMessage = "Речь распознана, но не удалось сохранить текст лекции."
                            )
                        }
                    }
                }

                override fun onStopped() {
                    handler.post {
                        handler.removeCallbacks(updateElapsedTime)
                        val status = mutableRecordingStatus.value
                        mutableRecordingStatus.value = status.copy(
                            phase = RecordingPhase.STOPPED,
                            elapsedMillis = currentElapsedTime(),
                            partialText = ""
                        )
                        audioPipeline = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }

                override fun onError(error: Exception) {
                    handler.post {
                        audioPipeline = null
                        failRecording(
                            "Не удалось записать или распознать речь.",
                            error
                        )
                    }
                }
            }
        )
        audioPipeline = pipeline
        try {
            pipeline.start()
        } catch (error: RuntimeException) {
            model.close()
            audioPipeline = null
            failStart(
                lectureId,
                filePath,
                "Не удалось запустить запись и распознавание.",
                error
            )
        }
    }

    private fun appendTranscript(current: String, addition: String): String =
        when {
            current.isBlank() -> addition
            current.endsWith('\n') -> "$current$addition"
            else -> "$current $addition"
        }

    private fun appendDisplayText(current: String, addition: String?): String =
        when {
            addition.isNullOrBlank() -> current
            current.isBlank() -> addition
            else -> "$current\n$addition"
        }

    private fun formatTimestamp(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis / MILLIS_PER_SECOND
        return String.format(
            Locale.ROOT,
            "%02d:%02d",
            totalSeconds / SECONDS_PER_MINUTE,
            totalSeconds % SECONDS_PER_MINUTE
        )
    }

    private fun loadTranscript(lectureId: Long): String {
        val database = LectureDatabase(this)
        return try {
            database.getTranscript(lectureId)
                ?: throw SQLiteException("Could not find lecture $lectureId.")
        } finally {
            database.close()
        }
    }

    private fun appendRecognizedSegment(
        lectureId: Long,
        rawText: String,
        timestampedDisplayText: String?
    ) {
        val database = LectureDatabase(this)
        try {
            database.appendRecognizedSegment(lectureId, rawText, timestampedDisplayText)
        } finally {
            database.close()
        }
    }

    private fun startForegroundRecordingNotification() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Запись лекций",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Идёт запись лекции")
            .setContentText("Аудио сохраняется на устройстве")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPendingIntent)
            .build()
    }

    private fun currentElapsedTime(): Long {
        val status = mutableRecordingStatus.value
        return if (status.phase == RecordingPhase.RECORDING) {
            elapsedBeforeResume + SystemClock.elapsedRealtime() - recordingSegmentStartedAt
        } else {
            status.elapsedMillis
        }
    }

    private fun failRecording(message: String, error: Exception) {
        Log.e(TAG, message, error)
        handler.removeCallbacks(updateElapsedTime)
        val outputPath = mutableRecordingStatus.value.filePath
        if (outputPath != null) deletePartialAudioFile(outputPath)
        mutableRecordingStatus.value = mutableRecordingStatus.value.copy(
            phase = RecordingPhase.ERROR,
            errorMessage = message,
            partialText = ""
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failStart(
        lectureId: Long,
        filePath: String,
        message: String,
        error: Exception
    ) {
        Log.e(TAG, message, error)
        if (stopRequestedDuringModelLoad) return
        handler.removeCallbacks(updateElapsedTime)
        deletePartialAudioFile(filePath)
        mutableRecordingStatus.value = RecordingStatus(
            phase = RecordingPhase.ERROR,
            lectureId = lectureId,
            errorMessage = message
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun deletePartialAudioFile(filePath: String) {
        val partialFile = File(filePath)
        if (partialFile.exists() && !partialFile.delete()) {
            Log.e(TAG, "Could not remove incomplete audio file at $filePath.")
        }
    }

    companion object {
        const val ACTION_START = "com.translecture.app.action.START_RECORDING"
        const val ACTION_STOP = "com.translecture.app.action.STOP_RECORDING"
        const val EXTRA_LECTURE_ID = "com.translecture.app.extra.LECTURE_ID"
        const val EXTRA_FILE_PATH = "com.translecture.app.extra.FILE_PATH"
        const val EXTRA_SUPPRESS_REPEATS = "com.translecture.app.extra.SUPPRESS_REPEATS"

        private const val TAG = "RecordingService"
        private const val NOTIFICATION_CHANNEL_ID = "lecture_recording"
        private const val NOTIFICATION_ID = 1001
        private const val TIMER_UPDATE_INTERVAL_MILLIS = 1000L
        private const val INVALID_LECTURE_ID = -1L
        private const val MODEL_ASSET_DIRECTORY = "model-ru"
        private const val MODEL_OUTPUT_DIRECTORY = "model-ru"
        private const val MILLIS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
    }
}
