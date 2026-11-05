# ZCode Android --- Native UI Architecture and Design Quality Specification

**Status:** Proposed architecture\
**Target platform:** Android\
**Primary UI framework:** Jetpack Compose\
**Product UI architecture:** Native Android only\
**Runtime:** Existing ZCode runtime\
**Agent engine:** Existing ZCode Agent Core\
**Design quality requirement:** Avoid AI slop

------------------------------------------------------------------------

## 1. Objective

Define the architecture and implementation process for a genuine Native
Android application built around the existing ZCode runtime and agent
capabilities.

The Android application must provide its own native interface. Jetpack
Compose is the UI framework for all user-facing product screens.

This is not a rewrite of the ZCode agent engine. Reuse compatible
upstream services, protocols, runtime behavior, authentication,
workspace operations, and agent capabilities wherever they are verified
to work on Android.

The objective is not to embed the ZCode Desktop or Remote Control web
interface inside an Android shell. The objective is to build a
mobile-first ZCode experience with ZCode's product identity and
workflows.

## 2. Binding Architecture Decisions

### 2.1 Native-only product interface

1.  All primary and secondary product screens must be implemented with
    Jetpack Compose.
2.  WebView must not be used to render the ZCode product UI.
3.  Do not load the ZCode Desktop interface or `/remote` as the Android
    application's primary interface.
4.  Do not treat ZCode Remote Control as an implementation architecture
    or runtime dependency.
5.  Remote Control may be inspected as a visual or interaction reference
    only.
6.  Existing web UI code may remain in the upstream source or
    distribution where required by other platforms, but it must not be
    embedded as the Android product interface.
7.  Do not remove or alter unrelated upstream web functionality as part
    of the Android UI migration.

A native screen is not considered implemented if it merely opens a
WebView, displays a static mockup, or forwards the user to a remote web
interface.

### 2.2 Preserve the existing runtime

1.  Reuse the existing ZCode runtime and Agent Core wherever technically
    compatible.
2.  Do not create a second independent agent engine.
3.  Do not duplicate runtime functionality in the UI layer.
4.  Do not invent undocumented endpoints, RPC methods, event types, or
    request formats.
5.  Preserve verified authentication, session, task, and workspace
    behavior.
6.  Keep long-running runtime operations off the Android main thread.
7.  The UI must communicate through a defined Kotlin integration layer
    and verified runtime contracts.

### 2.3 Android device identity

The Android host must be represented as **This device**, not **This
computer**.

This requirement applies to the actual host identity and behavior, not
only visible wording. Inspect and adapt host metadata, workspace paths,
file operations, terminal behavior, tool context, and environment
information so they accurately describe the Android device.

Do not claim that desktop-specific capabilities are available on Android
unless they have been implemented and verified.

### 2.4 User experience

The intended first-run experience is:

`Install → Open → Request permissions when needed → Sign in to Z.ai → Native ZCode interface`

Runtime provisioning and initialization should occur in the background
where technically possible. Do not expose runtime-management steps as
the normal product workflow, including manual actions to extract Bionic,
install Node/Git/Bash, start the server, or open a web interface.

The application must verify runtime readiness and start or recover
required services automatically. Do not re-extract a valid runtime on
every launch.

Show progress or diagnostics only when they are necessary to explain a
delay, request user action, or recover from an error. Advanced
diagnostics may be available separately.

------------------------------------------------------------------------

## 3. Architecture

Use the following logical layers:

  -----------------------------------------------------------------------
  Layer                               Responsibility
  ----------------------------------- -----------------------------------
  Native UI                           All user-facing Android screens,
                                      navigation, and interactions

  Jetpack Compose                     Design system, conversation, agent
                                      activity, workspace, settings

  ViewModels and state layer          UI state, lifecycle-aware state
                                      collection, user actions

  Kotlin integration layer            Typed APIs, RPC/client
                                      communication, data mapping, error
                                      handling

  Android host layer                  Lifecycle, permissions, storage
                                      access, notifications, background
                                      execution

  ZCode runtime                       Existing server, agent services,
                                      tools, and execution environment

  Workspace and persistence           Project files, sessions, memory,
                                      settings, backup/export

  Model provider                      Z.ai/GLM and other providers
                                      supported by verified upstream
                                      interfaces
  -----------------------------------------------------------------------

### Architecture constraints

-   Compose must not depend on WebView implementation details.
-   UI state must not become a second authoritative copy of runtime
    state.
-   Runtime communication must be isolated behind typed interfaces.
-   Android lifecycle handling must be explicit.
-   Local runtime access must be authenticated and protected.
-   Do not expose internal localhost addresses or runtime controls in
    the normal UI.
