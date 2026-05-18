package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.ui.canvas.CanvasController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `render_canvas` — push an HTML render to the Canvas surface.
 *
 * The agent's visual channel. When text alone underserves the user —
 * an image you generated, an explainer card, a mini-game, a breath
 * pacer — render to the Canvas instead.
 *
 * Modes (delivery):
 *   - `mode=inline`: HTML body passed directly. Up to ~32 KB.
 *   - `mode=file`: HTML written to filesDir/canvas/<uuid>.html.
 *
 * Templates (skeleton wrapping):
 *   - `template=blank`: no wrapper. Agent owns `<html>` + `<head>`.
 *   - `template=tailwind` (default): wraps in a `<!doctype html>`
 *     skeleton that pre-loads Tailwind Play CDN (~85 KB) + DaisyUI
 *     styled core (~140 KB) + Mythara theme overrides. Agent
 *     writes ONLY the body content using Tailwind utilities and
 *     DaisyUI components.
 *   - `template=preact-tailwind`: same as tailwind PLUS Preact +
 *     HTM (~5 KB combined) so the agent can write JSX-shaped
 *     components without a build step.
 *
 * All templates load from `file:///android_asset/canvas/` (the
 * baseURL CanvasScreen already configures), so no CDN fetches
 * happen at render time — fully offline.
 *
 * Set `retain=true` to keep the render after the user navigates
 * away. Set `auto_navigate=false` to suppress the UI pivot to
 * Canvas (default true).
 */
