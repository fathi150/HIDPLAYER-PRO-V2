package com.hidplayer.pro

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hidplayer.pro.data.DeviceActivationManager
import com.hidplayer.pro.data.IptvRepository
import com.hidplayer.pro.model.*
import com.hidplayer.pro.player.TvPlayerEngine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var activationManager: DeviceActivationManager
    private lateinit var repository: IptvRepository
    private lateinit var playerEngine: TvPlayerEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        activationManager = DeviceActivationManager(this)
        repository = IptvRepository()
        playerEngine = TvPlayerEngine(this, activationManager.loadPlayerSettings())

        setContent {
            HidPlayerProV2App(
                activationManager = activationManager,
                repository = repository,
                playerEngine = playerEngine
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playerEngine.release()
    }
}

@Composable
fun HidPlayerProV2App(
    activationManager: DeviceActivationManager,
    repository: IptvRepository,
    playerEngine: TvPlayerEngine
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(activationManager.loadSavedStatus()) }
    var settings by remember { mutableStateOf(activationManager.loadPlayerSettings()) }
    var favorites by remember { mutableStateOf(activationManager.getFavorites()) }
    var screen by remember { mutableStateOf(if (status.isActive) AppScreen.HOME else AppScreen.ACTIVATION) }
    var previousScreen by remember { mutableStateOf(AppScreen.HOME) }

    var liveCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var liveChannels by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }

    var movieCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var movies by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }

    var seriesCategories by remember { mutableStateOf<List<CategoryItem>>(emptyList()) }
    var seriesList by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var selectedSeries by remember { mutableStateOf<MediaEntry?>(null) }
    var currentEpisodes by remember { mutableStateOf<List<EpisodeItem>>(emptyList()) }

    var activePlaylist by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var currentPlayIndex by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    fun loadAllContent() {
        scope.launch {
            isLoading = true
            val (lCats, lItems) = repository.loadLive(status, settings.vpnShieldEnabled)
            liveCategories = lCats
            liveChannels = lItems
            val (mCats, mItems) = repository.loadMovies(status, settings.vpnShieldEnabled)
            movieCategories = mCats
            movies = mItems
            val (sCats, sItems) = repository.loadSeries(status, settings.vpnShieldEnabled)
            seriesCategories = sCats
            seriesList = sItems
            isLoading = false
        }
    }

    LaunchedEffect(status.isActive) {
        if (status.isActive && liveChannels.isEmpty()) {
            loadAllContent()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF050814), Color(0xFF0D1B36), Color(0xFF050814))
                )
            )
    ) {
        when (screen) {
            AppScreen.ACTIVATION -> ActivationScreen(
                status = status,
                initialApiUrl = activationManager.getActivationApiUrl(),
                isLoading = isLoading,
                onCheckServer = { apiUrl ->
                    scope.launch {
                        isLoading = true
                        status = activationManager.verifyWithServer(apiUrl)
                        isLoading = false
                        if (status.isActive) {
                            screen = AppScreen.HOME
                            loadAllContent()
                        }
                    }
                },
                onManualXtream = { host, user, pass ->
                    activationManager.saveManualXtream(host, user, pass)
                    status = activationManager.loadSavedStatus()
                    screen = AppScreen.HOME
                    loadAllContent()
                },
                onManualM3u = { m3u ->
                    activationManager.saveManualM3u(m3u)
                    status = activationManager.loadSavedStatus()
                    screen = AppScreen.HOME
                    loadAllContent()
                }
            )

            AppScreen.HOME -> HomeDashboardV2(
                status = status,
                settings = settings,
                liveCount = liveChannels.size,
                movieCount = movies.size,
                seriesCount = seriesList.size,
                isLoading = isLoading,
                onOpenLive = { screen = AppScreen.LIVE_TV },
                onOpenMovies = { screen = AppScreen.MOVIES },
                onOpenSeries = { screen = AppScreen.SERIES },
                onToggleVpn = {
                    val updated = settings.copy(vpnShieldEnabled = !settings.vpnShieldEnabled)
                    settings = updated
                    activationManager.savePlayerSettings(updated)
                    playerEngine.applySettings(updated)
                },
                onOpenSettings = { screen = AppScreen.SETTINGS },
                onRefresh = { loadAllContent() }
            )

            AppScreen.LIVE_TV -> MediaBrowserScreen(
                title = "البث المباشر (LIVE TV)",
                accent = Color(0xFF00E5FF),
                categories = liveCategories,
                items = liveChannels,
                favorites = favorites,
                onToggleFavorite = { favorites = activationManager.toggleFavorite(it) },
                onSelectItem = { list, idx ->
                    previousScreen = AppScreen.LIVE_TV
                    activePlaylist = list
                    currentPlayIndex = idx
                    currentTitle = list[idx].name
                    playerEngine.playStream(list[idx].streamUrl)
                    screen = AppScreen.PLAYER
                },
                onBack = { screen = AppScreen.HOME }
            )

            AppScreen.MOVIES -> MediaBrowserScreen(
                title = "الأفلام (MOVIES)",
                accent = Color(0xFFF59E0B),
                categories = movieCategories,
                items = movies,
                favorites = favorites,
                onToggleFavorite = { favorites = activationManager.toggleFavorite(it) },
                onSelectItem = { list, idx ->
                    previousScreen = AppScreen.MOVIES
                    activePlaylist = list
                    currentPlayIndex = idx
                    currentTitle = list[idx].name
                    playerEngine.playStream(list[idx].streamUrl)
                    screen = AppScreen.PLAYER
                },
                onBack = { screen = AppScreen.HOME }
            )

            AppScreen.SERIES -> MediaBrowserScreen(
                title = "المسلسلات (SERIES)",
                accent = Color(0xFFA855F7),
                categories = seriesCategories,
                items = seriesList,
                favorites = favorites,
                onToggleFavorite = { favorites = activationManager.toggleFavorite(it) },
                onSelectItem = { list, idx ->
                    val chosen = list[idx]
                    selectedSeries = chosen
                    scope.launch {
                        isLoading = true
                        currentEpisodes = repository.loadSeriesEpisodes(status, chosen.id, settings.vpnShieldEnabled)
                        isLoading = false
                        screen = AppScreen.SERIES_DETAILS
                    }
                },
                onBack = { screen = AppScreen.HOME }
            )

            AppScreen.SERIES_DETAILS -> SeriesEpisodesScreen(
                seriesName = selectedSeries?.name ?: "الحلقات",
                episodes = currentEpisodes,
                isLoading = isLoading,
                onPlayEpisode = { ep ->
                    previousScreen = AppScreen.SERIES_DETAILS
                    activePlaylist = currentEpisodes.mapIndexed { i, e ->
                        MediaEntry(e.id, e.title, e.streamUrl, "", number = i + 1)
                    }
                    currentPlayIndex = currentEpisodes.indexOf(ep).coerceAtLeast(0)
                    currentTitle = ep.title
                    playerEngine.playStream(ep.streamUrl)
                    screen = AppScreen.PLAYER
                },
                onBack = { screen = AppScreen.SERIES }
            )

            AppScreen.PLAYER -> FullscreenPlayerV2(
                playerEngine = playerEngine,
                title = currentTitle,
                settings = settings,
                onCycleAspectRatio = {
                    val next = (settings.aspectRatioMode + 1) % 3
                    settings = settings.copy(aspectRatioMode = next)
                    activationManager.savePlayerSettings(settings)
                },
                onToggleVpn = {
                    val updated = settings.copy(vpnShieldEnabled = !settings.vpnShieldEnabled)
                    settings = updated
                    activationManager.savePlayerSettings(updated)
                    playerEngine.applySettings(updated)
                },
                onNext = {
                    if (activePlaylist.isNotEmpty()) {
                        currentPlayIndex = (currentPlayIndex + 1) % activePlaylist.size
                        currentTitle = activePlaylist[currentPlayIndex].name
                        playerEngine.playStream(activePlaylist[currentPlayIndex].streamUrl)
                    }
                },
                onPrev = {
                    if (activePlaylist.isNotEmpty()) {
                        currentPlayIndex = if (currentPlayIndex - 1 < 0) activePlaylist.lastIndex else currentPlayIndex - 1
                        currentTitle = activePlaylist[currentPlayIndex].name
                        playerEngine.playStream(activePlaylist[currentPlayIndex].streamUrl)
                    }
                },
                onBack = {
                    playerEngine.stop()
                    screen = previousScreen
                }
            )

            AppScreen.SETTINGS -> PlayerSettingsScreen(
                settings = settings,
                onUpdateSettings = { updated ->
                    settings = updated
                    activationManager.savePlayerSettings(updated)
                    playerEngine.applySettings(updated)
                },
                onOpenActivation = { screen = AppScreen.ACTIVATION },
                onBack = { screen = AppScreen.HOME }
            )
        }
    }
}

