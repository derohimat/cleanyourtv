package com.gathin.tvhome

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random

private val Outfit = FontFamily(Font(R.font.outfit))
private val Gold = Color(0xFFE7C07B)
private val DarkInk = Color(0xFF1A1205)

/** One prayer cell's data for the rail. */
private class Pray(val name: String, val time: String, val next: Boolean)

/** A launchable app tile (dock or grid). */
private class AppEntry(val pkg: String, val label: String, val image: ImageBitmap?, val isBanner: Boolean, val launch: Intent?)

/** Everything the Home screen draws, read once from the existing Java Config/PrayerTimes/Hijri logic. */
private class HomeData(
    val greeting: String,
    val zoneLabel: String,
    val dateLine: String,
    val hijriLine: String?,
    val nextName: String?,
    val countdown: String?,
    val cells: List<Pray>,
    val quoteAr: String?,
    val quoteId: String?,
    val quoteSrc: String?,
    val wallpaper: androidx.compose.ui.graphics.ImageBitmap?,
    val dim: Float,
    /** Milliseconds between wallpaper rotations; 0 means no timed rotation. */
    val wallpaperRotationMs: Long
)

/**
 * The Home screen, migrated from the View/XML MainActivity to Jetpack Compose.
 * Reuses the existing Java logic (Config, PrayerTimes, Hijri, PrayerText) unchanged.
 */
class ComposeHomeActivity : ComponentActivity() {

    // Apps are read once at startup; prayer/quote/wallpaper refresh on resume.
    private var dock: List<AppEntry> = emptyList()
    private var grid: List<AppEntry> = emptyList()
    private val dataState = androidx.compose.runtime.mutableStateOf<HomeData?>(null)
    private val gridOpen = androidx.compose.runtime.mutableStateOf(false)

