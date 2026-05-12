package com.mythara.minimax.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MiniMax's error envelope. Two shapes appear in practice:
 *  - OpenAI-compat path: `{"error":{"message":"…","type":"…","code":"2049"}}`
 *  - Native path: `{"base_resp":{"status_code":2049,"status_msg":"…"}}`
 *
 * The OpenAI-compat shape covers `chat/completions` and `models`, which
 * is all we currently call. If we ever talk to native endpoints (T2A in
 * non-streaming mode, STT), add a parallel decoder.
 */
@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
data class ErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
    @SerialName("param") val param: String? = null,
)
