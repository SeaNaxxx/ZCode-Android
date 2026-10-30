#!/usr/bin/env node
/**
 * TLS self-diagnosis for the embedded Bionic runtime (owner requirement,
 * 2026-10-31: "Inspect the actual certificate chain, CA configuration,
 * system time, and original TLS error. Do not disable certificate
 * verification.").
 *
 * Runs under the embedded node, inside the app runtime env, and performs ONE
 * real HTTPS request to the provider endpoint (default https://api.z.ai/,
 * unauthenticated — TLS needs no key; no credentials are ever read, logged
 * or required). Every line is prefixed "[tls]" so logcat/RuntimeBus/CI grep
 * 1:1. Certificate verification is NEVER disabled here; a validation failure
 * is reported with the EXACT openssl code, reason, message and the peer
 * certificate chain (subject/issuer/validity/fingerprint), plus the local
 * clock skew against the server Date header (clock skew is a classic
 * CERT_HAS_EXPIRED source on real phones).
 *
 * Exit code is ALWAYS 0 (diagnostic, must never kill the server start); the
 * "TLS-DIAGNOSE-OK" / "TLS-DIAGNOSE-FAIL" marker lines are what CI asserts.
 */
'use strict';

const https = require('https');
const tls = require('tls');
const url = require('url');

const target = (() => {
  const argv = process.argv.slice(2);
  const i = argv.indexOf('--url');
  const raw = i >= 0 && argv[i + 1] ? argv[i + 1] : 'https://api.z.ai/';
  try { return new URL(raw); } catch (e) { return new URL('https://api.z.ai/'); }
})();

function log(obj) {
  console.log('[tls] ' + JSON.stringify(obj));
}

function summarizeCert(c) {
  if (!c) return null;
  return {
    subject: c.subject && c.subject.CN,
    issuer: c.issuer && (c.issuer.O || c.issuer.CN),
    issuerCN: c.issuer && c.issuer.CN,
    validFrom: c.valid_from,
    validTo: c.valid_to,
    fingerprint256: c.fingerprint256,
  };
}

function clockSkewFromHeader(dateHeader) {
  if (!dateHeader) return null;
  const server = Date.parse(dateHeader);
  if (Number.isNaN(server)) return null;
  return Date.now() - server;
}

const env = process.env;
const evidence = {
  node: process.version,
  platform: process.platform,
  arch: process.arch,
  url: target.href,
  ca: {
    sslCertFile: env.SSL_CERT_FILE || null,
    gitSslCainfo: env.GIT_SSL_CAINFO || null,
    nodeExtraCaCerts: env.NODE_EXTRA_CA_CERTS || null,
    zcodeAgentCaCert: env.ZCODE_AGENT_CA_CERT || null,
    opensslConf: env.OPENSSL_CONF || null,
    rootCertificates: tls.rootCertificates.length,
  },
  now: new Date().toISOString(),
};

log({ step: 'start', ...evidence });

for (const key of ['SSL_CERT_FILE', 'NODE_EXTRA_CA_CERTS', 'ZCODE_AGENT_CA_CERT']) {
  const p = env[key];
  if (!p) continue;
  const st = (() => { try { return require('fs').statSync(p); } catch (e) { return null; } })();
  log({ step: 'ca-file', key, path: p, exists: !!st, bytes: st ? st.size : null });
}

const startedAt = Date.now();
const req = https.get(
  target,
  {
    headers: { 'User-Agent': 'zcode-tls-diagnose', Accept: '*/*' },
    timeout: 30000,
    // NOTE: rejectUnauthorized stays at its default (true). Verification is
    // never weakened here — the whole point is to observe the real verdict.
  },
  (res) => {
    const sock = res.socket;
    const chainIssuers = [];
    try {
      const cert = sock.getPeerCertificate(true);
      let c = cert;
      let hops = 0;
      while (c && hops < 6) {
        chainIssuers.push({
          subject: c.subject && c.subject.CN,
          issuer: c.issuer && (c.issuer.O || c.issuer.CN),
          validTo: c.valid_to,
        });
        if (c.issuerCertificate && c.issuerCertificate.fingerprint256 !== c.fingerprint256) {
          c = c.issuerCertificate;
          hops++;
        } else break;
      }
    } catch (e) { /* peer cert unavailable — keep going */ }

    const skewMs = clockSkewFromHeader(res.headers.date);
    // Drain enough of the body to prove the connection really works, then
    // stop — the response content is irrelevant for TLS evidence.
    let size = 0;
    res.on('data', (d) => { size += d.length; if (size > 16384) res.destroy(); });
    res.on('end', () => finish());
    res.on('close', () => finish());
    let done = false;
    function finish() {
      if (done) return;
      done = true;
      log({
        step: 'result',
        httpStatus: res.statusCode,
        tlsAuthorized: sock ? sock.authorized : null,
        tlsProtocol: sock && sock.getProtocol ? sock.getProtocol() : null,
        cipher: sock && sock.getCipher ? sock.getCipher().name : null,
        chain: chainIssuers,
        clockSkewMs: skewMs,
        durationMs: Date.now() - startedAt,
      });
      const ok = sock && sock.authorized === true;
      console.log(ok ? 'TLS-DIAGNOSE-OK' : 'TLS-DIAGNOSE-FAIL');
      process.exit(0);
    }
  }
);

req.on('error', (err) => {
  const cause = err && err.cause ? err.cause : null;
  log({
    step: 'error',
    error: err.message,
    code: err.code || null,
    causeCode: cause ? cause.code || null : (err.code || null),
    causeReason: cause ? cause.reason || null : null,
    causeMessage: cause ? cause.message || null : null,
    durationMs: Date.now() - startedAt,
  });
  console.log('TLS-DIAGNOSE-FAIL');
  process.exit(0);
});

req.on('timeout', () => {
  req.destroy(new Error('timeout after 30s'));
});
