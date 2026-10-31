#!/usr/bin/env node
// verify-second-send.cjs — regression for the on-device "second message in the
// same conversation fails" defect (owner device diagnostic, 2026-10-31).
//
// ROOT CAUSE (upstream packages/services/src/project/index.ts route()): the
// standalone server's ownerHostId is a Source-Server identity
// (remote:server:<id>) while the web UI, for an EXISTING session opened from
// the task list, sends hostId "local" (tab has no workspaceIdentity).
// SessionPane.tsx dispatchSendTextAfterConfig calls
// projectService.resolveSessionDirectories(task, "local") BEFORE dispatching
// the send; route() rejected "local" as a non-owner host with
// project.host-offline, so the second message died pre-admission (no DB row).
// The FIRST message of a new chat skips that call (sessionId null) — hence
// "first message works, second fails".
//
// This driver reproduces the user path at the exact RPC boundary over the
// REAL server WebSocket, with no storage bypass:
//   PROBE A  project.readTree({hostId:"local"})          — route() behavior
//   PROBE B  zcode-agent.createSession({projectId,...})  — creates a REAL task
//            row via prepareSessionCreation->reserveCreatedTask, then
//            project.resolveSessionDirectories({workspacePath,taskId},"local")
//            — the EXACT call SessionPane makes before the second send.
// UNPATCHED server: PROBE A/B fail with "project.host-offline" (exit 4).
// PATCHED server:   both return data; prints SECOND-SEND-REGRESSION-OK (exit 0).
//
// Usage:
//   node verify-second-send.cjs --url http://127.0.0.1:3030 \
//     --workspace /data/.../files/workspace --dist <path to zcode.mjs> \
//     [--provider verify-mock] [--model mock-model] [--timeout 180]

"use strict";

const { createRequire } = require("module");
const path = require("path");

function parseArgs(argv) {
  const o = {
    url: "http://127.0.0.1:3030", workspace: null, dist: null,
    provider: "verify-mock", model: "mock-model", timeout: 180,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[++i] === undefined ? null : argv[i];
    if (a === null) { console.error("missing value for " + argv[i - 1]); process.exit(2); }
    if (argv[i - 1] === "--url") o.url = a;
    else if (argv[i - 1] === "--workspace") o.workspace = a;
    else if (argv[i - 1] === "--dist") o.dist = a;
    else if (argv[i - 1] === "--provider") o.provider = a;
    else if (argv[i - 1] === "--model") o.model = a;
    else if (argv[i - 1] === "--timeout") o.timeout = Number(a) || 180;
    else { console.error("unknown arg: " + argv[i - 1]); process.exit(2); }
  }
  if (!o.workspace) { console.error("usage: --workspace <dir> is required"); process.exit(2); }
  return o;
}

const opts = parseArgs(process.argv.slice(2));

let WebSocketImpl;
try {
  WebSocketImpl = require("ws");
  console.log("[2nd-send] ws module: kit-local require");
} catch (e) {
  if (!opts.dist) {
    console.error("[2nd-send] ws unavailable and --dist not provided: " + e.message);
    process.exit(2);
  }
  WebSocketImpl = createRequire(path.resolve(opts.dist))("ws");
  console.log("[2nd-send] ws module: resolved from dist node_modules");
}

// ---------------------------------------------------------------- VQL wire
const T = { UNDEF: 0, STRING: 1, BUFFER: 2, VSBUFFER: 3, ARRAY: 4, OBJECT: 5, INT: 6 };

function writeVQL(value) {
  if (value === 0) return Buffer.from([0]);
  const out = [];
  let v = value >>> 0;
  while (v !== 0) { out.push(v & 0x7f); v = v >>> 7; }
  for (let i = 0; i < out.length; i++) {
    if (i < out.length - 1) out[i] |= 0x80;
  }
  return Buffer.from(out);
}

function serialize(data) {
  if (data === undefined) return Buffer.from([T.UNDEF]);
  if (typeof data === "string") {
    const raw = Buffer.from(data, "utf8");
    return Buffer.concat([Buffer.from([T.STRING]), writeVQL(raw.length), raw]);
  }
  if (Buffer.isBuffer(data)) {
    return Buffer.concat([Buffer.from([T.BUFFER]), writeVQL(data.length), data]);
  }
  if (Array.isArray(data)) {
    const parts = [Buffer.from([T.ARRAY]), writeVQL(data.length)];
    for (const el of data) parts.push(serialize(el));
    return Buffer.concat(parts);
  }
  if (typeof data === "number" && Number.isInteger(data)) {
    return Buffer.concat([Buffer.from([T.INT]), writeVQL(data)]);
  }
  const raw = Buffer.from(JSON.stringify(data), "utf8");
  return Buffer.concat([Buffer.from([T.OBJECT]), writeVQL(raw.length), raw]);
}

