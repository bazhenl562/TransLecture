package com.translecture.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.media.MediaPlayer
import android.content.res.Configuration
import android.os.IBinder
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.translecture.app.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val recordingService = mutableStateOf<RecordingService?>(null)
    private val oledBlackMode = mutableStateOf(false)
    private var isRecordingServiceBound = false

    private val recordingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RecordingService.LocalBinder
            recordingService.value = binder?.getService()
            if (binder == null) {
                Log.e("MainActivity", "Recording service returned an unexpected binder.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        oledBlackMode.value = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(PREFERENCE_OLED_BLACK_MODE, false)
        setContent {
            MyApplicationTheme(oledBlackMode = oledBlackMode.value) {
                LectureLibraryScreen(
                    recordingService = recordingService.value,
                    oledBlackMode = oledBlackMode.value,
                    onOledBlackModeChange = { enabled ->
                        oledBlackMode.value = enabled
                        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREFERENCE_OLED_BLACK_MODE, enabled)
                            .apply()
                    },
                    startRecording = ::startRecording,
                    pauseRecording = { recordingService.value?.pauseRecording() },
                    resumeRecording = { recordingService.value?.resumeRecording() },
                    stopRecording = { recordingService.value?.stopRecording() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isRecordingServiceBound = bindService(
            Intent(this, RecordingService::class.java),
            recordingServiceConnection,
            Context.BIND_AUTO_CREATE
        )
        if (!isRecordingServiceBound) {
            Log.e("MainActivity", "Could not bind to recording service.")
        }
    }

    override fun onStop() {
        if (isRecordingServiceBound) {
            unbindService(recordingServiceConnection)
            isRecordingServiceBound = false
        }
        recordingService.value = null
        super.onStop()
    }

    private fun startRecording(
        lectureId: Long,
        filePath: String,
        suppressRepeatedText: Boolean
    ) {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_LECTURE_ID, lectureId)
            putExtra(RecordingService.EXTRA_FILE_PATH, filePath)
            putExtra(RecordingService.EXTRA_SUPPRESS_REPEATS, suppressRepeatedText)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private companion object {
        const val PREFERENCES_NAME = "lecture_notes_preferences"
        const val PREFERENCE_OLED_BLACK_MODE = "oled_black_mode"
    }
}

@Composable
fun LectureLibraryScreen(
    recordingService: RecordingService?,
    oledBlackMode: Boolean,
    onOledBlackModeChange: (Boolean) -> Unit,
    startRecording: (Long, String, Boolean) -> Unit,
    pauseRecording: () -> Unit,
    resumeRecording: () -> Unit,
    stopRecording: () -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { LectureDatabase(context.applicationContext) }
    DisposableEffect(database) {
        onDispose { database.close() }
    }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var lectures by remember { mutableStateOf(emptyList<Lecture>()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedLectureId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(database) {
        try {
            lectures = withContext(Dispatchers.IO) { database.getLectures() }
        } catch (error: SQLiteException) {
            Log.e("LectureLibrary", "Could not load lectures", error)
            snackbarHostState.showSnackbar("Не удалось загрузить список лекций.")
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { contentPadding ->
        val lectureToEdit = lectures.firstOrNull { it.id == selectedLectureId }
        if (lectureToEdit == null) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                if (maxWidth >= LARGE_SCREEN_BREAKPOINT) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        LibraryControls(
                            modifier = Modifier
                                .width(LIBRARY_CONTROLS_WIDTH)
                                .fillMaxHeight(),
                            oledBlackMode = oledBlackMode,
                            onOledBlackModeChange = onOledBlackModeChange,
                            onCreateLecture = { showCreateDialog = true }
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "Список лекций",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            LectureListContent(
                                lectures = lectures,
                                isLoading = isLoading,
                                onLectureClick = { selectedLectureId = it.id },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        LibraryControls(
                            modifier = Modifier.fillMaxWidth(),
                            oledBlackMode = oledBlackMode,
                            onOledBlackModeChange = onOledBlackModeChange,
                            onCreateLecture = { showCreateDialog = true }
                        )
                        LectureListContent(
                            lectures = lectures,
                            isLoading = isLoading,
                            onLectureClick = { selectedLectureId = it.id },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        } else {
            LectureEditorScreen(
                lecture = lectureToEdit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                onBack = { selectedLectureId = null },
                onSave = { transcript ->
                    val updatedLecture = withContext(Dispatchers.IO) {
                        database.saveTranscript(lectureToEdit.id, transcript)
                        database.getLectures().firstOrNull { it.id == lectureToEdit.id }
                            ?: throw SQLiteException("Could not reload lecture ${lectureToEdit.id}.")
                    }
                    selectedLectureId = updatedLecture.id
                    lectures = lectures.map { lecture ->
                        if (lecture.id == updatedLecture.id) updatedLecture else lecture
                    }
                },
                recordingService = recordingService,
                onStartRecording = { lectureId, transcript, suppressRepeatedText ->
                    val directory = File(context.filesDir, "recordings")
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw IOException("Could not create the lecture audio directory.")
                    }
                    val outputFile = File(directory, "lecture-$lectureId-${UUID.randomUUID()}.m4a")
                    withContext(Dispatchers.IO) {
                        database.saveTranscript(lectureId, transcript)
                        database.saveAudioPath(lectureId, outputFile.absolutePath)
                    }
                    startRecording(lectureId, outputFile.absolutePath, suppressRepeatedText)
                    val updatedLecture = lectureToEdit.copy(
                        transcript = transcript,
                        audioPath = outputFile.absolutePath
                    )
                    selectedLectureId = updatedLecture.id
                    lectures = lectures.map { lecture ->
                        if (lecture.id == updatedLecture.id) updatedLecture else lecture
                    }
                },
                onPauseRecording = pauseRecording,
                onResumeRecording = resumeRecording,
                onStopRecording = stopRecording
            )
        }
    }

    if (showCreateDialog) {
        CreateLectureDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title ->
                coroutineScope.launch {
                    try {
                        val lecture = withContext(Dispatchers.IO) {
                            database.createLecture(title)
                        }
                        lectures = listOf(lecture) + lectures
                        showCreateDialog = false
                    } catch (error: SQLiteException) {
                        Log.e("LectureLibrary", "Could not create lecture", error)
                        snackbarHostState.showSnackbar("Не удалось сохранить лекцию.")
                    }
                }
            }
        )
    }
}

@Composable
private fun LibraryControls(
    modifier: Modifier = Modifier,
    oledBlackMode: Boolean,
    onOledBlackModeChange: (Boolean) -> Unit,
    onCreateLecture: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Ваши лекции",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Создайте лекцию — она сохранится на этом устройстве.",
            style = MaterialTheme.typography.bodyLarge
        )
        Button(
            onClick = onCreateLecture,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Новая лекция")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Чёрный фон OLED")
                Text(
                    text = "Для экранов OLED",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = oledBlackMode,
                onCheckedChange = onOledBlackModeChange
            )
        }
    }
}

@Composable
private fun LectureListContent(
    lectures: List<Lecture>,
    isLoading: Boolean,
    onLectureClick: (Lecture) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        isLoading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }

        lectures.isEmpty() -> EmptyLectureList()
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(lectures, key = Lecture::id) { lecture ->
                LectureCard(
                    lecture = lecture,
                    onClick = { onLectureClick(lecture) }
                )
            }
        }
    }
}

