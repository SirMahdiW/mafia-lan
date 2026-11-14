package ir.mafia

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class Server(private val port: Int, private val engine: Engine, private val asset: (String) -> ByteArray?) {
    private class Client(val sock: Socket, val out: OutputStream, val tok: String?)

    private val clients = CopyOnWriteArrayList<Client>()
    private val pusher = Executors.newSingleThreadExecutor()
    private var ss: ServerSocket? = null

    companion object {
        /** حداکثر حجم بدنهٔ درخواست (۶۴ کیلوبایت) تا از تخصیص حافظهٔ بزرگ جلوگیری شود. */
        const val MAX_BODY = 64 * 1024
        fun lanIps(port: Int): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && !it.isLoopbackAddress }.map { "http://${it.hostAddress}:$port" }
        } catch (e: Exception) { emptyList() }
    }

    fun start() {
        // هر استثنای خارج‌کنترل‌شده در این نخ (پس‌زمینه) در اندروید باعش مرگ برنامه می‌شود.
        engine.onChange = {
            try { pusher.execute { pushAll() } } catch (e: Throwable) { e.printStackTrace() }
        }
        engine.onReset = { clients.forEach { try { it.sock.close() } catch (e: Exception) {} }; clients.clear() }
        val s = ServerSocket(port)
        ss = s
        Thread {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                } catch (e: Exception) { }
            }
        }.start()
    }

    fun stop() { try { ss?.close() } catch (e: Exception) {}; pusher.shutdownNow(); clients.forEach { try { it.sock.close() } catch (e: Exception) {} } }

    private fun sse(c: Client, data: String): Boolean = try {
        synchronized(c) { c.out.write(("data: $data\n\n").toByteArray()); c.out.flush() }
        true
    } catch (e: Throwable) { false }

    private fun pushAll() {
        try {
            for (c in clients) if (!sse(c, engine.view(c.tok))) { clients.remove(c); try { c.sock.close() } catch (e: Exception) {} }
        } catch (e: Throwable) { e.printStackTrace() }
    }

    private fun send(o: OutputStream, code: Int, type: String, body: ByteArray) {
        val st = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        o.write("HTTP/1.1 $code $st\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        o.write(body); o.flush()
    }

    private fun mime(name: String): String = when {
        name.endsWith(".mp3") -> "audio/mpeg"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        name.endsWith(".html") -> "text/html; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun handle(sock: Socket) {
        try {
            val inp = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val head = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) { sock.close(); return }
                head.append(b.toChar())
                if (head.endsWith("\r\n\r\n")) break
                if (head.length > 16384) { sock.close(); return }
            }
            val lines = head.toString().split("\r\n")
            val parts = lines[0].split(" ")
            val method = parts[0]
            val full = parts.getOrElse(1) { "/" }
            val path = full.substringBefore("?")
            val query = full.substringAfter("?", "")
            var len = 0
            for (l in lines) if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(":").trim().toIntOrNull() ?: 0
            // جلوگیری از تخصیص حافظهٔ بزرگ (و در نتیجه از کرش پروسه) در اثر بدنهٔ نامعتبر
            if (len < 0 || len > MAX_BODY) {
                send(out, 413, "application/json; charset=utf-8", Json.write(mapOf("err" to "حجم درخواست بیش از حد مجاز است")).toByteArray())
                sock.close(); return
            }
            val buf = ByteArray(len)
            var r = 0
            while (r < len) { val n = inp.read(buf, r, len - r); if (n < 0) break; r += n }
            val body = String(buf, 0, r, Charsets.UTF_8)
            val loop = sock.inetAddress.isLoopbackAddress

            if (path == "/events") {
                val tok = query.split("&").firstOrNull { it.startsWith("t=") }?.substring(2)?.let { URLDecoder.decode(it, "UTF-8") }
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                out.flush()
                val c = Client(sock, out, tok?.ifEmpty { null })
                clients.add(c)
                sse(c, engine.view(c.tok))
                while (!sock.isClosed) {
                    Thread.sleep(15000)
                    try { synchronized(c) { out.write(": hb\n\n".toByteArray()); out.flush() } } catch (e: Exception) { break }
                }
                clients.remove(c); sock.close(); return
            }
            if (method == "POST") {
                val b = Json.parseMap(body)
                if (body.isNotBlank() && b.isEmpty()) {
                    // بدنهٔ JSON نامعتبر → پاسخ روشن به‌جای بستن بی‌صدای سوکت
                    send(out, 400, "application/json; charset=utf-8", Json.write(mapOf("err" to "درخواست نامعتبر است")).toByteArray())
                    sock.close(); return
                }
                val res: Map<String, Any?> = when (path) {
                    "/join" -> engine.join(b, loop)
                    "/local" -> engine.localStart(b)
                    "/act" -> {
                        @Suppress("UNCHECKED_CAST")
                        engine.act(b["token"] as? String, (b["m"] as? Map<String, Any?>) ?: emptyMap()); emptyMap()
                    }
                    else -> mapOf("err" to "مسیر نامعتبر است")
                }
                send(out, 200, "application/json; charset=utf-8", Json.write(res).toByteArray())
                sock.close(); return
            }
            val name = if (path == "/") "client.html" else path.removePrefix("/")
            val serve = name == "client.html" ||
                (listOf("audio/", "img/", "data/").any { name.startsWith(it) } && !name.contains(".."))
            val data = if (serve) asset(name) else null
            if (data == null) send(out, 404, "text/plain", ByteArray(0))
            else send(out, 200, mime(name), data)
            sock.close()
        } catch (e: Throwable) {
            // نخ‌های پس‌زمینه در اندروید با استثنای مهارنشده می‌میرند و کل برنامه بسته می‌شود
            e.printStackTrace()
            try { sock.close() } catch (x: Exception) {}
        }
    }
}
