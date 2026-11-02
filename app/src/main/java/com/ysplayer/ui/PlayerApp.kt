@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ysplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import com.ysplayer.LibraryViewModel
import com.ysplayer.BuildConfig
import com.ysplayer.LegalActivity
import com.ysplayer.data.*
import com.ysplayer.library.LibraryRepository
import com.ysplayer.playback.PlaybackBus
import com.ysplayer.playback.PlaybackSnapshot
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.Disposable
import kotlinx.coroutines.flow.distinctUntilChanged

private val Sage = Color(0xFF52665B)
private val Paper = Color(0xFFF8F6F0)
private val Copper = Color(0xFF98654D)

@Composable
fun PlayerApp(vm: LibraryViewModel = viewModel()) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFB0CDB9), secondary = Color(0xFFDFB69F))
        else lightColorScheme(primary = Sage, secondary = Copper, background = Paper, surface = Paper, surfaceContainer = Color(0xFFEEEEE6))) {
        val context = LocalContext.current
        val works by vm.works.collectAsStateWithLifecycle()
        val tracks by vm.tracks.collectAsStateWithLifecycle()
        val sources by vm.sources.collectAsStateWithLifecycle()
        val playlists by vm.playlists.collectAsStateWithLifecycle()
        val entries by vm.entries.collectAsStateWithLifecycle()
        val progress by vm.progress.collectAsStateWithLifecycle()
        val playback by PlaybackBus.state.collectAsStateWithLifecycle()
        val scan by vm.repository.scanState.collectAsStateWithLifecycle()
        val notice by vm.notice.collectAsStateWithLifecycle()
        val gridLibrary by vm.gridLibrary.collectAsStateWithLifecycle()
        val metadata by vm.metadata.state.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var workId by rememberSaveable { mutableStateOf<String?>(null) }
        var listId by rememberSaveable { mutableStateOf<String?>(null) }
        var playerOpen by rememberSaveable { mutableStateOf(false) }
        var coversOnly by remember { mutableStateOf(false) }
        var createList by remember { mutableStateOf(false) }
        var searchExpanded by rememberSaveable { mutableStateOf(false) }
        var libraryQuery by rememberSaveable { mutableStateOf("") }
        var libraryFilterOpen by remember { mutableStateOf(false) }
        var removeWork by remember { mutableStateOf<Work?>(null) }
        val libraryRoot = !playerOpen && workId == null && listId == null && tab == 0
        val snackbar = remember { SnackbarHostState() }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                vm.scan(uri = uri, coversOnly = coversOnly)
            }.onFailure { vm.notice.value = "目录授权失败：${it.message}" }
        }
        val addSource: (Boolean) -> Unit = { coversOnly = it; folderPicker.launch(null) }
        LaunchedEffect(notice) { if (notice.isNotEmpty()) { snackbar.showSnackbar(notice); vm.notice.value = "" } }
        BackHandler(playerOpen || workId != null || listId != null) {
            when { playerOpen -> playerOpen = false; workId != null -> workId = null; else -> listId = null }
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            if (libraryRoot) LibraryTopBar(libraryQuery, searchExpanded, { libraryQuery = it }, { searchExpanded = it },
                { libraryFilterOpen = true }, { vm.scan() }, { addSource(false) }, !scan.running && sources.isNotEmpty())
            else TopAppBar(title = {
                Text(if (playerOpen) "正在收听" else if (workId != null) "作品详情" else if (listId != null) "我的播放列表" else if (tab == 1) "播放列表" else "设置", fontWeight = FontWeight.SemiBold)
            }, navigationIcon = {
                if (playerOpen || workId != null || listId != null) IconButton(onClick = {
                    when { playerOpen -> playerOpen = false; workId != null -> workId = null; else -> listId = null }
                }) { Icon(Icons.Default.ArrowBack, "返回") }
            }, actions = {
                if (workId != null && !playerOpen) IconButton(onClick = { removeWork = works.find { it.id == workId } }) {
                    Icon(Icons.Default.DeleteOutline, "从作品库移除")
                }
                if (!playerOpen && workId == null && listId == null) {
                    IconButton(onClick = { vm.scan() }, enabled = !scan.running && sources.isNotEmpty()) { Icon(Icons.Default.Refresh, "刷新目录") }
                    IconButton(onClick = { if (tab == 1) createList = true else addSource(false) }) { Icon(Icons.Default.Add, "添加") }
                }
            })
        }, bottomBar = {
            if (!playerOpen) Column {
                if (playback.trackId.isNotEmpty()) MiniPlayer(playback, { playerOpen = true }, { vm.control { if (isPlaying) pause() else { prepare(); play() } } })
                NavigationBar {
                    listOf("作品库" to Icons.Default.LibraryMusic, "播放列表" to Icons.Default.QueueMusic, "设置" to Icons.Default.Settings).forEachIndexed { i, (name, icon) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; workId = null; listId = null }, icon = { Icon(icon, name) }, label = { Text(name) })
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (scan.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(scan.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::cancelScan) { Text("取消") }
                    }
                }
                if (metadata.running && !playerOpen) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(metadata.message, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    TextButton(onClick = vm::cancelMetadata) { Text("暂停") }
                }
                when {
                    playerOpen -> PlayingPage(vm, playback, works, tracks)
                    workId != null -> works.find { it.id == workId }?.let { WorkDetail(vm, it, tracks, playlists, progress, { playerOpen = true }) }
                        ?: EmptyCard("作品已不在库中", "目录可能已重新整理，可返回作品库查看。", "返回作品库") { workId = null }
                    listId != null -> playlists.find { it.id == listId }?.let { PlaylistDetail(vm, it, entries.filter { entry -> entry.playlistId == it.id }, works, tracks, { id -> workId = id }, { listId = null }, { playerOpen = true }) }
                    tab == 0 -> LibraryPage(works, tracks, progress, { workId = it }, { addSource(false) }, gridLibrary, vm::setGridLibrary,
                        libraryQuery, libraryFilterOpen, { libraryFilterOpen = false })
                    tab == 1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("按自己的习惯，组织每一次收听。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (playlists.isEmpty()) item { EmptyCard("还没有播放列表", "在作品详情中添加整部作品或单条轨道。", "新建列表") { createList = true } }
                        items(playlists, key = { it.id }) { list ->
                            val listEntries = entries.filter { it.playlistId == list.id }
                            val first = works.find { work -> listEntries.firstOrNull()?.workId == work.id }
                            Card(onClick = { listId = list.id }) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Cover(first, Modifier.size(64.dp)); Spacer(Modifier.width(16.dp))
                                    Column { Text(list.name, fontWeight = FontWeight.SemiBold); Text("${listEntries.size} 个条目", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        item { OutlinedButton(onClick = { createList = true }) { Icon(Icons.Default.Add, null); Text("新建播放列表") } }
                    }
                    else -> SettingsPage(vm, sources, addSource, scan.message)
                }
            }
        }
        if (createList) NameDialog("新建播放列表", "", { createList = false }) { vm.createPlaylist(it); createList = false }
        removeWork?.let { selected -> RemoveWorkDialog(selected.title, { removeWork = null }) {
            removeWork = null
            vm.removeWork(selected) { workId = null }
        } }
    }
}

