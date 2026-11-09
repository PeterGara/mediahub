package com.petergara.mediahub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MediaMetadata
import com.petergara.mediahub.api.LocalApiServer
import com.petergara.mediahub.data.MediaRepository
import com.petergara.mediahub.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createPlaybackChannel()
        LocalApiServer.start(applicationContext)
        PlayerController.connect(applicationContext)
        requestMediaPermissions()
        setContent {
            MaterialTheme(colorScheme = rememberLightScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { MediaHubApp() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            LocalApiServer.stop()
            PlayerController.release()
        }
    }

    /** Media3 tvorí kanál s LOW dôležitosťou → OnePlus ho skryje. Preto HIGH. */
    private fun createPlaybackChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val existing = nm.getNotificationChannel("default_channel_id")
            if (existing == null || existing.importance < NotificationManager.IMPORTANCE_HIGH) {
                val ch = NotificationChannel(
                    "default_channel_id", "Prehrávanie", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Ovládanie prehrávania hudby" }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /** All files access – jediný spôsob premenovať/presunúť cudzie súbory (Android 11+). */
    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun requestMediaPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        else arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}

/** Stav vyhľadávania – jeden vstup pre všetky typy médií. */
class SearchState {
    var query by mutableStateOf("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaHubApp() {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var openImage by remember { mutableStateOf<MediaRepository.MediaItem?>(null) }
    var openVideo by remember { mutableStateOf<MediaRepository.MediaItem?>(null) }
    val tabs = listOf("Hudba", "Galéria", "Súbory")

    // Fullscreen prehliadač obrázka
    openImage?.let { item ->
        ImageViewer(item) { openImage = null }
        return
    }
    // Prehrávač videa
    openVideo?.let { item ->
        VideoPlayerScreen(item) { openVideo = null }
        return
    }

    Scaffold(
        bottomBar = { NowPlayingBar() },
        topBar = {
            Column {
                Text(
                    "MediaHub",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Hľadať hudbu, fotky, videá…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> MusicTab(query)
                1 -> GalleryTab(query) { openImage = it }
                else -> VideoTab(query) { openVideo = it }
            }
        }
    }
}

// ───────────────────────────── HUDBA ─────────────────────────────

@Composable
fun MusicTab(query: String) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaRepository.MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<MediaRepository.MediaItem?>(null) }
    var aiTarget by remember { mutableStateOf<MediaRepository.MediaItem?>(null) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var aiRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        loading = true
        items = withContext(Dispatchers.IO) {
            MediaRepository.get(context).list("audio", query, 300)
        }
        loading = false
    }

    Column {
        if (!hasAllFilesAccess()) WarningCard(
            "Chýba All files access",
            "Bez neho premenovanie/presun zlyhá. Čítanie a prehrávanie funguje.",
            "Povoliť"
        ) { (context as? MainActivity)?.requestAllFilesAccess() }

        when {
            loading -> Loading()
            items.isEmpty() -> Empty("Žiadna hudba")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { playAudio(items, item) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.MusicNote, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge)
                            val sub = listOfNotNull(item.artist, item.album)
                                .joinToString(" · ")
                                .ifBlank { formatSize(item.size) }
                            Text(sub, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { playAudio(items, item) }) {
                            Icon(Icons.Default.PlayArrow, "Prehrať")
                        }
                        IconButton(onClick = { aiTarget = item }) {
                            Icon(Icons.Default.AutoAwesome, "AI analýza")
                        }
                        IconButton(onClick = { renameTarget = item }) {
                            Icon(Icons.Default.Edit, "Premenovať")
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { item ->
        RenameDialog(item, { renameTarget = null }) { newName ->
            renameTarget = null
            scope.launch {
                try {
                    MediaRepository.get(context).rename(item.id, item.kind, newName)
                    items = withContext(Dispatchers.IO) {
                        MediaRepository.get(context).list("audio", query, 300)
                    }
                } catch (e: Exception) { }
            }
        }
    }

    // AI analýza hudby – pošle ju do modelu a zapíše výsledok do ID3/FLAC tagov
    aiTarget?.let { item ->
        AiAnalysisDialog(
            item = item,
            running = aiRunning,
            result = aiResult,
            onDismiss = { aiTarget = null; aiResult = null; aiRunning = false },
            onRun = {
                aiRunning = true
                aiResult = null
                scope.launch {
                    aiResult = withContext(Dispatchers.IO) {
                        try {
                            val url = java.net.URL("http://127.0.0.1:8765/analyze_audio")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.outputStream.use {
                                it.write("""{"id":${item.id},"kind":"audio"}""".toByteArray())
                            }
                            val resp = conn.inputStream.bufferedReader().readText()
                            conn.disconnect()
                            val o = org.json.JSONObject(resp)
                            if (o.optBoolean("ok")) {
                                val a = o.optJSONObject("analysis")
                                "✅ Tagy zapísané\n\n" +
                                    "Názov: ${a?.optString("title")}\n" +
                                    "Interpret: ${a?.optString("artist")}\n" +
                                    "Žáner: ${a?.optString("genre")}\n" +
                                    "Jazyk: ${a?.optString("language")}\n" +
                                    "Nálada: ${a?.optString("mood")}\n" +
                                    "Tagy: ${a?.optJSONArray("tags")?.toString() ?: "-"}"
                            } else {
                                "❌ ${o.optString("error", o.optString("write_error", "neznáma chyba"))}\n" +
                                    (o.optString("raw").takeIf { it.isNotBlank() }?.let { "Model vrátil: $it" } ?: "")
                            }
                        } catch (e: Exception) {
                            "❌ AI nedostupná (${e.message}).\nBeží llama-server na 8080?"
                        }
                    }
                    aiRunning = false
                    items = withContext(Dispatchers.IO) {
                        MediaRepository.get(context).list("audio", query, 300)
                    }
                }
            }
        )
    }
}

/** Dialóg AI analýzy – model prečíta metadata a zapíše tagy. */
@Composable
fun AiAnalysisDialog(
    item: MediaRepository.MediaItem,
    running: Boolean,
    result: String?,
    onDismiss: () -> Unit,
    onRun: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI analýza") },
        text = {
            Column {
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Model navrhne názov, interpreta, žáner a tagy a zapíše ich do súboru (ID3/FLAC).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                when {
                    running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Analyzujem…", style = MaterialTheme.typography.bodySmall)
                    }
                    result != null -> Text(result, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onRun, enabled = !running) {
                Text(if (result == null) "Analyzovať" else "Znova")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Zavrieť") }
        }
    )
}

// ───────────────────────────── GALÉRIA ─────────────────────────────

@Composable
fun GalleryTab(query: String, onOpen: (MediaRepository.MediaItem) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaRepository.MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<MediaRepository.MediaItem?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        loading = true
        items = withContext(Dispatchers.IO) {
            MediaRepository.get(context).list("image", query, 500)
        }
        loading = false
    }

    Column {
        if (!hasAllFilesAccess()) WarningCard(
            "Chýba All files access",
            "Bez neho premenovanie/presun zlyhá.",
            "Povoliť"
        ) { (context as? MainActivity)?.requestAllFilesAccess() }

        when {
            loading -> Loading()
            items.isEmpty() -> Empty("Žiadne fotky")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.id }) { item ->
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .padding(2.dp)
                    ) {
                        Thumbnail(item)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable { onOpen(item) }
                        )
                        IconButton(
                            onClick = { renameTarget = item },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(Icons.Default.Edit, "Premenovať",
                                tint = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { item ->
        RenameDialog(item, { renameTarget = null }) { newName ->
            renameTarget = null
            scope.launch {
                try {
                    MediaRepository.get(context).rename(item.id, item.kind, newName)
                    items = withContext(Dispatchers.IO) {
                        MediaRepository.get(context).list("image", query, 500)
                    }
                } catch (e: Exception) { }
            }
        }
    }
}

/** Skutočný náhľad obrázka z MediaStore (thumbnail). */
@Composable
fun Thumbnail(item: MediaRepository.MediaItem) {
    val context = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        bmp = withContext(Dispatchers.IO) {
            try {
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(300, 300), null)
                } else {
                    MediaStore.Images.Thumbnails.getThumbnail(
                        context.contentResolver, item.id,
                        MediaStore.Images.Thumbnails.MINI_KIND, null
                    )
                }
            } catch (e: Exception) { null }
        }
    }

    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } ?: Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
    }
}

/** Fullscreen náhľad obrázka s premenovaním a info. */
@Composable
fun ImageViewer(item: MediaRepository.MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var renameTarget by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        bmp = withContext(Dispatchers.IO) {
            try {
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item.id
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(1600, 1600), null)
                } else {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) { null }
        }
    }

    Scaffold(
        topBar = {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, "Späť")
                }
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { renameTarget = true }) {
                    Icon(Icons.Default.Edit, "Premenovať")
                }
            }
        }
    ) { p ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(p)
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center
        ) {
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } ?: CircularProgressIndicator()
        }
    }

    if (renameTarget) {
        RenameDialog(item, { renameTarget = false }) { newName ->
            renameTarget = false
            scope.launch {
                try { MediaRepository.get(context).rename(item.id, item.kind, newName) }
                catch (e: Exception) { }
            }
        }
    }
}

