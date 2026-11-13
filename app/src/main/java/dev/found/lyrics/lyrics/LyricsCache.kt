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
)

/**
 * File-backed cache for lyrics, keyed by artist + title + duration.
 * Everything stays in app-private storage.
 */
class LyricsCache(context: Context) {

    private val directory: File = File(context.filesDir, "lyrics").apply { mkdirs() }

    fun key(artist: String, title: String, durationSec: Int): String {
        val raw = "$artist|$title|$durationSec".lowercase()
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun read(key: String): CachedLyrics? {
        val file = File(directory, "$key.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            CachedLyrics(
                artist = json.optString("artist"),
                title = json.optString("title"),
                durationSec = json.optInt("durationSec"),
                syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotEmpty() },
                plainLyrics = json.optString("plainLyrics").takeIf { it.isNotEmpty() },
                fetchedAt = json.optLong("fetchedAt"),
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
                .put("syncedLyrics", lyrics.syncedLyrics ?: JSONObject.NULL)
                .put("plainLyrics", lyrics.plainLyrics ?: JSONObject.NULL)
                .put("fetchedAt", lyrics.fetchedAt)
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

    private companion object {
        const val TAG = "FoundCache"
    }
}
