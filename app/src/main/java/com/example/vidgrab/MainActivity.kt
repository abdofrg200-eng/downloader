package com.example.vidgrab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {

    private val vm: DownloadViewModel by viewModels()

    // إذن الإشعارات (أندرويد 13+) عشان إشعار التقدم يبان
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.onUrl(it.trim()) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // لو جه رابط من "مشاركة" في تطبيق تاني
        handleShare(intent)

        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { MainScreen(vm) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: DownloadViewModel) {
    val s by vm.state.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("VidGrab", style = MaterialTheme.typography.headlineMedium)
        Text(
            "الصق رابط الفيديو، اختار الجودة، وحمّل.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = s.url,
            onValueChange = vm::onUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("رابط الفيديو") },
            singleLine = true,
            trailingIcon = {
                TextButton(onClick = {
                    clipboard.getText()?.text?.let { vm.onUrl(it.trim()) }
                }) { Text("لصق") }
            },
        )

        Button(
            onClick = vm::fetchInfo,
            enabled = s.url.isNotBlank() && !s.loading && !s.downloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (s.loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("جلب المعلومات")
        }

        if (s.title != null) {
            s.thumbnail?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
            Text(s.title!!, style = MaterialTheme.typography.titleMedium)

            // فيديو / صوت
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !s.audioOnly,
                    onClick = { vm.setAudioOnly(false) },
                    label = { Text("فيديو (MP4)") },
                )
                FilterChip(
                    selected = s.audioOnly,
                    onClick = { vm.setAudioOnly(true) },
                    label = { Text("صوت فقط (MP3)") },
                )
            }

            // الجودات
            if (!s.audioOnly) {
                Text("الجودة", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = s.selectedHeight == null,
                            onClick = { vm.selectHeight(null) },
                            label = { Text("الأعلى") },
                        )
                    }
                    items(s.heights) { h ->
                        FilterChip(
                            selected = s.selectedHeight == h,
                            onClick = { vm.selectHeight(h) },
                            label = { Text("${h}p") },
                        )
                    }
                }
            }

            if (s.downloading) {
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${(s.progress * 100).toInt()}%")
                    OutlinedButton(onClick = vm::cancel) { Text("إلغاء") }
                }
            } else {
                Button(onClick = vm::download, modifier = Modifier.fillMaxWidth()) {
                    Text("تحميل")
                }
            }
        }

        s.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
