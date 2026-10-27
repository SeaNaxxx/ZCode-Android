#!/usr/bin/env node
/**
 * E2 device-side ONLINE HTTPS evidence probe (failure 2 acceptance criterion:
 * "A real HTTPS request succeeds with certificate verification enabled").
 *
 * Runs under the embedded Bionic node with the network re-enabled. Proves:
 *  1. node/OpenSSL starts cleanly in the app runtime (no Termux paths);
 *  2. a real HTTPS request to the public internet succeeds;
 *  3. the server certificate chains to a PUBLIC root and verifies
 *     (socket.authorized === true) — verification is never disabled here;
 *  4. no environment variable leaks a com.termux path.
 *
 * Exit 0 + "TLS-HTTPS-OK ..." = pass.
 */
'use strict';
const assert = require('assert');
const fs = require('fs');
const https = require('https');
const tls = require('tls');

const url = process.argv[2] || 'https://api.github.com/zen';

const conf = process.env.OPENSSL_CONF || '';
assert(conf.length > 0, 'OPENSSL_CONF is not set');
assert(!conf.includes('com.termux'), 'OPENSSL_CONF leaks a Termux path: ' + conf);
fs.accessSync(conf, fs.constants.R_OK);

const leaks = Object.entries(process.env)
  .filter(([k, v]) => typeof v === 'string' && v.includes('com.termux'))
  .map(([k]) => k);
assert.strictEqual(leaks.length, 0, 'ENV LEAKS TERMUX PATHS: ' + leaks.join(','));
assert(!process.env.NODE_TLS_REJECT_UNAUTHORIZED,
  'NODE_TLS_REJECT_UNAUTHORIZED must never be set');
assert(tls.rootCertificates.length > 100, 'node root store unexpectedly small');

https.get(url, { headers: { 'User-Agent': 'zcode-e2-tls-probe', Accept: '*/*' }, timeout: 25000 }, (res) => {
  let size = 0;
  res.on('data', (d) => { size += d.length; if (size > 65536) res.destroy(); });
  res.on('end', () => {
    const evidence = {
      node: process.version,
      platform: process.platform,
      arch: process.arch,
      url,
      httpsStatus: res.statusCode,
      tlsAuthorized: res.socket.authorized,
      tlsProtocol: res.socket.getProtocol(),
      tlsCipher: res.socket.getCipher().name,
      rootCertificates: tls.rootCertificates.length,
      opensslConf: conf,
    };
    assert.strictEqual(res.statusCode, 200, 'HTTPS status ' + res.statusCode);
    assert.strictEqual(res.socket.authorized, true,
      'certificate NOT authorized: ' + res.socket.authorizationError);
    console.log('TLS-HTTPS-OK ' + JSON.stringify(evidence));
    process.exit(0);
  });
}).on('timeout', function () { this.destroy(new Error('request timeout')); })
  .on('error', (e) => {
    console.error('TLS-HTTPS-FAIL ' + e.message);
    process.exit(1);
  });
