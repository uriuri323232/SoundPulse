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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
            val initial = remember { store.load() }
            var themeKey by remember { mutableStateOf(initial.palette to initial.skin) }
            val scheme = remember(themeKey) { schemeFor(paletteOf(themeKey.first), skinOf(themeKey.second)) }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = scheme) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App(store) { pal, sk -> themeKey = pal to sk }
                    }
                }
            }
        }
    }
}

fun fmt(ms: Long): String { val s = (ms.coerceAtLeast(0) / 1000); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

fun crashFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "last_crash.txt")
fun readCrash(ctx: android.content.Context): String? = runCatching { crashFile(ctx).let { if (it.exists() && it.length() > 0) it.readText() else null } }.getOrNull()
fun clearCrash(ctx: android.content.Context) { runCatching { crashFile(ctx).delete() } }
fun copyText(ctx: android.content.Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("SoundPulse crash", text))
    }
}

fun shareText(ctx: android.content.Context, text: String) {
    runCatching {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun marksText(marks: List<Mark>, nameOf: (String) -> String): String =
    marks.groupBy { it.uri }.entries.joinToString("\n\n") { e ->
        nameOf(e.key) + "\n" + e.value.sortedBy { it.ms }.joinToString("\n") { (if (it.flag) "🚩 " else "🔖 ") + fmt(it.ms) + "  " + it.text }
    }

@Composable fun App(store: Store, onTheme: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(store.load()) }
    val set: Setter = { f -> d = f(d); val snap = d; scope.launch(Dispatchers.IO) { store.save(snap) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var all by remember { mutableStateOf(listOf<Clip>()) }
    var subfolders by remember { mutableStateOf(listOf<Folder>()) }
    var path by remember { mutableStateOf(listOf<androidx.documentfile.provider.DocumentFile>()) }
    var cur by remember { mutableStateOf<Clip?>(null) }
    var env by remember { mutableStateOf<FloatArray?>(null) }
    var gesture by rememberSaveable { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var crashLog by remember { mutableStateOf(readCrash(ctx)) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    val sp = remember { Speaker(ctx) }
    val p = remember { Audio.player(ctx) }
    val cuts = remember(env) { chapters(env) }
    val snack = remember { SnackbarHostState() }
    val dS by rememberUpdatedState(d)
    val allS by rememberUpdatedState(all)
    val curS by rememberUpdatedState(cur)
    fun browseCurrent() {
        val dir = path.lastOrNull() ?: return
        scope.launch { val r = withContext(Dispatchers.IO) { browse(ctx, dir) }; subfolders = r.first; all = r.second }
    }
    fun refresh() { browseCurrent() }
    val enterFolder: (Folder) -> Unit = { f -> path = path + f.doc }
    val goBack: () -> Unit = { if (path.size > 1) path = path.dropLast(1) }
    val playClip: (Clip, Long) -> Unit = { c, start ->
        curS?.let { old -> runCatching { val k = old.uri.toString(); val ps = p.currentPosition; set { x -> x.copy(pos = x.pos + (k to ps)) } } }
        val st = if (start >= 0) start else maxOf(0L, (dS.pos[c.uri.toString()] ?: 0L) - 3000L)
        cur = c
        Audio.play(ctx, c.uri, c.name, st)
        runCatching { p.setPlaybackSpeed(dS.speed); p.skipSilenceEnabled = dS.skip }
    }
    val playRef by rememberUpdatedState(playClip)
    LaunchedEffect(d.folder) {
        val root = d.folder?.let { withContext(Dispatchers.IO) { rootDoc(ctx, Uri.parse(it)) } }
        path = if (root != null) listOf(root) else emptyList()
    }
    LaunchedEffect(path) { if (path.isNotEmpty()) browseCurrent() else { all = emptyList(); subfolders = emptyList() } }
    LaunchedEffect(cur) {
        env = null
        cur?.let { c -> env = runCatching { analyze(ctx, c.uri, c.uri.toString() + c.size + c.mod) }.getOrNull() }
    }
    LaunchedEffect(err) { err?.let { snack.showSnackbar(it); err = null } }
    LaunchedEffect(Unit) { runCatching { p.setPlaybackSpeed(dS.speed); p.skipSilenceEnabled = dS.skip } }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0L) {
            while (System.currentTimeMillis() < sleepAt) delay(1000)
            runCatching { p.pause() }; sleepAt = 0L
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            val c = curS
            if (c != null && p.isPlaying) { val k = c.uri.toString(); val ps = p.currentPosition; set { x -> x.copy(pos = x.pos + (k to ps)) } }
        }
    }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) curS?.let { c ->
                    val k = c.uri.toString()
                    set { x -> x.copy(played = x.played + k, pos = x.pos - k) }
                    if (dS.autoNext) {
                        val l2 = allS.filter { it.audio }
                        val i = l2.indexOfFirst { it.uri == c.uri }
                        if (i >= 0 && i + 1 < l2.size) playRef(l2[i + 1], 0L)
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && p.playbackState != Player.STATE_ENDED) curS?.let { c ->
                    val k = c.uri.toString(); val ps = p.currentPosition
                    if (ps > 0) set { x -> x.copy(pos = x.pos + (k to ps)) }
                }
            }
            override fun onPlayerError(error: PlaybackException) { err = "לא ניתן לנגן את הקובץ הזה" }
        }
        p.addListener(l); onDispose { p.removeListener(l); sp.shutdown() }
    }
    LaunchedEffect(d.fx) { Audio.fx?.on(d.fx) }
    LaunchedEffect(d.repeat) { runCatching { p.repeatMode = if (d.repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF } }
    LaunchedEffect(d.skin, d.palette) { onTheme(d.palette, d.skin) }
    val deleteMark: (Mark) -> Unit = { m ->
        set { x -> x.copy(marks = x.marks - m) }
        scope.launch {
            val r = snack.showSnackbar("הסימון נמחק", "ביטול", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) set { x -> x.copy(marks = x.marks + m) }
        }
    }
    if (gesture && cur != null) { GestureMode(d, set, cur!!, cuts, sp) { gesture = false }; return }
    val tabs = listOf("ספרייה" to Icons.Rounded.LibraryMusic, "נגן" to Icons.Rounded.GraphicEq, "סימונים" to Icons.Rounded.Bookmarks, "אחסון" to Icons.Rounded.CleaningServices, "הגדרות" to Icons.Rounded.Settings)
    val skin = skinOf(d.skin)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) },
        bottomBar = { Column {
            val c = cur
            if (c != null && tab != 1) MiniPlayer(c) { tab = 1 }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, (l, ic) -> NavigationBarItem(tab == i, { tab = i }, { Icon(ic, null) }, label = { Text(l, maxLines = 1) }) }
            }
        } }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> Library(d, set, all, subfolders, (path.size <= 1), path.lastOrNull()?.name, enterFolder, goBack, cur) { c -> playClip(c, -1L); tab = 1 }
                1 -> when (skin) {
                    Skin.SPOTIFY -> PlayerSpotify(d, set, cur, env, cuts, sleepAt, { sleepAt = it }, deleteMark) { gesture = true }
                    Skin.YTM -> PlayerYtm(d, set, cur, env, cuts, sleepAt, { sleepAt = it }, deleteMark) { gesture = true }
                    else -> PlayerTab(d, set, cur, env, cuts, sleepAt, { sleepAt = it }, deleteMark) { gesture = true }
                }
                2 -> MarksTab(d, all, deleteMark) { uri, ms ->
                    val c = all.find { it.uri.toString() == uri }
                    if (c == null) err = "ההקלטה לא נמצאת בתיקייה הנוכחית" else { playClip(c, ms); tab = 1 }
                }
                3 -> StorageTab(d, all, ::refresh)
                else -> SettingsScreen(d, set)
            }
        }
    }
    crashLog?.let { log ->
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.BugReport, null) },
            title = { Text("האפליקציה נסגרה בפעם הקודמת") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("נשמר דוח על מה שקרה. אפשר להעתיק או לשתף אותו כדי שנתקן. שום דבר לא נשלח אוטומטית.",
                        style = MaterialTheme.typography.bodyMedium)
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        Text(log.take(900), Modifier.padding(10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ copyText(ctx, log); err = "הדוח הועתק" }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("העתק") }
                    TextButton({ shareText(ctx, "SoundPulse crash:\n\n" + log) }) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("שתף") }
                }
            },
            dismissButton = { TextButton({ clearCrash(ctx); crashLog = null }) { Text("סגור ומחק") } }
        )
    }
}