@Composable
fun TvCardButton(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF00E5FF),
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .background(
                color = if (focused) accent else Color(0xFF121D35),
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else accent.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .focusable()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = if (focused) Color.Black else Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = if (focused) Color(0xFF111827) else Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun HomeDashboardV2(
    status: ActivationStatus,
    settings: PlayerSettings,
    liveCount: Int,
    movieCount: Int,
    seriesCount: Int,
    isLoading: Boolean,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onToggleVpn: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header with Bold H Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color(0xFF00E5FF), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("H", color = Color.Black, fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
                Column {
                    Text("HIDPLAYER PRO", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Text("V2.0 • ANDROID TV EDITION", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("ID: ${status.deviceId}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("الانتهاء: ${status.expiryDate}", color = Color(0xFF4ADE80), fontSize = 13.sp)
                Text(
                    text = if (settings.vpnShieldEnabled) "VPN Shield: مفعل (1.1.1.1)" else "VPN Shield: متوقف",
                    color = if (settings.vpnShieldEnabled) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }

        // Main 3 Big Cards (Live TV, Movies, Series)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            TvCardButton(
                title = "البث المباشر",
                subtitle = if (isLoading) "جاري التحميل..." else "$liveCount قناة",
                modifier = Modifier.weight(1f).height(155.dp),
                accent = Color(0xFF00E5FF),
                onClick = onOpenLive
            )
            TvCardButton(
                title = "الأفلام",
                subtitle = if (isLoading) "جاري التحميل..." else "$movieCount فيلم",
                modifier = Modifier.weight(1f).height(155.dp),
                accent = Color(0xFFF59E0B),
                onClick = onOpenMovies
            )
            TvCardButton(
                title = "المسلسلات",
                subtitle = if (isLoading) "جاري التحميل..." else "$seriesCount مسلسل",
                modifier = Modifier.weight(1f).height(155.dp),
                accent = Color(0xFFA855F7),
                onClick = onOpenSeries
            )
        }

        // Bottom Quick Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvCardButton(
                title = if (settings.vpnShieldEnabled) "درع VPN: شغال ✓" else "تفعيل درع VPN",
                subtitle = "لتجاوز التقطيع",
                modifier = Modifier.weight(1f).height(80.dp),
                accent = if (settings.vpnShieldEnabled) Color(0xFF22C55E) else Color(0xFFEF4444),
                onClick = onToggleVpn
            )
            TvCardButton(
                title = "إعدادات المشغل",
                subtitle = "Buffer • Decoder • السيرفر",
                modifier = Modifier.weight(1f).height(80.dp),
                accent = Color(0xFF38BDF8),
                onClick = onOpenSettings
            )
            TvCardButton(
                title = "تحديث المحتوى",
                subtitle = "تحديث القنوات والأفلام",
                modifier = Modifier.weight(1f).height(80.dp),
                accent = Color(0xFF10B981),
                onClick = onRefresh
            )
        }
    }
}

@Composable
fun MediaBrowserScreen(
    title: String,
    accent: Color,
    categories: List<CategoryItem>,
    items: List<MediaEntry>,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onSelectItem: (List<MediaEntry>, Int) -> Unit,
    onBack: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(items, selectedCategory, searchQuery, favorites) {
        items.filter { item ->
            val matchCat = when (selectedCategory) {
                "ALL" -> true
                "FAV" -> favorites.contains(item.id)
                else -> item.categoryId == selectedCategory
            }
            val matchSearch = searchQuery.isBlank() || item.name.contains(searchQuery, ignoreCase = true)
            matchCat && matchSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    onBack(); true
                } else false
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = accent, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("بحث سريع...") },
                singleLine = true,
                modifier = Modifier.width(320.dp)
            )
            TvCardButton(title = "رجوع", modifier = Modifier.width(120.dp).height(52.dp), accent = accent, onClick = onBack)
        }

        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LazyColumn(
                modifier = Modifier
                    .width(250.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF0E172A), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(categories) { _, cat ->
                    TvCardButton(
                        title = cat.name,
                        modifier = Modifier.fillMaxWidth(),
                        accent = if (cat.id == selectedCategory) accent else Color(0xFF334155),
                        onClick = { selectedCategory = cat.id }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF0E172A), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(filtered) { idx, item ->
                    val isFav = favorites.contains(item.id)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvCardButton(
                            title = "${item.number}. ${item.name}",
                            subtitle = if (item.rating.isNotBlank()) "★ ${item.rating}" else "",
                            modifier = Modifier.weight(1f),
                            accent = accent,
                            onClick = { onSelectItem(filtered, idx) }
                        )
                        TvCardButton(
                            title = if (isFav) "★" else "☆",
                            modifier = Modifier.width(64.dp),
                            accent = if (isFav) Color(0xFFFACC15) else Color(0xFF475569),
                            onClick = { onToggleFavorite(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesEpisodesScreen(
    seriesName: String,
    episodes: List<EpisodeItem>,
    isLoading: Boolean,
    onPlayEpisode: (EpisodeItem) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    onBack(); true
                } else false
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(seriesName, color = Color(0xFFA855F7), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            TvCardButton(title = "رجوع", modifier = Modifier.width(120.dp), accent = Color(0xFFA855F7), onClick = onBack)
        }

        if (isLoading) {
            Text("جاري تحميل الحلقات...", color = Color.White, fontSize = 18.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(episodes) { _, ep ->
                    TvCardButton(
                        title = ep.title,
                        modifier = Modifier.fillMaxWidth(),
                        accent = Color(0xFFA855F7),
                        onClick = { onPlayEpisode(ep) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerSettingsScreen(
    settings: PlayerSettings,
    onUpdateSettings: (PlayerSettings) -> Unit,
    onOpenActivation: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK && it.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    onBack(); true
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("إعدادات المشغل والثبات (HIDPLAYER PRO)", color = Color(0xFF00E5FF), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            TvCardButton(title = "رجوع", modifier = Modifier.width(130.dp), onClick = onBack)
        }

        Text("1. التخزين المؤقت (Buffer Mode):", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            TvCardButton(
                title = "سريع (Fast Zapping)",
                subtitle = "تنقل فوري بين القنوات",
                modifier = Modifier.weight(1f),
                accent = if (settings.bufferMode == "FAST") Color(0xFF22C55E) else Color(0xFF334155),
                onClick = { onUpdateSettings(settings.copy(bufferMode = "FAST")) }
            )
            TvCardButton(
                title = "متوازن (Balanced)",
                subtitle = "موصى به",
                modifier = Modifier.weight(1f),
                accent = if (settings.bufferMode == "BALANCED") Color(0xFF22C55E) else Color(0xFF334155),
                onClick = { onUpdateSettings(settings.copy(bufferMode = "BALANCED")) }
            )
            TvCardButton(
                title = "ضد التقطيع (Anti-Freeze)",
                subtitle = "للإنترنت الضعيف",
                modifier = Modifier.weight(1f),
                accent = if (settings.bufferMode == "ANTI_FREEZE") Color(0xFF22C55E) else Color(0xFF334155),
                onClick = { onUpdateSettings(settings.copy(bufferMode = "ANTI_FREEZE")) }
            )
        }

        Text("2. معالج الفيديو (Decoder) ودرع VPN:", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            TvCardButton(
                title = "Hardware Decoder",
                subtitle = "الأسرع للـ 4K و FHD",
                modifier = Modifier.weight(1f),
                accent = if (settings.decoderMode == "HARDWARE") Color(0xFF00E5FF) else Color(0xFF334155),
                onClick = { onUpdateSettings(settings.copy(decoderMode = "HARDWARE")) }
            )
            TvCardButton(
                title = "Software Decoder",
                subtitle = "يحل مشاكل تجمد الصورة",
                modifier = Modifier.weight(1f),
                accent = if (settings.decoderMode == "SOFTWARE") Color(0xFF00E5FF) else Color(0xFF334155),
                onClick = { onUpdateSettings(settings.copy(decoderMode = "SOFTWARE")) }
            )
            TvCardButton(
                title = if (settings.vpnShieldEnabled) "درع VPN: مفعل ✓" else "درع VPN: متوقف",
                subtitle = "Cloudflare 1.1.1.1 DoH",
                modifier = Modifier.weight(1f),
                accent = if (settings.vpnShieldEnabled) Color(0xFF22C55E) else Color(0xFFEF4444),
                onClick = { onUpdateSettings(settings.copy(vpnShieldEnabled = !settings.vpnShieldEnabled)) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        TvCardButton(
            title = "إدارة السيرفر والتفعيل (Xtream / M3U / Device ID)",
            modifier = Modifier.fillMaxWidth(),
            accent = Color(0xFFF59E0B),
            onClick = onOpenActivation
        )
    }
}

@Composable
fun FullscreenPlayerV2(
    playerEngine: TvPlayerEngine,
    title: String,
    settings: PlayerSettings,
    onCycleAspectRatio: () -> Unit,
    onToggleVpn: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onBack: () -> Unit
) {
    val resizeMode = when (settings.aspectRatioMode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    val ratioLabel = when (settings.aspectRatioMode) {
        1 -> "ملء الشاشة"
        2 -> "تكبير Zoom"
        else -> "أصلي Fit"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { onNext(); true }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { onPrev(); true }
                        KeyEvent.KEYCODE_BACK -> { onBack(); true }
                        else -> false
                    }
                } else false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerEngine.exoPlayer
                    useController = true
                    controllerAutoShow = true
                    this.resizeMode = resizeMode
                }
            },
            update = { view ->
                view.player = playerEngine.exoPlayer
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xBB000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvCardButton(
                    title = "الشاشة: $ratioLabel",
                    modifier = Modifier.height(48.dp),
                    accent = Color(0xFF00E5FF),
                    onClick = onCycleAspectRatio
                )
                TvCardButton(
                    title = if (settings.vpnShieldEnabled) "VPN ✓" else "VPN ✕",
                    modifier = Modifier.height(48.dp),
                    accent = if (settings.vpnShieldEnabled) Color(0xFF22C55E) else Color(0xFFEF4444),
                    onClick = onToggleVpn
                )
            }
        }
    }
}

@Composable
fun ActivationScreen(
    status: ActivationStatus,
    initialApiUrl: String,
    isLoading: Boolean,
    onCheckServer: (String) -> Unit,
    onManualXtream: (String, String, String) -> Unit,
    onManualM3u: (String) -> Unit
) {
    var apiUrl by remember { mutableStateOf(initialApiUrl) }
    var host by remember { mutableStateOf(status.host) }
    var user by remember { mutableStateOf(status.username) }
    var pass by remember { mutableStateOf(status.password) }
    var m3u by remember { mutableStateOf(status.m3uUrl) }

    Row(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF101A30), RoundedCornerShape(16.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("HIDPLAYER PRO V2", color = Color(0xFF00E5FF), fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("معرف الجهاز (Device ID):", color = Color.Gray, fontSize = 14.sp)
            Text(status.deviceId, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text("الصلاحية: ${status.expiryDate}", color = Color(0xFF4ADE80), fontSize = 15.sp)

            OutlinedTextField(
                value = apiUrl,
                onValueChange = { apiUrl = it },
                label = { Text("رابط سيرفر التفعيل") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            TvCardButton(
                title = if (isLoading) "جاري التحقق..." else "تفعيل عبر السيرفر",
                modifier = Modifier.fillMaxWidth(),
                onClick = { onCheckServer(apiUrl) }
            )

            if (status.message.isNotBlank()) {
                Text(status.message, color = Color(0xFFFACC15), fontSize = 14.sp)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF101A30), RoundedCornerShape(16.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("إدخال سيرفر مباشر (Xtream / M3U)", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host (http://domain:port)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TvCardButton(title = "حفظ وتشغيل Xtream", modifier = Modifier.fillMaxWidth()) {
                if (host.isNotBlank()) onManualXtream(host, user, pass)
            }
            HorizontalDivider(color = Color(0xFF283550))
            OutlinedTextField(value = m3u, onValueChange = { m3u = it }, label = { Text("رابط M3U مباشر") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TvCardButton(title = "حفظ وتشغيل M3U", modifier = Modifier.fillMaxWidth(), accent = Color(0xFF22C55E)) {
                if (m3u.isNotBlank()) onManualM3u(m3u)
            }
        }
    }
}
