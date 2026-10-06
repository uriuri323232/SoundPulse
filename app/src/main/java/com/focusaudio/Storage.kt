package com.focusaudio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.security.MessageDigest

class Scan(val dups: List<Clip>, val old: List<Clip>, val junk: List<Clip>)

private fun hash(ctx: Context, u: Uri): String {
    val md = MessageDigest.getInstance("MD5"); val b = ByteArray(65536)
    runCatching { ctx.contentResolver.openInputStream(u)?.use { s -> while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun scan(ctx: Context, all: List<Clip>, oldDays: Int = 90): Scan {
    val junk = all.filter { val n = it.name.lowercase()
        n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".part") || n.endsWith(".crdownload") || n.startsWith("~") || n.startsWith(".") || it.size == 0L }
    val aud = all.filter { it.audio && it !in junk }
    val dups = aud.groupBy { it.size }.values.filter { it.size > 1 }.flatMap { g ->
        g.groupBy { hash(ctx, it.uri) }.values.filter { it.size > 1 }.flatMap { it.sortedBy { c -> c.mod }.drop(1) }
    }
    val old = aud.filter { it !in dups && System.currentTimeMillis() - it.mod > oldDays * 86_400_000L }
    return Scan(dups, old, junk)
}

fun delete(ctx: Context, list: List<Clip>) = list.forEach { runCatching { DocumentFile.fromSingleUri(ctx, it.uri)?.delete() } }

private const val BITRATE = 48000

/** Rough size of the AAC result, from the duration. Lets us skip files that would not shrink, before spending time encoding them. */
private fun worthCompressing(ctx: Context, c: Clip): Boolean = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, c.uri)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        ms <= 0L || ms / 1000.0 * (BITRATE / 8.0) * 1.1 < c.size * 0.75
    } finally { r.release() }
}.getOrDefault(true)

/**
 * Re-encodes to AAC (.m4a) at 48 kbps. The original is deleted only if the result is clearly smaller.
 * Files that cannot shrink are skipped quickly, and the caller can run several of these in parallel.
 */
fun compress(ctx: Context, tree: Uri, c: Clip, done: (Boolean) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        val worth = worthCompressing(ctx, c)
        main.post { if (!worth) done(false) else export(ctx, tree, c, done) }
    }.start()
}

private fun export(ctx: Context, tree: Uri, c: Clip, done: (Boolean) -> Unit) {
    val out = File(ctx.cacheDir, "c_${c.uri.toString().hashCode()}.m4a")
    val enc = DefaultEncoderFactory.Builder(ctx)
        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(BITRATE).build()).build()
    val item = EditedMediaItem.Builder(MediaItem.fromUri(c.uri)).setRemoveVideo(true).build()
    Transformer.Builder(ctx).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(enc)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                Thread {
                    var ok = false
                    if (out.length() in 1 until (c.size * 0.75).toLong()) runCatching {
                        val nf = DocumentFile.fromTreeUri(ctx, tree)!!.createFile("audio/mp4", c.name.substringBeforeLast('.') + ".m4a")!!
                        ctx.contentResolver.openOutputStream(nf.uri)!!.use { o -> out.inputStream().use { it.copyTo(o, 256 * 1024) } }
                        DocumentFile.fromSingleUri(ctx, c.uri)?.delete(); ok = true
                    }
                    out.delete()
                    Handler(Looper.getMainLooper()).post { done(ok) }
                }.start()
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { out.delete(); done(false) }
        }).build().let { tr -> runCatching { tr.start(item, out.absolutePath) }.onFailure { out.delete(); done(false) } }
}
