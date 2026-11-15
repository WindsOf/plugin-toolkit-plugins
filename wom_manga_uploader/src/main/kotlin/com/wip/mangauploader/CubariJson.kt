package com.wip.mangauploader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Pure editing helpers for Cubari series JSON documents
 * (`{"title", "description", "artist", "author", "cover", "last_updated", "chapters": {...}}`).
 * Unknown keys are always preserved so manual edits of the repository are never lost.
 */
object CubariJson {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as? JsonObject
        ?: throw IllegalArgumentException("Cubari series JSON must be an object")

    fun render(json: JsonObject): String = pretty.encodeToString(JsonObject.serializer(), json) + "\n"

    /** Writes title/description/author/artist/cover from [series] and bumps `last_updated`. */
    fun applySeriesMeta(existing: JsonObject?, series: Series, nowEpochSec: Long): JsonObject {
        val map = LinkedHashMap(existing ?: JsonObject(emptyMap()))
        map["title"] = JsonPrimitive(series.title)
        map["description"] = JsonPrimitive(series.description)
        map["artist"] = JsonPrimitive(series.artist)
        map["author"] = JsonPrimitive(series.author)
        if (series.coverUrl.isNullOrBlank()) map.remove("cover") else map["cover"] = JsonPrimitive(series.coverUrl)
        map.putIfAbsent("chapters", JsonObject(emptyMap()))
        map["last_updated"] = JsonPrimitive(nowEpochSec)
        return JsonObject(map)
    }

    /**
     * Adds or replaces [group]'s page list of chapter [chapterKey]. Other groups of the same chapter are kept,
     * so already published chapters are updated in place. Also bumps the series `last_updated`.
     */
    fun upsertChapter(
        existing: JsonObject?,
        chapterKey: String,
        title: String,
        volume: String,
        group: String,
        pageUrls: List<String>,
        nowEpochSec: Long
    ): JsonObject {
        val map = LinkedHashMap(existing ?: JsonObject(emptyMap()))
        val chapters = LinkedHashMap((map["chapters"] as? JsonObject) ?: JsonObject(emptyMap()))
        val old = chapters[chapterKey] as? JsonObject
        val groups = LinkedHashMap((old?.get("groups") as? JsonObject) ?: JsonObject(emptyMap()))
        groups[group] = JsonArray(pageUrls.map { JsonPrimitive(it) })

        val chapter = LinkedHashMap(old ?: JsonObject(emptyMap()))
        chapter["title"] = JsonPrimitive(title)
        chapter["volume"] = JsonPrimitive(volume)
        chapter["last_updated"] = JsonPrimitive(nowEpochSec)
        chapter["groups"] = JsonObject(groups)
        chapters[chapterKey] = JsonObject(chapter)

        map["chapters"] = JsonObject(chapters)
        map["last_updated"] = JsonPrimitive(nowEpochSec)
        return JsonObject(map)
    }

    /** Removes [group] from the chapter; the whole chapter disappears when no group remains. */
    fun removeChapter(existing: JsonObject, chapterKey: String, group: String, nowEpochSec: Long): JsonObject {
        val chapters = LinkedHashMap((existing["chapters"] as? JsonObject) ?: return existing)
        val old = chapters[chapterKey] as? JsonObject ?: return existing
        val groups = LinkedHashMap((old["groups"] as? JsonObject) ?: JsonObject(emptyMap()))
        groups.remove(group)
        if (groups.isEmpty()) {
            chapters.remove(chapterKey)
        } else {
            val chapter = LinkedHashMap(old)
            chapter["groups"] = JsonObject(groups)
            chapter["last_updated"] = JsonPrimitive(nowEpochSec)
            chapters[chapterKey] = JsonObject(chapter)
        }
        val map = LinkedHashMap(existing)
        map["chapters"] = JsonObject(chapters)
        map["last_updated"] = JsonPrimitive(nowEpochSec)
        return JsonObject(map)
    }

