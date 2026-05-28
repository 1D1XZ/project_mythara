# Reddit — r/opensource

**Submit at:** https://reddit.com/r/opensource/submit
**Best time:** Thursday or Friday afternoon.

## Title

```
Mythara — open-source agentic AI OS layer for Android (MIT, Kotlin + Compose, BYO model)
```

## Body

```
Open-sourcing an Android side project I've been daily-driving for several months.

Project M.Y.T.H.A.R.A ("Mind Yoked Tonal Haptic Adaptive Resonant Assistant") is a sideloaded OS layer that runs an agentic AI entirely under your control:

- Local-first by construction. Your contacts, messages, photos, locations, personality analysis, voice + face samples — all stay on the device.
- 65+ tools wired into one agent loop: calls, SMS, WhatsApp, calendar, alarms, accessibility-driven screen reading, shell + Termux, file I/O, web fetch, image gen, on-device face recognition, notification triage, plan executor, skill suggester.
- Multi-skin Compose theme engine: Spatial / Aurora Glass / Living Rose / Holographic HUD.
- Optional cross-device sync through *your own* GitHub repo with a fine-grained PAT. No Mythara cloud.

Built around the assumption that the model is the user's choice, not the vendor's. Default model adapter is MiniMax M2 (cheap, 5M free tokens/month). The adapter is one 200-LOC file you can swap for Gemma Nano via MediaPipe, llama.cpp, or Ollama-via-LAN-HTTP. Wiki has a worked example: https://github.com/ankurCES/project_mythara/wiki/Bring-Your-Own-Model

License: MIT.
Hardware: Pixel 9 / 10 / Fold tested.

Repo: https://github.com/ankurCES/project_mythara
Wiki (architecture, BYO model, contributing): https://github.com/ankurCES/project_mythara/wiki

If you're looking for a project to contribute to:
- 🥇 Local-LLM adapter (Gemma Nano / llama.cpp / Ollama) — highest-leverage PR. Wiki has the contract.
- 🥈 New tools — ~30 LOC each. GitHub PR creation, Obsidian writes, Spotify control, HomeAssistant bridge.
- 🥉 New skin — `MythPalette + SkinSpec + MythBackdrop`. CRT, paper, brutalist all welcome.

Issues + PRs accepted readily; maintainer ([@ankurCES](https://github.com/ankurCES)) responds personally.
```

## Notes

- r/opensource is general-OSS broad — keep the post tighter than the niche subs.
- Emphasise the **contribution paths**. That's what this sub clicks through for.
