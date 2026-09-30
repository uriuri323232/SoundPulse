package com.focusaudio

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

typealias Setter = ((AppData) -> AppData) -> Unit

private val Teal = Color(0xFF0E9594); private val Coral = Color(0xFFE5566D)
private val LightC = lightColorScheme(primary = Color(0xFF0B7F7E), secondary = Color(0xFF3D5A80), tertiary = Color(0xFFE0A030),
    background = Color(0xFFF8F6F2), surface = Color(0xFFF8F6F2), surfaceVariant = Color(0xFFE8ECEB), surfaceContainer = Color(0xFFFFFFFF))
private val DarkC = darkColorScheme(primary = Color(0xFF4FD1CF), secondary = Color(0xFF9DB7E0), tertiary = Color(0xFFF2C063),
    background = Color(0xFF121517), surface = Color(0xFF121517), surfaceVariant = Color(0xFF232A2C), surfaceContainer = Color(0xFF1B2124))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null)
            runCatching { registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS) }
        val store = Store(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkC else LightC) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(store) }
                }
            }
        }
    }
}

fun fmt(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable fun App(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(store.load()) }
    val set: Setter = { f -> d = f(d); val snap = d; scope.launch(Dispatchers.IO) { store.save(snap) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var all by remember { mutableStateOf(listOf<Clip>()) }
    var cur by remember { mutableStateOf<Clip?>(null) }
    var env by remember { mutableStateOf<FloatArray?>(null) }
    var gesture by rememberSaveable { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val sp = remember { Speaker(ctx) }
    val p = remember { Audio.player(ctx) }
    val cuts = remember(env) { chapters(env) }
    val snack = remember { SnackbarHostState() }
    fun refresh() { d.folder?.let { f -> scope.launch { all = withContext(Dispatchers.IO) { clips(ctx, Uri.parse(f)) } } } }
    LaunchedEffect(d.folder) { refresh() }
    LaunchedEffect(cur) {
        env = null
        cur?.let { c -> env = runCatching { analyze(ctx, c.uri, c.uri.toString() + c.size + c.mod) }.getOrNull() }
    }
    LaunchedEffect(err) { err?.let { snack.showSnackbar(it); err = null } }
    val curS by rememberUpdatedState(cur)
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) curS?.let { c -> set { x -> x.copy(played = x.played + c.uri.toString()) } }
            }
            override fun onPlayerError(error: PlaybackException) { err = "לא ניתן לנגן את הקובץ הזה" }
        }
        p.addListener(l); onDispose { p.removeListener(l); sp.shutdown() }
    }
    LaunchedEffect(d.fx) { Audio.fx?.on(d.fx) }
    if (gesture && cur != null) { GestureMode(d, set, cur!!, cuts, sp) { gesture = false }; return }
    val tabs = listOf("ספרייה" to Icons.Rounded.LibraryMusic, "נגן" to Icons.Rounded.GraphicEq, "אחסון" to Icons.Rounded.CleaningServices)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            tabs.forEachIndexed { i, (l, ic) -> NavigationBarItem(tab == i, { tab = i }, { Icon(ic, null) }, label = { Text(l) }) }
        } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> Library(d, set, all, cur) { c -> cur = c; Audio.play(ctx, c.uri, c.name); tab = 1 }
                1 -> PlayerTab(d, set, cur, env, cuts) { gesture = true }
                else -> StorageTab(d, all, ::refresh)
            }
        }
    }
}

