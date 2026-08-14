package com.stereopairfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasFullPhotoPermission = granted
        if (granted) vm.scanAll() else vm.permissionDenied()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Stereo Pair Finder") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Text(
                    "Tam tarama modu: Android'in telefonda gördüğü tüm yerel fotoğraflar salt okunur olarak incelenir. Kaynak fotoğraflar silinmez, değiştirilmez veya taşınmaz.",
                    Modifier.padding(14.dp)
                )
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Tüm telefonu tara")
            }

            if (!hasFullPhotoPermission) {
                Text(
                    "İzin sorulduğunda tam tarama için ‘Tüm fotoğraflara izin ver’ seçeneğini seçin.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

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

            if (state.busy) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = vm::cancel) {
                    Text("İptal")
                }
            }

            Text(state.stage)

            if (state.photoCount > 0) {
                Text("Bulunan fotoğraf: ${state.photoCount}")
                Text("Zaman filtresinden geçen olası çift: ${state.candidateCount}")
                Text("Stereo eşleşme: ${state.matchedCount}")
                Text("Kaydedilen SBS: ${state.savedCount}")
                if (state.failedCount > 0) {
                    Text("Atlanan/hata veren işlem: ${state.failedCount}")
                }
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.weight(1f))

            Text(
                "Eşleşen dosyalar Pictures/Stereo SBS Test/ klasörüne yüksek çözünürlüklü JPEG olarak otomatik kaydedilir. Bu çıktı klasörü yeni taramalarda kaynak olarak kullanılmaz.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
