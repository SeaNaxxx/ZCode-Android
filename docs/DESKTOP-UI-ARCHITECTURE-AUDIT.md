# DESKTOP UI ARCHITECTURE AUDIT (upstream zai-org/ZCode @ 3c5b2276)

**Method.** Direct read of the upstream source tree at the pinned commit. Every item is **[V] VERIFIED** (file read) unless marked **[A] ASSUMED**. This is the reference for the "Desktop WebView must preserve the original desktop experience" requirement and for the semantic token source used by the native Android design system.

## 1. Package identity

- The desktop app is **`packages/desktop/`** (`@zcode/desktop`, private, `productName: "ZCode"`, no own version — app version comes from the **workspace root `package.json` = 3.16.2** at this pin, applied via `scripts/build-metadata.mjs`). Electron **41.0.3**, electron-builder ^26.8.1.
- `packages/client/` is not a renderer — it is the shared transport lib (websocket/messageport/logger) used by **both** the desktop renderer and web remote control.
- Renderer stack: **React 19 + Tailwind CSS v4** (`@tailwindcss/vite`), Vite 8, TS 6; Radix/shadcn-style components in `packages/ui/src/components/ui/*`; Zustand (47 store files); **Lexical** composer; xterm terminal; Recharts; shiki + streamdown markdown.
- Process map [V from `tsup.config.ts`]: main (`out/main/index.js`, inlines `@zcode/server|shared|rpc|services|client|provider|provider-node|zcode-cua`) + CJS preloads + **host utilityProcess** (`out/host/index.js` running `@zcode/server` + `@zcode/services`) + scheduler utilityProcess; renderer entries: `index`, `resource-manager`, `cua-permission-panel`, `plugin-sandbox`.

## 2. Renderer entry and shell

- `src/renderer/index.html` → `src/main.tsx` (389 ln): React root waits for a **MessagePort** (`InternalChannels.ServicePort`) posted from preload, then `connectViaMessagePort(port)` (`@zcode/client`) → service registry → `<Root isDesktop …/>`.
- Theme bootstrap (main.tsx:79–103): reads `localStorage["zcode-theme"]` (**default `"zai-dark"`**), toggles `html.dark` + `theme-zai-light`/`theme-zai-dark` classes; platform classes `platform-{mac,windows,linux}-desktop`.
- Window kinds via query params: `windowKind=update-status`, `restoreSession`, `supportsSettings`, `initialWorkspacePath`, locale `zh-CN|en-US`.
- The real UI lives in the shared package **`packages/ui/`**: `Root.tsx` (1656 ln), `App.tsx` (1341 ln), `SettingsPage.tsx`, `TaskList.tsx`, `Terminal.tsx`, `PreviewPane.tsx`, `GitPane.tsx`, `LexicalChatInput.tsx`, `ModelConfigSelect.tsx`, `ToolCallBlocks.tsx` + 68 tool renderers, `app-shell/WorkspaceShellLayout.tsx` (2798 ln), `v4/` (176 files — the chat engine, `SessionPane.tsx` 5982 ln), `settings/` (~120 files), `project-sidebar/`, `terminal/`, `i18n/` (zh-CN default + en-US).

## 3. Design tokens (semantic source of truth)

`packages/ui/src/styles.css` (2279 ln): `@import "tailwindcss"` + shadcn/tailwind base + xterm.css; a generic `@theme` fallback + `.dark` fallback; the two **shipping product themes**:

- **`.theme-zai-light`** (styles.css:513–676) and **`.theme-zai-dark`** (677–852). Key verified values (full blocks preserved in the upstream file; excerpt):

| Token | Zai Light | Zai Dark |
|---|---|---|
| `--color-brand` / `--color-primary` | `#000000` | `#ffffff` |
| `--color-primary-foreground` | `#ffffff` | `#000000` |
| `--color-background` | `#f8f8f8` (+win-alt `#ececee`) | `#161616` (+win-alt `#2b2b2b`) |
| `--color-header` / `--color-panel` | `#ffffff` / `#ffffff` | `#202020` / `#202020` |
| `--color-sidebar` | `#f0f0f0` | `#161616` |
| `--color-card` | `#ffffff` | `#2b2b2b` |
| `--color-border` | `rgba(13,13,13,0.1)` | `rgba(255,255,255,0.1)` |
| `--color-foreground` | `var(--color-neutral-800)` | `var(--color-neutral-300)` |
| `--color-accent` | `#ebf4ff` | `#001d3d` |
| `--color-success` | `#1e8a3e` (surface `#eaf7ee`/fg `#166b32`) | `#46bf72` (surface `rgba(70,191,114,.16)`/fg `#87d9a4`) |
| `--color-warning` | `#e07b00` | `#ff8a30` |
| `--color-destructive` | `#e03131` | `#ff5c5c` |
| `--color-idle-task` | `#9e77ed` (surface `#f5f3ff`) | `#7b5ce5` (surface `#160d38`) |
| `--color-tag` | `#e6e6e6` | `#363636` |
| `--color-terminal-*` | 16-color palette, bg = background | dark palette incl. blue `#4099ff`, bright-blue `#80beff` |
| trajectory colors | user `#2563eb`, assistant `#0f766e`, reasoning `#7c3aed`, tool-call `#d97706`, tool-result `#0284c7` | user `#60a5fa`, assistant `#2dd4bf`, reasoning `#a78bfa`, tool-call `#f59e0b`, tool-result `#38bdf8` |

