package com.ardean.wxwatch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import androidx.wear.input.RemoteInputIntentHelper
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

val RADII_MI = listOf(5, 10, 25, 50, 100, 200)
val REFRESH_S = listOf(30, 45, 60)

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("wx", Context.MODE_PRIVATE)
    var radiusIdx get() = p.getInt("r", 2).coerceIn(RADII_MI.indices); set(v) = p.edit().putInt("r", v).apply()
    var planes get() = p.getBoolean("planes", true); set(v) = p.edit().putBoolean("planes", v).apply()
    var refreshIdx get() = p.getInt("ref", 0).coerceIn(REFRESH_S.indices); set(v) = p.edit().putInt("ref", v).apply()
    var zip get() = p.getString("zip", "24354")!!; set(v) = p.edit().putString("zip", v).apply()
}

data class Center(val lat: Double, val lon: Double, val source: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WxApp(Prefs(this)) } }
    }
}

/**
 * One-shot location: FusedLocationProvider (uses watch GPS, or the paired phone's location
 * when the watch is connected and its own GPS isn't used), then last known, then saved ZIP.
 */
@SuppressLint("MissingPermission")
suspend fun locateOnce(ctx: Context, prefs: Prefs): Center {
    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (granted) {
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        val cur = runCatching {
            withTimeoutOrNull(20_000) { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }
        }.getOrNull()
        cts.cancel()
        if (cur != null) return Center(cur.latitude, cur.longitude, "GPS/fused")
        val last = runCatching { fused.lastLocation.await() }.getOrNull()
        if (last != null) return Center(last.latitude, last.longitude, "last known (watch/phone)")
    }
    Net.zip(prefs.zip)?.let { return Center(it.first, it.second, "ZIP ${prefs.zip}") }
    // Final fallback: marion-wx-map default center (Marion, VA).
    return Center(36.8344, -81.5148, "default (Marion)")
}

class Trail { val pts = ArrayList<Pair<Double, Double>>() }

@Composable
fun WxApp(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var center by remember { mutableStateOf<Center?>(null) }
    var radiusIdx by remember { mutableIntStateOf(prefs.radiusIdx) }
    var planesOn by remember { mutableStateOf(prefs.planes) }
    var refreshIdx by remember { mutableIntStateOf(prefs.refreshIdx) }
    var showOptions by remember { mutableStateOf(false) }
    var planes by remember { mutableStateOf<List<Plane>>(emptyList()) }
    val trails = remember { HashMap<String, Trail>() }
    var radarBust by remember { mutableLongStateOf(System.currentTimeMillis() / 300000) }
    var status by remember { mutableStateOf("Locating…") }
    var tick by remember { mutableIntStateOf(0) } // bump to redraw when tiles arrive
    var locateRequest by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locateRequest++
    }
    // Once per app open (composition): ask permission if needed, then locate once.
    LaunchedEffect(Unit) {
        val has = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (has) locateRequest++
        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(locateRequest) {
        if (locateRequest == 0) return@LaunchedEffect
        status = "Locating…"
        center = locateOnce(ctx, prefs)
        status = center!!.source
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK || res.data == null) return@rememberLauncherForActivityResult
        val z = RemoteInput.getResultsFromIntent(res.data)?.getCharSequence("zip")?.toString()?.trim().orEmpty()
        if (Regex("^\\d{5}$").matches(z)) scope.launch {
            val ll = Net.zip(z)
            if (ll != null) { prefs.zip = z; trails.clear(); center = Center(ll.first, ll.second, "ZIP $z") ; status = "ZIP $z" }
            else status = "ZIP not found"
        } else status = "Enter 5-digit ZIP"
    }

    // Data refresh loop: only while the activity is STARTED (foreground). Stops when backgrounded.
    LaunchedEffect(center, planesOn, refreshIdx, radiusIdx) {
        val c = center ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                radarBust = System.currentTimeMillis() / 300000
                if (planesOn) {
                    val nm = ceil(RADII_MI[radiusIdx] / 1.15078).toInt().coerceIn(1, 250)
                    runCatching { Net.aircraft(c.lat, c.lon, nm) }
                        .onSuccess { list ->
                            planes = list
                            val seen = list.map { it.id }.toSet()
                            trails.keys.retainAll(seen)
                            for (p in list) {
                                val t = trails.getOrPut(p.id) { Trail() }
                                if (t.pts.lastOrNull() != (p.lat to p.lon)) t.pts += p.lat to p.lon
                                if (t.pts.size > 40) t.pts.subList(0, t.pts.size - 40).clear()
                            }
                            status = "${list.size} aircraft · ${c.source}"
                        }
                        .onFailure { status = "ADS-B error: ${it.message}" }
                } else { planes = emptyList(); trails.clear(); status = c.source }
                delay(REFRESH_S[refreshIdx] * 1000L)
            }
        }
    }

    val focus = remember { FocusRequester() }
    var rotAcc by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e ->
                if (showOptions) return@onRotaryScrollEvent false
                rotAcc += e.verticalScrollPixels
                if (abs(rotAcc) > 60f) {
                    val nidx = (radiusIdx + if (rotAcc > 0) 1 else -1).coerceIn(RADII_MI.indices)
                    if (nidx != radiusIdx) { radiusIdx = nidx; prefs.radiusIdx = nidx }
                    rotAcc = 0f
                }
                true
            }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) { detectTapGestures { showOptions = true } }
    ) {
        val c = center
        if (c != null) MapCanvas(c, RADII_MI[radiusIdx].toDouble(), radarBust, planes, trails, planesOn, tick) { tick++ }
        Column(Modifier.fillMaxSize().padding(top = 30.dp, bottom = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${RADII_MI[radiusIdx]} mi", fontSize = 14.sp, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(status, fontSize = 11.sp, color = Color(0xFFB0C4D8), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 60.dp))
        }
        if (showOptions) OptionsScreen(
            planesOn, { planesOn = it; prefs.planes = it },
            radiusIdx, { radiusIdx = it; prefs.radiusIdx = it },
            refreshIdx, { refreshIdx = it; prefs.refreshIdx = it },
            prefs.zip,
            onZip = {
                val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent,
                    listOf(RemoteInput.Builder("zip").setLabel("ZIP code").build()))
                zipLauncher.launch(intent)
            },
            onRelocate = { trails.clear(); locateRequest++ },
            onClose = { showOptions = false }
        )
    }
    LaunchedEffect(showOptions) { if (!showOptions) runCatching { focus.requestFocus() } }
}

