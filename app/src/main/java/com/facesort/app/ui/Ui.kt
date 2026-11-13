package com.facesort.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facesort.app.Prefs
import com.facesort.app.ScanMode
import com.facesort.app.cluster.Defaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.facesort.app.data.GroupWithCount
import com.facesort.app.data.VideoEntity
import com.facesort.app.media.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun App(vm: MainViewModel, hasMedia: Boolean, onRequestPerms: () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val screen by vm.screen.collectAsStateWithLifecycle()
        val msg by vm.message.collectAsStateWithLifecycle()
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
        BackHandler(enabled = screen != Screen.Home) { vm.screen.value = Screen.Home }

        val plan by vm.plan.collectAsStateWithLifecycle()
        plan?.let { p -> ConfirmMoveDialog(p, onConfirm = vm::confirmPlan, onDismiss = vm::cancelPlan) }

        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(vm, hasMedia, onRequestPerms)
                    is Screen.Group -> GroupScreen(vm, s.id)
                    Screen.Review -> VideoListScreen(vm, "Review", vm.review)
                    Screen.NoFaces -> VideoListScreen(vm, "No faces found", vm.noFaces)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, hasMedia: Boolean, onRequestPerms: () -> Unit) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val noFaces by vm.noFaces.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val total by vm.videoCount.collectAsStateWithLifecycle()
    var showFolders by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var askPersonName by remember { mutableStateOf(false) }
    val photoBusy by vm.photoBusy.collectAsStateWithLifecycle()
    val pickPhotos = rememberPhotoPicker(vm)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("FaceSort") },
            actions = {
                IconButton(onClick = { showFolders = true }) { Icon(Icons.AutoMirrored.Filled.List, "Source folders") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
            }
        )
        if (!hasMedia) {
            Card(Modifier.padding(12.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("FaceSort needs access to your videos to scan them. Everything stays on this phone.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestPerms) { Text("Grant access") }
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scan.running) OutlinedButton(onClick = vm::cancelScan) { Icon(Icons.Default.Close, null); Text(" Stop") }
            else Button(onClick = vm::startScan, enabled = hasMedia) { Icon(Icons.Default.Search, null); Text(" Scan") }
            Button(onClick = { vm.buildPlan() }, enabled = !scan.running) { Icon(Icons.AutoMirrored.Filled.Send, null); Text(" Organise") }
        }
        Row(Modifier.padding(horizontal = 12.dp)) {
            OutlinedButton(onClick = { askPersonName = true }, enabled = !photoBusy) {
                Icon(Icons.Default.Face, null); Text(if (photoBusy) " Adding…" else " Add person from photo")
            }
        }
        if (scan.running) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("${scan.phase}  ${if (scan.total > 0) "${scan.done}/${scan.total}" else ""}", style = MaterialTheme.typography.bodySmall)
                if (scan.total > 0) LinearProgressIndicator(progress = { scan.done.toFloat() / scan.total }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        scan.failed?.let { err ->
            Card(Modifier.padding(12.dp).fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (scan.modelMissing) err else "Last scan failed: $err", color = MaterialTheme.colorScheme.error)
                    if (scan.modelMissing) TextButton(onClick = vm::startScan, enabled = hasMedia) { Text("Retry") }
                }
            }
        }

        val people = groups.filter { it.name != null }
        val unnamed = groups.filter { it.name == null && it.faceCount > 0 }
        Text(
            "$total videos • ${people.size} people • ${unnamed.size} unnamed groups",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.screen.value = Screen.Review }, label = { Text("Review (${review.size})") },
                leadingIcon = { Icon(Icons.Default.Warning, null) })
            AssistChip(onClick = { vm.screen.value = Screen.NoFaces }, label = { Text("No faces (${noFaces.size})") },
                leadingIcon = { Icon(Icons.Default.Info, null) })
        }

        if (groups.isEmpty() && !scan.running) {
            Text(
                "No groups yet. Pick source folders (folder icon) or leave it on all videos, then tap Scan. " +
                    "FaceSort starts knowing nobody: you name each group once and later scans match automatically.",
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxSize()) {
            if (people.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("People") }
                items(people, key = { it.id }) { g -> GroupCell(g) { vm.screen.value = Screen.Group(g.id) } }
            }
            if (unnamed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Tap to name") }
                items(unnamed, key = { it.id }) { g -> GroupCell(g) { vm.screen.value = Screen.Group(g.id) } }
            }
        }
    }
    if (askPersonName) TextDialog("Who is in the photos?", "", onDone = { askPersonName = false; pickPhotos(it) },
        onDismiss = { askPersonName = false })
    if (showFolders) FolderDialog(vm) { showFolders = false }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

