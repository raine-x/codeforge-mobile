# CodeForge English Docs

The complete guide: how to use it, how it works, and why it was built this way.

For the highlights only, see the main [README.md](README.md). Chinese version: [README_ZH.md](README_ZH.md).

An AI coding workbench for Android. Go runs the kernel, Rust does the typesetting, Compose handles the interaction. Three languages, one process, one 17.4 MB APK.

---

## Contents

1. [Quick start](#quick-start)
2. [Using it](#using-it)
3. [Implementation](#implementation)
4. [How it works](#how-it-works)
5. [Build](#build)
6. [Engineering discipline](#engineering-discipline)
7. [Known limits](#known-limits)
8. [License](#license)

---

## Quick start

### Requirements

| Item | Requirement |
|---|---|
| Architecture | arm64-v8a real device, x86 emulators will not work because the Go kernel is cross-compiled for arm64 only |
| OS | Android 8.0, API 26 or newer |
| Network | None needed, the compat environment ships in the APK and unpacks locally on first launch |
| Dependencies | No root, no Termux, no PC |

### Install

Download app-release.apk and side-load it. It is signed with a self-built fixed certificate, v2 plus v3, so later versions install straight over the top.

Changing the signature breaks in-place updates. If the device still has an old debug-signed build, uninstall it first.

### First launch

1. The app pulls up the bundled kernel automatically, running as a specialUse foreground service visible in the notification shade.
2. It unpacks the bundled compat environment, proot plus an Alpine rootfs, in about ten seconds.
3. Before the first conversation, configure a model: open Settings, then Model Services, and add a provider or a model.

---

## Using it

### Sessions and projects

Create a project from the sidebar, pick a directory, the project name defaults to the last path segment, then choose a default permission tier. The three tiers are read-only, ask and auto, used as the initial value of the kernel perm endpoint.

The workspace is bound at the moment the session is created, so switching projects never leaks context.

Sessions can be renamed, archived, unarchived and deleted. Recoverable actions such as archiving are kept visually apart from irreversible ones such as deleting.

The file tree is reached by picking a directory while creating a project, or from the sidebar, to browse workspace files.

### Model setup

Settings, then Model Services:

- Provider: base URL, protocol family (OpenAI-compatible or Anthropic), API key with masked input, optionally stored as an env var
- Model: model id, display name, context window, input and output limits, RPM, and a three-state capability of supported, unsupported or undeclared
- Test connection: verify the key right away instead of discovering it is wrong after sending a message
- Discover: let the kernel enumerate the models a provider actually offers

### Skills, plugins and MCP

All three are reachable through at-completion in the input box:

| Source | Shape | Notes |
|---|---|---|
| Skills | One SKILL.md per name under .codeforge/skills in the workspace | Carries frontmatter with name, description, triggers and an enabled flag; the directory name is the completion token |
| Built-in plugins | Kernel built-ins | plan, goal_mode, vulnerability_research, reverse_analysis, multi_agent, skill_creator and others, individually switchable |
| MCP | External MCP servers | Only loaded ones are completed, because a tool that is not registered yet cannot be called by the kernel either |

Typing at pops up a candidate list. It scrolls, and it grows with what you have installed. Tap to insert.

### Built-in browser and element picking

1. Tap a link in a message and choose the system browser or the built-in one.
2. In the built-in browser, tap Select element and page elements become tappable.
3. A picked element gets a number and joins the conversation, and you can type a note in the same bubble so you never have to leave to type.
4. The element summary, with tag, id, class, text, geometry and styles, travels to the model with the message.

### Models and agents

- Three permission tiers: read-only, ask and auto, set at project creation and changeable at any time
- Approval cards: per-call confirmation, with arguments and diffs
- Goal mode: goal, rounds, verdict and findings in one status strip
- Subagents: their own task, their own context, results merged back
- Checkpoints and undo: roll back to any recorded step

### Background tasks

Submit a script or a command. It runs in the background, the panel shows status and output, and you can terminate it at any time.

### Voice input

- System speech service, that is SpeechRecognizer, zh-CN
- Cloud transcription, bring your own key, OpenAI-compatible protocol

Two-tier routing: cloud wins when configured, a dead system engine falls back automatically, and a 20-second no-result watchdog closes the attempt.

### Compat environment

Settings, then Runtime, one-tap installs:

| Package | Installs |
|---|---|
| Python | python3 plus pip |
| Node.js | nodejs plus npm |
| Go | go |
| C/C++ | build-base, which includes gcc and friends |
| Rust | cargo plus rust |
| Git | git |
| Java 17 | openjdk17-jre-headless |
| FFmpeg | ffmpeg |

The installed badge comes from an existence probe, and the probe command must run alone. Piping it makes the exit code come from the last stage of the pipe, which is always 0, so the probe silently reports everything as installed.

### Orientation

Three responsive tiers:

| Width | Layout |
|---|---|
| Under 600dp | Single column with a drawer sidebar |
| 600dp to 840dp | Two columns |
| 840dp and above | Wide two columns |

In landscape the sidebar collapses completely out of sight, leaving only the hamburger in the main top bar, and expanding or collapsing runs a 220 millisecond width transition.

---

## Implementation

### Layers

| Layer | Where | Responsibility |
|---|---|---|
| Entry | MainActivity.kt, CodeForgeApp.kt | Window, edge-to-edge, notifications, launching the kernel |
| UI | AppRoot, chat, sessions, browser, settings, theme under ui | Pure Compose, reads state, calls ViewModel methods |
| State | ui/ChatViewModel.kt | Single source of truth ChatUiState, folds events into interface state |
| Protocol | CoreTransport and HttpCoreTransport under core | Translates kernel JSON frames into CoreEvent, 24 kinds |
| Kernel | libcodeforge.so, a separate process written in Go | 36 REST endpoints plus 1 WebSocket, execution, protocol, workspace |

Unidirectional data flow: a user action reaches the ViewModel, then the transport, then the kernel, the WebSocket pushes events, the transport parses them into CoreEvent, the ViewModel folds them into ChatUiState, and Compose recomposes.

The UI layer must not touch the protocol layer, the state layer must not touch UI, and the protocol layer must not import the UI layer. Three hard boundaries enforced at compile time by imports.

### How the kernel process starts

CoreService is a specialUse foreground service that launches libcodeforge.so through ProcessBuilder:

1. The environment sets HOME to filesDir so the SQLite database lands in the .codeforge directory, and sets CODEFORGE_PORT to 0 for a random port, because a hardcoded one gets taken by another process.
2. Once the kernel binds it prints PORT=xxxxx to stdout, and the service scans for that prefix line by line.
3. Only then is the HTTP and WebSocket client created.

Authentication uses a cookie named codeforge_token, fetched from a GET on the root path. It is not a query parameter, and this is the easiest thing to get wrong.

### The typesetting engine

libopenrender.so, upstream raine-x OpenRender:

```
raw text, to incremental Markdown IR, to Rust typesetting, to PNG or structured blocks, to Compose
```

- Incremental: only new or changed paragraphs are fed to the engine, already-typeset parts are not recomputed
- Formulas: RaTeX renders them to PNG, and Chinese works through an embedded Noto Sans SC subset behind the RATEX_UNICODE_FONT switch
- Tables: column widths are allocated from measured render widths, not character-count heuristics, so headers stop breaking across lines
- HTML blocks: the engine has no HTML block type, so raw HTML must be detected before it is fed to the engine and mounted as a WebView instead

### The embedded browser

- BrowserOverlay.kt is the full-screen browser, and PageBridge.kt is the JS bridge, where window.__cf provides inspect, list, click, evaluate, contentHeight and viewport
- HtmlBlockView.kt is the HTML embedded in the message stream, with height measured from content and clamped between 140dp and 520dp
- HtmlDetection.kt handles raw HTML detection and merging

### Kernel API

The kernel exposes 36 REST endpoints covering config, permissions, sessions, workspaces, models, providers, tasks, memory, skills, plugins, MCP, the file tree, undo, diff and health checks.

When talking to a local service, always assume the first connection attempt fails. Liveness must be checked against a business endpoint, the health one, because an open port does not mean the handler is serving.

### Compat environment

env-kit.tgz, which is proot 5.1.107.101, and rootfs-alpine.tgz, which is an Alpine 3.24.3 minirootfs, ship inside the APK and unpack into the rootfs folder under filesDir on first launch.

The kernel runs commands inside that same directory tree through cfrun, and the path contract is single-rooted, exposed as the CELL variable.

Mirrors point at the USTC mirror because the official dl-cdn often times out in China, and the version is read from the rootfs itself so a mirror upgrade cannot desync a hardcoded number.

### Signing

The keystore lives at app/keystore/codeforge.keystore, RSA 2048, valid for 10000 days, and produces v2 plus v3 signatures.

Configured is not the same as shipped. Check the apksigner verify output, not the build script. When the keystore file is missing the build silently falls back to the debug keystore, which is v2 only.

---

## How it works

### Why targetSdk 28

Since Android 10, apps may not execute files from their own writable storage. The kernel is an ELF executable that has to run inside the app private directory, so targetSdk 28 is deliberate: trading a compatibility level for runtime capability, on purpose.

The cost is Play Store policy friction, but this app is fundamentally a developer tool that runs arbitrary code on your device, and the sideload-first positioning is explicit.

### Why write our own typesetting engine

Off-the-shelf solutions have three hard limits:

1. Chinese formulas, where many engines fall back to source text or misplace glyphs.
2. Streaming, since parsing the whole block and then typesetting means long replies stall.
3. Offline, because depending on a remote rendering service means formulas need a network.

The in-house chain claims all three: incremental IR for streaming, an embedded CJK font subset for Chinese, and everything local for offline.

### Why HTML must be detected before it reaches the engine

A CommonMark HTML block ends at the first blank line. When a model emits an indented HTML document, the engine chops it into fragments that cannot be reassembled.

So the flow is: detect whether this is a whole HTML document, and if so mount a WebView and bypass the engine entirely, otherwise hand it to the engine.

### Why element picking cannot rely on hover

Touch screens have no mousemove, so any scheme that remembers the currently hovered element is guaranteed to fail on a phone.

The working approach is tap-based: tap, the JS bridge returns the element info, and it gets numbered and joins the conversation.

### Why skill frontmatter must be flat single-line

The kernel frontmatter parser does a line-by-line strings.Cut on the colon, and it is not a YAML parser. A YAML block scalar, which is description followed by a pipe and then indented lines, gets shredded, leaving a bare pipe as the description.

So the bundled skills frontmatter is always rewritten into the flat single-line form the kernel understands.

Generalization: before passing structured metadata across a process boundary, find out which syntax the other side actually parses. The assumption that the other side supports YAML is often wrong.

### Why probe commands cannot contain a pipe

The exit code of gcc --version piped into head -1 comes from the last stage of the pipe, which is head, and that is always 0. So gcc being absent still gets judged as installed.

A probe must be the target command own single execution.

---

## Build

```bash
# requires JDK 17 and Android SDK, compileSdk 36, build-tools 36.0.0
./gradlew assembleDebug
# output at app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# output at app/build/outputs/apk/release/app-release.apk
```

| Component | Version |
|---|---|
| Gradle | 8.14.6, bundled with the wrapper |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.21 |
| Compose UI | 1.9.5 |
| Material 3 | 1.3.2 |
| compileSdk / targetSdk / minSdk | 36 / 28 / 26 |
| ABI | arm64-v8a |

Versions are pinned on purpose, and upgrading them means moving the whole chain at once. The reasoning lives in the version locking section of AGENTS.md.

### Rebuilding the native libraries

```bash
# Go kernel
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o <jniLibs>/libcodeforge.so ./cmd/agent

# Rust typesetting engine
powershell tools/openrender/build-android.ps1   # Windows
bash tools/openrender/build-android.sh          # Linux
```

If you change the font on the Rust side, the shared object must be rebuilt too, because include_bytes requires the font subset to be inside tools/openrender/fonts.

---

## Engineering discipline

The rules live in files, not in oral tradition:

| File | What it records |
|---|---|
| AGENTS.md | Layering, editing rules, verification standards, commit conventions, and the pitfalls hit so far, numbered up to P-053 |
| 工作进程.md | Where things stand, what comes next, open decisions |
| 改动历史.md | Every change: time, files, what, why, and how far it was verified. Archived only, entries are never deleted |

Verification is reported at four levels, honestly. If it was not run on a device, the record says runtime unverified. Documents are more trustworthy than conclusions, conclusions more than documents.

### Layout

```
app/src/main/java/com/hxy/codeforge/
  core/      kernel transport, protocol models, compat env, bundled skills
  render/    typesetting engine bridge and IR
  ui/        chat, sessions, settings, common, browser, theme
  voice/     cloud speech
app/src/main/jniLibs/arm64-v8a/   Go kernel and Rust engine
tools/openrender/                  engine sources, fonts and build scripts
docs/                              design docs and parity checklists
```

---

## Known limits

1. arm64-v8a only, because the Go kernel is cross-compiled for arm64 and x86 emulators cannot install it.
2. The compat environment is not yet verified on a real device. proot uses mmap with PROT_EXEC rather than execve, and whether SELinux allows PROT_EXEC on app_data_file has to be measured, not reasoned about.
3. There is no per-session stream bucketing. The web client isolates streaming frames by session_id, and the Android side has no such mechanism, so frames from a background session can land in the current view.
4. Referencing a file in conversation from the file tree is not implemented. Tapping a file only handles directories.
5. There is no dedicated subagent panel, only an inline card strip in the message stream plus settings.
6. The signing key password sits in plaintext in the build script. That is fine for sideloading, but it must move to an offline-held key plus environment variables before any store release.

---

## License

This project is released under the GNU General Public License v3.0 with one additional restriction: commercial use is not permitted without a separate written license.

### Free to do

- Personal study, research, teaching, non-profit public benefit, review and security auditing
- Clone the repository, deploy, modify and use it without receiving any consideration
- Submit code, docs, translations, issue reports or patches
- Keep copies for personal backup and disaster recovery
- Public redistribution, including through mirrors and package managers, provided the full LICENSE and copyright notices travel with it

### Not free to do

Without prior written permission from the copyright holder, the software or any part of it (source, APK, derivative works) may not be used for any commercial purpose. That covers selling, renting, charging for distribution, subscriptions, hosting fees, offering it as a SaaS or other paid on-demand service, building a paid product around it, or shipping it as a component of a closed-source commercial product.

Use that violates this clause receives no rights under this license and bears all resulting liability.

A commercial license must be requested in writing from the maintainers, stating purpose, scope and term, and takes effect only when signed by the copyright holder.

### Two things to know

**First, this is not free software in the FSF sense.** GPL-3.0 itself permits commercial use, and adding the non-commercial clause makes the work as a whole fail the free-software definition. Adopters should know this. It is the same trade-off the CodeForge-Go kernel repository makes.

**Second, the third-party components are unaffected.** The APK bundles a typesetting engine (MIT), a Noto Sans SC font subset (SIL OFL 1.1), proot and an Alpine rootfs, and bundled skills. Each remains under its own license, owned by its own authors. The additional terms here do not change or extend to them. See the third-party section of LICENSE for the full list.

### Distributing as an APK

You must also make the complete corresponding source available, as GPL-3.0 section 6 requires. This repository is the primary channel; build instructions are in the Build section above.

The full terms and the complete license text are in [LICENSE](LICENSE).