    // WallpaperRotation state: last picked filename and a stable Random instance.
    private var wallpaperPrevious: String? = null
    private val wallpaperRandom = Random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config.load(this)
        val (d, g) = loadApps(cfg)
        dock = d
        grid = g
        setContent {
            dataState.value?.let { data ->
                HomeScreen(
                    data, dock, grid, gridOpen,
                    onLaunch = { intent -> runCatching { startActivity(intent) } },
                    onSystem = { action -> runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    onPanel = { runCatching { startActivity(Intent(this, QuickSettingsActivity::class.java)) } },
                    onTick = { dataState.value = readHome(Config.load(this), advanceQuote = false, advanceWallpaper = false) },
                    onWallpaperRotate = { dataState.value = readHome(Config.load(this), advanceQuote = false, advanceWallpaper = true) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning to Home: close the grid, reload the wallpaper, recompute prayer and advance the quote.
        gridOpen.value = false
        dataState.value = readHome(Config.load(this), advanceQuote = true, advanceWallpaper = true)
    }

    /**
     * Called when the HOME key is pressed while this activity is already the top task.
     * Close the app grid and restore dock focus.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gridOpen.value = false
        // Focus restoration is handled by the LaunchedEffect(gridOpen.value) in HomeScreen.
    }

    private fun readHome(cfg: Config, advanceQuote: Boolean, advanceWallpaper: Boolean): HomeData {
        val now = Calendar.getInstance()

        // Greeting (time-of-day, or the configured fixed greeting, plus an optional name).
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val todRes = when {
            hour < 5 -> R.string.good_night
            hour < 12 -> R.string.good_morning
            hour < 17 -> R.string.good_afternoon
            hour < 22 -> R.string.good_evening
            else -> R.string.good_night
        }
        val base = if (cfg.greetingText.isEmpty()) getString(todRes) else cfg.greetingText
        val greeting = if (cfg.greetingName.isEmpty()) base
        else getString(R.string.greeting_with_name, base, cfg.greetingName)

        val datePattern = getString(R.string.date_format)
        val dateLine = SimpleDateFormat(datePattern, Locale.getDefault()).format(Date())

        var hijriLine: String? = null
        if (cfg.hijriDate) {
            val h = Hijri.of(LocalDate.now(), cfg.hijriOffset)
            if (h != null) hijriLine = getString(R.string.hijri_date_format, h[2], Hijri.MONTHS[h[1] - 1], h[0])
        }

        // Prayer rail.
        var nextName: String? = null
        var countdown: String? = null
        val cells = ArrayList<Pray>()
        val prayer = cfg.prayer
        if (prayer != null) {
            val today = LocalDate.now()
            val off = PrayerText.utcOffsetMinutes(now)
            val todayT = PrayerTimes.compute(today, prayer.latitude, prayer.longitude, prayer.elevation, off, prayer.method)
            val tomoT = PrayerTimes.compute(today.plusDays(1), prayer.latitude, prayer.longitude, prayer.elevation, off, prayer.method)
            if (todayT != null && tomoT != null) {
                val minute = PrayerText.minuteOfDay(now)
                var next = PrayerTimes.next(todayT, minute)
                val tomorrow = next < 0
                val times = if (tomorrow) tomoT else todayT
                if (tomorrow) next = PrayerTimes.SUBUH
                val until = times[next] + (if (tomorrow) 1440 else 0) - minute
                nextName = PrayerTimes.NAMES[next]
                countdown = getString(R.string.prayer_countdown, nextName, PrayerText.duration(this, until))
                for (i in times.indices) cells.add(Pray(PrayerTimes.NAMES[i], PrayerText.clock(this, times[i]), i == next))
            }
        }

        // Quote (a new one each return to Home).
        var ar: String? = null
        var id: String? = null
        var src: String? = null
        val quoteFile = Config.read(File(Config.dir(this), "quotes.json"))
        val quotes = quoteFile?.optJSONArray("quotes")
        if (quotes != null && quotes.length() > 0) {
            val prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
            // Advance to the next quote only on return to Home; a minute tick keeps the same one.
            val i = if (advanceQuote) {
                val n = (prefs.getInt("quote", -1) + 1) % quotes.length()
                prefs.edit().putInt("quote", n).apply()
                n
            } else {
                ((prefs.getInt("quote", 0) % quotes.length()) + quotes.length()) % quotes.length()
            }
            quotes.optJSONObject(i)?.let {
                ar = it.optString("ar"); id = it.optString("id"); src = it.optString("src")
            }
        }

        // Wallpaper: use WallpaperRotation.pick() for proper random no-repeat selection.
        // Only rotate (pick a new image) when advanceWallpaper is true; on minute ticks keep the
        // current image so the screen does not flash.
        if (advanceWallpaper) {
            val files = cfg.wallpapers
            if (cfg.wallpaperEnabled && files.isNotEmpty()) {
                val picked = WallpaperRotation.pick(files, wallpaperPrevious, wallpaperRandom)
                if (picked != null) {
                    wallpaperPrevious = picked.name
                    wallpaper = decodeScaled(picked)
                } else {
                    wallpaper = null
                }
            } else {
                wallpaper = null
            }
        }

        val rotationMs = if (cfg.wallpaperRotationMinutes > 0)
            cfg.wallpaperRotationMinutes * 60_000L else 0L

        return HomeData(
            greeting.uppercase(Locale.getDefault()), cfg.clockZoneLabel,
            dateLine.uppercase(Locale.getDefault()),
            hijriLine?.uppercase(Locale.getDefault()), nextName, countdown, cells, ar, id, src,
            wallpaper, cfg.wallpaperDim / 100f, rotationMs
        )
    }

    // Held across readHome calls so the wallpaper persists on minute ticks.
    private var wallpaper: ImageBitmap? = null

    private fun decodeScaled(file: File): ImageBitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options()
            var s = 1
            while (bounds.outWidth / s > 1920 || bounds.outHeight / s > 1080) s *= 2
            opts.inSampleSize = s
            BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }

    /** Returns the dock apps (in configured order) and the full app list (sorted), both from the leanback launcher set. */
    private fun loadApps(cfg: Config): Pair<List<AppEntry>, List<AppEntry>> {
        val pm = packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val entries = LinkedHashMap<String, AppEntry>()
        for (ri in pm.queryIntentActivities(probe, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || cfg.hiddenApps.contains(pkg) || entries.containsKey(pkg)) continue
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            val (image, isBanner) = loadAppImage(pm, ri)
            entries[pkg] = AppEntry(pkg, label, image, isBanner, pm.getLeanbackLaunchIntentForPackage(pkg))
        }
        val dock = cfg.dockApps.mapNotNull { entries[it] }
        val grid = entries.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
        return dock to grid
    }

    private fun loadAppImage(pm: PackageManager, ri: ResolveInfo): Pair<ImageBitmap?, Boolean> {
        val b = loadBannerDrawable(pm, ri)
        if (b != null && isBannerShaped(b.intrinsicWidth, b.intrinsicHeight)) {
            return drawableToImageBitmap(b, isBanner = true) to true
        }
        val icon = ri.activityInfo.loadIcon(pm) ?: ri.loadIcon(pm)
        return drawableToImageBitmap(icon, isBanner = false) to false
    }

    private fun loadBannerDrawable(pm: PackageManager, ri: ResolveInfo): Drawable? {
        var id = ri.activityInfo.banner
        if (id == 0) id = ri.activityInfo.applicationInfo.banner
        if (id != 0) {
            try {
                val res = pm.getResourcesForApplication(ri.activityInfo.applicationInfo)
                val density = Math.max(DisplayMetrics.DENSITY_XHIGH, resources.displayMetrics.densityDpi)
                val d = res.getDrawableForDensity(id, density, null)
                if (d != null) return d
            } catch (_: Throwable) {}
        }
        return ri.activityInfo.loadBanner(pm)
    }

    private fun isBannerShaped(width: Int, height: Int): Boolean {
        return width <= 0 || height <= 0 || width >= height * 1.35f
    }

    private fun drawableToImageBitmap(d: Drawable, isBanner: Boolean): ImageBitmap? = try {
        val w = if (isBanner) {
            (if (d.intrinsicWidth > 0) d.intrinsicWidth else 320).coerceIn(320, 640)
        } else {
            (if (d.intrinsicWidth > 0) d.intrinsicWidth else 128).coerceIn(96, 256)
        }
        val h = if (isBanner) {
            (if (d.intrinsicHeight > 0) d.intrinsicHeight else 180).coerceIn(180, 360)
        } else {
            (if (d.intrinsicHeight > 0) d.intrinsicHeight else 128).coerceIn(96, 256)
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        bmp.asImageBitmap()
    } catch (e: Throwable) {
        null
    }
}

@Composable
private fun HomeScreen(
    d: HomeData,
    dock: List<AppEntry>,
    grid: List<AppEntry>,
    gridOpen: androidx.compose.runtime.MutableState<Boolean>,
    onLaunch: (Intent) -> Unit,
    onSystem: (String) -> Unit,
    onPanel: () -> Unit,
    onTick: () -> Unit,
    onWallpaperRotate: () -> Unit
) {
    // 1a: Track which dock tile was last focused so we restore to it (not always tile 0).
    var lastDockIndex by remember { mutableIntStateOf(0) }
    // One FocusRequester per dock slot; recreated only when the dock size changes.
    val dockFocusers = remember(dock.size) { List(dock.size) { FocusRequester() } }
    LaunchedEffect(gridOpen.value) {
        if (!gridOpen.value) runCatching { dockFocusers.getOrNull(lastDockIndex)?.requestFocus() }
    }

    // Re-read the prayer countdown on each minute boundary (keeps the same quote and wallpaper).
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L * (61 - (System.currentTimeMillis() / 1000 % 60)))
            onTick()
        }
    }

    // 1b: Rotate the wallpaper on the theme's configured timer interval.
    LaunchedEffect(d.wallpaperRotationMs) {
        if (d.wallpaperRotationMs > 0) {
            delay(d.wallpaperRotationMs)
            while (true) {
                onWallpaperRotate()
                delay(d.wallpaperRotationMs)
            }
        }
    }

    // 1d: Grid scroll state hoisted here so it persists across open/close in the same session.
    val gridScrollState = rememberLazyGridState()

    Box(Modifier.fillMaxSize().background(Color(0xFF05070D))) {
        if (d.wallpaper != null) {
            Image(d.wallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // Legibility scrim: dark at top and bottom.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.0f to Color(0xD105070D), 0.26f to Color(0x4705070D),
                    0.5f to Color(0x1F05070D), 0.78f to Color(0x8505070D), 1.0f to Color(0xF005070D)
                )
            )
        )
        if (d.dim > 0f) Box(Modifier.fillMaxSize().background(Color(0xFF05070D).copy(alpha = d.dim)))

        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 32.dp)) {
            Header(d, onSystem, onPanel)
            if (d.cells.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                PrayerRail(d)
            }
            if (d.quoteAr != null) {
                Spacer(Modifier.height(14.dp))
                Quote(d)
            }
            Spacer(Modifier.weight(1f))
            if (dock.isNotEmpty()) Dock(
                apps = dock,
                focusers = dockFocusers,
                onFocused = { idx -> lastDockIndex = idx },
                onLaunch = onLaunch,
                onOpenGrid = { gridOpen.value = true }
            )
        }

