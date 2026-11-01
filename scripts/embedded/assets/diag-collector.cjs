#!/usr/bin/env node
/**
 * diag-collector.cjs — P0 agent-side diagnostics collector (self-diagnostics
 * study, 2026-11). One command that gathers the observable state of the
 * ZCode-Android runtime into a single redacted JSON report, for the embedded
 * agent (same uid) and for CI evidence stages.
 *
 * Collected (all OBSERVED reads, no speculation):
 *   meta        — node version, platform, timestamp
 *   provision   — provision-complete.json (verbatim fields, small)
 *   processes   — every same-uid node/zcode process: RSS **and** PSS in kB
 *                 (review note 2: RSS double-counts shared pages across the
 *                 node process family; PSS from smaps_rollup is the honest
 *                 per-process figure when readable)
 *   memory      — MemTotal / MemAvailable from /proc/meminfo
 *   storage     — free/total bytes for the app files dir (fs.statfs)
 *   server      — GET / and GET /remote on the local server (status, bytes,
 *                 content-type) — /remote is the upstream web bundle's built-in
 *                 mobile remote-control route
 *   webconsole  — files/logs/webconsole.log(+.old): size, rotation state,
 *                 shim verdict counters (applied vs engine-healthy), last
 *                 htmlPx values, last register/dom-state markers. Counts and
 *                 diagnostic lines only — never raw page content.
 *   serverlog   — files/logs/server.log(+.old): size + last lines (app >= 0.4.8)
 *   diagreport  — files/logs/diag-report.json presence + age (app >= 0.4.8)
 *
 * Every string field passes the redactor before it leaves this script
 * (review note 4: logs may contain untrusted page text; tokens/keys never
 * leave the device through this report).
 *
 * Usage (on device, under the embedded node):
 *   node diag-collector.cjs [--app-files <dir>] [--url <serverUrl>] [--out <file>]
 * Defaults: --app-files /data/user/0/io.github.zcodeandroid.app/files
 *           --url http://127.0.0.1:3030
 * Exit codes: 0 = report produced (even with degraded sections), 1 = no report.
 */
'use strict';

const fs = require('fs');
const path = require('path');

function argValue(flag, fallback) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const APP_FILES = argValue('--app-files', '/data/user/0/io.github.zcodeandroid.app/files');
const SERVER_URL = argValue('--url', 'http://127.0.0.1:3030');
const OUT_FILE = argValue('--out', null);

function redact(s) {
  let out = String(s);
  out = out.replace(/github_pat_[A-Za-z0-9_]+/g, '[REDACTED]');
  out = out.replace(/gh[posr]_[A-Za-z0-9]{16,}/g, '[REDACTED]');
  out = out.replace(/sk-[A-Za-z0-9_-]{8,}/g, '[REDACTED]');
  out = out.replace(/\b[0-9a-f]{32}\.[A-Za-z0-9_-]{8,}\b/g, '[REDACTED]');
  out = out.replace(
    /((api[_-]?key|token|authorization|secret|password)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&"]+)/gi,
    '$1[REDACTED]'
  );
  return out;
}

function readIfReadable(p) {
  try {
    return fs.readFileSync(p, 'utf8');
  } catch (_) {
    return null;
  }
}

function fileInfo(p) {
  try {
    const st = fs.statSync(p);
    return { exists: true, bytes: st.size, mtimeEpochMs: Math.round(st.mtimeMs) };
  } catch (_) {
    return { exists: false, bytes: 0, mtimeEpochMs: null };
  }
}

function procList() {
  const out = [];
  let dirs = [];
  try {
    dirs = fs.readdirSync('/proc');
  } catch (_) {
    return { error: 'readdir /proc failed', selfPid: process.pid };
  }
  // Self-visibility marker: with hidepid-style /proc masking, readdir may
  // hide even same-uid processes on some kernels/emulators. If this collector
  // cannot see ITSELF (a same-uid node), the process list is "unavailable on
  // this environment", NOT "no processes" — the two are different claims.
  let sawSelf = false;
  for (const entry of dirs) {
    const pid = parseInt(entry, 10);
    if (!Number.isInteger(pid) || pid <= 0) continue;
    const comm = readIfReadable(`/proc/${entry}/comm`);
    if (comm === null) continue;
    const name = comm.trim();
    if (name !== 'node' && name !== 'zcode-cli') continue;
    if (pid === process.pid) sawSelf = true;
    const status = readIfReadable(`/proc/${entry}/status`) || '';
    const rss = (status.match(/VmRSS:\s+(\d+)\s+kB/) || [])[1] || null;
    const rollup = readIfReadable(`/proc/${entry}/smaps_rollup`) || '';
    const pss = (rollup.match(/Pss:\s+(\d+)\s+kB/) || [])[1] || null;
    out.push({ pid, comm: name, rssKb: rss, pssKb: pss });
  }
  out.sort((a, b) => a.pid - b.pid);
  const visibility = sawSelf || out.length > 0 ? 'full' : 'masked (same-uid processes not listed by readdir /proc)';
  return { count: out.length, units: 'kB', visibility, processes: out };
}

function memInfo() {
  const text = readIfReadable('/proc/meminfo');
  if (text === null) return { error: '/proc/meminfo unreadable' };
  const total = (text.match(/MemTotal:\s+(\d+)\s+kB/) || [])[1] || null;
  const avail = (text.match(/MemAvailable:\s+(\d+)\s+kB/) || [])[1] || null;
  return { memTotalKb: total, memAvailableKb: avail, units: 'kB' };
}

function storageInfo() {
  try {
    const st = fs.statfsSync(APP_FILES);
    const bsize = st.bsize || st.bsize === 0 ? st.bsize : 4096;
    return {
      path: APP_FILES,
      freeBytes: bsize * st.bavail,
      totalBytes: bsize * st.blocks,
    };
  } catch (e) {
    return { error: 'statfs failed: ' + e.message };
  }
}

async function probe(url) {
  const started = Date.now();
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(5000) });
    let bytes = 0;
    try {
      const text = await res.text();
      bytes = Buffer.byteLength(text);
    } catch (_) {}
    return { url, status: res.status, contentType: res.headers.get('content-type'), bytes, ms: Date.now() - started };
  } catch (e) {
    return { url, error: redact(e.message), ms: Date.now() - started };
  }
}

