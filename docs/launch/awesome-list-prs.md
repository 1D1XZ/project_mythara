# Awesome-list PRs

The cheapest, highest-durability way to compound launch traffic. Awesome lists rank in Google for years; one merged PR sends a slow trickle of stars + visitors forever.

## Submitted (round 1 + 2)

| List | Stars | PR | Section | Status |
|---|---:|---|---|---|
| [e2b-dev/awesome-ai-agents](https://github.com/e2b-dev/awesome-ai-agents) | 28k | [#1022](https://github.com/e2b-dev/awesome-ai-agents/pull/1022) | Open-source projects | ⏳ pending |
| [timschneeb/awesome-shizuku](https://github.com/timschneeb/awesome-shizuku) | 8.9k | [#139](https://github.com/timschneeb/awesome-shizuku/pull/139) | Apps → AI agents | ⏳ pending |
| [jetpack-compose/jetpack-compose-awesome](https://github.com/jetpack-compose/jetpack-compose-awesome) | 2.6k | [#98](https://github.com/jetpack-compose/jetpack-compose-awesome/pull/98) | App Projects | ⏳ pending |
| [androiddevnotes/awesome-jetpack-compose-android-apps](https://github.com/androiddevnotes/awesome-jetpack-compose-android-apps) | 1.5k | [#42](https://github.com/androiddevnotes/awesome-jetpack-compose-android-apps/pull/42) | Pattern → MVVM | ⏳ pending |
| [adisonhuang/awesome-kotlin-android](https://github.com/adisonhuang/awesome-kotlin-android) | 1k | [#4](https://github.com/adisonhuang/awesome-kotlin-android/pull/4) | 完整 app (complete app) | ⏳ pending |

**5 PRs across ~42k cumulative stars.** Each entry was inserted in the correct alphabetical / categorical spot and matches the list's existing format exactly (Shizuku uses backtick-license format; awesome-ai-agents uses `<details>` blocks with Category / Description / Links subsections; Kotlin-Android list uses dropdown image preview; etc.).

## Audited and ruled out (would've been closed)

| List | Stars | Why not |
|---|---:|---|
| [Shubhamsaboo/awesome-llm-apps](https://github.com/Shubhamsaboo/awesome-llm-apps) | 111k | Cookbook of hand-built templates that all live IN the repo. Not a curated list of external projects. |
| [awesome-selfhosted/awesome-selfhosted](https://github.com/awesome-selfhosted/awesome-selfhosted) | 295k | Strictly self-hosted **web services + web applications**. Android apps explicitly out of scope. |
| [wasabeef/awesome-android-ui](https://github.com/wasabeef/awesome-android-ui) | 56k | Android UI **libraries**, not apps. |
| [Arindam200/awesome-ai-apps](https://github.com/Arindam200/awesome-ai-apps) | 12.5k | Same pattern as awesome-llm-apps — internal practical-examples cookbook, not external links. |
| [janhq/awesome-local-ai](https://github.com/janhq/awesome-local-ai) | 1.9k | Focused on local LLM **inference engines** (Ollama, llama.cpp, vLLM). Mythara's chat backbone is cloud-default; doesn't qualify. |
| [kaushikb11/awesome-llm-agents](https://github.com/kaushikb11/awesome-llm-agents) | 1.5k | Agent **frameworks / SDKs** for developers (CrewAI, LangChain, AutoGen). Mythara is an end-user app, not a framework. |
| [awesome-assistants/awesome-assistants](https://github.com/awesome-assistants/awesome-assistants) | 1.2k | LLM persona prompts for Telegram bots, not standalone assistant apps. |
| [albertomosconi/foss-apps](https://github.com/albertomosconi/foss-apps) | 1.1k | "Most of these apps are available on F-Droid." Mythara isn't on F-Droid yet — revisit after the F-Droid submission. |
| [cloudflare/awesome-agents](https://github.com/cloudflare/awesome-agents) | 0.2k | Cloudflare Agents SDK only. |
| [nibzard/awesome-agentic-patterns](https://github.com/nibzard/awesome-agentic-patterns) | 4.6k | Patterns, not products. |

## Defer to month 4+ (gated by repo maturity rule)

| List | Stars | Gate |
|---|---:|---|
| [Lissy93/awesome-privacy](https://github.com/Lissy93/awesome-privacy) | 9.4k | Contribution rules: *"Repositories must not be newly created, and the first stable release older than 4 months"*. Set a calendar reminder. The **Voice Assistants section is currently empty (`services: []`)** so the first entry there will be high-visibility. |

## Other candidates if needed later

These are smaller or more specialised lists. Useful for sustained drip-feed marketing in months 2-6 once the launch wave settles.

| Target | Notes |
|---|---|
| [pronzzz/awesome-android-foss](https://github.com/pronzzz/awesome-android-foss) | Tiny but exactly Mythara's category. Quick win. |
| [vince-lam/awesome-local-llm](https://github.com/vince-lam/awesome-local-llms) | Submit when a Gemma-Nano adapter ships — that's the angle. |
| [aishwaryanr/awesome-generative-ai-guide](https://github.com/aishwaryanr/awesome-generative-ai-guide) | Submit when there's a long-form blog post live. |
| [Heapy/awesome-kotlin](https://github.com/Heapy/awesome-kotlin) | Submit under `Awesome > Applications > Android`. Pure-Kotlin Android project, clean fit. |
| [Awesome Generative AI Apps](https://github.com/aimerou/awesome-ai-apps) | Different curator from Arindam200 — verify scope before submitting. |

## Tracking the PRs

```bash
# Check status of all open PRs across forks
for r in e2b-dev/awesome-ai-agents timschneeb/awesome-shizuku jetpack-compose/jetpack-compose-awesome androiddevnotes/awesome-jetpack-compose-android-apps adisonhuang/awesome-kotlin-android ; do
  echo "=== $r ==="
  gh pr list --repo "$r" --author ankurCES --json number,state,title,url \
    | python3 -c "import sys,json; [print(f'  #{p[\"number\"]} [{p[\"state\"]}] {p[\"title\"]}') for p in json.load(sys.stdin)]"
done
```

## PR template (for future submissions)

### Title

```
Add Mythara — open-source agentic-AI Android OS layer (MIT)
```

### Body

```markdown
## What this adds

A new entry under `<section>`:

`<copy of the formatted entry exactly as it appears in the list>`

## Why it fits

<one-sentence justification matching the list's curation criteria>

## Compliance

- [x] License: MIT
- [x] Actively maintained (commits within the last 30 days)
- [x] Working / installable (`./gradlew :app:assembleDebug`)
- [x] Has a README + wiki at https://github.com/ankurCES/project_mythara/wiki
- [x] Format matches existing entries (alphabetical placement, same Markdown shape)
```

## What to do after a PR is merged

- ⭐ Star the list's repo.
- 🧵 If a maintainer requests changes, address within 24 hours.
- 📅 Wait 2 weeks before pinging a stale PR.
- 📈 After the entry lands, ranking on Google for "open source agentic AI Android" / "Jetpack Compose AI app" should start picking it up within ~4 weeks.

## How to find more candidate lists

```bash
gh search repos "awesome <topic>" --limit 20 \
  --json fullName,stargazersCount,description \
  | python3 -c "import sys,json
for r in json.load(sys.stdin):
    if r['stargazersCount'] >= 100:
        print(f'  {r[\"fullName\"]:55} {r[\"stargazersCount\"]:>6}  {(r[\"description\"] or \"\")[:80]}')"
```

Good topic seeds: `agentic-ai`, `local-llm`, `private-llm`, `personal-assistant`, `android-foss`, `open-source-ai`, `mobile-ai`, `jetpack-compose`, `kotlin-android`.

When you find one, READ ITS README + CONTRIBUTING + sample existing entries before opening any PR. Matching the existing style is what gets PRs merged.
