package com.xtreeroot.mb.ui

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.AccountSource
import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.LibrarySource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.toTrackMetadata
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.preferences.AppAppearance
import com.xtreeroot.mb.preferences.PlaybackMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class Tab(val chinese: String, val english: String) {
    Discover("发现", "Discover"), Search("搜索", "Search"), Library("资料库", "Library"), Account("我的", "Me");

    val icon get() = when (this) {
        Discover -> Icons.Default.Home
        Search -> Icons.Default.Search
        Library -> Icons.AutoMirrored.Filled.List
        Account -> Icons.Default.Person
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MBRoot(browser: BrowseModel = viewModel(), player: PlaybackModel = viewModel(), account: AccountModel = viewModel(),
                    library: LibraryModel = viewModel(), saved: SavedLibraryModel = viewModel(), plugins: PluginManagerModel = viewModel(),
                    pluginAccount: AccountModel = viewModel(key = "plugin-account"),
                    background: AppearanceBackgroundModel = viewModel(), nodeProfile: NodeProfileSyncModel = viewModel()) {
    val context = LocalContext.current
    val app = context.applicationContext as MBApplication
    val settings = app.settings
    val preferences by settings.values.collectAsStateWithLifecycle()
    val backgroundState by background.state.collectAsStateWithLifecycle()
    val trackPlaybackIssues by app.trackPlaybackIssues.values.collectAsStateWithLifecycle()
    val dark = when (preferences.appearance) {
        AppAppearance.System -> isSystemInDarkTheme()
        AppAppearance.Light -> false
        AppAppearance.Dark -> true
    }
    SideEffect {
        (context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val scope = rememberCoroutineScope()
    var actionTrack by rememberSaveable { mutableStateOf<String?>(null) }
    var exportTrack by rememberSaveable { mutableStateOf<String?>(null) }
    val tabState = rememberSaveableStateHolder()
    val openTrackActions: (Track) -> Unit = remember { { track -> actionTrack = track.toMetadataJson().toString() } }
    val exportAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) exportTrack?.let { saved.export(JSONObject(it).toTrackMetadata(), uri) }
        exportTrack = null
    }
    LaunchedEffect(saved) {
        saved.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    val downloadError by saved.transferError.collectAsStateWithLifecycle()
    LaunchedEffect(downloadError) {
        downloadError?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    val english = preferences.language.usesEnglish()
    CompositionLocalProvider(LocalMBEnglish provides english, LocalVideoBrowse provides (preferences.playbackMode == PlaybackMode.Video),
        LocalTrackPlaybackIssues provides trackPlaybackIssues) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFFF696F))
        else lightColorScheme(primary = Color(0xFFD1293B), background = Color(0xFFF5F5F7))) {
        var tab by rememberSaveable { mutableStateOf(Tab.Discover) }
        var category by rememberSaveable { mutableStateOf<DiscoveryCategory?>(null) }
        var showPlayer by rememberSaveable { mutableStateOf(false) }
        var showVideo by rememberSaveable { mutableStateOf(false) }
        var miniCover by remember { mutableStateOf<Rect?>(null) }
        var showQueue by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showPlugins by rememberSaveable { mutableStateOf(false) }
        val pluginState by plugins.state.collectAsStateWithLifecycle()
        val sourceNames = buildMap {
            if (preferences.builtInEnabled) put(app.bilibili.id, mb("哔哩哔哩", "Bilibili"))
            pluginState.installed.filter { it.enabled }.forEach { put(it.id, it.manifest.name) }
        }
        val sourceId = selectedSourceId(preferences.selectedSourceId, sourceNames.keys)
        val activeSource = sourceId?.let(app::musicSource)
        val pluginSource = activeSource as? PluginSource
        val searchUiState = key(activeSource) { rememberSaveableStateHolder() }
        LaunchedEffect(activeSource) {
            browser.activateSearch(activeSource)
            pluginAccount.activate(pluginSource)
            if (activeSource !== app.bilibili) account.cancelSignIn()
        }
        val catalog = remember(pluginSource) { PluginCatalogNavigator() }
        val page = catalog.current
        val visibleCatalog = page ?: catalog.home.takeIf { tab == Tab.Discover && pluginSource != null }
        LaunchedEffect(pluginState.requestedRepository) {
            if (pluginState.requestedRepository != null) showPlugins = true
        }
        var routes by rememberSaveable { mutableStateOf(arrayListOf<LibraryRoute>()) }
        val accountRoute = routes.lastOrNull().takeIf { tab == Tab.Account }
        val libraryPage by library.pages.state.collectAsStateWithLifecycle()
        val playback by player.playback.collectAsStateWithLifecycle()
        val focus = LocalFocusManager.current
        var seenVideoOpening by remember { mutableLongStateOf(playback.presentation.opening) }
        val user by account.account.collectAsStateWithLifecycle()
        val pluginUser by pluginAccount.account.collectAsStateWithLifecycle()
        val nodeBinding by settings.nodeBinding.collectAsStateWithLifecycle()
        val nodeProfileState by nodeProfile.state.collectAsStateWithLifecycle()
        val accountSource = activeSource as? AccountSource
        val selectedAccount = if (activeSource === app.bilibili) account else pluginAccount
        val visibleAccount = if (activeSource === app.bilibili) user else pluginUser
        val nodeTarget = NodeProfileTarget.verified(nodeBinding, sourceId, visibleAccount.verifiedFor(accountSource))
        fun currentNodeTarget(): NodeProfileTarget? {
            if (sourceId == null || app.musicSource(sourceId) !== activeSource) return null
            val latest = settings.values.value
            val available = buildList {
                if (latest.builtInEnabled) add(app.bilibili.id)
                app.installedPlugins.value.filter { it.enabled }.forEach { add(it.id) }
            }
            if (selectedSourceId(latest.selectedSourceId, available) != sourceId) return null
            return NodeProfileTarget.verified(settings.nodeBinding.value, sourceId, selectedAccount.account.value.verifiedFor(accountSource))
        }
        LaunchedEffect(nodeTarget) { nodeProfile.select(nodeTarget) { currentNodeTarget() == nodeTarget } }
        val webSignIn by app.pluginWebSignIn.requests.collectAsStateWithLifecycle(initialValue = null)
        val webLink by app.pluginWebLink.collectAsStateWithLifecycle()
        val playbackSignedIn = if (playback.current?.sourceId == app.bilibili.id) user.account != null
            else playback.current?.sourceId == pluginSource?.id && pluginUser.account != null
        val isWide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() >= 720.dp }
        LaunchedEffect(preferences.builtInEnabled) {
            account.activate(app.bilibili.takeIf { preferences.builtInEnabled })
            if (!preferences.builtInEnabled) {
                browser.sourceDisabled()
                category = null
                routes = arrayListOf()
            }
        }
        LaunchedEffect(user.account?.id, preferences.builtInEnabled) { if (preferences.builtInEnabled) browser.refresh() }
        LaunchedEffect(accountRoute, user.account?.id, preferences.builtInEnabled) {
            library.pages.open(accountRoute.takeIf { preferences.builtInEnabled })
        }
        LaunchedEffect(playback.connected, playback.current?.id, playback.presentation.mode) {
            if (playback.connected && playback.current == null) { showPlayer = false; showVideo = false }
            if (playback.presentation.mode == PlaybackMode.Music) showVideo = false
        }
        LaunchedEffect(playback.presentation.opening) {
            if (playback.presentation.opening != seenVideoOpening) {
                seenVideoOpening = playback.presentation.opening
                showPlayer = false
                showVideo = true
            }
        }
        val select: (List<Track>, Track) -> Unit = { tracks, track ->
            player.play(tracks, track)
        }
        val selectGroup: (List<Track>, Boolean) -> Unit = { tracks, shuffle ->
            tracks.firstOrNull { !it.isUnavailable }?.let { player.play(tracks, it, enableShuffle = shuffle) }
        }
        val showArtist: (String) -> Unit = { id ->
            showPlayer = false
            category = null
            tab = Tab.Account
            routes = arrayListOf(LibraryRoute.Creator(id))
        }
        val back: () -> Unit = {
            when {
                showPlugins -> showPlugins = false
                showQueue -> showQueue = false
                showSettings -> showSettings = false
                showPlayer -> showPlayer = false
                showVideo -> { showVideo = false; player.closeVideo() }
                page != null -> catalog.back()
                accountRoute != null -> routes = ArrayList(routes.dropLast(1))
                else -> category = null
            }
        }
        val switchTab: (Tab) -> Unit = { item -> tab = item; category = null; catalog.reset() }
        val switchSource: (String) -> Unit = { id ->
            if (id != sourceId) {
                player.clear()
                player.closeVideo()
                showPlayer = false
                showVideo = false
                showQueue = false
                actionTrack = null
                focus.clearFocus()
                category = null
                routes = arrayListOf()
                catalog.reset()
                browser.activateSearch(app.musicSource(id))
                settings.update { it.copy(selectedSourceId = id) }
            }
        }
        val refresh: () -> Unit = {
            val currentCategory = category
            when {
                visibleCatalog is CatalogDestination.Content -> visibleCatalog.pager.invalidate()
                currentCategory != null -> browser.refreshCategory(currentCategory)
                else -> browser.refresh()
            }
        }
        BackHandler(showPlayer || showVideo || showQueue || showSettings || showPlugins || category != null || accountRoute != null || page != null,
            onBack = back)
        if (showPlugins) {
            PluginsView(plugins, pluginState, removed = player::removeSource) { showPlugins = false }
        } else if (showSettings) {
            AppearanceBackdrop(backgroundState.image, dark) { surface ->
                SettingsView(preferences, settings::update, saved, browser, backgroundState, background, surface,
                    nodeProfileState, nodeTarget != null, {
                        val target = currentNodeTarget()
                        nodeProfile.publish(target) { currentNodeTarget() == target }
                    },
                    openPlugins = { showPlugins = true }) { showSettings = false }
            }
        } else if (showPlayer && playback.current != null) {
            CompositionLocalProvider(LocalTrackActions provides openTrackActions) {
                NowPlaying(player, playback, preferences, saved, signedIn = playbackSignedIn, close = { showPlayer = false },
                    showQueue = { showQueue = true }, showArtist = showArtist, openingCover = miniCover)
            }
        } else if (showVideo && playback.current != null) {
            CompositionLocalProvider(LocalTrackActions provides openTrackActions) {
                VideoDetailsScreen(player, playback, preferences, signedIn = playbackSignedIn,
                    browse = { showVideo = false; player.closeVideo() },
                    openMusic = { showVideo = false; player.closeVideo(); showPlayer = true })
            }
        } else AppearanceBackdrop(backgroundState.image, dark) { surface ->
            Scaffold(
                containerColor = surface,
                contentColor = MaterialTheme.colorScheme.onBackground,
                topBar = {
                    val discoverRoot = tab == Tab.Discover && page == null && category == null
                    val title = if (page != null && pluginSource != null) catalogTitle(page, pluginSource)
                        else category?.title() ?: accountRoute?.let { libraryPage.value.title ?: it.title() } ?: mb(tab.chinese, tab.english)
                    TopAppBar(title = {
                        if (discoverRoot && sourceId != null) SourceTabs(sourceNames, sourceId, switchSource)
                        else Text(title, maxLines = 1)
                    },
                        navigationIcon = {
                        if (category != null || accountRoute != null || page != null) IconButton(onClick = back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, mb("返回", "Back"))
                        }
                    }, actions = {
                        if (discoverRoot) {
                            IconButton(onClick = { switchTab(Tab.Search) }) { Icon(Icons.Default.Search, mb("搜索", "Search")) }
                        } else if (page == null && category == null && accountRoute == null && tab != Tab.Library && sourceId != null) {
                            SourceSwitcher(sourceNames, sourceId, switchSource)
                        }
                        if (tab == Tab.Discover || tab == Tab.Search) {
                            BrowsingModeSwitcher(preferences.playbackMode, compact = discoverRoot) { mode -> settings.update { it.copy(playbackMode = mode) } }
                        }
                        if (discoverRoot) {
                            DiscoverMore(refresh.takeIf { sourceId != null }, settings = { showSettings = true })
                        } else {
                            if (page is CatalogDestination.Content) TextButton(onClick = refresh) { Text(mb("刷新", "Refresh")) }
                            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, mb("设置", "Settings")) }
                        }
                    })
                },
                bottomBar = {
                    Column {
                        if (playback.current != null) {
                            MiniPlayer(playback, open = { showPlayer = true }, toggle = player::toggle,
                                queue = { showQueue = true }, coverBounds = { miniCover = it })
                        }
                        if (!isWide) NavigationBar {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(item == tab, onClick = { switchTab(item) },
                                    icon = { Icon(item.icon, null) }, label = { Text(mb(item.chinese, item.english)) })
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (isWide) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                        windowInsets = WindowInsets(0, 0, 0, 0)) {
                        Tab.entries.forEach { item ->
                            NavigationRailItem(item == tab, onClick = { switchTab(item) },
                                icon = { Icon(item.icon, null) }, label = { Text(mb(item.chinese, item.english)) })
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                            tabState.SaveableStateProvider(tab) {
                                CompositionLocalProvider(LocalTrackActions provides openTrackActions) {
                                    when {
                                        visibleCatalog != null && pluginSource != null -> PluginCatalogBrowser(pluginSource,
                                            visibleCatalog, catalog, playback.connected, select, player::play)
                                        tab != Tab.Library && sourceId == null -> EmptyMessage(mb("没有可用音源", "No available sources"))
                                        else -> when (tab) {
                                        Tab.Discover -> if (category == null) Discover(browser, playback.connected, select) { chosen ->
                                            category = chosen
                                            browser.open(chosen)
                                        } else CategoryView(browser, checkNotNull(category), playback.connected, select)
                                        Tab.Search -> {
                                            val id = activeSource?.id
                                            if (id != null) searchUiState.SaveableStateProvider(id) {
                                                Search(browser, playback.connected, id, pluginSource, pluginSource?.let { catalog::search }, select)
                                            }
                                            else EmptyMessage(mb("没有可用音源", "No available sources"))
                                        }
                                        Tab.Library -> LocalLibraryView(playback.connected, select, selectGroup, saved)
                                        Tab.Account -> if (pluginSource != null) PluginPersonalContent(pluginSource, catalog, pluginAccount, pluginUser)
                                        else if (accountRoute == null) AccountView(account) { route ->
                                            routes = ArrayList(routes + route)
                                        } else AccountLibraryView(library.pages, playback.connected, { routes = ArrayList(routes + it) }, select)
                                    }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val webRequest = webSignIn
        if (webRequest != null) key(webRequest.id) {
            PluginWebSignInView(webRequest, app.pluginWebSignIn) {
                pluginAccount.cancelSignIn()
                app.pluginWebSignIn.cancel(webRequest.id)
            }
        } else {
            val link = webLink
            if (link != null) key(link.id) {
                PluginWebLinkView(link) { app.closePluginWebLink(link.id) }
            } else if (pluginSource != null && pluginUser.signingIn) SignInDialog(pluginUser, pluginAccount)
        }
        if (showQueue) QueueSheet(player, playback, close = { showQueue = false })
        actionTrack?.let { metadata ->
            val track = JSONObject(metadata).toTrackMetadata()
            TrackActions(track, saved, playback.connected, close = { actionTrack = null },
                enqueue = { next -> player.enqueue(track, next) }, export = {
                    scope.launch {
                        try {
                            val name = saved.exportName(track)
                            exportTrack = metadata
                            exportAudio.launch(name)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { exportTrack = null; saved.report(failure) }
                    }
                }, creator = showArtist.takeIf { app.playbackSource(track.sourceId) is LibrarySource })
        }
    }
    }
}

private fun selectedSourceId(preferred: String?, available: Collection<String>): String? =
    preferred?.takeIf(available::contains) ?: available.firstOrNull()

@Composable
private fun DiscoverMore(refresh: (() -> Unit)?, settings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, mb("更多操作", "More Actions")) }
        DropdownMenu(expanded, { expanded = false }) {
            if (refresh != null) DropdownMenuItem(text = { Text(mb("刷新", "Refresh")) }, onClick = { expanded = false; refresh() })
            DropdownMenuItem(text = { Text(mb("设置", "Settings")) }, onClick = { expanded = false; settings() })
        }
    }
}

@Composable
private fun BrowsingModeSwitcher(mode: PlaybackMode, compact: Boolean, choose: (PlaybackMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = if (mode == PlaybackMode.Video) mb("视频模式", "Video Mode") else mb("听歌模式", "Music Mode")
    Box {
        if (compact) IconButton(onClick = { expanded = true }) {
            Icon(if (mode == PlaybackMode.Video) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.List, current)
        } else TextButton(onClick = { expanded = true }) { Text(current) }
        DropdownMenu(expanded, { expanded = false }) {
            PlaybackMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(if (option == PlaybackMode.Video) mb("视频模式", "Video Mode") else mb("听歌模式", "Music Mode")) },
                    onClick = { expanded = false; choose(option) })
            }
        }
    }
}

@Composable
private fun SourceSwitcher(sources: Map<String, String>, selected: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.widthIn(max = 124.dp)) {
            Text(sources.getValue(selected), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            sources.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; select(id) }) }
        }
    }
}