@Composable fun MiniPlayer(cur: Clip, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Audio.player(ctx) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(1L) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { runCatching { pos = p.currentPosition; dur = p.duration.let { if (it > 0) it else 1L }; playing = p.isPlaying }; delay(500) } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column {
            LinearProgressIndicator(progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
            Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(cur.name.substringBeforeLast('.'), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmt(pos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ runCatching { if (p.isPlaying) p.pause() else p.play() } }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "נגן או השהה") }
            }
        }
    }
}

@Composable fun MarksTab(d: AppData, all: List<Clip>, onDelete: (Mark) -> Unit, onOpen: (String, Long) -> Unit) {
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shown = d.marks.filter { filter == 0 || (filter == 1 && it.flag) || (filter == 2 && !it.flag) }
    fun nameOf(u: String) = all.find { it.uri.toString() == u }?.name?.substringBeforeLast('.') ?: "הקלטה שאינה זמינה"
    val groups = shown.groupBy { it.uri }.entries.toList()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("דגלים והערות", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            IconButton({ if (shown.isNotEmpty()) shareText(ctx, marksText(shown) { nameOf(it) }) }) { Icon(Icons.Rounded.Share, "שתף") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("הכל (${d.marks.size})") })
            FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("דגלים") })
            FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("סימניות") })
        }
        if (shown.isEmpty()) Empty(Icons.Rounded.Flag, "עוד אין סימונים. בזמן ההשמעה לחץ על דגל או סימנייה")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            groups.forEach { g ->
                item(key = "h" + g.key) { Text(nameOf(g.key), Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                items(g.value.sortedBy { it.ms }) { m ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(m.uri, m.ms) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (m.flag) Icons.Rounded.Flag else Icons.Rounded.Bookmark, null, tint = if (m.flag) Coral else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp)); Text(m.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmt(m.ms), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton({ onDelete(m) }) { Icon(Icons.Rounded.Close, "מחק", Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }
}

@Composable fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) =
    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp)); Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

