# LinkedIn

LinkedIn isn't the highest-volume channel but it converts professional contacts → stars / PRs / hiring conversations at a high rate. Don't skip it.

**Schedule:** Friday morning, 9 am ET. (Friday LinkedIn engagement is surprisingly strong for tech posts.)

## Post

```
After several months of side-project work, I'm open-sourcing Project M.Y.T.H.A.R.A.

It stands for "Mind Yoked Tonal Haptic Adaptive Resonant Assistant" — but the short version is: it's an open-source, agentic-AI OS layer for Android where the data stays on your phone.

The context: at the 2025 Android Show, Google rebranded Android from "an operating system" to "an intelligence system" and embedded Gemini across the OS surface — including a custom-widget generator, an in-car Gemini integration, and an auto-form-fill feature that scans your photo library for your passport.

The features are genuinely impressive. The data pipeline is theirs.

Mythara is what the same demos look like when the data, the agent, and the model all stay on your phone:
• An agent runtime with 65+ tools (calls, SMS, WhatsApp, calendar, alarms, shell, Termux, file I/O, web fetch, on-device face recognition, notification triage, planner).
• Personality analysis (Big Five + values + how-to-message prose) derived locally from your real conversations — never uploaded.
• A multi-skin Compose theme engine.
• Optional cross-device sync through your own GitHub repo with a fine-grained PAT. There is no Mythara server.

The default chat model is MiniMax M2 (cheap; 5M free tokens / month). The model adapter is one 200-LOC file — the highest-leverage contribution anyone could make is a Gemma-Nano-via-MediaPipe local-LLM adapter. Wiki has the worked example.

Built solo, used as my daily-driver phone OS layer. License: MIT.

Repo: https://github.com/ankurCES/project_mythara
Wiki (architecture, BYO model, contributing): https://github.com/ankurCES/project_mythara/wiki

#OpenSource #AgenticAI #Android #PrivacyByDesign #Kotlin #JetpackCompose
```

(attach hero composite)

## Notes

- LinkedIn rewards posts that **read like a story**. Lead with the Android-17 framing, not the feature list.
- Tag a few people whose work is adjacent (Android ecosystem, on-device ML, agentic AI) — but no more than 3. Algorithm penalises mass-tagging.
- Reply to every comment within 24 hours. LinkedIn's algorithm gives massive boosts to comment-heavy posts.
- Don't post the same thing twice. LinkedIn deprioritises duplicates HARD.
