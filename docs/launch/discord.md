# Discord — high-signal communities

Discord is where the real conversations happen for niche AI / Android dev. Lower volume than Reddit but higher conversion to contributors.

**Schedule:** Friday evening (after the LinkedIn post).
**Etiquette:** post in `#showcase` or `#share-your-projects` channels only — never general chat. Most servers have explicit rules.

---

## LocalLLaMA Discord

**Invite:** Search "LocalLLaMA Discord" — the official one is linked from the subreddit sidebar.
**Channel:** `#projects` or `#showcase` (read pinned rules first).

### Message

```
Hey LocalLLaMA — I open-sourced an agentic-AI OS layer for Android: https://github.com/ankurCES/project_mythara

It's MIT, runs on Pixel 9/10/Fold, ships with 65+ tools (calls, SMS, calendar, shell, Termux, face recognition, etc.) and a model-agnostic agent loop. Default model is MiniMax M2 — but the only file the loop talks to is `minimax/StreamingChat.kt` (~200 LOC). **A drop-in Gemma Nano via MediaPipe / llama.cpp / Ollama adapter is the highest-leverage PR anyone could ship.**

Wiki has the contract + a worked example for Gemma:
https://github.com/ankurCES/project_mythara/wiki/Bring-Your-Own-Model

System-prompt marker pattern for tool calls on models without native support is documented there too — smaller Llamas can be coached into it.

Happy to walk through the architecture or pair on the local-LLM swap with anyone interested.
```

---

## MediaPipe / Google AI Edge Discord

**Invite:** https://discord.gg/mediapipe (or via Google AI Edge docs)
**Channel:** `#android` or `#community-showcase`.

### Message

```
Shipped an Android side project that uses MediaPipe Face Detection + (planned) MediaPipe LLM Inference: https://github.com/ankurCES/project_mythara

The face pipeline runs MobileFaceNet on NNAPI + a `TYPE_SIGNIFICANT_MOTION`-gated bind/unbind so the camera is dark unless you physically pick up the phone (~10× drain reduction vs always-on).

Planning a Gemma-Nano-via-MediaPipe adapter as the local-model story; would love to learn from anyone who's built one. The agent loop already handles streaming + tool-call parsing; I just need the adapter.

The contract: https://github.com/ankurCES/project_mythara/wiki/Bring-Your-Own-Model
```

---

## Android Devs Discord

**Invite:** https://discord.gg/android-developers (or via r/androiddev sidebar)
**Channel:** `#showcase` / `#open-source-projects`.

### Message

```
Open-sourced an agentic-AI Android side project this week: https://github.com/ankurCES/project_mythara

MIT, Kotlin + Compose, Hilt, Room. 65+ tools, multi-skin theme engine, on-device personality analysis, pickup-only camera via `TYPE_SIGNIFICANT_MOTION`.

A couple of API-level wins might be useful even if you skip the AI piece:
- BAL-exempted PendingIntent.send on Android 14+: `ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()` through the 7-arg overload. Activity context required.
- Multi-skin theme engine via CompositionLocal that propagates through 21 routes with zero per-screen edits.
- Zero-dep markdown renderer (~330 LOC) for CommonMark subset.

Wiki has the architecture: https://github.com/ankurCES/project_mythara/wiki/Architecture
```

---

## Notes for ALL Discord posts

- **Read pinned rules first.** Some servers ban self-promotion outside specific channels.
- **One message per server.** Don't double-post.
- **Stay in the thread** for an hour after posting to answer questions.
- **Never DM the post link unsolicited.** That gets you banned across the federated mod network.
- If a server doesn't have a `#showcase`, find an active thread on a related topic and contribute substantively first — *then* mention your project in a follow-up that adds technical value.
