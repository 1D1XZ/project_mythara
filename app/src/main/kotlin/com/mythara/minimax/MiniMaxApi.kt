package com.mythara.minimax

import com.mythara.minimax.models.ModelsResponse
import retrofit2.Response
import retrofit2.http.GET

/**
 * Retrofit surface for the *non-streaming* endpoints we hit — currently
 * just `GET /models`, used to validate the user's API key when they save
 * it in Settings. The streaming `POST /chat/completions` goes through
 * [StreamingChat] directly via okhttp-sse and isn't on this interface,
 * because Retrofit's `Call`/`suspend` shapes don't model SSE cleanly.
 */
interface MiniMaxApi {
    @GET("models")
    suspend fun listModels(): Response<ModelsResponse>
}
