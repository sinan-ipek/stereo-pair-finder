package com.stereopairfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stereopairfinder.model.AnalysisResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Screen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasFullPhotoPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { uris ->
        vm.select(uris)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasFullPhotoPermission = granted
        if (granted) vm.scanAll() else vm.permissionDenied()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Stereo Pair Finder") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card {
                    Text(
                        "İki çalışma biçimi vardır: İsterseniz bir stereo çifti kendiniz seçin, isterseniz galeriyi otomatik taratın. Kaynak fotoğraflar hiçbir zaman silinmez, taşınmaz veya değiştirilmez.",
                        Modifier.padding(14.dp)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("İki fotoğraf seç")
                    }

                    Button(
                        onClick = {
                            if (hasFullPhotoPermission) {
                                vm.scanAll()
                            } else {
                                permissionLauncher.launch(readPermission)
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Galeriyi tara")
                    }
                }
            }

            item {
                Text("Seçilen fotoğraf: ${state.selected.size}/2")
                if (state.selected.size == 2) {
                    Button(
                        onClick = vm::analyze,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Seçilen çifti incele")
                    }
                }
                if (!hasFullPhotoPermission) {
                    Text(
                        "Galeri taraması için izin sorulduğunda ‘Tüm fotoğraflara izin ver’ seçeneğini seçin. Manuel çift seçimi bu izne ihtiyaç duymaz.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
                if (state.busy) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::cancel) {
                        Text("İptal")
                    }
                }

                Text(state.stage)
                state.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }

            if (state.photoCount > 0) {
                item {
                    Text("Bulunan fotoğraf: ${state.photoCount}")
                    Text("Zaman filtresinden geçen olası çift: ${state.candidateCount}")
                    Text("Stereo eşleşme: ${state.matchedCount}")
                    Text("Kaydedilen SBS: ${state.savedCount}")
                    if (state.failedCount > 0) {
                        Text("Atlanan/hata veren işlem: ${state.failedCount}")
                    }
                }
            }

            if (state.results.isNotEmpty()) {
                items(state.results, key = { it.pair.index }) { result ->
                    ResultCard(result, vm::save)
                }
            }

            item {
                Text(
                    "Hizalama yalnızca x-y kaydırma ile yapılır. Çok küçük bir kamera roll farkı ölçülür ve dikey hizalamayı açıkça iyileştirirse en fazla ±1° döndürme kullanılabilir. Perspective, shear, stretching ve bağımsız scale uygulanmaz.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Galeri taramasında eşleşen dosyalar Pictures/Stereo SBS Test/ klasörüne yüksek çözünürlüklü JPEG olarak otomatik kaydedilir. Bu çıktı klasörü sonraki taramalarda kaynak olarak kullanılmaz.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    result: AnalysisResult,
    onSave: (AnalysisResult) -> Unit
) {
    var showRight by remember { mutableStateOf(false) }
    val formatter = remember {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
    }

    fun formatTime(value: Long?): String =
        value?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Bulunamadı"

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                "Çift ${result.pair.index}: ${result.status.text}",
                style = MaterialTheme.typography.titleMedium
            )

            Row(Modifier.height(110.dp)) {
                result.leftPreview?.let { bitmap ->
                    Image(
                        bitmap.asImageBitmap(),
                        "Sol fotoğraf",
                        Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
                result.rightPreview?.let { bitmap ->
                    Image(
                        bitmap.asImageBitmap(),
                        "Sağ fotoğraf",
                        Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Text("Sol: ${formatTime(result.pair.left.takenAtMillis)}")
            Text("Sağ: ${formatTime(result.pair.right.takenAtMillis)}")
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

            result.sbsPreview?.let { bitmap ->
                Image(
                    bitmap.asImageBitmap(),
                    "Hizalanmış SBS önizleme",
                    Modifier.fillMaxWidth().aspectRatio(2f),
                    contentScale = ContentScale.Fit
                )

                val blinkBitmap = if (showRight) result.rightPreview else result.leftPreview
                if (blinkBitmap != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Blink: ${if (showRight) "sağ" else "sol"}")
                        Switch(showRight, { showRight = it })
                    }
                    Image(
                        blinkBitmap.asImageBitmap(),
                        "Blink hizalama denetimi",
                        Modifier.fillMaxWidth().height(180.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            if (result.saveable) {
                Button(onClick = { onSave(result) }) {
                    Text("Bu sonucu kaydet")
                }
            }
        }
    }
}
