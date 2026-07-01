#!/usr/bin/env bash
# alm-mandatory.sh — list your MANDATORY Adobe Learning Manager trainings using the
# authoritative signal: the BL enrollment record's `mandatory:true` flag.
#
# Why a shell helper (not alm.jsh): the `mandatory` flag and `deadline` are only in the
# internal BL API (api/bl/v1), which authenticates with the learningmanager.adobe.com
# session COOKIES + a csrf_token. Plain fetch() from the jsh runtime cannot send those
# cookies, so this runs through your live, logged-in browser tab via playwright-cli.
#
# Behaviour (mirrors Adobe "Inside"): current cycle only (latest deadline per training
# family), NOT-completed by default, sorted by due date, Secure Developer belts grouped.
#
# Usage:
#   bash alm-mandatory.sh            # incomplete mandatory trainings, by due date
#   bash alm-mandatory.sh --all      # include completed ones too
#   bash alm-mandatory.sh --json     # raw JSON
set -euo pipefail

MODE_ALL=0; MODE_JSON=0
for a in "$@"; do
  case "$a" in
    --all) MODE_ALL=1 ;;
    --json) MODE_JSON=1 ;;
  esac
done

# Locate a live newlearner tab (any runtime).
tab_line="$(playwright-cli tab-list 2>/dev/null | grep -i 'newlearner' | head -1 || true)"
[ -z "$tab_line" ] && tab_line="$(playwright-cli tab-list 2>/dev/null | grep -i 'cpcontents.adobe.com' | head -1 || true)"
if [ -z "$tab_line" ]; then
  echo "alm-mandatory: no live ALM tab found. Open your ALM home/training (logged in) and retry." >&2
  exit 1
fi
tab="$(printf '%s' "$tab_line" | grep -oE '\[[^]]+\]' | head -1 | tr -d '[]')"

# Discover account id, user id, and csrf token from the tab (top URL + child iframe URL).
ctx="$(playwright-cli eval --tab="$tab" "
(function(){
  var out={account:'',user:'',csrf:''};
  var m=/[?&]accountId=(\d+)/.exec(location.href); if(m) out.account=m[1];
  for(var f of document.querySelectorAll('iframe')){ try{ var u=new URL(f.src);
    var c=u.searchParams.get('csrfToken')||u.searchParams.get('csrf_token'); if(c){ out.csrf=c;
      out.user=u.searchParams.get('userId')||out.user; } }catch(e){} }
  for(var e of performance.getEntriesByType('resource')){ try{ var u2=new URL(e.name);
    var c2=u2.searchParams.get('csrf_token')||u2.searchParams.get('csrfToken'); if(c2&&!out.csrf) out.csrf=c2;
    var uid=/\/user\/(\d+)/.exec(u2.pathname); if(uid&&!out.user) out.user=uid[1];
  }catch(e){} }
  return JSON.stringify(out);
})()" 2>/dev/null)"

account="$(printf '%s' "$ctx" | grep -oE '\"account\":\"[0-9]+\"' | grep -oE '[0-9]+' | head -1 || true)"
user="$(printf '%s' "$ctx" | grep -oE '\"user\":\"[0-9]+\"' | grep -oE '[0-9]+' | head -1 || true)"
csrf="$(printf '%s' "$ctx" | grep -oE '\"csrf\":\"[a-f0-9]+\"' | sed -E 's/.*:"([a-f0-9]+)"/\1/' | head -1 || true)"
account="${account:-29997}"

if [ -z "$user" ] || [ -z "$csrf" ]; then
  echo "alm-mandatory: could not read csrf/user from the tab (is the dashboard loaded?)." >&2
  echo "  ctx=$ctx" >&2
  exit 1
fi

