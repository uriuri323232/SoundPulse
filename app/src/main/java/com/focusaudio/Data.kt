package com.focusaudio

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable data class Mark(val uri: String, val ms: Long, val text: String, val flag: Boolean = false)
@Serializable data class AppData(
    val folder: String? = null, val played: Set<String> = emptySet(), val marks: List<Mark> = emptyList(), val fx: Boolean = true,
    val pos: Map<String, Long> = emptyMap(), val speed: Float = 1f, val skip: Boolean = false,
    val autoNext: Boolean = true, val invert: Boolean = false, val speak: Boolean = false,
    val skin: String = "soundpulse", val palette: String = "aurora", val hyper: Boolean = false
)

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class Store(ctx: Context) {
    private val f = File(ctx.filesDir, "focus.json")
    fun load(): AppData = runCatching { json.decodeFromString(AppData.serializer(), f.readText()) }
        .recoverCatching { json.decodeFromString(AppData.serializer(), File(f.path + ".tmp").readText()) }.getOrDefault(AppData())
    @Synchronized fun save(d: AppData) = runCatching {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), d))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

class Clip(val name: String, val uri: Uri, val size: Long, val mod: Long, val audio: Boolean)

class Folder(val doc: DocumentFile, val name: String)

/** Lists the files of the chosen folder (works for internal storage and SD card via the system picker). */
fun clips(ctx: Context, tree: Uri): List<Clip> = runCatching {
    DocumentFile.fromTreeUri(ctx, tree)?.listFiles()?.filter { it.isFile }?.map {
        Clip(it.name ?: "?", it.uri, it.length(), it.lastModified(), (it.type ?: "").startsWith("audio/"))
    }?.sortedBy { it.name.lowercase() } ?: emptyList()
}.getOrDefault(emptyList())

fun rootDoc(ctx: Context, tree: Uri): DocumentFile? = runCatching { DocumentFile.fromTreeUri(ctx, tree) }.getOrNull()

/** Subfolders and files of one folder, for file-explorer style browsing. */
fun browse(ctx: Context, dir: DocumentFile): Pair<List<Folder>, List<Clip>> = runCatching {
    val items = dir.listFiles()
    val folders = items.filter { it.isDirectory }
        .map { Folder(it, it.name ?: "?") }.sortedBy { it.name.lowercase() }
    val files = items.filter { it.isFile }
        .map { Clip(it.name ?: "?", it.uri, it.length(), it.lastModified(), (it.type ?: "").startsWith("audio/")) }
        .sortedBy { it.name.lowercase() }
    folders to files
}.getOrDefault(emptyList<Folder>() to emptyList())
