package com.translecture.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

data class Lecture(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val transcript: String,
    val rawTranscript: String,
    val audioPath: String?
)

class LectureDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_LECTURES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_TRANSCRIPT TEXT NOT NULL DEFAULT '',
                $COLUMN_RAW_TRANSCRIPT TEXT NOT NULL DEFAULT '',
                $COLUMN_AUDIO_PATH TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL(
                "ALTER TABLE $TABLE_LECTURES ADD COLUMN $COLUMN_TRANSCRIPT TEXT NOT NULL DEFAULT ''"
            )
        }
        if (oldVersion < 3) {
            database.execSQL("ALTER TABLE $TABLE_LECTURES ADD COLUMN $COLUMN_AUDIO_PATH TEXT")
        }
        if (oldVersion < 4) {
            database.execSQL(
                "ALTER TABLE $TABLE_LECTURES ADD COLUMN $COLUMN_RAW_TRANSCRIPT TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    fun getLectures(): List<Lecture> {
        val lectures = mutableListOf<Lecture>()
        readableDatabase.query(
            TABLE_LECTURES,
            arrayOf(
                COLUMN_ID,
                COLUMN_TITLE,
                COLUMN_CREATED_AT,
                COLUMN_TRANSCRIPT,
                COLUMN_RAW_TRANSCRIPT,
                COLUMN_AUDIO_PATH
            ),
            null,
            null,
            null,
            null,
            "$COLUMN_CREATED_AT DESC, $COLUMN_ID DESC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
            val createdAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
            val transcriptIndex = cursor.getColumnIndexOrThrow(COLUMN_TRANSCRIPT)
            val rawTranscriptIndex = cursor.getColumnIndexOrThrow(COLUMN_RAW_TRANSCRIPT)
            val audioPathIndex = cursor.getColumnIndexOrThrow(COLUMN_AUDIO_PATH)
            while (cursor.moveToNext()) {
                lectures += Lecture(
                    id = cursor.getLong(idIndex),
                    title = cursor.getString(titleIndex),
                    createdAt = cursor.getLong(createdAtIndex),
                    transcript = cursor.getString(transcriptIndex),
                    rawTranscript = cursor.getString(rawTranscriptIndex),
                    audioPath = cursor.getString(audioPathIndex)
                )
            }
        }
        return lectures
    }

    fun createLecture(title: String): Lecture {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotEmpty()) { "Lecture title must not be blank." }

        val createdAt = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COLUMN_TITLE, normalizedTitle)
            put(COLUMN_CREATED_AT, createdAt)
            put(COLUMN_TRANSCRIPT, "")
            put(COLUMN_RAW_TRANSCRIPT, "")
            putNull(COLUMN_AUDIO_PATH)
        }
        val id = writableDatabase.insertOrThrow(TABLE_LECTURES, null, values)
        return Lecture(id, normalizedTitle, createdAt, "", "", null)
    }

    fun saveTranscript(lectureId: Long, transcript: String) {
        val values = ContentValues().apply {
            put(COLUMN_TRANSCRIPT, transcript)
        }
        val updatedRows = writableDatabase.update(
            TABLE_LECTURES,
            values,
            "$COLUMN_ID = ?",
            arrayOf(lectureId.toString())
        )
        if (updatedRows != 1) {
            throw android.database.sqlite.SQLiteException(
                "Expected to update one lecture, updated $updatedRows."
            )
        }
    }

    fun getTranscript(lectureId: Long): String? =
        readableDatabase.query(
            TABLE_LECTURES,
            arrayOf(COLUMN_TRANSCRIPT),
            "$COLUMN_ID = ?",
            arrayOf(lectureId.toString()),
            null,
            null,
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun appendRecognizedSegment(
        lectureId: Long,
        rawText: String,
        timestampedDisplayText: String?
    ) {
        require(rawText.isNotBlank()) { "Recognized text must not be blank." }
        val database = writableDatabase
        database.beginTransaction()
        try {
            val current = database.query(
                TABLE_LECTURES,
                arrayOf(COLUMN_TRANSCRIPT, COLUMN_RAW_TRANSCRIPT),
                "$COLUMN_ID = ?",
                arrayOf(lectureId.toString()),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw SQLiteException("Could not find lecture $lectureId.")
                }
                cursor.getString(0) to cursor.getString(1)
            }
            val values = ContentValues().apply {
                put(COLUMN_RAW_TRANSCRIPT, appendText(current.second, rawText))
                if (!timestampedDisplayText.isNullOrBlank()) {
                    put(COLUMN_TRANSCRIPT, appendDisplayLine(current.first, timestampedDisplayText))
                }
            }
            val updatedRows = database.update(
                TABLE_LECTURES,
                values,
                "$COLUMN_ID = ?",
                arrayOf(lectureId.toString())
            )
            if (updatedRows != 1) {
                throw SQLiteException("Expected to update one lecture, updated $updatedRows.")
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun saveAudioPath(lectureId: Long, audioPath: String?) {
        val values = ContentValues().apply {
            if (audioPath == null) {
                putNull(COLUMN_AUDIO_PATH)
            } else {
                put(COLUMN_AUDIO_PATH, audioPath)
            }
        }
        val updatedRows = writableDatabase.update(
            TABLE_LECTURES,
            values,
            "$COLUMN_ID = ?",
            arrayOf(lectureId.toString())
        )
        if (updatedRows != 1) {
            throw SQLiteException("Expected to update one lecture, updated $updatedRows.")
        }
    }

    private companion object {
        const val DATABASE_NAME = "lectures.db"
        const val DATABASE_VERSION = 4
        const val TABLE_LECTURES = "lectures"
        const val COLUMN_ID = "id"
        const val COLUMN_TITLE = "title"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_TRANSCRIPT = "transcript"
        const val COLUMN_RAW_TRANSCRIPT = "raw_transcript"
        const val COLUMN_AUDIO_PATH = "audio_path"

        fun appendText(current: String, addition: String): String =
            if (current.isBlank()) addition else "$current $addition"

        fun appendDisplayLine(current: String, addition: String): String =
            if (current.isBlank()) addition else "$current\n$addition"
    }
}