@Composable
internal fun LibraryPage(works: List<Work>, tracks: List<Track>, progress: List<Progress>, open: (String) -> Unit, add: () -> Unit,
    grid: Boolean, setGrid: (Boolean) -> Unit, query: String = "", filterOpen: Boolean = false, closeFilter: () -> Unit = {}) {
    var voices by remember { mutableStateOf(setOf<String>()) }
    var circles by remember { mutableStateOf(setOf<String>()) }
    var selectedTags by remember { mutableStateOf(setOf<String>()) }
    var reviewOnly by remember { mutableStateOf(false) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(false) }
    val filtered = remember(works, query, reviewOnly, favoriteOnly, voices, circles, selectedTags, recent, progress) { works.filter { work ->
        val matches = listOf(work.title, work.rj, work.voices, work.circles, work.tags).any { it.contains(query, true) }
        matches && (!reviewOnly || work.review.isNotEmpty()) && (!favoriteOnly || work.favorite) &&
            (voices.isEmpty() || voices.any { it == "未填写" && work.voices.isBlank() || it in names(work.voices) }) &&
            (circles.isEmpty() || circles.any { it == "未填写" && work.circles.isBlank() || it in names(work.circles) }) &&
            (selectedTags.isEmpty() || selectedTags.any { it == "未填写" && work.tags.isBlank() || it in tagNames(work.tags) })
    }.let { result -> if (recent) result.sortedByDescending { w -> progress.find { it.workId == w.id }?.updated ?: 0 } else result } }
    val counts = remember(tracks) { tracks.filter { it.available && !it.resource }.groupingBy { it.effectiveWorkId }.eachCount() }
    val heard = remember(progress) { progress.map { it.workId }.toSet() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val loader = context.imageLoader
    val prefetch = remember { mutableMapOf<String, Disposable>() }
    DisposableEffect(Unit) { onDispose { prefetch.values.forEach { it.dispose() }; prefetch.clear() } }
    LaunchedEffect(filtered, grid) {
        prefetch.values.forEach { it.dispose() }; prefetch.clear()
        snapshotFlow {
            if (grid) gridState.layoutInfo.visibleItemsInfo.map { it.index }
            else listState.layoutInfo.visibleItemsInfo.map { it.index }
        }.distinctUntilChanged().collect { indices ->
            val first = indices.minOrNull() ?: 0
            val last = indices.maxOrNull() ?: 0
            val upcoming = filtered.subList((first - 6).coerceAtLeast(0).coerceAtMost(filtered.size),
                (last + 13).coerceAtMost(filtered.size))
                .filter { it.cover.isNotBlank() || it.autoCover.isNotBlank() }
            val wanted = upcoming.associateBy { it.cover.ifEmpty { it.autoCover } }
            (prefetch.keys - wanted.keys).forEach { prefetch.remove(it)?.dispose() }
            wanted.forEach { (uri, work) -> if (uri !in prefetch) prefetch[uri] = loader.enqueue(coverRequest(context, work, if (grid) 640 else 320)) }
        }
    }
    Column {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val active = favoriteOnly || reviewOnly || recent || voices.isNotEmpty() || circles.isNotEmpty() || selectedTags.isNotEmpty()
            Text("${filtered.size} 部作品" + if (active) " · 已筛选" else "", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconToggleButton(checked = !grid, onCheckedChange = { setGrid(false) }) { Icon(Icons.Default.ViewList, "列表视图") }
            IconToggleButton(checked = grid, onCheckedChange = { setGrid(true) }) { Icon(Icons.Default.GridView, "双列封面视图") }
        }
        if (grid) LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState, modifier = Modifier.fillMaxSize().testTag("library_grid"),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (works.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyCard("让音声，回到自己的书架", "选择手机上的音声目录，自动按 RJ 与目录结构整理作品。", "选择音声目录", add) }
            else if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有匹配的作品，试试清除筛选。") }
            gridItems(filtered, key = { it.id }) { work ->
                WorkGridCard(work, "${counts[work.id] ?: 0} 条轨道" + if (work.id in heard) " · 可继续" else "", { open(work.id) })
            }
        } else LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("library_list"), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (works.isEmpty()) item { EmptyCard("让音声，回到自己的书架", "选择手机上的音声目录，自动按 RJ 与目录结构整理作品。", "选择音声目录", add) }
            else if (filtered.isEmpty()) item { Text("没有匹配的作品，试试清除筛选。") }
            items(filtered, key = { it.id }) { work ->
                WorkCard(work, "${counts[work.id] ?: 0} 条轨道" + if (work.id in heard) " · 可继续收听" else "", { open(work.id) })
            }
        }
    }
    if (filterOpen) AlertDialog(onDismissRequest = closeFilter, title = { Text("筛选作品") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            LibraryFilterToggle("最近收听", "recent", recent) { recent = it }
            LibraryFilterToggle("收藏", "favorite", favoriteOnly) { favoriteOnly = it }
            LibraryFilterToggle("待整理", "review", reviewOnly) { reviewOnly = it }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("声优（同类任一匹配）", fontWeight = FontWeight.Bold)
            (works.flatMap { names(it.voices) }.distinct().sorted() + "未填写").forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(name in voices, { voices = if (it) voices + name else voices - name }); Text(name) }
            }
            Text("社团（与声优同时匹配）", fontWeight = FontWeight.Bold)
            (works.flatMap { names(it.circles) }.distinct().sorted() + "未填写").forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(name in circles, { circles = if (it) circles + name else circles - name }); Text(name) }
            }
            Text("中文标签（同类任一匹配）", fontWeight = FontWeight.Bold)
            (works.flatMap { tagNames(it.tags) }.distinct().sorted() + "未填写").forEach { name ->
                Row(Modifier.fillMaxWidth().testTag("tag_filter_$name").clickable { selectedTags = if (name in selectedTags) selectedTags - name else selectedTags + name }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(name in selectedTags, { selectedTags = if (it) selectedTags + name else selectedTags - name }); Text(name)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = closeFilter) { Text("应用") } }, dismissButton = { TextButton(onClick = {
        voices = emptySet(); circles = emptySet(); selectedTags = emptySet(); recent = false; favoriteOnly = false; reviewOnly = false; closeFilter()
    }) { Text("清除筛选") } })
}

