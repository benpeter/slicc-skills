---
name: alm
description: >-
  Interact with Adobe Learning Manager (ALM, formerly Adobe Captivate Prime) —
  the corporate LMS at learningmanager.adobe.com / cpcontents.adobe.com. Use this
  to run and manage trainings: list your enrollments and progress, search the
  learning catalog, view course/certification/learning-program details, enroll or
  unenroll, check skills and earned badges, and get the browser player URL to
  actually take a training. Activate on mentions of Adobe Learning Manager,
  Captivate Prime, ALM, cpcontents, "my trainings", "my courses", enrollments,
  certifications, learning paths, compliance training, course completion/progress,
  learning catalog, or a cpcontents.adobe.com / learningmanager.adobe.com URL.
allowed-tools: bash
---

# Adobe Learning Manager (alm)

Direct client for the **Adobe Learning Manager Prime Learner API**
(`https://learningmanager.adobe.com/primeapi/v2`). The `alm` command (shipped as
`scripts/alm.jsh`) wraps the learner-facing endpoints so you can manage trainings
from the shell without clicking through the web player.

## Auth model (important)

- The API authenticates with `Authorization: oauth <accessToken>`.
- The `accessToken` is the **`nativeExtensionToken`** (`natext_…`) that the ALM
  "newlearner" player mints once the user is logged into `learningmanager.adobe.com`.
  It is **session-scoped** and expires.
- The API does **not** validate `Origin`, so calls work via plain `fetch()` — no
  browser is needed for API calls once a token is stored.
- The `alm.jsh` runtime here only has `fetch` + `fs`, so it **cannot scrape the
  browser itself**. Capturing/refreshing the token is done by the agent (see below)
  and handed to `alm login <token>`.
- Config (token, accountId, userId) is stored at `skills/alm/.config.json`.

### Capturing / refreshing the token (agent procedure)

The user must have an ALM training open and be logged in (the player lives on
`cpcontents.adobe.com`, backed by the `learningmanager.adobe.com` session). If the
SLICC browser is not logged in, use **teleport** (see `skills/handoff`) or the user's
connected follower browser to complete the Adobe Okta SSO first.

Then scrape the token from the player's child dashboard iframe and store it:

```bash
# 1. Find the open newlearner tab (any runtime). Example uses playwright-cli tab-list.
tab=$(playwright-cli tab-list | grep -i 'newlearner' | grep -oE '\[[^]]+\]' | head -1 | tr -d '[]')

# 2. Read the nativeExtensionToken (and accountId/userId) from the iframe URL.
info=$(playwright-cli eval --tab="$tab" "
  (function(){
    for (const f of document.querySelectorAll('iframe')) {
      try { const u=new URL(f.src); const t=u.searchParams.get('nativeExtensionToken');
        if(t) return JSON.stringify({token:t, user:u.searchParams.get('userId')||''}); } catch(e){}
    }
    const m=/[?&]accountId=(\d+)/.exec(location.href); return JSON.stringify({account:(m&&m[1])||''});
  })()
")
# 3. Extract the token and store it.
token=$(echo "$info" | grep -oE 'natext_[a-f0-9]+')
alm login "$token" --account=29997
```

If a command reports **"Token expired or invalid (persistent auth failure)"**, repeat
this capture. (The client already retries the frequent *transient* 401s that ALM's
edge returns under load — only a persistent failure means the token is truly dead.)

### One-command refresh

The above is wrapped in a helper the agent runs when the token dies:

```bash
bash /workspace/skills/alm/scripts/alm-refresh.sh
```

It finds an open newlearner tab (any runtime), scrapes the `nativeExtensionToken`
(plus accountId/userId), and calls `alm login`. Requirements: an ALM training/dashboard
open in a logged-in browser **with the CDP debugger attached** (i.e. a live SLICC tab).
If the tab shows "Debugger is not attached", reload/foreground it (or reopen the player)
and retry. If the SLICC browser isn't logged in at all, complete Adobe Okta SSO via
teleport or a follower browser first.

