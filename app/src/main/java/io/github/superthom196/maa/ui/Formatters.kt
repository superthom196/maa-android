package io.github.superthom196.maa.ui

import io.github.superthom196.maa.data.MaApiException
import io.github.superthom196.maa.data.NotLoggedInException
import io.github.superthom196.maa.data.OfflineException
import io.github.superthom196.maa.playback.PrefetchStatus
import java.io.IOException
import java.util.Locale

/** Why a sign-in failed, so the screen can say "wrong password" and "can't reach it" differently. */
enum class LoginFailure { BAD_CREDENTIALS, UNREACHABLE, OTHER }

/** Pure formatting helpers shared by the screens (unit-tested, no Android types). */
object Formatters {
    /** Settings → phone cache choices. The look-ahead alone needs ~6 MB per Opus track; the rest keeps recent plays. */
    val CACHE_CHOICES_MB = listOf(128, 256, 512, 1024, 2048)

    /** The user asked for at least two tracks ahead; ten is plenty for any drive through a dead zone. */
    val LOOKAHEAD_RANGE = 2..10

    /** 1536 → "1.5 KB"; one decimal below 10 units, none above, 1024-based like Android's own storage screens. */
    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v < 10) String.format(Locale.ROOT, "%.1f %s", v, units[i])
        else String.format(Locale.ROOT, "%d %s", v.toLong(), units[i])
    }

    /** Cache size choice: 512 → "512 MB", 2048 → "2 GB". */
    fun cacheSize(mb: Int): String =
        if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

    /** Playback position: "3:07", "1:02:03"; unknown or negative → "0:00". */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0)) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /**
     * "3/4 on phone · Downloading 45%": how much of the look-ahead window survives a dead zone,
     * then the prefetcher's own words. [onPhone] renders the count (a localized string); null when
     * there is nothing to say (nothing queued).
     */
    fun prefetchLine(status: PrefetchStatus, onPhone: (cached: Int, targets: Int) -> String): String? {
        val parts = listOfNotNull(
            if (status.targets > 0) onPhone(status.cached, status.targets) else null,
            status.message.trim().ifEmpty { null },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * Sorts a login exception. WS-B reports a rejected password as 401/403 (or [NotLoggedInException]);
     * transport failures surface as [IOException] or [OfflineException].
     */
    fun loginFailure(e: Throwable): LoginFailure = when {
        e is NotLoggedInException -> LoginFailure.BAD_CREDENTIALS
        e is MaApiException && (e.code == 401 || e.code == 403) -> LoginFailure.BAD_CREDENTIALS
        e is OfflineException || e is IOException -> LoginFailure.UNREACHABLE
        e.cause is IOException -> LoginFailure.UNREACHABLE
        else -> LoginFailure.OTHER
    }
}