- Typography: `--ui-font-size: 14px` root token; **text-ui ladder** `--text-ui-xl/-lg/-base/-caption/-sm/-xs/-2xs` computed as `calc(var(--ui-font-size) ± n px)` (settings-title +10/+16 variants); mono stack `ui-monospace, SFMono-Regular, Menlo, …, "Microsoft YaHei UI", …`.
- Radius: **no global radius scale** in styles.css; components use `var(--radius-sm)` and a workspace `--workspace-panel-radius`. (The upstream-blessed nested-radius convention used in the Android design proposal comes from component usage, not a token scale.)
- html/body `background: transparent !important` for Electron vibrancy (macOS `vibrancy:"under-window"`, Windows `backgroundMaterial:"acrylic"`, Linux transparent frame).

## 4. Desktop navigation and settings

- Shell: `WorkspaceShellLayout.tsx` — resizable `<aside>` sidebar (width persisted in localStorage) + main conversation frame (`WorkspaceHeader` → `V4ChatPane`) + bottom `AnimatedTerminalPanel` + right side-pane tabs. Sidebar content = `project-sidebar/` ("the only organizing view" per source comment): projects, task groups, usage summary + plan badge.
- Main view switch: `WorkspaceMainView = "chat" | "automations" | "plugin-store"`. Navigation is state-based; **no URL routing**.
- **Settings** = in-app layer (`root/WorkspaceSettingsLayer.tsx` → `SettingsPage.tsx`), sections defined in `settings/settingsPageConfig.ts` — groups `basics`, `agentCapabilities`, `dataAndStats`; ordered section ids: `general`, `appearance`, `modelProvider` (Model), `memory`, `subagents`, `plugin` (Plugins), `mcp`, `skill` (Skills), `commands`, `automations` (beta, hidden), `hooks`, `browser` (Browser Use), `remoteDevices`, `computerUse` (platform-gated), `shortcuts`, `workspaceFileSearch` (hidden), `usage` (Usage stats), `archives`. Cross-section intents via `sessionStorage` keys (`zcode-settings-section-intent`, `zcode-settings-last-section`, …) and a window event.
- Onboarding is a **dialog flow** (`packages/ui/src/onboarding/`, triggered by an onboarding record service), **not** a settings section.

## 5. Chat, composer, agent activity

- Chat engine: `v4/` — `SessionPane.tsx` (5982 ln orchestrator) → `ConversationTimeline.tsx` (2207 ln) → turn groups/rows; message rendering via `components/ai-elements/message.tsx` (derived from vercel/ai-elements, modified) with streamdown/shiki.
- Composer: `v4/ConversationComposer.tsx` (2790 ln) wrapping `LexicalChatInput.tsx` (Lexical PlainText + SlashCommandPlugin + MentionPlugin, IME fallback, draft store); toolbar `V4ComposerToolbar` reusing `chat-input-toolbar/` pure components; mode controls; attachment pills; CUA entry.
- Model selector: `ModelConfigSelect.tsx` (dropdown, provider-family connect submenu, Ctrl+M).
- Tool/agent activity: `ToolCallBlocks/` — **68 renderer files** (`edit.tsx`, `execute.tsx`, `execute-group.tsx`, `agent.tsx`, `ask-question.tsx`, `cua*.tsx`, `changes-group.tsx`, `fallback.tsx`, …) + `ConversationAgentToolCallRow`, `ConversationBackgroundWorks`, subagent drilldown, workflow run panes; shared display logic in `@zcode/shared` (`tool-call-summary.ts`, `zcode-protocol-v4/toolDisplay.ts`).

## 6. Workspace, editor, terminal

