package com.focusaudio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF0A0A0A)
private val Coral = Color(0xFFE5566D)

@Composable fun PlayerSpotify(d: AppData, set: Setter, cur: Clip?, env: FloatArray?, cuts: List<Long>, sleepAt: Long, onSleep: (Long) -> Unit, onDelete: (Mark) -> Unit, onGesture: () -> Unit) =
    SkinPlayer(Skin.SPOTIFY, d, set, cur, env, cuts, sleepAt, onSleep, onDelete, onGesture)

@Composable fun PlayerYtm(d: AppData, set: Setter, cur: Clip?, env: FloatArray?, cuts: List<Long>, sleepAt: Long, onSleep: (Long) -> Unit, onDelete: (Mark) -> Unit, onGesture: () -> Unit) =
    SkinPlayer(Skin.YTM, d, set, cur, env, cuts, sleepAt, onSleep, onDelete, onGesture)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable private fun SkinPlayer(
    skin: Skin, d: AppData, set: Setter, cur: Clip?, env: FloatArray?, cuts: List<Long>,
    sleepAt: Long, onSleep: (Long) -> Unit, onDelete: (Mark) -> Unit, onGesture: () -> Unit
) {
    val ctx = LocalContext.current
    val p = remember { Audio.player(ctx) }
    val pal = paletteOf(d.palette)
    val accent = Color(pal.c1)
    val haptic = LocalHapticFeedback.current
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(1L) }
    var playing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var note by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Mark?>(null) }
    var sheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { runCatching { pos = p.currentPosition; dur = p.duration.let { if (it > 0) it else 1L }; playing = p.isPlaying }; now = System.currentTimeMillis() / 10000 * 10000; delay(250) } }

    val bg = Brush.verticalGradient(listOf(skinTop(skin, pal), skinBg(skin, pal), skinBg(skin, pal)))
    Box(Modifier.fillMaxSize().background(bg)) {
        if (cur == null) { Empty(Icons.Rounded.GraphicEq, "בחר הקלטה מהספרייה") }
        else {
        val key = cur.uri.toString()
        val marks = d.marks.filter { it.uri == key }.sortedBy { it.ms }
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        fun seek(ms: Long) = runCatching { p.seekTo(ms.coerceIn(0, dur)) }
        fun prevChapter() { val t = cuts.lastOrNull { it < pos - 1200 } ?: 0L; seek(t) }
        fun nextChapter() { val t = cuts.firstOrNull { it > pos + 500 }; if (t != null) seek(t) else seek(dur) }
        val sleepLeft = if (sleepAt > now) (sleepAt - now) / 60000 + 1 else 0L

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = White)
                Text(if (skin == Skin.YTM) "הנגן" else "מושמע מתוך הספרייה", style = MaterialTheme.typography.labelMedium, color = Color(0xCCFFFFFF))
                Icon(Icons.Rounded.MoreVert, null, tint = White)
            }
            // "art" = the live waveform, filling the free space
            Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp).clip(RoundedCornerShape(if (skin == Skin.YTM) 10.dp else 16.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = .9f), Color(pal.c2).copy(alpha = .55f), Color(pal.c3).copy(alpha = .4f))))) {
                Box(Modifier.align(Alignment.Center).fillMaxSize().padding(14.dp)) { Wave(env, pos, dur, cuts, marks, modifier = Modifier.fillMaxSize()) { seek(it) } }
                Text(cur.name.substringBeforeLast('.'), Modifier.align(Alignment.BottomStart).padding(14.dp),
                    color = White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cur.name.substringBeforeLast('.'), color = White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (env == null) "מנתח..." else "פרקים: ${cuts.size + 1} · סימונים: ${marks.size}", color = Color(0xB3FFFFFF), style = MaterialTheme.typography.bodySmall)
                }
                IconButton({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); set { x -> x.copy(marks = x.marks + Mark(key, pos, note.ifBlank { "דגל" }, true)) }; note = "" }) {
                    Icon(if (skin == Skin.YTM) Icons.Rounded.ThumbUp else Icons.Rounded.Favorite, "סמן", tint = accent)
                }
            }
            LinearProgressIndicator(progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) }, color = if (skin == Skin.YTM) Color(0xFFFF0033) else White,
                trackColor = Color(0x33FFFFFF), modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp)))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text(fmt(pos), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelMedium)
                Text(fmt(dur), color = Color(0xB3FFFFFF), style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                IconButton({ prevChapter() }, Modifier.size(46.dp)) { Icon(Icons.Rounded.SkipPrevious, "פרק קודם", Modifier.size(30.dp), tint = White) }
                IconButton({ seek(pos - 30_000) }, Modifier.size(46.dp)) { Icon(Icons.Rounded.Replay30, "אחורה 30", Modifier.size(28.dp), tint = White) }
                Box(Modifier.size(66.dp).clip(CircleShape).background(White).clickable { runCatching { if (p.isPlaying) p.pause() else p.play() } }, Alignment.Center) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "נגן או השהה", Modifier.size(38.dp), tint = Black)
                }
                IconButton({ seek(pos + 30_000) }, Modifier.size(46.dp)) { Icon(Icons.Rounded.Forward30, "קדימה 30", Modifier.size(28.dp), tint = White) }
                IconButton({ nextChapter() }, Modifier.size(46.dp)) { Icon(Icons.Rounded.SkipNext, "פרק הבא", Modifier.size(30.dp), tint = White) }
            }
            PlayerOptions(d, set, p, sleepLeft, onSleep)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(note, { note = it }, Modifier.weight(1f), placeholder = { Text("הערה לנקודה") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                FilledIconButton({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); set { x -> x.copy(marks = x.marks + Mark(key, pos, note.ifBlank { "סימנייה" })) }; note = "" }, Modifier.size(52.dp)) { Icon(Icons.Rounded.Bookmark, "סימנייה") }
                FilledIconButton({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); set { x -> x.copy(marks = x.marks + Mark(key, pos, note.ifBlank { "דגל" }, true)) }; note = "" }, Modifier.size(52.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Coral, contentColor = White)) { Icon(Icons.Rounded.Flag, "דגל") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onGesture, Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.DirectionsWalk, null); Spacer(Modifier.width(6.dp)); Text("מצב הליכה") }
                FilledTonalButton({ sheet = true }, Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.FormatListBulleted, null); Spacer(Modifier.width(6.dp)); Text("פרקים וסימונים") }
            }
            if (skin == Skin.YTM) Row(Modifier.fillMaxWidth().padding(top = 2.dp), Arrangement.SpaceAround) {
                Text("הבא", color = White, fontWeight = FontWeight.Bold); Text("מילים", color = Color(0x99FFFFFF)); Text("קשור", color = Color(0x99FFFFFF))
            }
        }
        if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
            ExtrasSheet(cur, key in d.played, cuts, marks, { seek(it); sheet = false }, { editing = it }, onDelete,
                onRestart = { seek(0L); sheet = false },
                onTogglePlayed = { set { x -> x.copy(played = if (key in x.played) x.played - key else x.played + key) } },
                onShare = { shareText(ctx, cur.name.substringBeforeLast('.') + " — " + fmt(pos)) })
        }
        }
    }
    editing?.let { m ->
        var t by remember(m) { mutableStateOf(m.text) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("עריכת הערה") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton({ val nt = t.ifBlank { m.text }; set { x -> x.copy(marks = x.marks.map { e -> if (e == m) e.copy(text = nt) else e }) }; editing = null }) { Text("שמור") } },
            dismissButton = { TextButton({ editing = null }) { Text("ביטול") } })
    }
}

