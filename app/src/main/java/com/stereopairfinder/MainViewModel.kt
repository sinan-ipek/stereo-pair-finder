package com.stereopairfinder

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stereopairfinder.data.PhotoReader
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.StereoAnalyzer
import com.stereopairfinder.model.AnalysisResult
import com.stereopairfinder.model.DEFAULT_MAX_SECONDS
import com.stereopairfinder.model.DEFAULT_SIMILARITY
import com.stereopairfinder.model.PairPolicy
import com.stereopairfinder.model.Photo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val selected: List<Photo> = emptyList(),
    val results: List<AnalysisResult> = emptyList(),
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
    val similarity: Int = DEFAULT_SIMILARITY,
    val busy: Boolean = false,
    val stage: String = "Fotoğraf seçerek başlayın",
    val message: String? = null,
    val progress: Float = 0f
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = PhotoReader(app.contentResolver)
    private val saver = SbsSaver(app.contentResolver)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var work: Job? = null

    fun select(uris: List<Uri>) {
        work?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val photos = uris.mapNotNull { uri -> runCatching { reader.metadata(uri) }.getOrNull() }
            val duplicateTimes = photos.mapNotNull { it.takenAtMillis }
                .groupingBy { it }.eachCount().any { it.value > 1 }
            _state.value = UiState(
                selected = PairPolicy.sorted(photos),
                stage = "${photos.size} fotoğraf seçildi" +
                    if (duplicateTimes) "; eşit zamanlarda URI sırası kullanılır" else "",
                message = if (photos.size < 2) "En az iki fotoğraf seçin." else null
            )
        }
    }

    fun maxSeconds(value: Int) { _state.value = _state.value.copy(maxSeconds = value) }
    fun similarity(value: Int) { _state.value = _state.value.copy(similarity = value) }

    fun analyze() {
        if (_state.value.selected.size < 2) {
            _state.value = _state.value.copy(message = "En az iki fotoğraf seçin.")
            return
        }
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            val snapshot = _state.value
            val pairs = PairPolicy.adjacent(snapshot.selected)
            val results = mutableListOf<AnalysisResult>()
            _state.value = snapshot.copy(
                results = emptyList(),
                busy = true,
                stage = "Özellik noktaları inceleniyor",
                message = null,
                progress = 0f
            )
            try {
                pairs.forEachIndexed { index, pair ->
                    ensureActive()
                    _state.value = _state.value.copy(
                        stage = "Çift ${index + 1}/${pairs.size} hizalanıyor"
                    )
                    val left = reader.bitmap(pair.left.uri, 2048)
                    val right = reader.bitmap(pair.right.uri, 2048)
                    val result = StereoAnalyzer().analyze(
                        pair,
                        left,
                        right,
                        snapshot.maxSeconds,
                        snapshot.similarity
                    )
                    results += result
                    if (result.leftPreview !== left) left.recycle()
                    if (result.rightPreview !== right) right.recycle()
                    _state.value = _state.value.copy(
                        results = results.toList(),
                        progress = (index + 1f) / pairs.size
                    )
                }
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Tamamlandı: ${results.count { it.saveable }}/${results.size} çift eşleşti"
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(busy = false, stage = "İşlem iptal edildi")
            } catch (failure: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Analiz başarısız",
                    message = failure.message ?: "Bilinmeyen hata"
                )
            }
        }
    }

    fun cancel() { work?.cancel() }

    fun save(result: AnalysisResult) {
        val bitmap = result.sbsPreview ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { saver.save(bitmap) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        message = "${it.name}, ${it.location} konumuna kaydedildi"
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(message = "Kayıt başarısız: ${it.message}")
                }
        }
    }
}
