#!/usr/bin/env node
/**
 * verify-web-api-contract.cjs — contract guard for the web surface the app
 * depends on (external review note 3, 2026-11).
 *
 * Why: the local UI experience rides on upstream's HTTP surface served by the
 * standalone server (static root + SPA fallback + /api/* + /ws). That surface
 * is INTERNAL to upstream — undocumented and free to move with the pinned
 * commit. This test pins the observable contract the app relies on and FAILS
 * the build when it moves, so an upstream pin bump can never silently break
 * the phone UI:
 *
 *   GET /        -> 200 + text/html        (the web UI entry)
 *   GET /remote  -> 200 + text/html        (SPA fallback; the upstream bundle's
 *                                           built-in mobile remote-control route —
 *                                           server http.ts: isStaticFallbackAllowed)
 *   GET /ws      -> any status EXCEPT 404  (WebSocket endpoint exists; a plain
 *                                           GET must be rejected, not missing)
 *   GET /api/__contract_probe__ -> 404     (AUTHORED CONTROL: /api/* must NOT
 *                                           SPA-fallback — proves the 404
 *                                           semantics this contract rests on)
 *
 * Route-existence semantics: 401/403/405/426 all mean "exists but protected /
 * wrong method" -> PASS. Only 404 (or no answer) fails.
 *
 * Auth-free by design: no credentials are sent, stored, or printed. Runs on
 * the device under the embedded node against 127.0.0.1:3030 (CI stage
 * contract-api), or anywhere with network access to the server.
 *
 * Usage: node verify-web-api-contract.cjs [--url http://127.0.0.1:3030]
 * Exit: 0 = CONTRACT-OK, 1 = CONTRACT-FAIL.
 */
'use strict';

function argValue(flag, fallback) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const BASE = argValue('--url', 'http://127.0.0.1:3030');

async function probe(path) {
  const started = Date.now();
  try {
    const res = await fetch(BASE + path, { signal: AbortSignal.timeout(8000) });
    let contentType = res.headers.get('content-type') || '';
    let bytes = 0;
    try {
      bytes = Buffer.byteLength(await res.text());
    } catch (_) {}
    return { path, status: res.status, contentType, bytes, ms: Date.now() - started };
  } catch (e) {
    return { path, error: String(e && e.message), ms: Date.now() - started };
  }
}

async function main() {
  const results = [];
  let failures = 0;

  function check(name, ok, detail) {
    if (!ok) failures++;
    console.log((ok ? 'CONTRACT-PASS' : 'CONTRACT-FAIL') + ' ' + name + ': ' + detail);
  }

  const root = await probe('/');
  results.push(root);
  check(
    'GET /',
    root.status === 200 && String(root.contentType).includes('text/html'),
    'status=' + root.status + ' type=' + root.contentType + ' bytes=' + (root.bytes || 0)
  );

  const remote = await probe('/remote');
  results.push(remote);
  check(
    'GET /remote (mobile remote-control route, SPA fallback)',
    remote.status === 200 && String(remote.contentType).includes('text/html'),
    'status=' + remote.status + ' type=' + remote.contentType + ' bytes=' + (remote.bytes || 0)
  );

  const ws = await probe('/ws');
  results.push(ws);
  check(
    'GET /ws (WebSocket route exists, plain GET rejected)',
    ws.status !== undefined && ws.status !== 404,
    'status=' + ws.status
  );

  const control = await probe('/api/__contract_probe__');
  results.push(control);
  check(
    'GET /api/__contract_probe__ (authored control: /api/* must not SPA-fallback)',
    control.status === 404,
    'status=' + control.status
  );

  console.log('CONTRACT-SUMMARY ' + JSON.stringify(results));
  if (failures > 0) {
    console.log('CONTRACT-FAIL failures=' + failures + ' (upstream surface moved at the pinned commit — update the app or the pin deliberately)');
    process.exit(1);
  }
  console.log('CONTRACT-OK all ' + results.length + ' assertions hold at ' + BASE);
}

main().then(
  () => process.exit(0),
  (e) => {
    console.log('CONTRACT-FAIL fatal: ' + String(e && e.message));
    process.exit(1);
  }
);
