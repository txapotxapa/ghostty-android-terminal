/* A-SH agent board: herdr agents on every paired computer, live.
 *
 * The Android side (AshNative, see BoardBridge.java) keeps one SSH session
 * per computer running bridge.sh, and relays request/reply lines. Everything
 * else — polling, the activity feed, parsing agent session logs into chat
 * and trace views, reading approval menus off the screen — happens here.
 */
'use strict';

const N = window.AshNative;
const $ = (s, el = document) => el.querySelector(s);

// ---------------------------------------------------------------- utilities

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const enc = new TextEncoder();
const dec = new TextDecoder();
function b64e(s) {
  const b = enc.encode(s); let bin = '';
  for (let i = 0; i < b.length; i += 0x8000) bin += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
  return btoa(bin);
}
function b64d(s) {
  if (!s) return '';
  const bin = atob(s); const b = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i);
  return dec.decode(b);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();
function clock(t) { return new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }); }
function ago(t) {
  const s = Math.max(0, (now() - t) / 1000);
  if (s < 45) return 'just now';
  if (s < 3600) return Math.round(s / 60) + 'm ago';
  if (s < 86400) return Math.round(s / 3600) + 'h ago';
  return Math.round(s / 86400) + 'd ago';
}
function mmss(ms) {
  if (!(ms >= 0)) return '—';
  const s = Math.round(ms / 1000);
  if (s >= 3600) return Math.floor(s / 3600) + 'h' + String(Math.floor(s % 3600 / 60)).padStart(2, '0');
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
}
function compact(n) { return n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e3 ? Math.round(n / 1e3) + 'k' : String(n); }
function plural(n, w) { return n + ' ' + w + (n === 1 ? '' : 's'); }
function basename(p) { p = String(p || '').replace(/\/+$/, ''); return p.slice(p.lastIndexOf('/') + 1) || '/'; }

const I = {
  grow: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7"/></svg>',
  shrink: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 10h-6V4M4 14h6v6M14 10l7-7M10 14l-7 7"/></svg>',
  plus: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 5v14M5 12h14"/></svg>',
  back: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 5l-7 7 7 7"/></svg>',
  close: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 6l12 12M18 6L6 18"/></svg>',
  more: '<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>',
  mic: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg>',
  send: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 19V5M5 12l7-7 7 7"/></svg>',
  laptop: '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="5" width="16" height="11" rx="1.5"/><path d="M2 19h20"/></svg>',
  copy: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a1 1 0 0 1 1-1h10"/></svg>',
  qr: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M8 12h8"/></svg>',
  term: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="3" y="4" width="18" height="16" rx="2.5"/><path d="M7 9l3 3-3 3M13 15h4"/></svg>',
  trash: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg>',
  camera: '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>',
  photo: '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2.5"/><circle cx="9" cy="10" r="2"/><path d="M21 16l-5-5-9 9"/></svg>',
  file: '<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M14 3H6v18h12V7z"/><path d="M14 3v4h4"/></svg>',
  chev: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 6l6 6-6 6"/></svg>',
  check: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 12l5 5 9-10"/></svg>',
  refresh: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/></svg>',
  link: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/></svg>',
};

let toastTimer;
function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}
function haptic() { try { N.haptic(); } catch (e) {} }

// ---------------------------------------------------------- harness metadata

const KINDS = {
  claude: { label: 'Claude Code', short: 'cc', sub: 'Anthropic · chat + trace', bin: 'claude' },
  omp: { label: 'oh-my-pi', short: 'omp', sub: 'coding, subagents', bin: 'omp' },
  pi: { label: 'Pi', short: 'π', sub: 'minimal coding agent', bin: 'pi' },
  hermes: { label: 'Hermes', short: 'H', sub: 'skills, memory', bin: 'hermes' },
  codex: { label: 'Codex', short: 'cx', sub: 'OpenAI coding agent', bin: 'codex' },
  gemini: { label: 'Gemini CLI', short: 'G', sub: 'Google coding agent', bin: 'gemini' },
  opencode: { label: 'OpenCode', short: 'oc', sub: 'open-source agent', bin: 'opencode' },
  amp: { label: 'Amp', short: 'amp', sub: 'Sourcegraph agent', bin: 'amp' },
  copilot: { label: 'Copilot CLI', short: 'gh', sub: 'GitHub agent', bin: 'copilot' },
  qwen: { label: 'Qwen Code', short: 'qw', sub: 'Qwen coding agent', bin: 'qwen' },
  kimi: { label: 'Kimi', short: 'ki', sub: 'Moonshot agent', bin: 'kimi' },
  droid: { label: 'Droid', short: 'dr', sub: 'Factory agent', bin: 'droid' },
  cursor: { label: 'Cursor Agent', short: 'cu', sub: 'Cursor CLI', bin: 'cursor-agent' },
  grok: { label: 'Grok CLI', short: 'gr', sub: 'xAI coding agent', bin: 'grok' },
};
function kindMeta(k) { return KINDS[k] || { label: k || 'shell', short: k ? k.slice(0, 2) : '$', sub: '' }; }
const STATUS = {
  blocked: { label: 'Needs you', rank: 0 },
  working: { label: 'Running', rank: 1 },
  done: { label: 'Done', rank: 2 },
  idle: { label: 'Idle', rank: 3 },
  unknown: { label: 'Starting', rank: 4 },
};

// ------------------------------------------------------------------ the link

const hosts = new Map();   // name -> host state
let reqSeq = 0;

function hostState(name) {
  let h = hosts.get(name);
  if (!h) {
    h = { name, state: 'idle', info: {}, err: '', rtt: 0, pending: new Map(), snap: null, lastSnap: 0, retry: 0, polling: false, repos: null, bins: null, since: 0 };
    hosts.set(name, h);
  }
  return h;
}

function loadHosts() {
  let list = [];
  try { list = JSON.parse(N.hosts()) || []; } catch (e) {}
  const names = new Set(list.map((x) => x.name));
  for (const n of [...hosts.keys()]) if (!names.has(n)) hosts.delete(n);
  for (const x of list) {
    const h = hostState(x.name);
    h.address = x.address || ''; h.user = x.user || '';
    if (h.state === 'idle') connect(h);
  }
}

function connect(h) {
  h.state = 'connecting'; h.err = '';
  try { N.connect(h.name); } catch (e) { h.state = 'offline'; }
  render();
}

function call(hostName, ...args) {
  const h = hosts.get(hostName);
  if (!h || h.state !== 'online') return Promise.reject(new Error(hostName + ' is offline'));
  const id = String(++reqSeq);
  const line = id + '\t' + args.map((a) => 'x' + b64e(String(a))).join('\t');
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => { h.pending.delete(id); reject(new Error('timed out')); }, 25000);
    h.pending.set(id, { resolve, reject, t, t0: performance.now() });
    N.send(hostName, line);
  });
}
async function herdr(host, ...args) {
  const r = await call(host, 'herdr', ...args);
  let j = null;
  try { j = JSON.parse(r.out); } catch (e) {}
  if (j && j.error) { const e = new Error(j.error.message || j.error.code); e.code = j.error.code; throw e; }
  if (r.rc !== 0 && !j) { const e = new Error(r.out.trim().split('\n').pop() || 'herdr failed'); throw e; }
  return j ? j.result : r.out;
}

window.ash = {
  onReply(host, id, rc, b) {
    const h = hosts.get(host); if (!h) return;
    const p = h.pending.get(id); if (!p) return;
    h.pending.delete(id); clearTimeout(p.t);
    p.resolve({ rc, out: b64d(b), ms: performance.now() - p.t0 });
  },
  onLink(host, state, detail) {
    const h = hostState(host);
    const was = h.state;
    if (state === 'online') {
      h.state = 'online'; h.retry = 0; h.err = ''; h.since = now();
      h.info = {};
      for (const l of b64d(detail).split('\n')) { const i = l.indexOf('='); if (i > 0) h.info[l.slice(0, i)] = l.slice(i + 1); }
      if (h.everOnline && was !== 'online') logEvent({ type: 'net', host, text: 'Reconnected to ' + host, sub: 'herdr ' + (h.info.herdr || '?') });
      h.everOnline = true;
      startPolling(h);
      call(host, 'mutes').then((r) => { const m = {}; for (const l of r.out.split('\n')) { const i = l.indexOf('='); if (i > 0) m[l.slice(0, i)] = l.slice(i + 1); } h.mutes = m; }).catch(() => {});
      h.agentStates = null;
    } else if (state === 'connecting') {
      h.state = 'connecting';
    } else {
      for (const p of h.pending.values()) { clearTimeout(p.t); p.reject(new Error(host + ' disconnected')); }
      h.pending.clear();
      h.state = state === 'error' ? 'error' : 'offline';
      h.err = detail || '';
      if (was === 'online') logEvent({ type: 'net', host, text: 'Lost connection to ' + host, sub: 'reconnecting…' });
      scheduleReconnect(h);
    }
    render();
  },
  onDictation(id, text) { const f = dictations.get(id); if (f) { dictations.delete(id); f(text || ''); } },
  updateChanged() { render(); },
  setVisible(v) { ui.visible = !!v; if (v && ui.tab === 'activity') unseenNeeds = 0; if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); if (v) { render(); for (const h of hosts.values()) if (h.state === 'online') kick(h); } },
  setInsets(top, bottom) {
    document.documentElement.style.setProperty('--safe-top', top + 'px');
    document.documentElement.style.setProperty('--safe-bottom', bottom + 'px');
  },
  hostsChanged() { loadHosts(); render(); },
  setTheme(t) { applyTheme(t); render(); },
  back() { return goBack(); },
  onAttached(target, list) {
    atts[target] = (atts[target] || []).concat(list || []);
    renderAtts(target);
    const ta = document.getElementById(target); if (ta) { syncSend(ta); }
  },
  onUploaded(id, path, err) {
    const p = uploads.get(id); if (!p) return;
    uploads.delete(id);
    if (path) p.resolve(path); else p.reject(new Error(err || 'upload failed'));
  },
  share(items, tabs, note) {
    ui.sheet = { type: 'share', items: items || [], tabs: tabs || [], note: note || '' };
    render();
  },
  tab(t) { if (ui.sheet) closeSheet(); setTab(t); },
  openAgent(host, pane) { ui.sheet = null; ui.stack = [{ v: 'board' }]; ui.tab = 'board'; openDetail(host + '|' + pane); },
};

function scheduleReconnect(h) {
  if (h.reconnectTimer) return;
  // "unpaired"/"nossh"/"noherdr" won't fix themselves by retrying fast.
  const hard = /unpaired|nossh|noherdr|badname/.test(h.err);
  const delay = hard ? 60000 : Math.min(30000, 2000 * Math.pow(2, h.retry++));
  h.reconnectTimer = setTimeout(() => { h.reconnectTimer = null; if (hosts.has(h.name)) connect(h); }, delay);
}
function reconnectNow(h) {
  clearTimeout(h.reconnectTimer); h.reconnectTimer = null; h.retry = 0;
  try { N.reconnect(h.name); } catch (e) {}
  connect(h);
}

// ----------------------------------------------------------------- the model

const agents = new Map();  // "host|pane" -> agent
const ui = { stack: [{ v: 'board' }], tab: 'board', sheet: null, visible: true, filter: 'all' };

function startPolling(h) {
  if (h.polling) return;
  h.polling = true;
  (async () => {
    while (h.state === 'online') {
      const t0 = performance.now();
      try {
        const r = await call(h.name, 'herdr', 'api', 'snapshot');
        h.rtt = Math.round(r.ms);
        const j = JSON.parse(r.out);
        if (j.error) throw new Error(j.error.message);
        applySnapshot(h, j.result.snapshot);
      } catch (e) {
        if (h.state !== 'online') break;
      }
      const fast = ui.visible && !document.hidden;
      const wait = (fast ? 1200 : 4000) - (performance.now() - t0);
      await new Promise((res) => { h.wake = res; setTimeout(res, Math.max(150, wait)); });
    }
    h.polling = false;
  })();
}
function kick(h) { if (h.wake) h.wake(); }

function applySnapshot(h, s) {
  h.snap = s; h.lastSnap = now();
  const seen = new Set();
  const ws = new Map((s.workspaces || []).map((w) => [w.workspace_id, w]));
  for (const a of s.agents || []) {
    const key = h.name + '|' + a.pane_id;
    seen.add(key);
    let g = agents.get(key);
    const status = a.agent_status || 'unknown';
    if (!g) {
      g = { key, host: h.name, pane: a.pane_id, firstSeen: now(), tail: null, tailAt: 0, status: null, changed: now(), log: null };
      agents.set(key, g);
      if (h.lastSnap - h.since > 3000) logEvent({ type: 'start', host: h.name, key, text: 'New agent: ' + kindMeta(a.agent).label, sub: tidyPath(h, a.foreground_cwd || a.cwd) });
    }
    const prev = g.status;
    Object.assign(g, {
      kind: a.agent, title: a.terminal_title_stripped || '', cwd: a.foreground_cwd || a.cwd || '',
      rev: a.revision, seq: a.state_change_seq, session: a.agent_session || null,
      workspace: (ws.get(a.workspace_id) || {}).label || '', tab: a.tab_id, focused: a.focused, gone: false,
    });
    if (prev !== status) {
      g.status = status; g.changed = now();
      if (prev) onTransition(g, prev, status);
    }
  }
  for (const [key, g] of agents) {
    if (g.host === h.name && !seen.has(key) && !g.gone) {
      g.gone = true;
      logEvent({ type: 'gone', host: g.host, text: displayName(g) + ' closed', sub: tidyPath(h, g.cwd) });
      setTimeout(() => { if (agents.get(key)?.gone) { agents.delete(key); render(); } }, 4000);
    }
  }
  h.otherPanes = (s.panes || []).filter((p) => !p.agent && !seen.has(h.name + '|' + p.pane_id));
  refreshTails(h);
  render();
}

// Phone notifications mirror herdr's own pings: "needs attention" and
// "finished", each switchable in Settings, muted per agent the way herdr's
// [ui.sound.agents] mutes it (droid by default), and skipped for the agent
// you're looking at.
function notifyPrefs() {
  try { return Object.assign({ needs: true, done: true, mutes: true }, JSON.parse(N.prefs())); } catch (e) { return { needs: true, done: true, mutes: true }; }
}
function mutedByHerdr(g) {
  const m = hosts.get(g.host)?.mutes;
  return !!m && (m[g.kind] === 'off' || m['*'] === 'false');
}
function pingPhone(g, kind, title) {
  const p = notifyPrefs();
  if (!(kind === 'needs' ? p.needs : p.done)) return;
  if (p.mutes && mutedByHerdr(g)) return;
  const watching = !document.hidden && ui.visible && cur().v === 'detail' && cur().key === g.key;
  if (watching) return;
  try { N.notify(g.key, title, where(g) + (hosts.size > 1 ? '' : ' · ' + g.host), kind); } catch (e) {}
}

