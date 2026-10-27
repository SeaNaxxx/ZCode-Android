#!/usr/bin/env python3
"""E2: build the embedded Bionic runtime bundle (Termux prefix layout).

REUSE of the proven experiment/bionic-e0-e1 machinery (termux-rootfs.py,
13/13 PASS run 40263559737), adapted for APK embedding.

DEVICE FAILURE 1 (Vivo V2035, 2026-10-27, fixed in 0.3.1-e2):
  `CANNOT LINK EXECUTABLE .../usr/bin/node: library "libz.so.1" not found`
  Termux ships linker-critical sonames as SYMLINKS (libz.so.1 ->
  libz.so.1.3.2; same for sqlite/readline/lzma/bz2/expat). The old audit
  matched NEEDED names against directory ENTRIES, so a symlink name passed.

DEVICE FAILURE 2 (Vivo V2035, Android 13, 2026-10-28) — REDESIGN trigger:
  1. The 0.3.1 "fix" shipped sonames as tar HARDLINK entries and assumed the
     device refused symlink creation. The 0.3.1 device log PROVED otherwise:
     every symlink failure was NoSuchMethodError on java.nio.file.Path.of —
     an APP bug (Path.of needs API 34, device is API 33). Device symlink
     support was never actually tested; app-side fix (Paths.get) is in the
     Android code now.
  2. The device DOES refuse Files.createLink (AccessDeniedException x135),
     so tar hardlink entries forced ~199MB of budget-capped copies and left
     helper utilities absent. Lesson: the DELIVERED ARCHIVE must never make
     the device call link().

THIS BUILDER'S GUARANTEES (all verified against the DELIVERED tarball):
  G1. Every linker-critical soname (DT_NEEDED of every staged ELF) is a REAL
      FILE in usr/lib — materialized as a byte copy of its target before
      tarring. Linker closure is independent of device symlink support.
  G2. The delivered tar contains ZERO hardlink entries: deb-materialized
      duplicate files (git-core helpers, ...) are converted to RELATIVE
      symlinks; the audit fails the build otherwise (tar-level check).
  G3. usr/etc/tls/openssl.cnf and usr/etc/tls/cert.pem are present: the
      Termux-built libcrypto has OPENSSLDIR compiled in as
      /data/data/com.termux/files/usr/etc/tls (proven via strings on
      libcrypto.so.3), which is unreadable for the app and FATAL at node
      startup (OpenSSL configuration error, exit 13). The app points
      OPENSSL_CONF/SSL_CERT_FILE at these runtime copies.
  G4. Every .deb is SHA256-verified against the index stanza before
      extraction (dpkg-deb -x).

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


def hardlinks_to_symlinks(usr):
    """G2: convert duplicate (same-inode) real files to RELATIVE symlinks.

    dpkg-deb -x materializes package hard links (git-core helpers -> bin/git,
    busybox-style sets) as same-inode real files; GNU tar would store them as
    hardlink entries, which the Vivo V2035 refuses to create (EACCES x135,
    2026-10-28) and which cost ~199MB of budget-capped copies. Symlink
    entries carry no payload, and the app creates them with an API available
    on Android 13 (Paths.get — the app-side Path.of bug is fixed separately).
    The canonical member of each inode group is the first in sorted order.
    """
    groups = {}
    for dirpath, dirnames, filenames in os.walk(usr):
        dirnames.sort()
        for fn in sorted(filenames):
            p = pathlib.Path(dirpath) / fn
            if p.is_symlink() or not p.is_file():
                continue
            st = p.stat()
            groups.setdefault((st.st_dev, st.st_ino), []).append(p)
    converted = 0
    for members in groups.values():
        if len(members) < 2:
            continue
        canonical = members[0]
        for dup in members[1:]:
            rel = os.path.relpath(canonical, dup.parent)
            dup.unlink()
            os.symlink(rel, dup)
            converted += 1
    return converted


def needed_sonames(stage):
    """All DT_NEEDED names across the staged ELFs (pre-materialization scan)."""
    needed = set()
    for sub in ("usr/bin", "usr/libexec/git-core", "usr/lib"):
        d = stage / sub
        if not d.is_dir():
            continue
        for p in sorted(d.iterdir()):
            if not p.is_file() or p.is_symlink():
                continue
            n, _soname = elf_needed(p)
            if n:
                needed.update(n)
    return needed


def materialize_sonames(stage, needed):
    """G1: replace soname symlinks in usr/lib with REAL byte copies.

    Devices that refuse symlink() must still get a complete linker closure,
    so every symlink in usr/lib whose NAME appears as a DT_NEEDED of any
    staged ELF becomes a real file (copy of its resolved target). This is
    the durable form of the failure-1 fix: real payload in the tar, no
    link()/symlink() syscall required for the linker path.
    """
    lib_dir = stage / "usr/lib"
    materialized = []
    if not lib_dir.is_dir():
        return materialized
    for p in sorted(lib_dir.iterdir()):
        if not p.is_symlink() or p.name not in needed:
            continue
        target = p.resolve()
        if not target.is_file():
            continue  # dangling: leave for the audit to flag
        tmp = p.with_name(p.name + ".mat-tmp")
        tmp.unlink(missing_ok=True)
        shutil.copyfile(target, tmp)
        shutil.copymode(target, tmp)
        p.unlink()
        tmp.rename(p)
        materialized.append(p.name)
    return materialized


def openssl_gate(stage):
    """G3: openssl.cnf + cert.pem must ship inside the bundle.

    libcrypto's compiled-in OPENSSLDIR (/data/data/com.termux/...) is fatal
    for the app; the app points OPENSSL_CONF/SSL_CERT_FILE at the runtime
    copies of exactly these files. Missing either = build failure.
    """
    problems = []
    cnf = stage / "usr/etc/tls/openssl.cnf"
    cert = stage / "usr/etc/tls/cert.pem"
    if not cnf.is_file():
        problems.append(
            "usr/etc/tls/openssl.cnf missing — node startup is FATAL without it "
            "(libcrypto OPENSSLDIR points at an unreadable Termux path); "
            "seed the openssl package")
    if not cert.is_file():
        problems.append(
            "usr/etc/tls/cert.pem missing — SSL_CERT_FILE/CA verification needs "
            "a bundled store; seed ca-certificates")
    modules = stage / "usr/lib/ossl-modules"
    info = {
        "opensslConf": cnf.is_file(),
        "certPem": cert.is_file(),
        "osslModulesDir": modules.is_dir(),
        "osslModules": sorted(p.name for p in modules.iterdir()) if modules.is_dir() else [],
    }
    return problems, info


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
    """Extract the built tarball and verify the DELIVERED archive.

    Checks (failure-1/2 evidence, tar-level):
      a. symlink-strict dependency audit on the re-extracted tree;
      b. ZERO hardlink entries in the tar (device refuses link(); G2);
      c. every linker-critical soname is a REGULAR member (real payload, G1);
      d. openssl.cnf + cert.pem are members (G3).
    """
    import tarfile
    vdir = pathlib.Path(workdir) / f"verify-{arch}"
    if vdir.exists():
        shutil.rmtree(vdir)
    vdir.mkdir(parents=True)
    subprocess.run(["tar", "-xzf", str(tgz), "-C", str(vdir)], check=True)
    problems, stats, needed_real2 = audit(vdir)
    problems2, stats2, _ = audit(stage)
    hardlink_members = []
    member_names = set()
    real_members = set()
    with tarfile.open(tgz, "r:gz") as tf:
        for m in tf:
            member_names.add(m.name)
            if m.islnk():
                hardlink_members.append(m.name)
            elif m.isfile():
                real_members.add(m.name)
    if hardlink_members:
        problems.append(
            f"tar contains {len(hardlink_members)} HARDLINK entries (device refuses "
            f"link(), EACCES x135 on Vivo V2035): {hardlink_members[:6]}")
    missing_real = [n for n in needed_real2 if f"usr/lib/{n}" not in real_members]
    if missing_real:
        problems.append(
            f"linker-critical sonames not REGULAR members in tar: {missing_real[:10]}")
    for req in ("usr/etc/tls/openssl.cnf", "usr/etc/tls/cert.pem"):
        if req not in member_names:
            problems.append(f"tar missing required member {req} (G3)")
    shutil.rmtree(vdir)
    merged = [f"[tar-verify] {p}" for p in problems] + \
             [f"[stage] {p}" for p in problems2]
    stats2["tar_verified"] = not problems
    stats2["tar_hardlink_entries"] = len(hardlink_members)
    stats2["tar_needed_real_files"] = len(needed_real2)
    return merged, stats2


def baked_prefix_scan(stage):
    """Count hardcoded Termux-prefix strings in key binaries (informational).

    libcrypto.so.3/libssl.so.3 are included since failure 2: their compiled-in
    OPENSSLDIR/MODULESDIR are the reason OPENSSL_CONF/OPENSSL_MODULES must be
    set by the app at spawn time.
    """
    result = {}
    for rel in ("usr/bin/node", "usr/bin/git", "usr/bin/bash",
                "usr/lib/libcrypto.so.3", "usr/lib/libssl.so.3"):
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

    # DEVICE FAILURE 2 REDESIGN (evidence-ordered pipeline):
    #   step A: duplicate same-inode real files -> relative symlinks, so the
    #           delivered tar needs ZERO link() support on devices (G2);
    #   step B: soname symlinks in usr/lib -> REAL byte copies, so the linker
    #           closure needs ZERO symlink() support either (G1, failure-1).
    usr = stage / "usr"
    converted = hardlinks_to_symlinks(usr)
    needed = needed_sonames(stage)
    materialized = materialize_sonames(stage, needed)
    print(f"[bundle] G2: {converted} duplicate hard-linked files -> relative symlinks")
    print(f"[bundle] G1: {len(materialized)} linker-critical sonames -> real files "
          f"(of {len(needed)} distinct DT_NEEDED names)")

    # G3 gate: OpenSSL data files must ship (node startup is FATAL otherwise).
    ossl_problems, ossl_info = openssl_gate(stage)

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
    problems = problems + ossl_problems
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
        "hardlinksConvertedToSymlinks": converted,
        "sonamesMaterializedAsRealFiles": materialized,
        "openssl": ossl_info,
        "audit": audit_stats,
        "auditProblems": problems,
        "bakedTermuxPrefixStrings": baked,
        "appCopyBudgetBytes": APP_COPY_BUDGET_BYTES,
        "builtAt": datetime.now(timezone.utc).isoformat(),
        "note": (
            "Termux Bionic userspace closure for embedding in the APK. "
            "G1: linker-critical sonames ship as REAL FILES (linker closure "
            "independent of device symlink support; Vivo failure 1). "
            "G2: delivered tar contains ZERO hardlink entries — duplicates are "
            "relative symlinks (Vivo refuses link(), failure 2). "
            "G3: usr/etc/tls/openssl.cnf + cert.pem ship in the bundle; the app "
            "sets OPENSSL_CONF/OPENSSL_MODULES/SSL_CERT_FILE to the runtime "
            "copies because the Termux-built libcrypto has OPENSSLDIR compiled "
            "in as /data/data/com.termux/... (fatal EACCES at node startup, "
            "failure 2). Audit allowlist of Android system libs: " +
            ", ".join(sorted(SYSTEM_LIBS))
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