@Composable fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) =
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp)); Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable fun Library(d: AppData, set: Setter, all: List<Clip>, cur: Clip?, onPlay: (Clip) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) runCatching {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            set { it.copy(folder = u.toString()) }
        }
    }
    val list = all.filter { it.audio }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("הספרייה שלי", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        FilledTonalButton({ pick.launch(null) }, Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (d.folder == null) "בחר תיקיית הקלטות" else "החלף תיקייה")
        }
        if (list.isEmpty()) Empty(Icons.Rounded.LibraryMusic, if (d.folder == null) "בחר תיקייה כדי להתחיל" else "לא נמצאו הקלטות בתיקייה")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(list, key = { it.uri.toString() }) { c ->
                val done = c.uri.toString() in d.played; val active = c.uri == cur?.uri
                val nm = d.marks.count { it.uri == c.uri.toString() }
                Card(Modifier.fillMaxWidth().clickable { onPlay(c) }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .15f)), Alignment.Center) {
                            Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("%.1f MB".format(c.size / 1e6) + (if (done) "  •  הושמע" else "") + (if (nm > 0) "  •  $nm סימונים" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun Wave(env: FloatArray?, pos: Long, dur: Long, cuts: List<Long>, marks: List<Mark>, onSeek: (Long) -> Unit) {
    val on = MaterialTheme.colorScheme.primary; val off = MaterialTheme.colorScheme.outlineVariant
    val cutC = MaterialTheme.colorScheme.tertiary
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)
            .pointerInput(dur) { detectTapGestures { o -> onSeek((o.x / size.width * dur).toLong().coerceIn(0, dur)) } }
            .pointerInput(dur) { detectHorizontalDragGestures { ch, _ -> onSeek((ch.position.x / size.width * dur).toLong().coerceIn(0, dur)) } }) {
            val n = (size.width / 5).toInt().coerceAtLeast(1); val cy = size.height / 2
            if (env != null && env.isNotEmpty()) {
                val top = (env.maxOrNull() ?: 0.01f).coerceAtLeast(0.01f)
                for (i in 0 until n) {
                    val a = i * env.size / n; val b = maxOf(a + 1, (i + 1) * env.size / n)
                    var m = 0f; for (k in a until minOf(b, env.size)) if (env[k] > m) m = env[k]
                    val h = (m / top * cy * .9f).coerceAtLeast(2f); val x = i * 5f + 2.5f
                    drawLine(if (dur > 0 && x / size.width < pos.toFloat() / dur) on else off, Offset(x, cy - h), Offset(x, cy + h), 3f, StrokeCap.Round)
                }
            }
            if (dur > 0) {
                cuts.forEach { val x = it.toFloat() / dur * size.width; drawLine(cutC, Offset(x, 0f), Offset(x, size.height), 2f) }
                marks.forEach { val x = it.ms.toFloat() / dur * size.width
                    if (it.flag) { drawLine(Coral, Offset(x, 0f), Offset(x, size.height), 3f); drawCircle(Coral, 9f, Offset(x, 9f)) }
                    else drawCircle(on, 7f, Offset(x, size.height - 9f)) }
            }
        }
    }
}

