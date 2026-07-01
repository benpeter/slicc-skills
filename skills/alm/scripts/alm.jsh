// alm.jsh — Adobe Learning Manager (Captivate Prime) client for SLICC
//
// Talks to the public Prime Learner API: https://learningmanager.adobe.com/primeapi/v2
// Auth header: `Authorization: oauth <accessToken>`. The API does NOT validate Origin,
// so plain fetch() works — no browser is needed for API calls.
//
// This runtime exposes only `fetch`, `console`, `process`, and `require('fs'|'path')`.
// There is no browser/exec here, so the session token is captured out-of-band (the agent
// scrapes it from the newlearner player iframe) and handed to `alm login <token>`.
//
// COMMANDS
//   alm login <token> [--account=ID] [--user=ID]   Save an access token to config
//   alm token                         Show stored token + context (masked)
//   alm whoami [--json]               Show the signed-in learner
//   alm enrollments [--status=STATE]  List your trainings + progress
//   alm progress                      Compact completion summary
//   alm catalog [--query=Q] [--type=T] [--limit=N]   Search the catalog
//   alm show <loId> [--json]          Training details (+instances)
//   alm enroll <loId> [--instance=ID] Enroll yourself
//   alm unenroll <loId>               Remove your enrollment
//   alm skills [--limit=N]            List skills
//   alm badges [--json]               Your earned badges
//   alm player <loId>                 Print the browser player URL to run a training
//   alm raw <path> [--json]           GET any primeapi/v2 path
//
// loId: "certification:88031" or a bare number with --type=course|certification|learningProgram|jobAid

const fs   = require('fs');
const path = require('path');

const API_BASE = 'https://learningmanager.adobe.com/primeapi/v2';
const CONFIG_PATH = path.join(path.dirname(process.argv[1]), '..', '.config.json');

// ─── tiny output helpers (no runtime extensions available) ───────────────────
const isTTY = false;
const dim   = (s) => s;
const bold  = (s) => s;
const green = (s) => s;
function die(msg) { console.error('alm: ' + msg); process.exit(1); }
function outJson(obj) { console.log(JSON.stringify(obj, null, 2)); }
function trunc(s, n) { s = String(s == null ? '' : s); return s.length > n ? s.slice(0, n - 1) + '…' : s; }
function table(rows) {
  if (!rows.length) return '';
  const widths = [];
  for (const r of rows) r.forEach((cell, i) => { widths[i] = Math.max(widths[i] || 0, String(cell).length); });
  return rows.map(r => r.map((cell, i) => String(cell).padEnd(widths[i])).join('  ')).join('\n');
}

// ─── flag parsing ────────────────────────────────────────────────────────────
function parseArgs(argv) {
  const positional = [], flags = {};
  for (const a of argv) {
    const m = /^--([^=]+)(?:=(.*))?$/.exec(a);
    if (m) flags[m[1]] = m[2] === undefined ? true : m[2];
    else positional.push(a);
  }
  return { positional, flags };
}

// ─── config ────────────────────────────────────────────────────────────────
async function loadConfig() {
  try { return JSON.parse(await fs.readFile(CONFIG_PATH)); }
  catch { return {}; }
}
async function saveConfig(updates) {
  const cur = await loadConfig();
  const next = { ...cur, ...updates };
  await fs.writeFile(CONFIG_PATH, JSON.stringify(next, null, 2));
  return next;
}

// ─── loId helpers ────────────────────────────────────────────────────────────
const LO_TYPES = ['course', 'certification', 'learningProgram', 'jobAid'];
function normalizeLoId(raw, flags) {
  if (!raw) return null;
  if (String(raw).includes(':')) return raw;
  const t = (flags && (flags.type || flags.t)) || 'course';
  if (!LO_TYPES.includes(t)) die(`--type must be one of: ${LO_TYPES.join(', ')}`);
  return `${t}:${raw}`;
}

// ─── API ─────────────────────────────────────────────────────────────────────
async function api(pathAndQuery, opts = {}) {
  const cfg = await loadConfig();
  if (!cfg.access_token) {
    die('Not logged in. Ask the agent to capture a token from your open ALM training, then: alm login <token>');
  }
  const url = pathAndQuery.startsWith('http') ? pathAndQuery : API_BASE + pathAndQuery;
  const headers = {
    'Authorization': 'oauth ' + cfg.access_token,
    'Accept': 'application/vnd.api+json',
    ...(opts.headers || {}),
  };
  // ALM's edge intermittently returns a transient 401/403 (an nginx HTML "Authorization
  // Required" page, not a JSON body) under load. A genuinely expired token 401s persistently.
  // So we retry auth failures a few times with backoff and only give up if they stick.
  let res;
  const RETRY = [429, 503, 401, 403];
  for (let attempt = 0; attempt < 6; attempt++) {
    res = await fetch(url, { method: opts.method || 'GET', headers, body: opts.body });
    if (!RETRY.includes(res.status)) break;
    await new Promise(r => setTimeout(r, 400 * (attempt + 1)));
  }
  if (res.status === 401 || res.status === 403) {
    die('Token expired or invalid (persistent auth failure). Reopen an ALM training in your browser and re-run `alm login <token>`.');
  }
  const text = await res.text();
  if (!res.ok) die(`API ${res.status} on ${pathAndQuery}: ${text.slice(0, 200)}`);
  if (opts.raw) return text;
  try { return JSON.parse(text); } catch { return {}; }
}

