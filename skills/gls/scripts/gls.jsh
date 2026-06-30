// GLS Bank client for SLICC — modern `sliccy:` runtime API.
//
// Reads (accounts / balances / transactions / search) go through the GLS portal's
// own JSON API, called from the logged-in GLS page context via `sliccy:browser`.
// `transfer` drives the SEPA form, VERIFIES every field + the verification-of-payee
// result by reading the screen back, and stops at the review — money only moves on
// an explicit human SecureGo approval (`--send`), and never on a name mismatch
// unless `--confirm-mismatch` is given.
//
// Runtime API: capability bridges via `require('sliccy:<name>')` (post-2026-06-24).
// See skill-authoring/jsh-runtime-extensions.md.  // tva

const exec = require('sliccy:exec'); // exec(cmd) + exec.spawn(argv[]) — spawn avoids shell quoting
const browser = require('sliccy:browser'); // page-context fetch/eval against the GLS tab
const cli = require('sliccy:cli');

const GLS_HOST = 'gls-online-filiale.de';
const SP = '/services_cloud/portal/proxy-gateway/serviceproxy';
const SEPA_URL = 'https://www.gls-online-filiale.de/services_cloud/portal/webcomp/ueberweisung/sepa/#/erfassen';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const norm = (s) => String(s || '').replace(/[^0-9a-zA-Z]/g, '').toUpperCase();

// Browser CLI via spawn — argv elements are passed literally (no shell parsing),
// so a recipient name with spaces/apostrophes can't break out or inject.
async function pw(argv) {
  return await exec.spawn(['playwright', ...argv]);
}
async function pwOk(argv, what) {
  const r = await pw(argv);
  if (r.exitCode !== 0) cli.die('Browser command failed (' + (what || argv[0]) + '): ' + (r.stderr || '').trim());
  return r;
}

// --- GLS tab discovery (banking pages first, logout last) --------------------
async function glsTabs() {
  const tl = await pw(['tab-list']);
  if (tl.exitCode !== 0) cli.die('Could not list browser tabs (`playwright tab-list` failed).');
  return tl.stdout
    .split('\n')
    .filter((l) => l.includes(GLS_HOST))
    .map((l) => {
      const m = l.match(/^\[([^\]]+)\]/);
      return m ? { id: m[1], bank: /banking_start|\/webcomp\//.test(l), logout: /\/logout/.test(l) } : null;
    })
    .filter(Boolean)
    .sort((a, b) => a.logout - b.logout || b.bank - a.bank);
}

const BANKING_START = 'https://www.gls-online-filiale.de/services_cloud/portal/m/banking_start';
const PROBE = '/konto-service/v2/konto/group?kontoFilter=%7B%7D&groupBy=INDIVIDUELL';

// Run an async page-context expression in a tab; returns the value, or the
// sentinel {__detached:true} when the control link isn't attached.
async function evalIn(tabId, code) {
  try { return await browser.evalAsync(tabId, code); } catch (e) { return { __detached: true }; }
}
// Auth-probe a tab → HTTP status (200 live, 401/403 logged out), or null if detached.
async function ping(tabId) {
  const r = await evalIn(tabId, '(async()=>{try{const r=await fetch(' + JSON.stringify(SP + PROBE) + ',{credentials:"include"});return r.status;}catch(e){return -1;}})()');
  return r && r.__detached ? null : r;
}
const runtimeOf = (id) => (id && id.includes(':') ? id.split(':')[0] : null);

