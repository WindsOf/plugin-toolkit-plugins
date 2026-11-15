package com.wip.mangauploader

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.wip.plugintoolkit.api.PluginLogger
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** Connection pool shared by the SFTP and FTP implementations (bounded by `concurrentConnections`). */
abstract class PooledStorage<C : Any>(maxConnections: Int) : ArubaStorage {
    private val permits = Semaphore(maxConnections.coerceAtLeast(1))
    private val idle = ConcurrentLinkedQueue<C>()

    protected abstract fun open(): C
    protected abstract fun disconnect(connection: C)
    protected abstract fun isAlive(connection: C): Boolean

    protected suspend fun <T> withConnection(block: (C) -> T): T = permits.withPermit {
        withContext(Dispatchers.IO) {
            var connection = idle.poll()
            if (connection != null && !isAlive(connection)) {
                runCatching { disconnect(connection!!) }
                connection = null
            }
            val conn = connection ?: open()
            try {
                block(conn).also { idle.add(conn) }
            } catch (e: Throwable) {
                runCatching { disconnect(conn) }
                throw e
            }
        }
    }

    override fun close() {
        while (true) {
            val c = idle.poll() ?: break
            runCatching { disconnect(c) }
        }
    }
}

class SftpStorage(private val config: ArubaConfig, private val logger: PluginLogger? = null) :
    PooledStorage<SftpStorage.Conn>(config.maxConnections) {

    class Conn(val session: Session, val channel: ChannelSftp)

    override fun open(): Conn {
        val session = JSch().getSession(config.username, config.host, config.port)
        session.setPassword(config.password)
        // Aruba hosts have no pre-shared known_hosts file in the plugin sandbox.
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(CONNECT_TIMEOUT_MS)
        val channel = session.openChannel("sftp") as ChannelSftp
        channel.connect(CONNECT_TIMEOUT_MS)
        logger?.debug("Opened SFTP connection to ${config.host}:${config.port}")
        return Conn(session, channel)
    }

    override fun disconnect(connection: Conn) {
        runCatching { connection.channel.disconnect() }
        runCatching { connection.session.disconnect() }
    }

    override fun isAlive(connection: Conn): Boolean = connection.session.isConnected && connection.channel.isConnected

    override suspend fun upload(remotePath: String, bytes: ByteArray) = withConnection { c ->
        mkdirs(c.channel, remotePath.substringBeforeLast('/', ""))
        c.channel.put(ByteArrayInputStream(bytes), remotePath, ChannelSftp.OVERWRITE)
    }

    override suspend fun delete(remotePath: String) = withConnection { c ->
        try {
            c.channel.rm(remotePath)
        } catch (e: SftpException) {
            if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e
        }
    }

    override suspend fun listNames(remoteDir: String): List<String> = withConnection { c ->
        try {
            c.channel.ls(remoteDir).map { (it as ChannelSftp.LsEntry).filename }.filter { it != "." && it != ".." }
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) emptyList() else throw e
        }
    }

    override suspend fun listDirectoryNames(remoteDir: String): List<String> = withConnection { c ->
        try {
            c.channel.ls(remoteDir)
                .map { it as ChannelSftp.LsEntry }
                .filter { it.filename != "." && it.filename != ".." && it.attrs.isDir }
                .map { it.filename }
                .sorted()
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) emptyList() else throw e
        }
    }

    override suspend fun testConnection(): String = withConnection { c ->
        val root = config.chaptersPath.trim().ifEmpty { "/" }
        mkdirs(c.channel, root.trimEnd('/'))
        val count = c.channel.ls(root).size - 2
        "SFTP connected to ${config.host}, $root has ${count.coerceAtLeast(0)} entries"
    }

    private fun mkdirs(channel: ChannelSftp, dir: String) {
        if (dir.isBlank()) return
        var current = if (dir.startsWith("/")) "" else "."
        for (part in dir.split('/').filter { it.isNotEmpty() }) {
            current = if (current == ".") part else "$current/$part"
            try {
                channel.stat(current)
            } catch (e: SftpException) {
                try {
                    channel.mkdir(current)
                } catch (mkdirErr: SftpException) {
                    // Concurrent uploads can race to create the same chapter directory.
                    channel.stat(current)
                }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}

class FtpStorage(private val config: ArubaConfig, private val logger: PluginLogger? = null) :
    PooledStorage<FTPClient>(config.maxConnections) {

    /** Shared-hosting webroot prefix (e.g. `/www.windsofresub.cloud`) discovered lazily when `/` is read-only. */
    private val resolvedWebrootPrefix = AtomicReference<String?>(null)

    override fun open(): FTPClient {
        val client = if (config.protocol == "FTPS") FTPSClient() else FTPClient()
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.connect(config.host, config.port)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            client.disconnect()
            throw java.io.IOException("FTP server refused connection: ${client.replyString}")
        }
        if (!client.login(config.username, config.password)) {
            val reply = client.replyString
            client.disconnect()
            throw java.io.IOException("FTP login failed: $reply")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        logger?.debug("Opened FTP connection to ${config.host}:${config.port}")
        return client
    }

    override fun disconnect(connection: FTPClient) {
        runCatching { connection.logout() }
        runCatching { connection.disconnect() }
    }

    override fun isAlive(connection: FTPClient): Boolean = connection.isConnected && runCatching { connection.sendNoOp() }.getOrDefault(false)

    override suspend fun upload(remotePath: String, bytes: ByteArray) = withConnection { c ->
        val clean = normalizeFtpPath(remotePath)
        val parent = clean.substringBeforeLast('/', "")
        val fileName = clean.substringAfterLast('/')
        val origin = runCatching { c.printWorkingDirectory() }.getOrNull()
        try {
            if (parent.isNotBlank()) enterOrCreateDir(c, parent)
            val targetName = if (parent.isNotBlank()) fileName else clean
            if (!c.storeFile(targetName, ByteArrayInputStream(bytes))) {
                throw java.io.IOException("FTP upload failed for $clean: ${c.replyString.trim()}")
            }
        } finally {
            runCatching { c.changeWorkingDirectory(origin ?: "/") }
        }
    }

    override suspend fun delete(remotePath: String) = withConnection { c ->
        val target = resolveExistingFtpPath(c, normalizeFtpPath(remotePath))
        c.deleteFile(target)
        Unit
    }

    override suspend fun listNames(remoteDir: String): List<String> = withConnection { c ->
        val origin = runCatching { c.printWorkingDirectory() }.getOrNull()
        try {
            val clean = resolveExistingFtpPath(c, normalizeFtpPath(remoteDir).ifEmpty { "/" })
            val names = if (c.changeWorkingDirectory(clean)) c.listNames() else c.listNames(clean)
            (names ?: emptyArray())
                .map { it.substringAfterLast('/') }
                .filter { it.isNotBlank() && it != "." && it != ".." }
        } finally {
            runCatching { c.changeWorkingDirectory(origin ?: "/") }
        }
    }

    override suspend fun listDirectoryNames(remoteDir: String): List<String> = withConnection { c ->
        val origin = runCatching { c.printWorkingDirectory() }.getOrNull()
        try {
            val clean = resolveExistingFtpPath(c, normalizeFtpPath(remoteDir).ifEmpty { "/" })
            if (!c.changeWorkingDirectory(clean)) return@withConnection emptyList()
            val dirs = runCatching { c.listDirectories() }.getOrNull()
            if (dirs != null && dirs.isNotEmpty()) {
                return@withConnection dirs
                    .mapNotNull { it?.name?.trim() }
                    .filter { it.isNotBlank() && it != "." && it != ".." }
                    .distinct()
                    .sorted()
            }
            val raw = runCatching { c.listFiles() }.getOrNull() ?: emptyArray()
            raw.filter { it != null && it.isDirectory && it.name != "." && it.name != ".." }
                .map { it.name.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        } finally {
            runCatching { c.changeWorkingDirectory(origin ?: "/") }
        }
    }

    override suspend fun testConnection(): String = withConnection { c ->
        val root = normalizeFtpPath(config.chaptersPath).ifEmpty { "/" }
        val origin = runCatching { c.printWorkingDirectory() }.getOrNull()
        try {
            val effective = enterOrCreateDir(c, root)
            val count = (c.listNames() ?: emptyArray()).count { it != "." && it != ".." }
            val note = if (effective != root) " (resolved webroot: $effective)" else ""
            "${config.protocol} connected to ${config.host}, $root has $count entries$note"
        } finally {
            runCatching { c.changeWorkingDirectory(origin ?: "/") }
        }
    }

    private fun enterOrCreateDir(client: FTPClient, dir: String): String {
        val clean = normalizeFtpPath(dir)
        if (clean.isEmpty()) return ""
        val cached = resolvedWebrootPrefix.get()
        if (cached != null && clean.startsWith("/") && !clean.startsWith("$cached/") && clean != cached) {
            return enterOrCreateDirect(client, "$cached$clean")
        }
        val primaryError = try {
            return enterOrCreateDirect(client, clean)
        } catch (e: java.io.IOException) {
            e
        }
        if (!clean.startsWith("/")) throw primaryError

        val rootDirs = listRootSubdirectories(client)
        val firstSegment = clean.trimStart('/').substringBefore('/')
        val webroot = pickWebrootDirectory(rootDirs) { candidate ->
            firstSegment.isNotEmpty() && runCatching { client.changeWorkingDirectory("/$candidate/$firstSegment") }.getOrDefault(false)
        }
        if (webroot != null) {
            val prefix = "/$webroot"
            resolvedWebrootPrefix.compareAndSet(null, prefix)
            logger?.info("Resolved FTP path '$clean' -> '$prefix$clean' (FTP root '/' is not writable)")
            return enterOrCreateDirect(client, "$prefix$clean")
        }
        val hint = if (rootDirs.isEmpty()) "" else " (available root dirs: ${rootDirs.joinToString()})"
        throw java.io.IOException("${primaryError.message}$hint", primaryError)
    }

    private fun resolveExistingFtpPath(client: FTPClient, clean: String): String {
        if (!clean.startsWith("/") || clean == "/") return clean
        val cached = resolvedWebrootPrefix.get()
        if (cached != null && !clean.startsWith("$cached/") && clean != cached) {
            return "$cached$clean"
        }
        if (runCatching { client.changeWorkingDirectory(clean) }.getOrDefault(false)) return clean
        val rootDirs = listRootSubdirectories(client)
        val webroot = pickWebrootDirectory(rootDirs) { candidate ->
            runCatching { client.changeWorkingDirectory("/$candidate$clean") }.getOrDefault(false)
        } ?: return clean
        val prefix = "/$webroot"
        resolvedWebrootPrefix.compareAndSet(null, prefix)
        return "$prefix$clean"
    }

    private fun listRootSubdirectories(client: FTPClient): List<String> = runCatching {
        if (!client.changeWorkingDirectory("/")) return@runCatching emptyList()
        val dirs = runCatching { client.listDirectories() }.getOrNull()
        val names = if (dirs != null && dirs.isNotEmpty()) {
            dirs.mapNotNull { it?.name?.trim() }
        } else {
            (runCatching { client.listFiles() }.getOrNull() ?: emptyArray())
                .filter { it != null && it.isDirectory }
                .map { it.name.trim() }
        }
        names.filter { it.isNotBlank() && it != "." && it != ".." }.distinct()
    }.getOrDefault(emptyList())

    private fun enterOrCreateDirect(client: FTPClient, clean: String): String {
        if (client.changeWorkingDirectory(clean)) return clean
        if (clean.startsWith("/")) {
            client.changeWorkingDirectory("/")
        }
        val parts = clean.split('/').filter { it.isNotEmpty() }
        var built = if (clean.startsWith("/")) "" else "."
        for (part in parts) {
            built = if (built == ".") part else "$built/$part"
            if (client.changeWorkingDirectory(part)) continue
            val created = client.makeDirectory(part)
            if (!client.changeWorkingDirectory(part)) {
                val createdAbs = if (!created) client.makeDirectory(built) else false
                if (!client.changeWorkingDirectory(part) && !client.changeWorkingDirectory(built)) {
                    throw java.io.IOException(
                        "FTP could not create or open directory '$built' (mkdir=${created || createdAbs}): ${client.replyString.trim()}"
                    )
                }
            }
        }
        return clean
    }

    private fun normalizeFtpPath(path: String): String =
        path.replace(Regex("/+"), "/").trim()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
    }
}

/**
 * Selects a shared-hosting webroot subdirectory under FTP `/` (such as `www.example.com` or `public_html`)
 * prioritizing one that already contains the requested first path segment and ignoring `*_Backup_*` folders.
 */
internal fun pickWebrootDirectory(rootDirs: List<String>, hasExistingFirstSegment: (String) -> Boolean): String? {
    val nonBackup = rootDirs.filterNot { it.contains("backup", ignoreCase = true) }
    nonBackup.firstOrNull(hasExistingFirstSegment)?.let { return it }
    return nonBackup.firstOrNull { dir ->
        dir.startsWith("www.", ignoreCase = true) ||
            dir.equals("public_html", ignoreCase = true) ||
            dir.equals("htdocs", ignoreCase = true) ||
            dir.equals("httpdocs", ignoreCase = true)
    }
}