# Page over BL enrollments, filter mandatory:true, dedupe to latest cycle per family,
# group the Secure Developer belts, and emit JSON. All logic runs in the page context.
result="$(playwright-cli eval --tab="$tab" "
(async()=>{
  const account='$account', user='$user', csrf='$csrf';
  const base='https://learningmanager.adobe.com/api/bl/v1/account/'+account+'/user/'+user;
  let offset=0, all=[], courses={}, tagsByCourse={};
  for(let p=0;p<12;p++){
    const r=await fetch(base+'/enrollment?csrf_token='+csrf+'&i_qp_limit=20&offset='+offset+'&b_qp_get_tags=true',{credentials:'include'});
    if(!r.ok) break; const j=await r.json();
    (j.course||[]).forEach(c=>{courses[c.id]={name:c.name||c.heading, cert:c.createdForCertification};});
    (j.courseTag||[]).forEach(t=>{ (tagsByCourse[t.courseId]=tagsByCourse[t.courseId]||[]).push(t.name); });
    const enr=j.enrollment||[]; all=all.concat(enr);
    if(enr.length<20) break; offset+=20;
  }
  // Secure Developer belts are a role-choice under one certification; they all carry the
  // 'SGB' / 'Security Green Belt Training' tag. Group them into a single parent line.
  function isBelt(courseId){ const tags=tagsByCourse[courseId]||[]; return tags.some(t=>/^SGB$|Security Green Belt/i.test(t)); }
  function fam(courseId, n){
    if(isBelt(courseId)) return 'security green belt - secure developer';
    return (n||'').replace(/RETIRED:?/i,'').replace(/\([A-Z ]+\)/i,'')
      .replace(/\b(Course|Training|Overview|Fundamentals|Policy|At Adobe\.?)\b/gi,'')
      .replace(/[0-9.]+/g,'').replace(/\s+/g,' ').trim().toLowerCase();
  }
  const byFam={};
  for(const e of all){ if(e.mandatory!==true) continue;
    const info=courses[e.courseId]||{}; const n=info.name||('#'+e.courseId);
    const belt=isBelt(e.courseId); const k=fam(e.courseId,n); const due=(e.deadline||'').slice(0,10);
    const display=belt?'Security Green Belt – Secure Developer':n;
    const rec={name:display, due, passed:!!e.pass, prog:e.progressPercent||0, courseId:e.courseId, cert:info.cert};
    // For grouped belts, prefer to keep an incomplete member (so "done" only if ALL done)
    const prev=byFam[k];
    if(!prev) byFam[k]=rec;
    else {
      if(belt){ // group: done only if every belt passed; keep earliest incomplete due
        prev.passed = prev.passed && rec.passed;
        if(!rec.passed && (prev.passed || due < prev.due)) { prev.due = due; prev.courseId = rec.courseId; }
      } else if(due > prev.due) byFam[k]=rec; // non-belt: latest cycle
    }
  }
  const now=Date.now();
  const rows=Object.values(byFam).map(r=>({
    name:r.name, due:r.due, done:r.passed,
    overdue: !r.passed && r.due && Date.parse(r.due) < now,
    url:'https://cpcontents.adobe.com/public/newlearner/newlearner_46b41063.html?accountId='+account+
        '&hostName=learningmanager.adobe.com#/overviewPage?loId='+r.courseId+'&loType=course'
  })).sort((a,b)=>(a.due||'9999').localeCompare(b.due||'9999'));
  return JSON.stringify(rows);
})()" 2>/dev/null)"

if [ "$MODE_JSON" = "1" ]; then
  printf '%s\n' "$result"
  exit 0
fi

# Render with node (available in this shell) — table + links.
node -e '
const rows = JSON.parse(process.argv[1] || "[]");
const all = process.argv[2] === "1";
const list = all ? rows : rows.filter(r => !r.done);
if (!list.length) { console.log(all ? "No mandatory trainings found." : "No outstanding mandatory trainings — you are all caught up."); process.exit(0); }
console.log("Mandatory trainings" + (all ? " (all, current cycle)" : " — outstanding") + ", by due date:\n");
for (const r of list) {
  const due = r.due || "no deadline";
  const tag = r.done ? "[done]" : (r.overdue ? "[OVERDUE]" : "[due]");
  console.log(`${tag} ${due}  ${r.name}`);
  console.log(`         ${r.url}`);
}
const outstanding = rows.filter(r=>!r.done).length;
const overdue = rows.filter(r=>!r.done && r.overdue).length;
console.log(`\n${rows.length} mandatory (current cycle) · ${outstanding} outstanding${overdue?" · "+overdue+" OVERDUE":""}`);
console.log("Signal: BL enrollment mandatory:true; Secure Developer belts grouped; latest cycle per training.");
' "$result" "$MODE_ALL"
