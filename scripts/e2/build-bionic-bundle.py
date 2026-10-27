#!/usr/bin/env python3
"""E2: build the embedded Bionic runtime bundle (Termux prefix layout).

REUSE of the proven experiment/bionic-e0-e1 machinery (termux-rootfs.py,
13/13 PASS run 40263559737), adapted for APK embedding.

DEVICE FAILURE 1 FIX (Vivo V2035, 2026-10-27 diagnostics):
  `CANNOT LINK EXECUTABLE .../usr/bin/node: library "libz.so.1" not found`
  Root cause chain (all evidence-backed):
    1. Termux ships linker-critical sonames as SYMLINKS (libz.so.1 ->
       libz.so.1.3.2; same for icu/sqlite/readline/ncurses/lzma/bz2/expat).
    2. The bundle closure included them, but the static audit matched NEEDED
       names against directory ENTRIES — a symlink name passed the audit.
    3. On the x86_64 AVD the app's tar extractor created symlinks fine (node
       probe v24.22.1). On the real Vivo device EVERY Files.createSymbolicLink
       failed ("symlink skipped" x150+ in the device log) — OEM/SELinux/filesystem
       behavior we cannot reproduce here — so every soname symlink was absent.
  Fix: HARD-LINK MATERIALIZATION. Every file-symlink in the staged prefix is
  replaced by a real hard link to its target before tarring:
    - tar stores hardlink entries with NO payload -> no APK size cost;
    - on-device, hard links (Files.createLink) extract as REAL FILES; the app
      falls back to a budget-capped copy if a device refuses link() too;
    - the audit now requires every NEEDED soname to resolve to a REAL FILE
      (not a symlink) in usr/lib, both in the stage AND in the re-extracted
      tarball (catches tar-level emission problems);
    - the builder emits prefix-critical.txt (the exact real-file sonames the
      linker needs) so the app can fail fast on-device if any is missing.

  Every .deb is SHA256-verified against the index stanza before extraction
  (dpkg-deb -x, which understands data.tar.zst).
  Output tar root is `usr/` (Termux prefix layout). The app extracts it
  under <filesDir>/runtime/ so the prefix becomes
  /data/data/com.example.zcode_mobile/files/runtime/usr.
  Absolute Termux symlinks (/data/data/com.termux/files/usr/...) are
  rewritten to relative (proven fix exp/e0-e1-bionic 761f225).
  Prune man/doc/locale (+ npm if present) to keep the APK small.

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
# Bionic system libs present on API 24+ AND actually needed as DT_NEEDED
# resolution targets. NOTE: libstdc++.so was REMOVED — nothing in the closure
# NEEDs it (verified by readelf over the full stage); keeping it in the
# allowlist would only hide a real gap.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so",
    "ld-android.so", "libpthread.so", "librt.so",
}
# fallback copy budget enforced by the APP extractor for hardlink entries
# (worst case when a device refuses both symlinks and hard links)
APP_COPY_BUDGET_BYTES = 200 * 1024 * 1024


def fail(msg, code=2):
    print(f"[bundle] ERROR: {msg}", file=sys.stderr)
    sys.exit(code)


def fetch(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "zcode-e2-bundle/0.2"})
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


def materialize_symlinks(usr):
    """Replace every file-symlink in the prefix with a REAL hard link.

    Device failure 1 fix: on the Vivo V2035 the app could not create a single
    symlink, so every soname that existed only as a symlink was missing for
    the bionic linker. Hard links are regular files (same inode) — the linker
    resolution never differs, tar stores them without payload, and the app's
    extractor has a budget-capped copy fallback for devices that refuse
    link() as well.

    Kept as symlinks (and recorded): links that resolve to DIRECTORIES inside
    the prefix (none of them are linker-visible) and dangling/outside links.
    """
    materialized = 0
    kept_dir_links = []
    kept_dangling = []
    for dirpath, dirnames, filenames in os.walk(usr):
        dirnames.sort()
        for fn in sorted(filenames):
            p = pathlib.Path(dirpath) / fn
            if not p.is_symlink():
                continue
            target = os.readlink(p)
            tgt = (p.parent / target) if not target.startswith("/") else pathlib.Path(target)
            try:
                inside = tgt.resolve().is_relative_to(usr.resolve())
            except OSError:
                inside = False
            if tgt.is_file() and not tgt.is_symlink() and inside:
                # hard link to the real file (fallback to copy if link denied)
                try:
                    tmp = p.with_name(p.name + ".mat-tmp")
                    tmp.unlink(missing_ok=True)
                    os.link(tgt, tmp)
                    p.unlink()
                    tmp.rename(p)
                    materialized += 1
                except OSError as e:
                    p.unlink()
                    shutil.copyfile(tgt, p)
                    shutil.copymode(tgt, p)
                    materialized += 1
                    print(f"[bundle] materialize: link() denied for {p} ({e}), copied")
            elif tgt.is_dir():
                kept_dir_links.append(f"{p.relative_to(usr)} -> {target}")
            else:
                kept_dangling.append(f"{p.relative_to(usr)} -> {target}")
    return materialized, kept_dir_links, kept_dangling


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
    """Static dependency-closure audit — symlink-strict (device failure 1 fix).

    Every DT_NEEDED of every staged ELF must resolve to a REAL FILE (not a
    symlink) in usr/lib, or to a static allowlist of Android system libs.
    A symlink name would pass the OLD audit but is absent on devices that
    refuse symlink creation (Vivo V2035) — that failure class now fails the
    build instead of the phone. Returns (problems, stats).
    """
    lib_dir = stage / "usr/lib"
    real_files = set()
    symlink_names = set()
    if lib_dir.is_dir():
        for p in lib_dir.iterdir():
            if p.is_symlink():
                symlink_names.add(p.name)
            elif p.is_file():
                real_files.add(p.name)
    sonames = set()
    scanned = 0
    targets = []
    for sub in ("usr/bin", "usr/libexec/git-core", "usr/lib"):
        d = stage / sub
        if d.is_dir():
            targets.extend(sorted(p for p in d.iterdir() if p.is_file() and not p.is_symlink()))
    problems = []
    unsat = {}
    via_symlink = {}
    resolved_real = set()
    resolved_system = set()
    for p in targets:
        needed, soname = elf_needed(p)
        if needed is None:
            continue
        scanned += 1
        if soname:
            sonames.add(soname)
        for n in needed:
            if n in real_files:
                resolved_real.add(n)
                continue
            if n in SYSTEM_LIBS:
                resolved_system.add(n)
                continue
            if n in symlink_names:
                via_symlink.setdefault(n, []).append(str(p.relative_to(stage)))
                continue
            unsat.setdefault(n, []).append(str(p.relative_to(stage)))
    for name, users in sorted(via_symlink.items()):
        problems.append(
            f"NEEDED [{name}] resolves to a SYMLINK in usr/lib (device-fatal when "
            f"symlink creation is blocked) — used by {users[:4]}")
    for name, users in sorted(unsat.items()):
        problems.append(f"unsatisfied NEEDED [{name}] used by {users[:4]}")
    stats = {
        "elfs_scanned": scanned,
        "lib_real_files": len(real_files),
        "lib_symlinks_remaining": len(symlink_names),
        "needed_resolved_real_files": sorted(resolved_real),
        "needed_resolved_system": sorted(resolved_system),
        "needed_via_symlink": sorted(via_symlink.keys()),
        "unsatisfied_needed": sorted(unsat.keys()),
    }
    return problems, stats, sorted(resolved_real)


def verify_tarball(tgz, stage, needed_real, arch, workdir):
    """Extract the built tarball and re-run the symlink-strict audit.

    Proves the DELIVERED archive (including its hardlink entries) yields real
    files for every linker-critical soname — independent of how the stage dir
    looked before tarring.
    """
    vdir = pathlib.Path(workdir) / f"verify-{arch}"
    if vdir.exists():
        shutil.rmtree(vdir)
    vdir.mkdir(parents=True)
    subprocess.run(["tar", "-xzf", str(tgz), "-C", str(vdir)], check=True)
    problems, stats, needed_real2 = audit(vdir)
    problems2, stats2, _ = audit(stage)
    shutil.rmtree(vdir)
    merged = [f"[tar-verify] {p}" for p in problems] + \
             [f"[stage] {p}" for p in problems2]
    stats2["tar_verified"] = not problems
    stats2["tar_needed_real_files"] = len(needed_real2)
    return merged, stats2


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

    # DEVICE FAILURE 1 FIX: hard-link materialization BEFORE auditing.
    materialized, kept_dir_links, kept_dangling = materialize_symlinks(stage / "usr")
    print(f"[bundle] materialized {materialized} file-symlinks into hard links; "
          f"kept dir-links={len(kept_dir_links)}, dangling/outside={len(kept_dangling)}")

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

    problems, audit_stats, needed_real = audit(stage)
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
        "symlinkMaterialization": {
            "hardLinked": materialized,
            "keptDirLinks": kept_dir_links[:20],
            "keptDangling": kept_dangling[:30],
        },
        "audit": audit_stats,
        "auditProblems": problems,
        "bakedTermuxPrefixStrings": baked,
        "appCopyBudgetBytes": APP_COPY_BUDGET_BYTES,
        "builtAt": datetime.now(timezone.utc).isoformat(),
        "note": (
            "Termux Bionic userspace closure for embedding in the APK. "
            "Linker-critical sonames are shipped as REAL hard links (Vivo V2035 "
            "symlink-creation failure, 2026-10-27). Audit allowlist of Android "
            "system libs: " + ", ".join(sorted(SYSTEM_LIBS))
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

    # verify the DELIVERED archive extracts to a symlink-free linker closure
    problems_tar, audit_stats = verify_tarball(out_tgz, stage, needed_real, arch, workdir)
    if problems_tar:
        print("[bundle] TAR VERIFY PROBLEMS:")
        for pr in problems_tar:
            print("  - " + pr)
        info["audit"] = audit_stats
        info["auditProblems"] = problems_tar
        (outdir / f"manifest-{arch}.json").write_text(json.dumps(info, indent=2))
        fail(f"tar verification failed for {arch} ({len(problems_tar)} problems)", 3)

    # the app checks this list on-device after extraction (fail fast + classify).
    # ONLY usr/lib sonames here — the app's existing usr/bin/node + bash check
    # covers the binaries; mixing them in would false-fail every device.
    critical = sorted(set(needed_real))
    (outdir / f"prefix-{arch}.critical.txt").write_text(
        "".join(f"{n}\n" for n in critical))

    sha = hashlib.sha256(out_tgz.read_bytes()).hexdigest()
    (outdir / f"prefix-{arch}.sha256").write_text(sha + "\n")
    (outdir / f"manifest-{arch}.json").write_text(json.dumps(info, indent=2))
    print(f"[bundle] audit OK (stage + re-extracted tar): "
          f"{audit_stats['elfs_scanned']} ELFs, "
          f"{len(critical)} linker-critical real files, "
          f"{audit_stats['lib_symlinks_remaining']} non-critical symlinks kept")
    print(f"[bundle] baked-prefix strings: {baked} (informational)")
    print(f"[bundle] {len(manifest)} packages; tarball {out_tgz} "
          f"({out_tgz.stat().st_size >> 20} MiB) sha256={sha[:16]}...")


if __name__ == "__main__":
    main()
