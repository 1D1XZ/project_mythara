# Reddit — r/privacy

**Submit at:** https://reddit.com/r/privacy/submit
**Flair:** `Software`
**Best time:** Thursday morning, 10–11 am ET.

## Title

```
Open-source agentic AI for Android where the data never leaves the device. No analytics SDKs, no telemetry, auditable in 30 minutes.
```

## Body

```
TL;DR — I built and open-sourced an Android app that does what Google's Android-17 Gemini layer claims to, except your personality analysis, face index, contact data, and chat history all stay on your phone.

Inverts from Google's 2025 Android Show pitch:
- "Android is an intelligence system" → "Mythara is an OS layer you sideload"
- "Gemini scans your photo library for your passport" → local face + entity index, never auto-scans for ID docs
- "Cross-device sync via Google" → sync (optional) through *your own* GitHub repo with a fine-grained PAT
- "Cloud wake word" → Vosk on-device
- "Personalised suggestions trained on your data" → no centralised data, no training corpus, nothing to sell

What's on the phone:
- Chat history (Room DB, com.mythara.debug app storage)
- Per-contact Big Five + personality insights + relationship summary (locally derived, never uploaded)
- Face embeddings (only for photos you explicitly added; never auto-scans your gallery)
- Notification buffer (rolling 50-row in-memory, ephemeral — wiped on listener disconnect)
- Skills library (saved tool-chain recordings as Markdown files)

What's NOT on the phone (i.e. what you'd expect a "smart" assistant to have but Mythara doesn't):
- Browsing history
- Continuous location tracking
- Raw audio after STT (PCM is dropped once transcript is in)
- Live camera frames
- Any analytics SDK at all (the dep tree has zero — grep it)

Auditable in 30 minutes:
- `git grep -nE "https?://" app/src/main/kotlin` — every remote URL
- `git grep -nE "API.*key" app/src/main/kotlin` — every key read
- The repo is small enough to fully read before lunch

License: MIT.

Hardware: Pixel 9 / 10 / Fold tested. Should work on Pixel 6+ and Samsung One UI 6+. iOS not planned.

Default chat model is MiniMax M2 (cloud, but it's the only thing that's cloud — and you can swap it in 200 LOC for a local Gemma Nano via MediaPipe; wiki has the worked example).

The privacy doc lives at https://github.com/ankurCES/project_mythara/blob/main/docs/PRIVACY.md and walks through exactly what's stored, how to wipe everything, and the on-device threat model.

Repo: https://github.com/ankurCES/project_mythara
Wiki: https://github.com/ankurCES/project_mythara/wiki
```

## Notes

- r/privacy will scrutinise the "but the chat model is cloud" — be upfront about it AND clearly state the swap path. Hand-waving will get downvoted.
- Mention the no-analytics-SDK posture explicitly. It's rare and respected.
- Don't get into the "is it really private if it does X" arguments — link the audit instructions instead.
