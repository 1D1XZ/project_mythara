# Launch Playbook

> Tactical guide for pushing **Project M.Y.T.H.A.R.A** to its highest-value open-source channels. Ordered by **expected ROI** (audience fit × reach × ease of posting). Each entry is paired with a copy-paste-ready post in `docs/launch/`.

## Posting order (do this over ~5 days, not all at once)

Spreading the launch across days protects against hitting the same person on every channel + lets you incorporate early feedback before the bigger surfaces.

| Day | Channel | Post file |
|---|---|---|
| 1 (Mon) | **r/LocalLLaMA** — niche-fit but high-quality engagement first | [`reddit-r-localllama.md`](reddit-r-localllama.md) |
| 1 (Mon) | **r/selfhosted** — privacy-first audience | [`reddit-r-selfhosted.md`](reddit-r-selfhosted.md) |
| 2 (Tue) | **Hacker News — Show HN** — peak window 9-11am ET on a Tue/Wed | [`hacker-news-show-hn.md`](hacker-news-show-hn.md) |
| 2 (Tue) | **Twitter / X thread** — pair w/ HN so the wave amplifies | [`twitter-thread.md`](twitter-thread.md) |
| 3 (Wed) | **r/androiddev** — devs who can contribute | [`reddit-r-androiddev.md`](reddit-r-androiddev.md) |
| 3 (Wed) | **r/Android** — broader reach after the dev channels approved it | [`reddit-r-android.md`](reddit-r-android.md) |
| 3 (Wed) | **Mastodon (fosstodon.org)** + **Bluesky** | [`mastodon-bluesky.md`](mastodon-bluesky.md) |
| 4 (Thu) | **r/privacy** + **r/opensource** — broader OSS / privacy reach | [`reddit-r-privacy.md`](reddit-r-privacy.md), [`reddit-r-opensource.md`](reddit-r-opensource.md) |
| 4 (Thu) | **dev.to blog post** — long-form for SEO + GitHub stars from search | [`devto-blog.md`](devto-blog.md) |
| 5 (Fri) | **LinkedIn** — your professional network | [`linkedin.md`](linkedin.md) |
| 5 (Fri) | **Discord** — LocalLLaMA, MediaPipe, Android Devs | [`discord.md`](discord.md) |
| Ongoing | **Awesome list PRs** | [`awesome-list-prs.md`](awesome-list-prs.md) |

## Pre-launch checklist (~15 minutes)

- [ ] **Pin the README hero** — already shipped (`d376125`).
- [ ] **GitHub repo topics** — already set (`agentic-ai`, `local-first`, etc.).
- [ ] **Repo description** — already set (M.Y.T.H.A.R.A backronym).
- [ ] **Issue templates** — add bug + feature templates so first-time visitors can file proper issues. ~10 min in `gh repo edit` UI.
- [ ] **CONTRIBUTING.md** — already in wiki; add a stub at repo root linking to it.
- [ ] **LICENSE file** — confirm `LICENSE` (MIT) is at repo root.
- [ ] **Releases tab** — tag a `v0.1.0` release with the current commit so visitors see a download. `gh release create v0.1.0 --notes "Initial public release."` + attach the debug APK.
- [ ] **Discussions tab** — enable in repo settings → Features → Discussions. Doubles as your community forum.

## Conversation prep

You will get questions. Have ready answers:

| Question | Short answer |
|---|---|
| "How is this different from Termux + an LLM?" | Mythara is the agent runtime + tools + on-device personality model + the design language. Termux is a shell. |
| "Why MiniMax instead of [vendor]?" | Cheapest 5M free tokens + native tool calls. Swappable in 200 LOC ([Bring Your Own Model](https://github.com/ankurCES/project_mythara/wiki/Bring-Your-Own-Model)). |
| "Why not iOS?" | Apple's sandbox kills 80% of the tool catalogue. Not planned. |
| "Will Google sue you?" | The framing is editorial, not trademark-infringing. The OS layer is original code under MIT. |
| "Why no demo video?" | Coming in week 2 — record after first round of feedback so the demo reflects the real ship state. |
| "How do I trust you?" | The code's auditable; `git grep -nE "https?://"` shows every remote call. No analytics SDKs in the dep tree. |
| "Battery?" | Camera wakes only on phone-pickup (`TYPE_SIGNIFICANT_MOTION`). Idle drain is the agent's wake-word listener (Vosk, ~50mW) + the notification listener (~0 baseline). |

## Tracking

After launch, monitor:
- **Stars** — `gh repo view` shows current count.
- **Issues + PRs** — assign them daily.
- **Mentions** — search "M.Y.T.H.A.R.A" or "project_mythara" on Twitter/Mastodon weekly.
- **Discussions** — pin a "post your build issues here" thread.

A first-week goal of **500 stars + 5 PRs** is realistic if the HN post stays above the fold for ~6 hours.

## Reminder: don't oversell

The audiences on r/LocalLLaMA, HN, and Lobste.rs punish hype. Lead with what's true today:
- ✅ Agent runtime + 65+ tools shipped.
- ✅ Local-first analytics shipped.
- ✅ Multi-skin theme engine shipped.
- ⚠️ MiniMax cloud model is the default; **local model is roadmap**, not shipped.
- ⚠️ Wear OS face is roadmap.

State this plainly. Communities respect it.