// ─── JSON:API helpers ──────────────────────────────────────────────────────
function indexIncluded(doc) {
  const map = {};
  for (const inc of (doc.included || [])) map[`${inc.type}:${inc.id}`] = inc;
  return map;
}
function loTitle(inc) {
  const locs = inc?.attributes?.localizedMetadata || [];
  const en = locs.find(l => /^en/i.test(l.locale)) || locs[0];
  return en?.name || inc?.attributes?.name || inc?.id || '—';
}

// ─── commands ────────────────────────────────────────────────────────────────
async function cmdLogin(token, flags) {
  if (!token) {
    die('usage: alm login <accessToken> [--account=ID] [--user=ID]\n' +
        '  The token is the `nativeExtensionToken` (natext_…) from the newlearner player iframe.\n' +
        '  Ask the agent: "capture my ALM token" — it will scrape it from your open training tab.');
  }
  const updates = { access_token: token, updated_at: Date.now() };
  if (flags.account) updates.accountId = String(flags.account);
  if (flags.user) updates.userId = String(flags.user);
  await saveConfig(updates);
  // verify + capture canonical user id
  const me = await api('/user');
  const uid = me?.data?.id;
  if (uid) await saveConfig({ userId: uid });
  console.log(green('✓ Logged in to Adobe Learning Manager'));
  console.log(dim(`  User: ${me?.data?.attributes?.name || 'user'}${uid ? ' (' + uid + ')' : ''}`));
}

async function cmdToken() {
  const cfg = await loadConfig();
  if (!cfg.access_token) die('No token stored. Run: alm login <token>');
  console.log(table([
    ['Token',   cfg.access_token.slice(0, 18) + '…'],
    ['Account', cfg.accountId || '—'],
    ['User',    cfg.userId || '—'],
    ['Updated', cfg.updated_at ? new Date(cfg.updated_at).toISOString() : '—'],
  ]));
}

async function cmdWhoami(flags) {
  const me = await api('/user');
  if (flags.json) return outJson(me);
  const a = me?.data?.attributes || {};
  console.log(table([
    ['Name',    a.name || '—'],
    ['Email',   a.email || '—'],
    ['User ID', me?.data?.id || '—'],
    ['Roles',   (a.roles || []).join(', ') || '—'],
    ['Points',  String(a.pointsEarned ?? '—')],
    ['State',   a.state || '—'],
  ]));
}

async function fetchEnrollments(extra = '') {
  return api(`/enrollments?include=learningObject&page.limit=50${extra}`);
}

async function cmdEnrollments(flags) {
  const statusFilter = (flags.status || '').toUpperCase();
  const doc = await fetchEnrollments();
  if (flags.json) return outJson(doc);
  const inc = indexIncluded(doc);
  const rows = [['LO ID', 'Title', 'State', 'Progress']];
  for (const e of (doc.data || [])) {
    const a = e.attributes || {};
    const st = (a.state || '').toUpperCase();
    if (statusFilter && st !== statusFilter) continue;
    const loRef = e.relationships?.learningObject?.data;
    const lo = loRef ? inc[`${loRef.type}:${loRef.id}`] : null;
    rows.push([loRef?.id || '—', trunc(lo ? loTitle(lo) : '—', 46), st || '—',
      (a.progressPercent != null ? a.progressPercent + '%' : '—')]);
  }
  if (rows.length === 1) { console.log(dim('No enrollments.')); return; }
  console.log(table(rows));
  console.log(dim(`\n${rows.length - 1} enrollment(s). \`alm show <loId>\` for details, \`alm player <loId>\` to run.`));
}

