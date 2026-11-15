package com.wip.mangauploader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wip.plugintoolkit.api.PluginLogger
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class GitException(message: String) : RuntimeException(message)

/**
 * [GitClient] backed by the system `git` executable. Credentials are passed through a one-shot
 * `http.extraHeader` so the token is never written into `.git/config`, and every log line is masked.
 */
class CliGitClient(
    private val username: String,
    private val token: String,
    private val logger: PluginLogger? = null,
    private val gitExecutable: String = "git"
) : GitClient {

    override suspend fun ensureRepo(dir: String, remoteUrl: String, branch: String) {
        val target = File(dir)
        if (File(target, ".git").isDirectory) return
        target.parentFile?.mkdirs()
        logger?.info("Cloning Cubari repository into $dir")
        val targetBranch = branch.trim()
        if (targetBranch.isEmpty()) {
            run(null, "clone", remoteUrl, dir)
            return
        }
        try {
            run(null, "clone", "--branch", targetBranch, remoteUrl, dir)
        } catch (e: GitException) {
            // Unknown configured branch (e.g. 'main' when remote uses 'master') or empty remote: clone default first.
            logger?.warn("Clone with --branch $targetBranch failed (${e.message?.take(120)}), cloning remote default")
            target.deleteRecursively()
            run(null, "clone", remoteUrl, dir)
            val remoteDefault = resolveBranch(dir)
            if (remoteDefault.isNullOrBlank()) {
                run(target, "checkout", "-B", targetBranch)
            } else {
                logger?.info("Using remote default branch '$remoteDefault' instead of missing '$targetBranch'")
            }
        }
    }

    override suspend fun pull(dir: String, branch: String) {
        val targetBranch = branch.trim()
        try {
            if (targetBranch.isEmpty()) run(File(dir), "pull", "--rebase")
            else run(File(dir), "pull", "--rebase", "origin", targetBranch)
        } catch (e: GitException) {
            val msg = e.message.orEmpty()
            if (msg.contains("couldn't find remote ref", ignoreCase = true)) {
                val fallback = resolveBranch(dir)
                if (!fallback.isNullOrBlank() && fallback != targetBranch) {
                    logger?.info("Remote branch '$targetBranch' missing; pulling remote default '$fallback'")
                    run(File(dir), "checkout", fallback)
                    run(File(dir), "pull", "--rebase", "origin", fallback)
                } else {
                    logger?.info("Remote branch $targetBranch does not exist yet, nothing to pull")
                }
            } else if (isRecoverableLockOrDirtyError(msg)) {
                throw GitException("$msg — local Git state is locked or dirty; use 'Restore from Git' in Settings & Locks to reset to origin.")
            } else throw e
        }
    }

    override suspend fun resetHardFromRemote(dir: String, remoteUrl: String, branch: String): String {
        val target = File(dir)
        val gitDir = File(target, ".git")
        if (!gitDir.isDirectory) {
            target.deleteRecursively()
            ensureRepo(dir, remoteUrl, branch)
            val active = resolveBranch(dir) ?: branch.trim().ifEmpty { "default" }
            logger?.info("Re-cloned Cubari repository cleanly on branch '$active'")
            return "Re-cloned clean repository on $active"
        }

        clearGitLocks(gitDir)
        runCatching { run(target, "rebase", "--abort") }
        runCatching { run(target, "merge", "--abort") }
        runCatching { run(target, "cherry-pick", "--abort") }
        if (remoteUrl.isNotBlank()) {
            runCatching { run(target, "remote", "set-url", "origin", remoteUrl.trim()) }
        }
        run(target, "fetch", "--prune", "origin")
        runCatching { run(target, "remote", "set-head", "origin", "--auto") }

        val targetBranch = pickResetBranch(target, branch)
        if (targetBranch == null) {
            runCatching { run(target, "reset", "--hard") }
            runCatching { run(target, "clean", "-fd") }
            logger?.info("Cleaned uncommitted changes in Cubari repository (remote has no commits yet)")
            return "Reset local uncommitted changes (remote has no commits yet)"
        }
        run(target, "checkout", "-B", targetBranch, "origin/$targetBranch")
        run(target, "reset", "--hard", "origin/$targetBranch")
        run(target, "clean", "-fd")
        logger?.info("Restored Cubari repository cleanly from origin/$targetBranch")
        return "Restored from origin/$targetBranch"
    }

    private suspend fun pickResetBranch(repo: File, configuredBranch: String): String? {
        val configured = configuredBranch.trim().removePrefix("refs/heads/").removePrefix("origin/")
        if (configured.isNotEmpty()) {
            val hasConfigured = runCatching {
                run(repo, "rev-parse", "--verify", "refs/remotes/origin/$configured")
            }.isSuccess
            if (hasConfigured) return configured
        }
        val sym = runCatching { run(repo, "symbolic-ref", "--short", "refs/remotes/origin/HEAD") }.getOrNull()
            ?.trim()
            ?.removePrefix("origin/")
            ?.takeIf { it.isNotBlank() && it != "HEAD" }
        if (sym != null) return sym
        val refs = runCatching { run(repo, "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin/") }.getOrNull()
            ?.lines()
            ?.map { it.trim().removePrefix("origin/") }
            ?.firstOrNull { it.isNotBlank() && it != "HEAD" }
        return refs
    }

    private fun clearGitLocks(gitDir: File) {
        listOf(
            File(gitDir, "index.lock"),
            File(gitDir, "HEAD.lock"),
            File(gitDir, "shallow.lock"),
            File(gitDir, "ORIG_HEAD.lock")
        ).forEach { lock ->
            if (lock.exists()) {
                lock.delete()
                logger?.warn("Removed stale Git lock file: ${lock.name}")
            }
        }
    }

    private fun isRecoverableLockOrDirtyError(msg: String): Boolean {
        val lower = msg.lowercase()
        return "unstaged changes" in lower ||
            "uncommitted changes" in lower ||
            "unmerged" in lower ||
            "index.lock" in lower ||
            "rebase" in lower ||
            "overwritten by" in lower ||
            "needs merge" in lower
    }

    override suspend fun resolveBranch(dir: String): String? {
        val repo = File(dir)
        val sym = runCatching { run(repo, "symbolic-ref", "--short", "refs/remotes/origin/HEAD") }.getOrNull()
            ?.trim()
            ?.removePrefix("origin/")
            ?.takeIf { it.isNotBlank() && it != "HEAD" }
        if (sym != null) return sym
        return runCatching { run(repo, "rev-parse", "--abbrev-ref", "HEAD") }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "HEAD" }
    }

    override suspend fun hasChanges(dir: String): Boolean =
        run(File(dir), "status", "--porcelain").isNotBlank()

    override suspend fun commitAll(dir: String, message: String): Boolean {
        val repo = File(dir)
        run(repo, "add", "-A")
        if (run(repo, "status", "--porcelain").isBlank()) return false
        run(repo, "-c", "user.name=WOM Manga Uploader", "-c", "user.email=uploader@wom.local", "commit", "-m", message)
        return true
    }

    override suspend fun push(dir: String, branch: String) {
        val repo = File(dir)
        val refSpec = branch.trim().let { if (it.isEmpty()) "HEAD" else "HEAD:$it" }
        try {
            run(repo, "push", "origin", refSpec)
        } catch (e: GitException) {
            logger?.warn("Push rejected, rebasing and retrying once: ${e.message?.take(160)}")
            pull(dir, branch)
            run(repo, "push", "origin", refSpec)
        }
    }

    override suspend fun testRemote(remoteUrl: String): String {
        val out = run(null, "ls-remote", "--heads", remoteUrl)
        val branches = out.lines().count { it.isNotBlank() }
        return "Repository reachable ($branches branch(es))"
    }

    private fun authHeader(): List<String> {
        if (token.isBlank()) return emptyList()
        val basic = Base64.getEncoder().encodeToString("${username.ifBlank { "x-access-token" }}:$token".encodeToByteArray())
        return listOf("-c", "http.extraHeader=Authorization: Basic $basic")
    }

    private suspend fun run(workDir: File?, vararg args: String): String = withContext(Dispatchers.IO) {
        val command = listOf(gitExecutable, "-c", "credential.helper=") + authHeader() + args.toList()
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        workDir?.let { builder.directory(it) }
        builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            throw GitException("Could not start '$gitExecutable'. Install Git and make sure it is on PATH (${e.message})")
        }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(GIT_TIMEOUT_MIN, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw GitException("git ${args.firstOrNull()} timed out")
        }
        val masked = mask(output).trim()
        if (process.exitValue() != 0) {
            throw GitException("git ${args.firstOrNull()} failed (exit ${process.exitValue()}): $masked")
        }
        logger?.debug("git ${args.firstOrNull()} ok")
        masked
    }

    private fun mask(text: String): String {
        var result = text
        if (token.isNotBlank()) result = result.replace(token, "***")
        return result.replace(Regex("Authorization: Basic [A-Za-z0-9+/=]+"), "Authorization: Basic ***")
    }

    private companion object {
        const val GIT_TIMEOUT_MIN = 10L
    }
}
