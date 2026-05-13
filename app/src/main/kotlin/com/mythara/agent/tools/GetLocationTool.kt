package com.mythara.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `get_location` — current physical location of the device.
 *
 * Uses platform [LocationManager] (no Play Services dependency).
 * On API 30+ asks for a fresh fix via `getCurrentLocation`; on older
 * devices falls back to `getLastKnownLocation` across the GPS and
 * NETWORK providers, picking the freshest.
 *
 * Reverse-geocoding to a human-readable address is best-effort via
 * [Geocoder]; if the device has no geocoder backend (some custom
 * ROMs, Pixels without Play Services) we still return lat/lng/accuracy.
 *
 * Permission: ACCESS_FINE_LOCATION. We surface a structured
 * `permission_denied` error if not granted; the model can then ask
 * the user to grant via system Settings.
 */
@Singleton
class GetLocationTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    @Serializable
    data class Response(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val altitudeMeters: Double? = null,
        val timestampMs: Long,
        val provider: String,
        /** Human-readable reverse-geocode, or null when geocoder is unavailable. */
        val addressLine: String? = null,
        val locality: String? = null,
        val country: String? = null,
    )

    override val name: String = "get_location"

    override val description: String =
        "The phone's current location (latitude + longitude + accuracy + best-effort address). " +
            "Use when the user asks 'where am I', 'where is my phone', or any question that needs a coordinate."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"Location permission isn't granted. Open Settings → Apps → Mythara → Permissions and allow Location to use this tool."}""",
            )
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return ToolResult(
                ok = false,
                output = """{"error":"no_location_manager","detail":"Couldn't access the system location service."}""",
            )
        val location = currentLocation(lm) ?: return ToolResult(
            ok = false,
            output = """{"error":"no_fix","detail":"Couldn't get a location fix. Try moving near a window or to an open area, then ask again."}""",
        )
        val (addr, loc, country) = reverseGeocode(location)
        val response = Response(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            timestampMs = location.time,
            provider = location.provider ?: "unknown",
            addressLine = addr,
            locality = loc,
            country = country,
        )
        return ToolResult(ok = true, output = JSON.encodeToString(Response.serializer(), response))
    }

    /**
     * Best-effort one-shot fix. API 30+ supports the fresh
     * `getCurrentLocation` callback; older devices fall back to the
     * cached `getLastKnownLocation` across both GPS + Network
     * providers, picking the most recent.
     */
    @androidx.annotation.RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ])
    private suspend fun currentLocation(lm: LocationManager): Location? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = pickBestProvider(lm) ?: return null
            return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(
                        provider,
                        cancel,
                        ctx.mainExecutor,
                    ) { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                }
            } ?: bestLastKnown(lm)
        }
        return bestLastKnown(lm)
    }

    @androidx.annotation.RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ])
    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = lm.getProviders(true)
        var best: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best!!.time) best = l
        }
        return best
    }

    private fun pickBestProvider(lm: LocationManager): String? {
        val enabled = lm.getProviders(true)
        return when {
            LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> enabled.firstOrNull()
        }
    }

    private suspend fun reverseGeocode(location: Location): Triple<String?, String?, String?> {
        if (!Geocoder.isPresent()) return Triple(null, null, null)
        val geocoder = Geocoder(ctx, Locale.getDefault())
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val a = list?.firstOrNull() ?: return@runCatching Triple<String?, String?, String?>(null, null, null)
                val line = (0..(a.maxAddressLineIndex.coerceAtLeast(0)))
                    .mapNotNull { runCatching { a.getAddressLine(it) }.getOrNull() }
                    .joinToString(", ")
                    .ifBlank { null }
                Triple(line, a.locality, a.countryName)
            }.getOrElse { Triple(null, null, null) }
        }
    }

    companion object {
        private const val FRESH_FIX_TIMEOUT_MS = 8_000L
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