function onTransition(g, prev, status) {
  const name = displayName(g);
  if (status === 'blocked') {
    logEvent({ type: 'blocked', host: g.host, key: g.key, text: name + ' needs you', sub: where(g) });
    pingPhone(g, 'needs', name + ' needs you');
  } else {
    try { N.clearNotification(g.key); } catch (e) {}
    if ((status === 'done' || status === 'idle') && prev === 'working') {
      logEvent({ type: 'done', host: g.host, key: g.key, text: name + ' finished', sub: where(g) });
      pingPhone(g, 'done', name + ' finished');
    }
    if (status === 'working' && prev === 'blocked') {
      logEvent({ type: 'working', host: g.host, key: g.key, text: name + ' is back to work', sub: where(g) });
    }
  }
  if (status === 'idle' && pendingPrompt(g.key)) {
    const text = pendingPrompt(g.key); pendingPrompt(g.key, null);
    herdr(g.host, 'agent', 'prompt', g.pane, text).catch((e) => toast('Couldn\'t send the first prompt: ' + e.message));
  }
}

// A shell's own title (user@host:path) shows until the agent sets one.
function displayName(g) { return g.title && !/^[\w.-]+@[\w.-]+:/.test(g.title) ? g.title : kindMeta(g.kind).label; }
function tidyPath(h, p) {
  const home = h && h.info && h.info.home;
  if (!p) return '';
  if (home && (p === home || p.startsWith(home + '/'))) p = '~' + p.slice(home.length);
  return p;
}
function repoOf(g) {
  const wt = String(g.cwd || '').match(/\/\.herdr\/worktrees\/([^/]+)\/[^/]+/);
  if (wt) return wt[1] + ' · worktree';
  const h = hosts.get(g.host); const p = tidyPath(h, g.cwd); return p === '~' ? '~' : basename(p);
}
function where(g) {
  const parts = [repoOf(g)];
  if (hosts.size > 1) parts.push(g.host);
  return parts.join(' · ');
}

// A first prompt waiting for the agent to clear a startup question (folder
// trust, login). Kept in storage so an app restart doesn't drop it.
function pendingPrompt(key, set) {
  let m = {};
  try { m = JSON.parse(localStorage.getItem('ash.pending.v1') || '{}'); } catch (e) {}
  if (set === undefined) return m[key] || '';
  if (set) m[key] = set; else delete m[key];
  try { localStorage.setItem('ash.pending.v1', JSON.stringify(m)); } catch (e) {}
  return set;
}

// ------------------------------------------------------------- card tails

const LOG_KINDS = { claude: 1, omp: 1 };
function hasLog(g) { return !!(g.session && LOG_KINDS[g.kind] && g.session.value); }

async function refreshTails(h) {
  for (const g of agents.values()) {
    if (g.host !== h.name || g.gone || g.tailBusy) continue;
    const age = now() - g.tailAt;
    const live = g.status === 'working' || g.status === 'blocked';
    const changed = g.tailKey !== g.rev + ':' + g.seq;
    if (!(g.tail === null || (live && age > 2500) || (changed && age > 1500))) continue;
    g.tailBusy = true;
    fetchTail(g).then((t) => { g.tail = t; }).catch(() => {}).finally(() => {
      g.tailBusy = false; g.tailAt = now(); g.tailKey = g.rev + ':' + g.seq; render();
    });
  }
}