- Editor/preview = **read-only viewers**, no Monaco: `PreviewPane.tsx` (code via shiki `CodeViewer`, markdown, PDF, media, docx/xlsx/pptx), diffs via `@pierre/diffs` (worker pool), `WorkspaceFileTree.tsx`, `GitPane.tsx`, whiteboard/treemapping panes.
- Terminal: `terminal/TerminalSession.tsx` = **xterm.js** + fit/clipboard addons; theme from `--color-terminal-*` tokens (`terminalTheme.ts`); PTY server side in `packages/services/src/terminal/terminalService.ts` — **`node-pty` loaded in the host utilityProcess**, streamed to the renderer over the MessagePort RPC channel; desktop rebuilds node-pty in postinstall and packages per-platform prebuilds.

## 7. Theme persistence & appearance settings (verified options)

- `useTheme.ts`: `Theme = "light" | "dark" | "zai-light" | "zai-dark" | "system"`; key **`zcode-theme`**; legacy mapping `light→zai-light`, `dark→zai-dark`; `system` follows `prefers-color-scheme` live; syncs `color-scheme` + `theme-color` meta. (The bundled Android web build's inline bootstrap uses the same key and `zai-dark` default.)
- Appearance section (`settingsCodePreview.tsx:81–280`):
  - Theme mode select: exactly `system` / `zai-dark` / `zai-light`.
  - **Interface text size**: numeric, `MIN 12 / MAX 20 / DEFAULT 14` px, persisted `zcode-ui-font-size-px`, applied **only** as `--ui-font-size` (root font-size untouched — icon/layout sizes unaffected by design).
  - **Code settings** (persisted `zcode-code-preview-settings`): independent **light code theme** and **dark code theme** (options: `github-light/dark`, `vitesse-light/dark`, `min-light/dark`, `github-*-high-contrast`, `catppuccin-latte/mocha`), `showLineNumbers` switch, `wrapLongLines` switch, **code font size** 12–20 px; live dual light/dark preview card.

## 8. How desktop loads its frontend (critical for WebView parity)

- **Production: `file://`.** `desktopHostProcess.ts:145–155` — packaged builds call `win.loadFile(join(dirname, "../renderer/<page>.html"))`; dev uses `ELECTRON_RENDERER_URL` (Vite dev server :5174). `vite.config.ts` sets `base: "./"` explicitly for file:// loading. **No local HTTP server serves the desktop UI.**
- **Data transport: not HTTP.** Main forks the host utilityProcess; host runs `@zcode/server`+`@zcode/services` and hands a **MessagePort** to preload → renderer (`connectViaMessagePort`). The renderer never speaks REST to a backend.
- `resources/glm/zcode.cjs` inside the shipped exe = the bundled agent CLI (`apps/zcode-cli/packages/cli/dist/zcode.cjs`), spawned by the host as `app-server --stdio`.
- **`packages/web/` is the Web Remote Control** (phone controlling a desktop session): same `Root` from `@zcode/ui` but connects via **WebSocket relay** (`connectViaWebSocket` + acknowledged relay protocol) with its own OAuth (`web/src/auth/`). `packages/server/src/http.ts` can serve a static SPA root — that is how the **Android embedded server** serves `zcode/web` (same UI library, HTTP+WS transport instead of MessagePort). `packages/web-share/` + `packages/share-ui/` are separate share frontends.
- Route facts verified on the embedded server (contract stage): `GET /` → SPA 200; `GET /remote` → same SPA (mobile remote-control route); `GET /ws` → **404** (upgrade-only at the pin); `/api/*` → 404 (no SPA fallback).

## 9. Consequences for the Android design system

1. The desktop "identity" = Zai Light/Dark semantic tokens + text-ui ladder + mono-for-technical + flat bordered surfaces + xterm palette + 68-Renderer tool presentation. These are the tokens the native Compose theme must mirror (see ANDROID-NATIVE-ARCHITECTURE-AUDIT.md for the current deviation).
2. Desktop settings is a state-based layered page with 18 sections — Android must adapt the same sections/terminology into native category navigation (see ANDROID-DESKTOP-FEATURE-PARITY.md §Settings).
3. Appearance options (system/zai-light/zai-dark; UI font 12–20; independent code themes per mode; line numbers; wrap; code font 12–20) are the **actual upstream value set** the Android appearance screen must replicate where supported.
4. Desktop has no mobile layout to copy: upstream's own mobile surface is the Web Remote Control reading the same `@zcode/ui` — reinforcing "one identity, platform-native implementations."
5. WebView on Android must never be required for native screens (verified: no WebView in the native app; the loopback server serves the SPA for the desktop-compat surface only — WEBVIEW-DECISION.md).

**Limits:** audit covers the pinned source; the shipped 3.14.3/3.16.3 exes were not unpacked (see ZCODE-DISTRIBUTION-PROVENANCE.md §3), so renderer differences introduced between those cuts are unknown.
