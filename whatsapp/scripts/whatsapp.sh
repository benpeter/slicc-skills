#!/usr/bin/env bash
# whatsapp.sh — WhatsApp Web client, driven via playwright-cli.
#
# WhatsApp Web has NO public HTTP API (end-to-end-encrypted WebSocket / Signal
# protocol), so this drives the web.whatsapp.com page DOM through playwright-cli.
# It reuses the existing logged-in browser session (a local tab OR a paired tray
# follower). Run it through the SLICC bash shell (which provides playwright-cli,
# host and node); the .jsh runtime cannot shell out, so this is a .sh, invoked as:
#
#     bash /workspace/skills/whatsapp/scripts/whatsapp.sh <command> [args]
#
# Commands:
#   status                    Tab + login status
#   chats [N]                 List N most recent chats (default 20); * = unread
#   unread                    Only chats with unread messages
#   read <chat> [N]           Open <chat>, print last N messages (default 15)
#   send <chat> <message>     Send a message to <chat>

TAB=""
RT=""

die() { echo "whatsapp: $*" >&2; exit 1; }

# JSON-encode a string argument via node (safe interpolation into page JS).
jsonesc() { node -e 'process.stdout.write(JSON.stringify(process.argv[1]||""))' "$1"; }

# Derive --runtime flag from a (possibly follower-prefixed) target id.
set_rt_from_tab() {
  case "$TAB" in
    follower-*:*) RT="--runtime=$(printf '%s' "$TAB" | sed -n 's/^\(follower-[0-9a-f-]*\):.*/\1/p')" ;;
    *) RT="" ;;
  esac
}

find_tab() {
  # Local tab-list aggregates follower tabs too; the target id carries the runtime.
  local line
  line=$(playwright-cli tab-list 2>/dev/null | grep 'web.whatsapp.com' | head -1)
  if [ -n "$line" ]; then
    TAB=$(printf '%s' "$line" | sed -n 's/^\[\([^]]*\)\].*/\1/p'); set_rt_from_tab; return 0
  fi
  local rt
  for rt in $(host 2>/dev/null | grep -oE 'follower-[0-9a-f-]+' | sort -u); do
    line=$(playwright-cli tab-list --runtime="$rt" 2>/dev/null | grep 'web.whatsapp.com' | head -1)
    if [ -n "$line" ]; then
      TAB=$(printf '%s' "$line" | sed -n 's/^\[\([^]]*\)\].*/\1/p'); RT="--runtime=$rt"; return 0
    fi
  done
  die "No WhatsApp tab found. Open https://web.whatsapp.com/ (and log in) in your browser or a paired follower, then try again."
}

# wa_eval <js-file> -> prints the raw eval result (JSON string). Retries on the
# remote-debugger detach that happens when a follower changes foreground tab.
wa_eval() {
  local file="$1" out i
  for i in 1 2 3 4; do
    out=$(playwright-cli eval-file "$file" --tab="$TAB" $RT 2>&1)
    if [ $? -eq 0 ] && ! printf '%s' "$out" | grep -qi 'Debugger is not attached\|No target with given id'; then
      rm -f "$file" 2>/dev/null; printf '%s' "$out"; return 0
    fi
    case "$out" in
      *"Debugger is not attached"*|*"No target with given id"*) sleep 1.5; continue ;;
      *) rm -f "$file" 2>/dev/null; printf '%s' "$out" >&2; return 1 ;;
    esac
  done
  rm -f "$file" 2>/dev/null
  echo "Eval failed after retries. Bring the WhatsApp tab to the foreground on the browser/follower and retry." >&2
  printf '%s' "$out" >&2
  return 1
}

