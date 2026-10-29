# Phase 2 receiver boundary review

> Integration update: the user approved and accepted the [single-APK video preview](SINGLE_APK_INTEGRATION.md). The assessment below describes its review-time baseline and proposed strategy; native implementation is now confined to the selected engine module. No license was adopted and distribution clearance remains open.

> Subsequent development update (2026-10-29): the user approved an opt-in [direct-TV video experiment](TV_RECEIVER_EXPERIMENT.md). Statements below about absent implementation describe the reviewed baseline. The harness now selects libplist 2.7.0, OpenSSL 3.5.4 and Android MediaCodec; GStreamer remains unselected for production. This does not adopt licenses or clear distribution.

Assessment date: 2026-10-28. Branch: `plan/phase-2-airplay`, baseline `b876c0d` plus uncommitted architecture documentation. Documentation proposal only; no receiver interfaces, modules or manifest entries are implemented. See [licensing assessment](../LICENSE_COMPLIANCE.md) and [Phase 2 plan](PLAN.md).

## Findings from documentation and source

The actual Gradle graph still has six modules and no receiver implementation, AIDL, JNI or native build. `core:domain` is Kotlin/JDK-only. Existing light and notification ports contain no native/receiver dependencies. Phase 1 behavior needs no rewrite.

The updated architecture supports a single APK and a dedicated process in principle, but is not yet an interchangeable implementation. Its proposed `integrations:uxplay-native` owns the service, playback Activity, JNI and native rendering together. Replacing that module would replace reusable Android lifecycle and IPC hosting too. The abstract control contract is conceptual, not an implemented substitutability guarantee. Naming the integration in the main application API, passing GStreamer sink strings as settings, or exposing UxPlay pairing/session structs would create avoidable coupling.

Required correction: define independently authored receiver semantics, then keep the Android runtime host distinct from the UxPlay engine adapter. No GPL-derived declarations, copied upstream headers, native constants, callback structs or pipeline syntax belong in core, IPC or shared runtime. Ordinary protocol facts and independently designed application requirements are not a license for copying upstream implementation expression.

## Proposed narrow application contract

The following is a design sketch, not a new API or implementation. Types are ordinary Kotlin/JDK values; no Android, Binder, JNI, GStreamer, UxPlay or media dependency. Prefer existing callback/executor conventions; a coroutine/DI framework is unnecessary.

```kotlin
interface AirPlayReceiverControl : AutoCloseable {
    fun snapshot(): ReceiverSnapshot
    fun start(options: ReceiverOptions, reply: (ReceiverResult) -> Unit): RequestHandle
    fun stop(reply: (ReceiverResult) -> Unit): RequestHandle
    fun observe(observer: (ReceiverSnapshot) -> Unit): Subscription
    override fun close()
}
interface RequestHandle { fun cancel() }
interface Subscription : AutoCloseable { override fun close() }
```

Use an AirPlay-specific package (or `AirPlayReceiverSnapshot`) to avoid confusing this proposed snapshot with the existing notification `ReceiverSnapshot`. Names are provisional. `snapshot()` returns cached immutable state without blocking IPC. `observe` returns current state then ordered updates on a documented injected dispatcher; unsubscribe prevents future delivery, including queued stale updates.

| Model | Proposed semantics |
| --- | --- |
| Availability | Available, not included in this build, unsupported device, temporarily unavailable; separate from running state. |
| Capabilities | Explicit supported audio/video modes and display/session limits where verified. Distinguish unknown from unsupported. A reported extended-display capability is not device acceptance evidence. No vendor property bag. |
| ReceiverOptions | Validated display name and only settings actually required by the first implementation. No native command line, pipeline expression or library path. Pairing policy/secrets need a separately reviewed narrow path if required. |
| Lifecycle | Stopped, starting, ready/discoverable, stopping, failed. Ready follows successful initialization/discovery registration, not merely service binding. |
| Connection/session | None, connecting, connected, playing, disconnecting; optional opaque session ID and normalized mode. Bound the session count, initially one if feasibility supports it. No private sender identifiers by default. |
| ReceiverResult | Accepted operation ID, rejected typed failure, or completion with observed state; choose a single acknowledgement convention at implementation. Accepted is not proof playback began. |
| ReceiverFailure | Unsupported, invalid configuration, busy, permission denied, startup failure, discovery failure, connection failure, decode/render failure, disconnected, timeout, cancelled, internal failure. Stable category plus operation/session ID; sanitized detail only, no native exception object. |