const Reader = {
  create(buf) { return { buf, pos: 0 }; },
  read(r, n) {
    if (r.pos + n > r.buf.length) throw new Error("VQL read past end (" + n + " at " + r.pos + ")");
    const out = r.buf.subarray(r.pos, r.pos + n);
    r.pos += n;
    return out;
  },
  vql(r) {
    let value = 0;
    for (let shift = 0; ; shift += 7) {
      const b = Reader.read(r, 1)[0];
      value |= (b & 0x7f) << shift;
      if (!(b & 0x80)) return value;
    }
  },
  data(r) {
    const tag = Reader.read(r, 1)[0];
    switch (tag) {
      case T.UNDEF: return undefined;
      case T.STRING: return Reader.read(r, Reader.vql(r)).toString("utf8");
      case T.BUFFER: return Reader.read(r, Reader.vql(r));
      case T.VSBUFFER: return Reader.read(r, Reader.vql(r));
      case T.ARRAY: {
        const n = Reader.vql(r);
        const out = [];
        for (let i = 0; i < n; i++) out.push(Reader.data(r));
        return out;
      }
      case T.OBJECT: return JSON.parse(Reader.read(r, Reader.vql(r)).toString("utf8"));
      case T.INT: return Reader.vql(r);
      default: throw new Error("unknown VQL tag " + tag);
    }
  },
};

const HEADER_SIZE = 13;
const MSG_REGULAR = 1;

// ------------------------------------------------------------- RPC client
class ChannelClient {
  constructor(wsUrl) {
    this.nextId = 1;
    this.pending = new Map();
    this.initialized = false;
    this.chunks = [];
    this.chunkLen = 0;
    this.ws = new WebSocketImpl(wsUrl, { perMessageDeflate: false });
    this.ws.on("message", (data) => this.onData(Buffer.isBuffer(data) ? data : Buffer.from(data)));
    this.ws.on("close", (code, reason) => this.failAll("socket closed " + code + " " + (reason || "")));
    this.ws.on("error", (e) => this.failAll("socket error: " + e.message));
  }

  open(timeoutMs) {
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error("ws open timeout")), timeoutMs);
      this.ws.once("open", () => { clearTimeout(t); resolve(); });
      this.ws.once("error", (e) => { clearTimeout(t); reject(e); });
    });
  }

  onData(chunk) {
    this.chunks.push(chunk);
    this.chunkLen += chunk.length;
    for (;;) {
      if (this.chunkLen < HEADER_SIZE) return;
      const head = Buffer.concat(this.chunks, this.chunkLen).subarray(0, HEADER_SIZE);
      const len = head.readUInt32BE(9);
      if (this.chunkLen < HEADER_SIZE + len) return;
      const frame = this.take(HEADER_SIZE + len);
      this.onFrame(frame.subarray(HEADER_SIZE));
    }
  }

  take(n) {
    const out = Buffer.alloc(n);
    let off = 0;
    while (off < n) {
      const c = this.chunks[0];
      const need = n - off;
      if (c.length <= need) {
        c.copy(out, off);
        off += c.length;
        this.chunks.shift();
      } else {
        c.copy(out, off, 0, need);
        this.chunks[0] = c.subarray(need);
        off += need;
      }
    }
    this.chunkLen -= n;
    return out;
  }

  onFrame(payload) {
    const reader = Reader.create(payload);
    let header;
    let body;
    try {
      header = Reader.data(reader);
      body = reader.pos < payload.length ? Reader.data(reader) : undefined;
    } catch (e) {
      console.log("[2nd-send] frame decode error: " + e.message);
      return;
    }
    const [type, id] = header;
    if (type === 200) { this.initialized = true; return; }
    if (type === 201 || type === 202) {
      const p = this.pending.get(id);
      if (!p) return;
      this.pending.delete(id);
      if (type === 201) p.resolve(body);
      else p.reject(new Error((body && (body.message || body.name)) || "RPC error"));
      return;
    }
    if (type === 100 || type === 102) {
      // Server->client request (rare): answer with an empty result so the
      // server never waits on us.
      this.rawSend(Buffer.concat([serialize([201, id])]), {});
      return;
    }
    // events / anything else: ignore
  }

  rawSend(headerPayload, arg) {
    const payload = Buffer.concat([headerPayload, serialize(arg)]);
    const frame = Buffer.alloc(HEADER_SIZE + payload.length);
    frame.writeUInt8(MSG_REGULAR, 0);
    frame.writeUInt32BE(0, 1);
    frame.writeUInt32BE(0, 5);
    frame.writeUInt32BE(payload.length, 9);
    payload.copy(frame, HEADER_SIZE);
    this.ws.send(frame, { binary: true });
  }

  call(channel, command, arg, timeoutMs) {
    return this.callArgs(channel, command, arg === undefined ? [] : [arg], timeoutMs);
  }

  callArgs(channel, command, argsArray, timeoutMs) {
    const id = this.nextId++;
    // ProxyChannel convention (upstream packages/rpc/src/proxy-channel.ts):
    // the wire body is the ARRAY of the method's positional arguments
    // (client toService -> channel.call(name, methodArgs); server
    // target.apply(handler, args || [])). A bare object would be spread as
    // zero arguments -> saveProject(undefined) -> zod invalid_type.
    // Multi-arg methods (resolveSessionDirectories(task, hostId)) MUST pass
    // the positional array via callArgs — otherwise the 2nd arg is silently
    // dropped and the wire call is not the one the UI makes.
    const wireArg = Array.isArray(argsArray) ? argsArray : [];
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("RPC timeout: " + channel + "." + command));
      }, timeoutMs || 20000);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(t); resolve(v); },
        reject: (e) => { clearTimeout(t); reject(e); },
      });
      this.rawSend(serialize([100, id, channel, command]), wireArg);
    });
  }

  failAll(msg) {
    for (const [, p] of this.pending) p.reject(new Error(msg));
    this.pending.clear();
  }
}