# Shared page-context helpers (prepended to every generated eval file).
helpers_js() {
cat <<'JS'
  function _text(el){ return el ? el.innerText : ''; }
  function _q(sel,root){ return (root||document).querySelector(sel); }
  function _qa(sel,root){ return [...(root||document).querySelectorAll(sel)]; }
  function _loggedIn(){ return !!_q('#pane-side'); }
  function _chatRows(){ return _qa('#pane-side [role="row"]'); }
  function _rowTitle(r){ const t=_q('span[title]',r); return t?t.getAttribute('title'):null; }
  function _rowUnread(r){ return _qa('span[aria-label]',r).some(s=>/ungelesen|unread/i.test(s.getAttribute('aria-label')||'')); }
  function _rowPreview(r){ const lines=_text(r).split('\n').map(s=>s.trim()).filter(Boolean); return lines.length?lines[lines.length-1]:''; }
  function _sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  function _fireInput(el){ el.dispatchEvent(new InputEvent('input',{bubbles:true})); }
  function _searchBox(){ return _q('#side [role="textbox"][data-tab="3"]')||_q('#side div[contenteditable][data-tab="3"]')||_q('#side [role="textbox"]'); }
JS
}

# Resolve a chat name to a snapshot ref and click it (trusted click; synthetic
# .click() does not trigger WhatsApp's React navigation). Sets nothing; returns
# 0 on success. Uses `node` to pick the best matching "row ... [ref=eNN]" line.
open_chat() {
  local name="$1" snap ref lname
  snap=$(playwright-cli snapshot --tab="$TAB" $RT 2>&1)
  if printf '%s' "$snap" | grep -qi 'Debugger is not attached'; then sleep 1.5; snap=$(playwright-cli snapshot --tab="$TAB" $RT 2>&1); fi
  lname=$(printf '%s' "$name" | tr 'A-Z' 'a-z')
  # Parse '- row "<title>" [ref=eNN]' lines; prefer prefix match, then substring.
  ref=$(printf '%s\n' "$snap" | awk -v n="$lname" '
    {
      i=index($0,"- row \"");
      if(i==0) next;
      s=substr($0,i+7);
      q=index(s,"\" [ref=");
      if(q==0) next;
      title=tolower(substr(s,1,q-1));
      r=substr(s,q);
      if(match(r,/ref=e[0-9]+/)==0) next;
      ref=substr(r,RSTART+4,RLENGTH-4);
      if(title==n && exact=="") exact=ref;
      else if(index(title,n)==1 && starts=="") starts=ref;
      else if(index(title,n)>0 && sub=="") sub=ref;
    }
    END { if(exact!="") print exact; else if(starts!="") print starts; else print sub; }
  ')
  [ -n "$ref" ] || return 1
  playwright-cli click "$ref" --tab="$TAB" $RT >/dev/null 2>&1 || return 1
  sleep 1.5
  return 0
}

TMP=""
mktmp() { TMP="/shared/.wa_$_$RANDOM.js"; }

# Build a temp eval file: helpers + an async IIFE wrapping the given body.
build_eval() {
  mktmp
  { echo "(async function(){"; helpers_js; printf '%s\n' "$1"; echo "})()"; } > "$TMP"
}

# ── commands ────────────────────────────────────────────────────────────────

cmd_status() {
  find_tab
  build_eval 'return JSON.stringify({loggedIn:_loggedIn(),title:document.title,chats:_loggedIn()?_chatRows().length:0});'
  local res; res=$(wa_eval "$TMP") || exit 1
  local rt_label="local"; [ -n "$RT" ] && rt_label="${RT#--runtime=}"
  echo "Tab: $TAB ($rt_label)"
  node -e '
    let d=JSON.parse(process.argv[1]); if(typeof d==="string")d=JSON.parse(d);
    console.log("Logged in: "+(d.loggedIn?"yes":"no"));
    if(d.loggedIn) console.log("Visible chat rows: "+d.chats);
    else console.log("Scan the QR code on web.whatsapp.com to log in.");
  ' "$res"
}

cmd_chats() {
  find_tab
  local n="${1:-20}"
  build_eval "
    if(!_loggedIn()) return JSON.stringify({error:'not_logged_in'});
    const rows=_chatRows().slice(0,$n);
    return JSON.stringify({chats:rows.map(r=>({title:_rowTitle(r),unread:_rowUnread(r),preview:_rowPreview(r).slice(0,120)})).filter(c=>c.title)});"
  local res; res=$(wa_eval "$TMP") || exit 1
  print_chats "$res"
}

cmd_unread() {
  find_tab
  build_eval "
    if(!_loggedIn()) return JSON.stringify({error:'not_logged_in'});
    const rows=_chatRows().filter(_rowUnread);
    return JSON.stringify({chats:rows.map(r=>({title:_rowTitle(r),unread:true,preview:_rowPreview(r).slice(0,120)})).filter(c=>c.title)});"
  local res; res=$(wa_eval "$TMP") || exit 1
  print_chats "$res" empty="No unread chats."
}

print_chats() {
  node -e '
    let d=JSON.parse(process.argv[1]); if(typeof d==="string")d=JSON.parse(d);
    if(d.error==="not_logged_in"){ console.error("Not logged into WhatsApp Web. Open https://web.whatsapp.com/ and scan the QR code."); process.exit(1); }
    const empty=(process.argv[2]||"empty=No chats found.").replace(/^empty=/,"");
    if(!d.chats.length){ console.log(empty); process.exit(0); }
    for(const c of d.chats){
      console.log((c.unread?"* ":"  ")+c.title);
      if(c.preview) console.log("    "+c.preview);
    }
  ' "$1" "${2:-}"
}

cmd_read() {
  find_tab
  open_chat "$1" || die "Chat not found: $1"
  local n="${2:-15}"
  build_eval "
    const rows=_qa('#main [role=\"row\"]');
    const msgs=rows.slice(-$n).map(r=>{
      const cp=_q('.copyable-text[data-pre-plain-text]',r);
      const meta=cp?cp.getAttribute('data-pre-plain-text'):'';
      const txtEl=_q('span.selectable-text',r);
      const text=txtEl?_text(txtEl):'';
      return {meta:(meta||'').trim(),text:(text||'').trim()};
    }).filter(m=>m.meta||m.text);
    return JSON.stringify({msgs});"
  local res; res=$(wa_eval "$TMP") || exit 1
  node -e '
    let d=JSON.parse(process.argv[1]); if(typeof d==="string")d=JSON.parse(d);
    console.log("# "+process.argv[2]+"\n");
    if(!d.msgs.length){ console.log("(no messages read)"); process.exit(0); }
    for(const m of d.msgs){ console.log(m.meta+m.text); }
  ' "$res" "$1"
}

cmd_send() {
  find_tab
  open_chat "$1" || die "Chat not found: $1"
  # Focus the compose box, then use trusted keystrokes (synthetic events do not
  # trigger WhatsApp's send). Verify the box exists first.
  build_eval "
    const box=_q('#main div[contenteditable=\"true\"][data-tab=\"10\"]')||_q('#main footer div[contenteditable=\"true\"]');
    if(!box) return JSON.stringify({ok:false});
    box.focus();
    return JSON.stringify({ok:true});"
  local chk; chk=$(wa_eval "$TMP") || exit 1
  case "$chk" in *'"ok":true'*) : ;; *) die "Could not find the compose box for: $1" ;; esac
  # Trusted typing + send. Newlines in the message are sent as Shift+Enter.
  local first=1 line
  # Split message on literal newlines; type each line, Shift+Enter between them.
  printf '%s\n' "$2" | while IFS= read -r line || [ -n "$line" ]; do
    if [ "$first" -eq 0 ]; then playwright-cli press "Shift+Enter" --tab="$TAB" $RT >/dev/null 2>&1; fi
    [ -n "$line" ] && playwright-cli type "$line" --tab="$TAB" $RT >/dev/null 2>&1
    first=0
  done
  playwright-cli press "Enter" --tab="$TAB" $RT >/dev/null 2>&1
  echo "Sent to $1: $2"
}

usage() {
  sed -n '12,17p' "$0" | sed 's/^# \{0,1\}//'
}

cmd="${1:-}"; shift 2>/dev/null || true
case "$cmd" in
  status) cmd_status ;;
  chats)  cmd_chats "${1:-}" ;;
  unread) cmd_unread ;;
  read)   [ -n "${1:-}" ] || die "usage: read <chat> [N]"; cmd_read "$1" "${2:-}" ;;
  send)   [ -n "${1:-}" ] && [ -n "${2:-}" ] || die "usage: send <chat> <message>"; cmd_send "$1" "$2" ;;
  ""|-h|--help|help) usage ;;
  *) echo "Unknown command: $cmd" >&2; usage; exit 1 ;;
esac