@Singleton
class RenderCanvasTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: CanvasController,
) : Tool {
    override val name = "render_canvas"
    override val description =
        "Render HTML to Mythara's Canvas surface. Default template wraps the agent's HTML " +
            "with Tailwind v3 + DaisyUI + the Mythara theme (data-theme='mythara'), so the " +
            "agent writes ONLY body content using Tailwind utility classes + DaisyUI " +
            "components (`btn`, `card`, `badge`, `stat`, `alert`, `chat`, etc.). " +
            "Use template='preact-tailwind' for interactive renders that need state " +
            "(JSX-shaped via the `html\\`...\\`` template literal). Use template='blank' " +
            "only when you need to emit a complete custom <html> document. All assets " +
            "are bundled — no CDN fetches at render time."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("html", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "HTML for the body (or full document if template=blank). " +
                        "JS bridge `window.mythara.sendInput(json)` is available.",
                )
            })
            put("template", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "'tailwind' (default) | 'preact-tailwind' | 'blank'. " +
                        "Tailwind/Preact bundle is pre-loaded offline.",
                )
            })
            put("mode", buildJsonObject {
                put("type", "string")
                put("description", "'inline' (default, <= 32 KB) or 'file' (for larger).")
            })
            put("retain", buildJsonObject {
                put("type", "boolean")
                put("description", "Keep render across navigation. Default false.")
            })
            put("auto_navigate", buildJsonObject {
                put("type", "boolean")
                put("description", "Auto-pivot the UI to Canvas. Default true.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("html"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val rawHtml = args["html"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (rawHtml.isBlank()) return ToolResult.fail("html must be a non-empty body")
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull()?.lowercase() ?: "inline"
        val template = args["template"]?.jsonPrimitive?.contentOrNull()?.lowercase() ?: "tailwind"
        val retain = args["retain"]?.jsonPrimitive?.booleanOrNull() ?: false
        val autoNavigate = args["auto_navigate"]?.jsonPrimitive?.booleanOrNull() ?: true

        val wrapped = when (template) {
            "blank" -> rawHtml
            "tailwind" -> wrapTailwind(rawHtml, withPreact = false)
            "preact-tailwind" -> wrapTailwind(rawHtml, withPreact = true)
            else -> return ToolResult.fail("template must be 'tailwind' | 'preact-tailwind' | 'blank'")
        }

        return when (mode) {
            "inline" -> {
                controller.render(
                    CanvasController.Render(
                        mode = CanvasController.RenderMode.Inline,
                        payload = wrapped,
                        retain = retain,
                    ),
                    autoNavigate = autoNavigate,
                )
                ToolResult.ok(
                    "rendered ${wrapped.length} chars inline (template=$template, retain=$retain, nav=$autoNavigate)",
                )
            }
            "file" -> {
                runCatching {
                    val dir = File(context.filesDir, "canvas").apply { mkdirs() }
                    val file = File(dir, "${UUID.randomUUID()}.html")
                    file.writeText(wrapped)
                    controller.render(
                        CanvasController.Render(
                            mode = CanvasController.RenderMode.File,
                            payload = file.absolutePath,
                            retain = retain,
                        ),
                        autoNavigate = autoNavigate,
                    )
                    ToolResult.ok("wrote ${file.name} (${wrapped.length} chars, template=$template) and rendered")
                }.getOrElse { ToolResult.fail("file write failed: ${it.message}") }
            }
            else -> ToolResult.fail("mode must be 'inline' or 'file'")
        }
    }

    /** Compose a full HTML document around the agent's body content.
     *  Loads the bundled Tailwind Play CDN + DaisyUI styled core +
     *  the Mythara theme overlay. Optionally adds Preact + HTM for
     *  interactive components. */
    private fun wrapTailwind(body: String, withPreact: Boolean): String = buildString {
        append("""<!doctype html>
<html lang="en" data-theme="mythara">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <title>Mythara Canvas</title>
  <link rel="stylesheet" href="daisyui.css">
  <link rel="stylesheet" href="canvas.css">
  <script src="tailwind.min.js"></script>
  <script>
    // Tailwind Play CDN config — extend the theme with Mythara's
    // palette so `bg-mythara-charple`, `text-mythara-bok`, etc. work
    // alongside DaisyUI's `btn-primary` / `bg-base-200` shorthand.
    if (window.tailwind) {
      tailwind.config = {
        darkMode: 'class',
        theme: {
          extend: {
            fontFamily: {
              mono: ['JetBrains Mono', 'ui-monospace', 'SF Mono', 'Menlo', 'monospace'],
            },
            colors: {
              mythara: {
                bg:       '#1B1A22',
                surface:  '#26252E',
                surfaceMid: '#3A3943',
                surfaceHi:  '#4E4D58',
                fg:       '#E8E6F0',
                fgMute:   '#A8A4AB',
                fgDim:    '#605F6B',
                charple:  '#6B50FF',
                bok:      '#68FFD6',
                lavender: '#9B86FF',
                julep:    '#00FFB2',
                citron:   '#E8FF27',
                mustard:  '#F5EF34',
                malibu:   '#00A4FF',
                sriracha: '#EB4268',
              },
            },
          },
        },
      };
    }
  </script>
""")
        if (withPreact) {
            append("""  <script src="preact.min.js"></script>
  <script src="preact-hooks.min.js"></script>
  <script src="htm.min.js"></script>
  <script>
    // Expose a tiny no-build JSX shim. The agent imports
    // { html, render, useState, useEffect } from window.mythara,
    // builds components with the htm tagged template literal,
    // and mounts into #root.
    window.mythara = window.mythara || {};
    Object.assign(window.mythara, {
      h: preact.h,
      render: preact.render,
      Fragment: preact.Fragment,
      html: htm.bind(preact.h),
      useState: preactHooks.useState,
      useEffect: preactHooks.useEffect,
      useReducer: preactHooks.useReducer,
      useRef: preactHooks.useRef,
    });
  </script>
""")
        }
        append("""</head>
<body class="bg-mythara-bg text-mythara-fg">
  <div class="mythara-stage">
""")
        append(body)
        append("""  </div>
</body>
</html>
""")
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun JsonPrimitive.booleanOrNull(): Boolean? = runCatching { content.toBoolean() }.getOrNull()
}
