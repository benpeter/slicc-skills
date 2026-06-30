---
name: gls
description: >-
  Read GLS Bank online-banking data from the CLI — accounts, balances, and
  transactions (with name/purpose search and date ranges). Use when the user
  asks about their GLS account, GLS Bank, gls-online-filiale, their Kontostand /
  balance, Umsätze / transactions, "how much did I get from X", "when did I last
  pay Y", standing orders / Daueraufträge, or wants to script anything against
  GLS online banking. Talks to the GLS "services_cloud" portal's own JSON API
  from the logged-in page context. Read-only — it never moves money.
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
```

### Examples

```bash
gls accounts                                  # overview of all accounts
gls balance Eingang                           # balance of the "Eingang" account
gls search "Kaan Uyan"                         # when did money from Kaan Uyan last arrive?
gls transactions Eingang --from=2026-06-01 --to=2026-06-30
gls transactions --search=Telekom --days=90 --json | jq '.[].betragEuro'
```

## How it works

- Reads go through the page-context fetch helper in
  [`scripts/gls.jsh`](scripts/gls.jsh): the script finds the GLS tab via
  `playwright-cli tab-list`, writes a tiny `fetch(..., {credentials:'include'})`
  expression to `/shared/`, runs it with `playwright-cli eval-file --tab=<id>`,
  and parses the JSON back. The browser carries cookies + origin, so the
  `proxy-gateway` accepts the call with no extra auth.
- The discovered API surface (accounts, transactions, balances, standing orders,
  session/OAuth) is mapped in [`references/endpoints.md`](references/endpoints.md).

## Limits & safety

- **Read-only by design.** No transfer/standing-order/payment command ships here.
  Moving money on GLS requires a **SecureGo plus** confirmation that only the
  account holder can give; the full Überweisung UI flow is documented (for the
  human, or a future *guarded* command) in
  [`references/ui-flows.md`](references/ui-flows.md).
- **401 / logged out** → run `gls keepalive`; if that reports "logged out", sign
  in again in the browser.
- Driving GLS from a **SLICC tray follower** (e.g. cup mode) has extra quirks —
  the GLS tab must be foregrounded for its data to load and for the page to stay
  drivable. See the "Driving from a SLICC follower" note in
  [`references/ui-flows.md`](references/ui-flows.md).
