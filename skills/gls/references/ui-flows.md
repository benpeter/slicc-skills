# GLS UI flows & gotchas

What the API can't do (login, money movement) and the operational quirks of
driving GLS online banking.

## Login — human-only

Sign-in is **username/Alias + PIN**, then a **SecureGo plus** push approval (or
chipTAN/photoTAN). The push approval happens on the account holder's phone and
**cannot be automated**. The agent's job is to *foreground a login tab and ask
the human to sign in*, then drive read APIs once the session is live.

Logout page: `…/portal/logout` shows "Sie wurden erfolgreich abgemeldet" with an
**Anmelden** button. If you land here, the session is gone — the human must log
in again.

## Session lifetime & keep-alive (the important one)

There are **two** timers, both ~5 minutes:

1. **Idle auto-logout** — the visible "Automatische Abmeldung in N Minuten"
   countdown. On expiry every tab bounces to `…/portal/logout` and a **full
   re-login** is required. Reset by **any UI activity** (a click, a keystroke).
   Clicking the logout-countdown itself is enough.
2. **OAuth access token (~5 min)** — what the `proxy-gateway` API checks. The SPA
   refreshes it on activity/navigation. **Bare `fetch` API calls do _not_ refresh
   it** — so a script that only hits the API will start getting **401** after a
   few minutes even though the page is still open.

Verified behaviour: after the token lapses, a single **navigation/reload
re-bootstraps the SPA and silently renews the token** (no re-login) — provided
the idle-logout hasn't *also* fired. Once idle-logout fires, only a human login
brings it back.

**Keep-alive for automation:** reload (or click) the GLS tab every ~3 minutes.
`gls keepalive` does a reload + a 200 check. Run it in a loop alongside long jobs:

```bash
while true; do gls keepalive --quiet; sleep 180; done
```

## Transfer (Überweisung) — the `gls transfer` flow (verified end-to-end)

Backed by the `zv-credit-transfer` API in [`endpoints.md`](endpoints.md). The
command fills the form and stops at the review; `--send` clicks the final button
and the **human approves in SecureGo plus**.

1. Nav → SEPA form (`…/ueberweisung/sepa/#/erfassen`). **The tab must be the
   foreground/active tab** or the Angular fields won't accept input.
2. **Auftraggeberkonto** (sender): defaults to the main giro; change via the
   account dropdown for `--from`.
3. **Zahlungsempfänger** — the recipient field has an autocomplete that
   **overlays the IBAN field**:
   - **External**: type the name, **press Escape to close the dropdown** (else the
     next click/fill lands on a suggestion and fills a *wrong* recipient+IBAN),
     then fill the IBAN.
   - **Internal (own account)**: type the account name and **select the
     "Umbuchungskonto … <name>" suggestion** — it auto-fills name + own IBAN.
   - Refs shift whenever the autocomplete opens/closes — re-snapshot before each fill.
4. **Betrag** (`120,00`), optional **Verwendungszweck**. **Echtzeit**: tick "Als
   Echtzeitüberweisung ausführen" — this **reflows the form** (the "Eingaben
   prüfen" ref moves), so re-read it after.
5. **Eingaben prüfen** → **verification-of-payee** runs (yes, even for external
   banks):
   - **match** → review shows "stimmt … überein"; continue.
   - **no-match** → a blocking modal **"Bitte prüfen"** (⦿ Empfängername ändern /
     ○ Ihre Eingabe beibehalten); choosing *keep* expands a **no-refund liability
     disclaimer** before **Weiter**. The override carries **no API flag** (same
     `vopId`). An automated command must **stop here** unless explicitly told to keep.
6. Confirm: the review's button is **"Senden"** or **"Bestätigen mit SecureGo plus"**
   → a **decoupled SecureGo plus push** to the phone. The page polls until you
   approve, then commits → **"Überweisung erfolgreich entgegengenommen"** + timestamp.
   Internal own-bank transfers also require SecureGo (no TAN-free path was observed).
   SEPA routes by **IBAN**, so the name only affects the VOP label, not where money lands.

**Safety contract for the command:** never auto-keep a VOP mismatch; never complete
without the human's SecureGo approval (`--send` is opt-in, and SecureGo is on the phone).

## Expected bookings vs. standing orders

- **Umsätze → "Erwartete Buchungen"** tab shows only *pre-noted* (vorgemerkt)
  bookings (card holds, imminent SEPA) — not future-dated standing orders.
- **Daueraufträge** (`…/auftraege/dauerauftraege`) lists every standing order
  with its **next execution date** and amount — the source for "what's due in the
  next N days". Direct debits (SEPA-Lastschriften) are **not** standing orders and
  appear nowhere here; project them from transaction history instead.

## Driving from a SLICC tray follower (cup mode)

When the GLS tab lives on a **tray follower** (e.g. the human's browser joined to
a cup leader) rather than a local tab, two quirks bite:

- **Foreground to load data.** The account/transaction widgets only fetch when
  the tab is the **foreground** tab. A background follower tab renders the
  chrome but no data. `playwright-cli tab-select <index>` to foreground it.
- **Foreground to keep control.** The leader can lose its debugger attachment to
  a follower tab; a **freshly opened** tab (`playwright-cli tab-new <url>
  --runtime=<follower>`) attaches cleanly, and foregrounding it keeps both the
  data load and the control link alive. Re-foreground/re-open if `eval`/`snapshot`
  start returning "Debugger is not attached".

On a normal local SLICC float (extension/CLI) the GLS tab is a regular local tab
and neither quirk applies.
