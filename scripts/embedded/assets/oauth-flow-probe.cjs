#!/usr/bin/env node
/**
 * OAuth login-path self-probe for the embedded Bionic runtime.
 *
 * Owner report (2026-10-31): the agent only works in "start free" mode —
 * full Z AI login never completes on the phone. The standalone web UI's
 * login is a SERVER-side device flow (oauthService.startOAuthWithPolling):
 * POST /api/v1/oauth/cli/init returns an anonymous flow, the browser
 * authorizes, and GET /oauth/cli/poll/:flowId turns ready. This probe drives
 * exactly those two endpoints under the app runtime env with a locally
 * generated anonymous poll token:
 *   - NO user credentials are read, required or transmitted;
 *   - no authorization can complete (nothing approves the flow);
 *   - nothing is persisted anywhere.
 * A failure here (TLS to zcode.z.ai, envelope rejection, poll contract
 * drift) names the failing login layer before the UI is ever involved.
 *
 * Every line is prefixed "[oauth]" so logcat/RuntimeBus/CI grep 1:1.
 * Certificate verification is NEVER disabled. Exit code is ALWAYS 0
 * (diagnostic, must never kill the server start); the
 * "OAUTH-FLOW-PROBE-OK" / "OAUTH-FLOW-PROBE-FAIL" marker lines are what
 * CI asserts.
 */
'use strict';

const https = require('https');
const tls = require('tls');

const baseUrl = (() => {
  const argv = process.argv.slice(2);
  const i = argv.indexOf('--base-url');
  const raw = i >= 0 && argv[i + 1] ? argv[i + 1] : 'https://zcode.z.ai/api/v1';
  try { return new URL(raw).toString().replace(/\/$/, ''); } catch (e) {
    return 'https://zcode.z.ai/api/v1';
  }
})();

function log(obj) {
  console.log('[oauth] ' + JSON.stringify(obj));
}

// Safe URL evidence: origin + path only. Query strings carry flow-bound
// state/client parameters that do not belong in a paste-safe log.
function safeUrl(u) {
  try { const p = new URL(u); return p.origin + p.pathname; } catch (e) { return null; }
}

function summarizeCert(c) {
  if (!c) return null;
  return {
    subject: c.subject && c.subject.CN,
    issuer: c.issuer && (c.issuer.O || c.issuer.CN),
    validTo: c.valid_to,
  };
}

function chainOf(sock) {
  const chain = [];
  try {
    let c = sock.getPeerCertificate(true);
    let hops = 0;
    while (c && hops < 6) {
      chain.push(summarizeCert(c));
      if (c.issuerCertificate && c.issuerCertificate.fingerprint256 !== c.fingerprint256) {
        c = c.issuerCertificate;
        hops++;
      } else break;
    }
  } catch (e) { /* peer cert unavailable — keep going */ }
  return chain;
}

function clockSkewFromHeader(dateHeader) {
  if (!dateHeader) return null;
  const server = Date.parse(dateHeader);
  if (Number.isNaN(server)) return null;
  return Date.now() - server;
}

// One HTTPS request with certificate verification left at its default (true).
// Resolves { status, headers, body, sock } or rejects with the raw error.
function request(method, urlStr, headers, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      urlStr,
      {
        method,
        headers: Object.assign({ 'User-Agent': 'zcode-oauth-flow-probe' }, headers),
        timeout: 30000,
      },
      (res) => {
        // Capture the TLSSocket synchronously: res.socket can be null by the
        // time the response 'end' event fires (socket returned to the agent).
        const sock = res.socket;
        const chunks = [];
        let size = 0;
        res.on('data', (d) => {
          size += d.length;
          if (size <= 65536) chunks.push(d);
        });
        res.on('end', () => resolve({
          status: res.statusCode,
          headers: res.headers,
          body: Buffer.concat(chunks).toString('utf8'),
          sock,
        }));
      }
    );
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('timeout after 30s')));
    if (body) req.write(body);
    req.end();
  });
}

const TRANSIENT = new Set(['ENOTFOUND', 'EAI_AGAIN', 'ETIMEDOUT', 'ECONNREFUSED', 'ENETUNREACH']);

