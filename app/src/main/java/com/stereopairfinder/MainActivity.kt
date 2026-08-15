package com.stereopairfinder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stereopairfinder.model.*
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
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
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
    var showScanSettings by remember { mutableStateOf(false) }
    var pendingScanSettings by remember { mutableStateOf(ScanSettings()) }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            vm.clearMessage()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { uris -> vm.select(uris) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasFullPhotoPermission = granted
        if (granted) vm.scanAll(pendingScanSettings) else vm.permissionDenied()
    }

    if (showScanSettings) {
        ScanSettingsDialog(
            initial = pendingScanSettings,
            resumeAvailable = state.resumeAvailable,
            onDismiss = { showScanSettings = false },
            onStart = { settings ->
                pendingScanSettings = settings
                showScanSettings = false
                if (hasFullPhotoPermission) {
                    vm.scanAll(settings)
                } else {
                    permissionLauncher.launch(readPermission)
                }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Stereo Pair Finder") }) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                            pendingScanSettings = pendingScanSettings.copy(
                                startMode = if (state.resumeAvailable) {
                                    ScanStartMode.RESUME
                                } else {
                                    ScanStartMode.FROM_START
                                }
                            )
                            showScanSettings = true
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
            }

            item {
                if (state.busy) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::cancel) { Text("İptal") }
                }
                Text(state.stage)
            }

            if (state.photoCount > 0) {
                item {
                    Text("Bulunan fotoğraf: ${state.photoCount}")
                    Text("Olası çift: ${state.candidateCount}")
                    Text("Stereo eşleşme: ${state.matchedCount}")
                    Text("Kaydedilen SBS: ${state.savedCount}")
                    if (state.failedCount > 0) Text("Atlanan/hata veren: ${state.failedCount}")
                }
            }

            if (state.results.isNotEmpty()) {
                items(state.results, key = { it.pair.index }) { result ->
                    ResultCard(
                        result = result,
                        busy = state.busy,
                        onSave = vm::save
                    )
                }
            }

            item {
                Text(
                    "Hizalama yalnızca translation ile yapılır; yalnızca belirgin fayda sağlarsa en fazla ±1° küçük rotation kullanılır. Perspective, shear ve stretching uygulanmaz.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ScanSettingsDialog(
    initial: ScanSettings,
    resumeAvailable: Boolean,
    onDismiss: () -> Unit,
    onStart: (ScanSettings) -> Unit
) {
    var cropMode by remember { mutableStateOf(initial.render.cropMode) }
    var verticalBias by remember { mutableFloatStateOf(initial.render.verticalBias) }
    var maxSeconds by remember { mutableIntStateOf(initial.maxSeconds) }
    var similarity by remember { mutableIntStateOf(initial.similarity) }
    var startMode by remember {
        mutableStateOf(
            if (initial.startMode == ScanStartMode.RESUME && resumeAvailable) {
                ScanStartMode.RESUME
            } else {
                ScanStartMode.FROM_START
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tarama ayarları") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Kadraj")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceButton("Fit", cropMode == CropMode.FIT) { cropMode = CropMode.FIT }
                    ChoiceButton("4:3", cropMode == CropMode.FOUR_THREE) { cropMode = CropMode.FOUR_THREE }
                    ChoiceButton("Fill", cropMode == CropMode.FILL) { cropMode = CropMode.FILL }
                }
                Text(
                    when (cropMode) {
                        CropMode.FIT -> "Fit: ortak alanı mümkün olduğunca korur."
                        CropMode.FOUR_THREE -> "4:3: yatay fotoğrafı 4:3, dikey fotoğrafı otomatik 3:4 yapar."
                        CropMode.FILL -> "Fill: kareyi doldurur; gerekirse daha fazla kırpar."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                Text("Dikey kadraj")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceButton("Yukarı", verticalBias == -0.5f) { verticalBias = -0.5f }
                    ChoiceButton("Ortala", verticalBias == 0f) { verticalBias = 0f }
                    ChoiceButton("Aşağı", verticalBias == 0.5f) { verticalBias = 0.5f }
                }

                HorizontalDivider()
                Text("Tarama")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(
                        label = "↺ Baştan",
                        selected = startMode == ScanStartMode.FROM_START
                    ) { startMode = ScanStartMode.FROM_START }
                    ChoiceButton(
                        label = "▶ Devam",
                        selected = startMode == ScanStartMode.RESUME,
                        enabled = resumeAvailable
                    ) { startMode = ScanStartMode.RESUME }
                }
                Text(
                    when {
                        !resumeAvailable -> "Henüz kayıtlı tarama noktası yok; ilk tarama Baştan yapılacak."
                        startMode == ScanStartMode.RESUME -> "Son işlenen MediaStore fotoğrafından sonraki çiftlerle devam eder."
                        else -> "Kayıtlı tarama noktası sıfırlanır ve tüm galeri yeniden taranır."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                Text("Azami zaman farkı: $maxSeconds saniye")
                Slider(
                    value = maxSeconds.toFloat(),
                    onValueChange = { maxSeconds = it.toInt() },
                    valueRange = 1f..60f,
                    steps = 58
                )

                Text("Benzerlik eşiği: %$similarity")
                Slider(
                    value = similarity.toFloat(),
                    onValueChange = { similarity = it.toInt() },
                    valueRange = 1f..100f,
                    steps = 98
                )

                Text(
                    "Tarama başladıktan sonra tüm eşleşmeler bu ayarlarla otomatik hazırlanıp kaydedilir.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStart(
                        ScanSettings(
                            render = RenderSettings(cropMode, verticalBias),
                            maxSeconds = maxSeconds,
                            similarity = similarity,
                            startMode = startMode
                        )
                    )
                }
            ) { Text("Taramayı başlat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("İptal") } }
    )
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

@Composable
private fun ResultCard(
    result: AnalysisResult,
    busy: Boolean,
    onSave: (AnalysisResult, RenderSettings) -> Unit
) {
    var showRight by remember(result.pair.index) { mutableStateOf(false) }
    var cropMode by remember(result.pair.index) { mutableStateOf(CropMode.FIT) }
    var verticalBias by remember(result.pair.index) { mutableFloatStateOf(0f) }
    var swapEyes by remember(result.pair.index) { mutableStateOf(false) }
    val formatter = remember {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
    }

    fun formatTime(value: Long?): String =
        value?.let { formatter.format(Instant.ofEpochMilli(it)) } ?: "Bulunamadı"

    val displayedLeftPhoto = if (swapEyes) result.pair.right else result.pair.left
    val displayedRightPhoto = if (swapEyes) result.pair.left else result.pair.right

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Çift ${result.pair.index}: ${result.status.text}",
                style = MaterialTheme.typography.titleMedium
            )
            Text("Sol: ${formatTime(displayedLeftPhoto.takenAtMillis)}")
            Text("Sağ: ${formatTime(displayedRightPhoto.takenAtMillis)}")
            Text(
                "Benzerlik %.1f%% · %d güvenilir eşleşme · düşey hata %.2f px".format(
                    result.similarity,
                    result.reliableMatches,
                    result.medianVerticalError
                ),
                style = MaterialTheme.typography.bodySmall
            )

            if (result.leftPreview != null && result.rightPreview != null) {
                Text("Kadraj")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoiceButton("Fit", cropMode == CropMode.FIT) { cropMode = CropMode.FIT }
                    ChoiceButton("4:3", cropMode == CropMode.FOUR_THREE) { cropMode = CropMode.FOUR_THREE }
                    ChoiceButton("Fill", cropMode == CropMode.FILL) { cropMode = CropMode.FILL }
                    OutlinedButton(onClick = { verticalBias = 0f }) { Text("Sıfırla") }
                }

                val displayedLeftPreview = if (swapEyes) result.rightPreview else result.leftPreview
                val displayedRightPreview = if (swapEyes) result.leftPreview else result.rightPreview

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(cropMode) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    verticalBias = (verticalBias - dragAmount / 220f).coerceIn(-1f, 1f)
                                }
                            }
                    ) {
                        EyePreview(
                            bitmap = displayedLeftPreview,
                            cropMode = cropMode,
                            verticalBias = verticalBias,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                        EyePreview(
                            bitmap = displayedRightPreview,
                            cropMode = cropMode,
                            verticalBias = verticalBias,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }

                    if (swapEyes) {
                        Button(
                            onClick = { swapEyes = false },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("⇄")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { swapEyes = true },
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(48.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("⇄")
                        }
                    }
                }

                Text(
                    when (cropMode) {
                        CropMode.FIT -> "Fit: mümkün olan ortak alanı korur. Parmağınızla iki gözü birlikte yukarı/aşağı taşıyabilirsiniz."
                        CropMode.FOUR_THREE -> "4:3: yataysa 4:3, dikeyse 3:4 kadraj. Parmağınızla üst-alt kompozisyonu seçebilirsiniz."
                        CropMode.FILL -> "Fill: kareyi doldurur. Parmağınızla hangi üst-alt bölgenin kalacağını seçebilirsiniz."
                    },
                    style = MaterialTheme.typography.bodySmall
                )

                val blinkBitmap = if (showRight) displayedRightPreview else displayedLeftPreview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Blink: ${if (showRight) "sağ" else "sol"}")
                    Switch(showRight, { showRight = it })
                }
                Image(
                    bitmap = blinkBitmap.asImageBitmap(),
                    contentDescription = "Blink hizalama denetimi",
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentScale = ContentScale.Fit
                )
            }

            if (result.saveable) {
                Button(
                    onClick = {
                        onSave(result, RenderSettings(cropMode, verticalBias, swapEyes))
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Bu sonucu kaydet")
                }
            }
        }
    }
}

@Composable
private fun EyePreview(
    bitmap: Bitmap,
    cropMode: CropMode,
    verticalBias: Float,
    modifier: Modifier = Modifier
) {
    val alignment = BiasAlignment(0f, verticalBias.coerceIn(-1f, 1f))
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (cropMode) {
            CropMode.FIT -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alignment = alignment
                )
            }

            CropMode.FOUR_THREE -> {
                val portrait = bitmap.height > bitmap.width
                val frame = if (portrait) {
                    Modifier.fillMaxHeight().aspectRatio(3f / 4f)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = frame,
                    contentScale = ContentScale.Crop,
                    alignment = alignment
                )
            }

            CropMode.FILL -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = alignment
                )
            }
        }
    }
}
