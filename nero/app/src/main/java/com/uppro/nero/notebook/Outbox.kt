package com.uppro.nero.notebook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class OutItem(val id: String, val name: String, val size: Long, val addedAt: Long, val file: File)

data class ReceivedItem(val name: String, val size: Long, val at: Long, val uri: Uri?)

/** Arquivos esperando o notebook baixar (celular → notebook) e recebidos (notebook → celular). */
object Outbox {

    private val _items = MutableStateFlow<List<OutItem>>(emptyList())
    val items: StateFlow<List<OutItem>> = _items.asStateFlow()

    private val _received = MutableStateFlow<List<ReceivedItem>>(emptyList())
    val received: StateFlow<List<ReceivedItem>> = _received.asStateFlow()

    private fun dir(context: Context) = File(context.cacheDir, "outbox").apply { mkdirs() }

    @Synchronized
    fun load(context: Context) {
        if (_items.value.isNotEmpty()) return
        _items.value = dir(context).listFiles().orEmpty()
            .mapNotNull { f ->
                val parts = f.name.split("__", limit = 2)
                if (parts.size != 2) null else OutItem(parts[0], parts[1], f.length(), f.lastModified(), f)
            }
            .sortedByDescending { it.addedAt }
    }

    /** Copia o conteúdo de [uri] para a fila. Rode fora da thread principal. */
    fun add(context: Context, uri: Uri): OutItem? = runCatching {
        load(context)
        val name = sanitize(displayName(context, uri) ?: "arquivo")
        val id = System.currentTimeMillis().toString(36) + (100..999).random()
        val target = File(dir(context), "${id}__$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it, 64 * 1024) }
        } ?: return null
        val item = OutItem(id, name, target.length(), System.currentTimeMillis(), target)
        synchronized(this) { _items.value = listOf(item) + _items.value }
        item
    }.getOrNull()

    fun remove(id: String) {
        synchronized(this) {
            _items.value.firstOrNull { it.id == id }?.file?.delete()
            _items.value = _items.value.filterNot { it.id == id }
        }
    }

    fun clear() {
        synchronized(this) {
            _items.value.forEach { it.file.delete() }
            _items.value = emptyList()
        }
    }

    fun find(id: String): OutItem? = _items.value.firstOrNull { it.id == id }

    /** Salva um arquivo vindo do notebook em Downloads/Nero. */
    fun saveIncoming(context: Context, rawName: String, length: Long, input: InputStream, onProgress: (Long) -> Unit): ReceivedItem? {
        val name = sanitize(rawName)
        var uri: Uri? = null
        val out: OutputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nero")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri!!) ?: return null
        } else {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nero")
            folder.mkdirs()
            var f = File(folder, name)
            var i = 1
            while (f.exists()) f = File(folder, name.substringBeforeLast('.') + " ($i)" + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }).also { i++ }
            uri = Uri.fromFile(f)
            f.outputStream()
        }
        var written = 0L
        val ok = runCatching {
            out.use { o ->
                val buf = ByteArray(64 * 1024)
                while (written < length) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), length - written).toInt())
                    if (r < 0) break
                    o.write(buf, 0, r)
                    written += r
                    onProgress(written)
                }
            }
            written == length
        }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val u = uri ?: return null
            if (ok) {
                context.contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } else {
                runCatching { context.contentResolver.delete(u, null, null) }
                return null
            }
        } else if (!ok) {
            return null
        }
        val item = ReceivedItem(name, written, System.currentTimeMillis(), uri)
        synchronized(this) { _received.value = listOf(item) + _received.value }
        return item
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return clean.take(120).ifBlank { "arquivo" }
    }
}
