# Adobe Learning Manager — discovered API surface

Discovered by inspecting the ALM "newlearner" player
(`cpcontents.adobe.com/public/newlearner/...`) for account **29997**
(`adobeinternal` subdomain) while logged in as a Learner.

## Two API surfaces

### 1. Public Prime API (used by this skill) — RECOMMENDED

- **Base:** `https://learningmanager.adobe.com/primeapi/v2`
- **Format:** JSON:API (`Accept: application/vnd.api+json`)
- **Auth:** `Authorization: oauth <nativeExtensionToken>` (also accepts `Bearer`).
- **Origin:** not validated → works from plain `fetch()` (no cookies, no browser).
- **Docs:** Adobe publishes this API (search "Adobe Learning Manager API primeapi v2").

### 2. Internal BL API (widget backend) — not used here

- **Base:** `https://learningmanager.adobe.com/api/bl/v1/account/{accountId}/...`
- **Auth:** `learningmanager.adobe.com` **session cookies** (SameSite=None, sent
  cross-origin from the cpcontents player) + a `csrf_token` query param.
- Reachable only from a page context that carries the session cookies
  (`playwright-cli eval` on the logged-in tab), not from plain `fetch()`.
- Examples seen: `/nomenclature`, `/accountSetting`, `/theme`, `/user/{id}`,
  `/user/{id}/menu`, `/user/{id}/userNotification`, `/certificationName//{id}`,
  `/courseName//{id}`. Prefer the public API above; keep this as a fallback.

## Token acquisition

The `nativeExtensionToken` (`natext_…`) is embedded in the child dashboard iframe URL
of the newlearner player once authenticated:

```
https://cpcontents.adobe.com/public/newLearnerDashboardReactApp/index_*.html
  ?csrfToken=<csrf>&userId=<uid>&displayType=DESKTOP_HOME_PG
  &nativeExtensionToken=natext_<hex>&hostName=learningmanager.adobe.com
```

Read `iframe.src` from the player's main frame (or scan
`performance.getEntriesByType('resource')`) to extract it. Token is session-scoped;
re-capture when it dies. Login to `learningmanager.adobe.com` goes through Adobe Okta
SSO (`adobe.okta.com`), which needs interactive MFA — use teleport / a follower browser.

## Transient 401s

Under load the edge returns intermittent `401 Authorization Required` (an nginx HTML
page, not JSON) for a valid token. Retry with backoff; a *persistent* 401/403 means the
token is actually expired. The client retries `[429, 503, 401, 403]` up to 6× with
linear backoff.

## Verified endpoints (public primeapi/v2)

| Method | Path | Notes |
|--------|------|-------|
| GET | `/user` | Current learner (id, name, email, roles, points, state). |
| GET | `/users/{id}` | A specific user. |
| GET | `/users/{id}/userBadges` | Earned badges. |
| GET | `/enrollments?include=learningObject&page.limit=50` | Your enrollments; include LO for titles. `state` ∈ ENROLLED/STARTED/COMPLETED, `progressPercent`. |
| POST | `/enrollments?loId=<id>[&loInstanceId=<id>]` | Enroll self. |
| DELETE | `/enrollments/{loId}_{userId}` | Unenroll. |
| GET | `/learningObjects?page.limit=N[&filter.loTypes=course]` | Catalog listing. |
| GET | `/learningObjects/{loId}?include=enrollment,instances,subLOs,skills` | LO detail. |
| GET | `/search?query=Q&page.limit=N[&filter.loTypes=...]` | Free-text catalog search. |
| GET | `/catalogs` | Catalogs visible to the user. |
| GET | `/skills?page.limit=N` | Skills taxonomy. |

## Mandatory trainings — authoritative signal (BL API)

The **public `primeapi/v2` cannot tell you what is mandatory.** Its
`/enrollments` `data[].attributes` are only:
`completionDeadline`, `dateCompleted`, `dateEnrolled`, `dateStarted`,
`enrollmentSource`, `hasPassed`, `progressPercent`, `score`, `state` — **no mandatory
flag**, and it omits compliance certifications. `enrollmentSource` (AUTO/ADMIN/SELF) is
**NOT** a reliable mandatory proxy (verified wrong against the user's Adobe "Inside" list).

The truth is in the **BL enrollment record**
(`GET /api/bl/v1/account/{acct}/user/{uid}/enrollment?csrf_token=…`, cookie-authed,
page-context only):

```jsonc
{
  "courseId": 14044603,
  "mandatory": true,                       // ← authoritative
  "deadline": "2026-07-31T07:03:16.000Z",  // ← due date
  "enrollmentSource": "CERT_ENROLL",
  "pass": true, "progressPercent": 0, "enrollmentState": "ACTIVE",
  "assignedByUserId": 10220955, "aetId": 39451
}
```

Response is normalized arrays: `enrollment[]`, `course[]` (id→name via `name`/`heading`,
plus `createdForCertification`), `courseTag[]` (`{courseId, name}`), `meta` (paging;
`i_qp_limit`, `offset`, ~20/page). Auth: `learningmanager.adobe.com` session cookies
(SameSite=None) + `csrf_token` (read from the newlearner child-iframe URL / network
entries). `primeapi` `oauth` tokens are short-lived and unrelated to this surface.

### How "mandatory" maps to the Inside view (implemented in `scripts/alm-mandatory.sh`)

- filter `mandatory === true`;
- **current cycle**: compliance certs recur yearly (38 historical records back to 2022
  for this user) — keep the latest `deadline` per training family;
- **group Secure Developer green-belt** role variants (C++/Java/Cloud/Data Scientist/QA/
  Software Architect/…) into one *Security Green Belt – Secure Developer* line; they all
  carry the tag `SGB` / `Security Green Belt Training` in `courseTag[]`;
- Inside shows **not-completed only** and is a **stale snapshot**; the helper is live;
- sort by `deadline`; OVERDUE = deadline past and not passed.

Player link per item: `newlearner …#/overviewPage?loId=<courseId>&loType=course`.

## ID schemes

- **Learning Object id:** `<loType>:<number>` — `course:848553`,
  `certification:88031`, `learningProgram:<n>`, `jobAid:<n>`.
- **Instance id:** `<loId>_<n>` — e.g. `certification:88031_87503` (often a
  `DEFAULT_INSTANCE_STR`).
- **Enrollment id:** `<loId>_<userId>`.

## Context for account 29997 (Adobe internal)

- accountId `29997`, subdomain `adobeinternal`, name "Adobe".
- Player build shell: `newlearner_46b41063.html` (hash changes across releases; the
  `alm player` command hardcodes the last-seen shell — update if Adobe ships a new one).
- Nomenclature: Module, Course, Learning Path, Certification, Learning Plan, Job Aid,
  Catalog, Skill, Badge (standard ALM entities).
