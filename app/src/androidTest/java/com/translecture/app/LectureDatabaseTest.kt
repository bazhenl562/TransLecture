package com.translecture.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class LectureDatabaseTest {
    @Test
    fun migrationPreservesLecturesAndEditedTranscriptAfterReopeningDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "lecture-test-${System.nanoTime()}.db"
        val legacyDatabase = object : SQLiteOpenHelper(context, databaseName, null, 1) {
            override fun onCreate(database: SQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE lectures (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, created_at INTEGER NOT NULL)"
                )
                database.insertOrThrow(
                    "lectures",
                    null,
                    ContentValues().apply {
                        put("title", "Лекция по физике")
                        put("created_at", 1_700_000_000_000L)
                    }
                )
            }

            override fun onUpgrade(
                database: SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) = Unit
        }

        try {
            legacyDatabase.writableDatabase
            legacyDatabase.close()

            val database = LectureDatabase(context, databaseName)
            try {
                val migratedLecture = Lecture(
                    id = 1,
                    title = "Лекция по физике",
                    createdAt = 1_700_000_000_000L,
                    transcript = "",
                    rawTranscript = "",
                    audioPath = null
                )
                assertEquals(listOf(migratedLecture), database.getLectures())
                val createdLecture = database.createLecture("  Новая лекция  ")
                assertEquals("Новая лекция", createdLecture.title)
                database.saveTranscript(migratedLecture.id, "Сила равна массе, умноженной на ускорение.")
                database.saveAudioPath(migratedLecture.id, "/recordings/physics.m4a")
                database.close()

                val reopenedDatabase = LectureDatabase(context, databaseName)
                try {
                    val reopenedLectures = reopenedDatabase.getLectures()
                    assertEquals(
                        migratedLecture.copy(
                            transcript = "Сила равна массе, умноженной на ускорение.",
                            audioPath = "/recordings/physics.m4a"
                        ),
                        reopenedLectures.single { it.id == migratedLecture.id }
                    )
                    assertEquals(
                        createdLecture,
                        reopenedLectures.single { it.id == createdLecture.id }
                    )
                } finally {
                    reopenedDatabase.close()
                }
            } finally {
                database.close()
            }
        } finally {
            legacyDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun recognizedSegmentsKeepRawTextAndAppendTimestampedDisplayText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "recognized-test-${System.nanoTime()}.db"
        val database = LectureDatabase(context, databaseName)
        try {
            val lecture = database.createLecture("Лекция")
            database.appendRecognizedSegment(
                lecture.id,
                rawText = "Скорость равна пути",
                timestampedDisplayText = "[00:04] Скорость равна пути"
            )
            database.appendRecognizedSegment(
                lecture.id,
                rawText = "Скорость равна пути",
                timestampedDisplayText = null
            )

            assertEquals(
                lecture.copy(
                    transcript = "[00:04] Скорость равна пути",
                    rawTranscript = "Скорость равна пути Скорость равна пути"
                ),
                database.getLectures().single()
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