@Composable fun SettingsScreen(d: AppData, set: Setter) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    if (showHelp) { HelpScreen { showHelp = false }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("הגדרות", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        CreditCard()

        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().clickable { showHelp = true }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("מדריך והסברים", fontWeight = FontWeight.Bold)
                    Text("מה כל פונקציה עושה ואיך משתמשים בה", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("סגנון נגן", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Skin.values().forEach { s ->
                val sel = d.skin == s.id
                Surface(color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { set { it.copy(skin = s.id) } }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = sel, onClick = { set { it.copy(skin = s.id) } })
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.label, fontWeight = FontWeight.Bold)
                            Text(when (s) { Skin.SPOTIFY -> "מראה בסגנון ספוטיפיי"; Skin.YTM -> "מראה בסגנון יוטיוב מיוזיק"; else -> "המראה המקורי עם גל-הקול" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Text("ערכת צבע", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PALETTES.forEach { pp ->
                val sel = d.palette == pp.id
                Column(Modifier.clickable { set { it.copy(palette = pp.id) } }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(46.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(pp.c1), Color(pp.c2), Color(pp.c3))))
                        .then(if (sel) Modifier.padding(0.dp) else Modifier)) {
                        if (sel) Icon(Icons.Rounded.Check, null, Modifier.align(Alignment.Center), tint = Color(0xFF07130F))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(pp.label, style = MaterialTheme.typography.labelSmall, color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text("עוצמת גרפיקה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = !d.hyper, onClick = { set { it.copy(hyper = false) } }, label = { Text("רגוע") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp)) })
            FilterChip(selected = d.hyper, onClick = { set { it.copy(hyper = true) } }, label = { Text("מוגזם") }, leadingIcon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp)) })
        }

        HorizontalDivider()
        Text("השמעה", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ToggleRow("מעבד דיבור", "מבהיר את הקול בהקלטות דיבור", d.fx) { nv -> set { it.copy(fx = nv) }; Audio.fx?.on(nv) }
        ToggleRow("דילוג על שקטים", "מקצר שתיקות ארוכות", d.skip) { nv -> set { it.copy(skip = nv) } }
        ToggleRow("המשך אוטומטי", "מעבר להקלטה הבאה בסוף", d.autoNext) { nv -> set { it.copy(autoNext = nv) } }
        ToggleRow("אישור קולי במצב הליכה", "הקראת כל פעולה", d.speak) { nv -> set { it.copy(speak = nv) } }

        Text("SoundPulse · גרסה ${BuildTag.VERSION}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable fun HelpScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton({ onBack() }, Modifier.size(40.dp)) { Icon(Icons.Rounded.ArrowForward, "חזרה") }
            Spacer(Modifier.width(10.dp))
            Text("מדריך SoundPulse", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CreditCard()
            Text("SoundPulse הוא נגן להקלטות ולשיעורים ארוכים. הכול עובד ללא אינטרנט, בלי פרסומות ובלי הרשמה. להלן כל מה שאפשר לעשות בו.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HelpItem(Icons.Rounded.GraphicEq, "גל-הקול והנגן",
                "במסך הנגן רואים את גל-הקול של ההקלטה. הקשה או גרירה עליו מקפיצה את ההשמעה לנקודה. הכפתורים: אחורה/קדימה 30 שניות, ניגון/השהיה, ומעבר לפרק הקודם/הבא. כל הפקדים נמצאים על מסך אחד בלי צורך לגלול.")

            HelpItem(Icons.Rounded.Segment, "פרקים אוטומטיים",
                "האפליקציה מזהה לבד שתיקות ארוכות בהקלטה ומחלקת אותה לפרקים, כדי שתוכל לדלג בין נושאים. רשימת הפרקים נמצאת בכפתור \"עוד\" שבתחתית הנגן.")

            HelpItem(Icons.Rounded.Restore, "המשך מאיפה שעצרת",
                "לכל הקלטה נשמר המקום האחרון. כשחוזרים אליה היא ממשיכה משם, ואף חוזרת כ-3 שניות אחורה כדי להיכנס שוב לעניין. בספרייה רואים ליד כל הקלטה \"נעצר ב-...\".")

            HelpItem(Icons.Rounded.Bookmark, "סימניות, דגלים והערות",
                "בזמן ההשמעה אפשר לכתוב הערה לנקודה הנוכחית ולשמור אותה כ‏סימנייה (כחולה) או כ‏דגל (אדום, להדגשה). אפשר לערוך ולמחוק (עם ביטול). כל הסימונים מכל ההקלטות מרוכזים בטאב \"סימונים\", עם סינון וקפיצה ישירה לנקודה, ואפשר לשתף אותם כטקסט.")

            HelpItem(Icons.Rounded.Speed, "מהירות השמעה",
                "לחיצה על שבב המהירות מחליפה בין 0.75x ל-3x. המהירות נשמרת בין הפעלות. שימושי להאזנה מהירה לשיעורים ארוכים.")

            HelpItem(Icons.Rounded.RecordVoiceOver, "מעבד דיבור",
                "שבב \"מעבד דיבור\" מפעיל עיבוד קול שמבהיר דיבור: מגביר את תחום התדרים של הדיבור, מחליש רעש רקע ורעמים, ומשווה בין קטעים חזקים וחלשים כך שהעוצמה אחידה. מצוין להקלטות שיעור לא מקצועיות. אפשר לכבות אם מעדיפים את הקול המקורי. (זמינות התכונה תלויה במכשיר.)")

            HelpItem(Icons.Rounded.FastForward, "דילוג על שקטים",
                "שבב \"דלג שקטים\" מקצר אוטומטית שתיקות ארוכות בזמן ההשמעה, כדי לחסוך זמן בלי לפספס תוכן.")

            HelpItem(Icons.Rounded.Timer, "טיימר שינה",
                "לחיצות על שבב הטיימר קובעות כיבוי אוטומטי בעוד 15 / 30 / 60 דקות, ואז הנגן נעצר לבד. לחיצה נוספת מבטלת.")

            HelpItem(Icons.Rounded.PlaylistPlay, "המשך אוטומטי",
                "כשמופעל, בסוף הקלטה האפליקציה עוברת אוטומטית להקלטה הבאה באותה תיקייה.")

            HelpItem(Icons.Rounded.DirectionsWalk, "מצב הליכה (מחוות)",
                "הופך את כל המסך לשלט מחוות, כדי להפעיל בלי להסתכל (למשל תוך כדי הליכה):\n• הקשה — ניגון/השהיה\n• החלקה ימינה — 30 שניות קדימה; שמאלה — אחורה (אפשר להפוך כיוון)\n• החלקה למעלה — סימנייה; למטה — הפרק הבא\n• לחיצה ארוכה — דגל\n• הקשה כפולה — הקראה קולית (שם ההקלטה / כמה נשאר / הסימנייה האחרונה)\nכל פעולה מלווה ברטט ובהבהוב, ואפשר להפעיל אישור קולי שמקריא כל פעולה. המסך נשאר דלוק.")

            HelpItem(Icons.Rounded.FolderOpen, "ספרייה וניווט תיקיות",
                "בוחרים תיקיית הקלטות פעם אחת. אפשר להיכנס לתת-תיקיות ולחזור אחורה כמו בסייר קבצים, לחפש הקלטות ותיקיות, לסנן \"שלא הושמעו\" ולמיין \"החדשות קודם\". נגן מיני מופיע בתחתית בכל הלשוניות.")

            HelpItem(Icons.Rounded.Compress, "כיווץ הקלטות שהושמעו",
                "בלשונית \"אחסון\" אפשר לחסוך מקום: האפליקציה ממירה ל-AAC (איכות דיבור) רק הקלטות שכבר הושמעו במלואן, וכך הן תופסות הרבה פחות מקום. הקלטות שלא הושמעו לא נוגעים בהן. אפשר לסמן הקלטה כ\"הושמעה\" ידנית מכפתור \"עוד\" שבנגן. באותו מסך יש גם סורק שמאתר קבצים כפולים, ישנים וזבל למחיקה.")

            HelpItem(Icons.Rounded.Palette, "עיצוב וערכות",
                "בהגדרות אפשר לבחור סגנון נגן (SoundPulse / Spotify / YouTube Music), ערכת צבע מתוך 12, ומצב גרפיקה רגוע או מוגזם. הבחירה לא משנה אף פונקציה — רק את המראה.")

            Text("SoundPulse · גרסה ${BuildTag.VERSION}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun HelpItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = { onChange(it) })
    }
}

/** Prominent credit banner, shown at the top of Settings and the guide. */
@Composable fun CreditCard(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(c.primary, c.tertiary))).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(c.onPrimary.copy(alpha = .2f)), Alignment.Center) {
                Icon(Icons.Rounded.Favorite, null, tint = c.onPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("פותח על ידי", style = MaterialTheme.typography.labelMedium, color = c.onPrimary.copy(alpha = .85f))
                Text("יהודי לא פשוט", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = c.onPrimary)
                Text("@מתמחים טופ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.onPrimary)
            }
        }
    }
}
