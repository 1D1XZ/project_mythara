package com.mythara.branding

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Process-wide queue of pending "thought ripple" pings that the live
 * wallpaper drains each frame and animates as concentric expanding
 * cyan rings, like dropping a pebble into the rose.
 *
 * Fired by anything that wants the wallpaper to acknowledge an event
 * — currently [com.mythara.agent.AgentRunner.submit] uses it to
 * surface "you just asked me something" without needing a foreground
 * UI. Future hooks could include reminders firing, notification
 * triage decisions, or completed tool calls.
 *
 * The renderer keeps its own short-lived list of ACTIVE ripples
 * (drained from this queue) so it can age them out over the
 * RIPPLE_DURATION_MS window even if no one pings again.
 *
 * Origin coordinates are canvas-fraction `[0, 1]` so callers don't
 * need to know the device's actual pixel dimensions. Use the
 * default sentinel `-1f` for "centre on the rose" — the renderer
 * substitutes the rose's real centre at draw time.
 */
object ThoughtRippleSink {

    /** Single ripple — origin in canvas-fraction coords + the
     *  wall-clock time the ripple was queued. The renderer's frame
     *  loop ages each ripple by `now - startMs` to compute its
     *  current radius + alpha. */
    data class Ripple(
        val originXFrac: Float,
        val originYFrac: Float,
        val startMs: Long,
    )

    private val pending = ConcurrentLinkedQueue<Ripple>()

    /** Queue a ripple. Defaults to "centre on the rose" — the
     *  renderer translates the sentinel `-1f` to the actual rose
     *  centre at draw time, since this sink doesn't know the canvas
     *  dimensions. */
    fun ping(originXFrac: Float = -1f, originYFrac: Float = -1f) {
        pending.add(Ripple(originXFrac, originYFrac, System.currentTimeMillis()))
    }

    /** Drain everything queued since the last call. Called from the
     *  renderer once per frame. ConcurrentLinkedQueue makes this
     *  safe to call without locking even though `ping()` may be
     *  invoked from other threads (agent / IO scope coroutines). */
    fun drainAll(): List<Ripple> = generateSequence { pending.poll() }.toList()
}
