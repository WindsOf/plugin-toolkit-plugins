package com.wip.mangauploader

/** Lock keys returned by `@PluginLocks` (`true` = requirement satisfied / unlocked). */
object LockKeys {
    const val CUBARI = "cubari_ready"
    const val ARUBA = "aruba_ready"
    const val MANGADEX = "mangadex_ready"

    /** Stage 1 = Aruba storage + Cubari repo. */
    const val STAGE1 = "stage1_ready"

    /** Stage 2 = authenticated MangaDex session with a scanlation group. */
    const val STAGE2 = "stage2_ready"

    /** MangaDex -> Cubari backfill needs Stage 1 plus an authenticated MangaDex session. */
    const val SYNC = "sync_ready"
}

enum class Readiness { READY, PARTIAL, LOCKED }

/** Readiness + human readable diagnostics of one target (Cubari / Aruba / MangaDex). */
data class TargetStatus(
    val id: String,
    val label: String,
    val readiness: Readiness,
    val missing: List<String>,
    val message: String
)

data class LockReport(
    val cubari: TargetStatus,
    val aruba: TargetStatus,
    val mangadex: TargetStatus,
    val syncReady: Boolean
) {
    val targets: List<TargetStatus> get() = listOf(cubari, aruba, mangadex)
    val stage1Ready: Boolean get() = cubari.readiness == Readiness.READY && aruba.readiness == Readiness.READY
    val stage2Ready: Boolean get() = mangadex.readiness == Readiness.READY

    fun toLockMap(): Map<String, Boolean> = mapOf(
        LockKeys.CUBARI to (cubari.readiness == Readiness.READY),
        LockKeys.ARUBA to (aruba.readiness == Readiness.READY),
        LockKeys.MANGADEX to (mangadex.readiness == Readiness.READY),
        LockKeys.STAGE1 to stage1Ready,
        LockKeys.STAGE2 to stage2Ready,
        LockKeys.SYNC to syncReady
    )
}

/** Pure lock evaluation, shared by `@PluginLocks` and the Settings & Locks view. */
object LockEvaluator {

    fun evaluate(config: MangaUploaderConfig): LockReport {
        val cubari = target(
            id = "cubari", label = "Cubari / GitHub",
            required = listOf(
                "GitHub username" to config.cubariUsername,
                "GitHub token" to config.cubariToken,
                "Remote repository URL" to config.cubariRemoteUrl
            )
        )
        val aruba = target(
            id = "aruba", label = "Aruba Storage",
            required = listOf(
                "Host" to config.arubaHost,
                "Username" to config.arubaUsername,
                "Password" to config.arubaPassword,
                "Chapters path" to config.arubaChaptersPath.takeIf { config.arubaHost.isNotBlank() }.orEmpty(),
                "Public base URL" to config.arubaPublicBaseUrl
            )
        )
        val mangadex = mangadexTarget(config)
        val syncReady = cubari.readiness == Readiness.READY &&
            aruba.readiness == Readiness.READY &&
            config.isMangaDexAuthenticated
        return LockReport(cubari, aruba, mangadex, syncReady)
    }

    private fun mangadexTarget(config: MangaUploaderConfig): TargetStatus {
        val missing = mutableListOf<String>()
        if (!config.isMangaDexAuthenticated) missing += "MangaDex login"
        if (config.mdGroupId == null) missing += "Scanlation group"
        val readiness = when {
            missing.isEmpty() -> Readiness.READY
            config.isMangaDexAuthenticated -> Readiness.PARTIAL
            else -> Readiness.LOCKED
        }
        val message = when (readiness) {
            Readiness.READY -> "Logged in" + config.mdProfileName.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty()
            Readiness.PARTIAL -> "Logged in, group missing"
            Readiness.LOCKED -> "Not authenticated - log in to unlock Stage 2"
        }
        return TargetStatus("mangadex", "MangaDex", readiness, missing, message)
    }

    private fun target(id: String, label: String, required: List<Pair<String, String>>): TargetStatus {
        val missing = required.filter { it.second.isBlank() }.map { it.first }
        val readiness = when {
            missing.isEmpty() -> Readiness.READY
            missing.size == required.size -> Readiness.LOCKED
            else -> Readiness.PARTIAL
        }
        val message = if (missing.isEmpty()) "Configured" else "Missing: " + missing.joinToString(", ")
        return TargetStatus(id, label, readiness, missing, message)
    }
}
