#!/usr/bin/env node
// get-rg.cjs — provision a working ripgrep for the embedded Bionic runtime.
//
// The embedded distribution ships no `rg` binary; the agent's Grep tool
// resolves ripgrep from PATH (docs/ENGINEERING-NOTES.md §1), so search
// capability is degraded on-device without it. This helper downloads the
// Alpine ripgrep build for the RUNNING architecture plus its musl loader,
// libpcre2 and libgcc, extracts a self-contained kit (no symlinks — OEM
// tars are handled like the bundle guarantees require), and installs a
// `rg` wrapper into $PREFIX/bin when that directory is available so PATH
// resolution works for the agent's tools.
//
// Usage: node get-rg.cjs [outDir]   (default: $HOME/rg-kit)
// Network: fetches from dl-cdn.alpinelinux.org at run time (Alpine 3.20).
// The script verifies file layout (sizes, SONAME short name, launcher);
// actually executing rg requires the target device/emulator.

'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const ALPINE = 'https://dl-cdn.alpinelinux.org/alpine/v3.20';
const ARCH = { arm64: 'aarch64', x64: 'x86_64' }[process.arch];
if (!ARCH) {
  console.error('unsupported arch: ' + process.arch);
  process.exit(1);
}
const MUSL = 'ld-musl-' + ARCH + '.so.1';
const OUT = process.argv[2] ||
  path.join(process.env.HOME || process.cwd(), 'rg-kit');

async function fetchBuf(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(res.status + ' ' + url);
  return Buffer.from(await res.arrayBuffer());
}

// APKINDEX.tar.gz is a tar.gz whose single member is the index text with
// P:<name> / V:<version> stanzas per package.
async function apkIndex(repo) {
  const tar = zlib.gunzipSync(await fetchBuf(`${ALPINE}/${repo}/${ARCH}/APKINDEX.tar.gz`));
  let off = 0;
  while (off + 512 <= tar.length) {
    const name = tar.slice(off, off + 100).toString('utf8').replace(/\0.*/, '');
    const size = parseInt(tar.toString('utf8', off + 124, off + 136).replace(/\0.*/, '').trim() || '0', 8);
    const type = String.fromCharCode(tar[off + 156]);
    if (name === 'APKINDEX' && (type === '0' || type === '\0')) {
      return tar.slice(off + 512, off + 512 + size).toString('utf8');
    }
    off += 512 + Math.ceil(size / 512) * 512;
  }
  throw new Error('APKINDEX member not found in ' + repo);
}

function pick(index, name) {
  const versions = [];
  for (const stanza of index.split('\n\n')) {
    const p = stanza.match(/^P:(.+)$/m);
    const v = stanza.match(/^V:(.+)$/m);
    if (p && v && p[1] === name) versions.push(v[1]);
  }
  if (!versions.length) throw new Error('package not found in index: ' + name);
  return `${name}-${versions.sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).pop()}.apk`;
}

// Extract regular members whose path matches keep, into OUT/lib, keeping the
// basename (rg, loader, libs). Alpine ships pcre2/libgcc under usr/lib/.
function extractApk(apkBuf, keep) {
  const tar = zlib.gunzipSync(apkBuf);
  const libDir = path.join(OUT, 'lib');
  fs.mkdirSync(libDir, { recursive: true });
  let off = 0;
  while (off + 512 <= tar.length) {
    const name = tar.slice(off, off + 100).toString('utf8').replace(/\0.*$/, '');
    if (!name) break;
    const size = parseInt(tar.toString('utf8', off + 124, off + 136).replace(/\0.*$/, '').trim() || '0', 8);
    const type = String.fromCharCode(tar[off + 156]);
    if ((type === '0' || type === '\0') && keep.test(name)) {
      const dest = path.join(libDir, path.basename(name));
      fs.writeFileSync(dest, tar.slice(off + 512, off + 512 + size));
      fs.chmodSync(dest, 0o755);
      console.log('extracted', dest, size, 'bytes');
    }
    off += 512 + Math.ceil(size / 512) * 512;
  }
}

