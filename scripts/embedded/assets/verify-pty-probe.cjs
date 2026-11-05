#!/usr/bin/env node
// PTY evidence probe (REVISED architecture §10 / DEPENDENCY-AUDIT touchpoint 6):
// runs INSIDE the app's Bionic runtime via run-as node. Establishes, with
// evidence, whether the interactive-terminal path (node-pty) can work on
// this device — SEPARATELY from the agent Bash tool path (which does not
// use a PTY; agent-effects already proves that path end-to-end).
//
// What it reports (each line is a verdict ingredient, never a guess):
//   1. module discovery: where node-pty lives in the extracted runtime
//      (walk of $APP_FILES/runtime, depth-limited), or ABSENT.
//   2. load: require() result or the exact load error (path tried, ABI).
//   3. spawn: a REAL pty.spawn of the embedded sh running `echo` + `id`,
//      collecting data until exit (require('node-pty') loading is NOT
//      sufficient evidence — the ChatGPT-style reports never proved spawn).
//
// Exit code 0 = evidence collected (POSITIVE or NEGATIVE); the verdict
// line PTY-PROBE-VERDICT is what CI/doctor reads. A negative result is a
// completed evidence run, not a probe failure — the terminal UI stays
// unbuilt either way until this says spawn works.
"use strict";

const fs = require("node:fs");
const path = require("node:path");

const APP_FILES = process.argv[2] || "/data/data/io.github.zcodeandroid.app/files";
const RT = path.join(APP_FILES, "runtime");
const MARKER = "PTY-PROBE-MARKER-7f3a";

function walk(dir, depth, out) {
  if (depth > 6 || out.length > 400) return;
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === "node-pty") out.push(p);
      else walk(p, depth + 1, out);
    }
  }
}

function findNodePtyDirs() {
  const out = [];
  walk(RT, 0, out);
  return out;
}

function tryLoad(dir) {
  try {
    const pty = require(dir);
    return { ok: true, pty, dir };
  } catch (e) {
    return { ok: false, error: String((e && e.message) || e), dir };
  }
}

function trySpawn(pty) {
  return new Promise((resolve) => {
    let out = "";
    let settled = false;
    const done = (result) => { if (!settled) { settled = true; resolve(result); } };
    try {
      const term = pty.spawn("sh", ["-c", "echo " + MARKER + "; id -u"], {
        cols: 80, rows: 24, cwd: RT, env: process.env,
      });
      const timer = setTimeout(() => {
        try { term.kill(); } catch {}
        done({ ok: false, error: "spawn timed out after 10s", output: out });
      }, 10000);
      term.onData((d) => { out += d; });
      term.onExit((code) => {
        clearTimeout(timer);
        done({ ok: out.includes(MARKER), output: out, exitCode: code });
      });
    } catch (e) {
      done({ ok: false, error: String((e && e.message) || e) });
    }
  });
}

(async () => {
  console.log("PTY-PROBE: runtime=" + RT);
  const dirs = findNodePtyDirs();
  console.log("PTY-PROBE: node-pty directories found: " + (dirs.length ? dirs.join(", ") : "ABSENT"));
  if (!dirs.length) {
    console.log("PTY-PROBE-VERDICT: NEGATIVE (module absent from runtime closure; interactive terminal stays unbuilt)");
    return;
  }
  let loaded = null;
  for (const d of dirs) {
    const r = tryLoad(d);
    console.log("PTY-PROBE: load " + d + " -> " + (r.ok ? "OK" : "ERROR: " + r.error));
    if (r.ok && !loaded) loaded = r;
  }
  if (!loaded) {
    console.log("PTY-PROBE-VERDICT: NEGATIVE (load failed — ABI/binary mismatch; evidence above names the exact missing binary)");
    return;
  }
  const spawn = await trySpawn(loaded.pty);
  console.log("PTY-PROBE: spawn -> " + (spawn.ok ? "OK" : "ERROR: " + (spawn.error || "marker missing")) +
    (spawn.output ? " output=" + JSON.stringify(spawn.output.slice(0, 200)) : ""));
  console.log(spawn.ok
    ? "PTY-PROBE-VERDICT: POSITIVE (module loads AND a real pty.spawn produced output; terminal UI work may proceed on this evidence)"
    : "PTY-PROBE-VERDICT: NEGATIVE (module loads but pty.spawn failed; terminal UI stays unbuilt)");
})();