@Composable fun Library(d: AppData, set: Setter, files: List<Clip>, folders: List<Folder>, atRoot: Boolean, folderName: String?, onEnter: (Folder) -> Unit, onBack: () -> Unit, cur: Clip?, onPlay: (Clip) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) runCatching {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            set { it.copy(folder = u.toString()) }
        }
    }
    var q by rememberSaveable { mutableStateOf("") }
    var onlyNew by rememberSaveable { mutableStateOf(false) }
    var newest by rememberSaveable { mutableStateOf(false) }
    val base = files.filter { it.audio }
    val list = base.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
        .filter { !onlyNew || it.uri.toString() !in d.played }
        .let { if (newest) it.sortedByDescending { c -> c.mod } else it }
    val shownFolders = folders.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("הספרייה שלי", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        FilledTonalButton({ pick.launch(null) }, Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(if (d.folder == null) "בחר תיקיית הקלטות" else "החלף תיקייה")
        }
        if (d.folder != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!atRoot) {
                    FilledTonalIconButton({ onBack() }, Modifier.size(38.dp)) { Icon(Icons.Rounded.ArrowForward, "חזרה") }
                    Spacer(Modifier.width(8.dp))
                } else { Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)) }
                Text(if (atRoot) "בית" else (folderName ?: "תיקייה"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("חיפוש הקלטה או תיקייה") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = onlyNew, onClick = { onlyNew = !onlyNew }, label = { Text("שלא הושמעו") })
                FilterChip(selected = newest, onClick = { newest = !newest }, label = { Text("החדשות קודם") })
                Text("${shownFolders.size} תיקיות · ${list.size} הקלטות", Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (d.folder == null) Empty(Icons.Rounded.LibraryMusic, "בחר תיקייה כדי להתחיל")
        else if (list.isEmpty() && shownFolders.isEmpty()) Empty(Icons.Rounded.LibraryMusic, if (q.isBlank()) "התיקייה ריקה" else "אין תוצאות לחיפוש")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(shownFolders, key = { "f:" + it.name }) { f ->
                Card(Modifier.fillMaxWidth().clickable { onEnter(f) }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = .18f)), Alignment.Center) {
                            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Icon(Icons.Rounded.ChevronLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(list, key = { "c:" + it.uri.toString() }) { c ->
                val k = c.uri.toString()
                val done = k in d.played; val active = c.uri == cur?.uri
                val nm = d.marks.count { it.uri == k }
                val resume = d.pos[k] ?: 0L
                Card(Modifier.fillMaxWidth().clickable { onPlay(c) }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .15f)), Alignment.Center) {
                            Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("%.1f MB".format(c.size / 1e6) + (if (done) "  •  הושמע" else "") + (if (resume > 5000 && !done) "  •  נעצר ב-${fmt(resume)}" else "") + (if (nm > 0) "  •  $nm סימונים" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Precomputed bar heights (0..1), so drawing never scans the whole recording. */
fun peaks(env: FloatArray?, n: Int): FloatArray {
    if (env == null || env.isEmpty()) return FloatArray(0)
    val out = FloatArray(n); var top = 0.01f
    for (i in 0 until n) {
        val a = (i.toLong() * env.size / n).toInt(); val b = maxOf(a + 1, ((i + 1).toLong() * env.size / n).toInt()).coerceAtMost(env.size)
        var m = 0f; for (k in a until b) if (env[k] > m) m = env[k]
        out[i] = m; if (m > top) top = m
    }
    for (i in out.indices) out[i] = out[i] / top
    return out
}

@Composable fun Wave(env: FloatArray?, pos: Long, dur: Long, cuts: List<Long>, marks: List<Mark>, modifier: Modifier = Modifier.fillMaxWidth().height(110.dp), onSeek: (Long) -> Unit) {
    val on = MaterialTheme.colorScheme.primary; val off = MaterialTheme.colorScheme.outlineVariant
    val cutC = MaterialTheme.colorScheme.tertiary
    val bars = remember(env) { peaks(env, 160) }
    var drag by remember { mutableStateOf<Float?>(null) }   // seek only when the finger lifts, so dragging stays smooth
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(dur) { detectTapGestures { o -> onSeek((o.x / size.width * dur).toLong().coerceIn(0, dur)) } }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { onSeek((it * dur).toLong().coerceIn(0, dur)) }; drag = null },
                        onDragCancel = { drag = null }
                    ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
                }) {
                val cy = size.height / 2
                val frac = drag ?: if (dur > 0) pos.toFloat() / dur else 0f
                if (bars.isNotEmpty()) {
                    val n = bars.size; val bw = size.width / n
                    for (i in 0 until n) {
                        val h = (bars[i] * cy * .9f).coerceAtLeast(2f); val x = i * bw + bw / 2
                        drawLine(if (x / size.width < frac) on else off, Offset(x, cy - h), Offset(x, cy + h), (bw * .62f).coerceAtLeast(2f), StrokeCap.Round)
                    }
                }
                if (dur > 0) {
                    cuts.forEach { val x = it.toFloat() / dur * size.width; drawLine(cutC, Offset(x, 0f), Offset(x, size.height), 2f) }
                    marks.forEach { val x = it.ms.toFloat() / dur * size.width
                        if (it.flag) { drawLine(Coral, Offset(x, 0f), Offset(x, size.height), 3f); drawCircle(Coral, 9f, Offset(x, 9f)) }
                        else drawCircle(on, 7f, Offset(x, size.height - 9f)) }
                    drawLine(Color.White, Offset(frac * size.width, 0f), Offset(frac * size.width, size.height), 3f)
                }
            }
            drag?.let { f ->
                Surface(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
                    Text(fmt((f * dur).toLong()), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable fun PlayerTab(d: AppData, set: Setter, cur: Clip?, env: FloatArray?, cuts: List<Long>, sleepAt: Long, onSleep: (Long) -> Unit, onDelete: (Mark) -> Unit, onGesture: () -> Unit) {
    val ctx = LocalContext.current
    val p = remember { Audio.player(ctx) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(1L) }
    var playing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var note by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Mark?>(null) }
    var sheet by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) { while (true) { runCatching { pos = p.currentPosition; dur = p.duration.let { if (it > 0) it else 1L }; playing = p.isPlaying }; now = System.currentTimeMillis() / 10000 * 10000; delay(250) } }
    if (cur == null) { Empty(Icons.Rounded.GraphicEq, "בחר הקלטה מהספרייה"); return }
    val key = cur.uri.toString()
    val marks = d.marks.filter { it.uri == key }.sortedBy { it.ms }
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    fun seek(ms: Long) = runCatching { p.seekTo(ms.coerceIn(0, dur)) }
    fun prevChapter() { seek(cuts.lastOrNull { it < pos - 1200 } ?: 0L) }
    fun nextChapter() { val t = cuts.firstOrNull { it > pos + 500 }; if (t != null) seek(t) else seek(dur) }
    val sleepLeft = if (sleepAt > now) (sleepAt - now) / 60000 + 1 else 0L

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(cur.name.substringBeforeLast('.'), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Card(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(14.dp).fillMaxSize()) {
                Wave(env, pos, dur, cuts, marks, modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 90.dp)) { seek(it) }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.SpaceBetween) {
                    Text(fmt(pos), style = MaterialTheme.typography.labelLarge)
                    if (env == null) Text("מנתח...", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("נותרו " + fmt(dur - pos), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            IconButton({ prevChapter() }, Modifier.size(46.dp)) { Icon(Icons.Rounded.SkipPrevious, "פרק קודם", Modifier.size(28.dp)) }
            IconButton({ seek(pos - 30_000) }, Modifier.size(52.dp)) { Icon(Icons.Rounded.Replay30, "אחורה 30 שניות", Modifier.size(32.dp)) }
            FilledIconButton({ runCatching { if (p.isPlaying) p.pause() else p.play() } }, Modifier.size(70.dp)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "נגן או השהה", Modifier.size(40.dp))
            }
            IconButton({ seek(pos + 30_000) }, Modifier.size(52.dp)) { Icon(Icons.Rounded.Forward30, "קדימה 30 שניות", Modifier.size(32.dp)) }
            IconButton({ nextChapter() }, Modifier.size(46.dp)) { Icon(Icons.Rounded.SkipNext, "פרק הבא", Modifier.size(28.dp)) }
        }
        PlayerOptions(d, set, p, sleepLeft, onSleep)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(note, { note = it }, Modifier.weight(1f), placeholder = { Text("הערה לנקודה") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            FilledIconButton({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); set { x -> x.copy(marks = x.marks + Mark(key, pos, note.ifBlank { "סימנייה" })) }; note = "" }, Modifier.size(52.dp)) { Icon(Icons.Rounded.Bookmark, "הוסף סימנייה") }
            FilledIconButton({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); set { x -> x.copy(marks = x.marks + Mark(key, pos, note.ifBlank { "דגל" }, true)) }; note = "" }, Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Coral, contentColor = androidx.compose.ui.graphics.Color.White)) { Icon(Icons.Rounded.Flag, "הוסף דגל") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onGesture, Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.DirectionsWalk, null); Spacer(Modifier.width(6.dp)); Text("מצב הליכה") }
            FilledTonalButton({ sheet = true }, Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.FormatListBulleted, null); Spacer(Modifier.width(6.dp)); Text("עוד") }
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        ExtrasSheet(cur, key in d.played, cuts, marks, { seek(it); sheet = false }, { editing = it }, onDelete,
            onRestart = { seek(0L); sheet = false },
            onTogglePlayed = { set { x -> x.copy(played = if (key in x.played) x.played - key else x.played + key) } },
            onShare = { shareText(ctx, cur.name.substringBeforeLast('.') + " — " + fmt(pos)) })
    }
    editing?.let { m ->
        var t by remember(m) { mutableStateOf(m.text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("עריכת הערה") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton({ val nt = t.ifBlank { m.text }; set { x -> x.copy(marks = x.marks.map { e -> if (e == m) e.copy(text = nt) else e }) }; editing = null }) { Text("שמור") } },
            dismissButton = { TextButton({ editing = null }) { Text("ביטול") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ExtrasSheet(cur: Clip, isPlayed: Boolean, cuts: List<Long>, marks: List<Mark>, onSeek: (Long) -> Unit, onEdit: (Mark) -> Unit, onDelete: (Mark) -> Unit, onRestart: () -> Unit, onTogglePlayed: () -> Unit, onShare: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onRestart, label = { Text("מהתחלה") }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)) })
                FilterChip(selected = isPlayed, onClick = onTogglePlayed, label = { Text(if (isPlayed) "סומן כהושמע" else "סמן כהושמע") }, leadingIcon = { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) })
                AssistChip(onClick = onShare, label = { Text("שתף מיקום") }, leadingIcon = { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)) })
            }
        }
        if (cuts.isEmpty() && marks.isEmpty()) item { Text("עדיין אין פרקים או סימונים בהקלטה הזו", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (cuts.isNotEmpty()) item { Text("פרקים", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(cuts.size) { i -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSeek(cuts[i]) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Segment, null, tint = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(12.dp)); Text("פרק ${i + 2}", Modifier.weight(1f)); Text(fmt(cuts[i]), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (marks.isNotEmpty()) item { Text("סימונים והערות", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(marks.size) { i -> val m = marks[i]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSeek(m.ms) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (m.flag) Icons.Rounded.Flag else Icons.Rounded.Bookmark, null, tint = if (m.flag) Coral else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp)); Text(m.text, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fmt(m.ms), color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ onEdit(m) }) { Icon(Icons.Rounded.Edit, "ערוך", Modifier.size(18.dp)) }
                IconButton({ onDelete(m) }) { Icon(Icons.Rounded.Close, "מחק", Modifier.size(18.dp)) }
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
    var gpos by remember { mutableLongStateOf(0L) }
    val bg by animateColorAsState(if (flash) MaterialTheme.colorScheme.primary.copy(alpha = .25f) else MaterialTheme.colorScheme.background, label = "flash")
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) { while (true) { gpos = runCatching { p.currentPosition }.getOrDefault(0L); delay(500) } }
    LaunchedEffect(flash) { if (flash) { delay(250); flash = false } }
    fun show(t: String, i: androidx.compose.ui.graphics.vector.ImageVector) { label = t; icon = i; flash = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (dNow.speak) sp.say(t) }
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
                        val fwd = if (dNow.invert) dx < 0 else dx > 0
                        p.seekTo(maxOf(0, p.currentPosition + if (fwd) 30_000 else -30_000))
                        if (fwd) show("+30 שניות", Icons.Rounded.Forward30) else show("-30 שניות", Icons.Rounded.Replay30)
                    } else if (dy < -80) { val pos = p.currentPosition; set { x -> x.copy(marks = x.marks + Mark(key, pos, "סימנייה")) }; show("סימנייה נוספה", Icons.Rounded.Bookmark) }
                    else if (dy > 80) { val n = cuts.firstOrNull { it > p.currentPosition }; if (n != null) { p.seekTo(n); show("פרק הבא", Icons.Rounded.SkipNext) } else show("אין פרק הבא", Icons.Rounded.SkipNext) }
                }
            })
        }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(icon, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(fmt(gpos), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (d.invert) "הקשה: נגן/השהה  •  שמאלה: קדימה 30 שניות  •  ימינה: אחורה  •  למעלה: סימנייה  •  למטה: פרק הבא  •  לחיצה ארוכה: דגל  •  הקשה כפולה: הקראה"
             else "הקשה: נגן/השהה  •  ימינה: קדימה 30 שניות  •  שמאלה: אחורה  •  למעלה: סימנייה  •  למטה: פרק הבא  •  לחיצה ארוכה: דגל  •  הקשה כפולה: הקראה",
            Modifier.align(Alignment.BottomCenter).padding(24.dp), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onExit) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("יציאה") }
            FilterChip(selected = d.invert, onClick = { val nv = !d.invert; set { it.copy(invert = nv) } }, label = { Text("הפוך כיוון") }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)) })
            FilterChip(selected = d.speak, onClick = { val nv = !d.speak; set { it.copy(speak = nv) } }, label = { Text("קול") }, leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(18.dp)) })
        }
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
    // Two files at a time: the phone has several cores, so this is noticeably faster than one by one.
    fun run(list: List<Clip>) {
        if (list.isEmpty() || tree == null) { busy = false; msg = "הכיווץ הסתיים"; refresh(); return }
        busy = true
        val queue = list.toMutableList(); val total = list.size
        var active = 0; var finished = 0; var saved = 0
        fun pump() {
            while (active < 2 && queue.isNotEmpty()) {
                val c = queue.removeAt(0); active++
                msg = "מכווץ... ($finished מתוך $total)"
                compress(ctx, tree, c) { ok ->
                    active--; finished++; if (ok) saved++
                    if (queue.isEmpty() && active == 0) { busy = false; msg = "הכיווץ הסתיים: כווצו $saved מתוך $total"; refresh() }
                    else { msg = "מכווץ... ($finished מתוך $total)"; pump() }
                }
            }
        }
        pump()
    }
    if (tree == null) { Empty(Icons.Rounded.CleaningServices, "בחר תיקייה בלשונית הספרייה"); return }
    val playedMb = played.sumOf { it.size } / 1e6
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ניהול אחסון", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // Compress played recordings — the main space saver, made clear and prominent.
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .22f)), Alignment.Center) {
                        Icon(Icons.Rounded.Compress, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("דחיסת הקלטות שהושמעו", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("ממיר ל-AAC וחוסך מקום. הקלטות שלא הושמעו לא נוגעים בהן.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (played.isNotEmpty())
                    Text("${played.size} הקלטות שהושמעו במלואן · ${"%.1f".format(playedMb)} MB זמינות לדחיסה", style = MaterialTheme.typography.bodyMedium)
                else
                    Text("אין כרגע הקלטות שהושמעו במלואן. השמע הקלטה עד הסוף והיא תופיע כאן.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ run(played) }, Modifier.fillMaxWidth().height(50.dp), enabled = !busy && played.isNotEmpty()) {
                    Icon(Icons.Rounded.Compress, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "מכווץ..." else "כווץ עכשיו וחסוך מקום")
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        HorizontalDivider()
        Text("ניקוי קבצים", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("סורק את התיקייה ומאתר מה אפשר למחוק כדי לפנות מקום.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton({ busy = true; msg = "סורק..."; scope.launch { res = withContext(Dispatchers.IO) { runCatching { scan(ctx, all) }.getOrNull() }; busy = false; msg = if (res == null) "הסריקה נכשלה" else "" } },
            Modifier.fillMaxWidth().height(50.dp), enabled = !busy) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("סרוק כפולים, ישנים וזבל") }
        res?.let { r ->
            listOf(Triple("קבצים כפולים", Icons.Rounded.ContentCopy, r.dups), Triple("הקלטות ישנות (מעל 90 יום)", Icons.Rounded.History, r.old), Triple("קבצי זבל זמניים", Icons.Rounded.DeleteSweep, r.junk)).forEach { (t, ic, l) ->
                Card(Modifier.fillMaxWidth().clickable(enabled = l.isNotEmpty()) { ask = t to l }, shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ic, null, tint = if (l.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(12.dp))
                        Text(t, Modifier.weight(1f))
                        Text("${l.size} · ${"%.1f".format(l.sumOf { it.size } / 1e6)} MB", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    ask?.let { (t, l) ->
        AlertDialog(onDismissRequest = { ask = null }, title = { Text("למחוק $t?") },
            text = { Text("${l.size} קבצים יימחקו לצמיתות: " + l.take(4).joinToString { it.name }) },
            confirmButton = { TextButton({ ask = null; scope.launch { withContext(Dispatchers.IO) { delete(ctx, l) }; res = null; refresh(); msg = "נמחקו ${l.size} קבצים" } }) { Text("מחק") } },
            dismissButton = { TextButton({ ask = null }) { Text("ביטול") } })
    }
}
