package com.wip.mangauploader

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.wip.plugintoolkit.api.HttpMethod
import org.wip.plugintoolkit.api.HttpRequest
import org.wip.plugintoolkit.api.HttpResponse
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.PluginNetworkClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/** Resolved IPv4 record from DNS-over-HTTPS along with cache TTL in seconds. */
data class DohRecord(val ip: String, val ttlSec: Long = 120L)

internal object DohResolver {
    private const val CLOUDFLARE_DOH = "https://1.1.1.1/dns-query"
    private const val GOOGLE_DOH = "https://8.8.8.8/resolve"

    fun isIpLiteral(host: String): Boolean =
        IPV4_LITERAL.matches(host) || host.contains(':')

    suspend fun resolve(
        host: String,
        requester: suspend (String) -> HttpResponse = { url ->
            executeHttpUrlConnection(
                HttpRequest(
                    url = url,
                    method = HttpMethod.GET,
                    headers = mapOf("Accept" to "application/dns-json", "User-Agent" to "WOM-MangaUploader/1.0"),
                    timeoutMs = 10_000L
                ),
                proxy = null,
                basicProxyAuth = null
            )
        }
    ): DohRecord? {
        val encoded = URLEncoder.encode(host, Charsets.UTF_8)
        for (endpoint in listOf("$CLOUDFLARE_DOH?name=$encoded&type=A", "$GOOGLE_DOH?name=$encoded&type=A")) {
            val response = requester(endpoint)
            if (response.isSuccess) {
                parseDohIPv4(response.bodyAsText())?.let { return it }
            }
        }
        return null
    }

    /** Extracts the first usable public IPv4 `A` (`type == 1`) record from a DoH JSON payload. */
    fun parseDohIPv4(jsonText: String): DohRecord? {
        val root = runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull() ?: return null
        val answers = root["Answer"] as? JsonArray ?: return null
        for (item in answers) {
            val obj = item as? JsonObject ?: continue
            val type = (obj["type"] as? JsonPrimitive)?.intOrNull ?: continue
            if (type != 1) continue
            val ip = (obj["data"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!IPV4_LITERAL.matches(ip) || ip.startsWith("127.") || ip == "0.0.0.0") continue
            val ttl = ((obj["TTL"] as? JsonPrimitive)?.longOrNull ?: 120L).coerceIn(30L, 300L)
            return DohRecord(ip = ip, ttlSec = ttl)
        }
        return null
    }
}

/**
 * Shared HTTP(S) execution over [HttpURLConnection], optionally through a [Proxy].
 */
internal suspend fun executeHttpUrlConnection(
    request: HttpRequest,
    proxy: Proxy?,
    basicProxyAuth: String? = null,
    onConnectRefused: ((ConnectException) -> String?)? = null
): HttpResponse = withContext(Dispatchers.IO) {
    val conn = try {
        val targetUrl = URI(request.url).toURL()
        if (proxy != null) targetUrl.openConnection(proxy) as HttpURLConnection
        else targetUrl.openConnection() as HttpURLConnection
    } catch (t: Throwable) {
        return@withContext HttpResponse(statusCode = 0, errorMessage = t.message ?: t::class.simpleName)
    }
    try {
        val timeoutMs = (request.timeoutMs ?: 30_000L).toInt().coerceAtLeast(1_000)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.requestMethod = request.method.name

        val hasMultipart = request.multipartParts.isNotEmpty()
        request.headers.forEach { (k, v) ->
            if (!(hasMultipart && k.equals("Content-Type", ignoreCase = true))) {
                conn.setRequestProperty(k, v)
            }
        }
        basicProxyAuth?.let { conn.setRequestProperty("Proxy-Authorization", it) }

        when {
            hasMultipart -> {
                val boundary = "----WomBoundary${UUID.randomUUID().toString().replace("-", "")}"
                val encodedParts = request.multipartParts.map { part ->
                    val headerBytes = buildString {
                        append("--").append(boundary).append("\r\n")
                        append("Content-Disposition: form-data; name=\"").append(part.name).append('"')
                        part.filename?.let { append("; filename=\"").append(it).append('"') }
                        append("\r\n")
                        part.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                        append("\r\n")
                    }.encodeToByteArray()
                    headerBytes to part.data
                }
                val closingBytes = "--$boundary--\r\n".encodeToByteArray()
                val crlfBytes = "\r\n".encodeToByteArray()
                val totalLength = encodedParts.sumOf { (h, d) -> h.size.toLong() + d.size.toLong() + crlfBytes.size } + closingBytes.size
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setRequestProperty("Connection", "close")
                conn.setFixedLengthStreamingMode(totalLength)
                conn.doOutput = true
                conn.outputStream.use { out ->
                    for ((headerBytes, data) in encodedParts) {
                        out.write(headerBytes)
                        out.write(data)
                        out.write(crlfBytes)
                    }
                    out.write(closingBytes)
                    out.flush()
                }
            }
            request.body != null -> {
                conn.setFixedLengthStreamingMode(request.body!!.size.toLong())
                conn.doOutput = true
                conn.outputStream.use { it.write(request.body) }
            }
        }

        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
        val bodyBytes = if (request.method == HttpMethod.HEAD || stream == null) ByteArray(0)
        else stream.use { readAllBytes(it) }
        val headers = conn.headerFields.entries
            .filter { it.key != null }
            .associate { it.key to it.value.orEmpty() }
        HttpResponse(statusCode = code, headers = headers, body = bodyBytes)
    } catch (e: ConnectException) {
        val custom = onConnectRefused?.invoke(e)
        HttpResponse(statusCode = 0, errorMessage = custom ?: (e.message ?: "Connection refused"))
    } catch (t: Throwable) {
        HttpResponse(statusCode = 0, errorMessage = t.message ?: t::class.simpleName)
    } finally {
        conn.disconnect()
    }
}

private fun readAllBytes(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    input.copyTo(out)
    return out.toByteArray()
}

/**
 * [PluginNetworkClient] backed by JVM [HttpURLConnection] routing through an explicit HTTP or SOCKS proxy URL.
 */
class ProxyNetworkClient(private val proxyUrl: String) : PluginNetworkClient {

