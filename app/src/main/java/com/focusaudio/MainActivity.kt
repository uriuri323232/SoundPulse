package com.focusaudio

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay

typealias Setter = ((AppData) -> AppData) -> Unit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        val store = Store(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0E7C7B))) { App(store) }
            }
        }
    }
}

fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

@Composable fun App(store: Store) {
    val ctx = LocalContext.current
    var d by remember { mutableStateOf(store.load()) }
    val set: Setter = { f -> d = f(d); store.save(d) }
    var tab by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf(listOf<Clip>()) }
    var cur by remember { mutableStateOf<Clip?>(null) }
    var env by remember { mutableStateOf<FloatArray?>(null) }
    var gesture by remember { mutableStateOf(false) }
    val sp = remember { Speaker(ctx) }
    val p = remember { Audio.player(ctx) }
    val cuts = remember(env) { chapters(env) }
    fun refresh() { d.folder?.let { all = clips(ctx, Uri.parse(it)) } }
    LaunchedEffect(d.folder) { refresh() }
    LaunchedEffect(cur) { env = null; cur?.let { env = runCatching { analyze(ctx, it.uri) }.getOrNull() } }
    val curS by rememberUpdatedState(cur)
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) curS?.let { c -> set { x -> x.copy(played = x.played + c.uri.toString()) } }
            }
        }
        p.addListener(l); onDispose { p.removeListener(l) }
    }
    if (gesture && cur != null) { GestureMode(d, cur!!, cuts, sp) { gesture = false }; return }
    val tabs = listOf("ספרייה" to Icons.Default.List, "נגן" to Icons.Default.PlayArrow, "אחסון" to Icons.Default.Delete)
    Scaffold(bottomBar = {
        NavigationBar { tabs.forEachIndexed { i, (l, ic) -> NavigationBarItem(tab == i, { tab = i }, { Icon(ic, null) }, label = { Text(l) }) } }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> Library(d, set, all) { c -> cur = c; Audio.play(ctx, c.uri, c.name); tab = 1 }
                1 -> PlayerTab(d, set, cur, env, cuts) { gesture = true }
                else -> StorageTab(d, all, ::refresh)
            }
        }
    }
}

@Composable fun Library(d: AppData, set: Setter, all: List<Clip>, onPlay: (Clip) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            set { it.copy(folder = u.toString()) }
        }
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button({ pick.launch(null) }, Modifier.fillMaxWidth()) { Text(if (d.folder == null) "בחר תיקיית הקלטות" else "החלף תיקייה") }
        LazyColumn {
            items(all.filter { it.audio }) { c ->
                val done = c.uri.toString() in d.played
                ListItem(headlineContent = { Text(c.name, maxLines = 1) },
                    supportingContent = { Text("%.1f MB".format(c.size / 1e6) + if (done) "  |  הושמע" else "") },
                    modifier = Modifier.clickable { onPlay(c) })
            }
        }
    }
}