async function cmdProgress() {
  const doc = await fetchEnrollments();
  const inc = indexIncluded(doc);
  let done = 0, started = 0, notStarted = 0;
  const lines = [];
  for (const e of (doc.data || [])) {
    const a = e.attributes || {};
    const pct = a.progressPercent != null ? a.progressPercent : 0;
    if ((a.state || '').toUpperCase() === 'COMPLETED') done++;
    else if (pct > 0) started++; else notStarted++;
    const loRef = e.relationships?.learningObject?.data;
    const lo = loRef ? inc[`${loRef.type}:${loRef.id}`] : null;
    const bar = '█'.repeat(Math.round(pct / 10)).padEnd(10, '░');
    lines.push(`  ${bar} ${String(pct).padStart(3)}%  ${trunc(lo ? loTitle(lo) : (loRef?.id || ''), 40)}`);
  }
  console.log(bold('Learning progress'));
  console.log(lines.join('\n') || dim('  (no enrollments)'));
  console.log(dim(`\n  ${done} completed · ${started} in progress · ${notStarted} not started`));
}

function cmdMandatory() {
  // The authoritative "mandatory" signal is the BL enrollment flag `mandatory:true`,
  // which is NOT exposed by the public primeapi/v2 this client uses (its /enrollments
  // lacks the flag and omits compliance certifications entirely). The flag lives only in
  // the cookie-authenticated BL API, which needs a live logged-in browser tab — something
  // this fetch-only runtime cannot reach. So mandatory listing is provided by an agent
  // helper that runs through the browser page context:
  console.log('Mandatory trainings use ALM\'s internal `mandatory` flag (BL API), which needs your');
  console.log('live logged-in browser session. Ask the agent to run the helper, or run:');
  console.log('');
  console.log('  bash ' + path.join(path.dirname(process.argv[1]), 'alm-mandatory.sh') + '            # outstanding');
  console.log('  bash ' + path.join(path.dirname(process.argv[1]), 'alm-mandatory.sh') + ' --all      # include completed');
  console.log('');
  console.log('It filters mandatory:true, keeps the current cycle, groups the Secure Developer');
  console.log('belts, and sorts by due date — matching the Adobe "Inside" required-training view.');
}

async function cmdCatalog(flags) {
  const limit = parseInt(flags.limit ?? flags.l ?? 15, 10);
  const q = flags.query || flags.q;
  let path;
  if (q) {
    path = `/search?query=${encodeURIComponent(q)}&page.limit=${limit}` +
           (flags.type ? `&filter.loTypes=${encodeURIComponent(flags.type)}` : '');
  } else {
    path = `/learningObjects?page.limit=${limit}` +
           (flags.type ? `&filter.loTypes=${encodeURIComponent(flags.type)}` : '');
  }
  const doc = await api(path);
  if (flags.json) return outJson(doc);
  const rows = [['LO ID', 'Title', 'Type']];
  for (const d of (doc.data || [])) {
    const a = d.attributes || {};
    const title = loTitle(d) !== d.id ? loTitle(d) : (a.name || d.id);
    rows.push([trunc(d.id, 26), trunc(title, 46), a.loType || (d.id.split(':')[0]) || '—']);
  }
  if (rows.length === 1) { console.log(dim('No results.')); return; }
  console.log(table(rows));
}

async function cmdShow(loId, flags) {
  if (!loId) die('usage: alm show <loId>');
  const doc = await api(`/learningObjects/${encodeURIComponent(loId)}?include=enrollment,instances,subLOs,skills`);
  if (flags.json) return outJson(doc);
  const d = doc.data || {};
  const a = d.attributes || {};
  const desc = (a.localizedMetadata || []).find(l => /^en/i.test(l.locale))?.description
            || (a.localizedMetadata || [])[0]?.description || '';
  console.log(bold(loTitle(d)) + dim(`  [${d.id}]`));
  if (desc) console.log(trunc(desc.replace(/\s+/g, ' '), 300));
  console.log(table([
    ['Type',       a.loType || (d.id ? d.id.split(':')[0] : '—')],
    ['Duration',   a.duration != null ? Math.round(a.duration / 60) + ' min' : '—'],
    ['Enrollment', d.relationships?.enrollment ? green('enrolled') : dim('not enrolled')],
    ['State',      a.state || '—'],
  ]));
  const instances = (doc.included || []).filter(i => i.type === 'learningObjectInstance');
  if (instances.length) {
    console.log(dim('\nInstances:'));
    for (const ins of instances.slice(0, 8)) console.log('  ' + ins.id + dim('  ' + (loTitle(ins) || '')));
  }
  console.log(dim(`\nRun it: alm player ${d.id}   ·   Enroll: alm enroll ${d.id}`));
}

async function cmdEnroll(loId, flags) {
  if (!loId) die('usage: alm enroll <loId> [--instance=ID]');
  const params = new URLSearchParams({ loId });
  if (flags.instance) params.set('loInstanceId', flags.instance);
  await api(`/enrollments?${params.toString()}`, { method: 'POST' });
  console.log(green(`✓ Enrolled in ${loId}`));
  console.log(dim(`Run it now: alm player ${loId}`));
}