function fail(msg, code) {
  console.error("[2nd-send] FAILED: " + msg);
  process.exit(code || 3);
}

async function main() {
  const wsUrl = opts.url.replace(/^http/, "ws") + "/ws";
  console.log("[2nd-send] connecting " + wsUrl);
  const client = new ChannelClient(wsUrl);
  await client.open(10000);
  const deadline = Date.now() + 15000;
  while (!client.initialized) {
    if (Date.now() > deadline) fail("no protocol initialize within 15s");
    await new Promise((r) => setTimeout(r, 100));
  }
  console.log("[2nd-send] channel protocol initialized");

  // ---- ensure the workspace is a registered project (official path, idempotent)
  let projects;
  try {
    projects = await client.call("project", "readProjects", undefined, 20000);
  } catch (e) {
    fail("readProjects: " + e.message);
  }
  const list = Array.isArray(projects) ? projects : [];
  let project = list.find((p) => (p.roots || []).some((root) => root.rootPath === opts.workspace));
  if (!project) {
    project = await client.call("project", "saveProject", { roots: [opts.workspace] }, 30000);
    console.log("[2nd-send] saveProject OK id=" + (project && project.id));
  }
  const projectId = project && project.id;
  if (!projectId) fail("no projectId for workspace " + opts.workspace);
  console.log("[2nd-send] project id=" + projectId + " root=" + opts.workspace);

  // ---- PROBE A: route() behavior — readTree with the UI's local hostId
  let probeA = "PASS";
  try {
    const tree = await client.call("project", "readTree",
      { hostId: "local", includeTasks: true }, 20000);
    const taskCount = (tree && Array.isArray(tree.tasks)) ? tree.tasks.length : -1;
    console.log("[2nd-send] PROBE A readTree(local): OK tasks=" + taskCount);
  } catch (e) {
    probeA = "FAIL";
    console.log("[2nd-send] PROBE A readTree(local): " + e.message);
  }

  // ---- PROBE B: the EXACT SessionPane pre-send call with a REAL session
  let probeB = "PASS";
  let sessionId = null;
  try {
    const snapshot = await client.call("zcode-agent", "createSession", {
      workspacePath: opts.workspace,
      projectId,
      mode: "yolo",
      model: {
        providerId: opts.provider,
        modelId: opts.model,
        options: { reasoningLevel: "disabled" },
      },
    }, 90000);
    sessionId = snapshot && snapshot.session && snapshot.session.sessionId;
    if (!sessionId) throw new Error("createSession returned no sessionId");
    console.log("[2nd-send] createSession OK session=" + sessionId);
    const dirs = await client.callArgs("project", "resolveSessionDirectories",
      [{ workspacePath: opts.workspace, taskId: sessionId }, "local"], 20000);
    console.log("[2nd-send] PROBE B resolveSessionDirectories(local): OK projectId=" +
      (dirs && dirs.projectId) + " additionalDirectories=" +
      JSON.stringify((dirs && dirs.additionalDirectories) || []));
  } catch (e) {
    probeB = "FAIL";
    console.log("[2nd-send] PROBE B createSession/resolveSessionDirectories(local): " + e.message);
  }

  console.log("[2nd-send] verdict: PROBE A=" + probeA + " PROBE B=" + probeB);
  if (probeA === "PASS" && probeB === "PASS") {
    console.log("SECOND-SEND-REGRESSION-OK");
    process.exit(0);
  }
  const offline = (probeA === "FAIL" || probeB === "FAIL");
  console.error("[2nd-send] SECOND-SEND-REGRESSION-FAILED" +
    (offline ? " (project.host-offline class defect present)" : ""));
  process.exit(4);
}

const hardStop = setTimeout(() => {
  console.error("[2nd-send] FAILED: global timeout after " + opts.timeout + "s");
  process.exit(3);
}, opts.timeout * 1000);
hardStop.unref();

main().catch((e) => fail(e && e.message ? e.message : String(e)));
