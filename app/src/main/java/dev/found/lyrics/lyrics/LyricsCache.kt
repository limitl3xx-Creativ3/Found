package dev.found.lyrics.lyrics

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Lyrics as stored on disk, so a track only ever needs the network once. */
data class CachedLyrics(
    val artist: String,
    val title: String,
    val durationSec: Int,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val fetchedAt: Long,
    /** The track has no lyrics at all, it is an instrumental. */
    val instrumental: Boolean = false,
)

/**
 * File-backed cache for lyrics, keyed by artist + title + duration.
 * Everything stays in app-private storage.
 */
class LyricsCache(context: Context) {

    private val directory: File = File(context.filesDir, "lyrics").apply { mkdirs() }

    /**
     * The cache key of a track.
     *
     * The same track has to land on the same key every time, so the parts are
     * normalised (trimmed, collapsed whitespace, lower case) before they are
     * hashed.
     */
    fun key(artist: String, title: String, durationSec: Int): String {
        val raw = listOf(artist, title, durationSec.toString())
            .joinToString("|") { normalise(it) }
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Trims, collapses whitespace and lower cases, so the key is stable. */
    private fun normalise(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()

    fun read(key: String): CachedLyrics? {
        val file = File(directory, "$key.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            CachedLyrics(
                artist = json.optString("artist"),
                title = json.optString("title"),
                durationSec = json.optInt("durationSec"),
                syncedLyrics = json.optStringOrNull("syncedLyrics"),
                plainLyrics = json.optStringOrNull("plainLyrics"),
                fetchedAt = json.optLong("fetchedAt"),
                instrumental = json.optBoolean("instrumental", false),
            )
        } catch (e: Exception) {
            Log.w(TAG, "dropping unreadable cache entry: ${e.message}")
            runCatching { file.delete() }
            null
        }
    }

    fun write(key: String, lyrics: CachedLyrics) {
        try {
            val json = JSONObject()
                .put("artist", lyrics.artist)
                .put("title", lyrics.title)
                .put("durationSec", lyrics.durationSec)
                .put("syncedLyrics", lyrics.syncedLyrics ?: "")
                .put("plainLyrics", lyrics.plainLyrics ?: "")
                .put("fetchedAt", lyrics.fetchedAt)
                .put("instrumental", lyrics.instrumental)
            // Write to a temp file first so a crash cannot corrupt the entry.
            val temp = File(directory, "$key.json.tmp")
            temp.writeText(json.toString())
            val target = File(directory, "$key.json")
            if (!temp.renameTo(target)) {
                target.writeText(json.toString())
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not cache lyrics: ${e.message}")
        }
    }

    /**
     * Reads a string that may be missing.
     *
     * `JSONObject.optString` turns a JSON null into the text "null", so the
     * null has to be checked before the value is read.
     */
    private fun JSONObject.optStringOrNull(name: String): String? {
        if (isNull(name)) return null
        return optString(name).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "FoundCache"
    }
}
