# Awesome-list PRs

The cheapest, highest-durability way to compound launch traffic. Awesome lists rank in Google for years; one merged PR sends a slow trickle of stars + visitors forever.

**Strategy:** open ~8-12 PRs across the lists below over the launch week. Each takes ~5 minutes. Acceptance rates vary; aim for 50 %.

## Order of priority

Higher-rank repos = more durable traffic. Don't waste energy on the long-tail until the top ones land.

### Tier 1 — must-PR

| Repo | Where to add | Stars |
|---|---|---|
| **awesome-agentic-ai** — https://github.com/raj-dy/awesome-agentic-ai | `Mobile` or `Tools` section | 1.4k |
| **awesome-llm-apps** — https://github.com/Shubhamsaboo/awesome-llm-apps | `AI Agent Frameworks` or new `Mobile` section | 28k+ |
| **awesome-self-hosted** — https://github.com/awesome-selfhosted/awesome-selfhosted | `Personal Assistants` (already exists) | 200k+ |
| **awesome-privacy** — https://github.com/Lissy93/awesome-privacy | `Mobile Assistants` or `Open Source AI` | 11k+ |
| **awesome-android-ui** — https://github.com/wasabeef/awesome-android-ui | only if you have a strong showcase video | 50k+ |

### Tier 2 — high-fit

| Repo | Where to add |
|---|---|
| **awesome-ai-agents** — https://github.com/e2b-dev/awesome-ai-agents | `Open Source Agents` section |
| **awesome-android** — https://github.com/JStumpp/awesome-android | Apps → Productivity |
| **awesome-foss-apps** — https://github.com/syntithenai/awesome-foss-apps | New entry |
| **awesome-local-llm** — https://github.com/vince-lam/awesome-local-llm | `Mobile / Edge` section |
| **awesome-android-libraries** — https://github.com/wasabeef/awesome-android-libraries | only if your markdown renderer / theme engine module ships standalone |

### Tier 3 — long-tail (do these after Tier 1 lands)

- **awesome-llmops** — https://github.com/tensorchord/awesome-llmops
- **awesome-foss-android** — search GitHub for current top-ranked one
- **awesome-private-llm** — search; multiple exist
- **awesome-kotlin** — https://github.com/Heapy/awesome-kotlin — under `Awesome > Applications > Android`
- **awesome-android-things** — only if you do a Wear OS face port

## Template PR

Most awesome lists follow the same convention. Copy this as your PR body:

### PR Title

```
Add Project M.Y.T.H.A.R.A — open-source agentic-AI Android OS layer
```

### PR Body

```markdown
## What this adds

A new entry under `<section>`:

- **[Project M.Y.T.H.A.R.A](https://github.com/ankurCES/project_mythara)** — open-source, local-first, agentic-AI OS layer for Android. 65+ built-in tools, on-device personality analysis, multi-skin Compose theme engine. MIT. `Kotlin` `Android` `MIT`

## Why it fits

`<one-sentence justification matching the list's curation criteria>`

## Compliance

- [x] License: MIT
- [x] Actively maintained (commits within the last 30 days)
- [x] Working / installable (`./gradlew :app:assembleDebug` from a clean clone)
- [x] Has a README + docs (wiki at https://github.com/ankurCES/project_mythara/wiki)
- [x] Format matches existing entries in the list
```

### List-specific tweaks

- **awesome-self-hosted** — emphasise "sync through your own GitHub repo". Their curation is allergic to anything cloud-default.
- **awesome-privacy** — emphasise no analytics SDKs + the `git grep` audit. Lissy93's list reviews carefully.
- **awesome-llm-apps** — emphasise the agent runtime + tool catalogue, not just the chat surface.
- **awesome-agentic-ai** — emphasise the planner + skill suggester, not the UI.

## After your PRs

- Watch GitHub Notifications. Awesome-list maintainers usually respond within a few days.
- If a maintainer asks for changes, address them within 24 hours — that's the window before they move on.
- **Star their repo.** Polite reciprocation; also signals you care about the list, not just your project.
- **Don't comment "any update?"** on a stale PR. Wait 2 weeks, then a polite ping.