@Composable
private fun LibraryFilterToggle(label: String, key: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("library_filter_$key").clickable { onChange(!selected) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, onChange); Text(label)
    }
}

@Composable
private fun WorkDetail(vm: LibraryViewModel, work: Work, tracks: List<Track>, playlists: List<Playlist>, progress: List<Progress>, showPlayer: () -> Unit) {
    val context = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    var addTrack by remember { mutableStateOf<String?>(null) }
    var trackMenu by remember { mutableStateOf<Track?>(null) }
    var variant by remember(work.id) { mutableStateOf(vm.selectedVariant(work.id)) }
    var bonus by rememberSaveable(work.id) { mutableStateOf(false) }
    var resources by rememberSaveable(work.id) { mutableStateOf(false) }
    var coverDialog by remember { mutableStateOf(false) }
    var images by remember(work.id) { mutableStateOf(emptyList<Asset>()) }
    LaunchedEffect(work.id, work.autoCover) { images = vm.repository.dao.allAssets().filter { it.workId == work.id && it.type == "image" } }
    val available = tracks.filter { it.effectiveWorkId == work.id }
    val variants = available.filter { it.available && (resources || !it.resource) }.map { it.variant }.distinct().sorted()
    LaunchedEffect(variants) { if (variant !in variants) variant = variants.firstOrNull().orEmpty() }
    val visible = LibraryRepository.sorted(available.filter { variant.isEmpty() || it.variant == variant }.filter { resources || !it.resource })
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.cover(work, uri) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(work, Modifier.size(128.dp), pixels = 640); Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(work.rj.ifEmpty { "本地作品" }, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                    Text(work.title, style = MaterialTheme.typography.titleLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    if (work.voices.isNotBlank()) Text(work.voices, style = MaterialTheme.typography.bodySmall)
                    if (work.circles.isNotBlank()) Text(work.circles, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { coverDialog = true }) { Text("选择封面") }
            if (work.cover.isNotEmpty()) TextButton(onClick = { vm.resetCover(work) }) { Text("自动封面") }
            TextButton(onClick = { edit = true }) { Text("编辑信息") }
            IconButton(onClick = { vm.favorite(work) }) { Icon(if (work.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏") }
        } }
        if (work.review.isNotEmpty()) item { Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) { Text(work.review); TextButton(onClick = { vm.confirm(work) }) { Text("已确认此归组") } }
        } }
        if (work.tags.isNotBlank()) item { WorkTags(work.tags) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.playWork(work, variant, bonus, resources = resources); showPlayer() }) { Icon(Icons.Default.PlayArrow, null); Text(if (progress.any { it.workId == work.id }) "继续收听" else "播放作品") }
            OutlinedButton(onClick = { addTrack = "" }) { Text("加入列表") }
        } }
        if (progress.any { it.workId == work.id }) item { TextButton(onClick = { vm.playWork(work, variant, bonus, resume = false, resources = resources); showPlayer() }) { Text("从头开始") } }
        if (variants.size > 1) item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            variants.forEach { name -> FilterChip(variant == name, { variant = name; vm.selectVariant(work.id, name) }, label = { Text(name) }) }
        } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(bonus, { bonus = it }); Text("播放作品时包含特典") }
            if (available.any { it.resource }) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(resources, { resources = it }); Text("显示并允许播放程序资源候选") }
        }
        if (visible.isEmpty()) item { Text("没有普通音声轨道。可检查目录授权，或查看程序资源候选。") }
        items(visible, key = { it.id }) { track ->
            Card {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(enabled = track.available) { vm.playWork(work, variant, true, trackId = track.id, resources = resources); showPlayer() }.padding(vertical = 14.dp)) {
                        Text(track.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(track.section, if (!track.available) "文件缺失" else if (track.resource) "资源候选" else track.variant).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { trackMenu = track }) { Icon(Icons.Default.MoreVert, "轨道操作") }
                }
            }
        }
    }
    if (edit) EditWorkDialog(work, { edit = false }) { vm.saveWork(it); edit = false }
    if (coverDialog) AlertDialog(onDismissRequest = { coverDialog = false }, title = { Text("选择作品封面") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (images.isEmpty()) item { Text("当前作品没有图片候选，可以从手机选择图片文件。") }
            items(images, key = { it.uri }) { image ->
                Row(Modifier.fillMaxWidth().clickable { vm.cover(work, Uri.parse(image.uri)); coverDialog = false }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(work.copy(cover = image.uri), Modifier.size(68.dp)); Spacer(Modifier.width(12.dp)); Text(image.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { coverDialog = false; imagePicker.launch(arrayOf("image/*")) }) { Text("选择其他图片文件") } }, dismissButton = { TextButton(onClick = { coverDialog = false }) { Text("取消") } })
    if (addTrack != null) PlaylistPicker(vm, playlists, work.id, addTrack.orEmpty()) { addTrack = null }
    trackMenu?.let { track ->
        var destination by remember(track.id) { mutableStateOf(false) }
        if (destination) AlertDialog(onDismissRequest = { trackMenu = null }, title = { Text("移动到作品") }, text = {
            val works by vm.works.collectAsStateWithLifecycle()
            LazyColumn(Modifier.heightIn(max = 400.dp)) { items(works, key = { it.id }) { target ->
                TextButton(onClick = { vm.saveTrack(track.copy(manualWorkId = target.id)); trackMenu = null }) { Text(target.title) }
            } }
        }, confirmButton = { TextButton(onClick = { trackMenu = null }) { Text("取消") } })
        else AlertDialog(onDismissRequest = { trackMenu = null }, title = { Text("轨道操作") }, text = {
            Column {
                Text(track.name, maxLines = 2)
                TextButton(onClick = { addTrack = track.id; trackMenu = null }) { Text("加入播放列表") }
                TextButton(onClick = { vm.moveTrack(track, -1); trackMenu = null }) { Text("向上移动") }
                TextButton(onClick = { vm.moveTrack(track, 1); trackMenu = null }) { Text("向下移动") }
                TextButton(onClick = { destination = true }) { Text("调整作品归属") }
                TextButton(onClick = { vm.saveTrack(track.copy(manualWorkId = "", sortOrder = -1)); trackMenu = null }) { Text("恢复自动归属与顺序") }
            }
        }, confirmButton = { TextButton(onClick = { trackMenu = null }) { Text("关闭") } })
    }
}

@Composable
private fun PlaylistDetail(vm: LibraryViewModel, playlist: Playlist, entries: List<PlaylistEntry>, works: List<Work>, tracks: List<Track>, openWork: (String) -> Unit, close: () -> Unit, showPlayer: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(playlist.name, style = MaterialTheme.typography.headlineMedium) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.playPlaylist(playlist.id); showPlayer() }, enabled = entries.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Text("连续播放") }
            TextButton(onClick = { rename = true }) { Text("改名") }; TextButton(onClick = { delete = true }) { Text("删除列表") }
        } }
        if (entries.isEmpty()) item { Text("进入作品详情，点击“加入列表”添加内容。") }
        items(entries, key = { it.id }) { entry ->
            val work = works.find { it.id == entry.workId }
            val track = tracks.find { it.id == entry.trackId }
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = work != null) { openWork(entry.workId) }, verticalAlignment = Alignment.CenterVertically) {
                        Cover(work, Modifier.size(72.dp)); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (entry.trackId.isNotEmpty()) track?.name ?: "轨道不可用" else work?.title ?: "作品不可用", maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(if (entry.trackId.isEmpty()) "整部作品 · ${work?.rj.orEmpty()}" else "单条轨道 · ${work?.rj.orEmpty()}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row { TextButton(onClick = { vm.moveEntry(playlist.id, entry.id, -1) }) { Text("上移") }; TextButton(onClick = { vm.moveEntry(playlist.id, entry.id, 1) }) { Text("下移") }; TextButton(onClick = { vm.removeEntry(entry) }) { Text("移除") } }
                }
            }
        }
    }
    if (rename) NameDialog("列表名称", playlist.name, { rename = false }) { vm.renamePlaylist(playlist.copy(name = it)); rename = false }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除此列表？") }, text = { Text("源文件和作品库会保留。") }, confirmButton = {
        TextButton(onClick = { vm.action { vm.repository.deletePlaylist(playlist.id) }; close() }) { Text("删除") }
    }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@Composable
