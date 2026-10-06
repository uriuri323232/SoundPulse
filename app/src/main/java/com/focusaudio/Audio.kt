package com.focusaudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

/** Speech processor: EQ boosts 300-4000 Hz and cuts rumble and hiss; a compressor evens out loud and quiet parts; a noise gate lowers background noise. */
class Fx(sid: Int) {
    private val eq = runCatching { Equalizer(0, sid) }.getOrNull()
    private val dp = if (Build.VERSION.SDK_INT >= 28) runCatching {
        val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2, false, 0, true, 1, false, 0, true).build()
        DynamicsProcessing(0, sid, cfg).also { d ->
            val b = d.getMbcBandByChannelIndex(0, 0)
            b.isEnabled = true; b.attackTime = 10f; b.releaseTime = 150f; b.ratio = 3f
            b.threshold = -40f; b.kneeWidth = 6f; b.noiseGateThreshold = -70f; b.expanderRatio = 2f; b.postGain = 8f
            d.setMbcBandAllChannelsTo(0, b)
        }
    }.getOrNull() else null

    init {
        runCatching {
            eq?.let { e ->
                val r = e.bandLevelRange
                for (i in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(i.toShort()) / 1000
                    val g = when { hz < 120 -> -1500; hz in 300..4000 -> 700; hz > 8000 -> -1200; else -> 0 }
                    e.setBandLevel(i.toShort(), g.coerceIn(r[0].toInt(), r[1].toInt()).toShort())
                }
            }
        }
        on(true)
    }
    fun on(b: Boolean) { runCatching { eq?.enabled = b; dp?.enabled = b } }
}

object Audio {
    private var p: ExoPlayer? = null
    var fx: Fx? = null
    fun player(c: Context): ExoPlayer = p ?: ExoPlayer.Builder(c.applicationContext, DefaultMediaSourceFactory(c.applicationContext, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        .also { p = it; it.setSeekParameters(SeekParameters.CLOSEST_SYNC); fx = Fx(it.audioSessionId) }

    fun play(c: Context, uri: Uri, name: String, startMs: Long = 0L) {
        val p = player(c)
        runCatching {
            p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(name).build()).build(), startMs)
            p.prepare(); p.play()
        }
        runCatching { ContextCompat.startForegroundService(c.applicationContext, Intent(c.applicationContext, PlaybackService::class.java)) }
    }
}

class PlaybackService : MediaSessionService() {
    private var s: MediaSession? = null
    override fun onCreate() { super.onCreate(); s = MediaSession.Builder(this, Audio.player(this)).build() }

    // Promote to foreground immediately so Android's 5s startForeground deadline is always met.
    // Uses Media3's own notification id/channel so its notification takes over seamlessly.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            val chId = "default_channel_id"
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm != null && nm.getNotificationChannel(chId) == null)
                    nm.createNotificationChannel(NotificationChannel(chId, "השמעה", NotificationManager.IMPORTANCE_LOW))
            }
            val n = NotificationCompat.Builder(this, chId)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("SoundPulse")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1001, n)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = s
    override fun onDestroy() { s?.release(); s = null; super.onDestroy() }
}

/** Local text-to-speech. Needs a Hebrew voice installed on the device for offline use. */
class Speaker(ctx: Context) {
    private var t: TextToSpeech? = null
    init { t = TextToSpeech(ctx.applicationContext) { if (it == TextToSpeech.SUCCESS) t?.language = Locale("he", "IL") } }
    fun say(s: String) { runCatching { t?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "f") } }
    fun shutdown() { runCatching { t?.stop(); t?.shutdown() } }
}

/** Loudness envelope: one value (0..1) per 100 ms, used for the waveform, silence detection and chapters. */
suspend fun analyze(ctx: Context, uri: Uri, key: String = uri.toString()): FloatArray = withContext(Dispatchers.IO) {
    val cf = java.io.File(ctx.cacheDir, "env_" + key.hashCode() + ".bin")
    runCatching {
        if (cf.exists()) {
            val bb = java.nio.ByteBuffer.wrap(cf.readBytes()).asFloatBuffer()
            return@withContext FloatArray(bb.remaining()).also { bb.get(it) }
        }
    }
    val oldPri = Thread.currentThread().priority
    runCatching { Thread.currentThread().priority = Thread.MIN_PRIORITY }
    val res = try { decodeEnvelope(ctx, uri) } finally { runCatching { Thread.currentThread().priority = oldPri } }
    runCatching {
        val bb = java.nio.ByteBuffer.allocate(res.size * 4); bb.asFloatBuffer().put(res); cf.writeBytes(bb.array())
    }
    res
}

private fun decodeEnvelope(ctx: Context, uri: Uri): FloatArray {
    val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
    val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
    ex.selectTrack(ti)
    val f = ex.getTrackFormat(ti)
    val per = (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) * f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) / 10).coerceAtLeast(1)
    val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
    val info = MediaCodec.BufferInfo(); val out = ArrayList<Float>()
    var eos = false; var done = false; var mx = 0; var n = 0
    try {
    codec.configure(f, null, null, 0); codec.start()
    while (!done) {
        if (!eos) {
            val i = codec.dequeueInputBuffer(5000)
            if (i >= 0) {
                val len = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                if (len < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                else { codec.queueInputBuffer(i, 0, len, ex.sampleTime, 0); ex.advance() }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 5000)
        if (o >= 0) {
            val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            while (sb.hasRemaining()) { val v = abs(sb.get().toInt()); if (v > mx) mx = v; if (++n >= per) { out.add(mx / 32768f); mx = 0; n = 0 } }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
        }
    }
    } finally { runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { ex.release() } }
    return out.toFloatArray()
}

/** Chapter starts (ms): the middle of any silence of 2 seconds or more, with at least 2 minutes between chapters. */
fun chapters(env: FloatArray?): List<Long> {
    if (env == null || env.isEmpty()) return emptyList()
    val thr = maxOf(0.01f, (env.maxOrNull() ?: 0f) * 0.05f)
    val cuts = ArrayList<Long>(); var last = 0; var i = 0
    while (i < env.size) {
        if (env[i] < thr) {
            var j = i; while (j < env.size && env[j] < thr) j++
            val mid = (i + j) / 2
            if (j - i >= 20 && mid - last >= 1200) { cuts.add(mid * 100L); last = mid }
            i = j
        } else i++
    }
    return cuts
}
