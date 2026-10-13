package org.irfan.irfantv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// ============================ Colors ============================

internal object Pal {
    val Black = Color(0xFF04070C)
    val Navy = Color(0xFF070D1F)
    val Deep = Color(0xFF0B132B)
    val Royal = Color(0xFF1D4ED8)
    val Electric = Color(0xFF3B82F6)
    val Ice = Color(0xFF7DD3FC)
    val Gold = Color(0xFFFBBF24)
    val Text = Color(0xFFE6EEFF)
    val Muted = Color(0xFF8DA2C8)
}

// ============================ Data ============================

data class Channel(
    val name: String,
    val url: String,
    val logo: String,
    val category: String,
    val quality: String,
    val bangla: Boolean,
    val priority: Boolean,
    val rank: Int,
    val referer: String,
    val userAgent: String
)

val PLAYLISTS = listOf(
    Pair("https://iptv-org.github.io/iptv/countries/bd.m3u", true),
    Pair("https://iptv-org.github.io/iptv/countries/in.m3u", false)
)

val FILTERS = listOf("All", "Bangla", "Drama", "News", "Sports", "Movies", "Kids", "Music")

// Popular drama / entertainment channels, shown first (order = rank)
val PRIORITY = listOf(
    "&tv", "&pictures", "star jalsha", "jalsha movies", "zee bangla", "zee tv",
    "zee cinema", "zee anmol", "star plus", "star bharat", "star gold",
    "sony", "colors", "sab tv", "hbo", "sun bangla", "life ok"
)

private val priorityRegexes = PRIORITY.map {
    Regex("(?<![a-z0-9])" + Regex.escape(it) + "(?![a-z0-9])")
}
private val qualityRegex = Regex("""\((\d{3,4}p)\)""")
private val bracketRegex = Regex("""\s*\[[^\]]*\]""")
private val banglaRegex = Regex("bangla|bengali|jalsha|kolkata")

private fun attr(line: String, key: String): String {
    val m = Regex(key + "=\"([^\"]*)\"").find(line)
    return if (m != null) m.groupValues[1] else ""
}

fun categoryOf(group: String, name: String): String {
    val g = (group + " " + name).lowercase()
    return when {
        g.contains("news") -> "News"
        g.contains("sport") -> "Sports"
        g.contains("movie") || g.contains("cinema") || g.contains("pictures") -> "Movies"
        g.contains("kids") || g.contains("cartoon") || g.contains("children") -> "Kids"
        g.contains("music") -> "Music"
        else -> "Entertainment"
    }
}

fun parseM3u(text: String, fromBd: Boolean): List<Channel> {
    val out = ArrayList<Channel>()
    var name: String? = null
    var logo = ""
    var group = ""
    var referer = ""
    var ua = ""
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTINF")) {
            name = line.substringAfterLast(",").trim()
            logo = attr(line, "tvg-logo")
            group = attr(line, "group-title")
            referer = ""
            ua = ""
        } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
            val opt = line.substringAfter(":")
            val key = opt.substringBefore("=").trim().lowercase()
            val value = opt.substringAfter("=", "").trim()
            if (key == "http-referrer") referer = value
            if (key == "http-user-agent") ua = value
        } else if (line.startsWith("#")) {
            continue
        } else {
            val nm = name
            if (nm != null && line.startsWith("http", ignoreCase = true)) {
                val quality = qualityRegex.find(nm)?.groupValues?.get(1) ?: ""
                var clean = nm.replace(qualityRegex, "").replace(bracketRegex, "").trim()
                if (clean.isEmpty()) clean = nm
                val low = clean.lowercase()
                var rank = 999
                for (i in priorityRegexes.indices) {
                    if (priorityRegexes[i].containsMatchIn(low)) {
                        rank = i
                        break
                    }
                }
                out.add(
                    Channel(
                        name = clean,
                        url = line,
                        logo = logo,
                        category = categoryOf(group, clean),
                        quality = quality,
                        bangla = fromBd || banglaRegex.containsMatchIn(low),
                        priority = rank != 999,
                        rank = rank,
                        referer = referer,
                        userAgent = ua
                    )
                )
                name = null
            }
        }
    }
    return out
}

fun fetchText(url: String): String? {
    repeat(3) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", "IrfanTV/1.0")
            try {
                if (conn.responseCode == 200) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // try again
        }
    }
    return null
}

