# WhatsApp Web selector map

Verified live against web.whatsapp.com (logged-in session, German locale, 2026).
WhatsApp obfuscates class names and rotates them across releases, so prefer the
structural attributes below (`role`, `data-tab`, `data-pre-plain-text`) over
generated classes.

## Runtime / driving model

- WhatsApp Web has **no HTTP API** — drive the DOM via `playwright-cli`.
- Reads/inspection: `playwright-cli eval-file` (page-context JS).
- Navigation (open a chat) and text entry (send) **require trusted input** —
  synthetic DOM events do not work. Use `playwright-cli click <ref>`,
  `playwright-cli type`, and `playwright-cli press`.
- On a **follower**, the target id is `follower-<uuid>:<tabId>`; every call needs
  `--tab=<full id>` and `--runtime=<uuid>`. The local `tab-list` aggregates
  follower tabs, so the runtime can be derived from the target id prefix.
- CDP debugger must be attached to the tab (SLICC attaches to tabs it drives).
  Re-attach with `playwright-cli open "https://web.whatsapp.com/" --runtime=<id> --fg`.

## Auth / app shell

| Purpose | Selector |
|---|---|
| Logged-in indicator | `#pane-side` (present only after login) |
| Left column (chat list + search) | `#side` |
| Open conversation panel | `#main` |

## Chat list (left pane)

| Purpose | Selector / notes |
|---|---|
| Chat rows | `#pane-side [role="row"]` (virtualized; only visible rows are in the DOM) |
| Chat/group name | `span[title]` within a row → `getAttribute('title')` |
| Last-message preview | **last non-empty line of the row's `innerText`** (the last `[role="gridcell"]` is often empty; the useful text is in the row text) |
| Unread marker | a descendant `span[aria-label]` whose label matches `unread` / `ungelesen` |

Note: `[role="listitem"]` does **not** match; rows live in a `[role="grid"]`.

## Opening a chat (trusted click)

1. `playwright-cli snapshot` — rows appear as `- row "<title> <date> <preview…>" [ref=eNN]`.
2. Match the requested name against the quoted title (exact → prefix → substring).
3. `playwright-cli click <ref>` — a **trusted** click; `.click()`/synthetic
   `MouseEvent`/`PointerEvent` do NOT trigger WhatsApp's React navigation.
4. Wait ~1.5s for `#main` to populate.

## Reading messages (open conversation)

| Purpose | Selector / notes |
|---|---|
| Message rows | `#main [role="row"]` |
| Sender + timestamp | `.copyable-text[data-pre-plain-text]` → attribute value `"[HH:MM, D.M.YYYY] Sender: "` |
| Message text | `span.selectable-text` → `innerText` |
| Direction (in/out) | **Not reliably available** in this build — `.message-in`/`.message-out` classes are absent and `data-id` carries no `true_/false_` prefix. Use the sender name from the meta instead of a direction arrow. |
| Header contact name | `#main header span[title]` can read as a tooltip ("Klicke hier für Kontaktinfo"); prefer the name the caller passed. |

## Sending (trusted keystrokes)

| Purpose | Selector / notes |
|---|---|
| Compose box | `#main div[contenteditable="true"][data-tab="10"]` (fallback `#main footer div[contenteditable="true"]`); `aria-label` = "Nachricht an X schreiben" / "Type a message" |
| Focus | `box.focus()` via eval (fine for focus), OR click its snapshot ref |
| Enter text | `playwright-cli type "<text>"` (trusted). `execCommand('insertText')` is unreliable on the Lexical editor. |
| Newlines | `playwright-cli press "Shift+Enter"` between lines |
| Send | `playwright-cli press "Enter"` (a synthetic Enter/`data-icon="send"` click does not send) |

Caution: when calling `playwright-cli type`, pass the runtime as the `--runtime=<id>`
flag — never as a bare positional, or the id text gets typed into the box.

## Search (intentionally not implemented)

The search box is `#side [role="textbox"][data-tab="3"]` (label "Suchen oder neuen
Chat beginnen"; its `contenteditable` attribute is not literally `"true"`). Trusted
typing into it behaved inconsistently (often "keine gefunden" for names that exist),
the results view restructures `#pane-side`, and a failed run can leave stale text in
the box (clear via `Escape` or a tab reload). Given `open_chat`'s substring matching
over the visible list, search was dropped to keep the skill reliable.