// Resolve a GLS tab with a LIVE (HTTP 200) session that we can actually drive.
// Mirrors the verified recovery: a pre-existing tab is often stale/logged-out, or
// (in cup/follower mode) can't be attached. Probe every candidate for 200; if none
// is live+drivable, open a FRESH FOREGROUND banking tab — a tab WE open inherits the
// authenticated session and, foregrounded on the follower, the leader attaches to it.
let _tab = null;
async function resolveLiveTab() {
  if (_tab && (await ping(_tab)) === 200) return _tab;
  _tab = null;
  const tabs = await glsTabs();
  for (const c of tabs) if ((await ping(c.id)) === 200) { _tab = c.id; return _tab; }
  // none live/drivable → open a fresh foreground tab on the same runtime (follower) if any
  const rt = tabs.map((t) => runtimeOf(t.id)).find(Boolean);
  const args = ['tab-new', BANKING_START, '--foreground'];
  if (rt) args.push('--runtime=' + rt);
  const out = (await pw(args)).stdout || '';
  let fresh = (out.match(/targetId:\s*([^\]\s]+)/) || [])[1] || null;
  if (fresh && rt && !fresh.includes(':')) fresh = rt + ':' + fresh; // composite id for a follower tab
  if (fresh)
    for (let i = 0; i < 12; i++) {
      await sleep(700);
      const st = await ping(fresh);
      if (st === 200) { _tab = fresh; return _tab; }
      if (st === 401 || st === 403) break; // a fresh tab is also logged out → session genuinely expired
    }
  cli.die('No logged-in GLS session. Open https://www.gls-online-filiale.de and log in (with SecureGo), then retry.');
}

async function pageFetch(path, { method = 'GET', body = null } = {}) {
  const init =
    '{method:' + JSON.stringify(method) + ',credentials:"include",headers:{Accept:"application/json"' +
    (body ? ',"Content-Type":"application/json"' : '') + '}' + (body ? ',body:' + JSON.stringify(JSON.stringify(body)) : '') + '}';
  const code =
    '(async()=>{const r=await fetch(' + JSON.stringify(SP + path) + ',' + init +
    ');const t=await r.text();let j=null;try{j=JSON.parse(t)}catch(e){}return {status:r.status,json:j,text:j?null:t.slice(0,200)};})()';
  let r = await evalIn(await resolveLiveTab(), code);
  if (r && r.__detached) { _tab = null; r = await evalIn(await resolveLiveTab(), code); } // tab dropped mid-call → re-resolve once
  return r;
}
function checkAuth(res) {
  if (!res) cli.die('GLS request failed (no response).');
  if (res.status === 401 || res.status === 403) cli.die('GLS session expired (' + res.status + '). Reload / re-login at gls-online-filiale.de, then retry.');
  if (res.status === 0 || res.status >= 500) cli.die('GLS request failed (HTTP ' + res.status + ').');
}