@Composable fun PlayerTab(d: AppData, set: Setter, cur: Clip?, env: FloatArray?, cuts: List<Long>, onGesture: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Audio.player(ctx) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(1L) }
    var playing by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var note by rememberSaveable { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) { while (true) { runCatching { pos = p.currentPosition; dur = p.duration.let { if (it > 0) it else 1L }; playing = p.isPlaying }; delay(250) } }
    if (cur == null) { Empty(Icons.Rounded.GraphicEq, "בחר הקלטה מהספרייה"); return }
    val key = cur.uri.toString()
    val marks = d.marks.filter { it.uri == key }.sortedBy { it.ms }
    fun seek(ms: Long) = runCatching { p.seekTo(ms.coerceIn(0, dur)) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(cur.name.substringBeforeLast('.'), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Wave(env, pos, dur, cuts, marks) { seek(it) }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceBetween) {
                        Text(fmt(pos), style = MaterialTheme.typography.labelLarge)
                        if (env == null) Text("מנתח את ההקלטה...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(fmt(dur), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                IconButton({ seek(pos - 30_000) }, Modifier.size(56.dp)) { Icon(Icons.Rounded.Replay30, "אחורה 30 שניות", Modifier.size(34.dp)) }
                FilledIconButton({ runCatching { if (p.isPlaying) p.pause() else p.play() } }, Modifier.size(76.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "נגן או השהה", Modifier.size(42.dp))
                }
                IconButton({ seek(pos + 30_000) }, Modifier.size(56.dp)) { Icon(Icons.Rounded.Forward30, "קדימה 30 שניות", Modifier.size(34.dp)) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                AssistChip({ val l = listOf(1f, 1.25f, 1.5f, 2f, 2.5f, 3f); speed = l[(l.indexOf(speed) + 1) % l.size]; runCatching { p.setPlaybackSpeed(speed) } },
                    { Text("${speed}x") }, leadingIcon = { Icon(Icons.Rounded.Speed, null, Modifier.size(18.dp)) })
                FilterChip(d.fx, { v -> set { it.copy(fx = !d.fx) }; Audio.fx?.on(!d.fx) }, { Text("מעבד דיבור") })
                FilterChip(skip, { skip = !skip; runCatching { p.skipSilenceEnabled = skip } }, { Text("דלג שקטים") })
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("הערה לנקודה הזו") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); set { x -> x.copy(marks = x.marks + Mark(key, pos, note.ifBlank { "סימנייה" })) }; note = "" },
                            Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.Bookmark, null); Spacer(Modifier.width(6.dp)); Text("סימנייה") }
                        Button({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); set { x -> x.copy(marks = x.marks + Mark(key, pos, note.ifBlank { "דגל" }, true)) }; note = "" },
                            Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral)) { Icon(Icons.Rounded.Flag, null); Spacer(Modifier.width(6.dp)); Text("דגל") }
                    }
                }
            }
        }
        item { FilledTonalButton(onGesture, Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Rounded.DirectionsWalk, null); Spacer(Modifier.width(8.dp)); Text("מצב הליכה (מחוות)") } }
        if (cuts.isNotEmpty()) item { Text("פרקים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(cuts.size) { i -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { seek(cuts[i]) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Segment, null, tint = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(12.dp)); Text("פרק ${i + 2}", Modifier.weight(1f)); Text(fmt(cuts[i]), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (marks.isNotEmpty()) item { Text("סימנים והערות", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(marks.size) { i -> val m = marks[i]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { seek(m.ms) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (m.flag) Icons.Rounded.Flag else Icons.Rounded.Bookmark, null, tint = if (m.flag) Coral else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp)); Text(m.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fmt(m.ms), color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ set { x -> x.copy(marks = x.marks - m) } }) { Icon(Icons.Rounded.Close, "מחק", Modifier.size(18.dp)) }
            } }
    }
}

/** Whole screen is a gesture pad. Tap: play/pause. Swipe right/left: +/-30s. Swipe up: bookmark. Swipe down: next chapter. Long press: flag. Double tap: read aloud. */
@Composable fun GestureMode(d: AppData, set: Setter, cur: Clip, cuts: List<Long>, sp: Speaker, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Audio.player(ctx) }
    val haptic = LocalHapticFeedback.current
    val dNow by rememberUpdatedState(d)
    var step by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("הקש להשמעה או השהיה") }
    var icon by remember { mutableStateOf(Icons.Rounded.TouchApp) }
    var flash by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (flash) MaterialTheme.colorScheme.primary.copy(alpha = .25f) else MaterialTheme.colorScheme.background, label = "flash")
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(flash) { if (flash) { delay(250); flash = false } }
    fun show(t: String, i: androidx.compose.ui.graphics.vector.ImageVector) { label = t; icon = i; flash = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val key = cur.uri.toString()
    Box(Modifier.fillMaxSize().background(bg)
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { runCatching { if (p.isPlaying) { p.pause(); show("מושהה", Icons.Rounded.Pause) } else { p.play(); show("מנגן", Icons.Rounded.PlayArrow) } } },
                onLongPress = { val pos = runCatching { p.currentPosition }.getOrDefault(0L); set { x -> x.copy(marks = x.marks + Mark(key, pos, "דגל", true)) }; show("דגל נוסף ב-${fmt(pos)}", Icons.Rounded.Flag) },
                onDoubleTap = {
                    val say = when (step++ % 3) {
                        0 -> cur.name.substringBeforeLast('.')
                        1 -> { val dd = p.duration; if (dd <= 0) "משך לא ידוע" else { val left = ((dd - p.currentPosition) / 1000).coerceAtLeast(0); "נותרו ${left / 60} דקות ו-${left % 60} שניות" } }
                        else -> dNow.marks.filter { it.uri == key && it.ms <= p.currentPosition }.maxByOrNull { it.ms }?.text ?: "אין סימנייה"
                    }
                    label = say; icon = Icons.Rounded.RecordVoiceOver; sp.say(say)
                })
        }
        .pointerInput(Unit) {
            var dx = 0f; var dy = 0f
            detectDragGestures(onDragStart = { dx = 0f; dy = 0f }, onDrag = { _, a -> dx += a.x; dy += a.y }, onDragCancel = { dx = 0f; dy = 0f }, onDragEnd = {
                runCatching {
                    if (abs(dx) > abs(dy) && abs(dx) > 80) {
                        p.seekTo(maxOf(0, p.currentPosition + if (dx > 0) 30_000 else -30_000))
                        if (dx > 0) show("+30 שניות", Icons.Rounded.Forward30) else show("-30 שניות", Icons.Rounded.Replay30)
                    } else if (dy < -80) { val pos = p.currentPosition; set { x -> x.copy(marks = x.marks + Mark(key, pos, "סימנייה")) }; show("סימנייה נוספה", Icons.Rounded.Bookmark) }
                    else if (dy > 80) { val n = cuts.firstOrNull { it > p.currentPosition }; if (n != null) { p.seekTo(n); show("פרק הבא", Icons.Rounded.SkipNext) } else show("אין פרק הבא", Icons.Rounded.SkipNext) }
                }
            })
        }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(icon, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        }
        Text("הקשה: נגן/השהה  •  ימינה/שמאלה: 30 שניות  •  למעלה: סימנייה  •  למטה: פרק הבא  •  לחיצה ארוכה: דגל  •  הקשה כפולה: הקראה",
            Modifier.align(Alignment.BottomCenter).padding(24.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onExit, Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("יציאה") }
    }
}

