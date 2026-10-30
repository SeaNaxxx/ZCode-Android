#!/usr/bin/env node
// register-workspace-project.cjs — register the app workspace as a project on
// the local ZCode web server, using the OFFICIAL project service RPC
// (channel "project", command "saveProject") — the same call the official
// Projects UI makes. No storage bypass, no upstream changes.
//
// WHY: upstream design gives the standalone HTTP server a remote-style project
// storage host identity, so session creation (draft sessions) requires the
// workspace to be registered as a project with exactly one root == the
// workspace path (packages/services/src/project-storage/remote-policy.ts:
// assertRemoteTaskDirectories -> "project.remote-project-required"). Without
// registration every send fails with 草稿会话创建失败 / "Failed to send".
//
// WIRE (packages/rpc): 13-byte frame header [type=1][id u32be][ack u32be]
// [len u32be]; payload = serialize([100, reqId, channel, command]) +
// serialize(arg); serialize = VQL tagged: 0 undefined, 1 string, 2 buffer,
// 3 vsbuffer, 4 array, 5 object(JSON), 6 int. Responses: [201, id, data]
// success, [202, id, {message,...}] error, [200] initialize.
//
// Idempotent: readProjects first; if a project already contains the workspace
// root, exit 0 without writing.
//
// Usage:
//   node register-workspace-project.cjs --url http://127.0.0.1:3030 \
//     --workspace /data/.../files/workspace [--dist <path to zcode.mjs>]
//
// Exit codes: 0 registered (or already), 3 protocol/registration failure,
// 2 usage error.

"use strict";

const { createRequire } = require("module");
const path = require("path");

function parseArgs(argv) {
  const o = { url: "http://127.0.0.1:3030", workspace: null, dist: null, timeout: 60 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--url") o.url = argv[++i];
    else if (a === "--workspace") o.workspace = argv[++i];
    else if (a === "--dist") o.dist = argv[++i];
    else if (a === "--timeout") o.timeout = Number(argv[++i]) || 60;
    else { console.error("unknown arg: " + a); process.exit(2); }
  }
  if (!o.workspace) { console.error("usage: --workspace <dir> is required"); process.exit(2); }
  return o;
}

const opts = parseArgs(process.argv.slice(2));

// The dist embeds `ws` in its node_modules; resolve it from the dist entry so
// the kit script stays dependency-free.
let WebSocketImpl;
try {
  WebSocketImpl = require("ws");
  console.log("[register] ws module: kit-local require");
} catch (e) {
  if (!opts.dist) {
    console.error("[register] ws unavailable and --dist not provided: " + e.message);
    process.exit(2);
  }
  const distRequire = createRequire(path.resolve(opts.dist));
  WebSocketImpl = distRequire("ws");
  console.log("[register] ws module: resolved from dist node_modules");
}

// ---------------------------------------------------------------- VQL wire
const T = { UNDEF: 0, STRING: 1, BUFFER: 2, VSBUFFER: 3, ARRAY: 4, OBJECT: 5, INT: 6 };

function writeVQL(buf, value) {
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
    return Buffer.concat([Buffer.from([T.STRING]), writeVQL(0, raw.length), raw]);
  }
  if (Buffer.isBuffer(data)) {
    return Buffer.concat([Buffer.from([T.BUFFER]), writeVQL(0, data.length), data]);
  }
  if (Array.isArray(data)) {
    const parts = [Buffer.from([T.ARRAY]), writeVQL(0, data.length)];
    for (const el of data) parts.push(serialize(el));
    return Buffer.concat(parts);
  }
  if (typeof data === "number" && Number.isInteger(data)) {
    return Buffer.concat([Buffer.from([T.INT]), writeVQL(0, data)]);
  }
  const raw = Buffer.from(JSON.stringify(data), "utf8");
  return Buffer.concat([Buffer.from([T.OBJECT]), writeVQL(0, raw.length), raw]);
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
      console.log("[register] frame decode error: " + e.message);
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
      this.rawSend(serialize([201, id]), {});
      return;
    }
    // events / anything else: ignore
  }

  rawSend(headerBuf, arg) {
    const payload = Buffer.concat([headerBuf, serialize(arg)]);
    const frame = Buffer.alloc(HEADER_SIZE + payload.length);
    frame.writeUInt8(MSG_REGULAR, 0);
    frame.writeUInt32BE(0, 1);
    frame.writeUInt32BE(0, 5);
    frame.writeUInt32BE(payload.length, 9);
    payload.copy(frame, HEADER_SIZE);
    this.ws.send(frame, { binary: true });
  }

  call(channel, command, arg, timeoutMs) {
    const id = this.nextId++;
    // ProxyChannel convention (upstream packages/rpc/src/proxy-channel.ts):
    // the wire body is the ARRAY of the method's positional arguments
    // (client toService -> channel.call(name, methodArgs); server
    // target.apply(handler, args || [])). A bare object would be spread as
    // zero arguments -> saveProject(undefined) -> zod invalid_type.
    const wireArg = arg === undefined ? [] : [arg];
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

function fail(msg) {
  console.error("[register] FAILED: " + msg);
  process.exit(3);
}

async function main() {
  const wsUrl = opts.url.replace(/^http/, "ws") + "/ws";
  console.log("[register] connecting " + wsUrl);
  const client = new ChannelClient(wsUrl);
  await client.open(10000);

  const deadline = Date.now() + 15000;
  while (!client.initialized) {
    if (Date.now() > deadline) fail("no protocol initialize within 15s");
    await new Promise((r) => setTimeout(r, 100));
  }
  console.log("[register] channel protocol initialized");

  let projects;
  try {
    projects = await client.call("project", "readProjects", undefined, 20000);
  } catch (e) {
    fail("readProjects: " + e.message);
  }
  const list = Array.isArray(projects) ? projects : [];
  const already = list.some((p) =>
    (p.roots || []).some((root) => root.rootPath === opts.workspace));
  if (already) {
    const name = (list.find((p) => (p.roots || []).some((r) => r.rootPath === opts.workspace)) || {}).name;
    console.log("[register] already registered: project '" + name + "' root=" + opts.workspace);
    process.exit(0);
  }

  console.log("[register] existing projects: " +
    list.map((p) => p.name + " [" + (p.roots || []).map((r) => r.rootPath).join(", ") + "]").join("; "));
  let saved;
  try {
    saved = await client.call("project", "saveProject", { roots: [opts.workspace] }, 30000);
  } catch (e) {
    fail("saveProject: " + e.message);
  }
  console.log("[register] saveProject OK: id=" + (saved && saved.id) +
    " name=" + (saved && saved.name) +
    " roots=" + (saved && saved.roots ? saved.roots.map((r) => r.rootPath).join(", ") : "?"));

  // verify through the same official read path the session flow uses
  let verify;
  try {
    verify = await client.call("project", "readProjects", undefined, 20000);
  } catch (e) {
    fail("verify readProjects: " + e.message);
  }
  const vlist = Array.isArray(verify) ? verify : [];
  const ok = vlist.some((p) => (p.roots || []).some((root) => root.rootPath === opts.workspace));
  if (!ok) fail("workspace root not present after saveProject");
  console.log("[register] VERIFIED: workspace is a registered project root " +
    "(draft sessions can now resolve projectId)");
  process.exit(0);
}

const hardStop = setTimeout(() => {
  console.error("[register] FAILED: global timeout after " + opts.timeout + "s");
  process.exit(3);
}, opts.timeout * 1000);
hardStop.unref();

main().catch((e) => fail(e && e.message ? e.message : String(e)));
