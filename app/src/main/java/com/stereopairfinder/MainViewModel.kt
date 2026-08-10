package com.stereopairfinder

import android.app.Application
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

data class UiState(val selected: List<Photo> = emptyList(), val results: List<AnalysisResult> = emptyList(),
    val maxSeconds: Int=DEFAULT_MAX_SECONDS, val similarity: Int=DEFAULT_SIMILARITY, val busy:Boolean=false,
    val stage:String="Fotoğraf seçerek başlayın", val message:String?=null, val progress:Float=0f)

class MainViewModel(app: Application):AndroidViewModel(app) {
    private val reader=PhotoReader(app.contentResolver); private val saver=SbsSaver(app.contentResolver)
    private val _state=MutableStateFlow(UiState()); val state=_state.asStateFlow(); private var work:Job?=null
    fun select(uris:List<Uri>) { work?.cancel(); viewModelScope.launch(Dispatchers.IO) {
        val photos=uris.mapNotNull { uri -> runCatching { reader.metadata(uri) }.getOrNull() }
        _state.value=UiState(selected=PairPolicy.sorted(photos),message=if(photos.size<2) "En az iki fotoğraf seçin." else null,
            stage="${photos.size} fotoğraf seçildi${if(photos.mapNotNull{it.takenAtMillis}.groupingBy{it}.eachCount().any{it.value>1}) "; eşit zamanlarda URI sırası kullanılır" else ""}")
    }}
    fun maxSeconds(v:Int){_state.value=_state.value.copy(maxSeconds=v)}; fun similarity(v:Int){_state.value=_state.value.copy(similarity=v)}
    fun analyze(){ if(_state.value.selected.size<2){_state.value=_state.value.copy(message="En az iki fotoğraf seçin.");return}; work?.cancel(); work=viewModelScope.launch(Dispatchers.Default){
        val snapshot=_state.value; val pairs=PairPolicy.adjacent(snapshot.selected); val results=mutableListOf<AnalysisResult>()
        _state.value=snapshot.copy(busy=true,results=emptyList(),message=null,stage="Özellik noktaları inceleniyor",progress=0f)
        try { pairs.forEachIndexed { i,pair -> ensureActive(); _state.value=_state.value.copy(stage="Çift ${i+1}/${pairs.size} hizalanıyor")
            val l=reader.bitmap(pair.left.uri,2048); val r=reader.bitmap(pair.right.uri,2048)
            val result=StereoAnalyzer().analyze(pair,l,r,snapshot.maxSeconds,snapshot.similarity); results+=result
            if(result.leftPreview!==l) l.recycle(); if(result.rightPreview!==r) r.recycle()
            _state.value=_state.value.copy(results=results.toList(),progress=(i+1f)/pairs.size)
        }; _state.value=_state.value.copy(busy=false,stage="Tamamlandı: ${results.count{it.saveable}}/${results.size} çift eşleşti")
        } catch(_:CancellationException){_state.value=_state.value.copy(busy=false,stage="İşlem iptal edildi")}
        catch(t:Throwable){_state.value=_state.value.copy(busy=false,stage="Analiz başarısız",message=t.message ?: "Bilinmeyen hata")}
    }}
    fun cancel(){work?.cancel()}
    fun save(result:AnalysisResult){val bitmap=result.sbsPreview?:return; viewModelScope.launch(Dispatchers.IO){runCatching{saver.save(bitmap)}.onSuccess{_state.value=_state.value.copy(message="${it.name}, ${it.location} konumuna kaydedildi")}.onFailure{_state.value=_state.value.copy(message="Kayıt başarısız: ${it.message}")}}}
}
