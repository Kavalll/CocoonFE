package com.thorfeeds.catalog.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.thorfeeds.catalog.core.ArtWindow
import com.thorfeeds.catalog.core.ByteFormat
import com.thorfeeds.catalog.core.CatalogArt
import com.thorfeeds.catalog.core.CatalogDeduper
import com.thorfeeds.catalog.core.CatalogOrder
import com.thorfeeds.catalog.core.CatalogSort
import com.thorfeeds.catalog.core.CatalogRow
import com.thorfeeds.catalog.core.ParsedRelease
import com.thorfeeds.catalog.core.LocatedArchive
import com.thorfeeds.catalog.core.TitleCanonicalizer
import com.thorfeeds.catalog.core.UploadDates
import com.thorfeeds.catalog.data.SourceRecord
import com.thorfeeds.catalog.data.toParsed
import com.thorfeeds.catalog.download.DownloadPhase
import com.thorfeeds.catalog.download.DownloadSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogRoute(
    dualScreen: Boolean,
    onOpenDetail: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onChooseFolder: () -> Unit,
    onImportFeed: () -> Unit,
    onSolve: (sourceId: String, url: String) -> Unit,
) {
    val vm: CatalogViewModel = viewModel()
    val entities by vm.releases.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val onDevice by vm.onDeviceKeys.collectAsState()
    val folderLabel by vm.folderLabel.collectAsState()
    val folderProblem by vm.folderProblem.collectAsState()
    val folderUri by vm.folderUri.collectAsStateWithLifecycle()
    val download by vm.download.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.userMessages.collect { snackbar.showSnackbar(it) } }

    val errors = sources.mapNotNull { source ->
        source.lastError?.let { error -> "${source.label}: $error" }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Thor Feeds") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                actions = {
                    IconButton(onClick = onOpenDownloads) { Icon(Icons.Filled.Download, "Downloads") }
                    IconButton(onClick = onChooseFolder) { Icon(Icons.Filled.Folder, "Games folder") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Refresh feeds") }
                    IconButton(onClick = onOpenSources) { Icon(Icons.Filled.Settings, "Feeds") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (folderUri == null) {
                Banner("Choose the games folder GameHub Lite already uses. Downloads go there.")
                FilledTonalButton(onClick = onChooseFolder, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Choose games folder")
                }
            } else if (folderProblem != null) {
                Banner(folderProblem ?: "")
            } else if (!folderLabel.isNullOrBlank()) {
                Text(
                    "Games folder · $folderLabel",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            errors.forEach { message -> Banner(message) }
            sources.filter { it.needsSolve && it.isRemote }.forEach { source ->
                FilledTonalButton(
                    onClick = { onSolve(source.id, source.location) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text("Solve check · ${source.label}") }
            }
            val sort by vm.catalogSort.collectAsStateWithLifecycle()
            val artByTitle by vm.artByTitle.collectAsState()
            val allRows = remember(entities) { CatalogDeduper.rows(entities.map { it.toParsed() }) }
            val popularity = remember(artByTitle, sort) {
                if (sort != CatalogSort.POPULARITY) {
                    emptyMap()
                } else {
                    artByTitle.mapNotNull { (key, art) -> art.popularity?.let { key to it } }.toMap()
                }
            }
            val rows = remember(allRows, query, sort, popularity) {
                CatalogOrder.sort(
                    allRows.filter { CatalogDeduper.matchesQuery(it, query) },
                    sort,
                    popularity,
                )
            }
            val listState = rememberLazyListState()
            var artWindow by remember { mutableIntStateOf(ArtWindow.PAGE) }
            var artWindowKey by remember { mutableStateOf(sort to query) }
            if (artWindowKey != (sort to query)) {
                artWindowKey = sort to query
                artWindow = ArtWindow.PAGE
            }
            val rowCount = rows.size
            LaunchedEffect(listState, rowCount) {
                snapshotFlow {
                    val info = listState.layoutInfo
                    ArtWindow.Viewport(
                        lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                        visibleCount = info.visibleItemsInfo.size,
                        heightPx = info.viewportEndOffset - info.viewportStartOffset,
                    )
                }.collect { viewport ->
                    val next = ArtWindow.nextPage(artWindow, rowCount, viewport)
                    if (next != artWindow) artWindow = next
                }
            }
            val pageTitles = remember(rows, artWindow) {
                rows.take(artWindow).map { it.canonicalTitle }
            }
            LaunchedEffect(pageTitles) { vm.ensureArt(pageTitles) }
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search titles") },
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CatalogSort.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { vm.setCatalogSort(option) },
                        label = { Text(sortLabel(option)) },
                    )
                }
            }
            when {
                sources.isEmpty() && !refreshing -> EmptyCatalog(
                    title = "No feeds yet",
                    body = "Add a JSON feed URL or import a DownloadSourceFeed file. Nothing is built in.",
                    action = "Add a feed",
                    onAction = onOpenSources,
                )
                refreshing && rows.isEmpty() && query.isBlank() -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                rows.isEmpty() -> EmptyCatalog(
                    title = if (query.isBlank()) "No games in these feeds" else "No matches",
                    body = if (query.isBlank()) {
                        "The feeds loaded, but they did not contain any titles."
                    } else {
                        "Try a different search."
                    },
                    action = if (query.isBlank()) "Import a file" else "Clear search",
                    onAction = if (query.isBlank()) onImportFeed else ({ vm.setQuery("") }),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(rows, key = { _, row -> TitleCanonicalizer.identityKey(row.canonicalTitle) }) { index, row ->
                        val present = TitleCanonicalizer.identityKey(row.canonicalTitle) in onDevice
                        val active = download?.releaseId != null &&
                            row.all.any { it.id == download?.releaseId } &&
                            download?.terminal == false
                        CatalogCard(
                            row = row,
                            art = artByTitle[TitleCanonicalizer.identityKey(row.canonicalTitle)],
                            onDevice = present,
                            downloading = active,
                            onClick = {
                                vm.select(row.canonicalTitle)
                                onOpenDetail()
                            },
                            onFocused = {
                                if (ArtWindow.focusedIndexRequestsNext(index, artWindow, rows.size)) {
                                    artWindow = (artWindow + ArtWindow.PAGE).coerceAtMost(rows.size)
                                }
                                if (dualScreen) {
                                    vm.select(row.canonicalTitle)
                                    onOpenDetail()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    row: CatalogRow,
    art: CatalogArt?,
    onDevice: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
    onFocused: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Card(
        onClick = onClick,
        interactionSource = interaction,
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.onFocusChanged { state ->
            if (state.isFocused) onFocused?.invoke()
        },
    ) {
        Row(Modifier.fillMaxWidth()) {
            GameArt(title = row.canonicalTitle, art = art, modifier = Modifier.width(84.dp).height(126.dp))
            Column(Modifier.weight(1f).padding(14.dp)) {
                Text(row.canonicalTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(
                        row.latest.fileSizeLabel,
                        UploadDates.format(row.latest.uploadDate),
                        if (row.older.isEmpty()) "1 build" else "${row.all.size} builds",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onDevice || downloading) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onDevice) Badge("On device")
                        if (downloading) Badge("Downloading")
                    }
                }
            }
        }
    }
}

@Composable
private fun GameArt(title: String, art: CatalogArt?, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbWidth = with(density) { 84.dp.roundToPx() }.coerceAtLeast(1)
    val thumbHeight = with(density) { 126.dp.roundToPx() }.coerceAtLeast(1)
    Box(modifier.clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))) {
        ArtPlaceholder(title, Modifier.matchParentSize())
        val url = art?.capsuleUrl
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .size(thumbWidth, thumbHeight)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ArtPlaceholder(title: String, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "·",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun sortLabel(sort: CatalogSort): String {
    return when (sort) {
        CatalogSort.NEWEST -> "Newest"
        CatalogSort.NAME -> "Name"
        CatalogSort.SIZE -> "Size"
        CatalogSort.POPULARITY -> "Popularity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailRoute(
    showBack: Boolean,
    onBack: () -> Unit,
    onChooseFolder: () -> Unit = {},
    onDownloadStarted: () -> Unit = {},
) {
    val vm: CatalogViewModel = viewModel()
    val entities by vm.releases.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsState()
    val onDevice by vm.onDeviceKeys.collectAsState()
    val archivesByGame by vm.onDeviceArchives.collectAsState()
    val download by vm.download.collectAsState()
    val folderUri by vm.folderUri.collectAsStateWithLifecycle()
    val rows = remember(entities) { CatalogDeduper.rows(entities.map { it.toParsed() }) }
    val row = rows.firstOrNull { TitleCanonicalizer.identityKey(it.canonicalTitle) == selection?.let(TitleCanonicalizer::identityKey) }
    val present = row != null && TitleCanonicalizer.identityKey(row.canonicalTitle) in onDevice
    val deleteArchive by vm.deleteArchiveAfterExtract.collectAsStateWithLifecycle()
    val archives = row?.let { archivesByGame[TitleCanonicalizer.identityKey(it.canonicalTitle)].orEmpty() }.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(row?.canonicalTitle ?: "Game") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        if (row == null) {
            EmptyCatalog(
                title = "Select a game",
                body = "Pick a title on the catalog screen. This display shows the release, older builds, and download progress.",
                action = if (showBack) "Back to catalog" else "Waiting for a selection",
                onAction = onBack,
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(row.canonicalTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (present) Badge("On device") else Badge("Not in the games folder")
                Text(
                    "Latest release is kept in the catalog. Older builds stay available here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                row.all.forEachIndexed { index, release ->
                    ReleaseCard(
                        release = release,
                        latest = index == 0,
                        onDevice = present,
                        folderReady = folderUri != null,
                        download = download?.takeIf { it.releaseId == release.id },
                        busyElsewhere = download != null && download?.terminal == false && download?.releaseId != release.id,
                        onDownload = { id ->
                            vm.startDownload(id)
                            onDownloadStarted()
                        },
                        onCancel = vm::cancelDownload,
                        onChooseFolder = onChooseFolder,
                        onOpenZArchiver = vm::openZArchiver,
                        archives = if (index == 0) archives else emptyList(),
                        onExtract = if (index == 0 && archives.isNotEmpty()) {
                            { vm.extractExisting(release.id) }
                        } else {
                            null
                        },
                        deleteArchive = deleteArchive,
                        onDeleteArchive = vm::setDeleteArchiveAfterExtract,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseCard(
    release: ParsedRelease,
    latest: Boolean,
    onDevice: Boolean,
    folderReady: Boolean,
    download: DownloadSnapshot?,
    busyElsewhere: Boolean,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenZArchiver: (String, String) -> Unit,
    archives: List<LocatedArchive> = emptyList(),
    onExtract: (() -> Unit)? = null,
    deleteArchive: Boolean = false,
    onDeleteArchive: (Boolean) -> Unit = {},
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (latest) "Latest" else "Older build", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(release.rawTitle, style = MaterialTheme.typography.titleMedium)
            Text(
                "${release.fileSizeLabel} · ${UploadDates.format(release.uploadDate)} · ${release.uris.size} link${if (release.uris.size == 1) "" else "s"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (download != null && !download.terminal) {
                val mirror = if (download.phase == DownloadPhase.Extracting) {
                    "Extracting ${download.fileName ?: "archive"}"
                } else {
                    "Mirror ${download.mirrorIndex + 1} of ${download.mirrorCount} · ${download.mirrorLabel}"
                }
                Text(mirror, fontWeight = FontWeight.Medium)
                val fraction = download.totalBytes?.takeIf { it > 0 }?.let { total ->
                    (download.bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                }
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                Text(progressLabel(download))
                download.message?.let { message ->
                    FailureText(message, emphasize = message.contains("Phase:"))
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            } else {
                download?.message?.let { message ->
                    FailureText(
                        message,
                        emphasize = download.phase == DownloadPhase.Failed || download.phase == DownloadPhase.Cancelled,
                    )
                }
                if (download?.offerZArchiver == true && !download.archiveUri.isNullOrBlank()) {
                    OutlinedButton(onClick = {
                        onOpenZArchiver(download.archiveUri, download.fileName ?: "archive.rar")
                    }) { Text("Extract with ZArchiver") }
                }
                if (onExtract != null && archives.isNotEmpty() && !busyElsewhere) {
                    Text(
                        archiveCaption(archives),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onExtract, enabled = folderReady) { Text("Extract") }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Remove archive",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Switch(checked = deleteArchive, onCheckedChange = onDeleteArchive)
                    }
                }
                when {
                    !folderReady -> Button(onClick = onChooseFolder) { Text("Choose a games folder") }
                    release.uris.isEmpty() -> Text("This release has no download links.")
                    busyElsewhere -> Text("Another download is running.")
                    else -> Button(
                        onClick = { onDownload(release.id) },
                        enabled = release.id.isNotBlank(),
                    ) {
                        Text(if (onDevice) "Download again" else "Download")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesRoute(
    onBack: () -> Unit,
    onChooseFolder: () -> Unit,
    onImportFeed: () -> Unit,
    onSolve: (sourceId: String, url: String) -> Unit,
) {
    val vm: CatalogViewModel = viewModel()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val useSecond by vm.useSecondScreen.collectAsStateWithLifecycle()
    val deleteArchive by vm.deleteArchiveAfterExtract.collectAsStateWithLifecycle()
    val folderLabel by vm.folderLabel.collectAsState()
    val folderProblem by vm.folderProblem.collectAsState()
    var editing by remember { mutableStateOf<SourceRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SourceRecord?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.userMessages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Feeds") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("JSON feeds", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Paste your own feed URL or import a local file with the same shape. No feed is included with the app.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add URL")
                    }
                    FilledTonalButton(onClick = onImportFeed) { Text("Import JSON") }
                }
            }
            if (sources.isEmpty()) {
                item { Text("No feeds yet.") }
            }
            items(sources, key = { it.id }) { source ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(source.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (source.isRemote) "Remote URL" else "Local file",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(source.location, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        source.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (source.needsSolve && source.isRemote) {
                            Button(onClick = { onSolve(source.id, source.location) }) { Text("Solve check") }
                        }
                        Row {
                            if (source.isRemote) {
                                IconButton(onClick = { editing = source }) { Icon(Icons.Filled.Edit, "Edit URL") }
                            }
                            IconButton(onClick = { pendingDelete = source }) { Icon(Icons.Filled.Delete, "Remove feed") }
                        }
                    }
                }
            }
            item {
                Text("Games folder", style = MaterialTheme.typography.titleMedium)
                Text(folderLabel ?: "No folder chosen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                folderProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onChooseFolder) { Text(if (folderLabel == null) "Choose folder" else "Change folder") }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remove archive after extracting", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Off by default. After a zip or rar extracts into the game folder, delete it only when those files are present. A failed extract keeps the archive.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = deleteArchive, onCheckedChange = vm::setDeleteArchiveAfterExtract)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use the other screen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "When a second display is connected, keep the catalog here and open the selected game on the other display.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useSecond, onCheckedChange = vm::setUseSecondScreen)
                }
            }
        }
    }

    if (adding || editing != null) {
        UrlDialog(
            title = if (editing == null) "Add feed URL" else "Edit feed URL",
            initial = editing?.location.orEmpty(),
            onDismiss = { adding = false; editing = null },
            onSave = { url ->
                val current = editing
                if (current == null) vm.addRemote(url) else vm.updateRemote(current.id, url)
                adding = false
                editing = null
            },
        )
    }
    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${source.label}?") },
            text = { Text("Games already in the folder stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(source.id)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UrlDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                placeholder = { Text("https://example.test/feed") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyCatalog(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(999.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun FailureText(text: String, emphasize: Boolean) {
    val context = LocalContext.current
    SelectionContainer {
        Text(
            text,
            color = if (emphasize) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    TextButton(onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Thor Feeds download", text))
    }) {
        Text("Copy details")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsRoute(showBack: Boolean, onBack: () -> Unit) {
    val vm: CatalogViewModel = viewModel()
    val records by vm.downloadRecords.collectAsState()
    val live by vm.download.collectAsState()
    val rows = records.sortedByDescending { it.updatedAt }
    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.ButtonB) {
                onBack()
                true
            } else {
                false
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            EmptyCatalog(
                title = "No downloads yet",
                body = "Games you download show up here, including after Thor Feeds is closed.",
                action = if (showBack) "Back" else "Waiting",
                onAction = onBack,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.releaseId }) { record ->
                    DownloadRow(record, live, vm)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: com.thorfeeds.catalog.download.DownloadRecord,
    live: DownloadSnapshot?,
    vm: CatalogViewModel,
) {
    val current = live?.takeIf { it.releaseId == record.releaseId }
    val phase = current?.phase ?: record.toPhase()
    val bytes = current?.bytesRead ?: record.bytesRead
    val total = current?.totalBytes ?: record.totalBytes
    val fileName = current?.fileName ?: record.fileName
    val message = current?.message ?: record.message
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.canonicalTitle, style = MaterialTheme.typography.titleMedium)
            Text(downloadPhaseLabel(phase), fontWeight = FontWeight.Medium)
            Text(downloadProgress(fileName, bytes, total), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!phase.isSettled() && total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (!phase.isSettled()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (!message.isNullOrBlank() && (phase == DownloadPhase.Failed || phase == DownloadPhase.Cancelled || phase == DownloadPhase.Interrupted)) {
                FailureText(message, emphasize = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!phase.isSettled()) {
                    OutlinedButton(onClick = vm::cancelDownload) { Text("Cancel") }
                }
                if (phase == DownloadPhase.Failed || phase == DownloadPhase.Interrupted || phase == DownloadPhase.Cancelled) {
                    Button(onClick = { vm.retryDownload(record.releaseId) }) { Text("Retry") }
                }
                val archiveName = fileName
                if (phase == DownloadPhase.Completed && archiveName != null && com.thorfeeds.catalog.core.ArchiveLayout.formatOf(archiveName) != null) {
                    OutlinedButton(onClick = { vm.extractExisting(record.releaseId) }) { Text("Extract") }
                }
                if (phase.isSettled()) {
                    OutlinedButton(onClick = { vm.deleteDownload(record.releaseId) }) { Text("Delete") }
                }
            }
        }
    }
}

private fun DownloadPhase.isSettled(): Boolean {
    return this == DownloadPhase.Completed || this == DownloadPhase.Failed ||
        this == DownloadPhase.Cancelled || this == DownloadPhase.Interrupted
}

private fun downloadPhaseLabel(phase: DownloadPhase): String {
    return when (phase) {
        DownloadPhase.Connecting -> "Connecting"
        DownloadPhase.Running -> "Downloading"
        DownloadPhase.Extracting -> "Extracting"
        DownloadPhase.Completed -> "Finished"
        DownloadPhase.Failed -> "Failed"
        DownloadPhase.Cancelled -> "Cancelled"
        DownloadPhase.Interrupted -> "Interrupted"
    }
}

private fun downloadProgress(fileName: String?, bytes: Long, total: Long?): String {
    val name = fileName?.let { "$it · " }.orEmpty()
    val amount = if (total != null && total > 0) {
        "${ByteFormat.format(bytes)} of ${ByteFormat.format(total)}"
    } else if (bytes > 0) {
        ByteFormat.format(bytes)
    } else {
        "No bytes yet"
    }
    return name + amount
}

private fun archiveCaption(archives: List<LocatedArchive>): String {
    val names = archives.map { it.fileName }.distinct()
    return if (names.size == 1) {
        "${names.single()} is already in the game folder."
    } else {
        "${names.size} archives are already in the game folder."
    }
}

private fun progressLabel(download: DownloadSnapshot): String {
    val name = download.fileName?.let { "$it · " }.orEmpty()
    val amount = if (download.totalBytes != null && download.totalBytes > 0) {
        "${ByteFormat.format(download.bytesRead)} of ${ByteFormat.format(download.totalBytes)}"
    } else {
        ByteFormat.format(download.bytesRead)
    }
    return name + amount
}