-   Do not add a cloud gateway or hosted inference layer unless
    explicitly approved.
-   Model inference may use the configured provider, while local agent
    execution and workspace operations remain on the Android device
    where supported.

------------------------------------------------------------------------

## 4. Design Quality Directive --- Avoid AI Slop

Before designing, implementing, or modifying any UI, research and
understand the meanings of **AI slop** and **AI slop design**.

Do not treat this as a superficial styling preference. It is a mandatory
design-quality requirement.

### 4.1 Understand the warning signs

Evaluate the actual interface for common AI-slop characteristics,
including:

-   Generic, repetitive layouts that resemble default AI-generated
    templates.
-   Excessive gradients, glowing effects, glassmorphism, and decorative
    backgrounds without functional purpose.
-   Unnecessary cards, pills, badges, icons, borders, and rounded
    containers.
-   Arbitrary colors, inconsistent spacing, and weak visual hierarchy.
-   Oversized headings and excessive whitespace that reduce information
    density.
-   Generic dashboard aesthetics unrelated to the product's actual
    workflows.
-   Repetitive component patterns that make every screen look identical.
-   Decorative elements that compete with content or add no usability
    value.
-   Placeholder copy, fabricated metrics, and meaningless interface
    elements.
-   Unnecessary animations and transitions.

These are warning signs, not an exhaustive checklist. Do not
mechanically ban a technique when it has a clear functional or visual
justification.

### 4.2 Design principles

The interface must feel like a professionally designed software product,
not an AI-generated landing page or generic dashboard.

Requirements:

-   Use a deliberate visual hierarchy and consistent design tokens.
-   Prioritize readability, useful information density, and task
    completion.
-   Apply typography, spacing, color, shapes, and elevation
    consistently.
-   Follow Android interaction conventions and accessibility
    requirements.
-   Use Material 3 and Jetpack Compose where appropriate without blindly
    accepting default component styling.
-   Preserve ZCode's identity while designing specifically for mobile.
-   Support coherent Dark and Light themes.
-   Prefer functional clarity over decorative effects.
-   Make every visible element justify its presence through a real user
    need.
-   Use restrained animation only when it communicates state or improves
    interaction.
-   Avoid copying ChatGPT Mobile, Gemini Mobile, or another product
    pixel-for-pixel. They may inform interaction principles, not replace
    product-specific design decisions.

### 4.3 Evidence-based design decisions

Before implementing a screen:

1.  Identify its purpose and primary user task.
2.  Identify the information and controls actually required.
3.  Define navigation and interaction behavior.
4.  Explain why the layout is appropriate for a phone.
5.  Check accessibility, contrast, touch-target sizes, font scaling, and
    responsive behavior.
6.  Compare the proposed design against the AI-slop warning signs.
7.  Remove elements that do not contribute to function, comprehension,
    or a justified visual system.

### 4.4 Design review gate

After implementation, review each screen and report:

-   Functional purpose.
-   Important layout and interaction decisions.
-   Visual elements that may resemble AI slop and why.
-   Accessibility and usability issues.
-   Unnecessary components to remove.
-   Dark and Light theme differences and consistency.
-   Evidence that controls perform their advertised actions.

Do not claim a design is professional merely because it looks modern or
attractive in a screenshot.

------------------------------------------------------------------------

## 5. Implementation Priorities

Implementation must be incremental. Each phase must produce working
functionality and inspectable evidence.

### Phase 0 --- Repository and runtime inspection

Start with inspection, not implementation.

Identify:

1.  Android application entry points.
2.  Existing Compose screens and navigation.
3.  Any WebView creation, initialization, JavaScript bridge, or
    web-based product UI.
4.  Runtime startup and shutdown procedures.
5.  Local server lifecycle and binding configuration.
6.  Authentication and session management.
7.  Actual RPC channels, methods, parameter shapes, and response types.
8.  WebSocket or SSE communication, if present.
9.  Agent event structures.
10. Workspace and file APIs.
11. Session, memory, and settings storage.
12. Error handling and reconnection behavior.
13. Android lifecycle and background execution behavior.
14. Existing tests and their execution environments.

Produce an inspection report containing source paths, classes,
functions, verified contracts, limitations, unknowns, and recommended
boundaries.

Do not infer behavior from method names alone. Inspect the pinned
upstream source and the Android implementation.

### Phase 1 --- Native design system

Establish the reusable Compose foundation.

Required elements:

-   Color tokens.
-   Typography scale.
-   Spacing scale.
-   Shape tokens.
-   Surface and elevation definitions.
-   Dark and Light themes.
-   Buttons and icon buttons.
-   Text fields.
-   Menus and dialogs.
-   Loading indicators.
-   Error and empty states.
-   Accessibility conventions.