// ───────────────────────────── VIDEA ─────────────────────────────

@Composable
fun VideoTab(query: String, onOpen: (MediaRepository.MediaItem) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaRepository.MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<MediaRepository.MediaItem?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        loading = true
        items = withContext(Dispatchers.IO) {
            MediaRepository.get(context).list("video", query, 300)
        }
        loading = false
    }

    Column {
        if (!hasAllFilesAccess()) WarningCard(
            "Chýba All files access",
            "Bez neho premenovanie/presun zlyhá.",
            "Povoliť"
        ) { (context as? MainActivity)?.requestAllFilesAccess() }

        when {
            loading -> Loading()
            items.isEmpty() -> Empty("Žiadne videá")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.id }) { item ->
                    Box(Modifier
                        .aspectRatio(0.8f)
                        .padding(3.dp)) {
                        VideoThumb(item)
                        Box(Modifier
                            .fillMaxSize()
                            .clickable { onOpen(item) })
                        Icon(
                            Icons.Default.PlayArrow, "Prehrať",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        IconButton(
                            onClick = { renameTarget = item },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(Icons.Default.Edit, "Premenovať",
                                tint = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { item ->
        RenameDialog(item, { renameTarget = null }) { newName ->
            renameTarget = null
            scope.launch {
                try {
                    MediaRepository.get(context).rename(item.id, item.kind, newName)
                    items = withContext(Dispatchers.IO) {
                        MediaRepository.get(context).list("video", query, 300)
                    }
                } catch (e: Exception) { }
            }
        }
    }
}

@Composable
fun VideoThumb(item: MediaRepository.MediaItem) {
    val context = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        bmp = withContext(Dispatchers.IO) {
            try {
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id
                )
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(uri, android.util.Size(300, 300), null)
                } else {
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                }
            } catch (e: Exception) { null }
        }
    }

    bmp?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } ?: Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
    }
}

