package com.stereopairfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stereopairfinder.model.AnalysisResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF12233F)
private val Ocean = Color(0xFF0B5F85)
private val Aqua = Color(0xFF18B7B2)
private val Sky = Color(0xFF66D8D1)
private val Cloud = Color(0xFFF3F7FA)
private val Muted = Color(0xFF607087)
private val Success = Color(0xFF118566)
private val Warning = Color(0xFFF0A13A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StereoPairTheme {
                StereoPairApp()
            }
        }
    }
}

@Composable
private fun StereoPairTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        primary = Ocean,
        onPrimary = Color.White,
        secondary = Aqua,
        onSecondary = Ink,
        background = Cloud,
        onBackground = Ink,
        surface = Color.White,
        onSurface = Ink,
        surfaceVariant = Color(0xFFE7F0F4),
        onSurfaceVariant = Muted,
        error = Color(0xFFB3261E)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun StereoPairApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.scanGallery() else vm.permissionDenied()
    }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { vm.select(it) }

    fun startGalleryScan() {
        if (
            ContextCompat.checkSelfPermission(context, galleryPermission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.scanGallery()
        } else {
            permissionLauncher.launch(galleryPermission)
        }
    }

    Scaffold(containerColor = Cloud) { padding ->
        when (state.page) {
            AppPage.HOME -> HomePage(
                state = state,
                onGalleryScan = ::startGalleryScan,
                onManual = vm::openManual,
                onCancel = vm::cancel,
                modifier = Modifier.padding(padding)
            )
            AppPage.MANUAL -> ManualPage(
                state = state,
                onBack = vm::goHome,
                onPick = {
                    picker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                onAnalyze = vm::analyzeManual,
                onCancel = vm::cancel,
                onMaxSeconds = vm::maxSeconds,
                onSimilarity = vm::similarity,
                onSave = vm::save,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun HomePage(
    state: UiState,
    onGalleryScan: () -> Unit,
    onManual: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HeroHeader() }
        item {
            Column(
                Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Ne yapmak istersiniz?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                ActionCard(
                    title = "Galeriyi Tara",
                    description = "Yeni fotoğrafları bulur, ardışık çiftleri otomatik hazırlar.",
                    kind = ActionKind.SCAN,
                    enabled = !state.busy,
                    onClick = onGalleryScan
                )
                ActionCard(
                    title = "İki Fotoğraf Seç",
                    description = "Belirlediğiniz bir çifti ayrıntılı olarak inceleyin.",
                    kind = ActionKind.PICK,
                    enabled = !state.busy,
                    onClick = onManual
                )
            }
        }
        if (state.busy || state.stage != "Hazır") {
            item {
                StatusCard(
                    state = state,
                    onCancel = onCancel,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
        state.scanSummary?.let { summary ->
            item {
                ScanSummaryCard(
                    summary = summary,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
        }
        state.message?.let { message ->
            item {
                MessageCard(message, Modifier.padding(horizontal = 20.dp))
            }
        }
        item {
            LastScanLine(
                state.lastScanMillis,
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun HeroHeader() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(
                androidx.compose.foundation.shape.RoundedCornerShape(
                    bottomStart = 34.dp,
                    bottomEnd = 34.dp
                )
            )
            .background(
                Brush.linearGradient(
                    colors = listOf(Ink, Ocean),
                    start = Offset.Zero,
                    end = Offset(900f, 700f)
                )
            )
            .padding(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.11f),
                modifier = Modifier.size(82.dp)
            ) {
                StereoMark(Modifier.padding(13.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    "Stereo Pair Finder",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Fotoğraflarınızdan gerçek 3D çiftler",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 14.sp
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    color = Sky,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private enum class ActionKind { SCAN, PICK }

@Composable
private fun ActionCard(
    title: String,
    description: String,
    kind: ActionKind,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color.White else Color(0xFFE9EEF1)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .background(
                        if (kind == ActionKind.SCAN) {
                            Brush.linearGradient(listOf(Aqua, Ocean))
                        } else {
                            Brush.linearGradient(listOf(Color(0xFF6187D7), Ink))
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                ActionGlyph(kind, Modifier.size(34.dp))
            }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Muted
                )
            }
            Text("›", color = Aqua, fontSize = 32.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
private fun StereoMark(modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        val stroke = size.minDimension * 0.07f
        val radius = size.minDimension * 0.13f
        val frameSize = Size(size.width * 0.55f, size.height * 0.54f)
        drawRoundRect(
            color = Sky,
            topLeft = Offset(size.width * 0.08f, size.height * 0.18f),
            size = frameSize,
            cornerRadius = CornerRadius(radius),
            style = Stroke(stroke)
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(size.width * 0.37f, size.height * 0.28f),
            size = frameSize,
            cornerRadius = CornerRadius(radius),
            style = Stroke(stroke)
        )
        drawCircle(Aqua, size.minDimension * 0.07f, Offset(size.width * 0.69f, size.height * 0.43f))
        val lineStroke = stroke * 0.8f
        drawLine(
            Color.White,
            Offset(size.width * 0.43f, size.height * 0.69f),
            Offset(size.width * 0.58f, size.height * 0.53f),
            lineStroke,
            StrokeCap.Round
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.58f, size.height * 0.53f),
            Offset(size.width * 0.83f, size.height * 0.72f),
            lineStroke,
            StrokeCap.Round
        )
    }
}

@Composable
private fun ActionGlyph(kind: ActionKind, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val white = Color.White
        val stroke = size.minDimension * 0.075f
        if (kind == ActionKind.SCAN) {
            drawCircle(
                color = white,
                radius = size.minDimension * 0.29f,
                center = Offset(size.width * 0.46f, size.height * 0.45f),
                style = Stroke(stroke)
            )
            drawLine(
                white,
                Offset(size.width * 0.67f, size.height * 0.66f),
                Offset(size.width * 0.87f, size.height * 0.86f),
                stroke,
                StrokeCap.Round
            )
            drawLine(
                white,
                Offset(size.width * 0.30f, size.height * 0.45f),
                Offset(size.width * 0.42f, size.height * 0.57f),
                stroke * 0.7f,
                StrokeCap.Round
            )
            drawLine(
                white,
                Offset(size.width * 0.42f, size.height * 0.57f),
                Offset(size.width * 0.61f, size.height * 0.35f),
                stroke * 0.7f,
                StrokeCap.Round
            )
        } else {
            drawRoundRect(
                white,
                Offset(size.width * 0.12f, size.height * 0.18f),
                Size(size.width * 0.58f, size.height * 0.58f),
                CornerRadius(size.width * 0.08f),
                style = Stroke(stroke)
            )
            drawRoundRect(
                white.copy(alpha = 0.75f),
                Offset(size.width * 0.31f, size.height * 0.30f),
                Size(size.width * 0.58f, size.height * 0.58f),
                CornerRadius(size.width * 0.08f),
                style = Stroke(stroke)
            )
        }
    }
}

@Composable
private fun StatusCard(state: UiState, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE4F5F4))
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.stage,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    color = Ocean
                )
                if (state.busy) {
                    OutlinedButton(onClick = onCancel) { Text("İptal") }
                }
            }
            if (state.busy) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Aqua,
                    trackColor = Color.White
                )
            }
        }
    }
}

@Composable
private fun ScanSummaryCard(summary: ScanSummary, modifier: Modifier = Modifier) {
    Card(
        modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (summary.firstScan) "İlk tarama özeti" else "Yeni fotoğraflar",
                fontWeight = FontWeight.Bold,
                color = Ink
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(summary.photos.toString(), "Fotoğraf", Ocean)
                Metric(summary.pairs.toString(), "Çift", Ink)
                Metric(summary.matched.toString(), "Oluşturuldu", Success)
                Metric((summary.rejected + summary.errors).toString(), "Atlandı", Warning)
            }
            if (summary.matched > 0) {
                Text(
                    "Sonuçlar Pictures/StereoPairFinder klasörüne kaydedildi.",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, fontSize = 11.sp, color = Muted)
    }
}

@Composable
private fun MessageCard(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = Color(0xFFFFF2DE)
    ) {
        Text(message, Modifier.padding(14.dp), color = Color(0xFF76521D), fontSize = 13.sp)
    }
}

@Composable
private fun LastScanLine(lastScanMillis: Long?, modifier: Modifier = Modifier) {
    val text = lastScanMillis?.let {
        val format = remember {
            DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm")
                .withZone(ZoneId.systemDefault())
        }
        "Son başarılı tarama: ${format.format(Instant.ofEpochMilli(it))}"
    } ?: "Henüz galeri taraması yapılmadı"
    Text(
        text,
        modifier.fillMaxWidth(),
        color = Muted,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ManualPage(
    state: UiState,
    onBack: () -> Unit,
    onPick: () -> Unit,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onMaxSeconds: (Int) -> Unit,
    onSimilarity: (Int) -> Unit,
    onSave: (AnalysisResult) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(42.dp).clickable(enabled = !state.busy, onClick = onBack),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = Color.White
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("‹", fontSize = 34.sp, color = Ocean)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("İki Fotoğraf Seç", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("Elle çift denetimi", color = Muted, fontSize = 13.sp)
                }
            }
        }
        item {
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onPick,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ocean)
                    ) {
                        Text(
                            if (state.selected.size == 2) "Fotoğrafları Değiştir" else "İki Fotoğraf Seç",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "${state.selected.size}/2 fotoğraf seçildi",
                        color = if (state.selected.size == 2) Success else Muted,
                        fontSize = 13.sp
                    )
                    HorizontalDivider(color = Color(0xFFE9EEF2))
                    Text("Azami zaman farkı: ${state.maxSeconds} saniye", color = Ink)
                    Slider(
                        value = state.maxSeconds.toFloat(),
                        onValueChange = { onMaxSeconds(it.toInt()) },
                        valueRange = 1f..60f,
                        steps = 58,
                        enabled = !state.busy
                    )
                    Text("Benzerlik eşiği: %${state.similarity}", color = Ink)
                    Slider(
                        value = state.similarity.toFloat(),
                        onValueChange = { onSimilarity(it.toInt()) },
                        valueRange = 1f..100f,
                        steps = 98,
                        enabled = !state.busy
                    )
                    Button(
                        onClick = onAnalyze,
                        enabled = !state.busy && state.selected.size == 2,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Aqua, contentColor = Ink)
                    ) {
                        Text("Çifti İncele", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (state.busy) {
            item { StatusCard(state, onCancel) }
        }
        state.message?.let { item { MessageCard(it) } }
        state.results.firstOrNull()?.let { result ->
            item { ResultCard(result, onSave) }
        }
        item { Spacer(Modifier.height(20.dp)) }
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

    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                result.status.text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (result.saveable) Success else Warning
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
                Spacer(Modifier.width(3.dp))
                result.rightPreview?.let {
                    Image(
                        it.asImageBitmap(),
                        "Sonra çekilen sağ fotoğraf",
                        Modifier.weight(1f).fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Text("Sol: ${time(result.pair.left.takenAtMillis)}", fontSize = 12.sp, color = Muted)
            Text("Sağ: ${time(result.pair.right.takenAtMillis)}", fontSize = 12.sp, color = Muted)
            Text("Zaman farkı: ${result.pair.seconds?.let { "%.3f sn".format(it) } ?: "—"}")
            Text("Benzerlik: %.1f%% · Eşleşme: %d".format(result.similarity, result.reliableMatches))
            Text(
                "Hizalama güveni: %.1f%% · Düşey hata: %.2f px".format(
                    result.alignmentConfidence,
                    result.medianVerticalError
                )
            )
            Text("Düşey translation: %.2f px".format(result.verticalTranslationPx))
            Text("Kadraj: ${result.framingMode}", fontSize = 13.sp, color = Muted)
            if (
                result.cropCenterXPercent != null &&
                result.cropCenterYPercent != null &&
                result.cropSidePercent != null
            ) {
                Text(
                    "Kırpma merkezi: X %.1f%% · Y %.1f%% · Kenar %.1f%%".format(
                        result.cropCenterXPercent,
                        result.cropCenterYPercent,
                        result.cropSidePercent
                    ),
                    fontSize = 12.sp,
                    color = Muted
                )
            }
            result.sbsPreview?.let { bitmap ->
                Image(
                    bitmap.asImageBitmap(),
                    "Hizalanmış SBS önizleme",
                    Modifier.fillMaxWidth().aspectRatio(2f),
                    contentScale = ContentScale.Fit
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Blink: ${if (overlay) "sağ" else "sol"}", Modifier.weight(1f))
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
                Button(
                    onClick = { onSave(result) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Success)
                ) {
                    Text("Bu Sonucu Kaydet")
                }
            }
        }
    }
}
