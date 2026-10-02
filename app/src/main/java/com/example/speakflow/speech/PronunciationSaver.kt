package com.example.speakflow.speech

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore

/** Saves recordings to Download/SpeakFlow (no storage permission needed on Android 10+). */
class PronunciationSaver(private val context: Context) {
    fun save(wav: ByteArray, name: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("저장 위치를 만들지 못했습니다.")
        try {
            (resolver.openOutputStream(uri) ?: error("파일을 열지 못했습니다.")).use { it.write(wav) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    companion object { const val FOLDER = "SpeakFlow" }
}