    private val parsedProxy: ParsedProxy by lazy { parseProxy(proxyUrl) }

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray =
        request(HttpRequest(url = url, method = HttpMethod.GET, headers = headers)).body

    override suspend fun getText(url: String, headers: Map<String, String>): String =
        get(url, headers).decodeToString()

    override suspend fun post(url: String, body: ByteArray, headers: Map<String, String>): ByteArray =
        request(HttpRequest(url = url, method = HttpMethod.POST, body = body, headers = headers)).body

    override suspend fun postText(url: String, body: String, headers: Map<String, String>): String =
        post(url, body.encodeToByteArray(), headers).decodeToString()

    override suspend fun request(request: HttpRequest): HttpResponse {
        val resp = executeHttpUrlConnection(
            request = request,
            proxy = parsedProxy.proxy,
            basicProxyAuth = parsedProxy.basicAuthHeader,
            onConnectRefused = { err ->
                if (parsedProxy.isLoopback) {
                    "Proxy (${parsedProxy.label}) refused connection (${err.message ?: "Connection refused"}) — " +
                        "no local proxy is listening on this port. Start your proxy or clear 'HTTP/SOCKS proxy URL' to use built-in DoH bypass."
                } else {
                    "Proxy (${parsedProxy.label}) ${err.message ?: "Connection refused"}"
                }
            }
        )
        return if (resp.statusCode == 0 && resp.errorMessage != null && !resp.errorMessage!!.startsWith("Proxy (")) {
            resp.copy(errorMessage = "Proxy (${parsedProxy.label}) ${resp.errorMessage}")
        } else resp
    }

    private data class ParsedProxy(val proxy: Proxy, val label: String, val basicAuthHeader: String?, val isLoopback: Boolean)