// Fetches both playlists at the same time, merges, removes duplicates, priority first.
suspend fun loadChannels(): Pair<List<Channel>, Int> = coroutineScope {
    val jobs = PLAYLISTS.map { p ->
        async(Dispatchers.IO) {
            val text = fetchText(p.first)
            if (text == null) null else parseM3u(text, p.second)
        }
    }
    val results = jobs.awaitAll()
    var failed = 0
    val seen = HashSet<String>()
    val merged = ArrayList<Channel>()
    for (r in results) {
        if (r == null) {
            failed++
            continue
        }
        for (c in r) {
            if (seen.add(c.url)) merged.add(c)
        }
    }
    val sorted = merged.sortedWith(
        compareBy<Channel>({ !it.priority }, { it.rank }, { it.name.lowercase() })
    )
    Pair(sorted, failed)
}

fun matchesFilter(c: Channel, f: String): Boolean = when (f) {
    "All" -> true
    "Bangla" -> c.bangla
    "Drama" -> c.category == "Entertainment"
    else -> c.category == f
}

// ============================ Activity ============================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Pal.Electric,
                    background = Pal.Black,
                    surface = Pal.Navy,
                    onSurface = Pal.Text
                )
            ) {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var playing by remember { mutableStateOf<Channel?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                val r = loadChannels()
                channels = r.first
                failed = r.second
            } catch (e: Exception) {
                channels = emptyList()
                failed = PLAYLISTS.size
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val shown = remember(channels, query, filter) {
        channels.filter {
            matchesFilter(it, filter) && (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    Box(Modifier.fillMaxSize()) {
        SparkleBackground()
        Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
            Header(count = channels.size, onRefresh = { refresh() })
            Spacer(Modifier.height(10.dp))
            SearchBar(query = query, onChange = { query = it })
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FILTERS) { f ->
                    Chip(label = f, selected = f == filter, onClick = { filter = f })
                }
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Pal.Ice)
                }
                channels.isEmpty() -> CenterMessage(
                    text = "Could not load channels. Check your internet.",
                    button = "Retry",
                    onClick = { refresh() }
                )
                shown.isEmpty() -> CenterMessage(
                    text = "No channels found",
                    button = "Show all",
                    onClick = {
                        query = ""
                        filter = "All"
                    }
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(shown, key = { it.url }) { ch ->
                        ChannelCard(ch = ch, onClick = { playing = ch })
                    }
                }
            }
        }
        val current = playing
        if (current != null) {
            PlayerScreen(ch = current, onClose = { playing = null })
        }
    }
}

// ============================ UI pieces ============================

private class Star(val x: Float, val y: Float, val size: Float, val phase: Float)

private fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color) {
    val p = Path()
    p.moveTo(c.x, c.y - r)
    p.quadraticBezierTo(c.x, c.y, c.x + r, c.y)
    p.quadraticBezierTo(c.x, c.y, c.x, c.y + r)
    p.quadraticBezierTo(c.x, c.y, c.x - r, c.y)
    p.quadraticBezierTo(c.x, c.y, c.x, c.y - r)
    p.close()
    drawPath(p, color)
}

@Composable
fun SparkleBackground() {
    val stars = remember {
        val r = java.util.Random(7)
        List(44) { Star(r.nextFloat(), r.nextFloat(), 3f + r.nextFloat() * 7f, r.nextFloat()) }
    }
    val transition = rememberInfiniteTransition(label = "sparkle")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "t"
    )
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Pal.Deep, Pal.Navy, Pal.Black)))
        for (s in stars) {
            val a = (sin((t + s.phase) * 2f * PI.toFloat()) + 1f) / 2f
            val alpha = 0.12f + 0.8f * a * a
            val center = Offset(s.x * size.width, s.y * size.height)
            val r = s.size.dp.toPx()
            drawCircle(Pal.Electric.copy(alpha = alpha * 0.18f), r * 1.8f, center)
            drawSparkle(center, r, Pal.Ice.copy(alpha = alpha))
        }
    }
}