@Composable
fun MapCanvas(
    c: Center, radiusMi: Double, bust: Long, planes: List<Plane>, trails: Map<String, Trail>,
    planesOn: Boolean, tick: Int, onTile: () -> Unit
) {
    var size by remember { mutableStateOf(IntSize(480, 480)) }
    val half = min(size.width, size.height) / 2.0
    val zf = Merc.zoomFor(radiusMi, c.lat, half)
    val zi = floor(zf).toInt()
    val scale = 2.0.pow(zf - zi)
    val cx = Merc.worldX(c.lon, zi.toDouble()); val cy = Merc.worldY(c.lat, zi.toDouble())
    val tilePx = 256 * scale
    val span = ceil(half / tilePx).toInt() + 1
    val tx0 = floor(cx / 256).toInt(); val ty0 = floor(cy / 256).toInt()
    val n = 1 shl zi
    data class T(val x: Int, val y: Int, val urls: List<String>)
    val tiles = remember(zi, tx0, ty0, span, bust) {
        val l = ArrayList<T>()
        for (dx in -span..span) for (dy in -span..span) {
            val x = ((tx0 + dx) % n + n) % n; val y = ty0 + dy
            if (y < 0 || y >= n) continue
            l += T(tx0 + dx, y, listOf(
                Feeds.esri(Feeds.ESRI_BASE, zi, x, y), Feeds.radar(zi, x, y, bust), Feeds.esri(Feeds.ESRI_REF, zi, x, y)))
        }
        l
    }
    LaunchedEffect(tiles) {
        tiles.flatMap { it.urls }.forEach { u -> launch { if (Net.cached(u) == null && Net.tile(u) != null) onTile() } }
    }
    val plane = remember { Path().apply { moveTo(0f, -9f); lineTo(6f, 7f); lineTo(0f, 3f); lineTo(-6f, 7f); close() } }
    Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        tick // read to recompose on tile arrival
        val ox = this.size.width / 2.0; val oy = this.size.height / 2.0
        fun toScreen(lat: Double, lon: Double): Offset {
            val wx = Merc.worldX(lon, zi.toDouble()); val wy = Merc.worldY(lat, zi.toDouble())
            return Offset((ox + (wx - cx) * scale).toFloat(), (oy + (wy - cy) * scale).toFloat())
        }
        for (t in tiles) {
            val left = (ox + (t.x * 256 - cx) * scale).toInt(); val top = (oy + (t.y * 256 - cy) * scale).toInt()
            val s = tilePx.roundToInt() + 1
            t.urls.forEachIndexed { i, u ->
                val bmp: Bitmap = Net.cached(u) ?: return@forEachIndexed
                drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(left, top), dstSize = IntSize(s, s),
                    alpha = if (i == 1) 0.75f else 1f)
            }
        }
        val r = half.toFloat()
        val inside = { o: Offset -> (o - center).getDistance() <= r }
        if (planesOn) {
            // Trails: only segments whose both ends are on the visible disc (off-map parts erased).
            for ((_, t) in trails) {
                val pts = t.pts.map { toScreen(it.first, it.second) }
                for (i in 1 until pts.size) if (inside(pts[i - 1]) && inside(pts[i]))
                    drawLine(Color(0x99FFC940), pts[i - 1], pts[i], strokeWidth = 2f)
            }
            for (p in planes) {
                val o = toScreen(p.lat, p.lon)
                if (!inside(o)) continue
                rotate(p.track.toFloat(), o) { translate(o.x, o.y) { drawPath(plane, Color(0xFFFFC940)) } }
            }
        }
        // ZIP/user center box
        drawRect(Color(0xFF3FD07F), topLeft = center - Offset(6f, 6f), size = androidx.compose.ui.geometry.Size(12f, 12f), style = Stroke(2f))
        drawCircle(Color(0x553FD07F), radius = r - 2f, style = Stroke(2f))
    }
}

