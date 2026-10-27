#!/usr/bin/env python3
"""Synthetic verification of build-bionic-bundle.py failure-2 redesign.

Builds a tiny fake prefix (real x86_64 ELFs from /bin + real symlinks),
then asserts the G1/G2/G3 pipeline behaviors:
  - hardlinks_to_symlinks: same-inode duplicates -> relative symlinks
  - materialize_sonames: soname symlink -> real byte copy
  - openssl_gate: flags missing openssl.cnf/cert.pem
  - verify-tar tar scan: zero hardlink members; soname = regular member
Run: python3 scripts/e2/test-bundle-guarantees.py
"""
import importlib.util
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location(
    "bbb", os.path.join(os.path.dirname(__file__), "build-bionic-bundle.py"))
bbb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bbb)


def main():
    work = pathlib.Path(tempfile.mkdtemp(prefix="bbb-test-"))
    stage = work / "stage"
    usr = stage / "usr"
    (usr / "bin").mkdir(parents=True)
    (usr / "lib").mkdir(parents=True)
    (usr / "libexec/git-core").mkdir(parents=True)
    (usr / "etc/tls").mkdir(parents=True)

    ok = True

    def check(name, cond):
        nonlocal ok
        print(("PASS" if cond else "FAIL"), name)
        if not cond:
            ok = False

    # --- fixture: a real ELF (x86_64 host bin) + soname symlink + dup file
    real_elf = "/bin/ls"
    if not os.path.isfile(real_elf):
        real_elf = "/usr/bin/ls"
    shutil.copyfile(real_elf, usr / "bin/git")
    shutil.copymode(real_elf, usr / "bin/git")
    # soname symlink: libfake.so.1 -> libfake.so.1.2 (real file)
    shutil.copyfile(real_elf, usr / "lib/libfake.so.1.2")
    os.symlink("libfake.so.1.2", usr / "lib/libfake.so.1")
    # a NON-soname symlink that must stay a symlink
    os.symlink("libfake.so.1.2", usr / "lib/libfake.so")
    # same-inode duplicate (deb-style hardlink) -> git-core helper
    os.link(usr / "bin/git", usr / "libexec/git-core/git-status")

    # --- G2: hardlinks -> symlinks
    converted = bbb.hardlinks_to_symlinks(usr)
    check("G2 converted exactly 1 duplicate", converted == 1)
    check("G2 duplicate now a symlink",
          (usr / "libexec/git-core/git-status").is_symlink())
    check("G2 symlink target relative to helper dir",
          os.readlink(usr / "libexec/git-core/git-status") == "../../bin/git")
    check("G2 canonical remains a real file",
          (usr / "bin/git").is_file() and not (usr / "bin/git").is_symlink())

    # --- G1: soname materialization
    needed = bbb.needed_sonames(stage)
    check("needed_sonames returns real ELF deps (readelf path works)",
          len(needed) > 0)
    # the fixture ELF (/bin/ls copy) does not NEED libfake.so.1 — exercise the
    # materialization function with an explicit needed set (the pipeline in
    # main() feeds it exactly this way after the real scan).
    materialized = bbb.materialize_sonames(stage, {"libfake.so.1"})
    check("G1 materialized libfake.so.1", materialized == ["libfake.so.1"])
    check("G1 soname now a REAL file",
          (usr / "lib/libfake.so.1").is_file()
          and not (usr / "lib/libfake.so.1").is_symlink())
    check("G1 non-soname symlink kept",
          (usr / "lib/libfake.so").is_symlink())

    # --- G3: openssl gate (missing files -> problems)
    problems, info = bbb.openssl_gate(stage)
    check("G3 flags missing openssl.cnf",
          any("openssl.cnf" in p for p in problems))
    check("G3 flags missing cert.pem", any("cert.pem" in p for p in problems))
    (usr / "etc/tls/openssl.cnf").write_text("[openssl_init]\n")
    (usr / "etc/tls/cert.pem").write_text("---fake---\n")
    problems2, info2 = bbb.openssl_gate(stage)
    check("G3 passes with files present", problems2 == [])
    check("G3 records module info", "opensslConf" in info2)

    # --- tar-level scan on the delivered archive
    out_tgz = work / "prefix-test.tgz"
    subprocess.run(["tar", "-C", str(stage), "-czf", str(out_tgz), "usr"], check=True)
    import tarfile
    hardlinks = []
    with tarfile.open(out_tgz, "r:gz") as tf:
        for m in tf:
            if m.islnk():
                hardlinks.append(m.name)
    check("delivered tar has ZERO hardlink entries", hardlinks == [])
    with tarfile.open(out_tgz, "r:gz") as tf:
        kinds = {m.name: m.type for m in tf}
    import tarfile as t2
    check("soname member is REGTYPE (real payload)",
          kinds.get("usr/lib/libfake.so.1") == t2.REGTYPE)
    check("non-soname member is SYMTYPE",
          kinds.get("usr/lib/libfake.so") == t2.SYMTYPE)

    shutil.rmtree(work)
    print("ALL GUARANTEE CHECKS:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
