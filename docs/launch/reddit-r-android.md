# Reddit — r/Android

**Submit at:** https://reddit.com/r/Android/submit
**Flair:** `App` or `Discussion`
**Best time:** Wednesday or Thursday, 10 am ET.

## Title

```
I've been daily-driving an open-source AI OS layer instead of Gemini for a few months. Here's what shipped.
```

## Body

```
Frustrated with the always-on cloud-Gemini posture Google has been pushing, I built a sideloaded Android app over the past several months that runs an agentic AI entirely on my phone. Open-sourced it today.

Quick tour:

🌹 Home — a particle face mesh that tracks my head via the front camera + a spinning geometric rose at the bottom. Tap rose = chat. Hold ≥ 3 seconds = mic on, transcript submits on release. Same press-and-hold-to-talk as a walkie-talkie. Pickup-detection means the camera only runs when I actually pick up the phone — drain is essentially zero between glances.

📢 Alerts — every notification on my phone surfaces here. Tap → opens the source app (took a logcat session to figure out the Android 14 BAL exemption). "Ask Mythara" → forwards into the agent loop with "help me handle this".

👥 People — full address book merged with Mythara's interaction history. Tap a contact → see Big Five trait bars, notable traits, "how to message them" prose, relationship summary, face-recognition samples, photos of them, recent interactions. All locally derived from my real chat history. Nothing uploaded.

🎨 Appearance — four skins: Spatial (flat), Aurora Glass (translucent + blur), Living Rose (HR-pulsing rose petals), Holographic HUD (line-art). Light + dark + auto (time-of-day) modes. Plus a Terminal chat mode for the old-school feel.

Things that are real:
- Agent runtime with 65+ tools (SMS, calls, WhatsApp, calendar, alarms, shell, Termux, web fetch, face recognition, notification triage, planner, skills).
- Personality analysis from your actual conversations, stored locally.
- Multi-device sync through *your own* GitHub repo with a fine-grained PAT. No Mythara cloud.

Things that aren't yet:
- Local model (Gemma Nano via MediaPipe is the roadmap). Currently uses MiniMax M2 (cheap, 5M free tokens/month). Adapter is swappable in 200 LOC.
- Wear OS face + glasses bridge.
- iOS — not planned. Apple's sandbox kills 80 % of the tool catalogue.

Hardware: tested on Pixel 9 / 10 / Fold. Should work on Pixel 6+ and Samsung One UI 6+.

License: MIT.

Repo: https://github.com/ankurCES/project_mythara
Wiki with screenshots + architecture: https://github.com/ankurCES/project_mythara/wiki

The framing is openly editorial — the 2025 Android Show rebranded Android from "operating system" to "intelligence system" and turned off comments on the keynote. Mythara is what the same demos look like when the data stays on your phone. The comment section, obviously, is open.
```

## Notes

- r/Android leans consumer-product-first. Visuals matter — drop the hero composite as the post image (or in a top comment).
- Avoid heavy code talk in the OP; save it for the comments. Devs will dig once they're hooked.
- The Android-17 framing is the hook. Don't bury it.