private fun PlaylistPicker(vm: LibraryViewModel, playlists: List<Playlist>, workId: String, trackId: String, close: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("加入播放列表") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            playlists.forEach { playlist -> TextButton(onClick = { vm.addToPlaylist(playlist, workId, trackId); close() }) { Text(playlist.name) } }
            OutlinedTextField(newName, { newName = it }, label = { Text("或创建新列表") }, singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = newName.isNotBlank(), onClick = {
        val list = Playlist(java.util.UUID.randomUUID().toString(), newName.trim())
        vm.action { vm.repository.dao.putPlaylist(list); vm.repository.dao.putEntries(listOf(PlaylistEntry(java.util.UUID.randomUUID().toString(), list.id, workId, trackId, 0))) }; close()
    }) { Text("创建并添加") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
private fun PlayingPage(vm: LibraryViewModel, state: PlaybackSnapshot, works: List<Work>, tracks: List<Track>) {
    val context = LocalContext.current
    val work = works.find { it.id == state.workId }
    val track = tracks.find { it.id == state.trackId }
    var queue by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && track != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.saveTrack(track.copy(subtitleUri = uri.toString()))
        }.onFailure { vm.notice.value = "字幕授权失败：${it.message}" }
    }
    Column(Modifier.fillMaxSize()) {
        PlaybackPages(Modifier.weight(1f), subtitleContent = { visible ->
            FullSubtitleReader(state, visible,
                onSeek = { position -> vm.seekToSubtitle(state.trackId, position) },
                onChooseSubtitle = { subtitlePicker.launch(arrayOf("*/*")) })
        }, coverContent = {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text("右滑查看完整字幕", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Cover(work, Modifier.fillMaxWidth(0.8f).aspectRatio(1f), pixels = 1024) }
                item { Text(state.workTitle, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge); Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 3) }
                if (state.error.isNotEmpty()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
                item { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.subtitle.ifEmpty { "♪" }, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp)); Text(state.subtitleStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } }
                item { Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { subtitlePicker.launch(arrayOf("*/*")) }, enabled = track != null) { Text("选择字幕") }
                    TextButton(onClick = { track?.let { vm.saveTrack(it.copy(subtitleUri = "")) } }, enabled = track != null) { Text("自动匹配") }
                    TextButton(onClick = { track?.let { vm.saveTrack(it.copy(subtitleOffset = it.subtitleOffset - 500)) } }, enabled = track != null) { Text("字幕提前 0.5s") }
                    TextButton(onClick = { track?.let { vm.saveTrack(it.copy(subtitleOffset = it.subtitleOffset + 500)) } }, enabled = track != null) { Text("字幕延后 0.5s") }
                } }
                if (track != null) item {
                    Text("字幕偏移 ${track.subtitleOffset}ms", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("自动", "UTF-8", "UTF-16LE", "Shift_JIS", "GB18030").forEach { encoding ->
                            FilterChip(track.subtitleEncoding == encoding, { vm.saveTrack(track.copy(subtitleEncoding = encoding)) }, label = { Text(encoding) }, modifier = Modifier.padding(end = 6.dp))
                        }
                    }
                }
            }
        })
        PlaybackControls(vm, state, { queue = true }, { settings = true })
    }
    if (queue) AlertDialog(onDismissRequest = { queue = false }, title = { Text("当前队列") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) { items(state.queue.size) { i ->
            TextButton(onClick = { vm.control { seekToDefaultPosition(i); prepare(); play() }; queue = false }) { Text("${if (i == state.index) "▶ " else ""}${i + 1}. ${state.queue[i]}") }
        } }
    }, confirmButton = { TextButton(onClick = { queue = false }) { Text("关闭") } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("播放设置") }, text = {
        Column {
            Text("播放速度")
            Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed -> TextButton(onClick = { vm.control { setPlaybackSpeed(speed) } }) { Text("${speed}×") } } }
            Text("睡眠定时")
            Row { listOf(0, 15, 30, 60).forEach { minutes -> TextButton(onClick = { vm.refreshPlayback(Bundle().apply { putInt("sleep", minutes) }) }) { Text(if (minutes == 0) "关闭" else "${minutes}分") } } }
            TextButton(onClick = { vm.refreshPlayback(Bundle().apply { putBoolean("endTrack", true) }) }) { Text("当前轨道结束后停止") }
            TextButton(onClick = { vm.control { pause(); stop(); clearMediaItems() }; settings = false }) { Text("停止播放") }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("完成") } })
}

