# slicc-skills

Custom [SLICC](https://www.sliccy.ai) skills — site-specific automations built
with the **secret-sauce** pattern (discover a web app's own API, then compile it
into a reusable `*.jsh` CLI skill that SLICC's agent can call).

## Skills

| Skill | What it does |
| --- | --- |
| [`gls`](skills/gls/) | Read-only GLS Bank online-banking access — accounts, balances, transactions (with search + date ranges). Calls the GLS portal's own JSON API from the logged-in page context. Never moves money. |

## Using a skill in SLICC

SLICC discovers compatibility skills from `.claude/skills/*/SKILL.md` and
`.agents/skills/*/SKILL.md` anywhere in the reachable VFS, and the `*.jsh` script
becomes a shell command (filename without `.jsh`). To install one:

1. Copy the skill folder into a discovered skills root in your SLICC VFS, e.g.
   `/workspace/skills/gls/` (or a `.claude/skills/gls/` in a mounted repo).
2. Open and **log in** to the target site in the browser (sign-in / 2FA is always
   human-only).
3. Ask SLICC to use it (e.g. *"what's my GLS balance?"*) or call the command
   directly in the terminal (`gls accounts`).

Each skill's own `SKILL.md` has the command list, prerequisites, and gotchas;
`references/` documents the reverse-engineered API and UI flows.

## Safety

These skills act **as you**, in your authenticated browser session, against your
own accounts. They are **read-only** unless a skill explicitly says otherwise, and
anything that moves money or makes irreversible changes is left to a human
confirmation step (e.g. a banking TAN). No credentials, tokens, or account data
are stored in this repo — skills read live data at call time.
