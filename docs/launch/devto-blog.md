# dev.to long-form blog post

dev.to + HackerNoon are excellent for SEO. A long-form launch post indexes Google + brings in starring traffic for months. Cross-post the same content to both.

**Submit at:**
- https://dev.to/new
- https://hackernoon.com/u/<your-username>/new-story

**Schedule:** Thursday morning, 10 am ET.
**Tags (dev.to):** `android`, `kotlin`, `opensource`, `ai`, `agentic`, `compose`

## Title

```
I open-sourced an agentic-AI OS layer for Android — here's what's inside
```

## Cover image

Use `docs/preview/hero.png` as the cover. dev.to renders it banner-style.

## Body

```markdown
# I open-sourced an agentic-AI OS layer for Android — here's what's inside

I've spent the past several months daily-driving a side project that runs an agentic AI as an OS layer on my phone — locally, with zero analytics SDKs, with the assistant's brain swappable for any model I want.

Today I open-sourced it as Project M.Y.T.H.A.R.A — "Mind Yoked Tonal Haptic Adaptive Resonant Assistant".

This post walks through what's actually in the codebase, why each piece exists, and the parts I most want help on.

---

## Why this exists

The 2025 Android Show rebranded Android from "an operating system" to "an intelligence system" — meaning Gemini is now embedded across messaging, camera, navigation, and in-car. The comments on the keynote upload were disabled. Some of the demos hinged on Gemini scanning your photo library for your passport.

I wanted the same outcomes — voice-driven personal assistant, contextual suggestions, agent-driven action-taking — but with the data, the agent, and the model under my control. Mythara is what that looks like.

---

## The architecture, one minute

```
ChatViewModel
    ↓
AgentRunner (queue · plan-gate · marker prefix routing)
    ↓
AgentLoop
    ↓
  ┌────────────┬────────────┬────────────┬──────────────┐
  │ Context    │ Loop       │ Hook       │ Skill        │
  │ Budget     │ Detector   │ Runner     │ Suggester    │
  │ Guard      │            │            │              │
  └────────────┴────────────┴────────────┴──────────────┘
    ↓
ToolRegistry (65+ tools)
  send_sms · send_whatsapp · place_call · create_calendar_event
  set_alarm · create_task · screen_read · run_shell · termux_exec
  read_file · write_file · web_fetch · render_canvas
  generate_image · open_url · save_skill · run_skill · ...
```

On-device analytics, memory, and the model adapter sit alongside:
- `PersonaTraitExtractor` + `GraphTurnExtractor` + `ContactProfileRepo` + `FaceTracker` + `NotificationListener` → all local-only.
- `MemorySync` (optional) → pushes JSONL channels to *your* GitHub repo. No vendor cloud.
- `MiniMaxClient` (or your Gemma / Llama / Qwen adapter) → the only model-side piece.

---

## What's actually in it

### Agent runtime
- `AgentLoop.kt` — streaming model interface, tool-call iteration, loop-detection, context-budget auto-summarisation.
- `HookRunner` — pre/post-tool middleware. Three default hooks: path sanitiser, dangerous-shell denial, allowlist consultation.
- `PlanAgent` + `PlanExecutor` — decomposes long prompts into multi-step plans, walks them.
- `SkillSuggestionStore` — watches repeated tool chains, offers to save them as named skills (markdown files under `filesDir/skills/<name>/`).

### Local-first analytics
- **Big Five** per contact, derived lexically (LIWC-style category counts) then prose-summarised by the chat model with a structured prompt. Tiered confidence disclaimer based on sample size.
- **Memory graph** — typed entities (person / place / organisation / app / notification-source) with classifier-driven cleanup that demotes spam senders out of the People list.
- **Face index** — MobileFaceNet on NNAPI / GPU; embeddings stored locally.
- **Interaction log** — per-contact (kind, source, ts, lat, lng) rows backfilled from chat + audit history.

### Design language
- **Multi-skin theme engine** — `MythPalette` + `SkinSpec` via CompositionLocal. Four skins (Spatial / Aurora Glass / Living Rose / Holographic HUD), light + dark + auto modes. Adding a new skin is ~80 LOC of data.
- **In-house Markdown renderer** — 330 LOC of zero-dep Kotlin handling the CommonMark subset that actually appears in agent prose: headings, bullets, ordered lists, quotes, fenced code, **bold**, *italic*, `code`, [links](url), ~~strike~~.

### Mobile-first UX
- **Rose-amulet PTT** — hold the bottom-centre spinning rose for ≥ 3 s to talk; release submits.
- **Spine launcher** — 3 dp Charple-breathing strip on the right edge; tap for menu.
- **Pickup-only camera** — `TYPE_SIGNIFICANT_MOTION` opens an 8 s active window; face detection extends it. Idle drain → ~0.
- **BAL-exempted notification launch** — Android 14+ requires `ActivityOptions.setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`. Documented in code; took a logcat session to track down.

---

## What I'd love help with

### 🥇 Local-LLM adapter
The single highest-leverage PR. The default model is MiniMax M2 — the agent loop talks to one 200-LOC file (`minimax/StreamingChat.kt`). Replace it with a Gemma Nano / llama.cpp / Ollama-via-LAN adapter and Mythara runs on your model. Wiki has a worked example for Gemma via MediaPipe LLM Inference:

https://github.com/ankurCES/project_mythara/wiki/Bring-Your-Own-Model

### 🥈 New tools
~30 LOC each. Examples we'd merge today:
- GitHub PR creation
- Obsidian vault writes
- Spotify control
- HomeAssistant bridge
- Email triage via IMAP

### 🥉 New skin
CRT, paper, brutalist — all welcome. Each skin is `MythPalette + SkinSpec + MythBackdrop` data.

---

## Links

- Repo: https://github.com/ankurCES/project_mythara
- Wiki (architecture, BYO model, contributing): https://github.com/ankurCES/project_mythara/wiki
- Why it exists (the Android-17 framing): https://github.com/ankurCES/project_mythara/wiki/Why-Mythara
- Privacy doc: https://github.com/ankurCES/project_mythara/blob/main/docs/PRIVACY.md

---

## Closing

I built Mythara because I wanted to use a phone whose AI is mine — not Google's, not Apple's, not OpenAI's. The code is auditable in 30 minutes. The dep tree has zero analytics SDKs. The model is whatever you want it to be.

If you've been waiting for an open-source agentic phone-AI to fork — this is the kit.

Build your phone's AI. Don't rent it. 🌹
```

## Notes

- dev.to + HackerNoon let you cross-post the same content. Submit both.
- Tag the post heavily; dev.to gives multi-tag posts more reach.
- Reply to every comment for 24 hours. Engaged threads boost the post's home-page placement.
- After ~2 weeks, the post starts ranking on Google for "open source agentic AI Android". That's the long-tail SEO win.