/** Prehrávač videa (Media3 PlayerView) – fullscreen. */
@Composable
fun VideoPlayerScreen(item: MediaRepository.MediaItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var player by remember(item.id) { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }

    androidx.compose.runtime.DisposableEffect(item.id) {
        val p = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        p.setMediaItem(ExoMediaItem.fromUri(item.path))
        p.prepare()
        p.play()
        player = p
        onDispose { p.release() }
    }

    Scaffold(
        topBar = {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, "Späť")
                }
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    ) { p ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(p)
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center
        ) {
            player?.let { pl ->
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        androidx.media3.ui.PlayerView(ctx).apply {
                            this.player = pl
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ───────────────────────────── SPOLOČNÉ ─────────────────────────────

/** Prehrá zoznam audio od kliknutej skladby. */
fun playAudio(all: List<MediaRepository.MediaItem>, clicked: MediaRepository.MediaItem) {
    val queue = all.filter { it.kind == "audio" }.map { m ->
        ExoMediaItem.Builder()
            .setUri(m.path)
            .setMediaId(m.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(m.name)
                    .setArtist(m.artist ?: "")
                    .setAlbumTitle(m.album ?: "")
                    .build()
            )
            .build()
    }
    if (queue.isEmpty()) return
    val idx = queue.indexOfFirst { it.mediaId == clicked.id.toString() }.coerceAtLeast(0)
    PlayerController.playQueue(queue, idx)
}

/** Mini prehrávač dole (Scaffold bottomBar) – vždy viditeľný, keď sa niečo hrá. */
@Composable
fun NowPlayingBar() {
    val title by PlayerController.currentTitle.collectAsState()
    val playing by PlayerController.isPlaying.collectAsState()
    val hasQueue by PlayerController.hasQueue.collectAsState()
    if (!hasQueue) return
    Surface(tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                title.ifBlank { "Prehrávanie…" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = { PlayerController.previous() }) {
                Icon(Icons.Default.SkipPrevious, "Predchádzajúca")
            }
            IconButton(onClick = { PlayerController.toggle() }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Prehrať/Pozastaviť"
                )
            }
            IconButton(onClick = { PlayerController.next() }) {
                Icon(Icons.Default.SkipNext, "Ďalšia")
            }
        }
    }
}

@Composable
fun RenameDialog(
    item: MediaRepository.MediaItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(item.name) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Premenovať") },
        text = {
            Column {
                Text(item.name, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(name.trim()) }) {
                Text("OK")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Zrušiť") }
        }
    )
}

@Composable
fun WarningCard(title: String, text: String, actionLabel: String, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.TextButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** All files access – potrebné na premenovanie/presun súborov. */
fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun rememberLightScheme() =
    if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        remember { dynamicLightColorScheme(ctx) }
    } else lightColorScheme()