@Composable fun StorageTab(d: AppData, all: List<Clip>, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tree = d.folder?.let { Uri.parse(it) }
    var res by remember { mutableStateOf<Scan?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Pair<String, List<Clip>>?>(null) }
    val played = all.filter { it.audio && it.uri.toString() in d.played && !it.name.endsWith(".m4a") }
    fun run(list: List<Clip>) {
        if (list.isEmpty() || tree == null) { busy = false; msg = "הכיווץ הסתיים"; refresh(); return }
        msg = "מכווץ ${list[0].name} (${list.size} נותרו)"; busy = true
        compress(ctx, tree, list[0]) { run(list.drop(1)) }
    }
    if (tree == null) { Empty(Icons.Rounded.CleaningServices, "בחר תיקייה בלשונית הספרייה"); return }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ניהול אחסון", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Button({ busy = true; msg = "סורק..."; scope.launch { res = withContext(Dispatchers.IO) { runCatching { scan(ctx, all) }.getOrNull() }; busy = false; msg = if (res == null) "הסריקה נכשלה" else "" } },
            Modifier.fillMaxWidth().height(52.dp), enabled = !busy) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("סרוק כפולים, ישנים וזבל") }
        res?.let { r ->
            listOf("קבצים כפולים" to r.dups, "הקלטות ישנות (מעל 90 יום)" to r.old, "קבצי זבל זמניים" to r.junk).forEach { (t, l) ->
                Card(Modifier.fillMaxWidth().clickable(enabled = l.isNotEmpty()) { ask = t to l }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t, Modifier.weight(1f)); Text("${l.size} • ${"%.1f".format(l.sumOf { it.size } / 1e6)} MB", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider()
        Text("קבצים שהושמעו במלואם: ${played.size}")
        FilledTonalButton({ run(played) }, Modifier.fillMaxWidth().height(52.dp), enabled = !busy && played.isNotEmpty()) {
            Text("כווץ קבצים שהושמעו (${"%.1f".format(played.sumOf { it.size } / 1e6)} MB)")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ask?.let { (t, l) ->
        AlertDialog(onDismissRequest = { ask = null }, title = { Text("למחוק $t?") },
            text = { Text("${l.size} קבצים יימחקו לצמיתות: " + l.take(4).joinToString { it.name }) },
            confirmButton = { TextButton({ ask = null; scope.launch { withContext(Dispatchers.IO) { delete(ctx, l) }; res = null; refresh(); msg = "נמחקו ${l.size} קבצים" } }) { Text("מחק") } },
            dismissButton = { TextButton({ ask = null }) { Text("ביטול") } })
    }
}