Acceptance criteria:

-   Consistent rendering across supported screen sizes.
-   Coherent Dark and Light themes.
-   Distinguishable interaction states.
-   Readable text at supported font scales.
-   Appropriate touch targets.
-   No decorative component obstructs primary interaction.
-   Components use shared tokens rather than unrelated hardcoded
    styling.

### Phase 2 --- Native conversation UI

Implement the primary conversation experience in Jetpack Compose.

Required functionality:

-   Conversation screen.
-   Message list.
-   User and assistant message presentation.
-   Correct rendering of long text and code blocks.
-   Streaming response presentation.
-   Message composer.
-   Send and stop-generation actions.
-   Conversation history navigation.
-   Message actions where supported.
-   Empty, loading, and recoverable error states.
-   Keyboard and insets handling.

Acceptance criteria:

-   Conversation state survives recomposition and configuration changes.
-   Message ordering is stable.
-   Streaming uses verified runtime events.
-   Failed requests produce explicit, recoverable errors.
-   The keyboard does not obscure the composer.
-   No static mock data is presented as a real conversation.
-   Image, audio, and file-upload support is not claimed until the
    corresponding integration is verified.

### Phase 3 --- Native agent activity UI

Represent agent execution as structured activity, not as ordinary
assistant chat text.

Potential categories include:

-   Tool invocation.
-   Tool result.
-   File reading.
-   File modification.
-   Terminal execution.
-   Terminal output.
-   Task progress.
-   Approval request.
-   Execution error.
-   Task completion.

These are conceptual UI categories, not claims about the current runtime
event schema.

Before implementing event rendering, inspect the actual protocol and map
verified event types to Compose components.

Acceptance criteria:

-   Tool activity is distinguishable from assistant prose.
-   Running, completed, failed, and approval-required states are
    represented accurately.
-   Approval requests cannot be mistaken for completed operations.
-   Errors remain visible and understandable.
-   The UI never fabricates execution progress or successful tool
    results.

### Phase 4 --- Native workspace UI

Introduce development functionality progressively, based on verified
runtime support.

Potential components:

-   Project and workspace navigation.
-   File explorer.
-   File preview.
-   Diff presentation.
-   Terminal interface.
-   Task and Todo panel.
-   Workspace information.
-   Editor integration, only if supported by the existing
    implementation.

Use Android Storage Access Framework for user-selected workspace access
where appropriate. Do not request broad all-files access merely to
provision the app's private runtime.

Acceptance criteria:

-   File operations use real runtime or Android storage operations.
-   Workspace boundaries are explicit.
-   No simulated terminal execution or fake file modifications.
-   Android host paths and identity are accurate.
-   Unsupported desktop-only capabilities are not exposed as working
    features.

### Phase 5 --- Native integration and feature parity

Integrate Compose screens with the existing ZCode runtime through the
Kotlin integration layer.

Responsibilities:

-   Define navigation and state ownership.
-   Preserve session continuity.
-   Prevent duplicate event processing.
-   Handle runtime disconnection and reconnection.
-   Define error propagation.
-   Handle task cancellation and permission responses.
-   Ensure lifecycle-aware subscriptions.
-   Verify that the UI reflects authoritative runtime state.

Do not use WebView as a fallback product screen. If a feature has not
been migrated, mark it as unavailable or incomplete and retain the
underlying upstream functionality without presenting it as a native
feature.

### Phase 6 --- Device validation

Validate on a real Android device. Emulator and CI results are
supplementary, not substitutes for physical-device evidence.

Testing must cover:

-   Cold startup.
-   Background runtime initialization.
-   Runtime readiness and recovery.
-   Authentication.
-   Workspace selection and access.
-   Conversation creation.
-   Message submission.
-   Streaming responses.
-   Agent activity events.
-   Permission requests and responses.
-   Task cancellation.
-   Session restoration.
-   Application force-stop and relaunch.
-   Background and foreground transitions.
-   Keyboard behavior.
-   Rotation and configuration changes.
-   Memory pressure.
-   Runtime failure and recovery.
-   Dark and Light themes.
-   Accessibility and font scaling.
-   Data export and restore.

A successful APK build does not prove runtime integration or device
compatibility.

------------------------------------------------------------------------

## 6. Runtime and RPC Contract Requirements

Before implementing or extending the Kotlin integration layer:

-   Inspect the exact pinned upstream revision.
-   Verify channel names and method names.
-   Verify parameter names, types, optionality, and response shapes.
-   Verify event names and event payloads.
-   Verify authentication requirements.
-   Verify cancellation and timeout semantics.
-   Verify reconnection and subscription lifecycle behavior.