@Composable
fun DiamondLogo(logoSize: Dp = 40.dp) {
    Canvas(Modifier.size(logoSize)) {
        val w = size.width
        val h = size.height
        val top = Path()
        top.moveTo(w * 0.27f, h * 0.10f)
        top.lineTo(w * 0.73f, h * 0.10f)
        top.lineTo(w * 0.96f, h * 0.38f)
        top.lineTo(w * 0.04f, h * 0.38f)
        top.close()
        val bottom = Path()
        bottom.moveTo(w * 0.04f, h * 0.38f)
        bottom.lineTo(w * 0.96f, h * 0.38f)
        bottom.lineTo(w * 0.5f, h * 0.95f)
        bottom.close()
        drawPath(bottom, Brush.linearGradient(listOf(Pal.Royal, Pal.Electric), Offset(0f, h), Offset(w, 0f)))
        drawPath(top, Brush.linearGradient(listOf(Pal.Ice, Pal.Electric), Offset(0f, 0f), Offset(w, h * 0.4f)))
        val line = Color.White.copy(alpha = 0.6f)
        drawLine(line, Offset(w * 0.04f, h * 0.38f), Offset(w * 0.96f, h * 0.38f), 1.5f)
        drawLine(line, Offset(w * 0.27f, h * 0.10f), Offset(w * 0.36f, h * 0.38f), 1.5f)
        drawLine(line, Offset(w * 0.73f, h * 0.10f), Offset(w * 0.64f, h * 0.38f), 1.5f)
        drawLine(line, Offset(w * 0.36f, h * 0.38f), Offset(w * 0.5f, h * 0.95f), 1.5f)
        drawLine(line, Offset(w * 0.64f, h * 0.38f), Offset(w * 0.5f, h * 0.95f), 1.5f)
    }
}