        if (gridOpen.value) AppGrid(grid, gridScrollState, onLaunch, onClose = { gridOpen.value = false })
    }
}

@Composable
private fun TopButtons(onSystem: (String) -> Unit, onPanel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(stringResource(R.string.inputs).uppercase()) { onSystem(Settings.ACTION_SETTINGS) }
        Pill(stringResource(R.string.quick_network).uppercase()) { onSystem(Settings.ACTION_WIFI_SETTINGS) }
        Pill(stringResource(R.string.settings).uppercase()) { onPanel() }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0x22FFFFFF) else Color(0x12FFFFFF))
            .then(if (focused) Modifier.border(2.dp, Gold, RoundedCornerShape(14.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label, style = TextStyle(color = Color(0xE6FFFFFF), fontSize = 13.sp, letterSpacing = 1.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
    }
}

/**
 * 1a: Dock now receives one FocusRequester per tile and an onFocused callback so the
 * parent can track which tile was last selected and restore to it when the grid closes.
 */
@Composable
private fun Dock(
    apps: List<AppEntry>,
    focusers: List<FocusRequester>,
    onFocused: (Int) -> Unit,
    onLaunch: (Intent) -> Unit,
    onOpenGrid: () -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 14.dp)
    ) {
        itemsIndexed(apps) { i, app ->
            Tile(
                app = app,
                fr = focusers.getOrNull(i),
                w = 196.dp,
                h = 110.dp,
                onLaunch = onLaunch,
                onDown = onOpenGrid,
                onFocused = { onFocused(i) }
            )
        }
    }
}