The integration layer must:

-   Use typed Kotlin models where practical.
-   Preserve unknown event data for diagnostics rather than fabricating
    interpretations.
-   Distinguish empty results from malformed responses and RPC failures.
-   Propagate meaningful errors to the UI.
-   Avoid silently converting protocol failures into empty lists or
    empty maps.
-   Avoid swallowing authentication, logout, or permission errors.
-   Avoid hardcoded protocol values unless they are verified against
    upstream definitions.
-   Keep secrets out of logs and user-visible error messages.

Do not invent endpoint names, ports, event types, client modes, or
request formats.

------------------------------------------------------------------------

## 7. State Management

Establish explicit ownership for application state.

  -----------------------------------------------------------------------
  State                               Authoritative owner
  ----------------------------------- -----------------------------------
  Navigation state                    Android UI

  Theme and visual preferences        Android UI / persistent settings

  Composer input                      Android UI

  Conversation and task data          Existing runtime or verified
                                      persistence layer

  Agent execution state               Existing runtime

  Workspace state                     Existing workspace implementation

  Authentication state                Existing OAuth/provider service,
                                      securely integrated

  Runtime lifecycle                   Android host layer
  -----------------------------------------------------------------------

The Kotlin layer may expose observable interfaces such as `StateFlow`
when appropriate.

Avoid duplicate authoritative conversation or execution state in
multiple layers. Compose should render state and dispatch user actions;
it must not independently simulate the agent engine.

------------------------------------------------------------------------

## 8. Persistence, Backup, and Reinstallation

Z.ai account authentication must not be assumed to synchronize local
ZCode sessions or workspace files.

Inspect and document separately:

-   Provider account and credentials.
-   Local conversations and task sessions.
-   Workspace files and uncommitted modifications.
-   Memory.
-   Project configuration.
-   Application settings.
-   Export and restore behavior.

Requirements:

-   Persist local sessions and settings across ordinary app restarts and
    in-place APK updates.
-   Store workspace data in a deliberate location with clear ownership.
-   Provide a verifiable export and restore mechanism for data that
    would otherwise be lost on uninstall.
-   Do not export credentials in plaintext.
-   Use Android Keystore-backed protection for sensitive credentials
    where applicable.
-   Treat Android backup as supplementary, not the sole recovery
    strategy.
-   Explain when a workspace folder must be selected again after
    reinstall.
-   Do not claim that signing into the same Z.ai account restores local
    data unless verified by an actual synchronization implementation and
    test.

------------------------------------------------------------------------

## 9. Android Permissions and Storage

Use just-in-time permission requests. Do not request every Android
permission during first launch.

-   Notifications: request runtime permission on supported Android
    versions when notification functionality is introduced and useful.
-   Photos and videos: prefer Android Photo Picker where possible.
-   Audio files: use the system document picker where possible.
-   Microphone: request only for an implemented voice-input feature.
-   Camera: request only for an implemented capture feature.
-   Calendar, location, nearby devices, contacts, phone, and SMS: do not
    request unless a specific implemented feature requires them.
-   Workspace access: prefer Storage Access Framework and persist URI
    permissions where supported.
-   Runtime provisioning in app-private storage does not require broad
    storage permission.
-   Do not request `MANAGE_EXTERNAL_STORAGE` as a shortcut for workspace
    access.

Explain the purpose of a permission at the point where the user invokes
the related feature.

------------------------------------------------------------------------

## 10. Local Runtime and Security

The runtime may retain an internal local service if required by ZCode
compatibility, but it must not become a user-facing web interface.

Inspect:

-   Exact server bind address and exposed interfaces.
-   Authentication for HTTP, RPC, and WebSocket channels.
-   Host and Origin validation.
-   Token storage and lifecycle.
-   Cross-origin and CSRF risks.
-   Workspace boundaries.
-   Command execution permissions and approval behavior.
-   Android app sandbox assumptions.
-   Cleartext local communication.
-   Runtime shutdown and recovery.

Do not assume that binding to `127.0.0.1` isolates a TCP service from
other Android applications.

Compare TCP loopback with Android-compatible Unix Domain Sockets when
feasible. Recommend the least invasive secure design that preserves the
upstream runtime.

Do not change security controls or server binding during an audit-only
task.

------------------------------------------------------------------------

## 11. Non-Functional Requirements

