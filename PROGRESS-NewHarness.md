# New Harness: Qwen Code — Research Findings & Implementation Plan

Status: **implemented + self-reviewed 2026-10-21 (P1–P9).** `AgentKind.QWEN_CODE` wired end-to-end: model + registry, UI labels/accents, `QwenRuntimeBridge.kt` (one-shot `-p` stream-json poll loop, `QwenRouteMapper`, per-session `settings.json`), `RuntimeInstaller` install/update arms + `QWEN_BUNDLE`, `OpenRouterRoutingGateway` `/chat/completions` passthrough, i18n (4 keys × 6 locales), `QwenBridgeTest`, bundle `pocketdev-qwen-arm64-2026.09.1.tar.zst` (sha256 `21ae0fd2…4e98`, host-verified) + manifest + offline asset sync. Harness lists need no per-list edits — all selection surfaces iterate `AgentKind.entries` (`AgentSwitchSheet`, first-launch list, `AgentScreen` tabs, Settings runtime list), and `providerProtocolForAgent(state.agentKind)` drops CLAUDE automatically. Review fixes: (1) `.qwen` added to `WorkspaceCheckpoints` ignore list (risk R7); (2) `providerProtocolForAgent` honors the `dshApi` wire picker for QWEN_CODE+CUSTOM (Qwen's `ifBlank{inferred}` fallback was dead — `AppPreferences.loadProvider` always fills `dshApi`); (3) wire-picker row + URL-change inference enabled for Qwen CUSTOM in `AgentScreen`/`SettingsScreenModern`/`ProviderSetupScreen`; (4) unused `QwenRuntimeBridge.hello()` removed (Test connection uses `providerApi.validate` with the corrected protocol). New tests: `qwenValidationUsesSelectedCustomWire`, `qwenRuntimePathsAreExcludedFromChanges` (116 JVM tests green, `:app:testOnlineDebugUnitTest`). Remaining: on-device verification (§6 steps 2–6) — host lacks device access.
Researched: 2026-10-20/21 against `@qwen-code/qwen-code@0.25.3`, `@oh-my-pi/pi-coding-agent@18.12.3`, and this repo at current HEAD.

---

## 1. Decision

**Qwen Code wins.** It is a pure-JavaScript Node CLI (`engines: node >= 22`, zero hard dependencies) and the PRoot guest already ships **Node v24.19.0** (`RuntimeInstaller.kt:1923`), i.e. the exact runtime class Claude Code uses successfully in this guest today. Its headless mode (`-p --output-format stream-json`) emits **Claude-Code-shaped stream-json**, so `ClaudeRuntimeBridge`'s poll-and-parse machinery ports almost 1:1.

**omp (can1357/oh-my-pi) is rejected** on hard platform grounds: it is Bun-only (`engines: { bun: ">=1.3.14" }`) with Rust N-API natives, and Bun cannot run reliably in this environment (seccomp + kernel floor, §2.1).

---

## 2. Candidate research

### 2.1 omp — rejected

| Fact | Evidence | Consequence here |
|---|---|---|
| Bun-only runtime | npm `@oh-my-pi/pi-coding-agent@18.12.3` metadata: `engines: { bun: ">=1.3.14" }`; README badges Bun runtime | Would have to bundle Bun into the glibc guest |
| Rust N-API natives | deps include `@oh-my-pi/pi-natives` (prebuilt `.node`), `puppeteer-core` | Extra native surface under PRoot ptrace |
| Bun needs kernel ≥ 5.6 | bun.com/docs/installation: "kernel 5.6+ strongly recommended" (minimum ~5.1) | Android 9/10/11 ship 4.9 / 4.14 / 4.19 — below floor on a large slice of minSdk-28 devices |
| Android app seccomp kills Bun | bun-termux issue #15: `pidfd_open` trapped as **SIGSYS** on Android 10 | Seccomp filters are inherited across `fork`/`exec` and run **before** ptrace; PRoot cannot intercept or emulate them (`PROOT_NO_SECCOMP=1` only disables PRoot's *own* seccomp acceleration, not the kernel filter) |
| Official Android Bun is bionic | Bun's Android builds are Termux/bionic-linked PIE | Unusable inside a glibc (`linux-arm64`) chroot; the glibc build hits the kernel/seccomp issues above |

omp's integration surface itself is fine (`omp -p`, `--mode rpc` NDJSON, `~/.omp/agent/models.yml` custom providers) — the runtime is the blocker, not the protocol.

### 2.2 Qwen Code — selected

| Fact | Evidence |
|---|---|
| `engines: { node: ">=22" }`, **zero hard deps** (prebundled `dist/` + `cli-entry.js`) | npm `@qwen-code/qwen-code@0.25.3` metadata (checked 2026-10-20) |
| Guest Node = **v24.19.0** at `/usr/local/bin/node` | `RuntimeInstaller.kt:1923`, install at `:1216-1256` (nodejs.org tarball → `usr/local/lib/nodejs`, symlinks node/npm/npx/corepack) |
| Optional native deps only — `sharp`, `@lydell/node-pty`, `@teddyzhu/clipboard` | npm `optionalDependencies`; all have `linux-arm64-gnu` prebuilds **and all are omittable** (`--omit=optional`): sharp = image paste, node-pty = interactive shell tool, clipboard = TUI clipboard. None needed headless. |
| Proven on Android arm64 | `@mmmbuto/qwen-code-termux` fork "carries only the Android/Termux compatibility delta": PTY swap (`@mmmbuto/pty-termux-utils`), base64 polyfill, skip husky/sandbox at install. Every delta is **bionic/Termux-specific**; this app's guest is glibc Ubuntu 20.04, where upstream's own code path applies. |
| Same runtime class as Claude Code | Claude Code already runs in this guest via the same Node/PRoot stack |

PRoot-specific compatibility notes:
- **Hardlinks**: dsh needed `link()`→`copyFile()` patches (`RuntimeInstaller.kt:653-678`) because PRoot emulates hardlinks as symlinks (`--link2symlink`). Qwen Code persists chats with tmp+**rename** (works under PRoot); npm reify uses renames/copies. No known hardlink dependency — but keep `applyDshAndroidCompatibility`-style patching as a contingency (§5 R4).
- **Folder trust is disabled by default** (`security.folderTrust.enabled`), so no headless trust dialog blocks first run; we simply never enable it (trusted-folders doc).
- **Sandbox is opt-in** (`tools.executionSandbox` is an operator-only policy; `tools.sandbox` unset = no Docker/Podman) — we never set it; PRoot *is* the sandbox.

### 2.3 Qwen Code headless contract (what we build against)

Sources: `docs/users/features/headless.md`, `docs/users/configuration/auth.md`, `docs/users/configuration/settings.md`, `docs/users/features/hooks.md`, `docs/users/configuration/trusted-folders.md` (bundled copies inside the npm package, v0.25.x).

**Invocation**
```
qwen -p <prompt>
     --output-format stream-json [--include-partial-messages]
     --model <id>
     --yolo | --approval-mode plan|default|auto-edit|auto|yolo
     --max-session-turns N   --max-wall-time 30m   --max-tool-calls N
     --system-prompt <text>  --append-system-prompt <text>
     [--resume <sessionId> | --continue]
```
- `--output-format stream-json` = line-delimited `{type:"system"|"assistant"|"user"|"result"}` — **Claude-Code-shaped**: `assistant`/`user` carry `message.content[]` with `tool_use` / `tool_result` blocks; `--include-partial-messages` adds Anthropic-style `content_block_delta` (`text_delta`, `thinking_delta`).
- `--input-format stream-json` exists but is documented "under construction" → **do not use**; prompt stays in argv like Claude (`-p <contextPrompt>`).
- Exit codes: `53` = session-turn cap, `55` = wall-time/tool-call budget, `130` = SIGINT.
- `--json-schema` output mode exists (not needed v1).

**Auth (fully headless, no `/auth` TUI)** — `~/.qwen/settings.json` and/or env:
- `security.auth.selectedType ∈ {openai, anthropic, gemini, …}`; `model.name`; `modelProviders.<protocol>[]` entries (`id`, `envKey`, `baseUrl`, `generationConfig`).
- Env fast-path: `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL`; `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL`; `GEMINI_API_KEY`.
- Qwen OAuth free tier discontinued 2026-04-15; browser OAuth impossible headless → **API-key providers only** (matches this app's model).

**Environment/config knobs we use** (settings.md env table):
`QWEN_HOME` (config dir, default `~/.qwen`), `QWEN_RUNTIME_DIR` (sessions/logs), `QWEN_TELEMETRY_ENABLED=0`, `QWEN_USAGE_STATISTICS_ENABLED=0`, `QWEN_CODE_UNATTENDED_RETRY=1` (infinite 429/529 retry — mobile networks), `QWEN_CODE_SUPPRESS_YOLO_WARNING=1`, `QWEN_CODE_DISABLE_CRON=1`, `QWEN_CODE_DISABLE_ARTIFACT=1`, `NO_COLOR=1`, `QWEN_SYSTEM_MD` (replaces built-in prompt — **avoid**; use `--append-system-prompt` instead).
Context files: global `~/.qwen/QWEN.md` + per-project `QWEN.md` are auto-appended to the system prompt (hierarchical, `context.fileName`).

**Hooks**: Claude-compatible `PreToolUse` **command hooks** — JSON on stdin, JSON on stdout with `hookSpecificOutput.permissionDecision: allow|deny|ask`; `timeout` in seconds; loopback http hooks allowed. This is the documented upgrade path to real interactive approvals (§3.4).

**Sessions**: JSONL under `~/.qwen/projects/<sanitized-cwd>/chats`; `--resume <sessionId>` / `--continue`. v1 does **not** use these (continuity via prompt text, like Claude); `session_id` from `system/init` is captured anyway for a future resume upgrade.

**Tool-result bounding**: tool_result content truncated to 65 536 bytes in json/stream-json output — no unbounded lines; the existing 256 KiB output-capture cap stays safe.

---

## 3. Integration design (decisions)

### 3.1 Session model — Claude pattern, one-shot per message

`QwenRuntimeBridge` mirrors `ClaudeRuntimeBridge`:
- One PRoot spawn per chat message: `qwen -p <contextPrompt> --output-format stream-json --include-partial-messages --yolo --model <model> --max-session-turns 25` (+ `--max-wall-time 30m` as a runaway guard).
- Output captured to file by `NativeSpawnProcess` (pipe mode), tailed 50 ms / 16 KiB via `RandomAccessFile` — unchanged.
- Continuity = `buildContextPrompt`-style history envelope (160k-char budget), **not** `--resume` (avoids coupling to `~/.qwen/projects` state and to cwd sanitization rules).
- Completion authority = `{"type":"result"}` line; graceful kill after (destroy → 3 s → destroyForcibly), same as Claude `:1050-1059`.
- `hello()` probe: `qwen -p "Reply with exactly: ok" --output-format json --max-session-turns 1 --max-tool-calls 0`, parse last `{"type":"result"}`.
- `ProviderRuntimeErrorDetector` reused verbatim (its auth/429/quota patterns are provider-wire-level, not Claude-specific); add Qwen-specific fatal lines for exit 53/55 ("turn limit reached", "wall time") → friendly `SessionFailed`.
- Post-run snapshot diff + checkpoints: use the **shared `WorkspaceCheckpoints`** engine (dsh/agy pattern), not Claude's private copy; add `.qwen` to ignored directory names.

### 3.2 Provider → env mapping (settings surface mirrors Claude Code)

Qwen speaks OpenAI natively → **no `LocalFormatGateway`** (Claude's Anthropic↔OpenAI translator is unnecessary). `providersForAgent(QWEN_CODE)` = all `ProviderKind` **except `CLAUDE`** (Anthropic subscription OAuth token — Qwen cannot consume it; same guard precedent as dsh's `vm_dsh_claude_unsupported`, `MainViewModel.kt:4663-4671`):

| ProviderKind | Qwen protocol | Env written |
|---|---|---|
| `ANTHROPIC` | anthropic | `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`, `ANTHROPIC_MODEL` |
| `OPENROUTER`, `DEEPSEEK`, `KIMI`, `NVIDIA_NIM`, `CUSTOM` | openai | `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL` |
| `OPENCODE_ZEN` | openai-responses | `OPENAI_API_KEY` + `selectedType=openai-responses` via written `~/.qwen/settings.json` (env fast-path has no responses variant) |
| `LLM_ROUTER` | openai + routing | `OPENAI_BASE_URL` → `OpenRouterRoutingGateway` extended to rewrite `provider` in `/chat/completions` bodies (it already does this for Anthropic `/v1/messages`); v1 fallback: treat as plain OpenRouter |
| `CLAUDE` | — | excluded from picker + runtime guard |

Implementation: the bridge writes a minimal `~/.qwen/settings.json` (guest `/root/.qwen`) per session — `security.auth.selectedType`, `model.name`, `modelProviders` entry with `envKey` pointing at the injected env var — plus the env map on `RuntimeLaunchConfig`. Writing settings.json (not relying on env alone) also pins `generationConfig` (timeouts, `streamIdleTimeoutMs`) and disables cron/artifacts/telemetry at the settings layer as belt-and-braces. Model aliases: pass `--model <provider.model>`; no Claude-style alias-pinning needed.

Capabilities set (`AgentRegistry.builtIns`): `API_KEY, PROVIDER_PICKER, MODEL_PICKER, RESUME, INTERACTIVE_APPROVALS` — Claude's set minus `ACCOUNT_LOGIN`. `retryWithNextApiKey` includes Qwen (API-key agent, rotates like Claude/dsh).

Settings UI = the existing **`AgentProviderCard`** path (`AgentScreen.kt:1004-1148`): provider picker, base URL, model field + discovery, multi-key vault management, validate/ping. Ping flows through `providerApi.validate(...)` with `providerProtocolForAgent` (no new branch — Qwen is not dsh, so `profile.kind.protocol` applies, `Models.kt:123-133`).

### 3.3 PRoot-aware system message

Two surfaces, both written by the installer/bridge:
1. **`~/.qwen/QWEN.md`** (global context file, HOME=/root) — written at install + on `ensureSettingsAndHooks`-style session prep. Full environment brief (draft below). Works for every session including interactive terminal use.
2. **`--append-system-prompt`** — short per-session addendum reusing the `<project_workspace>` toolchain-facts block (same facts Claude's `buildContextPrompt:680-733` emits). Never `--system-prompt`/`QWEN_SYSTEM_MD` (they replace built-in tool guidance).

Brief content (both surfaces share the source text):
- You run inside **PRoot** — a ptrace-based chroot emulator, *isolation not security* — with an **Ubuntu 20.04 arm64 (glibc) userland on an Android Linux kernel**. No systemd, no Docker/KVM, no real init, limited PTY.
- Workspace is bind-mounted at `/workspace/<slug>`; stay inside it. (Same `^/workspace/[a-z0-9-]{1,64}$` contract.)
- Android build stack preinstalled: `ANDROID_HOME=/root/android-sdk`, JDK 17, Gradle 8.14.3, offline Maven mirror, `android.aapt2FromMavenOverride` — build APKs with `./gradlew assembleDebug` (reuse the fast-path rules from `antigravityWorkspacePrompt`).
- Resource limits: ~4 GB RAM device; keep Gradle workers low; long builds are normal — prefer `--offline` when deps are cached.
- Filesystem quirks: hardlinks unreliable (use copy/rename); `/proc` is the host's; clock/network may sleep mid-command.
- No GUI browser, no `sudo` targets beyond root-in-chroot, apt exists but network installs are slow and unverified — prefer Node/Gradle tooling already present.

### 3.4 Permissions

- **v1: `--yolo` + `QWEN_CODE_SUPPRESS_YOLO_WARNING=1`** — matches current reality (Claude's file bridge auto-approves everything; `ToolRequested` is never emitted by any bridge today).
- **Documented upgrade path** (any later release, no protocol change needed): write a `PreToolUse` command hook in `~/.qwen/settings.json` whose script POSTs the request JSON to `/pocket-bridge/<id>.request` and waits for `<id>.response` (`allow`/`deny`) — the exact file protocol `watchPermissionRequests` (`ClaudeRuntimeBridge.kt:439-465`) already speaks; flip the watcher from auto-approve to emitting `ToolRequested`.

### 3.5 Install / update pipeline

Mirror **dsh** (npm-in-guest) + **Claude** (offline bundle):
- Online flavor: `npm install --omit=dev --omit=optional --no-audit --no-fund @qwen-code/qwen-code@<pinned>` into `usr/local/lib/qwen` (same staging/`$next` dance as `RuntimeInstaller.kt:485-491`, 20-min timeout), launcher symlink `/usr/local/bin/qwen` → `../lib/qwen/node_modules/.bin/qwen`. Marker file `rootfs/.pocket-qwen-version`.
- Offline flavor: `QWEN_BUNDLE = RuntimeBundle("Qwen Code", "pocketdev-qwen-arm64-2026.09.1.tar.zst", sha256, compressedBytes)` — pinned npm tree prepacked by `scripts/runtime-bundles/` kit; add to `prepareBundledAgentAssets` Sync + `dist/runtime-bundles/manifest.json` (**bundle-identity triplication invariant #5** — all three places together).
- `checkAgentUpdates`: advertise only the **pinned validated** `QWEN_VERSION` constant (dsh pattern, `:379-383`), not npm `latest` — upstream ships fast and we must re-validate stream-json under PRoot before promoting.
- `isAgentInstalled`: marker version check + `File(rootfs, "usr/local/lib/qwen/node_modules/.bin/qwen").isFile` (mirror `:263-277`).

---

## 4. File-level implementation plan

### P1 — Model & registry (compile-break core)

| File | Change |
|---|---|
| `model/Models.kt:51-81` | Add `QWEN_CODE("qwen-code", "Qwen Code", "<subtitle>", "~NN MB")` to `AgentKind`. |
| `model/Models.kt:136-140` | `providersForAgent`: `AgentKind.QWEN_CODE -> ProviderKind.entries.filterNot { it == ProviderKind.CLAUDE }`. |
| `runtime/AgentDriver.kt:32-41,43-84` | Add `qwenRuntime` to `AgentRegistry` ctor + `builtIns()` with capability set (§3.2); `init`'s `require` (`:36-37`) then passes. |
| `ui/MainViewModel.kt:416-431` | Construct `QwenRuntimeBridge`, pass to `AgentRegistry`, include in `activeRuntime()` (`:431`) dispatch. |

### P2 — Exhaustive-`when` sites (all 11 compile breaks)

`Models.kt:136-140` (P1) · `ui/ModelLabels.kt:44-48` `titleRes` → `R.string.agent_title_qwen`, `:51-55` `subtitleRes` → `agent_subtitle_qwen` · `runtime/RuntimeInstaller.kt:235-239` first-run dispatch, `:254-257` `ensureAgentInstalled`, `:263-277` `isAgentInstalled`, `:401-404` `updateAgent` · `ui/PocketDevApp.kt:1562-1565` `toolchainDownloadSummary`, `:1651-1655` `AgentChoiceRow` accent color (Qwen brand purple `0xFF615CED`; CC `0xFFD97757`, DS `0xFF4D6BFE`, AG `0xFF4285F4`), `:1656-1660` monogram `"QC"` · `ui/AgentScreen.kt:851-855` tab short title (+ `:946-950` `viewedAgent` → `agent_title_*`).

### P3 — Installer (`runtime/RuntimeInstaller.kt`)

- Companion (`:1908-1996`): `const val QWEN_VERSION = "0.25.3"` (pin at implementation time), `private val QWEN_BUNDLE = RuntimeBundle(...)`, guest-path const `QWEN_GUEST_PATH = "/usr/local/bin/qwen"`.
- `:235-277` install-check arms; `ensureQwenInstalled()` = npm-in-guest (copy `ensureDshInstalled:607-636` shape) + `updateQwen()` (copy `updateClaude:425-433` marker logic); `installedAgentVersions:349-365` + `checkAgentUpdates:368-392` arms.
- New `ensureQwenSettings(rootfs)`: write `/root/.qwen/settings.json` (auth type, model, provider entry, `telemetry.enabled:false`, `privacy.usageStatisticsEnabled:false`, `experimental.cron:false`, `experimental.artifact:false`, `tools.approvalMode:"yolo"`, `security.folderTrust.enabled:false`) and `/root/.qwen/QWEN.md` (§3.3 brief) — called from the `ensureSettingsAndHooks:1618` site.
- `process()` (`:1532-1616`): **no change** — Android SDK/Gradle env, `/pocket-bridge` bind, `HOME=/root` already apply to every guest command.

### P4 — Bridge (`runtime/QwenRuntimeBridge.kt`, new)

- `startSession`: build `contextPrompt` (reuse `buildContextPrompt` pattern incl. `<project_workspace>` facts), argv per §3.1, env per §3.2 + hygiene vars, `NativeSpawnProcess.start(pseudoTerminal=false)`, poll loop copy (`:200-241`).
- `consumeQwenJsonEvent`: port `consumeClaudeJsonEvent:474-585` — `system/init` (capture `session_id`, ignore rest), `system/*` → `RuntimeLog`, `content_block_delta` `text_delta`→`AssistantDelta` / `thinking_delta`→`ReasoningSummary` (150 ms/2000-char throttle), `assistant` `tool_use`→`emitToolStarted` (dedup by id; detail map: `run_shell_command`→`command`, `write_file`/`read_file`/`edit`→`file_path`, `glob`/`grep`→`pattern`), `user` `tool_result`→`ToolCompleted`, `result`→`SessionCompleted`/throw + graceful kill. **Pin exact field names against a device capture before finalizing** (§6 step 3).
- `ProviderRuntimeErrorDetector` reuse + exit-code mapping (53/55 → friendly failure; 130 = user stop, silent).
- `hello()`, `respondToApproval` (no-op v1), undo/accept/checkpoints via shared `WorkspaceCheckpoints`; ignore-list adds `.qwen`.
- `MainViewModel` wiring: events collection (`:571-572`, `:2052` pattern), `configureBridgeRoots` (`:2044-2048`), `retryWithNextApiKey` (`:5207`) include, CLAUDE-provider guard mirroring `:4663-4671` with new `vm_qwen_claude_unsupported`.

### P5 — UI

- `AgentScreen.kt`: tabs auto-include via `entries` (`:238-240`); config card = `AgentProviderCard` branch (no new card); hide `dshApi` row (already dsh-gated `:1043-1045`, `:1630-1633`); OpenRouter routing rows already LLM_ROUTER-gated.
- `PocketDevApp.kt`: startup routing (`:330-383`) — Qwen needs **no** onboarding branch (API-key flow reuses `ProviderSetupScreen`); `AgentChoiceRow` color/monogram (P2); "Recommended" badge stays on DEEPSEEK (`:1687`).
- `SettingsScreenModern.kt:423-427,830-912`: agent list additions.
- `data/AppPreferences.kt`: **no schema change** — `agent_kind` (`:42-44`) stores new `stableId`; per-agent provider namespace auto-derives via `providerPrefix:243`; `saveAgentConversation:71-77` already generic.

### P6 — i18n (6 locales)

New keys: `agent_title_qwen`, `agent_subtitle_qwen`, `vm_qwen_claude_unsupported`, `rt_install_qwen`, `rt_qwen_verifying`, `rt_qwen_failed` (+ any `st_*` reuse). Add to `values/strings.xml` (`:261-266` agent block) and mirror in `values-{zh,ru,hi,es,ar}` (agent block at `:194/198`, ar `:204`).

### P7 — Gateway (optional, feature-parity item)

`runtime/OpenRouterRoutingGateway.kt`: add `/chat/completions` body rewrite (`provider.order`/`provider.allow_fallbacks` — same JSON path OpenRouter accepts on the OpenAI wire) so `LLM_ROUTER` keeps provider-routing for Qwen. If skipped, `QwenRuntimeBridge` points `OPENAI_BASE_URL` straight at OpenRouter and routing is silently off (documented in code).

### P8 — Tests (`app/src/test/java/com/jarves/mh/`)

- Fix `DshBridgeTest.kt:349-398`: `providersForAgent` expectations + `agentKindsAreStable` (`:392-398`) for the 4th entry.
- New `QwenBridgeTest.kt` (JUnit4 + `org.json`, temp dirs via `Files.createTempDirectory("mh-…")`): stream-json parser fixtures (init/delta/tool_use/tool_result/result/error lines, exit-53/55 detection), launch-config env mapping per provider (incl. CLAUDE rejection), argv builder, settings.json writer.

### P9 — Bundle pipeline & docs

- `scripts/runtime-bundles/`: add qwen bundle build step (npm pack tree → tar.zst + marker), README contract table row.
- `app/build.gradle.kts:35-46`: add `pocketdev-qwen-arm64-<ver>.tar.zst` to `prepareBundledAgentAssets`/`prepareOfflineRuntimeAssets`.
- `dist/runtime-bundles/manifest.json`: `qwen` entry (version/file/sha256/bytes/includes/excludes).
- `AGENTS.md` + README: four-harness architecture line, `AgentKind` count, install commands flavor-qualified as usual.

---

## 5. Risks & mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | Node 24 + Qwen Code memory pressure on 4 GB devices (Claude Code already the heaviest path) | `--max-session-turns 25`, `--max-wall-time 30m`, `NODE_OPTIONS=--max-old-space-size=1024` in bridge env; monitor via existing FGS progress |
| R2 | stream-json shape drifts from Claude's (field names, partial-event framing) | Parser fixtures pinned from a **device capture** (§6 step 3), not docs; `result` line is the only completion authority |
| R3 | npm-in-guest install fails on flaky mobile network (20-min window) | dsh-proven staging dance (`$next` + atomic rename); offline flavor ships the pinned tree as `QWEN_BUNDLE` |
| R4 | PRoot hardlink emulation bites npm reify or Qwen internals (cf. dsh `link()` patches) | `--omit=optional` shrinks native surface; if observed, port `patchDshHardLinkPublication` approach; `emulateHardLinks` flag exists on spawn |
| R5 | Optional native deps attempted despite `--omit=optional` (npm version drift) | Bundle flavor pre-pins npm 11.17.0 (core bundle); verify `qwen --version` + `hello()` post-install gate (`:1523`-style check) |
| R6 | `--input-format stream-json` immaturity | Not used — one-shot `-p` design (§3.1) |
| R7 | Guest writes `.qwen/` into workspace polluting Changes tab | `.qwen` added to snapshot ignore list (P4) |
| R8 | `LLM_ROUTER` routing silently off if P7 deferred | Explicit code comment + release-note line; routing is a refinement, not a break |

## 6. Verification plan

1. `./gradlew :app:testOnlineDebugUnitTest` + `./gradlew lintOnlineDebug` (flavor-qualified per repo rules).
2. `./gradlew :app:installOnlineDebug -x lint` on device; first-run: Qwen tab appears, install completes (watch `RuntimeSetupService` progress), marker file exists.
3. **Protocol pinning capture**: run one real session with output tee'd to `cacheDir`, save `stream-json` transcript, freeze parser fixtures from it.
4. Smoke: Qwen task edits a file in `/workspace` (Changes tab shows diff; undo restores), then in a scratch Android project asks the agent to run `./gradlew assembleDebug` in the terminal tab path — proves SDK env parity with the other three harnesses.
5. Provider matrix spot-check: CUSTOM (OpenAI), ANTHROPIC, OPENROUTER, LLM_ROUTER; CLAUDE absent from picker; wrong-key path shows `ProviderRuntimeErrorDetector` message and rotates keys.
6. Offline flavor: `./gradlew :app:assembleOfflineRelease` asset sync picks up `pocketdev-qwen-*.tar.zst` (triplication check: filename/sha/size equal across build.gradle.kts, manifest.json, RuntimeInstaller.kt).

## 7. Sources

- github.com/can1357/oh-my-pi; npm `@oh-my-pi/pi-coding-agent@18.12.3` (engines/deps, 2026-10-20)
- bun.com/docs/installation (kernel requirement); github.com/bd-loser/bun-termux issue #15 (`pidfd_open` SIGSYS, Android 10)
- github.com/QwenLM/qwen-code; npm `@qwen-code/qwen-code@0.25.3` (engines/deps/optionalDeps, 2026-10-20)
- Qwen docs (bundled in package + qwenlm.github.io/qwen-code-docs): `users/features/headless.md`, `users/configuration/auth.md`, `users/configuration/settings.md`, `users/configuration/trusted-folders.md`, `users/features/hooks.md`
- npm `@mmmbuto/qwen-code-termux` README (Android/Termux delta inventory)
- Repo: `RuntimeInstaller.kt` (Node v24.19.0 `:1923`, npm-in-guest `:485-491`, PRoot argv `:1532-1616`, bundles `:1954-1991`), `ClaudeRuntimeBridge.kt`, `DshRuntimeBridge.kt`, `AntigravityRuntimeBridge.kt`, `AgentDriver.kt`, `Models.kt`, `AgentScreen.kt`, `PocketDevApp.kt`, `MainViewModel.kt`, `app/build.gradle.kts:33-121`, `dist/runtime-bundles/manifest.json`