    companion object {
        private fun parseProxy(raw: String): ParsedProxy {
            val normalized = raw.trim().let { if ("://" in it) it else "http://$it" }
            val uri = URI(normalized)
            val scheme = uri.scheme?.lowercase() ?: "http"
            val host = uri.host?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Invalid proxy URL '$raw': missing host")
            val isSocks = scheme.startsWith("socks")
            val port = if (uri.port > 0) uri.port else if (isSocks) 1080 else 8080
            val type = if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
            val auth = uri.rawUserInfo?.takeIf { it.isNotBlank() }?.let {
                "Basic " + Base64.getEncoder().encodeToString(it.encodeToByteArray())
            }
            val loopback = host.equals("localhost", ignoreCase = true) ||
                runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
            return ParsedProxy(Proxy(type, InetSocketAddress(host, port)), "$scheme://$host:$port", auth, loopback)
        }
    }
}

/**
 * Lightweight in-process loopback HTTP `CONNECT` / forward relay bound to `127.0.0.1`
 * so JVM [HttpURLConnection] keeps full TLS SNI and hostname verification against [host]
 * while dialing the DoH-resolved IP instead of OS DNS's sinkholed `127.0.0.1`.
 */
class LoopbackConnectRelay : AutoCloseable {
    private val overrides = ConcurrentHashMap<String, String>()
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var closed = false

    fun putOverride(host: String, ip: String) {
        overrides[host.lowercase()] = ip
    }

