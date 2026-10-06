package com.uppro.nero.notebook

import android.content.Context
import com.uppro.nero.data.NeroState
import com.uppro.nero.data.Store
import com.uppro.nero.data.TransferDirection
import com.uppro.nero.data.TransferInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo (sem bibliotecas) para trocar arquivos com o notebook pela rede Wi‑Fi.
 * Protegido por um PIN de 4 dígitos mostrado no app.
 */
class NotebookServer(private val context: Context) {

    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    var port: Int = 0
        private set

    fun start(): Int {
        stop()
        val executor = Executors.newCachedThreadPool()
        pool = executor
        var socket: ServerSocket? = null
        for (p in 8080..8095) {
            socket = runCatching { ServerSocket(p) }.getOrNull()
            if (socket != null) {
                port = p
                break
            }
        }
        val s = socket ?: throw IllegalStateException("Nenhuma porta livre")
        server = s
        executor.execute {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    continue
                }
                executor.execute { handle(client) }
            }
        }
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
    )

    private fun handle(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 60_000
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                val out = s.getOutputStream()
                val req = readRequest(input) ?: return
                route(req, out)
                out.flush()
            }
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.length > 16 * 1024) return null
        }
        val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(" ") ?: return null
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        val target = first[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            decode(k) to decode(v)
        }
        return Request(first[0].uppercase(), decode(path), query, headers, input)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun authorized(req: Request): Boolean {
        val pin = Store.settings.value.notebookPin
        val given = req.headers["x-nero-pin"] ?: req.query["pin"]
        return given == pin
    }

    private fun route(req: Request, out: OutputStream) {
        when {
            req.method == "GET" && (req.path == "/" || req.path == "/index.html") -> {
                val html = context.assets.open("notebook.html").use { it.readBytes() }
                respond(out, 200, "text/html; charset=utf-8", html)
            }
            !req.path.startsWith("/api/") -> respond(out, 404, "text/plain", "Não encontrado".toByteArray())
            !authorized(req) -> respond(out, 401, "application/json", """{"error":"pin"}""".toByteArray())
            req.method == "GET" && req.path == "/api/files" -> {
                val arr = JSONArray()
                Outbox.items.value.forEach {
                    arr.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("addedAt", it.addedAt))
                }
                val body = JSONObject().put("device", android.os.Build.MODEL).put("files", arr).toString()
                respond(out, 200, "application/json", body.toByteArray())
            }
            req.method == "GET" && req.path.startsWith("/api/file/") -> sendFile(req.path.removePrefix("/api/file/"), out)
            req.method == "DELETE" && req.path.startsWith("/api/file/") -> {
                Outbox.remove(req.path.removePrefix("/api/file/"))
                respond(out, 200, "application/json", "{}".toByteArray())
            }
            req.method == "POST" && req.path == "/api/upload" -> receive(req, out)
            else -> respond(out, 404, "text/plain", "Não encontrado".toByteArray())
        }
    }

    private fun sendFile(id: String, out: OutputStream) {
        val item = Outbox.find(id)
        if (item == null || !item.file.exists()) {
            respond(out, 404, "text/plain", "Arquivo não está mais disponível".toByteArray())
            return
        }
        val size = item.file.length()
        val encoded = java.net.URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: $size\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$encoded\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        var sent = 0L
        var lastUpdate = 0L
        progress(item.name, size, 0, TransferDirection.TO_NOTEBOOK, false)
        item.file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                sent += r
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 150) {
                    lastUpdate = now
                    progress(item.name, size, sent, TransferDirection.TO_NOTEBOOK, false)
                }
            }
        }
        out.flush()
        progress(item.name, size, sent, TransferDirection.TO_NOTEBOOK, sent == size)
    }

    private fun receive(req: Request, out: OutputStream) {
        val length = req.headers["content-length"]?.toLongOrNull()
        val name = req.query["name"].orEmpty().ifBlank { "arquivo" }
        if (length == null || length <= 0) {
            respond(out, 411, "application/json", """{"error":"length"}""".toByteArray())
            return
        }
        var lastUpdate = 0L
        progress(name, length, 0, TransferDirection.FROM_NOTEBOOK, false)
        val item = Outbox.saveIncoming(context, name, length, req.body) { done ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 150) {
                lastUpdate = now
                progress(name, length, done, TransferDirection.FROM_NOTEBOOK, false)
            }
        }
        if (item == null) {
            NeroState.setTransfer(null)
            respond(out, 500, "application/json", """{"error":"save"}""".toByteArray())
            return
        }
        progress(item.name, length, length, TransferDirection.FROM_NOTEBOOK, true)
        respond(out, 200, "application/json", JSONObject().put("saved", item.name).toString().toByteArray())
    }

    private fun progress(name: String, total: Long, done: Long, dir: TransferDirection, finished: Boolean) {
        NeroState.setTransfer(TransferInfo(name, total, done, dir, finished, System.currentTimeMillis()))
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 411 -> "Length Required"; else -> "Error"
        }
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
    }

    companion object {
        /** IP do celular na rede Wi‑Fi (ou do roteador do celular, no modo hotspot). */
        fun localIp(): String? {
            val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
                .filter { (_, addr) -> addr.isSiteLocalAddress }
            val preferred = candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") }
                ?: candidates.firstOrNull { (name, _) -> !name.startsWith("rmnet") && !name.startsWith("ccmni") && !name.startsWith("tun") }
            return preferred?.second?.hostAddress
        }
    }
}