@Composable fun Wave(env: FloatArray?, pos: Long, dur: Long, cuts: List<Long>, marks: List<Mark>, onSeek: (Long) -> Unit) {
    val on = MaterialTheme.colorScheme.primary; val off = MaterialTheme.colorScheme.outlineVariant
    val cutC = MaterialTheme.colorScheme.tertiary; val flagC = Color(0xFFD1495B)
    Canvas(Modifier.fillMaxWidth().height(96.dp)
        .pointerInput(dur) { detectTapGestures { o -> onSeek((o.x / size.width * dur).toLong().coerceIn(0, dur)) } }
        .pointerInput(dur) { detectHorizontalDragGestures { ch, _ -> onSeek((ch.position.x / size.width * dur).toLong().coerceIn(0, dur)) } }) {
        val n = (size.width / 4).toInt().coerceAtLeast(1); val cy = size.height / 2
        if (env != null && env.isNotEmpty()) {
            val top = env.max().coerceAtLeast(0.01f)
            for (i in 0 until n) {
                val a = i * env.size / n; val b = maxOf(a + 1, (i + 1) * env.size / n)
                var m = 0f; for (k in a until minOf(b, env.size)) if (env[k] > m) m = env[k]
                val h = (m / top * cy).coerceAtLeast(1.5f); val x = i * 4f + 2f
                drawLine(if (dur > 0 && x / size.width < pos.toFloat() / dur) on else off, Offset(x, cy - h), Offset(x, cy + h), 2.5f)
            }
        }
        if (dur > 0) {
            cuts.forEach { val x = it.toFloat() / dur * size.width; drawLine(cutC, Offset(x, 0f), Offset(x, size.height), 2f) }
            marks.forEach { drawCircle(if (it.flag) flagC else on, 6f, Offset(it.ms.toFloat() / dur * size.width, 8f)) }
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
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { pos = p.currentPosition; dur = maxOf(1L, p.duration); playing = p.isPlaying; delay(250) } }
    if (cur == null) { Text("בחר הקלטה מהספרייה", Modifier.padding(16.dp)); return }
    val marks = d.marks.filter { it.uri == cur.uri.toString() }.sortedBy { it.ms }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(cur.name, maxLines = 1, style = MaterialTheme.typography.titleMedium)
        Wave(env, pos, dur, cuts, marks) { p.seekTo(it) }
        Text(if (env == null) "מנתח את ההקלטה..." else "${fmt(pos)} / ${fmt(dur)}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ p.seekTo(maxOf(0, pos - 30_000)) }, contentPadding = PaddingValues(8.dp)) { Text("-30") }
            Button({ if (p.isPlaying) p.pause() else p.play() }) { Text(if (playing) "השהה" else "נגן") }
            OutlinedButton({ p.seekTo(pos + 30_000) }, contentPadding = PaddingValues(8.dp)) { Text("+30") }
            OutlinedButton({ val l = listOf(1f, 1.25f, 1.5f, 2f, 2.5f, 3f); speed = l[(l.indexOf(speed) + 1) % l.size]; p.setPlaybackSpeed(speed) }, contentPadding = PaddingValues(8.dp)) { Text("${speed}x") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("מעבד דיבור"); Switch(d.fx, { v -> set { it.copy(fx = v) }; Audio.fx?.on(v) })
            Text("דלג שקטים"); Switch(skip, { skip = it; p.skipSilenceEnabled = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(note, { note = it }, Modifier.weight(1f), label = { Text("הערה") }, singleLine = true)
            Button({ set { x -> x.copy(marks = x.marks + Mark(cur.uri.toString(), p.currentPosition, note.ifBlank { "סימנייה" })) }; note = "" }, contentPadding = PaddingValues(8.dp)) { Text("סימנייה") }
            OutlinedButton({ set { x -> x.copy(marks = x.marks + Mark(cur.uri.toString(), p.currentPosition, "דגל", true)) } }, contentPadding = PaddingValues(8.dp)) { Text("דגל") }
        }
        Button(onGesture, Modifier.fillMaxWidth()) { Text("מצב הליכה (מחוות)") }
        LazyColumn {
            itemsIndexed(cuts) { i, ms -> ListItem(headlineContent = { Text("פרק ${i + 2}") }, overlineContent = { Text(fmt(ms)) }, modifier = Modifier.clickable { p.seekTo(ms) }) }
            items(marks) { m -> ListItem(headlineContent = { Text(m.text) }, overlineContent = { Text((if (m.flag) "דגל " else "") + fmt(m.ms)) }, modifier = Modifier.clickable { p.seekTo(m.ms) }) }
        }
    }
}

/** Whole screen is a gesture pad. Tap: play/pause. Swipe right/left: +/-30 s. Swipe up: bookmark. Swipe down: next chapter. Double tap: read aloud. */
@Composable fun GestureMode(d: AppData, cur: Clip, cuts: List<Long>, sp: Speaker, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Audio.player(ctx) }
    var step by remember { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("הקש להשמעה או השהיה") }
    var bookmarks by remember { mutableStateOf(d.marks) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
        .pointerInput(Unit) {
            detectTapGestures(onTap = { if (p.isPlaying) { p.pause(); label = "מושהה" } else { p.play(); label = "מנגן" } },
                onDoubleTap = {
                    val say = when (step++ % 3) {
                        0 -> cur.name.substringBeforeLast('.')
                        1 -> { val left = (p.duration - p.currentPosition) / 1000; "נותרו ${left / 60} דקות ו-${left % 60} שניות" }
                        else -> bookmarks.filter { it.uri == cur.uri.toString() && it.ms <= p.currentPosition }.maxByOrNull { it.ms }?.text ?: "אין סימנייה"
                    }
                    label = say; sp.say(say)
                })
        }
        .pointerInput(Unit) {
            var dx = 0f; var dy = 0f
            detectDragGestures(onDragStart = { dx = 0f; dy = 0f }, onDrag = { _, a -> dx += a.x; dy += a.y }, onDragEnd = {
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > 80) {
                    p.seekTo(maxOf(0, p.currentPosition + if (dx > 0) 30_000 else -30_000)); label = if (dx > 0) "+30 שניות" else "-30 שניות"
                } else if (dy < -80) { bookmarks = bookmarks + Mark(cur.uri.toString(), p.currentPosition, "סימנייה"); label = "סימנייה נוספה" }
                else if (dy > 80) { cuts.firstOrNull { it > p.currentPosition }?.let { p.seekTo(it); label = "פרק הבא" } }
            })
        }, contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        TextButton(onExit, Modifier.align(Alignment.TopStart)) { Text("יציאה") }
    }
}

@Composable fun StorageTab(d: AppData, all: List<Clip>, refresh: () -> Unit) {
    val ctx = LocalContext.current
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
    if (tree == null) { Text("בחר תיקייה בלשונית הספרייה", Modifier.padding(16.dp)); return }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ res = scan(ctx, all); msg = "" }, Modifier.fillMaxWidth()) { Text("סרוק כפולים, ישנים וזבל") }
        res?.let { r ->
            listOf("קבצים כפולים" to r.dups, "הקלטות ישנות (מעל 90 יום)" to r.old, "קבצי זבל זמניים" to r.junk).forEach { (t, l) ->
                OutlinedButton({ if (l.isNotEmpty()) ask = t to l }, Modifier.fillMaxWidth()) { Text("$t: ${l.size} (${"%.1f".format(l.sumOf { it.size } / 1e6)} MB)") }
            }
        }
        HorizontalDivider()
        Text("קבצים שהושמעו במלואם: ${played.size}")
        Button({ run(played) }, Modifier.fillMaxWidth()) { Text("כווץ קבצים שהושמעו (${"%.1f".format(played.sumOf { it.size } / 1e6)} MB)") }
        if (msg.isNotEmpty()) Text(msg)
    }
    ask?.let { (t, l) ->
        AlertDialog(onDismissRequest = { ask = null }, title = { Text("למחוק $t?") },
            text = { Text("${l.size} קבצים יימחקו לצמיתות: " + l.take(4).joinToString { it.name }) },
            confirmButton = { TextButton({ delete(ctx, l); ask = null; res = null; refresh(); msg = "נמחקו ${l.size} קבצים" }) { Text("מחק") } },
            dismissButton = { TextButton({ ask = null }) { Text("ביטול") } })
    }
}