async function fetchTail(g) {
  if (g.status === 'blocked') {
    const scr = await screen(g, 'visible', false);
    const m = parseMenu(scr);
    if (m && m.question) {
      if (m.detailKind && m.detail) return [{ k: m.detailKind, v: m.detail }, { k: '?', v: m.question }];
      const choices = (m.options || []).filter((o) => !/^(type something|chat about this)/i.test(o.text)).map((o) => o.text).join(' · ');
      return [{ k: '?', v: m.question }, ...(choices ? [{ k: '', v: choices }] : [])];
    }
  }
  if (hasLog(g)) {
    const entries = await sessionLog(g, 60000);
    const out = [];
    for (let i = entries.length - 1; i >= 0 && out.length < 2; i--) {
      const e = entries[i];
      if (e.kind === 'tool') out.unshift({ k: e.verb, v: e.summary });
      else if (e.kind === 'text') out.unshift({ k: '›', v: firstLine(e.text).replace(/[`*]/g, '') });
      else if (e.kind === 'user' && out.length === 0) out.unshift({ k: 'you', v: firstLine(e.text) });
      if (e.kind === 'user') break;
    }
    if (out.length) return out;
  }
  const scr = await screen(g, 'recent', false, 60);
  return cleanTail(scr).slice(-2).map((v) => ({ k: '', v }));
}
function firstLine(s) { return String(s || '').split('\n').map((x) => x.trim()).find((x) => x) || ''; }

async function screen(g, source, ansi, lines) {
  const args = ['agent', 'read', g.pane, '--source', source];
  if (lines) args.push('--lines', String(lines));
  if (ansi) args.push('--format', 'ansi');
  const r = await call(g.host, 'herdr', ...args);
  if (r.rc !== 0) { let j; try { j = JSON.parse(r.out); } catch (e) {} throw new Error(j?.error?.message || 'read failed'); }
  return r.out;
}

const RULE = /^[\s─━═╌┄┈╍╴╶╸╺┅┉_\-=~·•▪]+$/;
const BOXY = /^[\s│┃║╭╮╯╰┌┐└┘├┤┬┴┼╔╗╚╝▏▕]*$/;
const FOOTER = /(\? for shortcuts|esc to interrupt|shift\+tab|auto mode on|accept edits|bypass permissions|Update available|ctrl\+[a-z] to|\(disable recaps|tokens? left|context left|Run: omp update|New version .* is available)/i;
function cleanTail(text) {
  const out = [];
  for (let l of String(text).split('\n')) {
    l = l.replace(/\s+$/, '').replace(/^[│┃║]\s?/, '').replace(/\s?[│┃║]$/, '');
    const t = l.trim();
    if (!t || RULE.test(t) || BOXY.test(t) || FOOTER.test(t)) continue;
    if (/^[>❯›]\s*$/.test(t)) continue;           // empty input prompt
    out.push(t.length > 160 ? t.slice(0, 160) : t);
  }
  return out;
}

// ------------------------------------------------- agent session log parsing

async function sessionLog(g, bytes) {
  const r = await call(g.host, 'log', g.session.kind, g.session.value, String(bytes || 400000));
  if (r.rc !== 0) throw new Error('no session log');
  return parseLog(g, r.out);
}

function parseLog(g, text) {
  const lines = text.split('\n');
  const out = [];
  const h = hosts.get(g.host);
  const rel = (p) => {
    if (!p) return '';
    p = String(p);
    if (g.cwd && p.startsWith(g.cwd + '/')) return p.slice(g.cwd.length + 1);
    return tidyPath(h, p);
  };
  const results = new Map();
  // Messages sent while Claude Code is busy: logged as queue operations
  // (enqueue, then dequeue / remove / popAll) rather than user messages, and,
  // when the agent takes one in mid-turn, as a queued_command attachment.
  const queued = [];
  for (const l of lines) {
    if (!l || l[0] !== '{') continue;
    let o; try { o = JSON.parse(l); } catch (e) { continue; }
    const t = Date.parse(o.timestamp || '') || 0;
    if (g.kind === 'claude') {
      if (o.type === 'queue-operation') {
        const c = typeof o.content === 'string' ? o.content.trim() : '';
        if (o.operation === 'enqueue') { if (c) queued.push({ text: c, t }); }
        else if (c) { const i = queued.findIndex((q) => q.text === c); if (i >= 0) queued.splice(i, 1); }
        else queued.length = 0;
        continue;
      }
      if (o.type === 'attachment') {
        const a = o.attachment || {};
        if (a.type === 'queued_command' && a.commandMode === 'prompt' && (a.humanTurn || a.origin?.kind === 'human')) {
          pushUser(out, a.prompt, Date.parse(a.timestamp || '') || t);
        }
        continue;
      }
      if (o.isMeta || o.isSidechain) continue;
      const m = o.message; if (!m) continue;
      if (o.type === 'user') {
        if (typeof m.content === 'string') { pushUser(out, m.content, t); continue; }
        for (const b of m.content || []) {
          if (b.type === 'text') pushUser(out, b.text, t);
          else if (b.type === 'tool_result') results.set(b.tool_use_id, { text: flat(b.content), err: !!b.is_error });
        }
      } else if (o.type === 'assistant') {
        for (const b of m.content || []) {
          if (b.type === 'text' && b.text.trim()) out.push({ kind: 'text', text: b.text, t });
          else if (b.type === 'tool_use') out.push(toolEntry(b.name, b.input || {}, b.id, t, rel));
        }
        if (m.usage) out.push({ kind: 'usage', n: (m.usage.output_tokens || 0), t });
      }
    } else {
      if (o.type !== 'message') continue;
      const m = o.message || {};
      if (m.role === 'user') {
        for (const b of (typeof m.content === 'string' ? [{ type: 'text', text: m.content }] : m.content || [])) if (b.type === 'text') pushUser(out, b.text, t);
      } else if (m.role === 'assistant') {
        for (const b of m.content || []) {
          if (b.type === 'text' && b.text.trim()) out.push({ kind: 'text', text: b.text, t });
          else if (b.type === 'toolCall') out.push(toolEntry(b.name, b.arguments || {}, b.id, t, rel, b.intent));
        }
        if (m.usage) out.push({ kind: 'usage', n: (m.usage.output || m.usage.output_tokens || 0), t });
      } else if (m.role === 'toolResult') {
        results.set(m.toolCallId, { text: flat(m.content), err: !!m.isError });
      }
    }
  }
  for (const e of out) if (e.kind === 'tool' && results.has(e.id)) { const r = results.get(e.id); e.result = r.text; e.err = r.err; }
  out.queued = queued;
  return out;
}
function flat(c) {
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map((b) => (typeof b === 'string' ? b : b.text || '')).join('\n');
  return '';
}
function pushUser(out, text, t) {
  text = String(text || '');
  const s = text.trim();
  if (!s || /^<(command-|local-command|system-reminder|user-memory|bash-|task-notification)/.test(s) || s.startsWith('[Request interrupted')) return;
  out.push({ kind: 'user', text: s, t });
}
function toolEntry(name, input, id, t, rel, intent) {
  const n = String(name || '').toLowerCase();
  const path = input.file_path || input.path || input.notebook_path || '';
  let kind = 'read', verb = n, summary = '', delta = '';
  if (/^(bash|shell|exec|run|terminal)/.test(n)) { kind = 'run'; verb = 'bash'; summary = input.command || input.cmd || ''; }
  else if (/^(edit|multiedit|str_replace|apply_patch|patch)/.test(n)) {
    kind = 'edit'; verb = 'edit'; summary = rel(path);
    const a = lines(input.old_string ?? input.oldText ?? ''), b = lines(input.new_string ?? input.newText ?? '');
    if (input.edits && Array.isArray(input.edits)) {
      let p = 0, m = 0; for (const e of input.edits) { m += lines(e.old_string ?? e.oldText ?? ''); p += lines(e.new_string ?? e.newText ?? ''); }
      delta = '+' + p + ' −' + m;
    } else if (a || b) delta = '+' + b + ' −' + a;
  }
  else if (/^(write|create|notebookedit)/.test(n)) { kind = 'edit'; verb = 'write'; summary = rel(path); const c = input.content ?? input.text; if (c != null) delta = '+' + lines(c); }
  else if (/^(read|view|cat)/.test(n)) { verb = 'read'; summary = rel(path) || input.url || ''; }
  else if (/^(grep|search|rg|find|glob|ls|list)/.test(n)) { verb = n.startsWith('glob') ? 'glob' : n.startsWith('ls') || n.startsWith('list') ? 'ls' : 'grep'; summary = [input.pattern || input.query || '', rel(input.path || '')].filter(Boolean).join('  '); }
  else if (/^(webfetch|fetch|browse)/.test(n)) { verb = 'fetch'; summary = input.url || ''; }
  else if (/^(websearch|web_search)/.test(n)) { verb = 'search'; summary = input.query || ''; }
  else if (/^(todowrite|todo|plan|task|agent|subagent|exitplanmode)/.test(n)) { kind = 'plan'; verb = /todo/.test(n) ? 'todo' : /plan/.test(n) ? 'plan' : 'agent'; summary = input.description || input.prompt?.slice(0, 120) || (Array.isArray(input.todos) ? plural(input.todos.length, 'item') : ''); }
  else if (/^(askuserquestion|ask)/.test(n)) { kind = 'ask'; verb = 'ask'; summary = input.question || (input.questions && input.questions[0]?.question) || ''; }
  else { summary = intent || Object.values(input).find((v) => typeof v === 'string') || ''; }
  summary = String(summary).replace(/\s+/g, ' ').trim();
  if (summary.length > 140) summary = summary.slice(0, 140) + '…';
  return { kind: 'tool', cls: kind, verb, summary, delta, id, t, path: kind === 'edit' ? path : '' };
}
function lines(s) { s = String(s || ''); return s ? s.split('\n').length : 0; }

// ------------------------------------------------- approval menu parsing

// Reads an agent's question and its choices off the visible screen. Works
// for numbered menus (Claude Code permission prompts), plain cursor menus
// (folder trust) and [y/n] prompts.
function parseMenu(text) {
  const L = String(text).split('\n').map((l) => l.replace(/\s+$/, ''));
  const cursorRe = /^(\s*)[❯›▸➜→>]\s+(\S.*)$/;
  let ci = -1;
  for (let i = L.length - 1; i >= 0 && i >= L.length - 40; i--) if (cursorRe.test(L[i]) && !/^\s*[>❯]\s*$/.test(L[i])) { ci = i; break; }
  const res = { options: [], selected: 0, question: '', detail: '', context: [], hint: '', yn: false };
  if (ci >= 0) {
    const ind = (l) => l.match(/^\s*/)[0].length;
    const isOpt = (l) => l.trim() && !RULE.test(l.trim()) && (cursorRe.test(l) || /^\s+(\d+[.)]\s+)?\S/.test(l));
    const baseInd = ind(L[ci]);
    let a = ci, b = ci;
    while (a - 1 >= 0 && isOpt(L[a - 1]) && Math.abs(ind(L[a - 1]) - baseInd) <= 2 && !/[?:]$/.test(L[a - 1].trim())) a--;
    const numbered = (l) => /^\s*(?:[❯›▸➜→>]\s+)?\d+[.)]\s/.test(l);
    for (;;) {
      if (b + 1 < L.length && isOpt(L[b + 1]) && ind(L[b + 1]) >= baseInd && !/(Esc|Enter) to /i.test(L[b + 1])) { b++; continue; }
      // A numbered menu can carry on below a rule (Claude puts "Chat about this" there).
      if (b + 2 < L.length && RULE.test(L[b + 1].trim()) && numbered(L[b + 2]) && numbered(L[ci])) { b += 2; continue; }
      break;
    }
    // A continuation line (deeper indent, no number) belongs to the option above.
    for (let i = a; i <= b; i++) {
      const l = L[i];
      if (RULE.test(l.trim())) continue;
      const m = l.match(cursorRe);
      const body = (m ? m[2] : l.trim());
      const num = body.match(/^(\d+)[.)]\s+(.*)$/);
      if (!m && !num && res.options.length && ind(l) > baseInd + 2) { const o = res.options[res.options.length - 1]; o.desc = (o.desc ? o.desc + ' ' : '') + body; continue; }
      res.options.push({ text: (num ? num[2] : body).trim(), num: num ? num[1] : null });
      if (m) res.selected = res.options.length - 1;
    }
    for (let i = b + 1; i < Math.min(L.length, b + 4); i++) if (/(Esc|Enter|Tab) to /i.test(L[i])) { res.hint = L[i].trim(); break; }
    // The question: the nearest sentence ending in "?" above the menu
    // (prompts often wrap it, or put a link line just above the choices),
    // else the nearest non-empty line.
    let q = a - 1;
    while (q >= 0 && (!L[q].trim() || RULE.test(L[q].trim()))) q--;
    if (q >= 0) res.question = L[q].trim();
    for (let i = a - 1, para = []; i >= Math.max(0, a - 16); i--) {
      const t = L[i].trim();
      if (t && !RULE.test(t)) { para.unshift(t); if (i > 0 && L[i - 1].trim() && !RULE.test(L[i - 1].trim())) continue; }
      const txt = para.join(' ');
      const qi = txt.indexOf('?');
      if (qi >= 0) {
        // Sentence start: after the last ". " / "! " / ": " before the "?"
        // (a dot inside "alpha.txt" isn't a sentence end).
        let st = 0; const re = /[.!?:]\s+/g; let mm;
        while ((mm = re.exec(txt)) && mm.index < qi) st = mm.index + mm[0].length;
        res.question = txt.slice(st, qi + 1).trim(); q = i; break;
      }
      para = [];
      if (t && RULE.test(t) && /─|━|═/.test(t)) break;
    }
    // Context: back up to the rule that opens the prompt box.
    let top = q - 1;
    while (top >= 0 && !(RULE.test(L[top].trim()) && L[top].trim().length > 20 && /─|━|═/.test(L[top]))) top--;
    res.context = L.slice(top + 1, Math.max(top + 1, q)).filter((l) => !/^\s*Tip:/.test(l));
    while (res.context.length && !res.context[0].trim()) res.context.shift();
    while (res.context.length && !res.context[res.context.length - 1].trim()) res.context.pop();
    // The most concrete line: the last one inside the box (a command, a path).
    const real = res.context.map((l) => l.trim()).filter((l) => l && !RULE.test(l));
    res.detail = real[real.length - 1] || '';
    const head = (real[0] || '').toLowerCase();
    res.detailKind = /^(bash|shell|run)\b|command$/.test(head) ? 'bash' : /^(edit|write|create|update|apply)\b/.test(head) ? 'edit' : /^(fetch|web)/.test(head) ? 'fetch' : '';
    res.tipLines = new Set(L.map((l, i) => (/^\s*Tip:/.test(l) ? i : -1)).filter((i) => i >= 0));
    res.ctxStart = top + 1; res.ctxEnd = Math.max(top + 1, q);
    if (res.options.length >= 2) return res;
  }
  // [y/n] style
  const tail = cleanTail(L.slice(-12).join('\n'));
  const yn = tail.slice().reverse().find((l) => /\[(y\/n|Y\/n|y\/N)\]|\(y\/n\)/.test(l));
  if (yn) return { options: [{ text: 'Yes', key: 'y' }, { text: 'No', key: 'n' }], selected: 0, question: yn, detail: '', context: tail.slice(-6, -1), hint: '', yn: true };
  if (tail.length) return { options: [], selected: 0, question: tail[tail.length - 1], detail: tail.length > 1 ? tail[tail.length - 2] : '', context: tail.slice(-8), hint: '', yn: false };
  return null;
}

function keysFor(menu, i) {
  const o = menu.options[i];
  if (o.key) return [o.key, 'enter'];
  const d = i - menu.selected;
  const keys = [];
  for (let k = 0; k < Math.abs(d); k++) keys.push(d > 0 ? 'down' : 'up');
  keys.push('enter');
  return keys;
}
function isNo(t) { return /^(no\b|reject|deny|cancel|exit\b)/i.test(t); }

// ------------------------------------------------------------------ ANSI

// The terminal theme's 16 ANSI colors and default fg/bg (AshNative.theme()).
let PAL = ['#1A1B1E', '#F07A6A', '#7FD1B9', '#E6B450', '#8AB4F8', '#B9A3F2', '#7FC8D9', '#C9CAC6',
  '#6C6E72', '#F3A397', '#A8E3D1', '#F0C674', '#AFCBFA', '#D0C2F7', '#A6DDE8', '#ECECEA'];
let INK = { fg: '#ECECEA', bg: '#121314' };
// The app theme: CSS variables for every token in board.css, plus the terminal palette.
function applyTheme(t) {
  if (!t || !t.vars) return;
  const r = document.documentElement.style;
  for (const k in t.vars) r.setProperty('--' + k, t.vars[k]);
  r.colorScheme = t.light ? 'light' : 'dark';
  if (t.ansi && t.ansi.length === 16) PAL = t.ansi;
  if (t.fg) INK = { fg: t.fg, bg: t.bg };
}
function c256(n) {
  if (n < 16) return PAL[n];
  if (n >= 232) { const v = 8 + (n - 232) * 10; return `rgb(${v},${v},${v})`; }
  n -= 16; const s = [0, 95, 135, 175, 215, 255];
  return `rgb(${s[Math.floor(n / 36)]},${s[Math.floor(n / 6) % 6]},${s[n % 6]})`;
}
function ansiHtml(text) {
  let out = '', st = {}, open = false;
  const flush = () => { if (open) { out += '</span>'; open = false; } };
  const style = () => {
    const css = [];
    let fg = st.fg, bg = st.bg;
    if (st.inv) { const x = fg; fg = bg || INK.fg; bg = x || INK.bg; }
    if (fg) css.push('color:' + fg); if (bg) css.push('background:' + bg);
    const cls = [st.b && 'a-b', st.d && 'a-d', st.i && 'a-i', st.u && 'a-u'].filter(Boolean).join(' ');
    if (css.length || cls) { out += `<span${cls ? ` class="${cls}"` : ''}${css.length ? ` style="${css.join(';')}"` : ''}>`; open = true; }
  };
  const re = /\x1b\[([0-9;:]*)m|\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][A-Za-z0-9]|[\x00-\x08\x0b-\x1f]/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    out += esc(text.slice(last, m.index)); last = re.lastIndex;
    if (m[1] === undefined) continue;
    const p = m[1] === '' ? [0] : m[1].split(/[;:]/).map(Number);
    for (let i = 0; i < p.length; i++) {
      const v = p[i];
      if (v === 0) st = {}; else if (v === 1) st.b = 1; else if (v === 2) st.d = 1; else if (v === 3) st.i = 1; else if (v === 4) st.u = 1;
      else if (v === 7) st.inv = 1; else if (v === 22) { st.b = 0; st.d = 0; } else if (v === 23) st.i = 0; else if (v === 24) st.u = 0; else if (v === 27) st.inv = 0;
      else if (v >= 30 && v <= 37) st.fg = PAL[v - 30]; else if (v >= 90 && v <= 97) st.fg = PAL[v - 82]; else if (v === 39) st.fg = null;
      else if (v >= 40 && v <= 47) st.bg = PAL[v - 40]; else if (v >= 100 && v <= 107) st.bg = PAL[v - 92]; else if (v === 49) st.bg = null;
      else if ((v === 38 || v === 48) && p[i + 1] === 5) { st[v === 38 ? 'fg' : 'bg'] = c256(p[i + 2]); i += 2; }
      else if ((v === 38 || v === 48) && p[i + 1] === 2) { st[v === 38 ? 'fg' : 'bg'] = `rgb(${p[i + 2]},${p[i + 3]},${p[i + 4]})`; i += 4; }
    }
    flush(); style();
  }
  out += esc(text.slice(last)); flush();
  return out;
}

// ------------------------------------------------------------------ markdown

function md(src) {
  const blocks = String(src).replace(/\r/g, '').split(/\n```/);
  let html = '';
  blocks.forEach((blk, i) => {
    if (i % 2 === 1) { const nl = blk.indexOf('\n'); html += '<pre>' + esc(blk.slice(nl + 1).replace(/```\s*$/, '')) + '</pre>'; return; }
    for (const para of blk.split(/\n{2,}/)) {
      const ls = para.split('\n').filter((l) => l.trim());
      if (!ls.length) continue;
      if (ls.every((l) => /^\s*([-*•]|\d+[.)])\s+/.test(l))) {
        const ol = /^\s*\d/.test(ls[0]);
        html += (ol ? '<ol>' : '<ul>') + ls.map((l) => '<li>' + inline(l.replace(/^\s*([-*•]|\d+[.)])\s+/, '')) + '</li>').join('') + (ol ? '</ol>' : '</ul>');
      } else {
        html += '<p>' + ls.map((l) => inline(l.replace(/^#+\s+/, ''))).join('<br>') + '</p>';
      }
    }
  });
  return html;
}
function inline(s) {
  return esc(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank">$1</a>');
}

// ---------------------------------------------------------------- activity

const ACT_KEY = 'ash.activity.v1';
let activity = [];
try { activity = JSON.parse(localStorage.getItem(ACT_KEY) || '[]'); } catch (e) { activity = []; }
let unseenNeeds = 0;
function logEvent(ev) {
  ev.t = now();
  activity.unshift(ev);
  if (activity.length > 300) activity.length = 300;
  try { localStorage.setItem(ACT_KEY, JSON.stringify(activity)); } catch (e) {}
  if (ev.type === 'blocked' && (ui.tab !== 'activity' || !ui.visible)) unseenNeeds++;
  return ev;
}

// ------------------------------------------------- agent favorites

function store(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch (e) { return fallback; } }
function save(key, v) { try { localStorage.setItem(key, JSON.stringify(v)); } catch (e) {} }
const favs = () => store('ash.favs.v1', []);
function toggleFav(kind) { const f = favs(); const i = f.indexOf(kind); if (i >= 0) f.splice(i, 1); else f.push(kind); save('ash.favs.v1', f); }

// ---------------------------------------------------------------- dictation

const dictations = new Map();
function dictate(cb) {
  const id = String(++reqSeq);
  dictations.set(id, cb);
  try { N.dictate(id); } catch (e) { dictations.delete(id); toast('Voice input isn\'t available'); }
}

// ------------------------------------------------------------- attachments
//
// Photos, screenshots and files for an agent: the composer's + opens Camera /
// Photos / Files (native pickers), the picks come back as thumbnails above the
// text, and sending uploads each to ~/.cache/a-sh/inbox on the agent's
// computer and puts the paths in the prompt, where the agent reads them.

const atts = {};            // composer textarea id -> [{token, name, image, thumb}]
const uploads = new Map();  // request id -> {resolve, reject}
let upSeq = 0;

// One thumbnail: the image, or a file icon with its name; removable in a composer.
function attHtml(a, target) {
  const face = a.thumb ? `<img src="${a.thumb}" alt="">`
    : `<div class="fn">${I.file}<span>${esc(a.name.replace(/^\d{8}-\d{6}-\d+-/, ''))}</span></div>`;
  const x = target ? `<button data-act="att-x" data-target="${esc(target)}" data-token="${esc(a.token)}" aria-label="Remove">${I.close}</button>` : '';
  return `<div class="att">${face}${x}</div>`;
}
function renderAtts(target) {
  const el = document.getElementById('atts-' + target); if (!el) return;
  const html = (atts[target] || []).map((a) => attHtml(a, target)).join('');
  if (el.innerHTML !== html) el.innerHTML = html;
}
function clearAtts(target, drop) {
  for (const a of atts[target] || []) if (drop) try { N.dropAttachment(a.token); } catch (e) {}
  atts[target] = [];
  renderAtts(target);
}
function uploadAtt(host, token) {
  return new Promise((resolve, reject) => {
    const id = 'j' + (++upSeq);
    uploads.set(id, { resolve, reject });
    try { N.upload(host, token, id); } catch (e) { uploads.delete(id); reject(e); }
  });
}
// The prompt an agent gets: the message, then where the attachments are.
function withPaths(text, paths, items) {
  const imgs = items.every((a) => a.image);
  const what = (imgs ? 'image' : 'file') + (paths.length > 1 ? 's' : '');
  const msg = text || `Take a look at the attached ${what}.`;
  return `${msg} (attached ${what}: ${paths.join(' ')})`;
}
async function sendToAgent(g, text, items) {
  // Shown in the chat straight away, until the session log has it.
  const m = { text: text || (items.length ? 'Attachment…' : ''), state: 'sending', at: now() };
  (g.outbox = g.outbox || []).push(m); render();
  try {
    const paths = [];
    for (const a of items) paths.push(await uploadAtt(g.host, a.token));
    m.text = items.length ? withPaths(text, paths, items) : text;
    const busy = g.status === 'working' || g.status === 'blocked';
    await herdr(g.host, 'agent', 'prompt', g.pane, m.text);
    m.state = busy ? 'queued' : 'sent'; m.at = now(); render();
    for (const a of items) try { N.dropAttachment(a.token); } catch (e) {}
    return m.state;
  } catch (e) {
    g.outbox = g.outbox.filter((x) => x !== m); render();
    throw e;
  }
}

// ------------------------------------------------------------------ routing

const cur = () => ui.stack[ui.stack.length - 1];
function push(v) { ui.stack.push(v); ui.anim = 'enter'; render(); }
function goBack() {
  if (ui.sheet) { closeSheet(); return true; }
  if (ui.stack.length > 1) { ui.stack.pop(); ui.anim = null; render(); return true; }
  return false;   // Home ↔ Activity ↔ Terminal is the app's back history (MainActivity)
}
function setTab(t) {
  ui.tab = t; ui.stack = [{ v: t }]; ui.anim = null;
  if (t === 'activity') unseenNeeds = 0;
  render();
}
function openDetail(key) {
  const g = agents.get(key); if (!g) return;
  clearAtts('steer', true);
  const tab = hasLog(g) ? (g.status === 'blocked' ? 'trace' : 'chat') : 'screen';
  push({ v: 'detail', key, tab });
  loadDetail(g, true);
}

// ------------------------------------------------------------------ render

let renderQueued = false;
function render() {
  // Straight away, not on the next frame: the badges matter most while the
  // board is hidden behind the terminal, where frames don't come.
  pushNav();
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => { renderQueued = false; renderNow(); });
}

function renderNow() {
  const app = $('#app');
  const v = cur();
  const html = v.v === 'board' ? viewBoard() : v.v === 'detail' ? viewDetail(v) : v.v === 'new' ? viewNew(v)
    : v.v === 'activity' ? viewActivity() : v.v === 'computers' ? viewComputers() : '';
  const id = v.v + (v.key || '');
  let el = app.firstElementChild;
  if (!el || el.dataset.id !== id) {
    app.innerHTML = `<section class="view${ui.anim ? ' ' + ui.anim : ''}" data-id="${esc(id)}"></section>`;
    el = app.firstElementChild; ui.anim = null;
    morph(el, html, true);
  } else {
    morph(el, html, false);
  }
  renderSheet();
  if (v.slide) { const d = $('#dscroll'); if (d) d.classList.add(v.slide > 0 ? 'from-right' : 'from-left'); v.slide = 0; }
  renderAtts('steer');
  renderAtts('newprompt');
}

// Minimal keyed DOM patching: regions marked data-k are replaced only when
// their HTML changed, so scroll positions, focus and typed text survive the
// once-a-second refresh.
function morph(el, html, fresh) {
  if (fresh) { el.innerHTML = html; return; }
  const tpl = document.createElement('template'); tpl.innerHTML = html;
  const next = tpl.content;
  for (const n of next.querySelectorAll('[data-k]')) {
    const old = el.querySelector(`[data-k="${CSS.escape(n.dataset.k)}"]`);
    if (!old) { el.innerHTML = html; return; }
    if (n.dataset.keep !== undefined) continue;
    if (old.innerHTML !== n.innerHTML) old.innerHTML = n.innerHTML;
    for (const a of n.getAttributeNames()) if (a !== 'data-k' && old.getAttribute(a) !== n.getAttribute(a)) old.setAttribute(a, n.getAttribute(a));
  }
}

// The nav bar is native (NavBarView, under the board and the terminal
// alike); the page reports its root and badge counts whenever they change.
let navSent = '';
function pushNav() {
  const needs = [...agents.values()].filter((g) => g.status === 'blocked' && !g.gone).length;
  // Whether back has somewhere to go inside the board (a sheet, a sub-screen);
  // moving between Home, Activity and the terminal is the app's history.
  const canBack = !!ui.sheet || ui.stack.length > 1;
  const s = [ui.tab, needs, unseenNeeds, canBack].join('|');
  if (s === navSent) return;
  navSent = s;
  try { N.nav(ui.tab, needs, unseenNeeds, canBack); } catch (e) {}
}

// ---- board

function sortedAgents() {
  return [...agents.values()].sort((a, b) => (STATUS[a.status]?.rank ?? 9) - (STATUS[b.status]?.rank ?? 9) || (b.changed - a.changed));
}

function viewBoard() {
  const list = sortedAgents();
  const hs = [...hosts.values()];
  const needs = list.filter((g) => g.status === 'blocked' && !g.gone).length;
  let sub;
  if (!hs.length) sub = '<span class="live off"></span>no computers paired';
  else if (hs.length === 1) {
    const h = hs[0];
    const panes = h.snap ? (h.snap.panes || []).length : 0;
    sub = `<span class="live ${h.state === 'online' ? '' : h.state === 'connecting' ? 'warn' : 'off'}"></span>` + esc([h.name, h.state === 'online' ? 'tailscale' : h.state === 'connecting' ? 'connecting…' : 'offline',
      h.state === 'online' ? plural(panes, 'pane') : '', needs ? needs + (needs === 1 ? ' needs you' : ' need you') : ''].filter(Boolean).join(' · ')) + ' ›';
  } else {
    const on = hs.filter((h) => h.state === 'online').length;
    sub = `<span class="live ${on === hs.length ? '' : on ? 'warn' : 'off'}"></span>` + esc([on + '/' + hs.length + ' computers online', plural(list.length, 'agent'), needs ? needs + (needs === 1 ? ' needs you' : ' need you') : ''].filter(Boolean).join(' · ')) + ' ›';
  }
  let body;
  if (!hs.length) body = emptyPair();
  else if (!list.length) {
    const connecting = hs.some((h) => h.state === 'connecting' || (h.state === 'online' && !h.snap));
    const offline = hs.every((h) => h.state === 'offline' || h.state === 'error');
    if (connecting) body = '<div class="cards">' + '<div class="skel"></div>'.repeat(3) + '</div>';
    else if (offline) body = `<div class="empty"><h3>Can't reach ${hs.length === 1 ? esc(hs[0].name) : 'your computers'}</h3><p>${esc(offlineReason(hs[0]))}</p><div class="row"><button class="btn" data-act="go" data-v="computers">${I.laptop} Computers</button><button class="btn primary" data-act="reconnect-all">${I.refresh} Retry</button></div></div>`;
    else body = `<div class="empty"><h3>No agents running</h3><p>Start one here, or run an agent in herdr on your computer and it shows up on this board.</p><div class="row"><button class="btn primary" data-act="new">${I.plus} New agent</button></div></div>`;
  } else {
    body = '<div class="cards">' + list.map(card).join('') + otherPanes() + '</div>';
  }
  return `<header class="top"><div class="top-row"><div class="h1">Agents</div>
      <button class="fab" data-act="new" aria-label="New agent">${I.plus}</button></div>
      <button class="sub" data-act="go" data-v="computers" data-k="sub">${sub}</button></header>
    <div data-k="upd">${updateBanner()}</div>
    <main class="scroll" data-k="board">${body}</main>`;
}

function updateBanner() {
  let u = null;
  try { u = JSON.parse(N.updateInfo()); } catch (e) {}
  if (!u || !/available|ready|downloading|failed|permission/.test(u.state)) return '';
  const txt = u.state === 'downloading' ? `Downloading A-SH ${esc(u.version)} · ${u.percent || 0}%`
    : u.state === 'failed' ? `Update didn't finish: ${esc(u.error || 'tap for details')}`
    : `A-SH ${esc(u.version)} is ${u.state === 'ready' ? 'ready to install' : 'available'}`;
  return `<button class="upd ${u.state}" data-act="open-update"><span>${txt}</span><b>${u.state === 'failed' ? 'Details' : u.state === 'downloading' ? 'View' : u.state === 'ready' ? 'Install' : 'Update'} ›</b></button>`;
}

function card(g) {
  const k = kindMeta(g.kind);
  const st = g.gone ? 'idle' : g.status || 'unknown';
  const tail = g.tail === null ? '<div class="tail empty">…</div>'
    : g.tail.length ? `<div class="tail">${g.tail.map((l) => `<div class="ln">${l.k ? `<span class="k">${esc(l.k.padEnd(5))}</span> ` : ''}${esc(l.v)}</div>`).join('')}</div>`
      : '<div class="tail empty">no output yet</div>';
  const pill = g.gone ? 'Closed' : STATUS[st]?.label || st;
  return `<button class="card ${st}${g.gone ? ' gone' : ''}" data-act="agent" data-key="${esc(g.key)}">
    <div class="card-head"><span class="badge">${esc(k.short)}</span>
      <div class="who"><b>${esc(displayName(g))}</b><span>${esc(where(g))}</span></div>
      <span class="pill ${st}">${esc(pill)}</span></div>
    ${tail}
    ${st === 'idle' || st === 'unknown' ? '' : `<div class="prog ${st}"><i></i></div>`}
  </button>`;
}

function otherPanes() {
  const rows = [];
  for (const h of hosts.values()) for (const p of h.otherPanes || []) rows.push({ h, p });
  if (!rows.length) return '';
  return `<div class="section-label">Other panes</div><div class="mini">${rows.slice(0, 12).map(({ h, p }) => `
    <button data-act="pane" data-host="${esc(h.name)}" data-pane="${esc(p.pane_id)}"><span class="badge">$</span>
      <div class="who"><b>${esc(paneTitle(h, p))}</b><span>${esc(tidyPath(h, p.foreground_cwd || p.cwd))}${hosts.size > 1 ? ' · ' + esc(h.name) : ''}</span></div></button>`).join('')}</div>`;
}

function shortUptime(u) {
  if (!u) return '—';
  const n = (re) => +((u.match(re) || [])[1] || 0);
  const d = n(/(\d+) weeks?/) * 7 + n(/(\d+) days?/), hh = n(/(\d+) hours?/), mm = n(/(\d+) minutes?/);
  return d ? `${d}d ${hh}h` : hh ? `${hh}h ${mm}m` : `${mm}m`;
}

function paneTitle(h, p) {
  const t = p.terminal_title_stripped || '';
  return t && !/^[\w.-]+@[\w.-]+:/.test(t) ? t : basename(tidyPath(h, p.foreground_cwd || p.cwd)) || 'shell';
}

function offlineReason(h) {
  if (!h) return '';
  if (/unpaired/.test(h.err)) return 'This phone isn\'t paired with it any more. Run a-sh-pair on the computer and scan again.';
  if (/noherdr/.test(h.err)) return 'herdr isn\'t installed there. Run setup-home on the computer.';
  if (/nossh/.test(h.err)) return 'The SSH client is missing from Linux on this phone.';
  return 'Check that Tailscale is on, here and on the computer. Retrying in the background.';
}

function emptyPair() {
  return `<div class="empty"><h3>Pair your computer</h3><p>Run this on the computer, then scan the QR code it shows.</p>
    <div class="cmd" style="width:100%"><code>${esc(SETUP)}</code><button class="icon-btn" data-act="copy" aria-label="Copy">${I.copy}</button></div>
    <div class="row"><button class="btn primary" data-act="scan">${I.qr} Scan QR</button><button class="btn" data-act="paste">Paste link</button></div></div>`;
}
const SETUP = 'bash <(curl -fsSL https://raw.githubusercontent.com/txapotxapa/ghostty-android-terminal/main/tools/setup-home)';

// ---- detail

function viewDetail(v) {
  const g = agents.get(v.key);
  if (!g) return `<div class="bar"><button class="icon-btn" data-act="back">${I.back}</button><div class="plain">Agent closed</div></div>`;
  const k = kindMeta(g.kind);
  const tabs = hasLog(g) ? [['chat', 'Chat'], ['trace', 'Trace'], ['screen', 'Terminal']] : [['screen', 'Terminal']];
  const st = g.gone ? 'idle' : g.status;
  let body = '';
  if (v.tab === 'chat') body = chatHtml(g);
  else if (v.tab === 'trace') body = traceHtml(g);
  else body = screenHtml(g);
  const banner = st === 'blocked'
    ? `<button class="banner" data-act="review" data-key="${esc(g.key)}"><i></i><span>Waiting on you${g.menu?.question ? ': ' + esc(g.menu.question) : ''}</span><b>Review</b></button>` : '';
  return `<div class="bar"><button class="icon-btn" data-act="back" aria-label="Back">${I.back}</button>
      <span class="badge lg">${esc(k.short)}</span>
      <div class="title" data-k="dt"><b>${esc(displayName(g))}</b><span>${esc(where(g))} · ${esc(STATUS[st]?.label || st)}</span></div>
      <button class="icon-btn" data-act="agent-menu" data-key="${esc(g.key)}" aria-label="More">${I.more}</button></div>
    ${tabs.length > 1 ? `<div class="seg" role="tablist" data-k="seg">${tabs.map(([t, l]) => `<button role="tab" data-act="dtab" data-t="${t}" aria-selected="${v.tab === t}">${l}</button>`).join('')}</div>` : ''}
    <div class="scroll" id="dscroll" data-k="dbody-${v.tab}">${body}</div>
    <div class="dock"><div data-k="banner">${banner}</div>
      <div class="composer" data-k="composer" data-keep><button class="round plus" data-act="attach" data-target="steer" aria-label="Add photos or files">${I.plus}</button>
        <div class="cin"><div class="atts" id="atts-steer"></div><textarea id="steer" rows="1" placeholder="Steer ${esc(k.short === g.kind ? g.kind : k.label)}…" enterkeyhint="send"></textarea></div>
        <button class="round" data-act="mic" data-target="steer" aria-label="Dictate">${I.mic}</button>
        <button class="round go" data-act="steer" data-key="${esc(g.key)}" aria-label="Send" hidden>${I.send}</button></div></div>`;
}

function turnOf(entries) {
  let i = entries.length - 1;
  while (i >= 0 && entries[i].kind !== 'user') i--;
  return i < 0 ? entries : entries.slice(i);
}

function noLogNote(g) {
  return `<div class="note"><b>No transcript yet.</b> ${esc(kindMeta(g.kind).label)} writes its session log after its first reply, so chat and trace fill in then. The <b>Terminal</b> tab shows it live.</div>`;
}
function traceHtml(g) {
  if (!g.log && g.noLog) return noLogNote(g);
  if (!g.log) return '<div class="trace"><div class="tail empty">Loading trace…</div></div>';
  const turn = turnOf(g.log);
  const tools = turn.filter((e) => e.kind === 'tool');
  const t0 = turn[0]?.t || 0;
  const tEnd = g.status === 'working' ? now() : (turn[turn.length - 1]?.t || t0);
  const files = new Set(tools.filter((e) => e.path).map((e) => e.path));
  const tokens = turn.filter((e) => e.kind === 'usage').reduce((s, e) => s + e.n, 0);
  const stats = `<div class="stats"><div><small>time</small><b>${t0 ? mmss(tEnd - t0) : '—'}</b></div><div><small>steps</small><b>${tools.length}</b></div>
    <div><small>files</small><b>${files.size}</b></div><div><small>tokens</small><b>${tokens ? compact(tokens) : '—'}</b></div></div>`;
  const evs = turn.filter((e) => e.kind !== 'usage').slice(-80).map((e) => {
    const tm = t0 && e.t ? mmss(e.t - t0) : '';
    if (e.kind === 'user') return `<div class="ev plan"><span class="d"></span><div class="t"><em>you</em>${esc(firstLine(e.text).slice(0, 200))}</div><span class="tm">${tm}</span></div>`;
    if (e.kind === 'text') return `<div class="ev say"><span class="d"></span><div class="t"><em>says</em>${esc(firstLine(e.text).replace(/[`*]/g, '').slice(0, 220))}</div><span class="tm">${tm}</span></div>`;
    const out = e.result && (e.cls === 'run' || e.err || e.verb === 'grep') ? resultSnippet(e.result) : '';
    return `<div class="ev ${e.cls}${e.err ? ' err' : ''}"><span class="d"></span><div class="t"><em>${esc(e.verb)}</em>${esc(e.summary)}${e.delta ? ' <span style="color:var(--muted)">' + esc(e.delta) + '</span>' : ''}</div><span class="tm">${tm}</span>${out ? `<div class="out">${esc(out)}</div>` : ''}</div>`;
  });
  if (g.status === 'blocked' && g.menu?.question) evs.push(`<div class="ev ask"><span class="d"></span><div class="t"><em>ask</em>${esc(g.menu.question)}</div><span class="tm">${t0 ? mmss(now() - t0) : ''}</span></div>`);
  return stats + `<div class="trace">${evs.join('') || '<div class="tail empty">No steps in this turn yet.</div>'}</div>`;
}
function resultSnippet(s) {
  const ls = String(s).split('\n').map((l) => l.replace(/\s+$/, '')).filter((l) => l.trim());
  const pick = ls.length > 4 ? [...ls.slice(0, 2), '…', ...ls.slice(-1)] : ls;
  return pick.map((l) => (l.length > 120 ? l.slice(0, 120) + '…' : l)).join('\n');
}

function chatHtml(g) {
  if (!g.log && g.noLog) return noLogNote(g);
  if (!g.log) return '<div class="chat"><div class="tail empty">Loading conversation…</div></div>';
  const items = [];
  let steps = [];
  let lastT = 0;
  const flushSteps = () => {
    if (!steps.length) return;
    const last = steps[steps.length - 1];
    items.push(`<button class="steps" data-act="dtab" data-t="trace">▸ ${plural(steps.length, 'step')} · ${esc(last.verb)} ${esc(last.summary.slice(0, 40))}</button>`);
    steps = [];
  };
  const recent = g.log.filter((e) => e.kind !== 'usage').slice(-160);
  for (const e of recent) {
    if (e.t && e.t - lastT > 30 * 60 * 1000) { flushSteps(); items.push(`<div class="when">${esc(new Date(e.t).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' }))}</div>`); }
    if (e.t) lastT = e.t;
    if (e.kind === 'tool') { steps.push(e); continue; }
    flushSteps();
    if (e.kind === 'user') items.push(`<div class="msg me">${esc(e.text)}</div>`);
    else if (e.kind === 'text') items.push(`<div class="msg ai">${md(e.text)}</div>`);
  }
  flushSteps();
  if (g.status === 'working') items.push('<div class="steps" style="border-style:dashed">● working…</div>');
  for (const p of pendingMessages(g)) items.push(`<div class="msg me pending">${esc(p.text)}<small>${esc(p.note)}</small></div>`);
  return `<div class="chat">${items.slice(-60).join('') || '<div class="tail empty">No messages yet.</div>'}</div>`;
}

// What you sent that the log doesn't show as delivered yet: still in Claude
// Code's queue, or just sent from here (the outbox) and not logged yet. A
// message is never invisible between tapping Send and the agent reading it.
const QUEUED_NOTE = 'Queued · it\'ll read this at its next step';
function pendingMessages(g) {
  const norm = (x) => String(x || '').replace(/\s+/g, ' ').trim();
  const busy = g.status === 'working' || g.status === 'blocked';
  const queued = (g.queued || []).filter((q) => busy || now() - q.t < 60000);
  const seen = new Set([...(g.log || []).filter((e) => e.kind === 'user').slice(-20).map((e) => norm(e.text)), ...queued.map((q) => norm(q.text))]);
  g.outbox = (g.outbox || []).filter((m) => !seen.has(norm(m.text)) && (m.state === 'sending' || now() - m.at < 120000));
  return [
    ...queued.map((q) => ({ text: q.text, note: QUEUED_NOTE })),
    ...g.outbox.map((m) => ({ text: m.text, note: m.state === 'sending' ? 'Sending…' : m.state === 'queued' ? QUEUED_NOTE : 'Sent' })),
  ];
}

function screenHtml(g) {
  const keys = [['esc', 'Esc'], ['up', '↑'], ['down', '↓'], ['enter', 'Enter'], ['ctrl+c', 'Ctrl-C']];
  return `<div class="screen" id="scr">${g.screen ? ansiHtml(g.screen) : '<span style="color:var(--faint)">Loading…</span>'}</div>
    <div class="screen-actions">${keys.map(([k, l]) => `<button class="btn sm" data-act="key" data-k2="${k}" data-key="${esc(g.key)}">${l}</button>`).join('')}
      <button class="btn sm primary" style="margin-left:auto" data-act="takeover" data-key="${esc(g.key)}">${I.term}</button></div>`;
}

let detailTimer = null;
async function loadDetail(g, first) {
  clearTimeout(detailTimer);
  const v = cur();
  if (v.v !== 'detail' || v.key !== g.key) return;
  const sc = $('#dscroll');
  const atBottom = !sc || sc.scrollHeight - sc.scrollTop - sc.clientHeight < 60;
  try {
    if (g.status === 'blocked') g.menu = parseMenu(await screen(g, 'visible', false)); else g.menu = null;
    if (v.tab === 'screen') g.screen = tidyScreen((await screen(g, 'visible', true)).replace(/\n+$/, ''));
    else if (hasLog(g)) {
      try { g.log = await sessionLog(g, 400000); g.queued = g.log.queued || []; g.noLog = false; }
      catch (e) { g.noLog = true; if (first && v.tab !== 'screen') { v.tab = 'screen'; return loadDetail(g, true); } }
    }
  } catch (e) {
    if (first) toast(e.message);
  }
  renderNow();
  const pin = () => { const sc2 = $('#dscroll'); if (sc2) sc2.scrollTop = sc2.scrollHeight; };
  if (first || atBottom) { pin(); requestAnimationFrame(pin); if (first) setTimeout(pin, 250); }
  const busy = g.status === 'working' || g.status === 'blocked' || v.tab === 'screen';
  detailTimer = setTimeout(() => { const a = agents.get(g.key); if (a) loadDetail(a, false); }, busy ? 1800 : 5000);
}

// ---- approval sheet

function reviewSheet(g) {
  ui.sheet = { type: 'review', key: g.key, loading: true };
  render();
  Promise.all([screen(g, 'visible', false), screen(g, 'visible', true)]).then(([plain, ansi]) => {
    if (!ui.sheet || ui.sheet.key !== g.key) return;
    const menu = parseMenu(plain);
    const al = ansi.split('\n');
    const ctx = menu && menu.ctxStart !== undefined
      ? al.slice(menu.ctxStart, menu.ctxEnd).filter((l, i) => !menu.tipLines?.has(menu.ctxStart + i)).join('\n')
      : (menu?.context || []).join('\n');
    ui.sheet = { type: 'review', key: g.key, menu, ctx: tidyScreen(ctx).replace(/^(\s*\n)+|(\n\s*)+$/g, '') };
    render();
  }).catch((e) => { toast(e.message); closeSheet(); });
}

function sheetReview(s) {
  const g = agents.get(s.key);
  if (!g) return '';
  if (s.loading) return `<div class="grip"></div><h2>Reading ${esc(displayName(g))}…</h2><div class="meta">${esc(where(g))}</div><div class="body"><div class="skel" style="height:200px"></div></div>`;
  const m = s.menu;
  if (g.status !== 'blocked' && !s.sent) return `<div class="grip"></div><h2>Already answered</h2><div class="meta">${esc(where(g))} · ${esc(STATUS[g.status]?.label || '')}</div><div class="foot"><button class="btn" data-act="close-sheet">Close</button></div>`;
  const opts = m?.options || [];
  // An approval has a yes/no pair; anything else is a question, listed whole.
  const approval = opts.some((o) => /^yes\b/i.test(o.text)) && opts.some((o) => isNo(o.text));
  const yes = approval ? opts.findIndex((o) => !isNo(o.text)) : -1;
  const no = approval ? opts.findIndex((o) => isNo(o.text)) : -1;
  const others = opts.map((o, i) => [o, i]).filter(([, i]) => i !== yes && i !== no);
  return `<div class="grip"></div>
    <h2>${esc(m?.question || 'Waiting on you')}</h2>
    <div class="meta">${esc(where(g))}</div>
    <div class="body">
      ${s.ctx && s.ctx.trim() ? `<div class="ctx">${ansiHtml(s.ctx)}</div>` : ''}
      ${others.length ? `<div class="opts">${others.map(([o, i]) => `<button class="opt" data-act="choose" data-i="${i}"><span class="n">${esc(o.num || '•')}</span><span>${esc(o.text)}${o.desc ? `<small>${esc(o.desc)}</small>` : ''}</span></button>`).join('')}</div>` : ''}
    </div>
    <div class="foot">
      <div class="composer"><textarea id="amend" rows="1" placeholder="${m?.options?.some((o) => /^(type something|other)/i.test(o.text)) ? 'Or type your own answer…' : 'Or say what to do instead…'}"></textarea>
        <button class="round" data-act="mic" data-target="amend" aria-label="Dictate">${I.mic}</button>
        <button class="round go" data-act="amend" aria-label="Send" hidden>${I.send}</button></div>
      <div class="row">
        ${no >= 0 ? `<button class="btn danger" data-act="choose" data-i="${no}">${esc(shortLabel(opts[no].text, 'Reject'))}</button>` : `<button class="btn" data-act="esc">Cancel</button>`}
        ${yes >= 0 ? `<button class="btn primary" data-act="choose" data-i="${yes}">${esc(shortLabel(opts[yes].text, 'Approve'))}</button>` : `<button class="btn" data-act="takeover" data-key="${esc(g.key)}">${I.term} Terminal</button>`}
      </div>
    </div>`;
}
function shortLabel(t, fallback) {
  if (/^yes\b/i.test(t) && t.length > 18) return 'Approve';
  if (/^no\b/i.test(t) && t.length > 18) return 'Reject';
  return t.length > 24 ? fallback : t;
}

async function choose(i) {
  const s = ui.sheet; const g = agents.get(s.key); if (!g || !s.menu) return;
  haptic();
  // Re-read right before answering: if the menu moved on, don't press keys
  // into whatever replaced it.
  const fresh = parseMenu(await screen(g, 'visible', false));
  if (!fresh || fresh.question !== s.menu.question || fresh.options.length !== s.menu.options.length) { toast('The question changed — showing the new one'); reviewSheet(g); return; }
  try {
    await herdr(g.host, 'agent', 'send-keys', g.pane, ...keysFor(fresh, i));
    s.sent = true;
    closeSheet();
    toast(isNo(fresh.options[i].text) ? 'Rejected' : 'Sent: ' + fresh.options[i].text.slice(0, 40));
    setTimeout(() => kick(hosts.get(g.host)), 300);
  } catch (e) { toast(e.message); }
}

async function amend(text) {
  const s = ui.sheet; const g = agents.get(s.key); if (!g) return;
  const m = s.menu;
  try {
    // A question with its own free-text choice: pick it, type the answer.
    const free = m ? m.options.findIndex((o) => /^(type something|other|something else)/i.test(o.text)) : -1;
    if (free >= 0) {
      await herdr(g.host, 'agent', 'send-keys', g.pane, ...keysFor(m, free));
      await sleep(400);
      await herdr(g.host, 'pane', 'send-text', g.pane, text);
      await herdr(g.host, 'agent', 'send-keys', g.pane, 'enter');
      s.sent = true; closeSheet(); toast('Sent');
      return;
    }
    const no = m ? m.options.findIndex((o) => isNo(o.text)) : -1;
    if (m && m.options.length && no >= 0) await herdr(g.host, 'agent', 'send-keys', g.pane, ...keysFor(m, no));
    else if (m && m.options.length) await herdr(g.host, 'agent', 'send-keys', g.pane, 'esc');
    // Wait for the agent to leave the menu, then hand it the new instructions.
    for (let i = 0; i < 20; i++) {
      await sleep(300);
      const a = (await herdr(g.host, 'agent', 'get', g.pane)).agent || {};
      if (a.agent_status !== 'blocked') break;
    }
    await herdr(g.host, 'agent', 'prompt', g.pane, text);
    s.sent = true; closeSheet(); toast('Sent');
  } catch (e) {
    try { await herdr(g.host, 'pane', 'send-text', g.pane, text); await herdr(g.host, 'agent', 'send-keys', g.pane, 'enter'); s.sent = true; closeSheet(); toast('Sent'); }
    catch (e2) { toast(e.message); }
  }
}

// ---- other sheets

function agentMenu(g) {
  ui.sheet = { type: 'menu', key: g.key };
  render();
}
function sheetMenu(s) {
  const g = agents.get(s.key); if (!g) return '';
  return `<div class="grip"></div><h2>${esc(displayName(g))}</h2><div class="meta">${esc(where(g))} · ${esc(g.pane)}</div>
    <div class="menu">
      <button data-act="takeover" data-key="${esc(g.key)}">${I.term}<span>Open in terminal<small>Full control in a Ghostty tab</small></span></button>
      ${g.status === 'blocked' ? `<button data-act="review" data-key="${esc(g.key)}">${I.check}<span>Review what it's asking</span></button>` : ''}
      <button data-act="interrupt" data-key="${esc(g.key)}">${I.close}<span>Interrupt<small>Sends Esc, like pressing it at the desk</small></span></button>
      <button class="danger" data-act="close-agent" data-key="${esc(g.key)}">${I.trash}<span>Close agent<small>Closes its pane on ${esc(g.host)}</small></span></button>
    </div>`;
}
// The composer's +: the three ways in, as in the Claude and ChatGPT apps.
function sheetAttach() {
  return `<div class="grip"></div><h2>Add to message</h2>
    <div class="tiles">
      <button data-act="attach-how" data-how="camera">${I.camera}<span>Camera</span></button>
      <button data-act="attach-how" data-how="photos">${I.photo}<span>Photos</span></button>
      <button data-act="attach-how" data-how="files">${I.file}<span>Files</span></button>
    </div>`;
}
// New agent's folder browser: walk the computer's folders, then use one.
function sheetDirs(s) {
  const h = hosts.get(s.host);
  const rows = (s.entries || []).map((e) => `<button data-act="dir-open" data-name="${esc(e.name)}"><span class="path">${esc(e.name)}</span><span class="br">${e.git ? 'git' : ''}</span><span class="ck">${I.chev}</span></button>`).join('');
  const body = s.err ? `<div class="empty" style="margin:0"><p>${esc(s.err)}</p></div>`
    : !s.entries ? '<div class="skel" style="height:96px;border-radius:14px"></div>'
    : `<div class="list">${s.path !== '/' ? '<button data-act="dir-up"><span class="path more">..  up one folder</span></button>' : ''}${rows || '<button disabled><span class="path more">No folders inside</span></button>'}</div>`;
  return `<div class="grip"></div><h2>Pick a folder</h2>
    <div class="meta">${esc(s.path ? tidyPath(h, s.path) : 'Loading…')}${s.git ? ' · git' : ''}</div>
    <div class="body">${body}</div>
    <div class="foot"><button class="btn primary" data-act="dir-use" ${s.path && !s.err ? '' : 'disabled'}>Use ${esc(s.path ? (tidyPath(h, s.path) === '~' ? '~' : basename(s.path) || '/') : 'this folder')}</button></div>`;
}
async function loadDirs(s, path) {
  s.entries = null; s.err = ''; render();
  try {
    const r = await call(s.host, 'dirs', path);
    if (ui.sheet !== s) return;
    if (r.rc !== 0) throw new Error(r.out.trim() || "can't open " + path);
    const [first, ...lines] = r.out.split('\n');
    const [p, g] = first.split('\t');
    s.path = p; s.git = g === 'git';
    s.entries = lines.filter(Boolean).map((l) => { const [name, git] = l.split('\t'); return { name, git: git === 'git' }; });
  } catch (e) { if (ui.sheet === s) s.err = e.message; }
  render();
  const b = $('.sheet .body'); if (b) b.scrollTop = 0;
}
// Something shared into the app: pick the agent (or terminal tab) it goes to.
function sheetShare(s) {
  const list = sortedAgents().filter((g) => !g.gone);
  const rows = list.map((g) => `<button data-act="share-agent" data-key="${esc(g.key)}"${g.status === 'blocked' ? ' class="muted"' : ''}><span class="badge">${esc(kindMeta(g.kind).short)}</span>
      <span>${esc(displayName(g))}<small>${esc(where(g))} · ${g.status === 'blocked' ? 'answer its question first' : esc(STATUS[g.status]?.label || g.status || '')}</small></span></button>`).join('');
  const tabs = s.tabs.map((t) => `<button data-act="share-tab" data-index="${t.index}">${I.term}<span>${esc(t.title)}<small>${t.host ? 'Terminal tab · files go to ' + esc(t.host) + ', path pasted' : 'Terminal tab · path pasted at the prompt'}</small></span></button>`).join('');
  const n = s.items.length;
  return `<div class="grip"></div><h2>Send to…</h2>
    <div class="meta">${n ? n + (n === 1 ? ' attachment' : ' attachments') : 'Text only'}</div>
    <div class="body">
      ${n ? `<div class="atts share">${s.items.map((a) => attHtml(a)).join('')}</div>` : ''}
      <div class="composer note"><div class="cin"><textarea id="sharenote" rows="1" placeholder="Add a message for the agent (optional)">${esc(s.note)}</textarea></div></div>
      ${rows ? `<div class="section-label">Agents</div><div class="menu">${rows}</div>` : ''}
      ${tabs ? `<div class="section-label">Terminal tabs</div><div class="menu">${tabs}</div>` : ''}
      ${!rows && !tabs ? '<p style="color:var(--muted)">No agents running and no terminal tabs open.</p>' : ''}
    </div>`;
}
function sheetConfirm(s) {
  const g = agents.get(s.key); if (!g) return '';
  return `<div class="grip"></div><h2>Close ${esc(displayName(g))}?</h2>
    <div class="meta">${esc(where(g))}</div><div class="body"><p style="margin:0;color:var(--muted)">This closes the pane on ${esc(g.host)} and ends whatever is running in it. The session log stays on the computer.</p></div>
    <div class="foot"><div class="row"><button class="btn" data-act="close-sheet">Keep it</button><button class="btn danger" data-act="close-confirm" data-key="${esc(g.key)}">Close agent</button></div></div>`;
}
function sheetPane(s) {
  const h = hosts.get(s.host);
  return `<div class="grip"></div><h2>${esc(s.title)}</h2><div class="meta">${esc(s.host)} · ${esc(s.pane)}</div>
    <div class="body"><div class="ctx">${s.screen ? ansiHtml(s.screen) : 'Loading…'}</div></div>
    <div class="foot"><div class="row"><button class="btn" data-act="pane-close" ${h ? '' : 'disabled'}>Close pane</button><button class="btn primary" data-act="pane-open">${I.term} Open terminal</button></div></div>`;
}

// ---- agents on a computer: installed? ready? install them

const AGENT_STATE = {
  ready: { label: 'Ready', cls: 'ok' },
  firstrun: { label: 'Downloads on first use', cls: 'warn' },
  missing: { label: 'Not installed', cls: 'off' },
};
const installs = new Map();   // "host|kind" -> { pane, line, state: running|done|failed, rc }

async function loadAgentStates(h, force) {
  if (!h || h.state !== 'online') return;
  if (h.agentStates && !force && now() - h.agentsAt < 60000) return;
  const r = await call(h.name, 'agents');
  const m = {};
  for (const l of r.out.split('\n')) { const [k, st, ig] = l.split('\t'); if (k) m[k] = { state: st, integ: ig }; }
  h.agentStates = m; h.agentsAt = now();
  render();
}
function agentState(h, k) { return k === 'shell' ? { state: 'ready', integ: 'none' } : h?.agentStates?.[k] || null; }
function installedKinds(h) { return Object.keys(h?.agentStates || {}).filter((k) => h.agentStates[k].state !== 'missing'); }
function kindLabel(id) { return id === 'shell' ? 'Plain shell' : KINDS[id]?.label || id; }
function kindSub(id) { return id === 'shell' ? 'A herdr pane, no agent' : KINDS[id]?.sub || ''; }

async function ensureInstallsWorkspace(h) {
  const label = 'A-SH installs';
  const ws = (h.snap?.workspaces || []).find((w) => w.label === label);
  if (ws) return findPane(await herdr(h.name, 'tab', 'create', '--workspace', ws.workspace_id, '--cwd', h.info.home || '~', '--no-focus'));
  return findPane(await herdr(h.name, 'workspace', 'create', '--cwd', h.info.home || '~', '--label', label, '--no-focus'));
}

// Installs (or, for a first-use wrapper, downloads) one agent in a herdr pane
// on the computer, so it's visible there too. Resolves true on success.
function installAgent(hostName, kind) {
  const key = hostName + '|' + kind;
  const running = installs.get(key);
  if (running && running.state === 'running') return running.promise;
  const job = { state: 'running', line: 'Preparing…', pane: '', host: hostName, kind, t0: now() };
  installs.set(key, job);
  render();
  job.promise = (async () => {
    const h = hosts.get(hostName);
    try {
      const prep = await call(hostName, 'install-prep', kind);
      if (prep.rc !== 0 || !prep.out.trim()) throw new Error('no install recipe for ' + kind);
      job.pane = await ensureInstallsWorkspace(h);
      if (!job.pane) throw new Error('herdr didn\'t open a pane');
      await herdr(hostName, 'pane', 'run', job.pane, 'bash ' + prep.out.trim());
      for (;;) {
        await sleep(1500);
        if (now() - job.t0 > 20 * 60000) throw new Error('timed out after 20 minutes');
        const r = await call(hostName, 'herdr', 'pane', 'read', job.pane, '--source', 'recent', '--lines', '40');
        const ls = cleanTail(r.out);
        const done = r.out.match(/A-SH-INSTALL-DONE (\d+)/);
        const last = ls.filter((l) => !/A-SH-INSTALL-DONE/.test(l)).pop();
        if (last) job.line = last.replace(/^== /, '');
        render();
        if (done) { job.rc = +done[1]; break; }
      }
      if (job.rc !== 0) throw new Error(job.line || 'install failed');
      job.state = 'done';
      await loadAgentStates(h, true).catch(() => {});
      herdr(hostName, 'pane', 'close', job.pane).catch(() => {});
      toast(kindLabel(kind) + ' is ready on ' + hostName);
      logEvent({ type: 'done', host: hostName, text: kindLabel(kind) + ' installed', sub: hostName });
      return true;
    } catch (e) {
      job.state = 'failed'; job.line = e.message;
      toast('Couldn\'t install ' + kindLabel(kind) + ': ' + e.message);
      return false;
    } finally { render(); }
  })();
  return job.promise;
}

function agentRow(h, k, mode, v) {
  const st = agentState(h, k) || { state: 'missing', integ: 'none' };
  const job = installs.get(h.name + '|' + k);
  const f = favs();
  const selectable = mode === 'pick' && st.state !== 'missing';
  let right;
  if (job && job.state === 'running') right = `<span class="kstate warn"><i class="spin"></i>Installing</span>`;
  else if (st.state === 'missing') right = `<button class="btn sm ghost" data-act="agent-install" data-h="${esc(h.name)}" data-kind="${k}">Install</button>`;
  else if (st.state === 'firstrun') right = `<button class="btn sm" data-act="agent-install" data-h="${esc(h.name)}" data-kind="${k}">Download</button>`;
  else right = `<span class="kstate ok">${I.check}Ready</span>`;
  const sub = job && (job.state === 'running' || job.state === 'failed')
    ? `<span class="${job.state === 'failed' ? 'bad' : ''}">${esc(job.line)}</span>`
    : `<span>${esc(kindSub(k))}${st.state === 'firstrun' ? ' · downloads on first start' : ''}</span>${st.state === 'ready' && st.integ === 'missing' ? '<em data-act="agent-integ" data-h="' + esc(h.name) + '" data-kind="' + k + '">⚠ herdr guesses its status · tap to add the integration</em>' : ''}`;
  const body = `<span class="badge">${esc(k === 'shell' ? '$' : kindMeta(k).short)}</span><span class="who"><b>${esc(kindLabel(k))}</b>${sub}</span>`;
  return `<div class="krow${st.state === 'missing' ? ' dim' : ''}${mode === 'pick' && v?.kind === k ? ' sel' : ''}">
    ${selectable ? `<button class="kpick${v?.kind === k ? ' sel' : ''}" data-act="kind-pick" data-kind="${k}" aria-pressed="${v?.kind === k}">${body}</button>` : `<div class="kpick">${body}</div>`}
    ${k === 'shell' ? '' : `${right}${st.state === 'missing' ? '<span class="star-gap"></span>' : `<button class="star${f.includes(k) ? ' on' : ''}" data-act="kind-fav" data-kind="${k}" aria-label="${f.includes(k) ? 'Remove from' : 'Add to'} favorites">${f.includes(k) ? '★' : '☆'}</button>`}`}</div>`;
}

function sheetKinds(s) {
  const mode = s.mode || 'pick';
  const v = mode === 'pick' ? cur() : null;
  const h = hosts.get(mode === 'pick' ? v.host : s.host);
  if (!h) return '';
  if (!h.agentStates) return `<div class="grip"></div><h2>Agents on ${esc(h.name)}</h2><div class="body"><div class="skel" style="height:220px"></div></div>`;
  const all = Object.keys(h.agentStates);
  const f = favs();
  const ready = all.filter((k) => h.agentStates[k].state === 'ready');
  const first = all.filter((k) => h.agentStates[k].state === 'firstrun');
  const missing = all.filter((k) => h.agentStates[k].state === 'missing');
  const favRow = [...ready, ...first].filter((k) => f.includes(k));
  const sec = (label, list) => (list.length ? `<div class="section-label">${label}</div>${list.map((k) => agentRow(h, k, mode, v)).join('')}` : '');
  return `<div class="grip"></div><h2>${mode === 'pick' ? 'Choose an agent' : 'Agents on ' + esc(h.name)}</h2>
    <div class="meta">${ready.length} ready${first.length ? ' · ' + first.length + ' download on first use' : ''} · ${missing.length} more to install</div>
    <div class="body">
      ${sec('Favorites', favRow)}
      ${sec('Ready', ready.filter((k) => !f.includes(k)).concat(mode === 'pick' ? ['shell'] : []))}
      ${sec('Downloads on first use', first.filter((k) => !f.includes(k)))}
      ${sec('Not installed · one tap installs it on ' + esc(h.name), missing)}
      <p class="note" style="margin:12px 2px 4px">Installs use mise, the same way Omarchy does, and run in a herdr pane on ${esc(h.name)} named "A-SH installs" so you can watch them there. ★ pins an agent to the top and to the quick picks.</p></div>`;
}

function closeSheet() {
  const s = ui.sheet;
  if (s && s.type === 'share' && !s.sent) for (const a of s.items) try { N.dropAttachment(a.token); } catch (e) {}
  ui.sheet = null; render();
}
function renderSheet() {
  let host = $('#sheet-host');
  if (!ui.sheet) { if (host) host.remove(); return; }
  if (!host) { host = document.createElement('div'); host.id = 'sheet-host'; document.body.appendChild(host); host.innerHTML = '<div class="scrim" data-act="close-sheet"></div><div class="sheet" role="dialog"></div>'; }
  const s = ui.sheet;
  const html = s.type === 'kinds' ? sheetKinds(s) : s.type === 'review' ? sheetReview(s) : s.type === 'menu' ? sheetMenu(s) : s.type === 'confirm' ? sheetConfirm(s) : s.type === 'pane' ? sheetPane(s)
    : s.type === 'attach' ? sheetAttach() : s.type === 'share' ? sheetShare(s) : s.type === 'dirs' ? sheetDirs(s) : '';
  const sh = $('.sheet', host);
  if (sh.dataset.html === html) return;
  // Re-rendering mustn't lose what is being typed in the sheet.
  const kept = [...sh.querySelectorAll('textarea[id]')].map((t) => [t.id, t.value, t === document.activeElement]);
  sh.dataset.html = html; sh.innerHTML = html;
  for (const [id, value, focused] of kept) {
    const t = document.getElementById(id); if (!t) continue;
    if (value) { t.value = value; syncSend(t); }
    if (focused) t.focus();
  }
}

// ---- new agent

function newAgent() {
  clearAtts('newprompt', true);
  const v = { v: 'new', host: [...hosts.values()].find((h) => h.state === 'online')?.name || '', kind: '', folder: '', worktree: false, prompt: '', busy: '' };
  push(v); ui.anim = 'enter-up';
  loadNewOptions(v);
}
async function loadNewOptions(v) {
  const h = hosts.get(v.host); if (!h || h.state !== 'online') return;
  try {
    await loadAgentStates(h);
    if (!h.repos) h.repos = (await call(h.name, 'repos')).out.split('\n').filter(Boolean).map((l) => { const [p, b] = l.split('\t'); return { path: p, branch: b }; });
  } catch (e) { if (!h.repos) h.repos = []; toast(e.message); }
  if (!v.kind) {
    const inst = installedKinds(h);
    const last = store('ash.lastKind.v1', '');
    v.kind = [last, ...favs()].find((k) => k === 'shell' || inst.includes(k)) || inst[0] || 'shell';
  }
  if (!v.folder) v.folder = h.repos?.[0]?.path || h.info.home || '';
  render();
}

function viewNew(v) {
  const hs = [...hosts.values()];
  const h = hosts.get(v.host);
  const bins = h?.agentStates;
  const inst = installedKinds(h);
  const quick = [...new Set([...favs(), v.kind])].filter((k) => k === 'shell' || inst.includes(k));
  const kst = agentState(h, v.kind);
  const folders = [];
  const seen = new Set();
  // A folder picked with Browse… stays on the list, first.
  if (v.folder && !(h?.repos || []).some((r) => r.path === v.folder)) { seen.add(v.folder); folders.push({ path: v.folder, branch: '' }); }
  for (const r of h?.repos || []) if (!seen.has(r.path)) { seen.add(r.path); folders.push(r); }
  for (const g of agents.values()) if (g.host === v.host && g.cwd && !seen.has(g.cwd)) { seen.add(g.cwd); folders.push({ path: g.cwd, branch: '' }); }
  if (h?.info.home && !seen.has(h.info.home)) folders.push({ path: h.info.home, branch: '' });
  const isGit = v.folderGit || !!(h?.repos || []).find((r) => r.path === v.folder && r.branch);
  const label = v.kind === 'shell' ? 'Open shell in herdr' : 'Start ' + (KINDS[v.kind]?.label || v.kind) + ' in herdr';
  return `<div class="bar"><button class="icon-btn" data-act="back" aria-label="Close">${I.close}</button><div class="plain">New agent</div></div>
    <div class="scroll"><div class="form" data-k="newform">
      ${hs.length > 1 ? `<div class="label">Computer</div><div class="chips" style="padding:0">${hs.map((x) => `<button class="chip" data-act="new-host" data-h="${esc(x.name)}" aria-pressed="${x.name === v.host}" ${x.state === 'online' ? '' : 'disabled'}>${esc(x.name)}</button>`).join('')}</div>` : ''}
      ${!h || h.state !== 'online' ? `<div class="empty" style="margin:0"><h3>No computer online</h3><p>${esc(offlineReason(h || hs[0]))}</p></div>` : `
      <div class="label">Agent</div>
      ${!bins ? '<div class="skel" style="height:62px"></div>' : `<button class="select" data-act="pick-kind" aria-haspopup="dialog">
        <span class="badge">${esc(v.kind === 'shell' ? '$' : kindMeta(v.kind).short)}</span>
        <span class="who"><b>${esc(kindLabel(v.kind))}</b><span>${esc(kst?.state === 'firstrun' ? 'Downloads when it first starts' : kindSub(v.kind))}</span></span>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6"/></svg></button>
      ${quick.length > 1 ? `<div class="chips" style="padding:2px 0 0">${quick.map((k) => `<button class="chip" data-act="new-kind" data-kind="${k}" aria-pressed="${v.kind === k}">${favs().includes(k) ? '★ ' : ''}${esc(kindLabel(k))}</button>`).join('')}</div>` : ''}`}
      <div class="label">Folder</div>
      <div class="list">${!h.repos ? '<button disabled><span class="path">Finding repos…</span></button>' : folders.slice(0, 10).map((f) => `<button data-act="new-folder" data-path="${esc(f.path)}"><span class="path">${esc(tidyPath(h, f.path))}</span><span class="br">${esc(f.branch === 'detached' ? '' : f.branch)}</span><span class="ck">${f.path === v.folder ? I.check : ''}</span></button>`).join('') + `<button data-act="browse-dir"><span class="path more">Other folder…</span><span class="ck">${I.chev}</span></button>`}</div>
      <button class="check" role="checkbox" aria-checked="${v.worktree && isGit}" data-act="new-wt" ${isGit ? '' : 'disabled'}><span class="box">${v.worktree && isGit ? I.check : ''}</span>Run in its own git worktree</button>
      ${v.kind !== 'shell' ? `<div class="label">First prompt</div>
      <div class="prompt-box" data-keep data-k="np"><div class="atts" id="atts-newprompt"></div><textarea id="newprompt" placeholder="What should it work on? Tap the mic to dictate."></textarea></div>` : ''}`}
    </div></div>
    <div class="sticky-foot" data-k="newfoot">
      ${v.kind !== 'shell' ? `<button class="round" data-act="attach" data-target="newprompt" aria-label="Add photos or files">${I.plus}</button><button class="round" data-act="mic" data-target="newprompt" aria-label="Dictate">${I.mic}</button>` : ''}
      <button class="btn primary" data-act="new-start" ${!h || h.state !== 'online' || v.busy ? 'disabled' : ''}>${esc(v.busy || label)}</button></div>`;
}

async function startAgent(v) {
  const h = hosts.get(v.host); if (!h) return;
  const prompt = ($('#newprompt')?.value || '').trim();
  const items = v.kind === 'shell' ? [] : (atts.newprompt || []);
  save('ash.lastKind.v1', v.kind);
  const repo = basename(v.folder);
  const slug = (prompt || v.kind).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 24) || 'agent';
  // herdr agent names are unique per computer, and the plain kind ("claude")
  // is usually taken already, so every start gets a short random suffix.
  const tag = () => Math.random().toString(16).slice(2, 6);
  let ws = '';      // the workspace this start created, closed again if it fails
  let started = false;
  try {
    v.busy = v.worktree ? 'Creating worktree…' : 'Creating workspace…'; render();
    let res;
    if (v.worktree) {
      const branch = 'a-sh/' + slug + '-' + tag();
      res = await herdr(h.name, 'worktree', 'create', '--cwd', v.folder, '--branch', branch, '--label', repo + ' · ' + slug, '--no-focus');
    } else {
      res = await herdr(h.name, 'workspace', 'create', '--cwd', v.folder, '--label', repo, '--no-focus');
    }
    const pane = findPane(res);
    if (!pane) throw new Error('herdr didn\'t return a pane');
    ws = pane.split(':')[0];
    const key = h.name + '|' + pane;
    if (v.kind === 'shell') { started = true; v.busy = ''; ui.stack.pop(); render(); toast('Shell opened in herdr'); try { N.openTerminal(h.name, pane); } catch (e) {} return; }
    if (agentState(h, v.kind)?.state === 'firstrun') {
      v.busy = 'Downloading ' + kindLabel(v.kind) + '…'; render();
      if (!(await installAgent(h.name, v.kind))) throw new Error(kindLabel(v.kind) + ' didn\'t download');
    }
    v.busy = 'Starting ' + (KINDS[v.kind]?.label || v.kind) + '…'; render();
    let blocked = false;
    for (let attempt = 0; ; attempt++) {
      try { await herdr(h.name, 'agent', 'start', slug + '-' + tag(), '--kind', v.kind, '--pane', pane, '--timeout', '45000'); break; }
      catch (e) {
        if (e.code === 'agent_name_taken' && attempt < 3) continue;
        if (e.code === 'agent_not_ready' || /blocked/.test(e.message)) { blocked = true; break; }
        throw e;
      }
    }
    started = true;
    // Wait for the board's next snapshot to carry the agent, then open it.
    kick(h);
    for (let i = 0; i < 24 && !agents.get(key); i++) { await sleep(250); if (i % 4 === 3) kick(h); }
    const g = agents.get(key) || { key, host: h.name, pane };
    let first = prompt;
    if (items.length) {
      v.busy = 'Sending attachments…'; render();
      const paths = [];
      for (const a of items) paths.push(await uploadAtt(h.name, a.token));
      first = withPaths(prompt, paths, items);
      clearAtts('newprompt', true);
    }
    if (first) {
      if (blocked) pendingPrompt(key, first);
      else await herdr(h.name, 'agent', 'prompt', pane, first);
    }
    v.busy = '';
    ui.stack = [{ v: 'board' }]; ui.tab = 'board';
    if (agents.get(key)) openDetail(key); else render();
    if (blocked) toast('It needs your OK before starting — tap Review');
  } catch (e) {
    v.busy = ''; render(); toast('Couldn\'t start: ' + e.message);
    // Leave nothing behind: an empty workspace from a failed start is just clutter in herdr.
    if (ws && !started) herdr(h.name, 'workspace', 'close', ws).catch(() => {});
  }
}
function findPane(o) {
  if (!o || typeof o !== 'object') return '';
  if (o.root_pane?.pane_id) return o.root_pane.pane_id;
  if (typeof o.pane_id === 'string') return o.pane_id;
  for (const v of Object.values(o)) { const p = findPane(v); if (p) return p; }
  return '';
}

// ---- activity

function viewActivity() {
  const F = [['all', 'All'], ['needs', 'Needs you'], ['done', 'Finished'], ['net', 'Computers']];
  const needsN = activity.filter((e) => e.type === 'blocked' && now() - e.t < 86400000).length;
  const list = activity.filter((e) => ui.filter === 'all' || (ui.filter === 'needs' && e.type === 'blocked') || (ui.filter === 'done' && e.type === 'done') || (ui.filter === 'net' && e.type === 'net'));
  let html = '', day = '';
  for (const e of list.slice(0, 150)) {
    const d = new Date(e.t); const today = new Date();
    const yd = new Date(today); yd.setDate(today.getDate() - 1);
    const label = d.toDateString() === today.toDateString() ? 'Today' : d.toDateString() === yd.toDateString() ? 'Yesterday' : d.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric' });
    if (label !== day) { html += `<div class="day">${label}</div>`; day = label; }
    const ic = { blocked: ['blocked', '?'], done: ['done', '✓'], working: ['working', '▸'], start: ['', '+'], gone: ['', '×'], net: ['net', '⇄'] }[e.type] || ['', '•'];
    const live = e.key && agents.get(e.key);
    html += `<button class="item" ${live ? `data-act="agent" data-key="${esc(e.key)}"` : 'disabled'}><span class="ic ${ic[0]}">${ic[1]}</span>
      <span class="tx"><b>${esc(e.text)}</b><span>${esc(e.sub || '')}</span></span><time>${clock(e.t)}</time></button>`;
  }
  if (!html) html = `<div class="empty" style="margin:16px 0"><h3>Nothing yet</h3><p>Agents finishing, getting stuck or asking for approval show up here, while this phone is connected.</p></div>`;
  return `<header class="top"><div class="h1">Activity</div></header>
    <div class="chips" data-k="chips">${F.map(([f, l]) => `<button class="chip" data-act="filter" data-f="${f}" aria-pressed="${ui.filter === f}">${l}${f === 'needs' && needsN ? ' · ' + needsN : ''}</button>`).join('')}</div>
    <main class="scroll" data-k="feed"><div class="feed">${html}</div></main>`;
}

// ---- computers

function viewComputers() {
  const hs = [...hosts.values()];
  for (const h of hs) if (h.state === 'online' && !h.agentStates && !h.agentsLoading) { h.agentsLoading = true; loadAgentStates(h).catch(() => {}).finally(() => { h.agentsLoading = false; }); }
  return `<div class="bar"><button class="icon-btn" data-act="back" aria-label="Back">${I.back}</button><div class="plain">Computers</div></div>
    <div class="scroll" data-k="hosts"><div class="hosts">
      ${hs.map((h) => {
        const on = h.state === 'online';
        const s = h.snap;
        const nA = [...agents.values()].filter((g) => g.host === h.name && !g.gone).length;
        const stTxt = on ? `<span class="on">Online · Tailscale${h.rtt ? ' · ' + h.rtt + ' ms' : ''}</span>` : h.state === 'connecting' ? '<span class="warn">Connecting…</span>' : `<span class="bad">Offline</span>`;
        return `<div class="host"><div class="host-head"><span class="host-ic ${on ? '' : 'off'}">${I.laptop}</span>
            <div class="who"><b>${esc(h.name)}</b>${stTxt}</div></div>
          ${on ? `<div class="kv"><div><small>herdr ${esc(h.info.herdr || '')}</small><span>${s ? plural((s.panes || []).length, 'pane') + ' · ' + plural(nA, 'agent') : '…'}</span></div>
            <div><small>ssh</small><span>${esc(h.name)} / ${esc(h.name)}-sh</span></div>
            <div><small>address</small><span>${esc(h.address || '—')}</span></div>
            <div><small>uptime</small><span>${esc(shortUptime(h.info.uptime))}</span></div></div>`
            : `<p style="margin:0;color:var(--muted);font-size:14px">${esc(offlineReason(h))}</p>`}
          ${on ? `<button class="agents-line" data-act="manage-agents" data-h="${esc(h.name)}"><small>agents</small>
            <span>${h.agentStates ? installedKinds(h).map((k) => `<i>${esc(kindLabel(k))}</i>`).join('') || 'none yet' : '…'}</span><b>Manage ›</b></button>` : ''}
          <div class="row"><button class="btn" data-act="reconnect" data-h="${esc(h.name)}">${I.refresh} Reconnect</button>
            <button class="btn" data-act="open-shell" data-h="${esc(h.name)}">${I.term} Shell</button></div></div>`;
      }).join('')}
      <div class="pair"><h3>${hs.length ? 'Pair another computer' : 'Pair your computer'}</h3>
        <p>Run this on it, then scan the QR it shows. The code expires in 5 minutes.</p>
        <div class="cmd"><code>${esc(SETUP)}</code><button class="icon-btn" data-act="copy" aria-label="Copy command">${I.copy}</button></div>
        <div class="row" style="display:flex;gap:10px"><button class="btn primary" data-act="scan">${I.qr} Scan QR</button><button class="btn" data-act="paste">Paste link</button></div></div>
    </div></div>`;
}

// --------------------------------------------------------------- events

function syncSend(ta) {
  const box = ta.closest('.composer');
  if (!box) return;
  const has = ta.value.trim().length > 0 || (atts[ta.id] || []).length > 0;
  const go = box.querySelector('.round.go'), mic = box.querySelector('[data-act="mic"]');
  if (go) go.hidden = !has;
  if (mic) mic.hidden = has;
  sizeBox(ta, box);
}

// The message box, as in the Claude and ChatGPT apps: it grows with the text
// up to about eight lines (less on a short screen), then scrolls. Past that an
// expand button opens it to most of the screen above the keyboard; it closes
// again by tapping it, or by itself once the text fits (e.g. after sending).
function sizeBox(ta, box) {
  const vh = window.innerHeight;
  const cap = Math.round(Math.min(192, vh * 0.4)), big = Math.round(vh * 0.62);
  ta.style.height = 'auto';
  const need = ta.scrollHeight, over = need > cap + 4;
  if (!over) box.classList.remove('big');
  let t = box.querySelector('.grow');
  if (over && !t) {
    t = document.createElement('button');
    t.className = 'grow'; t.dataset.act = 'grow';
    (box.querySelector('.cin') || box).appendChild(t);
  }
  if (t) { t.hidden = !over; t.innerHTML = box.classList.contains('big') ? I.shrink : I.grow;
    t.setAttribute('aria-label', box.classList.contains('big') ? 'Shrink message box' : 'Expand message box'); }
  ta.style.height = (box.classList.contains('big') ? Math.max(big, cap) : Math.min(cap, need)) + 'px';
}
window.addEventListener('resize', () => { for (const ta of document.querySelectorAll('.composer textarea')) if (ta.value) syncSend(ta); });

document.addEventListener('input', (e) => { if (e.target.matches('.composer textarea')) syncSend(e.target); });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && e.target.matches('.composer textarea')) {
    e.preventDefault();
    const go = e.target.closest('.composer').querySelector('.round.go');
    if (go && !go.hidden) go.click();
  }
});

let pressTimer = null, pressed = null, longFired = false;
document.addEventListener('pointerdown', (e) => {
  const c = e.target.closest('.card');
  longFired = false;
  if (!c) return;
  pressed = c;
  pressTimer = setTimeout(() => { longFired = true; haptic(); const g = agents.get(c.dataset.key); if (g) agentMenu(g); }, 480);
});
['pointerup', 'pointercancel', 'pointerleave'].forEach((t) => document.addEventListener(t, () => { clearTimeout(pressTimer); pressed = null; }));
document.addEventListener('scroll', () => clearTimeout(pressTimer), true);

// ---- swipe between an agent's Chat / Trace / Terminal (X-style)
//
// The body follows the finger; let go past a fifth of the width (or flick)
// and the neighbouring tab slides in from that side; short of it, it springs
// back. Mostly-vertical drags stay scrolls, a block that can still scroll
// sideways (code, a wide screen) keeps its own swipe, and touches starting at
// the screen edges are left to Android's back gesture.

function detailTabs(g) { return hasLog(g) ? ['chat', 'trace', 'screen'] : ['screen']; }
function showDetailTab(t, dir) {
  const v = cur(); const a = agents.get(v.key);
  if (v.v !== 'detail' || !a || v.tab === t) return;
  v.tab = t; v.slide = dir || 0;
  a.screen = a.screen || null;
  render(); loadDetail(a, true);
}
function scrollsSideways(node, dx) {
  for (let n = node; n && n.id !== 'dscroll'; n = n.parentElement) {
    if (n.scrollWidth <= n.clientWidth + 1) continue;
    const ox = getComputedStyle(n).overflowX;
    if (ox !== 'auto' && ox !== 'scroll') continue;
    if (dx < 0 && n.scrollLeft + n.clientWidth < n.scrollWidth - 1) return true;
    if (dx > 0 && n.scrollLeft > 0) return true;
  }
  return false;
}
const swipe = { el: null };
document.addEventListener('touchstart', (e) => {
  swipe.el = null;
  const body = e.target.closest && e.target.closest('#dscroll');
  if (!body || cur().v !== 'detail' || ui.sheet || e.touches.length !== 1) return;
  const t = e.touches[0];
  if (t.clientX < 24 || t.clientX > innerWidth - 24) return;
  Object.assign(swipe, { el: body, target: e.target, x0: t.clientX, y0: t.clientY, dx: 0, on: false, dead: false, t0: performance.now() });
}, { passive: true });
document.addEventListener('touchmove', (e) => {
  if (!swipe.el || swipe.dead) return;
  const t = e.touches[0];
  const dx = t.clientX - swipe.x0, dy = t.clientY - swipe.y0;
  if (!swipe.on) {
    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
    if (Math.abs(dx) < Math.abs(dy) * 1.3 || scrollsSideways(swipe.target, dx)) { swipe.dead = true; return; }
    swipe.on = true; swipe.el.style.transition = 'none';
  }
  const v = cur(); const g = agents.get(v.key); const tabs = g ? detailTabs(g) : []; const i = tabs.indexOf(v.tab);
  const end = (dx > 0 && i <= 0) || (dx < 0 && i >= tabs.length - 1);
  swipe.dx = end ? dx / 4 : dx;          // rubber band where there is no next tab
  swipe.el.style.transform = `translateX(${swipe.dx}px)`;
  e.preventDefault();
}, { passive: false });
function endSwipe() {
  const el = swipe.el; swipe.el = null;
  if (!el || !swipe.on) return;
  const v = cur(); const g = agents.get(v.key); const tabs = g ? detailTabs(g) : []; const i = tabs.indexOf(v.tab);
  const dir = swipe.dx < 0 ? 1 : -1;
  const fast = Math.abs(swipe.dx) / Math.max(1, performance.now() - swipe.t0) > 0.5;
  const far = Math.abs(swipe.dx) > innerWidth * 0.2;
  const next = tabs[i + dir];
  if (next && Math.abs(swipe.dx) > 30 && (far || fast)) { haptic(); showDetailTab(next, dir); return; }
  el.style.transition = 'transform .18s ease-out'; el.style.transform = '';
}
document.addEventListener('touchend', endSwipe);
document.addEventListener('touchcancel', endSwipe);

document.addEventListener('click', async (e) => {
  const el = e.target.closest('[data-act]');
  if (!el || el.disabled) return;
  if (longFired) { longFired = false; e.preventDefault(); return; }
  const act = el.dataset.act;
  const g = el.dataset.key ? agents.get(el.dataset.key) : null;
  switch (act) {
    case 'tab': setTab(el.dataset.t); break;
    case 'back': goBack(); break;
    case 'go': push({ v: el.dataset.v }); break;
    case 'new': newAgent(); break;
    case 'agent': if (g) openDetail(g.key); break;
    case 'dtab': { const v = cur(); const a = agents.get(v.key); if (v.v === 'detail' && a) { const tabs = detailTabs(a); showDetailTab(el.dataset.t, Math.sign(tabs.indexOf(el.dataset.t) - tabs.indexOf(v.tab))); } break; }
    case 'review': if (g) reviewSheet(g); break;
    case 'agent-menu': if (g) agentMenu(g); break;
    case 'close-sheet': closeSheet(); break;
    case 'choose': choose(+el.dataset.i); break;
    case 'esc': { const a = agents.get(ui.sheet.key); closeSheet(); if (a) herdr(a.host, 'agent', 'send-keys', a.pane, 'esc').catch((x) => toast(x.message)); break; }
    case 'amend': { const t = $('#amend').value.trim(); if (t) amend(t); break; }
    case 'steer': {
      const ta = $('#steer'); const t = ta.value.trim(); const items = atts.steer || [];
      if ((!t && !items.length) || !g) break;
      if (g.status === 'blocked') { toast('It\'s waiting on a question — answer it first'); reviewSheet(g); break; }
      ta.value = ''; atts.steer = []; renderAtts('steer'); syncSend(ta);
      if (items.length) toast(items.length > 1 ? `Sending ${items.length} attachments…` : 'Sending attachment…');
      sendToAgent(g, t, items).then((st) => { toast(st === 'queued' ? 'Queued — it\'ll read it at its next step' : 'Sent'); kick(hosts.get(g.host)); setTimeout(() => loadDetail(g, false), 600); })
        .catch((x) => { ta.value = t; atts.steer = items; renderAtts('steer'); syncSend(ta); toast(x.message); });
      break;
    }
    case 'grow': {
      const box = el.closest('.composer'), ta = box.querySelector('textarea');
      box.classList.toggle('big'); haptic(); sizeBox(ta, box); ta.focus(); break;
    }
    case 'attach': haptic(); ui.sheet = { type: 'attach', target: el.dataset.target }; render(); break;
    case 'attach-how': { const target = ui.sheet?.target; closeSheet(); if (target) try { N.attach(target, el.dataset.how); } catch (x) { toast('Attachments aren\'t available'); } break; }
    case 'att-x': {
      const target = el.dataset.target, token = el.dataset.token;
      atts[target] = (atts[target] || []).filter((a) => a.token !== token);
      try { N.dropAttachment(token); } catch (x) {}
      renderAtts(target); const ta = document.getElementById(target); if (ta) syncSend(ta);
      break;
    }
    case 'share-agent': {
      const s = ui.sheet; const a = agents.get(el.dataset.key); if (!s || !a) break;
      if (a.status === 'blocked') { toast('It\'s waiting on a question — answer it first'); break; }
      const note = ($('#sharenote')?.value || '').trim();
      if (!note && !s.items.length) { toast('Nothing to send'); break; }
      s.sent = true; closeSheet();
      toast(s.items.length ? 'Sending to ' + displayName(a) + '…' : 'Sending…');
      sendToAgent(a, note, s.items).then(() => { toast('Sent to ' + displayName(a)); kick(hosts.get(a.host)); openDetail(a.key); })
        .catch((x) => toast(x.message));
      break;
    }
    case 'share-tab': {
      const s = ui.sheet; if (!s) break;
      s.sent = true; closeSheet();
      try { N.shareToTab(+el.dataset.index, JSON.stringify(s.items.map((a) => a.token))); } catch (x) {}
      break;
    }
    case 'mic': {
      const target = el.dataset.target;
      el.classList.add('rec');
      dictate((text) => {
        el.classList.remove('rec');
        const ta = document.getElementById(target); if (!ta || !text) return;
        ta.value = (ta.value ? ta.value.replace(/\s*$/, ' ') : '') + text; syncSend(ta); ta.focus();
      });
      break;
    }
    case 'key': if (g) { haptic(); herdr(g.host, 'agent', 'send-keys', g.pane, el.dataset.k2).then(() => setTimeout(() => loadDetail(g, false), 250)).catch((x) => toast(x.message)); } break;
    case 'takeover': if (g) { closeSheet(); try { N.openTerminal(g.host, g.pane); } catch (x) {} } break;
    case 'interrupt': if (g) { closeSheet(); herdr(g.host, 'agent', 'send-keys', g.pane, 'esc').then(() => toast('Interrupted')).catch((x) => toast(x.message)); } break;
    case 'close-agent': if (g) { ui.sheet = { type: 'confirm', key: g.key }; render(); } break;
    case 'close-confirm': if (g) {
      closeSheet();
      herdr(g.host, 'pane', 'close', g.pane).then(() => { toast('Closed'); if (cur().v === 'detail') goBack(); kick(hosts.get(g.host)); }).catch((x) => toast(x.message));
    } break;
    case 'pane': {
      const host = el.dataset.host, pane = el.dataset.pane;
      const h = hosts.get(host); const p = (h?.otherPanes || []).find((x) => x.pane_id === pane);
      ui.sheet = { type: 'pane', host, pane, title: p ? paneTitle(h, p) : 'shell' };
      render();
      call(host, 'herdr', 'pane', 'read', pane, '--source', 'visible', '--format', 'ansi').then((r) => { if (ui.sheet?.pane === pane) { ui.sheet.screen = cleanTrailing(r.out); render(); } });
      break;
    }
    case 'pane-open': { const s = ui.sheet; closeSheet(); try { N.openTerminal(s.host, s.pane); } catch (x) {} break; }
    case 'pane-close': { const s = ui.sheet; closeSheet(); herdr(s.host, 'pane', 'close', s.pane).then(() => { toast('Closed'); kick(hosts.get(s.host)); }).catch((x) => toast(x.message)); break; }
    case 'filter': ui.filter = el.dataset.f; render(); break;
    case 'reconnect': { const h = hosts.get(el.dataset.h); if (h) reconnectNow(h); break; }
    case 'reconnect-all': for (const h of hosts.values()) if (h.state !== 'online') reconnectNow(h); break;
    case 'open-shell': try { N.openShell(el.dataset.h); } catch (x) {} break;
    case 'copy': try { N.copy(SETUP); toast('Copied'); } catch (x) {} break;
    case 'scan': try { N.scanQr(); } catch (x) {} break;
    case 'paste': try { N.pastePairing(); } catch (x) {} break;
    case 'new-host': { const v = cur(); v.host = el.dataset.h; v.kind = ''; v.folder = ''; render(); loadNewOptions(v); break; }
    case 'new-kind': { const v = cur(); v.kind = el.dataset.kind; render(); break; }
    case 'new-folder': { const v = cur(); v.folder = el.dataset.path; v.folderGit = false; render(); break; }
    case 'browse-dir': { const v = cur(); const h = hosts.get(v.host); ui.sheet = { type: 'dirs', host: v.host, path: '', git: false, entries: null, err: '' }; render(); loadDirs(ui.sheet, v.folder || h?.info.home || '~'); break; }
    case 'dir-open': { const s = ui.sheet; loadDirs(s, s.path.replace(/\/$/, '') + '/' + el.dataset.name); break; }
    case 'dir-up': { const s = ui.sheet; loadDirs(s, s.path.replace(/\/[^/]*$/, '') || '/'); break; }
    case 'dir-use': { const s = ui.sheet; const v = cur(); if (v.v === 'new' && s.path) { v.folder = s.path; v.folderGit = s.git; } ui.sheet = null; render(); break; }
    case 'new-wt': { const v = cur(); v.worktree = !v.worktree; render(); break; }
    case 'pick-kind': ui.sheet = { type: 'kinds', mode: 'pick' }; render(); loadAgentStates(hosts.get(cur().host), true).catch(() => {}); break;
    case 'kind-pick': { const v = cur(); if (v.v === 'new') v.kind = el.dataset.kind; closeSheet(); break; }
    case 'kind-fav': toggleFav(el.dataset.kind); haptic(); render(); break;
    case 'agent-install': {
      haptic();
      const hn = el.dataset.h, k = el.dataset.kind;
      installAgent(hn, k).then((ok) => { const v = cur(); if (ok && v.v === 'new' && v.host === hn && ui.sheet?.type === 'kinds') { v.kind = k; render(); } });
      break;
    }
    case 'agent-integ': {
      e.stopPropagation();
      const hn = el.dataset.h, k = el.dataset.kind;
      herdr(hn, 'integration', 'install', k).then(() => { toast('herdr now tracks ' + kindLabel(k) + ' precisely'); loadAgentStates(hosts.get(hn), true); }).catch((x) => toast(x.message));
      break;
    }
    case 'manage-agents': { const h = hosts.get(el.dataset.h); ui.sheet = { type: 'kinds', mode: 'manage', host: el.dataset.h }; render(); loadAgentStates(h, true).catch((x) => toast(x.message)); break; }
    case 'open-update': try { N.openUpdates(); } catch (x) {} break;
    case 'new-start': startAgent(cur()); break;
  }
});
function cleanTrailing(s) { return tidyScreen(String(s).replace(/(\n\s*)+$/, '')); }
// Agent TUIs draw full-width rules for a 200-column desktop; on a phone they'd
// wrap into several lines, so long runs shrink to fit.
function tidyScreen(s) {
  return String(s).split('\n').map((l) => l.replace(/[─━═╌┄┈╍\-]{24,}/g, (r) => r.slice(0, 24)).replace(/[ \t]+((?:\x1b\[[0-9;]*m)*)$/, '$1')).join('\n');
}

document.addEventListener('visibilitychange', () => { if (!document.hidden) for (const h of hosts.values()) if (h.state === 'online') kick(h); render(); });
setInterval(() => { if (cur().v === 'detail' && cur().tab === 'trace') render(); }, 1000);

// --------------------------------------------------------------- boot

if (!N) {
  document.body.innerHTML = '<p style="padding:24px;color:#9A9C9F;font-family:sans-serif">The agent board runs inside the A-SH app.</p>';
} else {
  try { applyTheme(JSON.parse(N.theme())); } catch (x) {}
  loadHosts();
  render();
}
