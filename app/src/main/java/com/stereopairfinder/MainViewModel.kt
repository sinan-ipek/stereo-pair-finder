package com.stereopairfinder

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stereopairfinder.data.PhotoReader
import com.stereopairfinder.data.SbsSaver
import com.stereopairfinder.image.StereoAnalyzer
import com.stereopairfinder.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiState(
    val selected: List<Photo> = emptyList(),
    val results: List<AnalysisResult> = emptyList(),
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
    val similarity: Int = DEFAULT_SIMILARITY,
    val busy: Boolean = false,
    val stage: String = "Tüm telefonu taramaya hazır",
    val message: String? = null,
    val progress: Float = 0f,
    val photoCount: Int = 0,
    val candidateCount: Int = 0,
    val matchedCount: Int = 0,
    val savedCount: Int = 0,
    val failedCount: Int = 0
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = PhotoReader(app.contentResolver)
    private val saver = SbsSaver(app.contentResolver)
    private val prefs = app.getSharedPreferences("stereo_pair_finder", Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var work: Job? = null

    fun maxSeconds(v: Int) {
        _state.value = _state.value.copy(maxSeconds = v)
    }

    fun similarity(v: Int) {
        _state.value = _state.value.copy(similarity = v)
    }

    fun permissionDenied() {
        _state.value = _state.value.copy(
            message = "Tüm telefonu taramak için fotoğraf erişiminde ‘Tüm fotoğraflara izin ver’ seçilmelidir."
        )
    }

    fun scanAll() {
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            val snapshot = _state.value
            _state.value = snapshot.copy(
                selected = emptyList(),
                results = emptyList(),
                busy = true,
                stage = "Telefonun fotoğraf arşivi okunuyor…",
                message = null,
                progress = 0f,
                photoCount = 0,
                candidateCount = 0,
                matchedCount = 0,
                savedCount = 0,
                failedCount = 0
            )

            try {
                val photos = withContext(Dispatchers.IO) { reader.allPhotos() }
                ensureActive()

                val allAdjacent = PairPolicy.adjacent(photos)
                val candidates = allAdjacent.filter { pair ->
                    val seconds = pair.seconds
                    seconds != null && seconds <= snapshot.maxSeconds
                }

                _state.value = _state.value.copy(
                    photoCount = photos.size,
                    candidateCount = candidates.size,
                    stage = "${photos.size} fotoğraf bulundu · ${candidates.size} olası çift incelenecek"
                )

                if (candidates.isEmpty()) {
                    prefs.edit().putLong(KEY_LAST_FULL_SCAN, System.currentTimeMillis()).apply()
                    _state.value = _state.value.copy(
                        busy = false,
                        progress = 1f,
                        stage = "Tam tarama tamamlandı: uygun zaman aralığında çift bulunamadı"
                    )
                    return@launch
                }

                val analyzer = StereoAnalyzer()
                var matched = 0
                var saved = 0
                var failed = 0

                candidates.forEachIndexed { index, pair ->
                    ensureActive()
                    _state.value = _state.value.copy(
                        stage = "Olası çift ${index + 1}/${candidates.size} inceleniyor · $saved kaydedildi"
                    )

                    var left: Bitmap? = null
                    var right: Bitmap? = null
                    var result: AnalysisResult? = null

                    try {
                        left = reader.bitmap(pair.left.uri, FINAL_SOURCE_MAX_SIDE)
                        right = reader.bitmap(pair.right.uri, FINAL_SOURCE_MAX_SIDE)
                        result = analyzer.analyze(
                            pair,
                            left,
                            right,
                            snapshot.maxSeconds,
                            snapshot.similarity
                        )

                        if (result.status == PairStatus.MATCHED) {
                            matched++
                            val jpeg = result.sbsJpeg
                            if (jpeg != null) {
                                try {
                                    withContext(Dispatchers.IO) { saver.saveJpeg(jpeg) }
                                    saved++
                                } catch (_: Throwable) {
                                    failed++
                                }
                            } else {
                                failed++
                            }
                        }
                    } catch (_: CancellationException) {
                        throw CancellationException()
                    } catch (_: Throwable) {
                        failed++
                    } finally {
                        recycleResult(result)
                        left?.let { if (!it.isRecycled) it.recycle() }
                        right?.let { if (!it.isRecycled) it.recycle() }
                    }

                    _state.value = _state.value.copy(
                        matchedCount = matched,
                        savedCount = saved,
                        failedCount = failed,
                        progress = (index + 1f) / candidates.size
                    )
                }

                prefs.edit().putLong(KEY_LAST_FULL_SCAN, System.currentTimeMillis()).apply()
                _state.value = _state.value.copy(
                    busy = false,
                    progress = 1f,
                    matchedCount = matched,
                    savedCount = saved,
                    failedCount = failed,
                    stage = "Tam tarama tamamlandı: $matched stereo çift bulundu, $saved SBS kaydedildi"
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Tarama iptal edildi"
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Tarama başarısız",
                    message = t.message ?: "Bilinmeyen hata"
                )
            }
        }
    }

    /** Eski seçili-fotoğraf akışı kodda tutuluyor; tam tarama arayüzü bunu kullanmıyor. */
    fun select(uris: List<Uri>) {
        work?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val photos = uris.mapNotNull { uri ->
                runCatching { reader.metadata(uri) }.getOrNull()
            }
            _state.value = _state.value.copy(
                selected = PairPolicy.sorted(photos),
                message = if (photos.size < 2) "En az iki fotoğraf seçin." else null,
                stage = "${photos.size} fotoğraf seçildi"
            )
        }
    }

    fun analyze() {
        if (_state.value.selected.size < 2) {
            _state.value = _state.value.copy(message = "En az iki fotoğraf seçin.")
            return
        }
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            val snapshot = _state.value
            val pairs = PairPolicy.adjacent(snapshot.selected)
                .filter { it.seconds != null && it.seconds!! <= snapshot.maxSeconds }
            val results = mutableListOf<AnalysisResult>()
            val analyzer = StereoAnalyzer()
            _state.value = snapshot.copy(
                busy = true,
                results = emptyList(),
                message = null,
                stage = "Özellik noktaları inceleniyor",
                progress = 0f
            )
            try {
                pairs.forEachIndexed { i, pair ->
                    ensureActive()
                    _state.value = _state.value.copy(stage = "Çift ${i + 1}/${pairs.size} hizalanıyor")
                    val l = reader.bitmap(pair.left.uri, FINAL_SOURCE_MAX_SIDE)
                    val r = reader.bitmap(pair.right.uri, FINAL_SOURCE_MAX_SIDE)
                    val result = try {
                        analyzer.analyze(pair, l, r, snapshot.maxSeconds, snapshot.similarity)
                    } finally {
                        if (!l.isRecycled) l.recycle()
                        if (!r.isRecycled) r.recycle()
                    }
                    results += result
                    _state.value = _state.value.copy(
                        results = results.toList(),
                        progress = if (pairs.isEmpty()) 1f else (i + 1f) / pairs.size
                    )
                }
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Tamamlandı: ${results.count { it.saveable }}/${results.size} çift eşleşti"
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(busy = false, stage = "İşlem iptal edildi")
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Analiz başarısız",
                    message = t.message ?: "Bilinmeyen hata"
                )
            }
        }
    }

    fun cancel() {
        work?.cancel()
    }

    fun save(result: AnalysisResult) {
        val jpeg = result.sbsJpeg ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { saver.saveJpeg(jpeg) }
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

    private fun recycleResult(result: AnalysisResult?) {
        if (result == null) return
        val bitmaps = listOfNotNull(result.leftPreview, result.rightPreview, result.sbsPreview)
        val seen = HashSet<Int>()
        bitmaps.forEach { bitmap ->
            val key = System.identityHashCode(bitmap)
            if (seen.add(key) && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        private const val FINAL_SOURCE_MAX_SIDE = 4096
        private const val KEY_LAST_FULL_SCAN = "last_full_scan_millis"
    }
}