## Commands

```
alm login <token> [--account=ID] [--user=ID]   Save an access token
alm token                          Show stored token + context (masked)
alm whoami [--json]                Show the signed-in learner
alm enrollments [--status=STATE]   List your trainings + progress (ENROLLED/STARTED/COMPLETED)
alm progress                       Compact completion summary with progress bars
alm mandatory                      How to list mandatory trainings (points to the BL helper)
alm catalog [--query=Q] [--type=T] [--limit=N]   Search the catalog
alm show <loId> [--json]           Training details (+ instances, description, duration)
alm enroll <loId> [--instance=ID]  Enroll yourself in a training
alm unenroll <loId>                Remove your enrollment
alm skills [--limit=N]             List catalog skills
alm badges [--json]                Your earned badges
alm player <loId>                  Print the browser player URL to actually run a training
alm raw <path> [--json]            GET any primeapi/v2 path (power use)
```

### Learning-object IDs

`loId` is either a fully-typed id like `certification:88031` / `course:848553`, or a
bare number plus `--type=course|certification|learningProgram|jobAid` (default
`course`). Types map to the ALM "Learning Object" model.

### Mandatory trainings (IMPORTANT — use the BL helper)

The authoritative "mandatory" signal is the **BL enrollment flag `mandatory: true`**
(`api/bl/v1/.../enrollment`). The public `primeapi/v2` used by `alm.jsh` does **not**
expose it — its `/enrollments` lacks the flag *and* omits compliance certifications —
so do **not** infer mandatory from `enrollmentSource` (an earlier wrong heuristic).

Because that flag lives only in the cookie-authenticated BL API, list mandatory
trainings via the agent helper (runs through your live logged-in browser tab):

```bash
bash /workspace/skills/alm/scripts/alm-mandatory.sh          # outstanding (not completed)
bash /workspace/skills/alm/scripts/alm-mandatory.sh --all    # include completed
bash /workspace/skills/alm/scripts/alm-mandatory.sh --json   # raw
```

It mirrors the Adobe **Inside** required-training view:
- filter `mandatory === true`;
- keep the **current cycle** (compliance certs recur yearly → many historical records;
  take the latest deadline per training family);
- **group the "Secure Developer" green-belt** role variants (C++/Java/Cloud/Data
  Scientist/…) into a single *Security Green Belt – Secure Developer* line — they all
  carry the `SGB` / `Security Green Belt Training` tag;
- default to **not-completed** (Inside's list is incomplete-only, and is a stale
  snapshot — this helper is live, so items you finished today drop off immediately);
- sort by `deadline`, flag **OVERDUE**.

`deadline` is the due date (ALM uses year ≥ 2037 as a "no deadline" sentinel → shown as
none). The plain `alm mandatory` command just prints these instructions.

### Examples

```bash
bash scripts/alm-mandatory.sh        # outstanding required trainings by due date (BL flag)
alm enrollments --status=STARTED     # trainings you've begun but not finished
alm progress                         # visual completion overview
alm catalog --query="edge delivery" --type=course
alm show certification:88031         # e.g. "Adobe Security Awareness"
alm enroll course:7871204            # enroll, then:
alm player course:7871204            # get the URL to run it in the browser
alm raw /catalogs                    # explore the API directly
```

## Running vs. tracking a training

The API handles **enrollment and progress tracking**, but the actual course content
(video/SCORM/quiz modules) plays in the browser widget. Use `alm player <loId>` to get
the player URL, then open it in the user's authenticated browser (or via
`playwright-cli open <url> --foreground`) to take the training. `alm enrollments` /
`alm progress` then reflect completion.

## References

- `references/endpoints.md` — discovered endpoints, auth, ID schemes, and the two API
  surfaces (public `primeapi/v2` and the internal `api/bl/v1`).
