package com.focusaudio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val SLEEP_MIN = listOf(5, 15, 30, 45, 60, 90)

@Composable private fun OptTile(modifier: Modifier, icon: ImageVector, title: String, state: String, active: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Labelled option tiles shared by all three player skins. Each tile shows what it does and its current state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlayerOptions(d: AppData, set: Setter, p: ExoPlayer, sleepLeft: Long, onSleep: (Long) -> Unit) {
    var speedDlg by remember { mutableStateOf(false) }
    var sleepDlg by remember { mutableStateOf(false) }
    val half = Modifier.weight(1f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptTile(half, Icons.Rounded.Speed, "מהירות השמעה", "${d.speed}x · הקש לבחירה", d.speed != 1f) { speedDlg = true }
            OptTile(half, Icons.Rounded.Timer, "טיימר שינה", if (sleepLeft > 0) "נותרו $sleepLeft דקות" else "כבוי · הקש להפעלה", sleepLeft > 0) { sleepDlg = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptTile(half, Icons.Rounded.Tune, "מעבד דיבור", if (d.fx) "פעיל · דיבור ברור יותר" else "כבוי", d.fx) {
                val nv = !d.fx; set { it.copy(fx = nv) }; Audio.fx?.on(nv) }
            OptTile(half, Icons.Rounded.VolumeOff, "דילוג על שקטים", if (d.skip) "פעיל" else "כבוי", d.skip) {
                val nv = !d.skip; set { it.copy(skip = nv) }; runCatching { p.skipSilenceEnabled = nv } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptTile(half, Icons.Rounded.PlaylistPlay, "המשך אוטומטי", if (d.autoNext) "עובר להקלטה הבאה" else "נעצר בסוף ההקלטה", d.autoNext) {
                val nv = !d.autoNext; set { it.copy(autoNext = nv) } }
            OptTile(half, Icons.Rounded.RepeatOne, "חזרה על ההקלטה", if (d.repeat) "פעיל" else "כבוי", d.repeat) {
                val nv = !d.repeat; set { it.copy(repeat = nv) } }
        }
    }
    if (speedDlg) AlertDialog(onDismissRequest = { speedDlg = false }, title = { Text("מהירות השמעה") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SPEEDS.forEach { s -> FilterChip(selected = d.speed == s, onClick = { set { it.copy(speed = s) }; runCatching { p.setPlaybackSpeed(s) }; speedDlg = false }, label = { Text("${s}x") }) }
            }
        }, confirmButton = { TextButton({ speedDlg = false }) { Text("סגור") } })
    if (sleepDlg) AlertDialog(onDismissRequest = { sleepDlg = false }, title = { Text("השהיה אוטומטית אחרי...") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SLEEP_MIN.forEach { m -> AssistChip(onClick = { onSleep(System.currentTimeMillis() + m * 60000L); sleepDlg = false }, label = { Text("$m דקות") }) }
            }
        },
        confirmButton = { TextButton({ sleepDlg = false }) { Text("סגור") } },
        dismissButton = { if (sleepLeft > 0) TextButton({ onSleep(0L); sleepDlg = false }) { Text("בטל טיימר") } })
}
