package com.mythara.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone → watch insight push.
 *
 * Ships a short Mythara insight / important-notification line to the
 * paired watch over the Wearable Data Layer under [INSIGHT_PATH]. The
 * watch-side MytharaWearDataReceiver caches it and refreshes the
 * Mythara Tactical watch face's insight complication, so the line
 * appears on the wrist in near-real-time.
 *
 * Fire-and-forget, callback-style (no coroutines-play-services dep) —
 * mirrors the wear module's own sendToPhone().
 */
@Singleton
class WatchInsightPusher @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun push(line: String) {
        val text = line.trim().replace('\n', ' ').take(120)
        if (text.isBlank()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(ctx)
        val msgClient = Wearable.getMessageClient(ctx)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.d(TAG, "no connected watch; insight not pushed")
                    return@addOnSuccessListener
                }
                for (node in nodes) {
                    msgClient.sendMessage(node.id, INSIGHT_PATH, bytes)
                        .addOnFailureListener { e ->
                            Log.w(TAG, "insight push to ${node.displayName} failed: ${e.message}")
                        }
                }
                Log.d(TAG, "pushed insight to ${nodes.size} node(s): \"${text.take(60)}\"")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "could not list connected nodes: ${e.message}")
            }
    }

    companion object {
        private const val TAG = "Mythara/InsightPush"

        /** Keep in sync with the wear module's WearPaths.INSIGHT. */
        const val INSIGHT_PATH = "/mythara/insight"
    }
}
