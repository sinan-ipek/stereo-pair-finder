package com.stereopairfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stereopairfinder.model.AnalysisResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{Screen()}}}}

@Composable fun Screen(vm:MainViewModel= viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle(); val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)){vm.select(it)}
    Scaffold(topBar={TopAppBar(title={Text("Stereo Pair Finder")})}){pad->LazyColumn(Modifier.padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Text("Deneme modu: Kaynak fotoğraflar yalnızca okunur; silinmez, değiştirilmez ve taşınmaz.",Modifier.padding(14.dp))} }
        item { Button(onClick={picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!s.busy){Text("Fotoğrafları seç")}; Text("Seçilen fotoğraf: ${s.selected.size}") }
        item { Text("Azami zaman farkı: ${s.maxSeconds} saniye"); Slider(s.maxSeconds.toFloat(),{vm.maxSeconds(it.toInt())},valueRange=1f..60f,steps=58,enabled=!s.busy)
            Text("Benzerlik eşiği: %${s.similarity}"); Slider(s.similarity.toFloat(),{vm.similarity(it.toInt())},valueRange=1f..100f,steps=98,enabled=!s.busy) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=vm::analyze,enabled=!s.busy&&s.selected.size>=2){Text("Fotoğrafları incele")};if(s.busy)OutlinedButton(onClick=vm::cancel){Text("İptal")}}
            if(s.busy) LinearProgressIndicator(progress={s.progress},Modifier.fillMaxWidth()); Text(s.stage); s.message?.let{Text(it,color=MaterialTheme.colorScheme.error)} }
        if(s.results.isEmpty()) item{Text("Henüz çift sonucu yok.")} else items(s.results,key={it.pair.index}){ResultCard(it,vm::save)}
    }}
}

@Composable private fun ResultCard(r:AnalysisResult,onSave:(AnalysisResult)->Unit){var overlay by remember{mutableStateOf(false)}; val fmt=remember{DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS").withZone(ZoneId.systemDefault())}
    fun time(v:Long?)=v?.let{fmt.format(Instant.ofEpochMilli(it))}?:"Bulunamadı"
    Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("Çift ${r.pair.index}: ${r.status.text}",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.height(110.dp)){r.leftPreview?.let{Image(it.asImageBitmap(),"Önce çekilen sol fotoğraf",Modifier.weight(1f).fillMaxHeight(),contentScale=ContentScale.Crop)};r.rightPreview?.let{Image(it.asImageBitmap(),"Sonra çekilen sağ fotoğraf",Modifier.weight(1f).fillMaxHeight(),contentScale=ContentScale.Crop)}}
        Text("Sol (önce): ${time(r.pair.left.takenAtMillis)}");Text("Sağ (sonra): ${time(r.pair.right.takenAtMillis)}")
        Text("Zaman farkı: ${r.pair.seconds?.let{"%.3f sn".format(it)}?:"—"}")
        Text("Benzerlik: %.1f%% · Güvenilir eşleşme: %d".format(r.similarity,r.reliableMatches))
        Text("Hizalama güveni: %.1f%% · Medyan düşey hata: %.2f px".format(r.alignmentConfidence,r.medianVerticalError))
        Text("Ortak geçerli alan: %.1f%%".format(r.commonAreaRatio*100))
        r.sbsPreview?.let{bmp->Image(bmp.asImageBitmap(),"Hizalanmış SBS önizleme",Modifier.fillMaxWidth().aspectRatio(2f),contentScale=ContentScale.Fit)
            Row{Text("Blink: ${if(overlay)"sağ" else "sol"}");Switch(overlay,{overlay=it})};Image((if(overlay)r.rightPreview else r.leftPreview)!!.asImageBitmap(),"Blink hizalama denetimi",Modifier.fillMaxWidth().height(180.dp),contentScale=ContentScale.Fit)}
        if(r.saveable)Button(onClick={onSave(r)}){Text("Bu sonucu kaydet")}
    }}
}
