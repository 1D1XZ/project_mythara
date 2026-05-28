# Reddit — r/androiddev

**Submit at:** https://reddit.com/r/androiddev/submit
**Flair:** `Open Source`
**Best time:** Wednesday 10–11 am ET.

## Title

```
Project M.Y.T.H.A.R.A — open-source agentic-AI OS layer in Kotlin + Compose (MIT)
```

## Body

```
Open-sourcing an Android side project I've been daily-driving for several months.

It's a sideloaded OS layer (one APK, com.mythara.debug) that runs an agent loop with 65+ tools — Calls / SMS / WhatsApp / Calendar / Alarm / Tasks / Termux / accessibility-driven screen reading / web fetch / on-device face recognition / notification triage / Canvas render with bundled Tailwind+Preact / image gen / plan executor / skill suggester. Compose throughout, Hilt for DI, Room for the analytics + memory layer, no Material-3-look-and-feel.

Stuff in here that might be useful to other Android devs even if you skip the AI piece:

1. BAL-exempted PendingIntent launch on Android 14+ — the obvious `pi.send()` lands as BAL_BLOCK when the creator app hasn't granted background-activity-launch to the sender. The fix is `ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()` passed through the 7-arg `pi.send(ctx, code, fillIn, null, null, null, opts)`. Activity context required, not Application. Took a logcat session to track down.
   https://github.com/ankurCES/project_mythara/blob/main/app/src/main/kotlin/com/mythara/services/NotificationFeedRepository.kt

2. Wake-on-pickup camera via `TYPE_SIGNIFICANT_MOTION` — a hardware-batched, ultra-low-power wake sensor designed for "phone moved" cases. CameraX still streams at native rate (changing that requires a second IDLE ImageAnalysis target), so the throttle drops frames BEFORE InputImage.fromMediaImage + detector.process so the CPU/GPU savings are real. ~10× drop while idle.
   https://github.com/ankurCES/project_mythara/blob/main/app/src/main/kotlin/com/mythara/camera/PhonePickupDetector.kt

3. Multi-skin theme engine — `MythPalette` + `SkinSpec` via CompositionLocal so the theme + brightness + skin propagate through 21 routes with zero per-screen edits. Each skin is `palette × spec` data; adding one is ~80 LOC.
   https://github.com/ankurCES/project_mythara/wiki/Design-Language-and-Skins

4. In-house markdown renderer (~330 LOC, zero deps). Handles the CommonMark subset that actually appears in agent prose: headings, bullets, ordered lists, quotes, fenced code, **bold**, *italic*, `code`, [links](url), ~~strike~~. Replaces a ~700 KB commonmark+renderer DSL dep.
   https://github.com/ankurCES/project_mythara/blob/main/app/src/main/kotlin/com/mythara/ui/markdown/MarkdownText.kt

5. Agent loop with hook middleware — Pre/post-tool decisions (Allow / Deny / Rewrite). Three default hooks: PathSanitiser (`~/` rewrite), DangerousShellHook (`rm -rf` / `dd` / `chmod 777` deny), AutoApproveHook (allowlist consultation).

Default chat model is MiniMax M2 (cheap, 5M free tokens) — entirely model-agnostic, swap in 200 LOC. Wiki has a worked example for Gemma Nano via MediaPipe.

Repo: https://github.com/ankurCES/project_mythara
Wiki: https://github.com/ankurCES/project_mythara/wiki

Happy to discuss design choices, the Hilt graph, the BAL detective work, or the persona-extraction approach.
```

## Notes

- r/androiddev cares about Android API specifics. Lead with concrete API-level wins (BAL, SIGNIFICANT_MOTION, theme engine). Save the AI agentic framing for the second half.
- Drop a screenshot of the agent renderer + a code snippet in a top-level comment.
- Mods can be strict about self-promotion — make sure the post is technical-content-heavy.