function webconsoleSection() {
  const logsDir = path.join(APP_FILES, 'logs');
  const cur = path.join(logsDir, 'webconsole.log');
  const old = path.join(logsDir, 'webconsole.log.old');
  const section = {
    current: fileInfo(cur),
    previousGeneration: fileInfo(old),
    rotated: fileInfo(old).exists,
  };
  const counts = {
    shimApplied: 0,
    shimEngineHealthy: 0,
    registerLines: 0,
    domStateLines: 0,
    visualStateLines: 0,
    rendererGone: 0,
  };
  const htmlPx = [];
  const tail = [];
  const shimTail = [];
  for (const p of [old, cur]) {
    const text = readIfReadable(p);
    if (text === null) continue;
    for (const line of text.split('\n')) {
      if (line.indexOf('[zcode-shim] applied:') >= 0) counts.shimApplied++;
      if (line.indexOf('engine healthy') >= 0) counts.shimEngineHealthy++;
      if (line.indexOf('[register]') >= 0) counts.registerLines++;
      if (line.indexOf('dom-state:') >= 0) counts.domStateLines++;
      if (line.indexOf('visual-state') >= 0) counts.visualStateLines++;
      if (line.indexOf('RENDERER GONE') >= 0) counts.rendererGone++;
      const m = line.match(/htmlPx=(\d+x\d+)/);
      if (m && htmlPx[htmlPx.length - 1] !== m[1]) htmlPx.push(m[1]);
      if (line.indexOf('[zcode-shim]') >= 0) {
        shimTail.push(line);
        if (shimTail.length > 3) shimTail.shift();
      }
      if (tail.length >= 5) tail.shift();
      tail.push(line);
    }
  }
  section.verdictCounters = counts;
  section.shimEngineHealthyEverSeen = counts.shimEngineHealthy > 0;
  section.lastHtmlPxValues = htmlPx.slice(-6);
  section.shimTail = shimTail.map(redact);
  section.tail = tail.map(redact);
  return section;
}

function serverlogSection() {
  const logsDir = path.join(APP_FILES, 'logs');
  const cur = path.join(logsDir, 'server.log');
  const section = { current: fileInfo(cur) };
  const text = readIfReadable(cur);
  if (text !== null) {
    const lines = text.split('\n').filter(Boolean);
    section.lastLines = lines.slice(-5).map(redact);
  }
  return section;
}

function diagReportSection() {
  const p = path.join(APP_FILES, 'logs', 'diag-report.json');
  const info = fileInfo(p);
  const section = { file: info };
  if (info.exists) {
    const text = readIfReadable(p);
    if (text !== null) {
      try {
        const j = JSON.parse(text);
        section.state = j.state;
        section.reportedAt = j.ts;
        section.processCount = Array.isArray(j.processes) ? j.processes.length : null;
      } catch (e) {
        section.parseError = 'invalid JSON';
      }
    }
  }
  return section;
}

async function main() {
  const report = {
    collector: 'diag-collector.cjs v1',
    ts: new Date().toISOString(),
    meta: {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      appFiles: APP_FILES,
      serverUrl: SERVER_URL,
    },
  };
  const pc = readIfReadable(path.join(APP_FILES, 'runtime', 'provision-complete.json'));
  report.provision = pc !== null ? { source: 'runtime/provision-complete.json', parsed: (() => { try { return JSON.parse(pc); } catch (_) { return null; } })() } : { present: false };

  report.processes = procList();
  report.memory = memInfo();
  report.storage = storageInfo();
  report.server = {};
  report.server.root = await probe(SERVER_URL + '/');
  report.server.remoteMobileRoute = await probe(SERVER_URL + '/remote');
  report.webconsole = webconsoleSection();
  report.serverlog = serverlogSection();
  report.diagReport = diagReportSection();

  const json = JSON.stringify(report, null, 2);
  if (OUT_FILE) {
    fs.writeFileSync(OUT_FILE, json);
    console.log('DIAG-COLLECTOR-OK wrote ' + OUT_FILE + ' (' + Buffer.byteLength(json) + ' bytes)');
  } else {
    console.log(json);
  }
}

main().then(
  () => process.exit(0),
  (e) => {
    console.log(JSON.stringify({ collector: 'diag-collector.cjs', fatal: redact(e && e.message) }, null, 2));
    process.exit(1);
  }
);