async function cmdUnenroll(loId) {
  if (!loId) die('usage: alm unenroll <loId>');
  const cfg = await loadConfig();
  await api(`/enrollments/${encodeURIComponent(loId + '_' + (cfg.userId || ''))}`, { method: 'DELETE' });
  console.log(green(`✓ Unenrolled from ${loId}`));
}

async function cmdSkills(flags) {
  const limit = parseInt(flags.limit ?? 20, 10);
  const doc = await api(`/skills?page.limit=${limit}`);
  if (flags.json) return outJson(doc);
  const rows = [['ID', 'Skill']];
  for (const s of (doc.data || [])) rows.push([trunc(s.id, 12), trunc(s.attributes?.name || '—', 50)]);
  console.log(table(rows));
}

async function cmdBadges(flags) {
  const cfg = await loadConfig();
  if (!cfg.userId) die('No user id in config. Run: alm login <token>');
  const doc = await api(`/users/${cfg.userId}/userBadges?page.limit=50`);
  if (flags.json) return outJson(doc);
  const rows = [['Badge', 'Achieved']];
  for (const b of (doc.data || [])) rows.push([
    trunc(b.attributes?.badge?.name || b.id, 40),
    b.attributes?.dateAchieved ? String(b.attributes.dateAchieved).slice(0, 10) : '—',
  ]);
  if (rows.length === 1) { console.log(dim('No badges yet.')); return; }
  console.log(table(rows));
}

async function cmdPlayer(loId) {
  if (!loId) die('usage: alm player <loId>');
  const cfg = await loadConfig();
  const accountId = cfg.accountId || '29997';
  const [loType, num] = String(loId).includes(':') ? loId.split(':') : ['course', loId];
  const url = `https://cpcontents.adobe.com/public/newlearner/newlearner_46b41063.html` +
    `?accountId=${accountId}&hostName=learningmanager.adobe.com` +
    `#/overviewPage?loId=${num}&loType=${loType}`;
  console.log(url);
  console.log(dim('\nOpen this in your browser (with your ALM session) to run the training,'));
  console.log(dim('or ask the agent to open it with playwright-cli.'));
}

async function cmdRaw(p, flags) {
  if (!p) die('usage: alm raw <path>   e.g. alm raw /catalogs');
  const doc = await api(p.startsWith('/') ? p : '/' + p);
  outJson(doc);
}

function help() {
  console.log(`alm — Adobe Learning Manager (Captivate Prime) client

USAGE
  alm login <token> [--account=ID] [--user=ID]   Save an access token
  alm token                          Show stored token + context (masked)
  alm whoami [--json]                Show the signed-in learner
  alm enrollments [--status=STATE]   List your trainings + progress
  alm progress                       Compact completion summary
  alm mandatory                      How to list mandatory trainings (uses BL API helper)
  alm catalog [--query=Q] [--type=T] [--limit=N]   Search the catalog
  alm show <loId> [--json]           Training details (+instances)
  alm enroll <loId> [--instance=ID]  Enroll yourself
  alm unenroll <loId>                Remove your enrollment
  alm skills [--limit=N]             List skills
  alm badges [--json]                Your earned badges
  alm player <loId>                  Print the browser player URL to run a training
  alm raw <path> [--json]            GET any primeapi/v2 path

loId:  "certification:88031" or a bare number with --type=course|certification|learningProgram|jobAid

The access token is the newlearner player's nativeExtensionToken (session-scoped).
If a call reports the token expired, reopen an ALM training in your browser and
have the agent re-capture it, then: alm login <token>`);
}

// ─── router ────────────────────────────────────────────────────────────────
const { positional, flags } = parseArgs(process.argv.slice(2));
const cmd = positional[0];

if (!cmd || flags.help || flags.h) { help(); process.exit(0); }

switch (cmd) {
  case 'login':       await cmdLogin(positional[1], flags); break;
  case 'token':       await cmdToken(); break;
  case 'whoami':      await cmdWhoami(flags); break;
  case 'enrollments': await cmdEnrollments(flags); break;
  case 'progress':    await cmdProgress(); break;
  case 'mandatory':   cmdMandatory(); break;
  case 'catalog':     await cmdCatalog(flags); break;
  case 'show':        await cmdShow(normalizeLoId(positional[1], flags), flags); break;
  case 'enroll':      await cmdEnroll(normalizeLoId(positional[1], flags), flags); break;
  case 'unenroll':    await cmdUnenroll(normalizeLoId(positional[1], flags)); break;
  case 'skills':      await cmdSkills(flags); break;
  case 'badges':      await cmdBadges(flags); break;
  case 'player':      await cmdPlayer(normalizeLoId(positional[1], flags)); break;
  case 'raw':         await cmdRaw(positional[1], flags); break;
  default:            die(`Unknown command: ${cmd}\nRun \`alm --help\` for usage.`);
}