    @Synchronized
    fun proxy(): Proxy {
        var ss = serverSocket
        if (ss == null || ss.isClosed) {
            ss = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
            serverSocket = ss
            closed = false
            Thread.startVirtualThread { acceptLoop(ss) }
        }
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(ss.inetAddress, ss.localPort))
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed && !ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            Thread.startVirtualThread { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { c ->
            val clientIn = c.getInputStream()
            val clientOut = c.getOutputStream()
            val headerBytes = readHeaderBlock(clientIn) ?: return
            val headerText = headerBytes.decodeToString()
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val target = parts[1]

            if (method == "CONNECT") {
                val (host, port) = parseHostPort(target, defaultPort = 443) ?: return
                val ip = overrides[host.lowercase()] ?: return sendError(clientOut, 502)
                Socket().use { remote ->
                    remote.connect(InetSocketAddress(InetAddress.getByName(ip), port), 15_000)
                    clientOut.write("HTTP/1.1 200 Connection established\r\n\r\n".encodeToByteArray())
                    clientOut.flush()
                    relayBidirectional(clientIn, clientOut, remote.getInputStream(), remote.getOutputStream())
                }
            } else {
                val uri = runCatching { URI(target) }.getOrNull() ?: return
                val host = uri.host ?: return
                val port = if (uri.port > 0) uri.port else 80
                val ip = overrides[host.lowercase()] ?: return sendError(clientOut, 502)
                val pathAndQuery = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val rewritten = buildString {
                    append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n")
                    for (line in lines.drop(1)) {
                        if (line.isBlank()) continue
                        if (line.startsWith("Proxy-Connection:", ignoreCase = true) ||
                            line.startsWith("Connection:", ignoreCase = true)
                        ) continue
                        append(line).append("\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                Socket().use { remote ->
                    remote.connect(InetSocketAddress(InetAddress.getByName(ip), port), 15_000)
                    val remoteOut = remote.getOutputStream()
                    remoteOut.write(rewritten.encodeToByteArray())
                    remoteOut.flush()
                    relayBidirectional(clientIn, clientOut, remote.getInputStream(), remoteOut)
                }
            }
        }
    }

    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < 16 * 1024) {
            val b = input.read()
            if (b == -1) return null
            out.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                matched == 1 && b == '\n'.code -> 2
                matched == 3 && b == '\n'.code -> return out.toByteArray()
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int>? {
        val clean = target.trim()
        return if (clean.startsWith("[")) {
            val closeIdx = clean.indexOf(']')
            if (closeIdx < 0) return null
            val host = clean.substring(1, closeIdx)
            val port = clean.substring(closeIdx + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            host to port
        } else {
            val idx = clean.lastIndexOf(':')
            if (idx > 0) clean.substring(0, idx) to (clean.substring(idx + 1).toIntOrNull() ?: defaultPort)
            else clean to defaultPort
        }
    }

    private fun sendError(out: OutputStream, status: Int) {
        runCatching {
            out.write("HTTP/1.1 $status Bad Gateway\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.flush()
        }
    }

    private fun relayBidirectional(cIn: InputStream, cOut: OutputStream, rIn: InputStream, rOut: OutputStream) {
        val up = Thread.startVirtualThread {
            runCatching {
                cIn.copyTo(rOut)
                rOut.flush()
            }
        }
        runCatching {
            rIn.copyTo(cOut)
            cOut.flush()
        }
        runCatching { up.join(5_000) }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        val SHARED = LoopbackConnectRelay()
    }
}

/**
 * [PluginNetworkClient] decorator that delegates normally to the host's [PluginNetworkClient],
 * but transparently routes via DoH (`1.1.1.1` / `8.8.8.8`) + loopback CONNECT relay whenever OS DNS
 * sinkholes a domain to loopback / `0.0.0.0` (e.g. ISP DNS blocks) or direct transport fails on a DoH-resolvable host.
 */
class SmartMangaDexNetworkClient(
    private val delegate: PluginNetworkClient,
    private val autoDoh: Boolean = true,
    private val logger: PluginLogger? = null,
    private val relay: LoopbackConnectRelay = LoopbackConnectRelay.SHARED,
    private val dnsLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val dohResolve: suspend (String) -> DohRecord? = { host -> DohResolver.resolve(host) },
    private val directFallback: suspend (HttpRequest) -> HttpResponse = { req ->
        executeHttpUrlConnection(req, proxy = null, basicProxyAuth = null)
    },
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : PluginNetworkClient {

    private data class CachedEntry(val ip: String, val expiresAtMs: Long)
    private val cache = ConcurrentHashMap<String, CachedEntry>()
    private val loggedBypassHosts = ConcurrentHashMap.newKeySet<String>()

    override suspend fun get(url: String, headers: Map<String, String>): ByteArray =
        request(HttpRequest(url = url, method = HttpMethod.GET, headers = headers)).body

    override suspend fun getText(url: String, headers: Map<String, String>): String =
        get(url, headers).decodeToString()

    override suspend fun post(url: String, body: ByteArray, headers: Map<String, String>): ByteArray =
        request(HttpRequest(url = url, method = HttpMethod.POST, body = body, headers = headers)).body

    override suspend fun postText(url: String, body: String, headers: Map<String, String>): String =
        post(url, body.encodeToByteArray(), headers).decodeToString()

    override suspend fun request(request: HttpRequest): HttpResponse {
        val uri = runCatching { URI(request.url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val scheme = uri?.scheme?.lowercase().orEmpty()
        val dohEligible = autoDoh && (scheme == "https" || scheme == "http") &&
            host.isNotBlank() && !DohResolver.isIpLiteral(host)

        if (!dohEligible) return delegate.request(request)

        if (isOsDnsSinkholed(host)) {
            resolveCached(host)?.let { ip ->
                return executeViaDoh(request, host, ip)
            }
        }

        val direct = delegate.request(request)
        if (direct.statusCode == 0) {
            logger?.warn("Host network call failed for ${request.method} ${request.url.substringBefore('?')}: ${direct.errorMessage ?: "unknown"}; trying direct socket fallback")
            val fallback = directFallback(request)
            if (fallback.statusCode > 0) return fallback
            logger?.warn("Direct socket fallback failed for ${request.method} ${request.url.substringBefore('?')}: ${fallback.errorMessage ?: "unknown"}; trying DoH")
            val fallbackIp = resolveCached(host)
            if (fallbackIp != null) {
                return executeViaDoh(request, host, fallbackIp)
            }
        }
        return direct
    }

    private suspend fun isOsDnsSinkholed(host: String): Boolean = withContext(Dispatchers.IO) {
        val addrs = runCatching { dnsLookup(host) }.getOrNull() ?: return@withContext true
        addrs.isEmpty() || addrs.any { it.isLoopbackAddress || it.isAnyLocalAddress }
    }

    private suspend fun resolveCached(host: String): String? {
        val key = host.lowercase()
        val now = nowMs()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { return it.ip }
        val resolved = dohResolve(host) ?: return null
        cache[key] = CachedEntry(resolved.ip, now + resolved.ttlSec * 1_000L)
        return resolved.ip
    }

    private suspend fun executeViaDoh(request: HttpRequest, host: String, ip: String): HttpResponse {
        if (loggedBypassHosts.add(host.lowercase())) {
            logger?.info("OS DNS sinkholed '$host'; routing via DoH ($ip)")
        }
        relay.putOverride(host, ip)
        return executeHttpUrlConnection(request, proxy = relay.proxy(), basicProxyAuth = null)
    }
}

data class PkcePair(val verifier: String, val challenge: String)

object OAuthPkce {
    fun generate(): PkcePair {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PkcePair(verifier, challenge)
    }

    fun randomState(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Local HTTP callback listener for browser OAuth 2.0 PKCE login.
 * Defaults to an ephemeral loopback port (`http://127.0.0.1:<port>/callback`), or binds to an explicit
 * fixed loopback URI (e.g. `http://localhost:8765/callback`) when [configuredRedirectUri] is supplied.
 */
class OAuthLoopbackReceiver(
    private val expectedState: String,
    configuredRedirectUri: String = ""
) : AutoCloseable {
    private val result = CompletableDeferred<Result<String>>()
    private val endpoint = parseEndpoint(configuredRedirectUri)
    private val server: HttpServer = HttpServer.create(InetSocketAddress(endpoint.bindAddr, endpoint.port), 0)

    val port: Int get() = server.address.port
    val redirectUri: String get() = endpoint.explicitUri ?: "http://127.0.0.1:$port${endpoint.path}"

    init {
        server.createContext(endpoint.path) { exchange ->
            val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val state = params["state"].orEmpty()
            val error = params["error"]
            val desc = params["error_description"] ?: error
            val code = params["code"]

            val (status, message, resolved) = when {
                !error.isNullOrBlank() -> Triple(400, "MangaDex OAuth error: $desc", Result.failure(IllegalStateException(desc)))
                state != expectedState -> Triple(400, "Invalid OAuth state parameter.", Result.failure(IllegalStateException("Invalid OAuth state parameter")))
                code.isNullOrBlank() -> Triple(400, "No authorization code returned.", Result.failure(IllegalStateException("No authorization code returned")))
                else -> Triple(200, "MangaDex authorization received. You can close this tab and return to WOM Manga Uploader.", Result.success(code))
            }
            val html = "<!doctype html><html><body style=\"font-family:sans-serif;padding:24px\"><h3>$message</h3></body></html>".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(status, html.size.toLong())
            exchange.responseBody.use { it.write(html) }
            result.complete(resolved)
        }
        server.start()
    }

    suspend fun awaitCode(timeoutMs: Long = 180_000L): String =
        withTimeout(timeoutMs) { result.await().getOrThrow() }

    override fun close() {
        runCatching { server.stop(0) }
    }

    private data class Endpoint(val bindAddr: InetAddress, val port: Int, val path: String, val explicitUri: String?)

    companion object {
        private fun parseEndpoint(raw: String): Endpoint {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Endpoint(InetAddress.getLoopbackAddress(), 0, "/callback", null)
            }
            val uri = URI(trimmed)
            require(uri.scheme.equals("http", ignoreCase = true)) {
                "OAuth redirect URI must use http:// on loopback (got '$trimmed')"
            }
            val host = uri.host?.trim('[', ']').orEmpty()
            val bindAddr = when {
                host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" -> InetAddress.getByName("127.0.0.1")
                host == "::1" -> InetAddress.getByName("::1")
                else -> throw IllegalArgumentException("OAuth redirect URI must target loopback (127.0.0.1 or localhost), got '$trimmed'")
            }
            val port = uri.port
            require(port in 1..65535) {
                "OAuth redirect URI must specify an explicit port (e.g. http://127.0.0.1:8765/callback)"
            }
            val path = uri.rawPath?.takeIf { it.startsWith("/") } ?: "/callback"
            return Endpoint(bindAddr, port, path, trimmed)
        }

        internal fun parseQuery(raw: String): Map<String, String> =
            raw.split('&').filter { it.isNotEmpty() }.associate { part ->
                val k = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8)
                val v = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
                k to v
            }
    }
}