@Composable
private fun EmptyLectureList() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Пока лекций нет",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "После создания лекции здесь появится её запись.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LectureCard(lecture: Lecture, onClick: () -> Unit) {
    val dateFormatter = remember {
        SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = lecture.title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = dateFormatter.format(Date(lecture.createdAt)),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LectureEditorScreen(
    lecture: Lecture,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSave: suspend (String) -> Unit,
    recordingService: RecordingService?,
    onStartRecording: suspend (Long, String, Boolean) -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val context = LocalContext.current
    var transcript by remember(lecture.id) { mutableStateOf(lecture.transcript) }
    var savedTranscript by remember(lecture.id) { mutableStateOf(lecture.transcript) }
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }
    var showOriginalTranscript by rememberSaveable { mutableStateOf(false) }
    var showFullScreenTranscript by rememberSaveable { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var shouldStartRecording by remember { mutableStateOf(false) }
    var suppressRepeatedText by rememberSaveable(lecture.id) { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val emptyRecordingState = remember { MutableStateFlow(RecordingStatus()) }
    val recordingStatusFlow = recordingService?.recordingStatus ?: emptyRecordingState
    val recordingStatus by recordingStatusFlow.collectAsState()
    val currentOnSave by rememberUpdatedState(onSave)
    var appliedRecognition by remember(lecture.id) { mutableStateOf("") }
    val hasUnsavedChanges = transcript != savedTranscript
    val isPreparingRecording = recordingStatus.lectureId == lecture.id &&
        recordingStatus.phase == RecordingPhase.STARTING
    val isRecordingForLecture = recordingStatus.lectureId == lecture.id &&
        (recordingStatus.phase == RecordingPhase.RECORDING ||
            recordingStatus.phase == RecordingPhase.PAUSED)
    val audioFileExists = lecture.audioPath?.let { File(it).isFile } == true
    val originalTranscript = appendTranscript(
        lecture.rawTranscript,
        recordingStatus.rawRecognizedText
    )

    fun requestBack() {
        if (isPreparingRecording || isRecordingForLecture) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Остановите запись, чтобы закрыть лекцию.")
            }
        } else if (hasUnsavedChanges) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    suspend fun beginRecording() {
        try {
            onStartRecording(lecture.id, transcript, suppressRepeatedText)
            snackbarHostState.showSnackbar("Аудиозапись началась.")
        } catch (error: IOException) {
            Log.e("LectureEditor", "Could not prepare audio output", error)
            snackbarHostState.showSnackbar("Не удалось подготовить файл аудиозаписи.")
        } catch (error: SQLiteException) {
            Log.e("LectureEditor", "Could not save audio file path", error)
            snackbarHostState.showSnackbar("Не удалось сохранить данные аудиозаписи.")
        } catch (error: SecurityException) {
            Log.e("LectureEditor", "Microphone recording permission was denied", error)
            snackbarHostState.showSnackbar("Нет разрешения на использование микрофона.")
        } catch (error: IllegalStateException) {
            Log.e("LectureEditor", "Could not start foreground audio recording", error)
            snackbarHostState.showSnackbar("Не удалось запустить фоновую запись.")
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        shouldStartRecording = false
        if (granted) {
            coroutineScope.launch { beginRecording() }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Для записи лекции нужен доступ к микрофону.")
            }
        }
    }

    LaunchedEffect(recordingStatus.errorMessage) {
        recordingStatus.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(lecture.id, recordingStatusFlow) {
        recordingStatusFlow.collect { status ->
            if (status.lectureId != lecture.id) return@collect
            if (appliedRecognition.isEmpty() &&
                status.recognizedText.isNotEmpty() &&
                transcript.contains(status.recognizedText)
            ) {
                appliedRecognition = status.recognizedText
                return@collect
            }
            if (status.phase == RecordingPhase.STARTING && status.recognizedText.isEmpty()) {
                appliedRecognition = ""
            }

            val recognizedText = status.recognizedText
            if (recognizedText.startsWith(appliedRecognition) &&
                recognizedText.length > appliedRecognition.length
            ) {
                val newText = recognizedText.removePrefix(appliedRecognition)
                transcript = appendTimestampedTranscript(transcript, newText)
                appliedRecognition = recognizedText
                try {
                    currentOnSave(transcript)
                    savedTranscript = transcript
                } catch (error: SQLiteException) {
                    Log.e("LectureEditor", "Could not save recognized speech", error)
                    snackbarHostState.showSnackbar("Речь распознана, но не удалось сохранить текст.")
                }
            } else if (status.phase == RecordingPhase.STOPPED && transcript != savedTranscript) {
                try {
                    currentOnSave(transcript)
                    savedTranscript = transcript
                } catch (error: SQLiteException) {
                    Log.e("LectureEditor", "Could not save the final transcript", error)
                    snackbarHostState.showSnackbar("Не удалось сохранить итоговый текст лекции.")
                }
            }
        }
    }

    BackHandler {
        if (showDiscardDialog) {
            showDiscardDialog = false
        } else {
            requestBack()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = ::requestBack) {
                Text("К списку лекций")
            }
            Text(
                text = lecture.title,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Офлайн-распознавание работает без интернета. Текст лекции сохраняется автоматически.",
                style = MaterialTheme.typography.bodyMedium
            )
            when {
                isPreparingRecording -> Text(
                    text = "Подготовка аудиозаписи…",
                    style = MaterialTheme.typography.titleMedium
                )

                isRecordingForLecture -> {
                    Text(
                        text = "Идёт запись: ${formatRecordingDuration(recordingStatus.elapsedMillis)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (recordingStatus.phase == RecordingPhase.RECORDING) {
                                    onPauseRecording()
                                } else {
                                    onResumeRecording()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (recordingStatus.phase == RecordingPhase.RECORDING) {
                                    "Пауза"
                                } else {
                                    "Продолжить"
                                }
                            )
                        }
                        Button(
                            onClick = onStopRecording,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Остановить")
                        }
                    }
                }

                audioFileExists -> Text(
                    text = "Аудиозапись сохранена на устройстве.",
                    style = MaterialTheme.typography.bodyMedium
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("Подавлять повторы в тексте")
                            Text(
                                text = if (suppressRepeatedText) {
                                    "Исходная расшифровка сохранится полностью."
                                } else {
                                    "Все распознанные фрагменты будут показаны."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = suppressRepeatedText,
                            onCheckedChange = { suppressRepeatedText = it },
                            enabled = !shouldStartRecording
                        )
                    }
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                coroutineScope.launch { beginRecording() }
                            } else {
                                shouldStartRecording = true
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !shouldStartRecording
                    ) {
                        Text(if (shouldStartRecording) "Запрос доступа…" else "Начать аудиозапись")
                    }
                }
            }
            if (transcript.isNotBlank() || isPreparingRecording || isRecordingForLecture) {
                TextButton(onClick = { showFullScreenTranscript = true }) {
                    Text("Показать текст крупно")
                }
            }
            lecture.audioPath
                ?.takeIf { audioFileExists && !isPreparingRecording && !isRecordingForLecture }
                ?.let { audioPath -> AudioPlaybackControls(audioPath = audioPath) }
            TextButton(
                onClick = { showOriginalTranscript = true },
                enabled = originalTranscript.isNotBlank()
            ) {
                Text("Показать исходный текст")
            }
            OutlinedTextField(
                value = transcript,
                onValueChange = { transcript = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("Текст лекции") },
                placeholder = { Text("Текст лекции пока пуст") },
                enabled = !isPreparingRecording && !isRecordingForLecture
            )
            if (isRecordingForLecture && recordingStatus.partialText.isNotBlank()) {
                Text(
                    text = "Распознано сейчас: ${recordingStatus.partialText}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = {
                    coroutineScope.launch {
                        isSaving = true
                        try {
                            onSave(transcript)
                            savedTranscript = transcript
                            snackbarHostState.showSnackbar("Текст сохранён.")
                        } catch (error: SQLiteException) {
                            Log.e("LectureEditor", "Could not save transcript", error)
                            snackbarHostState.showSnackbar("Не удалось сохранить текст.")
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hasUnsavedChanges && !isSaving
            ) {
                Text(if (isSaving) "Сохранение…" else "Сохранить текст")
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Есть несохранённый текст") },
            text = { Text("Выйти без сохранения изменений?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    }
                ) {
                    Text("Выйти без сохранения")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Продолжить редактирование")
                }
            }
        )
    }

    if (showOriginalTranscript) {
        AlertDialog(
            onDismissRequest = { showOriginalTranscript = false },
            title = { Text("Исходный текст распознавания") },
            text = {
                Text(
                    text = originalTranscript,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showOriginalTranscript = false }) {
                    Text("Закрыть")
                }
            }
        )
    }

    if (showFullScreenTranscript) {
        FullScreenTranscriptDialog(
            title = lecture.title,
            transcript = transcript,
            partialText = recordingStatus.partialText,
            isRecording = isPreparingRecording || isRecordingForLecture,
            elapsedMillis = recordingStatus.elapsedMillis,
            onDismiss = { showFullScreenTranscript = false }
        )
    }
}

@Composable
private fun FullScreenTranscriptDialog(
    title: String,
    transcript: String,
    partialText: String,
    isRecording: Boolean,
    elapsedMillis: Long,
    onDismiss: () -> Unit
) {
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scrollState = rememberScrollState()
    val horizontalPadding = if (isLandscape) 24.dp else 20.dp
    val transcriptFontSize = if (isLandscape) 24.sp else 28.sp
    val transcriptLineHeight = if (isLandscape) 34.sp else 40.sp

    LaunchedEffect(transcript) {
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Закрыть")
                    }
                }
                if (isRecording) {
                    Text(
                        text = "Идёт запись · ${formatRecordingDuration(elapsedMillis)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = transcript.ifBlank { "Распознанный текст появится здесь." },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = transcriptFontSize,
                            lineHeight = transcriptLineHeight
                        )
                    )
                }
                if (partialText.isNotBlank()) {
                    Text(
                        text = "Распознаётся: $partialText",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (isLandscape) 18.sp else 20.sp
                        )
                    )
                }
            }
        }
    }
}