Exactly-once replies for admitted requests, bounded pending work, and deterministic rejection after close are required. Cancelling local waiting does not prove the remote operation was undone; refresh state instead of reporting success or blindly retrying. Define and test repeated start, repeated stop, start-during-stop and stale-session updates. Subscription lifetimes belong to their consumers.

`close()` on the main-process control adapter releases bindings, subscriptions and pending replies; it does not silently stop a user-enabled receiver when a settings screen closes. Explicit `stop()` requests receiver-wide shutdown. Separately, engine `close()` in the receiver process performs idempotent shutdown and releases all owned native/network/media resources. These are different ownership scopes.

## Proposed composition and runtime boundaries

```text
app: build-time selection, process-aware composition, navigation
  -> features:airplay           UI/controller -> pure receiver port
  -> receiver Android runtime   private service + playback Activity in :airplay
  -> integrations:uxplay-native selected engine implementation

features:airplay -> core:domain, core:designsystem, ipc:airplay-contract
receiver Android runtime -> core:domain, ipc:airplay-contract
integrations:uxplay-native -> receiver runtime's local engine API
core:domain -> Kotlin/JDK only
```

A proposed `integrations:airplay-runtime` module supplies the service, local engine API, playback host and lifecycle coordination. This is one meaningful additional boundary beyond the earlier plan. It does not implement the AirPlay protocol. Keep its local engine API in a small package initially; a separate API Gradle module is unnecessary until build needs justify it.

The service delegates to a `ReceiverEngine` with local start/stop/status/close operations. Its Android-only rendering attachment API may accept a Surface or other platform target **inside `:airplay` only**. Such types never enter pure domain or AIDL. The engine implementation owns protocol discovery/session networking, JNI, native loading and decoder/audio/render integration. UxPlay headers and GStreamer dependencies stay private to `uxplay-native`.

Use explicit composition, for example an Application-provided engine factory implemented by the app and invoked lazily by the runtime service. This is a proposal to validate, not a framework requirement. Do not initialize main-process feature objects when Application starts in `:airplay`. The runtime must not depend on the concrete UxPlay module; app composition selects that module. A different receiver may bring its own rendering adapter without changing notification, light, UI or control APIs.

One production APK packages the chosen engine. A Gradle dependency/factory change selects a replacement at build time; runtime plugins, reflection-based discovery, downloaded libraries and capability registries are out of scope. A development harness must not become a production dependency or expose the private service.

## IPC and process invariants

Keep the existing focused control/status contract: small versioned commands and immutable status events, bounded calls, death handling, scoped observation and explicit service binding. AIDL types are Android adapter types, mapped to pure models. No frames, audio, surfaces, shared-memory buffers, native pointers or arbitrary engine configuration cross IPC. Mac media goes directly to the chosen receiver stack.

Service and playback Activity belong to private `:airplay` components, not `isolatedProcess=true`. Validate surface loss/recreation, foreground-service eligibility, audio focus, background Activity launch, and discoverable-idle lifetime. The service owns native engine lifetime; Activity surface lifetime is subordinate and must not accidentally destroy a persistent session or retain a dead surface. These are unresolved implementation decisions, not claims of platform validation.

## Replacement and acceptance checks

Before integrating UxPlay, agree these semantics and preserve upstream provenance. Add only the seam exercised by the feasibility experiment. Before application integration, prove composition with a small test engine: startup, status, failure, stop and cleanup. A test engine is test infrastructure, never a fake production receiver.

A replacement build must omit UxPlay sources, headers, JNI registration, native libraries, transitive GStreamer/crypto/discovery dependencies used only by UxPlay, manifest initializers and engine-specific configuration. Verify resolved dependencies and packaged APK contents; renaming classes is insufficient. A replacement may intentionally retain independently licensed dependencies, but must inventory them again. Existing Phase 1 tests and behavior remain unchanged.

Architectural separation helps identify and remove copied code; it does not decide whether a distributed combined APK is GPL-covered. Independently authored adapters can still be part of a GPL-covered combined release. Future license choices require ownership and dependency review, not simply swapping the module name.

## Approval scope

This review proposes a neutral runtime/engine split and application semantics. No module, implementation code, native source, license file, commit or push is added. Next approved implementation should be the smallest evidence-driven feasibility seam, not a general receiver framework.