async function main() {
  const mainIdx = await apkIndex('main');
  const communityIdx = await apkIndex('community');
  const rgApk = pick(communityIdx, 'ripgrep');
  const muslApk = pick(mainIdx, 'musl');
  const pcre2Apk = pick(mainIdx, 'pcre2');
  const libgccApk = pick(mainIdx, 'libgcc');
  console.log(`arch: ${ARCH} | ripgrep: ${rgApk} | musl: ${muslApk} | pcre2: ${pcre2Apk} | libgcc: ${libgccApk}`);

  extractApk(await fetchBuf(`${ALPINE}/community/${ARCH}/${rgApk}`), /^usr\/bin\/rg$/);
  extractApk(await fetchBuf(`${ALPINE}/main/${ARCH}/${muslApk}`), new RegExp(`^(lib|usr/lib)/${MUSL}$`));
  extractApk(await fetchBuf(`${ALPINE}/main/${ARCH}/${pcre2Apk}`), /^usr\/lib\/libpcre2-8\.so\.0(\..*)?$/);
  extractApk(await fetchBuf(`${ALPINE}/main/${ARCH}/${libgccApk}`), /^usr\/lib\/libgcc_s\.so\.1$/);

  // rg links against the SONAME (libpcre2-8.so.0); the apk ships the
  // versioned file — materialize the short name as a real copy
  // (symlink-free, matching the project's bundle guarantees on OEM tars).
  const libDir = path.join(OUT, 'lib');
  for (const f of fs.readdirSync(libDir)) {
    const m = f.match(/^(libpcre2-8\.so\.0)\.\d+\.\d+$/);
    if (m && !fs.existsSync(path.join(libDir, m[1]))) {
      fs.copyFileSync(path.join(libDir, f), path.join(libDir, m[1]));
      fs.chmodSync(path.join(libDir, m[1]), 0o755);
      console.log('soname copy:', m[1], '->', f);
    }
  }

  // Launcher: exec rg via the musl loader with the kit's lib dir so the
  // kit is fully self-contained (no dependency on runtime LD_LIBRARY_PATH).
  const launcher = path.join(OUT, 'rg.sh');
  fs.writeFileSync(launcher,
    '#!/bin/sh\n' +
    `exec "$(dirname "$0")/lib/${MUSL}" --library-path "$(dirname "$0")/lib" "$(dirname "$0")/lib/rg" "$@"\n`);
  fs.chmodSync(launcher, 0o755);
  console.log('wrote', launcher);

  for (const f of ['lib/rg', 'lib/' + MUSL, 'lib/libpcre2-8.so.0', 'lib/libgcc_s.so.1', 'rg.sh']) {
    const p = path.join(OUT, f);
    if (!fs.existsSync(p)) throw new Error('kit incomplete, missing: ' + f);
    const sha = crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex').slice(0, 16);
    console.log('kit-ok:', f, fs.statSync(p).size + 'B', 'sha256:' + sha + '...');
  }

  // Install a PATH-visible wrapper when running inside the app runtime
  // (verify-run.sh / BionicEnv export PREFIX). Best effort: a read-only or
  // absent PREFIX must not fail the kit build.
  const prefixBin = process.env.PREFIX && path.join(process.env.PREFIX, 'bin');
  if (prefixBin && fs.existsSync(prefixBin)) {
    try {
      const wrap = path.join(prefixBin, 'rg');
      fs.writeFileSync(wrap, '#!/bin/sh\nexec "' + path.join(OUT, 'rg.sh') + '" "$@"\n');
      fs.chmodSync(wrap, 0o755);
      console.log('installed:', wrap);
    } catch (e) {
      console.log('prefix install skipped:', e.message);
    }
  }
}

main().catch((e) => { console.error('ERROR', e.message); process.exit(1); });
