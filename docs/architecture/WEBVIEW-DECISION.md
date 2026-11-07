# WebView decision — §2.1 retention list (explicit)

**Decision:** the Android product interface is native-only (Jetpack Compose).
WebView is retained as a **limited technical capability**, not a product
interface and never a fallback ([ANDROID-UI-ARCHITECTURE-REVISED.md](../ANDROID-UI-ARCHITECTURE-REVISED.md)
§2.1). The former `LocalWebViewScreen` product path has been deleted; any
future WebView use must fit one of these categories and only these:

1. **Trusted external web content a user explicitly opens inside the
   application** — e.g. a link target the user taps that is safer to render
   in-app than to hand off, with JS disabled unless the content requires it,
   and the origin displayed in the chrome (no invisible embedding).
2. **Documentation pages or web previews when a native renderer is not
   justified** — e.g. an upstream help page or a generated HTML preview of a
   verified artifact. Read-only, no bridge to the agent.
3. **Future web-based tools that have no native implementation, and only
   after a security review** — a written proposal naming the tool, the
   data it can reach, and why a native implementation is not justified.
   No such tool is approved today.

**Prohibited (already enforced by absence):** the conversation surface, the
workspace, settings, the terminal, agent activity — and any use as a
fallback for an unmigrated feature (unmigrated features are marked
unavailable per the feature matrix, never silently WebView-wrapped).

**Current state in this repo:** no WebView is instantiated by the native
product surface. `BuildConfig.DEBUG` still enables WebView *debugging* for
the loopback string (harmless, debug-only); no `WebView` object is created
in the native screens. No dependency on WebView implementation details in
Compose.

Any proposal to use WebView outside this list requires an evidence-based
proposal and owner approval before code is written.
