Android UI Architecture

ZCode Android — Native-first Hybrid

Status: Proposed Architecture
Target Platform: Android
Primary UI Framework: Jetpack Compose
Compatibility Layer: Enhanced WebView
Primary Design Direction: Minimalist Conversational UI
Runtime: Existing ZCode Runtime

---

1. Objective

Establish a gradual transition from the existing Enhanced WebView interface toward a Native-first Hybrid Android architecture.

The objective is to provide a modern Android experience inspired by the interaction principles of ChatGPT Mobile and Gemini Mobile while preserving ZCode's identity as an AI-powered development environment.

This is not a complete rewrite of ZCode.

The existing ZCode runtime, agent capabilities, workspace behavior, and web functionality must remain operational throughout the transition.

2. Architecture Decision

Adopt: Native-first Hybrid

Jetpack Compose will become the primary framework for new Android-native user-facing experiences.

Enhanced WebView will remain available for existing functionality that has not yet been migrated or cannot be reliably implemented natively.

Architecture responsibilities

Layer| Responsibility
Android UI| Native screens and interactions
Jetpack Compose| Design system, chat, agent interface
Kotlin Integration Layer| State management and runtime communication
WebView Compatibility Layer| Existing web-based functionality
ZCode Runtime| Existing server, agent, and execution environment
Workspace Layer| Project and file management

Architectural constraints

1. Do not remove the existing WebView implementation prematurely.
2. Do not duplicate runtime functionality inside the Android UI.
3. Do not introduce a second independent agent implementation.
4. Do not assume undocumented runtime endpoints.
5. Preserve existing authentication, session, and workspace behavior.
6. Keep native UI components independent of WebView implementation details wherever practical.

3. Design Direction

Visual Style

Adopt a minimalist visual language with consistent Android-native interaction conventions.

The interface should prioritize:

- Content readability.
- Clear visual hierarchy.
- Consistent spacing.
- Restrained use of color.
- Predictable navigation.
- Accessible contrast.
- Responsive layouts.
- Dark and light themes.

Avoid excessive gradients, decorative glass effects, unnecessary cards, and animations that do not communicate meaningful state changes.

Design References

Use ChatGPT Mobile and Gemini Mobile as interaction references, not as interfaces to reproduce pixel-for-pixel.

The objective is to adapt their conversational simplicity to a development-agent environment.

4. Implementation Priorities

The implementation must follow this order.

Phase 1 — Design System

Establish the reusable visual and interaction foundation.

Required components:

- Color tokens.
- Typography scale.
- Spacing scale.
- Shape and corner-radius tokens.
- Elevation and surface definitions.
- Light and dark themes.
- Buttons.
- Text fields.
- Icon buttons.
- Menus.
- Dialogs.
- Loading indicators.
- Error and empty states.
- Accessibility conventions.

All components must use shared tokens rather than independent hardcoded styling.

Acceptance criteria:

- Components render consistently across supported Android screen sizes.
- Light and dark themes work.
- Interactive states are distinguishable.
- Text remains readable at supported font scales.
- No decorative component interferes with primary interaction.

Phase 2 — Native Conversational UI

Implement the primary conversation experience using Jetpack Compose.

Required interface elements:

- Conversation screen.
- Message list.
- User message presentation.
- Assistant response presentation.
- Streaming response presentation.
- Message composer.
- Send and stop-generation actions.
- Conversation history navigation.
- Message actions.
- Empty conversation state.
- Loading and error states.

The composer should support a clear extensibility path for future multimodal inputs.

Do not claim image, audio, or file-upload support until the corresponding runtime integration has been verified.

Acceptance criteria:

- Conversation state survives normal recomposition.
- Message ordering is stable.
- Long messages and code blocks render correctly.
- Streaming behavior uses verified runtime events.
- Failed requests produce recoverable error states.
- The keyboard does not obscure the composer.

Phase 3 — Agent UI

Introduce a structured interface for development-agent activity.

The interface should distinguish normal conversational messages from execution events.

Potential component categories:

- Tool invocation.
- Tool result.
- File reading.
- File modification.
- Terminal execution.
- Terminal output.
- Task progress.
- Approval request.
- Execution error.
- Task completion.

These are UI categories, not claims about the current ZCode event schema.

Before implementing the event renderer, inspect the actual runtime protocol and map its verified event types to UI components.

Acceptance criteria:

- Tool activity is visually distinct from ordinary assistant text.
- Execution status is represented accurately.
- Errors remain visible and understandable.
- Approval-required operations cannot be mistaken for completed operations.
- The UI does not fabricate execution progress.

Phase 4 — Workspace UI

Introduce development-oriented functionality progressively.

Potential components:

- File explorer.
- Project navigation.
- Terminal.
- Task and Todo panel.
- Workspace information.
- File preview.
- Editor integration, if supported by the existing implementation.

Workspace functionality must be based on verified capabilities of the existing ZCode runtime.

Do not implement simulated terminal execution or fake file operations.

Phase 5 — Hybrid Integration

Integrate native screens with existing WebView functionality.

Responsibilities:

- Define navigation boundaries.
- Define state ownership.
- Establish lifecycle handling.
- Preserve session continuity.
- Handle transitions between native and web screens.
- Prevent duplicate event processing.
- Define error propagation.
- Handle runtime disconnection and reconnection.

