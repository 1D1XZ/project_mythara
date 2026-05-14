package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.HeartbeatSyncer
import com.mythara.memory.devices.DeviceMessageEntity
import com.mythara.memory.devices.DeviceMessageKind
import com.mythara.memory.devices.DeviceMessageRepository
import com.mythara.memory.devices.DeviceMessageStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `send_note_to_device` — sends a free-text idea / note / chat
 * message from this device to another Mythara install signed into
 * the same memory repo. The recipient's chat scrollback shows it as
 * a "from other device" card (same path as foreign chat history
 * restored by memory sync).
 *
 * Round-trip lands on the recipient within one heartbeat sync
 * (~5 min via HeartbeatSyncer, faster when the recipient is alive
 * and pulls inbox on the receiver-side sync).
 */
@Singleton
class SendNoteToDeviceTool @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
    private val deviceMessages: DeviceMessageRepository,
    /**
     * dagger.Lazy because HeartbeatSyncer → TaskExecutor → AgentRunner
     * → AgentLoop → ToolRegistry → this tool would otherwise form a
     * compile-time cycle. Lazy defers the resolve until the first
     * .get() call, which only happens after the tool actually fires.
     */
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : Tool {

    override val name: String = "send_note_to_device"
    override val description: String =
        "Send a free-text note / idea / chat message to ANOTHER of the user's Mythara installs (a phone, tablet, foldable, etc. signed into the same memory-sync repo). Communicates over the memory repo, not over the network directly. The recipient's chat scrollback shows the message as an incoming card from this device. " +
            "Use when the user explicitly asks to 'send / handoff / post / sync / share' a note or idea to a specific other device, or asks 'remind my <device> about X', etc. " +
            "Requires memory sync configured and the target device id (use list_mythara_devices to discover them)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "target_device_id",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Stable device id of the receiving Mythara install. Find it via list_mythara_devices.",
                        )
                    },
                )
                put(
                    "title",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "One-line summary the recipient sees at the top of the card. Max ~60 chars.",
                        )
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The actual note / idea / message body. Free text. Markdown is fine.",
                        )
                    },
                )
            },
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("target_device_id"))
                add(JsonPrimitive("title"))
            },
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val target = (args["target_device_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (target.isEmpty()) return ToolResult(false, """{"error":"missing_target_device_id"}""")
        val title = (args["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult(false, """{"error":"missing_title"}""")
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()

        val myId = deviceIdStore.id()
        if (target == myId) {
            return ToolResult(
                false,
                """{"error":"target_is_self","detail":"Can't send a note to this device — write it down in the chat surface directly."}""",
            )
        }

        val id = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("title", title)
            put("body", body)
        }.toString()
        val msg = DeviceMessageEntity(
            id = id,
            tsMillis = System.currentTimeMillis(),
            fromDevice = myId,
            toDevice = target,
            kind = DeviceMessageKind.CHAT_NOTE,
            requestId = id,
            payloadJson = payload,
            status = DeviceMessageStatus.PENDING,
        )
        deviceMessages.dao.insertIfAbsent(msg)
        Log.d(TAG, "enqueued chat_note $id → $target")
        // Kick the heartbeat so the message ships on the next sync
        // rather than waiting up to 5 minutes.
        runCatching { heartbeat.get().fireNow() }
        return ToolResult(
            true,
            """{"ok":true,"note_id":${JsonPrimitive(id)},"target":${JsonPrimitive(target)},"detail":"Note queued; will land on the recipient on its next sync."}""",
        )
    }

    companion object {
        private const val TAG = "Mythara/SendNote"
    }
}