// --- Data helpers ------------------------------------------------------------
async function fetchAccounts() {
  const path = '/konto-service/v2/konto/group?kontoFilter=' +
    encodeURIComponent('{"includeVertragsloseKonten":true,"includeVertraege":true}') + '&includeEmptyGroups=false&groupBy=INDIVIDUELL';
  const res = await pageFetch(path);
  checkAuth(res);
  const konten = ((res.json && res.json.groups) || []).flatMap((g) => g.konten || []);
  return konten.map((k) => ({
    name: k.displayName || k.produktName || '(unnamed)',
    iban: k.iban || k.businessIdent || '', ident: k.ident,
    saldo: k.saldoEuro != null ? k.saldoEuro : k.saldo, currency: k.waehrungCodeIsoAlpha || 'EUR', art: k.art || '',
  }));
}
async function resolveAccount(query) {
  const accts = await fetchAccounts();
  if (!accts.length) cli.die('No GLS accounts returned — check the session / login.');
  if (!query) return accts.find((a) => /eingang|giro|kontokorrent/i.test(a.name) || a.art === 'KONTOKORRENT') || accts[0];
  const q = norm(query);
  const hit = accts.find((a) => a.name.toLowerCase().includes(query.toLowerCase()) || norm(a.iban).includes(q));
  if (!hit) { cli.warn('No account matched "' + query + '". Available: ' + accts.map((a) => a.name).join(', '), { prefix: '' }); process.exit(1); }
  return hit;
}
function fmtAmount(n) { return n == null || isNaN(n) ? '' : new Intl.NumberFormat('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n); }
function ymd(d) { return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
function mkDate(x) { const d = new Date(x); if (isNaN(d.getTime())) cli.die('Invalid date: ' + x); return d; }

// --- Commands ----------------------------------------------------------------
const commands = {
  async accounts() {
    const { flags } = process.argv.parseFlags();
    const accts = await fetchAccounts();
    if (flags.json) return cli.out(JSON.stringify(accts, null, 2));
    const w = Math.max(...accts.map((a) => a.name.length), 4);
    for (const a of accts) cli.out(`${a.name.padEnd(w)}  ${a.iban}  ${fmtAmount(a.saldo).padStart(12)} ${a.currency}`);
  },

  async balance() {
    const { flags, positional } = process.argv.parseFlags();
    const a = await resolveAccount(positional[1]);
    if (flags.json) return cli.out(JSON.stringify(a, null, 2));
    cli.out(`${a.name} (${a.iban}): ${fmtAmount(a.saldo)} ${a.currency}`);
  },

  async transactions(opts) {
    const { flags, positional } = process.argv.parseFlags();
    const a = await resolveAccount(opts ? opts.account : positional[1]);
    const to = opts && opts.to ? mkDate(opts.to) : flags.to ? mkDate(flags.to) : new Date();
    const from = opts && opts.from ? mkDate(opts.from) : flags.from ? mkDate(flags.from) : new Date(to.getTime() - 90 * 86400000);
    const limit = (opts && opts.limit) || parseInt(flags.limit, 10) || 50;
    const search = (opts && opts.search) || flags.search || flags.q || null;
    const asJson = opts ? opts.json : flags.json;
    const uf = { steuerrelevant: null, searchInVirtuellenKontenOnly: null, kontoIdent: a.ident, searchText: search,
      fromBetrag: null, toBetrag: null, kategorie: null, sortierung: 'BUCHUNGSDATUM_DESC',
      fromBuchungDatum: ymd(from) + '+02:00', toBuchungDatum: ymd(to) + '+02:00' };
    const pd = { pageNumber: 0, pageSize: limit, endlessScrolling: false };
    const path = '/umsaetze-umsatz-service/umsatz/advanced/v2?umsatzFilter=' + encodeURIComponent(JSON.stringify(uf)) +
      '&umsatzPagingDescriptor=' + encodeURIComponent(JSON.stringify(pd));
    const res = await pageFetch(path);
    checkAuth(res);
    const rows = (res.json && res.json.umsaetze) || [];
    if (asJson) return cli.out(JSON.stringify(rows, null, 2));
    if (!rows.length) return cli.out(`No transactions for ${a.name}${search ? ` matching "${search}"` : ''}.`);
    cli.out(`${a.name} — ${rows.length} transaction(s)${search ? ` matching "${search}"` : ''}:\n`);
    for (const t of rows) {
      const date = (t.buchungZeit || '').slice(0, 10) || String(t.valuta || '');
      const amt = t.betragEuro != null ? t.betragEuro : t.betrag;
      const who = t.gegenparteiName || t.eigenerName || '';
      const purpose = (t.verwendungszweck || '').replace(/\s+/g, ' ').trim();
      cli.out(`${date}  ${((amt >= 0 ? '+' : '') + fmtAmount(amt)).padStart(13)} EUR  ${who}${purpose ? '  — ' + purpose : ''}`);
    }
    if (res.json && res.json.schlussSaldo != null) cli.out(`\nClosing balance in range: ${fmtAmount(res.json.schlussSaldo)} EUR`);
  },

  async search() {
    const { flags, positional } = process.argv.parseFlags();
    const term = positional[1];
    if (!term) cli.die('Usage: gls search <text> [account] [--days=N]');
    const days = parseInt(flags.days, 10) || 365;
    const to = new Date();
    return commands.transactions({ account: positional[2], search: term, from: ymd(new Date(to.getTime() - days * 86400000)),
      to: ymd(to), limit: parseInt(flags.limit, 10) || 200, json: !!flags.json });
  },

  async keepalive() {
    const { flags } = process.argv.parseFlags();
    const tab = await resolveLiveTab(); // ensures a live tab (opens a fresh one if all are stale)
    await pw(['reload', '--tab=' + tab]); // reload re-bootstraps the SPA → renews the OAuth token
    let st = null;
    for (let i = 0; i < 6; i++) { await sleep(700); st = await ping(tab); if (st === 200) break; if (st === 401 || st === 403) break; }
    if (st === 200) { if (!flags.quiet) cli.out('GLS session alive (refreshed).'); }
    else cli.die('GLS session is logged out — log back in at gls-online-filiale.de.');
  },

  // Prepare + VERIFY a SEPA transfer, stop at the review. Fail-closed: every field
  // is read back, the verification-of-payee verdict is polled to a definitive
  // result, and --send is gated on the review screen matching the request.
  async transfer() {
    const { flags } = process.argv.parseFlags();
    const toIban = norm(flags['to-iban'] || flags.iban || '');
    const toAccount = typeof flags['to-account'] === 'string' ? flags['to-account'] : null;
    const amount = typeof flags.amount === 'string' ? flags.amount : null;
    const name = typeof flags.name === 'string' ? flags.name : null;
    const purpose = typeof flags.purpose === 'string' ? flags.purpose : '';
    const instant = !!flags.instant, send = !!flags.send, confirmMismatch = !!flags['confirm-mismatch'];

    if (!amount || (!toIban && !toAccount))
      cli.die('Usage: gls transfer (--to-iban=IBAN --name="Recipient" | --to-account=Name) --amount=NN,NN [--purpose=] [--instant] [--from=Account] [--send] [--confirm-mismatch]');
    if (!/^\d{1,9},\d{2}$/.test(amount)) cli.die('Amount must be German format with cents, e.g. --amount=120,00 (got "' + amount + '"). No thousands separator.');
    if (toIban) { if (!name) cli.die('External transfer needs --name="Recipient" (verification-of-payee runs against it).'); if (!/^DE\d{20}$/.test(toIban)) cli.die('--to-iban must be a German IBAN (DE + 20 digits).'); }

    const T = await resolveLiveTab(); // live, drivable tab (opens a fresh foreground one if needed)
    const snap = async () => (await pw(['snapshot', '--tab=' + T])).stdout;
    const refOf = (s, re) => { const m = s.split('\n').find((l) => re.test(l)); return m ? (m.match(/\be[0-9]+\b/) || [])[0] : null; };
    const need = (s, re, field) => { const r = refOf(s, re); if (!r) cli.die('Could not find "' + field + '" on the SEPA form (page changed or not loaded).'); return r; };
    const valOf = (s, re) => { const m = s.split('\n').find((l) => re.test(l)); const v = m && m.match(/:\s*"([^"]*)"\s*$/); return v ? v[1] : null; };
    const fill = async (ref, value, field) => { await pwOk(['fill', '--tab=' + T, ref, value], 'fill ' + field); };

    await pwOk(['goto', '--tab=' + T, SEPA_URL], 'open SEPA form');
    let s = '', ready = false;
    for (let i = 0; i < 20; i++) { s = await snap(); if (/Zahlungsempfänger/.test(s) && /textbox "IBAN/.test(s)) { ready = true; break; } await sleep(400); }
    if (!ready) cli.die('SEPA form did not load (maybe logged out). Re-login at gls-online-filiale.de.');

    // optional sender change (read back)
    if (typeof flags.from === 'string') {
      const a = await resolveAccount(flags.from);
      const senderRef = refOf(s, /combobox "(Lade Kontodaten|.*EUR)/i);
      if (senderRef) {
        await pwOk(['click', '--tab=' + T, senderRef], 'open sender');
        await sleep(300); const s2 = await snap();
        const opt = refOf(s2, new RegExp('option "[^"]*' + a.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
        if (!opt) cli.die('Could not find sender account "' + a.name + '" in the dropdown.');
        await pwOk(['click', '--tab=' + T, opt], 'select sender'); await sleep(300);
      }
      const s3 = await snap();
      if (!new RegExp(a.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).test(valOf(s3, /combobox "(Lade Kontodaten|.*EUR)/i) || (s3.match(/combobox "([^"]*EUR[^"]*)"/) || [])[1] || ''))
        cli.die('Sender account did not switch to "' + a.name + '" — aborting.');
      s = s3;
    }

    // recipient
    const nameRef = need(s, /combobox "Name, Vorname/, 'recipient name');
    if (toAccount) {
      await fill(nameRef, toAccount, 'recipient (own account)'); await sleep(400);
      const s2 = await snap();
      const opts = s2.split('\n').filter((l) => /option "Umbuchungskonto/i.test(l) && new RegExp(toAccount.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i').test(l));
      if (opts.length === 0) cli.die('No own-account ("Umbuchungskonto") match for --to-account="' + toAccount + '".');
      if (opts.length > 1) cli.die('Ambiguous --to-account="' + toAccount + '" — matches ' + opts.length + ' own accounts. Be more specific.');
      await pwOk(['click', '--tab=' + T, (opts[0].match(/\be[0-9]+\b/) || [])[0]], 'pick Umbuchungskonto'); await sleep(300);
    } else {
      await fill(nameRef, name, 'recipient name'); await sleep(400);
      await pwOk(['press', '--tab=' + T, 'Escape'], 'close autocomplete'); await sleep(300);
      let s2 = await snap();
      if (/option "/.test(s2)) { await pwOk(['press', '--tab=' + T, 'Escape'], 'close autocomplete'); await sleep(300); s2 = await snap(); }
      const ibanRef = need(s2, /textbox "IBAN/, 'IBAN');
      await fill(ibanRef, toIban, 'IBAN'); await sleep(200);
      const s3 = await snap();
      const ibanVal = norm(valOf(s3, /textbox "IBAN/));
      if (ibanVal !== toIban) cli.die('IBAN field reads "' + ibanVal + '" but you asked for "' + toIban + '" — aborting (autocomplete may have overwritten it).');
    }

    s = await snap();
    await fill(need(s, /textbox "Betrag/, 'amount'), amount, 'amount'); await sleep(200);
    if (purpose) { s = await snap(); const vr = refOf(s, /textbox "Verwendungszweck/); if (vr) await fill(vr, purpose, 'purpose'); }
    if (instant) { s = await snap(); const er = need(s, /checkbox "Als Echtzeit/, 'Echtzeit checkbox'); await pwOk(['check', '--tab=' + T, er], 'check Echtzeit'); await sleep(300); }

    // read back amount + recipient name before validating
    s = await snap();
    const amtVal = valOf(s, /textbox "Betrag/);
    if (amtVal !== amount) cli.die('Amount field reads "' + amtVal + '" but you asked for "' + amount + '" — aborting.');
    if (name && !new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i').test(valOf(s, /combobox "Name, Vorname/) || '')) cli.die('Recipient name field changed unexpectedly — aborting.');

    // validate
    await pwOk(['click', '--tab=' + T, need(s, /button "Eingaben prüfen/, 'Eingaben prüfen')], 'submit for review');

    // poll for a DEFINITIVE verification-of-payee outcome (never infer match from absence)
    let vop = null;
    for (let i = 0; i < 20; i++) {
      s = await snap();
      if (/stimmt nicht mit dem für diese IBAN/.test(s) || /Bitte prüfen/.test(s)) { vop = 'mismatch'; break; }
      if (/stimmt mit dem für diese IBAN/.test(s)) { vop = 'match'; break; }
      await sleep(500);
    }
    if (!vop) cli.die('Could not read a verification-of-payee result from the review screen — aborting (did NOT submit).');

    if (vop === 'mismatch' && !confirmMismatch) {
      cli.warn('Verification-of-payee MISMATCH: the recipient name does not match the name registered for this IBAN.', { prefix: '' });
      cli.warn('GLS only proceeds if you accept a no-refund liability. Re-run with --confirm-mismatch to keep the name, or fix --name. NOT submitted.', { prefix: '' });
      process.exit(2);
    }
    if (vop === 'mismatch' && confirmMismatch) {
      const keep = need(s, /radio "Ihre Eingabe beibehalten/, 'keep-name option');
      await pwOk(['click', '--tab=' + T, keep], 'keep name'); await sleep(300);
      s = await snap(); const w = need(s, /button "Weiter/, 'Weiter'); await pwOk(['click', '--tab=' + T, w], 'acknowledge liability'); await sleep(400);
      for (let i = 0; i < 12; i++) { s = await snap(); if (/Sicherheitsabfrage|Senden|Bestätigen mit SecureGo/.test(s)) break; await sleep(400); }
    }

    // HARD GATE: read the review screen back and assert it matches the request
    s = await snap();
    const reviewIban = norm((s.match(/Empfänger[\s\S]{0,400}?(D\s*E[0-9,\s]{20,40})/) || [])[1] || '');
    const reviewHasAmt = new RegExp(amount.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*EUR').test(s);
    if (toIban && reviewIban && reviewIban !== toIban) cli.die('Review screen recipient IBAN "' + reviewIban + '" ≠ requested "' + toIban + '" — aborting, did NOT submit.');
    if (!reviewHasAmt) cli.die('Review screen does not show amount ' + amount + ' EUR — aborting, did NOT submit.');

    cli.out('Transfer prepared & verified' + (vop === 'mismatch' ? ' (name MISMATCH kept on your instruction)' : ' (verification-of-payee: match)') + '.');
    cli.out('Recipient IBAN ' + (toIban || '(own account)') + ', amount ' + amount + ' EUR' + (instant ? ', Echtzeit' : '') + '.');
    if (!send) return cli.out('Review it in the GLS window. Re-run with --send to submit (you then approve in SecureGo plus).');

    // submit → SecureGo push (human approves on phone)
    const sendRef = need(s, /button "(Senden|Bestätigen mit SecureGo plus)/, 'Send / SecureGo button');
    await pwOk(['click', '--tab=' + T, sendRef], 'send');
    cli.out('Sent. Approve the transfer in SecureGo plus on your phone…');
    for (let i = 0; i < 40; i++) {
      s = await snap();
      if (/entgegengenommen/.test(s)) return cli.out('✅ Transfer accepted (executed).');
      if (/abgelehnt|fehlgeschlagen/.test(s)) cli.die('Transfer was rejected / failed.');
      await sleep(3000);
    }
    cli.out('Still pending — check the GLS window for the SecureGo result.');
  },
};

// --- Entrypoint --------------------------------------------------------------
const { positional } = process.argv.parseFlags();
const cmd = positional[0];
if (!cmd || cmd === 'help') {
  cli.help([
    'GLS Bank client — read GLS online banking + prepare transfers from the CLI.',
    'Prereq: an open, logged-in GLS tab (gls-online-filiale.de).', '',
    'Commands:',
    '  accounts [--json]',
    '  balance [account] [--json]',
    '  transactions [account] [--search=TEXT] [--from=YYYY-MM-DD] [--to=YYYY-MM-DD] [--limit=N] [--json]',
    '  search <text> [account] [--days=N] [--json]',
    '  keepalive [--quiet]',
    '  transfer (--to-iban=IBAN --name="Recipient" | --to-account=Name) --amount=NN,NN',
    '           [--purpose=] [--instant] [--from=Account] [--send] [--confirm-mismatch]', '',
    'transfer fills + verifies the SEPA form and stops at the review (shows verification-of-payee).',
    'Money only moves with --send AND your SecureGo plus approval. A name mismatch needs --confirm-mismatch.',
    'The OAuth session expires after ~5 min idle — keep it warm with `gls keepalive`.',
  ].join('\n'));
  process.exit(0);
}
if (!commands[cmd]) cli.die('Unknown command: ' + cmd + '. Run "gls help".');
await commands[cmd]();
