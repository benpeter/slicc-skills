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

## Transfer (Überweisung) — documented, not shipped as a command

Moving money needs a SecureGo plus confirmation, so it is **not** exposed as an
automated command. The verified end-to-end UI flow, for the human or a future
*guarded* command:

1. Nav → **Überweisung** → SEPA form (`…/ueberweisung/sepa/#/erfassen`).
2. **Auftraggeberkonto** (sender): the account selector defaults to the main
   giro; change it if transferring from another account.
3. **Zahlungsempfänger**: typing a name offers an autocomplete that includes your
   own accounts as **"Umbuchungskonto …"** — picking one auto-fills the recipient
   name **and** IBAN (the safe path for own-account transfers). Otherwise enter
   name + IBAN.
4. **Betrag** (e.g. `1000,00`), optional **Verwendungszweck**.
5. **Eingaben prüfen** → review screen. GLS runs an IBAN/name check and states
   whether the recipient name matches the IBAN (verification-of-payee).
6. **Senden** → triggers the **SecureGo plus** approval. On success the
   confirmation reads **"Überweisung erfolgreich entgegengenommen"** with a
   timestamp; an own-bank transfer books within moments.

A future automated transfer command must stop at step 5 (fill + review) and hand
the **Senden**/SecureGo step to the human — never auto-confirm a payment.

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
