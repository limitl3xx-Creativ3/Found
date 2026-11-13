package dev.found.lyrics.lyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Result of a lyrics lookup. */
data class Lyrics(
    val lines: List<LyricLine>,
    val plainText: String?,
    val instrumental: Boolean,
    val source: String,
) {
    val hasSynced: Boolean get() = lines.isNotEmpty()
}

/**
 * Looks lyrics up online (LRCLIB), then keeps them on disk so the same track
 * works offline from then on.
 */
class LyricsRepository(context: Context) {

    private val cache = LyricsCache(context.applicationContext)

    /**
     * Returns the lyrics for a track: from cache when possible, otherwise from
     * the network (and cached afterwards).
     */
    suspend fun lyricsFor(artist: String, title: String, durationMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val durationSec = (durationMs / 1000L).toInt()
            val key = cache.key(artist, title, durationSec)

            cache.read(key)?.let { cached ->
                val lines = cached.syncedLyrics?.let { LrcParser.parse(it) } ?: emptyList()
                if (lines.isNotEmpty() || !cached.plainLyrics.isNullOrEmpty()) {
                    return@withContext Lyrics(
                        lines = lines,
                        plainText = cached.plainLyrics,
                        instrumental = false,
                        source = "cache",
                    )
                }
            }

            val remote = fetchRemote(artist, title, durationSec) ?: return@withContext null

            cache.write(
                key,
                CachedLyrics(
                    artist = artist,
                    title = title,
                    durationSec = durationSec,
                    syncedLyrics = remote.synced,
                    plainLyrics = remote.plain,
                    fetchedAt = System.currentTimeMillis(),
                )
            )

            Lyrics(
                lines = remote.synced?.let { LrcParser.parse(it) } ?: emptyList(),
                plainText = remote.plain,
                instrumental = remote.instrumental,
                source = "lrclib",
            )
        }

    private data class Remote(
        val synced: String?,
        val plain: String?,
        val instrumental: Boolean,
    )

    private fun fetchRemote(artist: String, title: String, durationSec: Int): Remote? {
        val directUrl = "https://lrclib.net/api/get" +
            "?artist_name=${encode(artist)}" +
            "&track_name=${encode(title)}" +
            "&duration=$durationSec"
        val direct = request(directUrl)?.let { parseOne(it) }
        if (direct != null) return direct

        // No exact match: search and take the closest result that has timings.
        val searchUrl = "https://lrclib.net/api/search" +
            "?track_name=${encode(title)}" +
            "&artist_name=${encode(artist)}"
        val body = request(searchUrl) ?: return null

        return try {
            val array = JSONArray(body)
            var best: Remote? = null
            var bestScore = Int.MIN_VALUE
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val remote = parseOne(item.toString()) ?: continue
                var score = 0
                if (remote.synced != null) score += 50
                val duration = item.optDouble("duration", -1.0).toInt()
                if (duration > 0 && durationSec > 0) {
                    val delta = kotlin.math.abs(duration - durationSec)
                    score += when {
                        delta <= 3 -> 40
                        delta <= 10 -> 20
                        else -> 0
                    }
                }
                if (item.optString("artistName").equals(artist, ignoreCase = true)) score += 10
                if (score > bestScore) {
                    bestScore = score
                    best = remote
                }
            }
            best
        } catch (e: Exception) {
            Log.w(TAG, "could not parse search response: ${e.message}")
            null
        }
    }

    /** Parses a single LRCLIB record; returns null when it carries no lyrics. */
    private fun parseOne(body: String): Remote? {
        return try {
            val item = JSONObject(body)
            val synced = item.optString("syncedLyrics").takeIf { it.isNotEmpty() }
            val plain = item.optString("plainLyrics").takeIf { it.isNotEmpty() }
            if (synced == null && plain == null) return null
            Remote(synced, plain, item.optBoolean("instrumental", false))
        } catch (e: Exception) {
            Log.w(TAG, "could not parse lyrics payload: ${e.message}")
            null
        }
    }

    /** Returns the raw body of a successful request, or null. */
    private fun request(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.d(TAG, "lookup returned HTTP $status")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "lookup failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value.ifBlank { " " }.trim(), "UTF-8")

    private companion object {
        const val TAG = "FoundLyrics"
        const val USER_AGENT = "Found/0.1 (Android; https://github.com/)"
    }
}
