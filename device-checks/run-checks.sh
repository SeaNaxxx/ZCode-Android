#!/data/data/com.termux/files/usr/bin/env bash
# ZCode-Android device checks (docs/RUNTIME-CHECKS.md) — run inside Termux, arm64 phone.
#
# Stages: deps | check1-desktop | check1-web | check2a | check3
# Protocol: every stage logs to ~/zcode-checks/logs/<stage>-<ts>.log and prints
# PASS / FAIL / BLOCKED only from real command results. Nothing is fabricated;
# a stage that cannot run stays NOT TESTED.
#
# Prereqs: Termux (F-Droid build), Termux:X11 app for check1-desktop,
# artifacts from CI workflow linux-arm64-build copied into ~/zcode-checks/artifacts/.

set -u

CK_ROOT="$HOME/zcode-checks"
ART="$CK_ROOT/artifacts"
LOG_DIR="$CK_ROOT/logs"
UBU=ubuntu
NODE_VER=24.14.0
NODE_URL="https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.xz"
ENV_BIN="/data/data/com.termux/files/usr/bin/proot-distro"

mkdir -p "$ART" "$LOG_DIR" "$CK_ROOT/workspace"
LOGF="/dev/null"

say() { echo "[$1] $2"; echo "[$(date +%H:%M:%S)] $2" >> "$LOGF"; }
run() { echo "+ $* [exit recorded]" >> "$LOGF"; "$@" >> "$LOGF" 2>&1; RC=$?; echo "  exit=$RC" >> "$LOGF"; return $RC; }
result() { say "$1" "RESULT: $2"; }
stage_log() { LOGF="$LOG_DIR/$1-$(date +%Y%m%d-%H%M%S).log"; : > "$LOGF"; say "$1" "log: $LOGF"; }

inside_env() {
  [ -x "$ENV_BIN" ] || { say "$1" "BLOCKED: proot-distro not installed (run stage: deps)"; return 1; }
  proot_distro_login "$1"
}

proot_distro_login() {
  # --shared-tmp exposes the Termux X11 socket (DISPLAY=:0) and tmp inside the env.
  $ENV_BIN login "$UBU" --shared-tmp -- bash -lc "$2"
}

# ---------------------------------------------------------------- stage: deps
stage_deps() {
  stage_log deps
  say deps "installing proot-distro"
  run pkg install -y proot-distro || { result deps "FAIL (pkg install)"; return 1; }
  say deps "installing proot ubuntu (skips if present)"
  run $ENV_BIN list
  if ! $ENV_BIN list 2>/dev/null | grep -q "$UBU"; then
    run $ENV_BIN install "$UBU" || { result deps "FAIL (proot-distro install)"; return 1; }
  fi
  say deps "env apt: electron system libs + utilities (missing names are recorded, not fatal)"
  inside_env deps "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y \
    curl ca-certificates xz-utils procps \
    libgtk-3-0t64 libnss3 libasound2t64 libgbm1 libxtst6 libxss1 libxrandr2 \
    libxkbcommon0 libdrm2 libpango-1.0-0 libcairo2 libcups2t64 libxcomposite1 \
    libxdamage1 libxfixes3 libxi6 libxinerama1 libxcursor1 fonts-noto-cjk"
  say deps "env: installing Node.js ${NODE_VER} arm64 (sha256 is printed for the record)"
  inside_env deps "curl -fsSL '$NODE_URL' -o /tmp/node.tar.xz && sha256sum /tmp/node.tar.xz \
    && mkdir -p /opt/node && tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1 \
    && ln -sf /opt/node/bin/node /usr/local/bin/node && ln -sf /opt/node/bin/npm /usr/local/bin/npm \
    && node --version"
  inside_env deps "node --version" && result deps "PASS" || result deps "FAIL (node)"
}

# -------------------------------------------------- stage: check1-desktop
stage_check1_desktop() {
  stage_log check1-desktop
  local artifact
  artifact="$(ls "$ART"/*.AppImage "$ART"/*.deb 2>/dev/null | head -1)"
  [ -n "$artifact" ] || { result check1-desktop "BLOCKED: no .AppImage/.deb in $ART (run CI workflow first)"; return 1; }
  say check1-desktop "artifact: $artifact"
  say check1-desktop "prereq: open the Termux:X11 app now; the desktop needs DISPLAY=:0"
  local launch
  case "$artifact" in
    *.AppImage)
      launch="chmod +x '$artifact' && '$artifact' --appimage-extract-and-run \
        --no-sandbox --disable-gpu --disable-dev-shm-usage"
      ;;
    *.deb)
      launch="rm -rf /tmp/zx && mkdir -p /tmp/zx && dpkg -x '$artifact' /tmp/zx \
        && EXE=\$(find /tmp/zx -maxdepth 4 -type f -executable | head -1) \
        && echo \"exe: \$EXE\" && \"\$EXE\" --no-sandbox --disable-gpu --disable-dev-shm-usage"
      ;;
  esac
  say check1-desktop "launching; liveness = process alive after 60s; visual confirmation is yours"
  ( inside_env check1-desktop "$launch" & echo $! > /tmp/zx_pid ) 2>>"$LOGF"
  sleep 60
  if kill -0 "$(cat /tmp/zx_pid 2>/dev/null)" 2>/dev/null; then
    say check1-desktop "process alive after 60s — confirm visually: is the ZCode window rendered?"
    result check1-desktop "PASS-CANDIDATE (confirm window visually, then log result)"
  else
    result check1-desktop "FAIL (process exited within 60s — read $LOGF tail)"
  fi
}