@Composable private fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp))

@Composable
fun GroupCell(g: GroupWithCount, onClick: () -> Unit) {
    Column(
        Modifier.padding(6.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FaceThumb(g.thumbPath, 88)
        Text(g.name ?: "Unknown", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text("${g.videoCount} videos", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FaceThumb(path: String?, sizeDp: Int) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it) } }
    }
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Icon(Icons.Default.Person, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(vm: MainViewModel, id: Long) {
    val group by vm.group(id).collectAsStateWithLifecycle(null)
    val videos by vm.videosIn(id).collectAsStateWithLifecycle(emptyList())
    val faces by vm.facesIn(id).collectAsStateWithLifecycle(emptyList())
    val allGroups by vm.groups.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<VideoEntity?>(null) }
    val pickPhotos = rememberPhotoPicker(vm)

    LaunchedEffect(group, allGroups) {
        // group was merged away / regrouped
        if (group == null && allGroups.isNotEmpty() && allGroups.none { it.id == id }) vm.screen.value = Screen.Home
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(group?.name ?: "Unknown person") },
            navigationIcon = { IconButton(onClick = { vm.screen.value = Screen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                group?.name?.let { n -> IconButton(onClick = { pickPhotos(n) }) { Icon(Icons.Default.Face, "Add photos of $n") } }
                IconButton(onClick = { naming = true }) { Icon(Icons.Default.Edit, "Name") }
                IconButton(onClick = { merging = true }) { Icon(Icons.Default.Add, "Merge") }
            }
        )
        if (group?.name == null) {
            Button(onClick = { naming = true }, modifier = Modifier.padding(12.dp)) { Text("Name this person") }
        }
        LazyRow(contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(faces, key = { it.id }) { FaceThumb(it.thumbPath, 64) }
        }
        Text("${videos.size} videos", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        VideoList(videos, onMove = { moving = it })
    }
    if (naming) TextDialog("Name", group?.name ?: "", onDone = { vm.nameGroup(id, it); naming = false }, onDismiss = { naming = false })
    if (merging) PickGroupDialog(
        title = "Merge this group into…",
        groups = allGroups.filter { it.id != id },
        onPick = { vm.merge(id, it); merging = false },
        onDismiss = { merging = false }
    )
    moving?.let { v -> MoveVideoDialog(vm, v) { moving = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoListScreen(vm: MainViewModel, title: String, flow: kotlinx.coroutines.flow.StateFlow<List<VideoEntity>>) {
    val videos by flow.collectAsStateWithLifecycle()
    var moving by remember { mutableStateOf<VideoEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("$title (${videos.size})") },
            navigationIcon = { IconButton(onClick = { vm.screen.value = Screen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
        VideoList(videos, onMove = { moving = it })
    }
    moving?.let { v -> MoveVideoDialog(vm, v) { moving = null } }
}

@Composable
fun VideoList(videos: List<VideoEntity>, onMove: (VideoEntity) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        items(videos, key = { it.id }) { v ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(v.uri), "video/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VideoThumb(v.uri)
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${v.relativePath} • ${v.faceCount} faces • ${(v.confidence * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onMove(v) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move to person") }
            }
        }
    }
}

@Composable
fun VideoThumb(uri: String) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try { ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(240, 240), null) } catch (_: Exception) { null }
        }
    }
    Box(Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
fun MoveVideoDialog(vm: MainViewModel, v: VideoEntity, onDismiss: () -> Unit) {
    val named by vm.named.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf(false) }
    if (newName) {
        TextDialog("New person", "", onDone = { vm.moveVideo(v.id, null, it); onDismiss() }, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${v.name}” to…") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { ListItem(headlineContent = { Text("+ New person…") }, modifier = Modifier.clickable { newName = true }) }
                items(named, key = { it.id }) { g ->
                    ListItem(headlineContent = { Text(g.name ?: "") }, leadingContent = { FaceThumb(g.thumbPath, 36) },
                        modifier = Modifier.clickable { vm.moveVideo(v.id, g.id); onDismiss() })
                }
                item { ListItem(headlineContent = { Text("Review (not sure)") }, modifier = Modifier.clickable { vm.moveVideo(v.id, null); onDismiss() }) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun PickGroupDialog(title: String, groups: List<GroupWithCount>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(groups, key = { it.id }) { g ->
                    ListItem(
                        headlineContent = { Text(g.name ?: "Unknown") },
                        supportingContent = { Text("${g.videoCount} videos") },
                        leadingContent = { FaceThumb(g.thumbPath, 40) },
                        modifier = Modifier.clickable { onPick(g.id) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun TextDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onDone(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmMoveDialog(plan: List<MoveItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val byFolder = plan.groupingBy { it.target }.eachCount().toList().sortedByDescending { it.second }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${plan.size} videos?") },
        text = {
            Column {
                Text("Videos will be moved (not copied) into these folders. Android will then ask you to allow the changes.")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(byFolder) { (folder, n) -> Text("$folder  —  $n", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FolderDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val folders by produceState<List<Folder>>(emptyList()) { value = vm.folders() }
    var selected by remember { mutableStateOf(vm.prefs.sourceFolders) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders to scan") },
        text = {
            Column {
                Text("None selected = all videos on the phone.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(folders.filterNot { it.relativePath.startsWith(Prefs.ROOT_FOLDER) }, key = { it.relativePath }) { f ->
                        Row(Modifier.fillMaxWidth().clickable {
                            selected = if (f.relativePath in selected) selected - f.relativePath else selected + f.relativePath
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(f.relativePath in selected, null)
                            Text("${f.relativePath}  (${f.count})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setFolders(selected); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { selected = emptySet() }) { Text("All videos") } }
    )
}

@Composable
fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val p = vm.prefs
    var cluster by remember { mutableFloatStateOf(p.clusterThreshold) }
    var match by remember { mutableFloatStateOf(p.matchThreshold) }
    var share by remember { mutableFloatStateOf(p.minShare) }
    var mode by remember { mutableStateOf(p.scanMode) }
    var moveReview by remember { mutableStateOf(p.moveReviewToo) }
    var confirmReset by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScrollCompat()) {
                Text("Grouping strictness: ${"%.2f".format(cluster)}  (higher = stricter, more groups)")
                Slider(cluster, { cluster = it }, valueRange = Defaults.SLIDER_MIN..Defaults.SLIDER_MAX)
                Text("Auto-match to named people: ${"%.2f".format(match)}")
                Slider(match, { match = it }, valueRange = Defaults.SLIDER_MIN..Defaults.SLIDER_MAX)
                Text("Dominant person share: ${(share * 100).roundToInt()}% (below → Review)")
                Slider(share, { share = it }, valueRange = 0.2f..0.9f)
                Text("Scan mode (applies to new scans)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
                Text("Every ${mode.stepMs / 1000}s, up to ${mode.maxFrames} frames" + if (mode.exactSeek) ", exact frames (slow)" else "",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { cluster = Defaults.CLUSTER_THRESHOLD; match = Defaults.MATCH_THRESHOLD; share = Defaults.MIN_SHARE }) {
                    Text("Restore default thresholds")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(moveReview, { moveReview = it }); Text("Also move Review videos to FaceSort/_Review")
                }
                TextButton(onClick = { confirmReset = true }) { Text("Reset all FaceSort data", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.applySettings(cluster, match, share, mode, moveReview); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset?") },
        text = { Text("Forget all scanned faces and named people. Your video files are not touched.") },
        confirmButton = { TextButton(onClick = { vm.resetAll(); confirmReset = false; onDismiss() }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

/** Returns a function that opens the system photo picker (no storage permission needed) for a named person. */
@Composable
fun rememberPhotoPicker(vm: MainViewModel): (String) -> Unit {
    var pendingName by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        val n = pendingName
        pendingName = null
        if (n != null && uris.isNotEmpty()) vm.addPersonFromPhotos(n, uris)
    }
    return { name ->
        pendingName = name
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
