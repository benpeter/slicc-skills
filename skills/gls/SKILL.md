---
name: gls
description: >-
  Read GLS Bank online banking and prepare transfers from the CLI — accounts,
  balances, transactions (name/purpose search + date ranges), and a guarded SEPA
  transfer. Use when the user asks about their GLS account, GLS Bank,
  gls-online-filiale, their Kontostand / balance, Umsätze / transactions, "how
  much did I get from X", "when did I last pay Y", or wants to send / prepare a
  GLS transfer or Überweisung. Talks to the GLS "services_cloud" portal's own
  JSON API from the logged-in page context. Reads are read-only; transfers stop
  at the review screen and only move money on an explicit human SecureGo approval.
allowed-tools: bash
---

# GLS Bank

Direct, read-only access to GLS online banking (the Atruvia **services_cloud**
portal at `gls-online-filiale.de`). The `gls` command calls the portal's own
`proxy-gateway` JSON API **from the logged-in GLS page context**, so the session
cookie and OAuth token are carried automatically — no tokens to manage, no UI
scraping for the common reads.

## Prerequisites

1. **A logged-in GLS tab.** Open `https://www.gls-online-filiale.de` in the
   browser and sign in. Logging in (username/PIN + **SecureGo plus** / TAN) is
   **human-only** — it cannot be automated. See
   [`references/ui-flows.md`](references/ui-flows.md).
2. **Keep the session warm.** GLS logs you out after **~5 minutes of
   inactivity**, and the OAuth token it uses for API calls is just as
   short-lived and is *only* refreshed by page activity — **bare API calls do
   not refresh it.** For anything longer than a couple of minutes, run a
   keep-alive loop:

   ```bash
   while true; do gls keepalive --quiet; sleep 180; done
   ```

   (Or, by hand, just click anything in the GLS tab every few minutes — clicking
   the logout-countdown is enough.)

## Commands

```bash
gls accounts [--json]
# List every account with IBAN and balance.

gls balance [account] [--json]
# Balance of one account (matched by name substring or IBAN; default: main giro).

gls transactions [account] [--search=TEXT] [--from=YYYY-MM-DD] [--to=YYYY-MM-DD] [--limit=N] [--json]
# List/search bookings for an account. Default range: last 90 days.

gls search <text> [account] [--days=N] [--json]
# Find bookings by counterparty name or purpose. Default range: last 365 days.

gls keepalive [--quiet]
# Refresh the session (reload → renews token + resets the idle timer).

gls transfer (--to-iban=IBAN --name="Recipient" | --to-account=Name) --amount=NN,NN \
             [--purpose=TEXT] [--instant] [--from=Account] [--send] [--confirm-mismatch]
# Prepare + validate a SEPA transfer in the GLS form and STOP at the review
# screen (shows verification-of-payee). Money only moves with --send AND your
# SecureGo plus approval on your phone. See "Transfers" below.
```

### Examples

```bash
gls accounts                                  # overview of all accounts
gls balance Eingang                           # balance of the "Eingang" account
gls search "Kaan Uyan"                         # when did money from Kaan Uyan last arrive?
gls transactions Eingang --from=2026-06-01 --to=2026-06-30
gls transactions --search=Telekom --days=90 --json | jq '.[].betragEuro'
```

## Transfers (guarded)

`gls transfer` drives the SEPA form, fills it, runs **Eingaben prüfen**, and stops
at the review screen showing GLS's **verification-of-payee** result. It never moves
money on its own.

```bash
# External — name is checked against the IBAN's registered holder (VOP):
gls transfer --to-iban=DE55… --name="Max Mustermann" --amount=120,00 --purpose="Rechnung 5"

# Own-account (internal) — picks the "Umbuchungskonto" suggestion automatically:
gls transfer --to-account="Rücklagen" --amount=200,00

# Instant (Direktüberweisung / Echtzeit):  add --instant
# Submit it too (then approve on your phone):  add --send
```

- **VOP name mismatch** → the command **stops** (exit 2) and tells you, rather than
  silently keeping a non-matching name. Re-run with `--confirm-mismatch` to accept
  GLS's no-refund liability and keep the name.
- **`--send`** clicks *Senden* / *Bestätigen mit SecureGo plus*; the transfer then
  **only completes when you approve it in SecureGo plus on your phone**. Without
  `--send` the command just prepares + validates and leaves the review on screen.
- All amounts use German format (`120,00`). Default sender is the main giro; use
  `--from=<account>` to send from another. The full flow + the API pipeline are in
  [`references/ui-flows.md`](references/ui-flows.md) and
  [`references/endpoints.md`](references/endpoints.md).

## How it works

- The script uses SLICC's modern `.jsh` runtime: `require('sliccy:browser')` for
  page-context fetch/eval against the logged-in GLS tab (cookies + origin carried,
  no extra auth) and `require('sliccy:exec')` to drive the browser CLI for tab
  discovery and the transfer form. (These replaced the old bare `exec`/`fs`
  globals on 2026-06-24 — see `skill-authoring/jsh-runtime-extensions.md`.)
- The discovered API surface (accounts, transactions, balances, the
  `zv-credit-transfer` pipeline, session/OAuth) is mapped in
  [`references/endpoints.md`](references/endpoints.md).

## Session discovery (how it finds a live tab)

Every command resolves a **live, drivable** GLS tab before doing anything:

1. Probe each open GLS tab with a real auth call — a tab is only used if it returns
   **HTTP 200** (stale / logged-out / detached tabs are skipped, not trusted).
2. If none is live+drivable, **open a fresh banking tab in the foreground** (on the
   same follower runtime, if any). A tab the skill opens itself inherits the
   authenticated session, and foregrounded, a tray follower attaches to it — this is
   the verified fix for "I have a logged-in tab but the command 401s / won't drive."
3. If even the fresh tab is logged out, the session is genuinely expired → it tells
   you to log in (rather than failing on a confusing 401).

So you don't have to babysit tabs: with a live GLS login anywhere in the
browser/follower, the commands find or open a tab that works.

## Notes & safety

- **401 / logged out** → run `gls keepalive`; if that reports "logged out", sign in
  again in the browser. Sign-in + SecureGo are always human-only.
- **SLICC tray follower (cup mode):** the skill auto-opens a fresh foreground tab as
  above, so it self-heals the follower's "can't drive a pre-existing tab" quirk. It's
  most reliable in a **normal** SLICC float (local tab). Background on the follower
  attach behavior is in [`references/ui-flows.md`](references/ui-flows.md).
