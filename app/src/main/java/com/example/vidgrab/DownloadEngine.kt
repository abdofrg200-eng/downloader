package com.example.vidgrab

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * منفّذ التحميل. بيتشغّل من DownloadService، وحالته (StateFlow) بتتشاف من الواجهة والإشعار.
 */
object DownloadEngine {

    data class State(
        val active: Boolean = false,
        val progress: Float = 0f,
        val message: String? = null,
    )

    private const val PROCESS_ID = "vidgrab-download"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    /** بتتندهي من الواجهة قبل تشغيل الخدمة عشان الـ UI يبان إنه بدأ فوراً. */
    fun prepare() {
        cancelled = false
        _state.value = State(active = true, progress = 0f, message = null)
    }

    fun reset() {
        _state.value = State()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun cancel() {
        cancelled = true
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (_: Exception) {
        }
    }

    /** تحميل متزامن (blocking) — لازم يتندهي من خيط خلفي. */
    fun run(context: Context, url: String, audioOnly: Boolean, height: Int?) {
        val workDir = File(context.cacheDir, "dl-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(url.trim())
            req.addOption("--no-playlist")
            req.addOption("-o", "${workDir.absolutePath}/%(title).100s.%(ext)s")

            if (audioOnly) {
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
                req.addOption("--audio-quality", "0")
            } else {
                val format = if (height != null) {
                    "bv*[height<=$height]+ba/b[height<=$height]/b"
                } else {
                    "bv*+ba/b"
                }
                req.addOption("-f", format)
                req.addOption("--merge-output-format", "mp4")
            }

            YoutubeDL.getInstance().execute(req, PROCESS_ID) { progress, _, _ ->
                _state.update { it.copy(progress = (progress / 100f).coerceIn(0f, 1f)) }
            }

            val saved = saveToDownloads(context, workDir)
            _state.value = State(
                active = false,
                progress = 1f,
                message = if (saved != null) "تم الحفظ في Downloads/VidGrab: $saved"
                else "انتهى التحميل لكن مفيش ملف ناتج",
            )
        } catch (e: Exception) {
            _state.value = State(
                active = false,
                message = if (cancelled) "تم إلغاء التحميل" else "فشل التحميل: ${e.message}",
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** ينسخ الملف الناتج لمجلد Downloads/VidGrab عن طريق MediaStore (من غير صلاحيات تخزين). */
    private fun saveToDownloads(context: Context, workDir: File): String? {
        val resolver = context.contentResolver
        val file = workDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: return null

        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VidGrab")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return file.name
    }
}
