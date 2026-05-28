# Reddit — r/selfhosted

**Submit at:** https://reddit.com/r/selfhosted/submit
**Flair:** `Software`
**Best time:** Monday morning, 9-10 am ET.

## Title

```
Mythara — self-hosted, on-device agentic AI for Android (sync through your own GitHub, not a vendor cloud)
```

## Body

```
Open-sourced an Android side project that treats personal AI like you'd treat a self-hosted NAS — your phone is the box, your data lives there, sync (if any) goes through infrastructure you own.

The pitch:
- Sideload one APK (com.mythara.debug). MIT-licensed.
- Personal AI assistant with 65+ tools (calls, SMS, calendar, alarms, file I/O, shell + Termux, web fetch, on-device face recognition, notification triage, planner).
- Personality + relationship analysis (Big Five + values + how-to-message prose) runs LOCALLY on your phone — never uploaded.
- Cross-device sync, when enabled, goes through **your own GitHub repo** with a fine-grained PAT you scope yourself. Mythara has no server.
- Multi-device coordination is just `git pull` + merge — no central account, no vendor cloud, no "remember password" anywhere except the model API key you optionally configure.

What's NOT cloud:
- Speech-to-text (Vosk, on-device)
- Wake word ("Hey Mythara", on-device)
- Face recognition (MobileFaceNet on NNAPI / GPU)
- Personality + entity extraction (lexical-then-LLM, but the LLM call stays in the chat layer)
- Notification listener (rolling 50-row buffer, ephemeral)

What IS cloud (default — swappable):
- Chat model. MiniMax M2 by default (5M free tokens/month). The model adapter is a 200-LOC file you can replace with a Gemma-Nano-via-MediaPipe local model. Wiki has a worked example: https://github.com/ankurCES/project_mythara/wiki/Bring-Your-Own-Model

Privacy auditing:
- Dependency tree has zero analytics SDKs. Grep it.
- Every remote URL is greppable: `git grep -nE "https?://" app/src/main/kotlin`
- The full privacy model is documented: https://github.com/ankurCES/project_mythara/blob/main/docs/PRIVACY.md

Hardware: tested on Pixel 9 / 10 / Fold. Should work on Pixel 6+ and Samsung One UI 6+. iOS not planned (Apple's sandbox kills most of the tool catalogue).

Repo: https://github.com/ankurCES/project_mythara
Wiki: https://github.com/ankurCES/project_mythara/wiki

Built solo, used as my daily-driver phone OS layer. AMA — happy to walk through the sync model, the BYO-model adapter contract, or the on-device personality extraction.
```

## Notes

- r/selfhosted respects the privacy + sovereignty angle. Lead with the "your phone is the NAS" framing.
- Be honest about the MiniMax cloud default — selfhosted folks will respect the candor + the swap path much more than vague hand-waves.
- Include screenshots of the People detail screen showing locally-derived personality analysis. Helps people grok what "private" means in practice.