function errorEvidence(err) {
  const cause = err && err.cause ? err.cause : null;
  return {
    error: err && err.message,
    code: (err && err.code) || (cause && cause.code) || null,
    causeCode: cause ? cause.code || null : null,
    causeReason: cause ? cause.reason || null : null,
    causeMessage: cause ? cause.message || null : null,
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function attemptInit(pollToken) {
  for (let attemptsLeft = 3; ; attemptsLeft--) {
    try {
      const res = await request(
        'POST',
        baseUrl + '/oauth/cli/init',
        {
          Authorization: 'Bearer ' + pollToken,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        JSON.stringify({ provider: 'zai' })
      );
      return res;
    } catch (err) {
      const code = (err && err.code) || (err && err.cause && err.cause.code) || null;
      if (TRANSIENT.has(code) && attemptsLeft > 1) {
        log({
          step: 'retry', phase: 'init', code, attemptsLeft,
          note: 'network not ready yet (offline phase / early boot); retrying in 8s',
        });
        await sleep(8000);
        continue;
      }
      throw err;
    }
  }
}

async function attemptPoll(pollUrl, pollToken) {
  for (let attemptsLeft = 3; ; attemptsLeft--) {
    try {
      return await request('GET', pollUrl, {
        Authorization: 'Bearer ' + pollToken,
        Accept: 'application/json',
      });
    } catch (err) {
      const code = (err && err.code) || (err && err.cause && err.cause.code) || null;
      if (TRANSIENT.has(code) && attemptsLeft > 1) {
        log({
          step: 'retry', phase: 'poll', code, attemptsLeft,
          note: 'network not ready yet; retrying in 8s',
        });
        await sleep(8000);
        continue;
      }
      throw err;
    }
  }
}

function parseEnvelope(res) {
  let parsed = null;
  try { parsed = JSON.parse(res.body); } catch (e) { /* handled below */ }
  return {
    httpStatus: res.status,
    envelopeCode: parsed ? parsed.code : null,
    msg: parsed ? parsed.msg || '' : null,
    data: parsed && parsed.data && typeof parsed.data === 'object' ? parsed.data : null,
    parseError: parsed ? null : 'response body is not JSON',
  };
}

async function main() {
  const env = process.env;
  log({
    step: 'start',
    node: process.version,
    platform: process.platform,
    arch: process.arch,
    base: baseUrl,
    ca: {
      sslCertFile: env.SSL_CERT_FILE || null,
      nodeExtraCaCerts: env.NODE_EXTRA_CA_CERTS || null,
      zcodeAgentCaCert: env.ZCODE_AGENT_CA_CERT || null,
      rootCertificates: tls.rootCertificates.length,
    },
    note: 'anonymous device-flow probe; no credentials involved, nothing stored',
  });

  // Fresh anonymous flow token, generated locally, never logged, never
  // persisted: it only authorizes polling of THIS throwaway flow.
  const pollToken = require('crypto').randomBytes(32).toString('hex');

  const startedAt = Date.now();
  const initRes = await attemptInit(pollToken);
  const sock = initRes.sock;
  const init = parseEnvelope(initRes);
  log({
    step: 'init',
    httpStatus: init.httpStatus,
    tlsAuthorized: sock ? sock.authorized : null,
    tlsProtocol: sock && sock.getProtocol ? sock.getProtocol() : null,
    chain: chainOf(sock),
    clockSkewMs: clockSkewFromHeader(initRes.headers.date),
    envelopeCode: init.envelopeCode,
    msg: init.msg,
    parseError: init.parseError,
    hasFlowId: !!(init.data && init.data.flow_id),
    authorizeUrl: init.data ? safeUrl(init.data.authorize_url) : null,
    pollIntervalSec: init.data ? init.data.poll_interval_sec || null : null,
    expiresAt: init.data ? init.data.expires_at || null : null,
    durationMs: Date.now() - startedAt,
  });
  if (sock && sock.authorized !== true) {
    console.log('OAUTH-FLOW-PROBE-FAIL');
    process.exit(0);
  }
  if (init.envelopeCode !== 0 || !init.data || !init.data.flow_id || !init.data.authorize_url) {
    log({ step: 'verdict', ok: false, reason: 'init envelope rejected (see init line above)' });
    console.log('OAUTH-FLOW-PROBE-FAIL');
    process.exit(0);
  }

  let authorizeHost = null;
  try { authorizeHost = new URL(init.data.authorize_url).host; } catch (e) { /* invalid */ }
  if (authorizeHost === null || authorizeHost.indexOf('z.ai') < 0) {
    log({ step: 'verdict', ok: false, reason: 'authorize_url host unexpected', authorizeHost });
    console.log('OAUTH-FLOW-PROBE-FAIL');
    process.exit(0);
  }

  const pollRes = await attemptPoll(
    baseUrl + '/oauth/cli/poll/' + encodeURIComponent(init.data.flow_id),
    pollToken
  );
  const pollSock = pollRes.sock;
  const poll = parseEnvelope(pollRes);
  log({
    step: 'poll',
    httpStatus: poll.httpStatus,
    tlsAuthorized: pollSock ? pollSock.authorized : null,
    envelopeCode: poll.envelopeCode,
    msg: poll.msg,
    status: poll.data ? poll.data.status || null : null,
    parseError: poll.parseError,
    durationMs: Date.now() - startedAt,
  });

  const ok = init.envelopeCode === 0 &&
    !!init.data.flow_id &&
    poll.envelopeCode === 0 &&
    poll.data && poll.data.status === 'pending';
  log({ step: 'verdict', ok, reason: ok ? 'login network path healthy (init + pending poll)' : 'login device-flow deviates from contract' });
  console.log(ok ? 'OAUTH-FLOW-PROBE-OK' : 'OAUTH-FLOW-PROBE-FAIL');
  process.exit(0);
}

main().catch((err) => {
  log(Object.assign({ step: 'error', durationMs: 0 }, errorEvidence(err)));
  console.log('OAUTH-FLOW-PROBE-FAIL');
  process.exit(0);
});