# ------------------------------------------------------ stage: check1-web
stage_check1_web() {
  stage_log check1-web
  local dist
  dist="$(find "$ART" -maxdepth 3 -type d -name zcode 2>/dev/null | head -1)"
  [ -n "$dist" ] || { result check1-web "BLOCKED: agent-dist tree not found under $ART"; return 1; }
  say check1-web "dist tree: $dist"
  run inside_env check1-web "node '$dist/bin/zcode.mjs' --version"
  inside_env check1-web "node '$dist/bin/zcode.mjs' --web --no-open --host 127.0.0.1 \
    --port 3030 --workspace '$CK_ROOT/workspace'" &
  local webpid=$!
  say check1-web "waiting for http://127.0.0.1:3030"
  local ok=""
  for i in $(seq 1 30); do
    code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:3030 2>/dev/null)"
    [ "$code" = "200" ] && { ok=1; break; }
    sleep 2
  done
  [ -n "$ok" ] && { say check1-web "open http://127.0.0.1:3030 in the phone browser"; result check1-web "PASS (HTTP 200)"; } \
                || result check1-web "FAIL (no HTTP 200 in 60s — see $LOGF)"
  kill $webpid 2>/dev/null
}

# --------------------------------------------------------- stage: check2a
stage_check2a() {
  stage_log check2a
  local dist
  dist="$(find "$ART" -maxdepth 3 -type d -name zcode 2>/dev/null | head -1)"
  [ -n "$dist" ] || { result check2a "BLOCKED: agent-dist tree not found"; return 1; }
  say check2a "OAuth: a URL will be printed — open it in the phone browser and approve"
  run inside_env check2a "node '$dist/bin/zcode.mjs' login zai --no-browser"
  inside_env check2a "test -s ~/.zcode/v2/credentials.json && echo credentials-present \
    && python3 -c \"import json;print(sorted(json.load(open('/root/.zcode/v2/credentials.json')).keys()))\" 2>/dev/null || true"
  if inside_env check2a "test -s ~/.zcode/v2/credentials.json"; then
    result check2a "PASS (credentials file created) — next: provider/testModelConnectivity or web UI state; billing 429/[1113] class = auth chain works"
  else
    result check2a "FAIL (credentials file missing — read $LOGF; classify network vs billing)"
  fi
}

# ----------------------------------------------------------- stage: check3
stage_check3() {
  stage_log check3
  local dist
  dist="$(find "$ART" -maxdepth 3 -type d -name zcode 2>/dev/null | head -1)"
  [ -n "$dist" ] || { result check3 "BLOCKED: agent-dist tree not found"; return 1; }
  say check3 "PTY probe (glibc prebuilt inside proot — expected PASS here, FAIL on bare Termux)"
  local closure
  closure="$(find "$dist" -maxdepth 2 -type d -name node_modules | head -1)"
  if [ -n "$closure" ]; then
    run inside_env check3 "NODE_PATH='$closure' node -e \"require('node-pty');console.log('node-pty loaded')\"" \
      && result check3 "pty: PASS" || result check3 "pty: FAIL (feature degrades by design; recorded)"
  else
    result check3 "pty: NOT TESTED (runtime closure node_modules not found)"
  fi
  printf 'The zcode device check marker is BANANA-42.\n' > "$CK_ROOT/workspace/note.txt"
  say check3 "workspace: $CK_ROOT/workspace (bind /sdcard or use this dir via --workspace)"
  say check3 "real task: in the web UI (or ndjson-smoke.mjs --task), prompt the agent to read note.txt and create summary.md containing BANANA-42"
  if [ -s "$CK_ROOT/workspace/summary.md" ] && grep -q BANANA-42 "$CK_ROOT/workspace/summary.md"; then
    result check3 "PASS (summary.md exists with marker) — the project success criterion is met"
  else
    result check3 "PENDING (run the task, then re-run stage check3 to verify)"
  fi
}

case "${1:-}" in
  deps)            stage_deps ;;
  check1-desktop)  stage_check1_desktop ;;
  check1-web)      stage_check1_web ;;
  check2a)         stage_check2a ;;
  check3)          stage_check3 ;;
  *) sed -n '2,10p' "$0"; echo "usage: $0 {deps|check1-desktop|check1-web|check2a|check3}"; exit 2 ;;
esac