    /** Sets (non-blank [url]) or removes (null/blank) the `cover` field. */
    fun setCover(existing: JsonObject?, url: String?, nowEpochSec: Long): JsonObject {
        val map = LinkedHashMap(existing ?: JsonObject(emptyMap()))
        if (url.isNullOrBlank()) map.remove("cover") else map["cover"] = JsonPrimitive(url)
        map["last_updated"] = JsonPrimitive(nowEpochSec)
        return JsonObject(map)
    }

    fun chapterKeys(json: JsonObject?): Set<String> =
        (json?.get("chapters") as? JsonObject)?.keys ?: emptySet()

    fun lastUpdated(json: JsonObject?): Long? = (json?.get("last_updated") as? JsonPrimitive)?.longOrNull

    fun chapterLastUpdated(json: JsonObject?, chapterKey: String): Long? =
        (((json?.get("chapters") as? JsonObject)?.get(chapterKey)) as? JsonObject)
            ?.get("last_updated")?.let { it as? JsonPrimitive }?.longOrNull

    fun string(json: JsonObject?, key: String): String? =
        (json?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    /** Page URLs of [group] in chapter [chapterKey], falling back to the first available group if absent. */
    fun pageUrls(json: JsonObject?, chapterKey: String, group: String): List<String> {
        val groups = ((json?.get("chapters") as? JsonObject)?.get(chapterKey) as? JsonObject)
            ?.get("groups") as? JsonObject ?: return emptyList()
        val selected = groups[group] ?: groups.values.firstOrNull()
        return (selected as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }

    /** True when the parsed JSON looks like a Cubari series manifest rather than unrelated config JSON at repo root. */
    fun looksLikeCubariSeries(json: JsonObject): Boolean =
        json["chapters"] is JsonObject || (json.containsKey("title") && json.containsKey("cover"))

    /**
     * Cubari reader URL for a JSON file hosted on GitHub raw
     * (`https://cubari.moe/read/gist/<base64url("raw/user/repo/refs/heads/branch/path")>/`).
     */
    fun readerUrl(user: String, repo: String, branch: String, path: String): String {
        val cleanBranch = branch.trim().removePrefix("refs/heads/").trim('/')
        val refPart = when {
            cleanBranch.isEmpty() || cleanBranch.equals("HEAD", ignoreCase = true) -> "HEAD"
            else -> "refs/heads/$cleanBranch"
        }
        val encodedPath = path.trim('/').split('/').filter { it.isNotEmpty() }
            .joinToString("/", transform = ::encodeReaderPathSegment)
        val raw = "raw/$user/$repo/$refPart/$encodedPath"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.encodeToByteArray())
        return "https://cubari.moe/read/gist/$encoded/"
    }

    /** Encodes a path segment for GitHub/Cubari raw URLs (keeps apostrophes `'` unencoded cleanly). */
    fun encodeReaderPathSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val safe = (b in 'A'.code..'Z'.code) ||
                (b in 'a'.code..'z'.code) ||
                (b in '0'.code..'9'.code) ||
                b == '-'.code || b == '.'.code || b == '_'.code || b == '~'.code || b == '\''.code
            if (safe) out.append(b.toChar())
            else out.append('%').append("0123456789ABCDEF"[b ushr 4]).append("0123456789ABCDEF"[b and 0x0F])
        }
        return out.toString()
    }

    /** `https://github.com/<owner>/<repo>(.git)` or `git@github.com:<owner>/<repo>.git` -> `<owner>`. */
    fun repoOwnerFromUrl(remoteUrl: String, fallback: String = ""): String {
        val cleaned = remoteUrl.trim().trimEnd('/').removeSuffix(".git")
        val path = when {
            cleaned.contains("://") -> cleaned.substringAfter("://").substringAfter('/', "")
            cleaned.contains(':') -> cleaned.substringAfter(':')
            else -> cleaned
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        return if (segments.size >= 2) segments[segments.size - 2] else fallback
    }

    /** `https://github.com/<user>/<repo>(.git)` → `repo`. */
    fun repoNameFromUrl(remoteUrl: String): String =
        remoteUrl.trim().trimEnd('/').removeSuffix(".git").substringAfterLast('/')

    fun jsonValue(value: JsonElement?): String? = (value as? JsonPrimitive)?.contentOrNull
}
