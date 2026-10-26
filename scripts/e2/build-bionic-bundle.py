#!/usr/bin/env python3
"""E2: build the embedded Bionic runtime bundle (Termux prefix layout).

REUSE of the proven experiment/bionic-e0-e1 machinery (termux-rootfs.py,
13/13 PASS run 40263559737), adapted for APK embedding:

  - Closure: BFS over Depends of the Termux packages index for ONE arch
    (same seeds as the proven E0/E1 run: nodejs-lts, git, bash,
    ca-certificates, termux-exec, libandroid-support).
  - Every .deb is SHA256-verified against the index stanza before extraction
    (dpkg-deb -x, which understands data.tar.zst).
  - Output tar root is `usr/` (Termux prefix layout). The app extracts it
    under <filesDir>/runtime/ so the prefix becomes
    /data/data/com.example.zcode_mobile/files/runtime/usr.
  - Absolute Termux symlinks (/data/data/com.termux/files/usr/...) are
    rewritten to relative (proven fix exp/e0-e1-bionic 761f225).
  - Prune man/doc/locale (+ npm if present) to keep the APK small.
  - Post-build static audit:
      (a) readelf DT_NEEDED closure of every staged ELF (bin, git-core
          helpers, lib) must be satisfied by embedded libs or a static
          allowlist of Android system libs -> exit 3 on gap;
      (b) hardcoded /data/data/com.termux strings scan (informational,
          non-canonical-prefix risk register).

Usage: build-bionic-bundle.py <aarch64|x86_64> <workdir> <outdir>
Exit codes: 0 ok; 2 resolve/download error (classification: workflow/setup);
            3 audit failure (classification: dependency gap).
"""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone

INDEX_URL = (
    "https://packages.termux.dev/apt/termux-main/dists/stable/main/"
    "binary-{arch}/Packages"
)
SEEDS = [
    "nodejs-lts",
    "git",
    "bash",
    "ca-certificates",
    "termux-exec",
    "libandroid-support",
    # util-linux ships lsns (and friends) NEEDEDing libmount, but nothing in
    # the Depends closure pulls it (first E2 audit finding) — seed it.
    "libmount",
    "libblkid",
]
TERMUX_PREFIX = "/data/data/com.termux/files/usr"
PRUNE = ["usr/share/man", "usr/share/doc", "usr/share/locale", "usr/lib/node_modules"]
# Android system libs present on API 24+ (bionic). Anything NEEDED outside
# this set and outside the bundle is an audit failure.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libstdc++.so",
    "ld-android.so", "libpthread.so", "librt.so",
}


def fail(msg, code=2):
    print(f"[bundle] ERROR: {msg}", file=sys.stderr)
    sys.exit(code)


def fetch(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "zcode-e2-bundle/0.1"})
    with urllib.request.urlopen(req, timeout=180) as r, open(dest, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)


def parse_index(path):
    stanzas = {}
    cur = {}
    for line in pathlib.Path(path).read_text(errors="replace").splitlines():
        if not line.strip():
            if cur.get("Package"):
                stanzas.setdefault(cur["Package"], cur)
            cur = {}
            continue
        if ":" in line:
            key, _, val = line.partition(":")
            cur[key.strip()] = val.strip()
    if cur.get("Package"):
        stanzas.setdefault(cur["Package"], cur)
    return stanzas


def dep_names(depends_field):
    # "libc++, openssl (>= 3.0), a | b" -> ["libc++", "openssl", "a"]
    out = []
    if not depends_field:
        return out
    for part in depends_field.split(","):
        first = part.split("|")[0].strip()
        name = first.split("(")[0].strip()
        if name:
            out.append(name)
    return out


def rewrite_symlinks(root):
    """Rewrite absolute Termux-prefix symlinks to relative (proven fix)."""
    rewritten = 0
    other_abs = []
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            p = pathlib.Path(dirpath) / fn
            if not p.is_symlink():
                continue
            target = os.readlink(p)
            if target.startswith(TERMUX_PREFIX):
                rel = os.path.relpath(target[len(TERMUX_PREFIX):].lstrip("/"), p.parent)
                p.unlink()
                os.symlink(rel, p)
                rewritten += 1
            elif target.startswith("/"):
                other_abs.append(f"{p} -> {target}")
    return rewritten, other_abs