/** A focusable app tile: banner or centered icon, gold ring + lift on focus, launches on click. onDown opens the grid (dock only). */
@Composable
private fun Tile(
    app: AppEntry,
    fr: FocusRequester?,
    w: androidx.compose.ui.unit.Dp,
    h: androidx.compose.ui.unit.Dp,
    onLaunch: (Intent) -> Unit,
    onDown: (() -> Unit)? = null,
    /** Called when this tile gains focus; used to track the last-focused dock tile. */
    onFocused: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tileScale")
    Box(
        Modifier
            .zIndex(if (focused) 2f else 1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(width = w, height = h)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0B0B10))
            .then(if (focused) Modifier.border(3.dp, Gold, RoundedCornerShape(18.dp)) else Modifier)
            .then(if (fr != null) Modifier.focusRequester(fr) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .then(
                if (onDown != null) Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onDown(); true } else false
                } else Modifier
            )
            .focusable()
            .clickable { app.launch?.let(onLaunch) },
        contentAlignment = Alignment.Center
    ) {
        if (app.image != null) {
            if (app.isBanner) {
                Image(app.image, app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(app.image, app.label, Modifier.size(52.dp), contentScale = ContentScale.Fit)
                }
            }
        } else {
            BasicText(app.label, style = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = Outfit))
        }
    }
}

/**
 * 1d: gridState is hoisted from HomeScreen so the scroll position survives close/reopen
 * within the same session. Each new onResume resets it naturally (state is recreated).
 */
