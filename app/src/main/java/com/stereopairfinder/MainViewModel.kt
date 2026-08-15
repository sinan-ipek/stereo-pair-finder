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
    val stage: String = "Hazır",
    val message: String? = null,
    val progress: Float = 0f,
    val photoCount: Int = 0,
    val candidateCount: Int = 0,
    val matchedCount: Int = 0,
    val savedCount: Int = 0,
    val failedCount: Int = 0,
    val resumeAvailable: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = PhotoReader(app.contentResolver)
    private val saver = SbsSaver(app.contentResolver)
    private val prefs = app.getSharedPreferences("stereo_pair_finder", Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var work: Job? = null

    init {
        // 1.6 checkpoint'i varsa doğrudan devam edilir. 1.5 yalnızca tam tarama
        // zamanını tutuyordu; o kayıt varsa ilk ▶ Devam sırasında son MediaStore
        // fotoğrafından yeni checkpoint üretilebilir.
        _state.value = _state.value.copy(
            resumeAvailable = loadCheckpoint() != null || prefs.contains(KEY_LAST_FULL_SCAN)
        )
    }

    fun clearMessage() {
        if (_state.value.message != null) _state.value = _state.value.copy(message = null)
    }

    fun permissionDenied() {
        _state.value = _state.value.copy(
            message = "Tüm telefonu taramak için fotoğraf erişiminde ‘Tüm fotoğraflara izin ver’ seçilmelidir."
        )
    }

    fun scanAll(settings: ScanSettings) {
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            val normalized = settings.copy(render = settings.render.normalized())

            if (normalized.startMode == ScanStartMode.FROM_START) {
                clearCheckpoint()
            }
            val requestedCheckpoint = if (normalized.startMode == ScanStartMode.RESUME) {
                loadCheckpoint()
            } else {
                null
            }
            val migrateLegacyCompletedScan =
                normalized.startMode == ScanStartMode.RESUME &&
                    requestedCheckpoint == null &&
                    prefs.contains(KEY_LAST_FULL_SCAN)

            _state.value = _state.value.copy(
                selected = emptyList(),
                results = emptyList(),
                maxSeconds = normalized.maxSeconds,
                similarity = normalized.similarity,
                busy = true,
                stage = if (normalized.startMode == ScanStartMode.RESUME) {
                    "Kaldığınız yer bulunuyor…"
                } else {
                    "Telefonun fotoğraf arşivi en baştan okunuyor…"
                },
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
                val orderedPhotos = PairPolicy.sorted(photos)

                // 1.5'ten yükseltme: 1.5'in KEY_LAST_FULL_SCAN kaydı yalnızca
                // tam tarama bitince yazılıyordu. Dolayısıyla son stabil fotoğrafı
                // güvenle tamamlanmış galerinin sınırı kabul edebiliriz.
                val effectiveCheckpoint = when {
                    requestedCheckpoint != null -> requestedCheckpoint
                    migrateLegacyCompletedScan -> {
                        val lastStablePhoto = orderedPhotos.lastOrNull {
                            PairPolicy.checkpointOf(it) != null
                        }
                        lastStablePhoto?.let(::saveCheckpoint)
                        lastStablePhoto?.let(PairPolicy::checkpointOf)
                    }
                    else -> null
                }

                val scanPhotos = if (
                    normalized.startMode == ScanStartMode.RESUME &&
                    effectiveCheckpoint != null
                ) {
                    PairPolicy.fromCheckpoint(orderedPhotos, effectiveCheckpoint)
                } else {
                    orderedPhotos
                }

                val candidates = PairPolicy.adjacent(scanPhotos).filter { pair ->
                    val seconds = pair.seconds
                    seconds != null && seconds <= normalized.maxSeconds
                }

                val modeText = if (
                    normalized.startMode == ScanStartMode.RESUME &&
                    effectiveCheckpoint != null
                ) {
                    "Devam"
                } else {
                    "Baştan"
                }

                _state.value = _state.value.copy(
                    photoCount = photos.size,
                    candidateCount = candidates.size,
                    stage = "$modeText taraması · ${photos.size} fotoğraf · ${candidates.size} olası çift"
                )

                if (candidates.isEmpty()) {
                    scanPhotos.lastOrNull()?.let(::saveCheckpoint)
                    prefs.edit().putLong(KEY_LAST_FULL_SCAN, System.currentTimeMillis()).apply()
                    _state.value = _state.value.copy(
                        busy = false,
                        progress = 1f,
                        stage = if (modeText == "Devam") {
                            "Kaldığınız yerden sonra incelenecek yeni stereo adayı yok"
                        } else {
                            "Tam tarama tamamlandı: uygun zaman aralığında çift bulunamadı"
                        }
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
                        stage = "$modeText · olası çift ${index + 1}/${candidates.size} inceleniyor · $saved kaydedildi"
                    )

                    var left: Bitmap? = null
                    var right: Bitmap? = null
                    var result: AnalysisResult? = null

                    try {
                        left = reader.bitmap(pair.left.uri, FINAL_SOURCE_MAX_SIDE)
                        right = reader.bitmap(pair.right.uri, FINAL_SOURCE_MAX_SIDE)
                        result = analyzer.analyze(
                            pair = pair,
                            leftBitmap = left,
                            rightBitmap = right,
                            maxSeconds = normalized.maxSeconds,
                            threshold = normalized.similarity,
                            enforceSelectionFilters = true,
                            renderSettings = normalized.render,
                            produceJpeg = true
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

                    // Bu çift tamamen işlendi. Uygulama bundan sonra kapanırsa
                    // Devam seçeneği sağ fotoğraftan sonraki komşuyla devam eder.
                    saveCheckpoint(pair.right)

                    _state.value = _state.value.copy(
                        matchedCount = matched,
                        savedCount = saved,
                        failedCount = failed,
                        progress = (index + 1f) / candidates.size
                    )
                }

                // Zaman filtresine girmemiş son fotoğrafları da checkpoint'e taşı.
                // Sonraki yeni fotoğrafla sınır çifti yine korunur; çünkü resume
                // listesi checkpoint fotoğrafının kendisini de içerir.
                scanPhotos.lastOrNull()?.let(::saveCheckpoint)
                prefs.edit().putLong(KEY_LAST_FULL_SCAN, System.currentTimeMillis()).apply()
                _state.value = _state.value.copy(
                    busy = false,
                    progress = 1f,
                    matchedCount = matched,
                    savedCount = saved,
                    failedCount = failed,
                    stage = "$modeText taraması tamamlandı: $matched stereo çift bulundu, $saved SBS kaydedildi"
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Tarama iptal edildi · ▶ Devam ile son işlenen noktadan sürdürebilirsiniz"
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

    fun select(uris: List<Uri>) {
        work?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val photos = uris.mapNotNull { uri -> runCatching { reader.metadata(uri) }.getOrNull() }
            recycleResults(_state.value.results)
            _state.value = _state.value.copy(
                selected = photos,
                results = emptyList(),
                message = if (photos.size < 2) "İki fotoğraf seçin." else null,
                stage = if (photos.size == 2) "2 fotoğraf seçildi" else "${photos.size} fotoğraf seçildi"
            )
        }
    }

    fun analyze() {
        if (_state.value.selected.size != 2) {
            _state.value = _state.value.copy(message = "Tam olarak iki fotoğraf seçin.")
            return
        }
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            val snapshot = _state.value
            val pair = PairCandidate(1, snapshot.selected[0], snapshot.selected[1])
            val analyzer = StereoAnalyzer()
            recycleResults(snapshot.results)
            _state.value = snapshot.copy(
                busy = true,
                results = emptyList(),
                message = null,
                stage = "Seçilen stereo çift hizalanıyor",
                progress = 0f
            )

            try {
                val left = reader.bitmap(pair.left.uri, FINAL_SOURCE_MAX_SIDE)
                val right = reader.bitmap(pair.right.uri, FINAL_SOURCE_MAX_SIDE)
                val result = try {
                    analyzer.analyze(
                        pair = pair,
                        leftBitmap = left,
                        rightBitmap = right,
                        maxSeconds = snapshot.maxSeconds,
                        threshold = snapshot.similarity,
                        enforceSelectionFilters = false,
                        renderSettings = RenderSettings(CropMode.FIT, 0f),
                        produceJpeg = false
                    )
                } finally {
                    if (!left.isRecycled) left.recycle()
                    if (!right.isRecycled) right.recycle()
                }

                _state.value = _state.value.copy(
                    busy = false,
                    results = listOf(result),
                    progress = 1f,
                    stage = if (result.saveable) {
                        "Manuel çift hizalandı · kadrajı ve sağ/sol sırasını ayarlayabilirsiniz"
                    } else {
                        "Manuel çift güvenilir biçimde hizalanamadı: ${result.status.text}"
                    }
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

    fun save(result: AnalysisResult, settings: RenderSettings) {
        if (result.status != PairStatus.MATCHED || _state.value.busy) return
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.Default) {
            _state.value = _state.value.copy(
                busy = true,
                message = null,
                stage = "Seçtiğiniz kadraj yüksek çözünürlükte hazırlanıyor…"
            )

            var left: Bitmap? = null
            var right: Bitmap? = null
            var rendered: AnalysisResult? = null
            try {
                left = reader.bitmap(result.pair.left.uri, FINAL_SOURCE_MAX_SIDE)
                right = reader.bitmap(result.pair.right.uri, FINAL_SOURCE_MAX_SIDE)
                rendered = StereoAnalyzer().analyze(
                    pair = result.pair,
                    leftBitmap = left,
                    rightBitmap = right,
                    maxSeconds = _state.value.maxSeconds,
                    threshold = _state.value.similarity,
                    enforceSelectionFilters = false,
                    renderSettings = settings.normalized(),
                    produceJpeg = true
                )
                val jpeg = rendered.sbsJpeg ?: error("Kaydedilebilir SBS üretilemedi")
                withContext(Dispatchers.IO) { saver.saveJpeg(jpeg) }
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Hazır",
                    message = "Fotoğraf Stereo SBS albümüne kaydedildi."
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(busy = false, stage = "Kayıt iptal edildi")
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    stage = "Kayıt başarısız",
                    message = "Kayıt başarısız: ${t.message ?: "Bilinmeyen hata"}"
                )
            } finally {
                recycleResult(rendered)
                left?.let { if (!it.isRecycled) it.recycle() }
                right?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    fun cancel() {
        work?.cancel()
    }

    private fun loadCheckpoint(): ScanCheckpoint? {
        if (!prefs.contains(KEY_CHECKPOINT_TAKEN) || !prefs.contains(KEY_CHECKPOINT_ID)) return null
        val taken = prefs.getLong(KEY_CHECKPOINT_TAKEN, Long.MIN_VALUE)
        val id = prefs.getLong(KEY_CHECKPOINT_ID, Long.MIN_VALUE)
        if (taken == Long.MIN_VALUE || id == Long.MIN_VALUE) return null
        return ScanCheckpoint(takenAtMillis = taken, mediaStoreId = id)
    }

    private fun saveCheckpoint(photo: Photo) {
        val checkpoint = PairPolicy.checkpointOf(photo) ?: return
        prefs.edit()
            .putLong(KEY_CHECKPOINT_TAKEN, checkpoint.takenAtMillis)
            .putLong(KEY_CHECKPOINT_ID, checkpoint.mediaStoreId)
            .apply()
        if (!_state.value.resumeAvailable) {
            _state.value = _state.value.copy(resumeAvailable = true)
        }
    }

    private fun clearCheckpoint() {
        prefs.edit()
            .remove(KEY_CHECKPOINT_TAKEN)
            .remove(KEY_CHECKPOINT_ID)
            .remove(KEY_LAST_FULL_SCAN)
            .apply()
        _state.value = _state.value.copy(resumeAvailable = false)
    }

    private fun recycleResults(results: List<AnalysisResult>) = results.forEach(::recycleResult)

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
        private const val KEY_CHECKPOINT_TAKEN = "scan_checkpoint_taken_millis"
        private const val KEY_CHECKPOINT_ID = "scan_checkpoint_media_store_id"
    }
}
