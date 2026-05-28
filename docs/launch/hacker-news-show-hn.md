# Hacker News — Show HN

**Submit at:** https://news.ycombinator.com/submit
**Best time:** Tuesday or Wednesday, **9–10 am ET**.
**URL field:** `https://github.com/ankurCES/project_mythara`
**Title (80 chars max — keep it tight):**

```
Show HN: M.Y.T.H.A.R.A – Open-source, local-first agentic AI layer for Android
```

## Body (text post — paste below the URL submission, in the comment field that auto-opens)

```
Hi HN — I'm Ankur. Project M.Y.T.H.A.R.A ("Mind Yoked Tonal Haptic Adaptive Resonant Assistant") is an open-source agentic-AI OS layer for Android that you sideload as one app. It's MIT-licensed and runs on Pixel 9 / 10 / Fold.

The framing is openly editorial — at the 2025 Android Show, Google rebranded Android from "an operating system" to "an intelligence system" and turned off comments on the keynote video. Mythara is what the same demos look like when the data, the agent, and the model stay on your phone.

What's actually shipped:
- An agent runtime with 65+ tools (calls, SMS, WhatsApp, calendar, alarms, shell, Termux, file I/O, web fetch, face recognition, notification triage, planner, skill suggester).
- Local-first analytics: per-contact Big Five + values + preferences + relationship graph, all derived from your actual chat history, never uploaded.
- Multi-skin Compose theme engine: Spatial (default), Aurora Glass (translucent), Living Rose (HR-breathing geometric rose), Holographic HUD.
- Notification listener → in-app Alerts hub with BAL-exempted source-app launch (Android 14+ requires ActivityOptions.setPendingIntentBackgroundActivityStartMode — not obvious; took a logcat session to figure out).
- Camera wakes only on phone-pickup via TYPE_SIGNIFICANT_MOTION + an 8s window that extends on each face detection. Idle drain → ~0.
- Music Mode: every word of a reply renders in a constructed OM-harmonic language with grammar particles (linker / copula / pronoun) and morphological suffixes (-ing, -ed, -s as appended tones).

What's NOT shipped — please don't expect it:
- Local LLM. Default model is MiniMax M2 (cheap, 5M free tokens/month, swappable in ~200 LOC). The highest-leverage PR anyone could ship is a Gemma-Nano-via-MediaPipe adapter — see the wiki's "Bring Your Own Model" page for the contract.
- Wear OS face / glasses bridge. Planned.
- iOS port. Not planned — Apple's sandbox kills 80 % of the tool catalogue.

What I'd love feedback on:
1. Anyone running an Android-side llama.cpp / MediaPipe / MLC-LLM stack — does the swap pattern in `minimax/StreamingChat.kt` look reasonable to adapt?
2. The system-prompt pattern for tool-call markers when the model doesn't have native tool support — see docs/wiki/Bring-Your-Own-Model.md. Open to refinement.
3. The pickup-only camera dance — is `TYPE_SIGNIFICANT_MOTION` the right primary or should I be using `WindowInsets.isVisible` + something else?

Built solo over the past several months, used as my daily-driver phone OS layer.

Repo: https://github.com/ankurCES/project_mythara
Wiki: https://github.com/ankurCES/project_mythara/wiki
```

## Notes for the poster

- **Do NOT** edit the title after submission — HN penalises edited titles.
- Respond to every top-level comment within the first 2 hours. Mod activity matters more than the post itself.
- If someone asks "why not just use Gemini", point at [Why-Mythara](https://github.com/ankurCES/project_mythara/wiki/Why-Mythara) — don't argue, link.
- Don't ask people to "upvote" anywhere. HN will dock you.
- If a comment is dismissive but technically wrong (e.g. "Android doesn't allow this"), respond once with logcat / API docs and move on. Don't argue past 2 replies.
