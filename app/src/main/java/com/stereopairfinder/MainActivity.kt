package com.stereopairfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { vm.select(it) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Stereo Pair Finder · ${BuildConfig.VERSION_NAME}") })
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "Deneme modu: Kaynak fotoğraflar yalnızca okunur; silinmez, " +
                            "değiştirilmez ve taşınmaz.",
                        Modifier.padding(14.dp)
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        picker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    enabled = !state.busy
                ) {
                    Text("Fotoğrafları seç")
                }
                Text("Seçilen fotoğraf: ${state.selected.size}")
            }
            item {
                Text("Azami zaman farkı: ${state.maxSeconds} saniye")
                Slider(
                    value = state.maxSeconds.toFloat(),
                    onValueChange = { vm.maxSeconds(it.toInt()) },
                    valueRange = 1f..60f,
                    steps = 58,
                    enabled = !state.busy
                )
                Text("Benzerlik eşiği: %${state.similarity}")
                Slider(
                    value = state.similarity.toFloat(),
                    onValueChange = { vm.similarity(it.toInt()) },
                    valueRange = 1f..100f,
                    steps = 98,
                    enabled = !state.busy
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::analyze,
                        enabled = !state.busy && state.selected.size >= 2
                    ) {
                        Text("Fotoğrafları incele")
                    }
                    if (state.busy) {
                        OutlinedButton(onClick = vm::cancel) { Text("İptal") }
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(state.stage)
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (state.results.isEmpty()) {
                item { Text("Henüz çift sonucu yok.") }
            } else {
                items(state.results, key = { it.pair.index }) { result ->
                    ResultCard(result, vm::save)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: AnalysisResult, onSave: (AnalysisResult) -> Unit) {
    var overlay by remember { mutableStateOf(false) }
    val formatter = remember {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
    }
    fun time(value: Long?) = value?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Bulunamadı"

    Card {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                "Çift ${result.pair.index}: ${result.status.text}",
                style = MaterialTheme.typography.titleMedium
            )
            Row(Modifier.height(110.dp)) {
                result.leftPreview?.let {
                    Image(
                        it.asImageBitmap(),
                        "Önce çekilen sol fotoğraf",
                        Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
                result.rightPreview?.let {
                    Image(
                        it.asImageBitmap(),
                        "Sonra çekilen sağ fotoğraf",
                        Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Text("Sol (önce): ${time(result.pair.left.takenAtMillis)}")
            Text("Sağ (sonra): ${time(result.pair.right.takenAtMillis)}")
            Text("Zaman farkı: ${result.pair.seconds?.let { "%.3f sn".format(it) } ?: "—"}")
            Text(
                "Benzerlik: %.1f%% · Güvenilir eşleşme: %d".format(
                    result.similarity,
                    result.reliableMatches
                )
            )
            Text(
                "Hizalama güveni: %.1f%% · Medyan düşey hata: %.2f px".format(
                    result.alignmentConfidence,
                    result.medianVerticalError
                )
            )
            Text("Ortak geçerli alan: %.1f%%".format(result.commonAreaRatio * 100))
            Text("Algoritma: ${result.algorithmVersion}")
            Text(
                "Kadraj: ${result.framingMode} · Konu güveni %.0f%% (%d örnek)".format(
                    result.subjectConfidence * 100.0,
                    result.subjectEvidenceCount
                )
            )
            Text("Yoğun paralaks örneği: ${result.parallaxEvidenceCount}")
            if (
                result.cropCenterXPercent != null &&
                result.cropCenterYPercent != null &&
                result.cropSidePercent != null
            ) {
                Text(
                    "Kırpma merkezi: X %.1f%% · Y %.1f%% · Kare kenarı %.1f%%".format(
                        result.cropCenterXPercent,
                        result.cropCenterYPercent,
                        result.cropSidePercent
                    )
                )
            }
            result.sbsPreview?.let { bitmap ->
                Image(
                    bitmap.asImageBitmap(),
                    "Hizalanmış SBS önizleme",
                    Modifier.fillMaxWidth().aspectRatio(2f),
                    contentScale = ContentScale.Fit
                )
                Row {
                    Text("Blink: ${if (overlay) "sağ" else "sol"}")
                    Switch(checked = overlay, onCheckedChange = { overlay = it })
                }
                val blink = if (overlay) result.rightPreview else result.leftPreview
                blink?.let {
                    Image(
                        it.asImageBitmap(),
                        "Blink hizalama denetimi",
                        Modifier.fillMaxWidth().height(180.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            if (result.saveable) {
                Button(onClick = { onSave(result) }) { Text("Bu sonucu kaydet") }
            }
        }
    }
}