def elf_needed(path):
    """DT_NEEDED + DT_SONAME of one ELF via readelf; None if not ELF/dynamic."""
    try:
        out = subprocess.run(
            ["readelf", "-d", str(path)],
            capture_output=True, text=True, timeout=30,
        ).stdout
    except Exception:
        return None, None
    needed = re.findall(r"\(NEEDED\).*?\[(.+?)\]", out)
    soname = (re.findall(r"\(SONAME\).*?\[(.+?)\]", out) or [None])[0]
    if not needed and "There is no dynamic section" in out:
        return [], soname
    if not out.strip() or "not an ELF file" in out or "Error:" in out:
        return None, None
    return needed, soname


def audit(stage):
    """Static dependency-closure audit. Returns (problems, stats)."""
    lib_dir = stage / "usr/lib"
    embedded_files = {p.name for p in lib_dir.iterdir()} if lib_dir.is_dir() else set()
    sonames = set()
    scanned = 0
    targets = []
    for sub in ("usr/bin", "usr/libexec/git-core", "usr/lib"):
        d = stage / sub
        if d.is_dir():
            targets.extend(sorted(p for p in d.iterdir() if p.is_file()))
    problems = []
    unsat = {}
    for p in targets:
        needed, soname = elf_needed(p)
        if needed is None:
            continue
        scanned += 1
        if soname:
            sonames.add(soname)
        for n in needed:
            if n in embedded_files or n in sonames or n in SYSTEM_LIBS:
                continue
            unsat.setdefault(n, []).append(str(p.relative_to(stage)))
    for name, users in sorted(unsat.items()):
        problems.append(f"unsatisfied NEEDED [{name}] used by {users[:4]}")
    stats = {
        "elfs_scanned": scanned,
        "embedded_lib_files": len(embedded_files),
        "unsatisfied_needed": sorted(unsat.keys()),
    }
    return problems, stats


def baked_prefix_scan(stage):
    """Count hardcoded Termux-prefix strings in key binaries (informational)."""
    result = {}
    for rel in ("usr/bin/node", "usr/bin/git", "usr/bin/bash"):
        p = stage / rel
        if not p.is_file():
            continue
        try:
            out = subprocess.run(
                ["strings", "-a", str(p)], capture_output=True, text=True, timeout=120
            ).stdout
            result[rel] = out.count(TERMUX_PREFIX)
        except Exception:
            result[rel] = -1
    return result