Avoid creating multiple independent sources of truth for conversation or agent state.

Phase 6 — Device Validation

Validate the resulting application on a real Android device.

Testing must cover:

- Cold startup.
- Application lifecycle.
- Runtime initialization.
- Session restoration.
- Conversation creation.
- Message submission.
- Streaming responses.
- Agent activity.
- WebView transitions.
- Keyboard behavior.
- Rotation and configuration changes.
- Background and foreground transitions.
- Memory pressure.
- Runtime failure and recovery.

A successful APK build is not evidence that runtime integration works.

5. Runtime Inspection Requirements

Before implementing the Kotlin integration layer, inspect the existing repository.

The implementation agent must identify:

1. Android application entry points.
2. Existing Compose screens and navigation.
3. Enhanced WebView initialization.
4. JavaScript bridge implementation.
5. Runtime startup and shutdown procedures.
6. Local server lifecycle.
7. Authentication and session management.
8. Actual HTTP endpoints.
9. WebSocket or SSE communication, if present.
10. Agent event structures.
11. Workspace and file APIs.
12. Existing error-handling mechanisms.

Produce an inspection report containing:

- Source file paths.
- Relevant classes and functions.
- Verified communication contracts.
- Existing implementation limitations.
- Missing information.
- Recommended integration boundaries.

Do not invent endpoint names, ports, event types, or request formats.

6. State Management

Establish explicit ownership for application state.

Suggested conceptual separation:

State| Owner
Navigation state| Android UI
Theme and visual preferences| Android UI
Composer input| Android UI
Conversation data| Existing runtime or verified data layer
Agent execution state| Existing runtime
Workspace state| Existing workspace implementation
WebView state| Compatibility layer

The Kotlin integration layer may expose runtime state through observable interfaces such as StateFlow, provided this matches the actual implementation.

Avoid storing duplicate authoritative conversation or execution state in both WebView and Compose.

7. Design and Interaction Requirements

Conversational Experience

The primary screen should prioritize the conversation.

The message composer should remain accessible and support:

- Text entry.
- Send action.
- Generation cancellation.
- Keyboard interaction.
- Future multimodal extensions.

Agent Activity

Agent operations should be represented as structured activity rather than being rendered as ordinary chat bubbles.

Users must be able to distinguish:

- Requested operation.
- Running operation.
- Completed operation.
- Failed operation.
- Operation requiring approval.

Workspace Navigation

Development tools should remain accessible without overwhelming the main conversation interface.

Use contextual navigation or dedicated workspace surfaces where appropriate.

Avoid exposing every development tool simultaneously on the primary chat screen.

8. Non-Functional Requirements

- Maintain compatibility with the existing runtime.
- Preserve existing functionality during migration.
- Avoid unnecessary dependencies.
- Avoid introducing parallel runtime implementations.
- Support Android lifecycle behavior.
- Keep UI rendering independent of long-running operations.
- Use accessible interaction targets.
- Handle network and runtime failures explicitly.
- Avoid blocking the main thread.
- Maintain predictable memory behavior.
- Keep implementation changes incremental and reviewable.

9. Prohibited Implementation Shortcuts

The implementation agent must not:

- Replace the working application with a visual mockup.
- Remove WebView before native functionality is validated.
- invent runtime endpoints or protocols.
- Generate fake terminal output.
- Simulate successful file modifications.
- Display fabricated agent progress.
- Hardcode credentials or authentication tokens.
- Claim runtime compatibility based only on compilation.
- Rewrite the ZCode agent engine as part of a UI migration.
- Introduce unrelated architectural changes without justification.

10. Required Deliverables

Each phase must produce inspectable evidence.

Phase| Deliverable
1| Design tokens and reusable Compose components
2| Functional native conversation screen
3| Runtime-backed agent activity renderer
4| Verified workspace components
5| Native/WebView integration
6| Device validation report

Each deliverable must identify:

- Files changed.
- Existing functionality affected.
- Tests executed.
- Build results.
- Known limitations.
- Remaining work.

11. Definition of Done

The migration is not complete merely because the application compiles.

The architecture is considered successfully implemented when:

1. Native screens operate correctly on a real Android device.
2. Existing ZCode runtime functionality remains accessible.
3. Conversation behavior uses the actual runtime.
4. Agent activity reflects actual runtime events.
5. WebView compatibility remains functional where required.
6. State transitions are reliable.
7. Runtime failures are handled without corrupting application state.
8. Device validation evidence is available.

12. Instructions to the Coding Agent

Start with repository inspection, not implementation.

Read the existing project instructions and identify the current Android architecture.

Do not modify the runtime or remove existing WebView functionality during the initial inspection phase.

Return an evidence-based implementation proposal containing:

1. Current architecture findings.
2. Existing Android components that can be reused.
3. Verified runtime communication contracts.
4. Required new Compose components.
5. Proposed file-level implementation plan.
6. Compatibility risks.
7. Test and validation strategy.

Wait for implementation approval before making broad architectural changes.

Final architectural direction:

Native-first Hybrid
Design System → Conversational UI → Agent UI → Workspace UI → Runtime Integration and Device Validation

The existing Enhanced WebView remains a compatibility layer until each replacement has been independently verified.