@Composable
fun Header(count: Int, onRefresh: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "shine")
    val shift by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "shift"
    )
    val dot by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "dot"
    )
    val brush = Brush.linearGradient(
        colors = listOf(Pal.Electric, Pal.Ice, Color.White, Pal.Ice, Pal.Electric),
        start = Offset(shift, 0f),
        end = Offset(shift + 300f, 0f)
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DiamondLogo(44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Irfan TV",
                style = TextStyle(
                    brush = brush,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )
            Row(
                Modifier
                    .padding(top = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x33182A5A))
                    .border(1.dp, Pal.Royal.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = dot))
                )
                Spacer(Modifier.width(6.dp))
                Text("LIVE  $count channels", color = Pal.Ice, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, Pal.Electric.copy(alpha = 0.7f), RoundedCornerShape(50))
                .clickable(onClick = onRefresh)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("Refresh", color = Pal.Ice, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SearchBar(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(Color(0x66101C3F))
            .border(
                1.dp,
                Brush.horizontalGradient(listOf(Pal.Electric, Color(0xFF16254F), Pal.Ice)),
                shape
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Pal.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(Pal.Ice),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("Search live channels...", color = Pal.Muted, fontSize = 15.sp)
                    }
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            Text(
                "Clear",
                color = Pal.Ice,
                fontSize = 13.sp,
                modifier = Modifier.clickable { onChange("") }.padding(start = 10.dp)
            )
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = if (selected) {
        Brush.horizontalGradient(listOf(Pal.Royal, Pal.Electric))
    } else {
        Brush.horizontalGradient(listOf(Color(0x33182A5A), Color(0x33182A5A)))
    }
    val borderColor = if (selected) Pal.Ice.copy(alpha = 0.9f) else Pal.Royal.copy(alpha = 0.45f)
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else Pal.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun StarIcon(starSize: Dp) {
    Canvas(Modifier.size(starSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension / 2f
        val inner = outer * 0.42f
        val p = Path()
        for (i in 0 until 10) {
            val ang = (-PI / 2 + i * PI / 5).toFloat()
            val rad = if (i % 2 == 0) outer else inner
            val x = cx + rad * cos(ang)
            val y = cy + rad * sin(ang)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawPath(p, Pal.Gold)
    }
}

@Composable
fun PlayButton() {
    Canvas(Modifier.size(46.dp)) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(Pal.Electric.copy(alpha = 0.10f), r, c)
        drawCircle(Pal.Electric.copy(alpha = 0.20f), r * 0.82f, c)
        drawCircle(Brush.linearGradient(listOf(Pal.Ice, Pal.Electric, Pal.Royal)), r * 0.62f, c)
        val w = size.width
        val h = size.height
        val tri = Path()
        tri.moveTo(w * 0.43f, h * 0.35f)
        tri.lineTo(w * 0.68f, h * 0.50f)
        tri.lineTo(w * 0.43f, h * 0.65f)
        tri.close()
        drawPath(tri, Color.White)
    }
}

@Composable
fun Initials(name: String) {
    val letters = name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(letters, color = Pal.Ice, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ChannelLogo(ch: Channel) {
    if (ch.logo.isBlank()) {
        Initials(ch.name)
    } else {
        SubcomposeAsyncImage(
            model = ch.logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(6.dp)
        ) {
            val state = painter.state
            if (state is AsyncImagePainter.State.Success) {
                SubcomposeAsyncImageContent()
            } else {
                Initials(ch.name)
            }
        }
    }
}

@Composable
fun ChannelCard(ch: Channel, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val borderBrush = if (ch.priority) {
        Brush.linearGradient(listOf(Pal.Gold.copy(alpha = 0.9f), Color(0xFF3B2A06), Pal.Gold.copy(alpha = 0.55f)))
    } else {
        Brush.linearGradient(listOf(Pal.Electric.copy(alpha = 0.8f), Color(0xFF0B1226), Pal.Ice.copy(alpha = 0.5f)))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF0E1A3D), Color(0xFF080D1E))))
            .border(1.dp, borderBrush, shape)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF16254F), Color(0xFF0B132B))))
        ) {
            ChannelLogo(ch)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ch.priority) {
                    StarIcon(15.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    ch.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            val tag = if (ch.quality.isEmpty()) ch.category else ch.category + "  •  " + ch.quality
            Text(
                tag,
                color = Pal.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        PlayButton()
    }
}

@Composable
fun ActionButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val bg = if (primary) {
        Brush.horizontalGradient(listOf(Pal.Royal, Pal.Electric))
    } else {
        Brush.horizontalGradient(listOf(Color(0x33182A5A), Color(0x33182A5A)))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Pal.Royal.copy(alpha = 0.6f), CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun CenterMessage(text: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = Pal.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Pal.Royal, Pal.Electric)))
                .clickable(onClick = onClick)
                .padding(horizontal = 26.dp, vertical = 12.dp)
        ) {
            Text(button, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ============================ Player ============================

private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

// ExoPlayer set up for live HLS: follows http<->https redirects, sends the
// referrer / user-agent that the playlist asks for, never opens a browser.
private fun buildPlayer(context: Context, ch: Channel, onError: (String) -> Unit): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(15000)
        .setUserAgent(if (ch.userAgent.isNotBlank()) ch.userAgent else DEFAULT_UA)
    if (ch.referer.isNotBlank()) {
        http.setDefaultRequestProperties(mapOf("Referer" to ch.referer))
    }
    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .build()
    player.addListener(object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            onError(error.errorCodeName)
        }
    })
    player.setMediaItem(MediaItem.fromUri(Uri.parse(ch.url.trim())))
    player.prepare()
    player.playWhenReady = true
    return player
}

// Fallback: hand the stream to any installed video app (VLC, MX Player, ...).
// Uses a video type so browsers are not offered.
private fun openExternally(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(url.trim()), "video/*")
        context.startActivity(Intent.createChooser(intent, "Play with..."))
    } catch (e: Exception) {
        Toast.makeText(context, "No video player app found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun PlayerScreen(ch: Channel, onClose: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    val exo = remember(attempt) {
        buildPlayer(context, ch) { code -> error = code }
    }

    DisposableEffect(exo) {
        onDispose { exo.release() }
    }

    // hide system bars while watching
    DisposableEffect(Unit) {
        val activity = context as Activity
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // pause when the app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exo) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exo.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    keepScreenOn = true
                }
            },
            update = { view -> view.player = exo },
            modifier = Modifier.fillMaxSize()
        )
        val err = error
        if (err != null) {
            Box(
                Modifier.fillMaxSize().background(Color(0xE6000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .padding(28.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Pal.Navy)
                        .border(1.dp, Pal.Royal.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(ch.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "This stream could not be played. It may be offline or blocked.\n($err)",
                        color = Pal.Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    ActionButton("Retry", true) {
                        error = null
                        attempt = attempt + 1
                    }
                    ActionButton("Open in another player", false) { openExternally(context, ch.url) }
                    ActionButton("Close", false, onClose)
                }
            }
        }
    }
}
