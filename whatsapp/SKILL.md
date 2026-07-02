---
name: whatsapp
description: Interact with WhatsApp Web (web.whatsapp.com) via its page DOM — list chats, read conversations, check unread messages, and send messages. Use when the user wants to automate WhatsApp, read or send a WhatsApp message, check WhatsApp chats or unread messages, message a contact or group on WhatsApp, or do any WhatsApp task without clicking through the UI. Activate on mentions of WhatsApp, WA, "message my ...", "text on WhatsApp", "send a WhatsApp", "check my WhatsApp", "unread WhatsApp messages", or related messaging workflows.
allowed-tools: bash
---

# WhatsApp

Drive WhatsApp Web (web.whatsapp.com) from the command line. WhatsApp Web has **no public HTTP/REST API** — the backend is an end-to-end-encrypted WebSocket (Signal protocol), and the internal webpack Store is obfuscated and version-fragile. So this skill drives the **page DOM** through `playwright-cli`, reusing the existing logged-in browser session (a local tab or a paired tray follower).

## How to run it

The tool is a **shell script**, not a `.jsh` command. The SLICC `.jsh` runtime cannot shell out to `playwright-cli`, but the bash shell can. Run it through the bash tool:

```bash
bash /workspace/skills/whatsapp/scripts/whatsapp.sh <command> [args]
```

## Requirements

- A `web.whatsapp.com` tab open **and logged in** (QR scanned), locally or on a connected follower.
- The tab must have an **attached CDP debugger**. SLICC attaches the debugger to tabs it drives; a tab the user merely opened may not be attached, and the debugger detaches when a follower switches its foreground tab. If you hit `Debugger is not attached`, (re)attach by opening the tab via playwright once:
  ```bash
  playwright-cli open "https://web.whatsapp.com/" --runtime=<follower-id> --fg
  ```
  Then close any duplicate stale WhatsApp tab so `find_tab` picks the attached one.

## Commands

```bash
bash .../whatsapp.sh status                    # Tab + login status
bash .../whatsapp.sh chats [N]                 # List N recent chats (default 20); * = unread
bash .../whatsapp.sh unread                    # Only chats with unread messages
bash .../whatsapp.sh read <chat> [N]           # Open <chat>, print last N messages (default 15)
bash .../whatsapp.sh send <chat> "<message>"   # Send a message to <chat>
```

`<chat>` is matched against contact/group names: exact → prefix → substring (case-insensitive). Quote names/messages with spaces:

```bash
bash .../whatsapp.sh read "Nives Morgenstern" 20
bash .../whatsapp.sh send "Family Group" "On my way, 10 min"
```

Read output prints each message as its WhatsApp meta line + text, e.g.
`[13:06, 27.6.2026] Ben:Gehst du gerade …` — the meta already names the sender, so no separate direction arrow is needed.

## How it works (important design constraints)

- **Tab discovery:** the local `playwright-cli tab-list` aggregates follower tabs, and the target id carries the runtime (`follower-<uuid>:<tabId>`). `find_tab` derives the `--runtime` flag from that id.
- **Listing/reading** use `playwright-cli eval-file` (page-context JS): login = `#pane-side`; chat rows = `#pane-side [role="row"]`, name from `span[title]`, preview from the row's last innerText line; messages = `#main [role="row"]`, meta from `.copyable-text[data-pre-plain-text]`, text from `span.selectable-text`.
- **Opening a chat requires a TRUSTED click** — synthetic `.click()`/`MouseEvent` do not trigger WhatsApp's React navigation. `open_chat` snapshots the page, resolves the chat name to a `[ref=eNN]`, and clicks it with `playwright-cli click`.
- **Sending requires TRUSTED keystrokes** — the compose box is focused (`div[contenteditable][data-tab="10"]`), then `playwright-cli type` enters the text and `playwright-cli press Enter` sends. Multi-line messages use `Shift+Enter` between lines.

See `references/selectors.md` for the full selector map and rationale.

## Not included

- **Search** was intentionally omitted: WhatsApp's Lexical search input behaves inconsistently under automation (trusted input sometimes returns "none found") and its results view restructures the DOM, and a failed run can leave stale text in the search box. Use `chats`/`unread` to browse, and `read`/`send` with a name — `open_chat`'s substring matching resolves any chat currently in the list.

## Troubleshooting

- **"Not logged into WhatsApp Web"** — scan the QR at https://web.whatsapp.com/.
- **"No WhatsApp tab found"** — open web.whatsapp.com in the browser or a paired follower.
- **"Debugger is not attached"** — see Requirements above; re-open the tab via `playwright-cli open --fg` to (re)attach, and close stale duplicate WhatsApp tabs. The script already retries a few times on transient detach.
- **"Chat not found"** — the name isn't in the current list; open/scroll the chat into view or use a more specific name.
- WhatsApp's DOM is localized and periodically re-obfuscated; selectors favor stable structural attributes (`role`, `data-tab`, `data-pre-plain-text`) over class names. If something breaks, re-verify against `references/selectors.md`.