@Composable
private fun AppGrid(
    apps: List<AppEntry>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onLaunch: (Intent) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(enabled = true) { onClose() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xFF05070D))) {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 28.dp)) {
            BasicText(stringResource(R.string.app_library).uppercase(), style = TextStyle(color = Gold, fontSize = 13.sp, letterSpacing = 2.8.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(18.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                gridItemsIndexed(apps) { i, app ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Tile(app, if (i == 0) first else null, 194.dp, 109.dp, onLaunch)
                        Spacer(Modifier.height(8.dp))
                        BasicText(app.label, style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 14.sp, fontFamily = Outfit), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(d: HomeData, onSystem: (String) -> Unit, onPanel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        HeaderClock(d)
        TopButtons(onSystem, onPanel)
    }
}

@Composable
private fun HeaderClock(d: HomeData) {
    // A clock that ticks each minute.
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)))
        }
    }
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tick))

    Column {
        BasicText(d.greeting, style = TextStyle(color = Gold, fontSize = 13.sp, letterSpacing = 3.4.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(clock, style = TextStyle(color = Color.White, fontSize = 68.sp, fontFamily = Outfit, fontWeight = FontWeight.Light))
            if (d.zoneLabel.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                BasicText(d.zoneLabel, Modifier.padding(bottom = 12.dp), style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 15.sp, letterSpacing = 1.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
            }
            Spacer(Modifier.width(18.dp))
            Box(Modifier.padding(bottom = 10.dp).width(1.dp).height(44.dp).background(Color(0x33FFFFFF)))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.padding(bottom = 8.dp)) {
                BasicText(d.dateLine, style = TextStyle(color = Color(0xB3FFFFFF), fontSize = 15.sp, letterSpacing = 1.4.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
                if (d.hijriLine != null) {
                    Spacer(Modifier.height(4.dp))
                    BasicText(d.hijriLine, style = TextStyle(color = Gold.copy(alpha = 0.8f), fontSize = 13.sp, letterSpacing = 1.4.sp, fontFamily = Outfit))
                }
            }
        }
    }
}

@Composable
private fun PrayerRail(d: HomeData) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xE60C1018))
            .padding(start = 12.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // "Berikutnya" / "Next" block.
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Gold), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_prayer), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(DarkInk))
        }
        Spacer(Modifier.width(13.dp))
        Column {
            BasicText(stringResource(R.string.prayer_next).uppercase(),
                style = TextStyle(color = Gold.copy(alpha = 0.9f), fontSize = 10.sp, letterSpacing = 1.8.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(2.dp))
            BasicText(d.countdown ?: "", style = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).height(42.dp).background(Color(0x1AFFFFFF)))
        Spacer(Modifier.width(10.dp))
        d.cells.forEach { c ->
            Column(
                Modifier.clip(RoundedCornerShape(13.dp))
                    .background(if (c.next) Gold else Color.Transparent)
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(c.name.uppercase(Locale.getDefault()),
                    style = TextStyle(color = if (c.next) DarkInk.copy(alpha = 0.75f) else Color(0x66FFFFFF), fontSize = 10.sp, letterSpacing = 1.2.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
                Spacer(Modifier.height(3.dp))
                BasicText(c.time,
                    style = TextStyle(color = if (c.next) DarkInk else Color(0xB8FFFFFF), fontSize = 19.sp, fontFamily = Outfit, fontWeight = if (c.next) FontWeight.Medium else FontWeight.Light))
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun Quote(d: HomeData) {
    Column(Modifier.fillMaxWidth()) {
        BasicText("—   " + stringResource(R.string.quote_eyebrow).uppercase(),
            style = TextStyle(color = Gold.copy(alpha = 0.8f), fontSize = 11.sp, letterSpacing = 2.2.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(10.dp))
        BasicText(d.quoteAr ?: "", Modifier.fillMaxWidth(),
            style = TextStyle(color = Color(0xF2FFFFFF), fontSize = 24.sp, textAlign = TextAlign.Center))
        Spacer(Modifier.height(10.dp))
        BasicText("\u201c" + (d.quoteId ?: "") + "\u201d",
            style = TextStyle(color = Color(0xD9FFFFFF), fontSize = 16.sp, fontFamily = Outfit, fontWeight = FontWeight.Light))
        Spacer(Modifier.height(8.dp))
        BasicText((d.quoteSrc ?: "").uppercase(Locale.getDefault()),
            style = TextStyle(color = Gold.copy(alpha = 0.6f), fontSize = 12.sp, letterSpacing = 1.sp, fontFamily = Outfit, fontWeight = FontWeight.Medium))
    }
}
