package com.stereopairfinder

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stereopairfinder.data.GalleryRepository
import com.stereopairfinder.data.PhotoReader
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.data.ScanBoundaryPolicy
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
import java.util.IdentityHashMap

enum class AppPage { HOME, MANUAL }

data class ScanSummary(
    val photos: Int,
    val pairs: Int,
    val matched: Int,
    val rejected: Int,
    val errors: Int,
    val firstScan: Boolean
)

data class UiState(
    val page: AppPage = AppPage.HOME,
    val selected: List<Photo> = emptyList(),
    val results: List<AnalysisResult> = emptyList(),
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
    val similarity: Int = DEFAULT_SIMILARITY,
    val busy: Boolean = false,
    val stage: String = "Hazır",
    val message: String? = null,
    val progress: Float = 0f,
    val lastScanMillis: Long? = null,
    val scanSummary: ScanSummary? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = PhotoReader(app.contentResolver)
    private val saver = SbsSaver(app.contentResolver)
    private val gallery = GalleryRepository(app)
    private val analyzer by lazy { StereoAnalyzer() }
    private val _state = MutableStateFlow(UiState(lastScanMillis = gallery.lastScanMillis()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var work: Job? = null

    fun openManual() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            page = AppPage.MANUAL,
            stage = "İki fotoğraf seçin",
            message = null
        )
    }

    fun goHome() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(page = AppPage.HOME, message = null)
    }

    fun permissionDenied() {
        _state.value = _state.value.copy(
            message = "Galeriyi taramak için bütün fotoğraflara erişim izni gerekir."
        )
    }

    fun select(uris: List<Uri>) {
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.IO) {
            recycleResults(_state.value.results)
            val photos = uris.take(2).mapNotNull { uri ->
                runCatching { reader.metadata(uri) }.getOrNull()
            }
            _state.value = _state.value.copy(
                selected = PairPolicy.sorted(photos),
                results = emptyList(),
                stage = if (photos.size == 2) "İki fotoğraf seçildi" else "İki fotoğraf seçin",
                message = if (photos.size < 2) "Tam olarak iki fotoğraf seçin." else null,
                progress = 0f
            )
        }
    }

    fun maxSeconds(value: Int) {
        _state.value = _state.value.copy(maxSeconds = value)
    }

    fun similarity(value: Int) {
        _state.value = _state.value.copy(similarity = value)
    }

    fun analyzeManual() {
        if (_state.value.selected.size != 2) {
            _state.value = _state.value.copy(message = "Önce iki fotoğraf seçin.")
            return
        }
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            val snapshot = _state.value
            val pair = PairPolicy.adjacent(snapshot.selected).single()
            recycleResults(snapshot.results)
            _state.value = snapshot.copy(
                results = emptyList(),
                busy = true,
                stage = "Fotoğraflar karşılaştırılıyor",
                message = null,
                progress = 0.1f
            )
            try {
                val left = reader.bitmap(pair.left.uri, 2048)
                val right = reader.bitmap(pair.right.uri, 2048)
                val result = analyzer.analyze(
                    pair,
                    left,
                    right,
                    snapshot.maxSeconds,
                    snapshot.similarity
                )
                if (result.leftPreview !== left) left.recycle()
                if (result.rightPreview !== right) right.recycle()
                _state.value = _state.value.copy(
                    results = listOf(result),
                    busy = false,
                    stage = result.status.text,
                    progress = 1f
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

    fun scanGallery() {
        if (_state.value.busy) return
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            _state.value = _state.value.copy(
                page = AppPage.HOME,
                busy = true,
                stage = "Yeni fotoğraflar aranıyor",
                message = null,
                progress = 0f,
                scanSummary = null
            )
            try {
                val batch = gallery.prepareBatch()
                ensureActive()
                if (batch.photos.isEmpty()) {
                    gallery.commit(batch)
                    val completed = gallery.lastScanMillis()
                    _state.value = _state.value.copy(
                        busy = false,
                        stage = "Yeni fotoğraf bulunamadı",
                        lastScanMillis = completed,
                        scanSummary = ScanSummary(0, 0, 0, 0, 0, batch.firstScan),
                        progress = 1f
                    )
                    return@launch
                }
                if (batch.photos.size == 1) {
                    _state.value = _state.value.copy(
                        busy = false,
                        stage = "Bir yeni fotoğraf beklemede",
                        message = "İlerleme noktası henüz değiştirilmedi; sonraki fotoğraf çekilince ikisi birlikte denetlenecek.",
                        scanSummary = ScanSummary(1, 0, 0, 0, 0, batch.firstScan),
                        progress = 1f
                    )
                    return@launch
                }

                val pairs = PairPolicy.adjacent(batch.photos)
                var matched = 0
                var rejected = 0
                var errors = 0
                pairs.forEachIndexed { index, pair ->
                    ensureActive()
                    _state.value = _state.value.copy(
                        stage = "Çift ${index + 1}/${pairs.size} denetleniyor",
                        progress = index.toFloat() / pairs.size
                    )
                    var left: Bitmap? = null
                    var right: Bitmap? = null
                    var result: AnalysisResult? = null
                    try {
                        left = reader.bitmap(pair.left.uri, 2048)
                        right = reader.bitmap(pair.right.uri, 2048)
                        val analyzed = analyzer.analyze(
                            pair,
                            left,
                            right,
                            _state.value.maxSeconds,
                            _state.value.similarity
                        )
                        result = analyzed
                        if (analyzed.saveable && analyzed.sbsPreview != null) {
                            saver.saveAutomatic(
                                analyzed.sbsPreview,
                                pair.left.tie,
                                pair.right.tie
                            )
                            matched++
                        } else {
                            rejected++
                        }
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (_: Throwable) {
                        errors++
                    } finally {
                        recycleUnique(
                            listOfNotNull(
                                left,
                                right,
                                result?.leftPreview,
                                result?.rightPreview,
                                result?.sbsPreview
                            )
                        )
                    }
                    _state.value = _state.value.copy(
                        progress = (index + 1f) / pairs.size
                    )
                }

                check(ScanBoundaryPolicy.shouldCommit(batch.photos.size))
                gallery.commit(batch)
                val completed = gallery.lastScanMillis()
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Tarama tamamlandı",
                    message = if (errors > 0) "$errors çift, okunamadığı için atlandı." else null,
                    progress = 1f,
                    lastScanMillis = completed,
                    scanSummary = ScanSummary(
                        photos = batch.photos.size,
                        pairs = pairs.size,
                        matched = matched,
                        rejected = rejected,
                        errors = errors,
                        firstScan = batch.firstScan
                    )
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Tarama iptal edildi",
                    message = "İlerleme noktası değiştirilmedi; sonraki taramada güvenle yeniden başlanacak."
                )
            } catch (failure: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Galeri taranamadı",
                    message = failure.message ?: "Bilinmeyen hata"
                )
            }
        }
    }

    fun cancel() {
        work?.cancel()
    }

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

    private fun recycleResults(results: List<AnalysisResult>) {
        recycleUnique(
            results.flatMap {
                listOfNotNull(it.leftPreview, it.rightPreview, it.sbsPreview)
            }
        )
    }

    private fun recycleUnique(bitmaps: List<Bitmap>) {
        val seen = IdentityHashMap<Bitmap, Boolean>()
        bitmaps.forEach { bitmap ->
            if (seen.put(bitmap, true) == null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
}
