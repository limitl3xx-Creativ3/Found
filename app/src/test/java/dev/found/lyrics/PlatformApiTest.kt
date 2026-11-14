package dev.found.lyrics

import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import org.junit.Test

/**
 * Prints what the platform actually offers.
 *
 * Several platform members that look useful are hidden from the public SDK,
 * so they are not in android.jar and cannot be called. This test reports what
 * is there, so the code can be written against the real API instead of
 * against a guess. It never fails: it only prints.
 */
class PlatformApiTest {

    @Test
    fun printTheApiSurface() {
        println("=== PLATFORM API REPORT ===")

        report(
            "Icon.getBitmap()",
            Icon::class.java.methods.any { it.name == "getBitmap" && it.parameterCount == 0 },
        )
        report(
            "Icon.toBitmap()",
            Icon::class.java.methods.any { it.name == "toBitmap" && it.parameterCount == 0 },
        )
        report(
            "Icon.loadDrawable(Context)",
            Icon::class.java.methods.any {
                it.name == "loadDrawable" && it.parameterCount == 1
            },
        )
        report(
            "Icon.toDrawable(Context)",
            Icon::class.java.methods.any {
                it.name == "toDrawable" && it.parameterCount == 1
            },
        )
        report(
            "Icon.getResId()",
            Icon::class.java.methods.any { it.name == "getResId" && it.parameterCount == 0 },
        )
        report(
            "MediaController.release()",
            MediaController::class.java.methods.any { it.name == "release" && it.parameterCount == 0 },
        )
        report(
            "MediaSessionManager.getActiveSessions(ComponentName)",
            MediaSessionManager::class.java.methods.any {
                it.name == "getActiveSessions" && it.parameterCount == 1
            },
        )
        report(
            "NotificationListenerService.getActiveSessions()",
            NotificationListenerService::class.java.methods.any {
                it.name == "getActiveSessions" && it.parameterCount == 0
            },
        )

        // The public keys of the media metadata: one of them may hold the art.
        val keys = MediaMetadata::class.java.fields
            .filter { it.name.startsWith("METADATA_KEY_") }
            .map { it.name }
            .sorted()
        println("--- public MediaMetadata keys: ${keys.joinToString(", ")}")

        val artworkKeys = listOf(
            "android.media.metadata.PICTURE",
            "android.media.metadata.ARTWORK",
            "android.media.metadata.ALBUM_ART",
            "android.media.metadata.IMAGE",
        )
        for (key in artworkKeys) {
            val value = runCatching {
                MediaMetadata::class.java.getDeclaredField("METADATA_KEY_PICTURE")
                    .get(null)
            }.getOrNull()
            println("--- artwork key candidate '$key' -> ${value ?: "unknown constant"}")
        }

        println("=== END PLATFORM API REPORT ===")
    }

    private fun report(name: String, present: Boolean) {
        println("--- $name: ${if (present) "available" else "NOT in the public SDK"}")
    }
}