@Composable
private fun PlaybackControls(vm: LibraryViewModel, state: PlaybackSnapshot, queue: () -> Unit, settings: () -> Unit) {
    var dragging by remember(state.trackId) { mutableStateOf<Float?>(null) }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Slider(value = dragging ?: state.position.toFloat().coerceIn(0f, state.duration.coerceAtLeast(0).toFloat()), onValueChange = { dragging = it },
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), onValueChangeFinished = {
                    dragging?.let { position -> vm.control { seekTo(position.toLong()) } }; dragging = null
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock((dragging ?: state.position.toFloat()).toLong()), style = MaterialTheme.typography.labelSmall)
                Text(clock(state.duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(onClick = { vm.control { seekToPreviousMediaItem() } }) { Icon(Icons.Default.SkipPrevious, "上一轨") }
                IconButton(onClick = { vm.control { seekTo((currentPosition - 10_000).coerceAtLeast(0)) } }) { Icon(Icons.Default.Replay10, "后退十秒") }
                FilledIconButton(onClick = { vm.control { if (isPlaying) pause() else { prepare(); play() } } }, Modifier.size(60.dp)) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停", Modifier.size(34.dp)) }
                IconButton(onClick = { vm.control { seekTo((currentPosition + 10_000).coerceAtMost(duration.coerceAtLeast(0))) } }) { Icon(Icons.Default.Forward10, "前进十秒") }
                IconButton(onClick = { vm.control { seekToNextMediaItem() } }) { Icon(Icons.Default.SkipNext, "下一轨") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = queue) { Icon(Icons.Default.QueueMusic, null); Text("队列") }
                TextButton(onClick = settings) { Text("${state.speed}× · 定时") }
                TextButton(onClick = { vm.control { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE; Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF } } }) {
                    Text(when (state.repeat) { Player.REPEAT_MODE_ONE -> "单轨循环"; Player.REPEAT_MODE_ALL -> "队列循环"; else -> "顺序播放" })
                }
            }
            if (state.sleepMinutes != 0) Text(if (state.sleepMinutes < 0) "当前轨道结束后停止" else "约 ${state.sleepMinutes} 分钟后停止", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun SettingsPage(vm: LibraryViewModel, sources: List<Source>, addSource: (Boolean) -> Unit, scanMessage: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var overlay by remember { mutableStateOf(prefs.getBoolean("overlay", false)) }
    var locked by remember { mutableStateOf(prefs.getBoolean("overlayLocked", false)) }
    var size by remember { mutableFloatStateOf(prefs.getFloat("overlaySize", 20f)) }
    var alpha by remember { mutableFloatStateOf(prefs.getFloat("overlayAlpha", 0.9f)) }
    var removeSource by remember { mutableStateOf<Source?>(null) }
    var catalogOpen by remember { mutableStateOf(false) }
    val automatic by vm.automaticMetadata.collectAsStateWithLifecycle()
    val metadata by vm.metadata.state.collectAsStateWithLifecycle()
    val stats by vm.creditStats.collectAsStateWithLifecycle()
    val catalog by vm.creditCatalog.collectAsStateWithLifecycle()
    val attempts by vm.creditAttempts.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("DLsite 社团、声优与标签", style = MaterialTheme.typography.titleLarge) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动补全缺失信息", Modifier.weight(1f)); Switch(automatic, vm::setAutomaticMetadata)
        } }
        item { Text("按第一个 RJ 编号补全社团、多人声优和官方简体中文标签，保留已有署名。旧版缓存缺少标签时会查询一次中文页面，此后复用总库。", style = MaterialTheme.typography.bodySmall) }
        item { Text("本地总库：${stats.works} 部作品 · ${stats.circles} 个社团 · ${stats.voices} 个声优署名", fontWeight = FontWeight.SemiBold) }
        item { Text(metadata.message, style = MaterialTheme.typography.bodySmall) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::syncMetadata, enabled = !metadata.running) { Text("补全缺失信息") }
            if (metadata.running) TextButton(onClick = vm::cancelMetadata) { Text("暂停") }
            else TextButton(onClick = { catalogOpen = true }) { Text("查看总库") }
        } }
        item { Text("联网串行进行，每次请求至少间隔 10 秒。单个作品失败后记录原因并跳过，继续下一个；下次补全可重试失败项。APK 不设置失败冷却，服务器的 Retry-After 只作提示。已成功的缓存会保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (attempts.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("最近未完成的查询", style = MaterialTheme.typography.labelMedium)
            attempts.take(5).forEach { attempt -> Text("${attempt.rj}：${attempt.message}" + if (attempt.retryAfter > 0) "\n服务器建议等待至：${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(attempt.retryAfter))}" else "", style = MaterialTheme.typography.bodySmall) }
        } }
        item { HorizontalDivider() }
        item { Text("文件来源", style = MaterialTheme.typography.titleLarge) }
        item { Row { OutlinedButton(onClick = { addSource(false) }) { Text("添加音声目录") }; Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { addSource(true) }) { Text("添加封面集") } } }
        item { Text("请选择具体音声子目录，例如 Download/DLsite。系统可能不允许选择整个 Download 或存储根目录。", style = MaterialTheme.typography.bodySmall) }
        items(sources, key = { it.uri }) { source -> Card {
            Column(Modifier.padding(12.dp)) {
                Text(source.name + if (source.coversOnly) " · 封面目录" else " · 音声目录", fontWeight = FontWeight.SemiBold)
                Text(source.uri, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                Row { TextButton(onClick = { vm.scan(source) }) { Text("重新扫描") }; TextButton(onClick = { addSource(source.coversOnly) }) { Text("重新授权") }; TextButton(onClick = { removeSource = source }) { Text("移除来源") } }
            }
        } }
        if (scanMessage.isNotEmpty()) item { Text(scanMessage, style = MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider(); Text("桌面歌词", style = MaterialTheme.typography.titleLarge) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("在其他应用上方显示字幕", Modifier.weight(1f))
            Switch(overlay, { enabled ->
                if (enabled && !Settings.canDrawOverlays(context)) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                    vm.notice.value = "授权返回后，请再次打开桌面歌词开关"
                } else { overlay = enabled; vm.settings { putBoolean("overlay", enabled) } }
            })
        } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("锁定位置并触摸穿透", Modifier.weight(1f)); Switch(locked, { locked = it; vm.settings { putBoolean("overlayLocked", it) } }) } }
        item { Text("可随时回到这里关闭歌词或解除锁定。锁屏与熄屏时自动隐藏。", style = MaterialTheme.typography.bodySmall) }
        item { Text("字号 ${size.toInt()}"); Slider(size, { size = it }, valueRange = 14f..36f, onValueChangeFinished = { vm.settings { putFloat("overlaySize", size) } }) }
        item { Text("透明度 ${(alpha * 100).toInt()}%"); Slider(alpha, { alpha = it }, valueRange = 0.2f..1f, onValueChangeFinished = { vm.settings { putFloat("overlayAlpha", alpha) } }) }
        item { Row { TextButton(onClick = { vm.settings { putInt("overlayColor", android.graphics.Color.WHITE) } }) { Text("白色字幕") }; TextButton(onClick = { vm.settings { putInt("overlayColor", 0xFFFFD78D.toInt()) } }) { Text("暖色字幕") }; TextButton(onClick = { vm.settings { putInt("overlayX", 0); putInt("overlayY", 180) } }) { Text("重置位置") } } }
        item { HorizontalDivider(); Text("后台播放与兼容", style = MaterialTheme.typography.titleLarge) }
        item { Text("后台与熄屏播放由系统媒体服务提供。若手机的省电设置中断播放，可在应用信息中允许后台活动，并检查电池优化设置。不同系统的设置名称以实际界面为准。", style = MaterialTheme.typography.bodyMedium) }
        item { OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("打开应用系统设置") } }
        item { OutlinedButton(onClick = { context.startActivity(Intent(context, LegalActivity::class.java)) }) { Text("开源许可与隐私说明") } }
        item { Text("音声书架 ${BuildConfig.VERSION_NAME}\n本地整理 · Android 10+\n导入仅建立索引，列表操作不修改源文件。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
    if (catalogOpen) CreditCatalogDialog(catalog) { catalogOpen = false }
    removeSource?.let { source -> AlertDialog(onDismissRequest = { removeSource = null }, title = { Text("移除此来源？") }, text = { Text("已有收听记录会保留，该来源的文件将标记为不可用。") }, confirmButton = {
        TextButton(onClick = { vm.action { vm.repository.removeSource(source) }; removeSource = null }) { Text("移除") }
    }, dismissButton = { TextButton(onClick = { removeSource = null }) { Text("取消") } }) }
}

@Composable
private fun Cover(work: Work?, modifier: Modifier = Modifier, pixels: Int = 320) {
    val context = LocalContext.current
    val request = remember(work?.cover, work?.autoCover, pixels) { coverRequest(context, work, pixels) }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Headphones, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary); Text(work?.rj?.takeLast(4) ?: "音声", style = MaterialTheme.typography.labelSmall) }
        AsyncImage(model = request, imageLoader = context.imageLoader, contentDescription = "${work?.title.orEmpty()} 封面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkGridCard(work: Work, subtitle: String, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = click).testTag("grid_work_${work.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Cover(work, Modifier.fillMaxWidth().aspectRatio(4f / 3f), pixels = 640)
            if (work.rj.isNotBlank()) Surface(Modifier.padding(8.dp), color = Color.Black.copy(alpha = 0.68f), contentColor = Color.White, shape = RoundedCornerShape(7.dp)) {
                Text(work.rj, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (work.favorite) Icon(Icons.Default.Favorite, "已收藏", Modifier.align(Alignment.BottomEnd).padding(8.dp).size(18.dp), tint = Color.White)
        }
        Text(work.title, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Headphones, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            names(work.circles).forEach { CreditBadge(it, true) }
            names(work.voices).forEach { CreditBadge(it, false) }
        }
        if (work.tags.isNotBlank()) WorkTags(work.tags)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkTags(tags: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tagNames(tags).forEach { tag ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(6.dp)) {
                Text(tag, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CreditBadge(name: String, circle: Boolean) {
    Surface(color = if (circle) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(name, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = if (circle) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CreditCatalogDialog(entries: List<CatalogEntry>, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var circle by remember { mutableStateOf(true) }
    val visible = entries.filter { it.kind == (if (circle) "circle" else "voice") }.filter { it.name.contains(query, true) }
    AlertDialog(onDismissRequest = close, title = { Text("本地社团 / 声优总库") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("搜索署名") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(circle, { circle = true }, label = { Text("社团") })
                FilterChip(!circle, { circle = false }, label = { Text("声优") })
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (visible.isEmpty()) item { Text("暂无对应记录，导入作品后补全即可积累。") }
                items(visible, key = { it.id }) { entry -> Column {
                    Text(entry.name, fontWeight = FontWeight.SemiBold)
                    Text("已缓存 ${entry.workCount} 部作品的对应关系", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            Text("声优按官方署名保存，不推断别名或真实身份。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}
@Composable
private fun WorkCard(work: Work, subtitle: String, click: () -> Unit) {
    Card(onClick = click, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(work, Modifier.size(92.dp)); Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(work.rj.ifEmpty { "本地作品" }, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall)
                Text(work.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (work.voices.isNotEmpty()) Text(work.voices, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (work.tags.isNotBlank()) Text(tagNames(work.tags).joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable
private fun MiniPlayer(state: PlaybackSnapshot, open: () -> Unit, toggle: () -> Unit) {
    Surface(tonalElevation = 4.dp) { Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(start = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium); Text(state.workTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
        IconButton(onClick = toggle) { Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "播放或暂停") }
    } }
}
@Composable
private fun EmptyCard(title: String, body: String, button: String, action: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Default.LibraryMusic, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = action) { Text(button) }
    } }
}
@Composable
private fun NameDialog(title: String, initial: String, close: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名称") }) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim()) }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
@Composable
private fun EditWorkDialog(work: Work, close: () -> Unit, save: (Work) -> Unit) {
    var title by remember { mutableStateOf(work.title) }
    var voices by remember { mutableStateOf(work.voices) }
    var circles by remember { mutableStateOf(work.circles) }
    AlertDialog(onDismissRequest = close, title = { Text("编辑作品信息") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("作品名称") })
            OutlinedTextField(voices, { voices = it }, label = { Text("声优，多个用顿号分隔") })
            OutlinedTextField(circles, { circles = it }, label = { Text("社团，多个用顿号分隔") })
            Text("自动提取的人名也可以在这里确认或修正。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { save(work.copy(title = title.trim(), voices = voices.trim(), circles = circles.trim())) }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
}
private fun names(value: String) = value.split('、', ',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
private fun tagNames(value: String) = value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
private fun clock(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) }