private enum class AudioPlaybackPhase {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR
}

private fun appendTranscript(current: String, addition: String): String {
    val normalizedAddition = addition.trim()
    return when {
        normalizedAddition.isEmpty() -> current
        current.isBlank() -> normalizedAddition
        current.endsWith('\n') -> "$current$normalizedAddition"
        else -> "$current $normalizedAddition"
    }
}

private fun appendTimestampedTranscript(current: String, addition: String): String {
    val normalizedAddition = addition.trim()
    return when {
        normalizedAddition.isEmpty() -> current
        current.isBlank() -> normalizedAddition
        else -> "${current.trimEnd()}\n${normalizedAddition.trimStart()}"
    }
}

@Composable
private fun AudioPlaybackControls(audioPath: String) {
    val context = LocalContext.current
    var mediaPlayer by remember(audioPath) { mutableStateOf<MediaPlayer?>(null) }
    var playbackPhase by remember(audioPath) { mutableStateOf(AudioPlaybackPhase.IDLE) }
    var playbackError by remember(audioPath) { mutableStateOf<String?>(null) }

    DisposableEffect(audioPath) {
        onDispose {
            mediaPlayer?.setOnPreparedListener(null)
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.setOnErrorListener(null)
            mediaPlayer?.release()
        }
    }

    fun releasePlayer() {
        mediaPlayer?.setOnPreparedListener(null)
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.setOnErrorListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }

    fun handlePlaybackError(player: MediaPlayer?, error: Exception) {
        Log.e("AudioPlayback", "Could not play audio file", error)
        player?.setOnPreparedListener(null)
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        player?.release()
        if (mediaPlayer === player) {
            mediaPlayer = null
        }
        playbackPhase = AudioPlaybackPhase.ERROR
        playbackError = "Не удалось воспроизвести аудиозапись."
    }

    fun startPlayback() {
        releasePlayer()
        val player = MediaPlayer()
        mediaPlayer = player
        playbackPhase = AudioPlaybackPhase.PREPARING
        playbackError = null

        try {
            player.setDataSource(audioPath)
            player.setOnPreparedListener { preparedPlayer ->
                preparedPlayer.start()
                playbackPhase = AudioPlaybackPhase.PLAYING
            }
            player.setOnCompletionListener { completedPlayer ->
                completedPlayer.release()
                mediaPlayer = null
                playbackPhase = AudioPlaybackPhase.COMPLETED
            }
            player.setOnErrorListener { failedPlayer, what, extra ->
                Log.e("AudioPlayback", "MediaPlayer error: what=$what, extra=$extra")
                failedPlayer.release()
                mediaPlayer = null
                playbackPhase = AudioPlaybackPhase.ERROR
                playbackError = "Не удалось воспроизвести аудиозапись."
                true
            }
            player.prepareAsync()
        } catch (error: IOException) {
            handlePlaybackError(player, error)
        } catch (error: IllegalArgumentException) {
            handlePlaybackError(player, error)
        } catch (error: IllegalStateException) {
            handlePlaybackError(player, error)
        } catch (error: SecurityException) {
            handlePlaybackError(player, error)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Аудиозапись",
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (playbackPhase == AudioPlaybackPhase.PLAYING) {
                        try {
                            mediaPlayer?.pause()
                            playbackPhase = AudioPlaybackPhase.PAUSED
                        } catch (error: IllegalStateException) {
                            handlePlaybackError(mediaPlayer, error)
                        }
                    } else if (playbackPhase == AudioPlaybackPhase.PAUSED) {
                        try {
                            mediaPlayer?.start()
                            playbackPhase = AudioPlaybackPhase.PLAYING
                        } catch (error: IllegalStateException) {
                            handlePlaybackError(mediaPlayer, error)
                        }
                    } else {
                        startPlayback()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = playbackPhase != AudioPlaybackPhase.PREPARING
            ) {
                Text(
                    when (playbackPhase) {
                        AudioPlaybackPhase.PLAYING -> "Пауза"
                        AudioPlaybackPhase.PREPARING -> "Подготовка…"
                        AudioPlaybackPhase.PAUSED -> "Продолжить"
                        AudioPlaybackPhase.COMPLETED -> "Слушать снова"
                        else -> "Прослушать"
                    }
                )
            }
            if (mediaPlayer != null) {
                TextButton(
                    onClick = {
                        releasePlayer()
                        playbackPhase = AudioPlaybackPhase.IDLE
                    }
                ) {
                    Text("Закрыть")
                }
            }
        }
        playbackError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatRecordingDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
}

private val LARGE_SCREEN_BREAKPOINT = 720.dp
private val LIBRARY_CONTROLS_WIDTH = 300.dp

@Composable
private fun CreateLectureDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая лекция") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(100) },
                label = { Text("Название") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim()) },
                enabled = title.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun EmptyLectureListPreview() {
    MyApplicationTheme {
        EmptyLectureList()
    }
}
