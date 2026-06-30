# GLS online-banking API (reverse-engineered)

Base host: `https://www.gls-online-filiale.de`
API root: `/services_cloud/portal/proxy-gateway/serviceproxy`

The portal is an Atruvia/Fiducia **services_cloud** SPA. The backend is a set of
namespaced micro-services behind a `proxy-gateway`. Every call below is made
**from the logged-in page context** (`fetch(url, { credentials: 'include' })`);
the session cookie carries auth — no `Authorization` header is needed. Calls made
without the browser's cookie/origin return **401**.

> Field **names** below are real; no account data is included. Capture live
> shapes yourself with `gls <cmd> --json`.

## Auth / session

| Endpoint | Notes |
| --- | --- |
| `/portal-oauth/oauth/token` | OAuth token mint/refresh. Short-lived (~5 min). |
| `/portal-oauth/oauth/check_token?token=GetTokenFromCookie` | Token validity probe; token lives in a cookie. |
| `…/zwp-session/rest/sessionIndicator/iid` | Session indicator. |

The token is refreshed by **page activity / navigation**, not by API calls. See
[`ui-flows.md`](ui-flows.md) → "Session lifetime & keep-alive".

## Accounts — VERIFIED ✓

```
GET …/konto-service/v2/konto/group
    ?kontoFilter={"includeVertragsloseKonten":true,"includeVertraege":true}
    &includeEmptyGroups=false&groupBy=INDIVIDUELL
```

Response: `{ groups: [ { name, konten: [ Konto, … ] } ], … }`

`Konto` fields used: `ident` (opaque account id used by other services),
`iban`, `displayName`, `saldo` / `saldoEuro`, `waehrungCodeIsoAlpha`, `art`
(e.g. `KONTOKORRENT`), `inhaberName`, `businessIdent`.

Lightweight variants seen: `…/konto-service/konto?...&groupBy=PERSON`,
`…/zv-konten-salden/.../saldo/<IBAN>`, `…/zv-konten-salden/.../kontodetails/<IBAN>`,
`…/zv-konten-salden/.../salden-with-paging`.

## Transactions (Umsätze) — VERIFIED ✓

```
GET …/umsaetze-umsatz-service/umsatz/advanced/v2
    ?umsatzFilter=<json>
    &umsatzPagingDescriptor=<json>
```

`umsatzFilter` (URL-encoded JSON):

```json
{
  "steuerrelevant": null,
  "searchInVirtuellenKontenOnly": null,
  "kontoIdent": "<account ident from konto/group>",
  "searchText": null,                       // free-text: counterparty name OR purpose
  "fromBetrag": null, "toBetrag": null,      // amount range
  "kategorie": null,
  "sortierung": "BUCHUNGSDATUM_DESC",
  "fromBuchungDatum": "2026-04-01+02:00",    // YYYY-MM-DD + TZ offset
  "toBuchungDatum":   "2026-06-30+02:00"
}
```

`umsatzPagingDescriptor`: `{ "pageNumber": 0, "pageSize": 50, "endlessScrolling": false }`

Response: `{ anzahlUmsaetzeSuche, betragSumme, saldoDiff, anfangsSaldo,
schlussSaldo, currentUserName, umsaetze: [ Umsatz, … ] }`

`Umsatz` fields used: `buchungZeit` (ISO), `valuta` (YYYYMMDD int),
`betrag` / `betragEuro`, `verwendungszweck`, `gegenparteiName`, `eigenerName`,
`kategorie`, `typ`, `wiederkehrend`, `kontoGegenpartieIban` (`kontoGegenparteiIban`),
`transactionId`.

> The `+02:00` offset is hard-coded to CEST. If a query at a DST boundary
> misbehaves, drop the offset or use the correct one for the date.

## Standing orders (Daueraufträge) — endpoint known, request contract TBD ⚠️

```
…/zv-dauerauftrag-v1/rest/de.fiduciagad.zv.dauerauftrag.view.v1.PeriodicPaymentViewApi/periodic_payment/paginated
```

Plain `GET` and the obvious `POST` paging/filter bodies all return **424**
("Bitte wenden Sie sich an Ihre Bank") — the SPA sends a specific
header/body/context this capture didn't pin down. **To finish it:** patch
`XMLHttpRequest`/`fetch` *before* the daueraufträge view loads (e.g. via a CDP
init script) and record the exact method, body, and headers of the real
`periodic_payment/paginated` call, then mirror it. Until then, read standing
orders from the rendered page (route below) or the UI.

Rendered list route (DOM fallback):
`/services_cloud/portal/webcomp/auftraege/dauerauftraege` — each row reads
`Dauerauftrag • Nr. N • <freq> <Empfänger> <±Betrag> EUR <zweck> <IBAN> Nächste: DD.MM.YYYY`.

## Other observed services

`structure-service` (navigation), `portal-frontpage-v1` (profile/contextDetails),
`aufgabenbox-service` (tasks), `epostfach-core-v1` (mailbox),
`ku-backend-v1` (card overview), `zv-bank-config`, `budgetcheck-analysis-v1`,
`umsaetze-additional-info-service-v1`.

## Page routes (SPA)

| View | Route |
| --- | --- |
| Start / accounts | `/services_cloud/portal/m/banking_start` |
| Transactions | `/services_cloud/portal/webcomp/banking/umsaetze` (needs an account selected) |
| Standing orders | `/services_cloud/portal/webcomp/auftraege/dauerauftraege` |
| Transfer (SEPA) | `/services_cloud/portal/webcomp/ueberweisung/sepa/#/erfassen` |
| Logout | `/services_cloud/portal/logout` |