-   Native Compose UI for all product screens.
-   Compatibility with the existing ZCode runtime.
-   No duplicate agent implementation.
-   No WebView product UI.
-   Incremental and reviewable changes.
-   Minimal unnecessary dependencies.
-   Correct Android lifecycle behavior.
-   No blocking work on the main thread.
-   Explicit handling of runtime and provider failures.
-   Accessible touch targets and readable typography.
-   Predictable memory and battery behavior.
-   Secure credential handling.
-   Reliable session persistence and recovery.
-   Accurate distinction between implemented, experimental, and
    unavailable features.

------------------------------------------------------------------------

## 12. Prohibited Shortcuts

The implementation agent must not:

-   Use WebView as the Android product interface.
-   Replace the working application with a static visual mockup.
-   Present ZCode Remote Control as the native application.
-   Invent runtime endpoints, RPC contracts, or event types.
-   Generate fake terminal output or file modifications.
-   Display fabricated agent progress or completion.
-   Hardcode credentials or authentication tokens.
-   Claim compatibility based only on compilation.
-   Rewrite the ZCode agent engine as part of a UI migration.
-   Expose desktop-only features as functional Android features without
    implementation.
-   Add decorative components solely to improve screenshots.
-   Apply generic AI-generated dashboard templates.
-   Modify unrelated runtime, RPC, authentication, or backend behavior
    during a visual-only task.
-   Make broad architectural changes without an evidence-based proposal
    and approval.

------------------------------------------------------------------------

## 13. Required Deliverables

Each phase must produce inspectable evidence.

  -----------------------------------------------------------------------
  Phase                               Deliverable
  ----------------------------------- -----------------------------------
  0                                   Repository and runtime inspection
                                      report

  1                                   Design tokens and reusable Compose
                                      components

  2                                   Functional native conversation
                                      screen

  3                                   Runtime-backed agent activity
                                      renderer

  4                                   Verified native workspace
                                      components

  5                                   Native UI/runtime integration
                                      report

  6                                   Physical-device validation report

  Design review                       AI-slop and usability review for
                                      each implemented screen

  Persistence                         Export/restore and reinstall
                                      behavior report

  Security                            Local runtime and agent execution
                                      security report
  -----------------------------------------------------------------------

Each deliverable must identify:

-   Files changed.
-   Existing functionality affected.
-   Tests executed and their environment.
-   Build results.
-   Physical-device evidence, when applicable.
-   Known limitations.
-   Remaining work.

------------------------------------------------------------------------

## 14. Definition of Done

The Native Android implementation is not complete merely because the
application compiles or a screenshot looks correct.

The target is considered achieved only when:

1.  All user-facing product screens are native Jetpack Compose.
2.  No WebView is used to render the ZCode product interface.
3.  Native screens operate correctly on a real Android device.
4.  Existing ZCode runtime functionality remains accessible through
    verified contracts.
5.  Conversation behavior uses actual runtime data.
6.  Agent activity reflects actual runtime events.
7.  Workspace operations perform real operations within defined
    boundaries.
8.  Android host identity is accurately represented as `This device`.
9.  Runtime failures are handled without corrupting application state.
10. Local data persistence and export/restore behavior are documented
    and tested.
11. Dark and Light themes are coherent and accessible.
12. The design review finds no unresolved generic AI-slop patterns that
    materially harm product quality.
13. Test and device-validation evidence is available.

------------------------------------------------------------------------

## 15. Instructions to the Coding Agent

Start with repository inspection, not implementation.

1.  Read repository instructions and identify the current Android
    architecture.
2.  Inspect the pinned upstream source and current Android
    implementation.
3.  Identify all WebView entry points and the functions they currently
    provide.
4.  Map each required user-facing workflow to a verified runtime
    contract.
5.  Identify which existing Compose components and Kotlin services can
    be reused.
6.  Identify missing native functionality and compatibility risks.
7.  Research AI slop and apply the design quality directive before
    proposing visual changes.
8.  Produce a file-level implementation plan and validation strategy.

The initial inspection must not modify runtime behavior, remove existing
functionality, or make broad architectural changes.

Wait for implementation approval before beginning broad implementation
work.

The inspection report must distinguish:

-   Confirmed facts from source code.
-   Behavior verified by tests.
-   Behavior verified on a physical device.
-   Inferences and unresolved questions.

### Final architectural direction

**Native Android UI (Jetpack Compose) → Kotlin Integration Layer →
Existing ZCode Runtime and Agent Core**

The Android product interface is native-only. Existing runtime and
upstream capabilities are reused through verified interfaces. WebView is
not a product UI fallback.

**Design acceptance:** intentional, product-specific, coherent,
accessible, mobile-first design. Reject generic AI-generated dashboard
aesthetics and decorative UI without functional purpose.
