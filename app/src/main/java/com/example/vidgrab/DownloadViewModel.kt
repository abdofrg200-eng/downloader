package com.example.vidgrab

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val url: String = "",
    val loading: Boolean = false,
    val title: String? = null,
    val thumbnail: String? = null,
    val heights: List<Int> = emptyList(),
    val audioOnly: Boolean = false,
    val selectedHeight: Int? = null, // null = أعلى جودة متاحة
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    // حالة الواجهة (الرابط، المعلومات، الاختيارات)
    private val ui = MutableStateFlow(UiState())

    // بندمجها مع حالة التحميل اللي جاية من الخدمة
    val state: StateFlow<UiState> = combine(ui, DownloadEngine.state) { u, e ->
        u.copy(
            downloading = e.active,
            progress = e.progress,
            message = e.message ?: u.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    fun onUrl(value: String) = ui.update { it.copy(url = value) }
    fun setAudioOnly(value: Boolean) = ui.update { it.copy(audioOnly = value) }
    fun selectHeight(value: Int?) = ui.update { it.copy(selectedHeight = value) }

    fun fetchInfo() {
        val url = ui.value.url.trim()
        if (url.isEmpty()) return
        DownloadEngine.clearMessage()
        ui.update {
            it.copy(
                loading = true, message = null, title = null, thumbnail = null,
                heights = emptyList(), selectedHeight = null,
            )
        }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url))
                }
                val heights = info.formats
                    ?.map { it.height }
                    ?.filter { it > 0 }
                    ?.distinct()
                    ?.sortedDescending()
                    ?: emptyList()
                ui.update {
                    it.copy(
                        loading = false,
                        title = info.title,
                        thumbnail = info.thumbnail,
                        heights = heights,
                    )
                }
            } catch (e: Exception) {
                ui.update { it.copy(loading = false, message = "تعذّر قراءة الرابط: ${e.message}") }
            }
        }
    }

    fun download() {
        val s = state.value
        if (s.url.isBlank() || s.downloading) return

        val ctx = getApplication<Application>()
        DownloadEngine.prepare()
        val intent = Intent(ctx, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, s.url.trim())
            putExtra(DownloadService.EXTRA_AUDIO_ONLY, s.audioOnly)
            putExtra(DownloadService.EXTRA_HEIGHT, s.selectedHeight ?: -1)
            putExtra(DownloadService.EXTRA_TITLE, s.title ?: "VidGrab")
        }
        try {
            ContextCompat.startForegroundService(ctx, intent)
        } catch (e: Exception) {
            DownloadEngine.reset()
            ui.update { it.copy(message = "تعذّر بدء خدمة التحميل: ${e.message}") }
        }
    }

    fun cancel() = DownloadEngine.cancel()
}
