#!/usr/bin/env bash
# alm-refresh.sh — re-capture the Adobe Learning Manager session token from an open
# newlearner player tab and store it via `alm login`.
#
# The alm.jsh runtime has no browser access, so token refresh runs here (agent-side)
# via playwright-cli. Requires an ALM training/dashboard open in a logged-in browser
# (leader or a connected follower runtime).
#
# Usage:  bash alm-refresh.sh
set -euo pipefail

echo "alm-refresh: locating an open Adobe Learning Manager tab…"

# Find a newlearner tab across all runtimes; fall back to any cpcontents tab.
tab_line="$(playwright-cli tab-list 2>/dev/null | grep -i 'newlearner' | head -1 || true)"
if [ -z "$tab_line" ]; then
  tab_line="$(playwright-cli tab-list 2>/dev/null | grep -i 'cpcontents.adobe.com' | head -1 || true)"
fi
if [ -z "$tab_line" ]; then
  echo "alm-refresh: no ALM tab found." >&2
  echo "  Open your ALM home/training in a logged-in browser, then run this again." >&2
  echo "  If the SLICC browser is not logged in, complete Adobe Okta SSO via teleport" >&2
  echo "  or your connected follower browser first (see skills/handoff)." >&2
  exit 1
fi

# tab-list prints "[<targetId>] <url> ..." — extract the id inside the first [...].
tab="$(printf '%s' "$tab_line" | grep -oE '\[[^]]+\]' | head -1 | tr -d '[]')"
if [ -z "$tab" ]; then
  echo "alm-refresh: could not parse tab id from: $tab_line" >&2
  exit 1
fi
echo "alm-refresh: using tab $tab"

# Scrape nativeExtensionToken + accountId + userId from the player's child iframe URL,
# falling back to network resource entries.
info="$(playwright-cli eval --tab="$tab" "
(function(){
  function pick(url){ try{ const u=new URL(url); const t=u.searchParams.get('nativeExtensionToken');
    if(!t) return null; return JSON.stringify({token:t, user:u.searchParams.get('userId')||''}); }catch(e){return null;} }
  for(const f of document.querySelectorAll('iframe')){ const r=pick(f.src); if(r) return r; }
  for(const e of performance.getEntriesByType('resource')){ const r=pick(e.name); if(r) return r; }
  return '';
})()" 2>/dev/null || true)"

token="$(printf '%s' "$info" | grep -oE 'natext_[A-Za-z0-9]+' | head -1 || true)"
user="$(printf '%s' "$info" | grep -oE '\"user\":\"[0-9]+\"' | grep -oE '[0-9]+' | head -1 || true)"

# accountId from the tab's top URL
top="$(playwright-cli eval --tab="$tab" "location.href" 2>/dev/null || true)"
account="$(printf '%s' "$top" | grep -oE 'accountId=[0-9]+' | grep -oE '[0-9]+' | head -1 || true)"

if [ -z "$token" ]; then
  echo "alm-refresh: no nativeExtensionToken on that tab." >&2
  echo "  Make sure the learner dashboard/training has fully loaded, then retry." >&2
  exit 1
fi

echo "alm-refresh: captured token ${token:0:16}…  (account=${account:-?} user=${user:-?})"

args=("$token")
[ -n "$account" ] && args+=("--account=$account")
[ -n "$user" ] && args+=("--user=$user")

alm login "${args[@]}"
