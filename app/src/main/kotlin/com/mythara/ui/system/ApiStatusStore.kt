package com.mythara.ui.system

/**
 * Process-wide tracker for the health of the AI services Mythara
 * relies on. Powers the coloured glowing dots in
 * [MytharaStatusBar].
 *
 * Three states per service so the visual readout is unambiguous:
 *   - [ApiHealth.Online]  — credential set + last call succeeded
 *                           (or no calls yet but the route is
 *                           reachable). Dot glows in the service
 *                           colour (blue for MiniMax, yellow for
 *                           Image).
 *   - [ApiHealth.Offline] — no credential, no network, or
 *                           explicitly disabled. Dot is grey.
 *   - [ApiHealth.Error]   — last call within ERROR_WINDOW_MS
 *                           returned a non-2xx / threw. Dot is red.
 *
 * Any caller (ModelRouter, vision client, etc.) can signal status
 * changes via [markOnline] / [markOffline] / [markError]. Pure
 * volatile-state singleton — no allocation, no synchronization
 * needed; reads / writes of references / Long / Int are atomic on
 * the JVM and the renderer is fine with reading a slightly out-of-
 * sync pair (worst case = one frame of stale state).
 */
object ApiStatusStore {

    enum class ApiHealth { Online, Offline, Error }

    @Volatile private var minimaxHealth: ApiHealth = ApiHealth.Offline
    @Volatile private var minimaxLastErrorTs: Long = 0L
    @Volatile private var imageHealth: ApiHealth = ApiHealth.Offline
    @Volatile private var imageLastErrorTs: Long = 0L

    /** Read the current MiniMax health, downgrading from Error to
     *  Online once the error window has elapsed without a fresh
     *  failure. */
    fun minimax(): ApiHealth = decay(minimaxHealth, minimaxLastErrorTs)

    /** Read the current Image (Gemini vision / MiniMax-VL) health
     *  with the same Error-decay semantics as [minimax]. */
    fun image(): ApiHealth = decay(imageHealth, imageLastErrorTs)

    fun markMinimaxOnline() { minimaxHealth = ApiHealth.Online }
    fun markMinimaxOffline() { minimaxHealth = ApiHealth.Offline }
    fun markMinimaxError() {
        minimaxHealth = ApiHealth.Error
        minimaxLastErrorTs = System.currentTimeMillis()
    }

    fun markImageOnline() { imageHealth = ApiHealth.Online }
    fun markImageOffline() { imageHealth = ApiHealth.Offline }
    fun markImageError() {
        imageHealth = ApiHealth.Error
        imageLastErrorTs = System.currentTimeMillis()
    }

    /** Auto-recover from Error → Online once ERROR_WINDOW_MS has
     *  elapsed without a fresh failure. The dot stays red just
     *  long enough that the user catches the failure context;
     *  beyond that we assume the service has recovered (or the
     *  user has fixed the credential) and stop scaring them. */
    private fun decay(current: ApiHealth, lastErrorTs: Long): ApiHealth {
        if (current != ApiHealth.Error) return current
        return if (System.currentTimeMillis() - lastErrorTs > ERROR_WINDOW_MS) ApiHealth.Online
        else current
    }

    /** How long an error stays "fresh" before the dot decays back
     *  to Online (assuming no new failures). 90 s is comfortable
     *  — long enough that a glance back at the device catches the
     *  failure, short enough that a transient ECONNRESET doesn't
     *  pin the dot red for the rest of the session. */
    private const val ERROR_WINDOW_MS = 90_000L
}
