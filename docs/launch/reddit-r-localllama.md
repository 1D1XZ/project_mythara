# Reddit — r/LocalLLaMA

**Submit at:** https://reddit.com/r/LocalLLaMA/submit
**Flair:** `Resources`
**Best time:** Monday morning, 10–11 am ET (peak weekly traffic).

## Title

```
[Open-source] Mythara — agentic Android OS layer with 65+ tools, BYO local model (MiniMax default; Gemma adapter wanted)
```

## Body

```
Hi r/LocalLLaMA,

I shipped Project M.Y.T.H.A.R.A — an open-source agentic-AI OS layer for Android. It's MIT, runs on Pixel 9 / 10 / Fold, and is built around the assumption that the **model is the user's choice, not the OS vendor's**.

Today the default model adapter is MiniMax M2 (5M free tokens/month, OpenAI-shaped API, native tool calls). But the only file the agent loop talks to is `minimax/StreamingChat.kt` — about 200 LOC. **A drop-in Gemma Nano / llama.cpp / Ollama-LAN adapter is the single highest-leverage PR anyone could ship.** Wiki page with the contract + a worked example for Gemma via MediaPipe LLM Inference:

https://github.com/ankurCES/project_mythara/wiki/Bring-Your-Own-Model

What you get with the agent loop, regardless of model:
- 65+ tools wired through one ToolRegistry (calls, SMS, WhatsApp, calendar, alarms, accessibility-driven screen reading, shell, Termux, file I/O, web fetch, on-device face recognition, image gen, Canvas render with Tailwind + Preact bundled, planner, skill suggester).
- Context-budget guard that auto-summarises history when you near the model's window.
- Loop detector that halts read→edit→read→edit cycles.
- Hook middleware that sanitises paths + blocks dangerous shell patterns.
- Plan agent that decomposes long prompts into a multi-step plan + executor walks it.
- Skill recorder — Mythara watches your repeated tool chains and offers to save them as named skills (`filesDir/skills/<name>/SKILL.md`).

For a model that doesn't have native tool calls, the wiki shows the system-prompt marker pattern (`[TOOL] name {...}`) that Gemma / smaller Llamas can be coached into emitting. The agent loop's stream parser is permissive — your adapter just needs to emit `ToolCallDelta` events when it sees them.

Repo: https://github.com/ankurCES/project_mythara

What's NOT shipped:
- Local LLM. That's the ask.
- Wear OS / glasses bridge. Planned.

What's also in there if it helps:
- On-device personality model — every contact gets a Big Five + values + how-to-message prose all derived locally from real chat history.
- Local face recognition (MobileFaceNet on NNAPI / GPU).
- Multi-skin Compose theme engine.

Happy to walk through the adapter contract with anyone who wants to swing the Gemma PR. AMA.
```

## Notes

- r/LocalLLaMA hates SaaS shilling. Lead with **"BYO model, MiniMax is just the default placeholder"** — that's the truth and that's what the community wants.
- Drop a comment with one screenshot of the chat surface + one of the agent tool-call trace in logcat. Visuals get upvotes.
- Stay in the thread for 4-6 hours after posting; the discussion is the whole point.