def main():
    arch = sys.argv[1] if len(sys.argv) > 1 else "aarch64"
    workdir = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "work")
    outdir = pathlib.Path(sys.argv[3] if len(sys.argv) > 3 else "out")
    if arch not in ("aarch64", "x86_64"):
        fail(f"unsupported arch {arch}", 2)
    workdir.mkdir(parents=True, exist_ok=True)
    outdir.mkdir(parents=True, exist_ok=True)

    index_path = workdir / f"Packages-{arch}"
    url = INDEX_URL.format(arch=arch)
    print(f"[bundle] fetching index {url}")
    try:
        fetch(url, index_path)
    except Exception as e:  # noqa: BLE001
        fail(f"index download failed: {e}")
    stanzas = parse_index(index_path)
    print(f"[bundle] index parsed: {len(stanzas)} packages")

    queue = list(SEEDS)
    seen, chosen = set(), {}
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        seen.add(name)
        st = stanzas.get(name)
        if st is None:
            fail(f"package not in index: {name}")
        chosen[name] = st
        for dep in dep_names(st.get("Depends", "")):
            if dep not in seen:
                queue.append(dep)

    rootfs = workdir / f"rootfs-{arch}"
    if rootfs.exists():
        shutil.rmtree(rootfs)
    manifest = []
    for name, st in sorted(chosen.items()):
        fname = st.get("Filename")
        want_sha = st.get("SHA256", "").lower()
        if not fname or not want_sha:
            fail(f"stanza for {name} missing Filename/SHA256")
        deb = workdir / f"{name}-{arch}.deb"
        durl = "https://packages.termux.dev/apt/termux-main/" + fname
        print(f"[bundle] {name} {st.get('Version')} -> {fname}")
        if deb.is_file() and hashlib.sha256(deb.read_bytes()).hexdigest() == want_sha:
            print("[bundle]   cached, sha256 OK")
        else:
            try:
                fetch(durl, deb)
            except Exception as e:  # noqa: BLE001
                fail(f"download failed for {name}: {e}")
        got = hashlib.sha256(deb.read_bytes()).hexdigest()
        if got != want_sha:
            fail(f"sha256 mismatch for {name}: want {want_sha} got {got}")
        subprocess.run(["dpkg-deb", "-x", str(deb), str(rootfs)], check=True)
        manifest.append({"package": name, "version": st.get("Version"), "sha256": got})

    stage = workdir / f"stage-{arch}"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    src_usr = rootfs / "data/data/com.termux/files/usr"
    if not (src_usr / "bin/node").is_file():
        fail("node binary missing after extraction (layout unexpected)")
    shutil.move(str(src_usr), str(stage / "usr"))
    for rel in PRUNE:
        p = stage / rel
        if p.exists():
            shutil.rmtree(p, ignore_errors=True)
    # node binary must stay at usr/bin/node; npm is deliberately not included
    # (the embedded zcode dist is self-contained).

    rewritten, other_abs = rewrite_symlinks(stage / "usr")

    node_bin = stage / "usr/bin/node"
    if not node_bin.is_file():
        fail("node binary missing in staged prefix")
    # belt-and-braces: every bin/git-core executable must be executable
    for sub in ("usr/bin", "usr/libexec/git-core"):
        d = stage / sub
        if d.is_dir():
            for p in d.iterdir():
                if p.is_file() and not p.is_symlink():
                    p.chmod(p.stat().st_mode | 0o111)

    problems, audit_stats = audit(stage)
    baked = baked_prefix_scan(stage)
    node_ver = chosen["nodejs-lts"].get("Version", "unknown")
    info = {
        "mode": "bionic",
        "arch": arch,
        "node": node_ver,
        "termuxIndex": url,
        "packages": manifest,
        "symlinksRewritten": rewritten,
        "otherAbsoluteSymlinks": other_abs[:20],
        "audit": audit_stats,
        "auditProblems": problems,
        "bakedTermuxPrefixStrings": baked,
        "builtAt": datetime.now(timezone.utc).isoformat(),
        "note": (
            "Termux Bionic userspace closure for embedding in the APK. "
            "Audit allowlist of Android system libs: " + ", ".join(sorted(SYSTEM_LIBS))
        ),
    }
    if problems:
        print("[bundle] AUDIT PROBLEMS:")
        for pr in problems:
            print("  - " + pr)
        (outdir / f"manifest-{arch}.json").write_text(json.dumps(info, indent=2))
        fail(f"dependency-closure audit failed for {arch} ({len(problems)} problems)", 3)

    out_tgz = outdir / f"prefix-{arch}.tgz"
    subprocess.run(["tar", "-C", str(stage), "-czf", str(out_tgz), "usr"], check=True)
    sha = hashlib.sha256(out_tgz.read_bytes()).hexdigest()
    (outdir / f"prefix-{arch}.sha256").write_text(sha + "\n")
    (outdir / f"manifest-{arch}.json").write_text(json.dumps(info, indent=2))
    print(f"[bundle] audit OK: {audit_stats['elfs_scanned']} ELFs scanned, "
          f"0 unsatisfied NEEDED")
    print(f"[bundle] baked-prefix strings: {baked} (informational)")
    print(f"[bundle] {len(manifest)} packages; tarball {out_tgz} "
          f"({out_tgz.stat().st_size >> 20} MiB) sha256={sha[:16]}...")


if __name__ == "__main__":
    main()
