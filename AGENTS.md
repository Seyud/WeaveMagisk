# WeaveMask — Agent Guide

A Magisk fork with Miuix UI (Jetpack Compose). Native (C++/Rust) + Android app (Kotlin/Compose).

## Build Commands

All builds go through `build.py` (Python 3.8+). Never invoke gradlew or cargo directly — `build.py` sets up environment, toolchains, and cross-compilation flags.

```bash
./build.py ndk          # Download and install ONDK (required first)
./build.py all          # Build everything (native + app + test APK)
./build.py native       # Build native binaries only
./build.py app          # Build the Magisk app APK
./build.py stub         # Build the stub app
./build.py test         # Build the test app
./build.py clean        # Clean all build artifacts
./build.py gen --abi arm64-v8a  # Generate IDE compilation database
./build.py clippy       # Run clippy on Rust sources
```

Flags: `-r` for release, `-v` for verbose, `-c <file>` for custom config (default: `config.prop`).

CI uses `python build.py -v -c .github/ci.prop all` (arm64-v8a only).

### Direct Gradle invocation (gotchas)

`build.py` wraps Gradle with the right environment and flags — always prefer it. If you must invoke `gradlew` directly (e.g. to verify a dependency change without rebuilding native):

- The Gradle project root is **`app/`**, not the repo root. `gradlew`/`gradlew.bat` live in `app/` and must be run with `app/` as the working directory. Running from the repo root fails with: `Directory '<repo>' does not contain a Gradle build.`
- The main app module was renamed to **`:apk`** (root project name is still `Magisk`, per `app/settings.gradle.kts`). Upstream Magisk's `:app:*` task names do **not** exist here — `:app:assembleRelease` fails with "project 'app' not found". Use `:apk:assembleRelease` / `:apk:assembleDebug`.
- Verified full combo: `cd app && ./gradlew :apk:assembleRelease :apk:assembleDebug` (both variants; release runs R8).

## Prerequisites

- `ANDROID_HOME` environment variable pointing to Android SDK
- `ANDROID_STUDIO` (optional, auto-discovers bundled JDK) or JDK 21 in PATH
- Windows: enable Developer Mode for symlink support
- `./build.py ndk` must be run before any native build — it downloads ONDK r30.0 to `$ANDROID_HOME/ndk/magisk`
- `sccache` or `ccache` in PATH will be auto-detected and used for build caching

## Architecture

### Native (`native/src/`)

Rust workspace + C++ via ndk-build. Workspace members: `base`, `boot`, `core`, `init`, `sepolicy`.

Build targets: `magisk`, `magiskinit`, `magiskboot`, `magiskpolicy`, `resetprop`. Rust builds first, then C++ links against Rust static libs.

Cargo config at `native/src/.cargo/config.toml`. Rust edition 2024. Clippy denies `unwrap_used`. rustfmt: `imports_granularity = "Module"`.

External dependencies are git submodules in `native/src/external/` — always clone with `--recurse-submodules`.

Generated code: `native/out/generated/flags.h` and `flags.rs` are created during build. Run `./build.py native` before IDE work on native code.

### App (`app/`)

Gradle composite build. Modules:
- **apk** — Main application (Jetpack Compose + Miuix). Package: `io.github.seyud.weave` (renamed from upstream Magisk's `:app`)
- **core** — Library module with business logic, Room DB, Retrofit, AIDL
- **shared** — Pure Java/Android library (no Kotlin). Used by core and stub
- **stub** — Lightweight stub APK for hidden mode. Obfuscated with lsparanoid
- **stub-res** — Extracted stub string resources
- **test** — UI test APK (UIAutomator). Always built as release
- **build-logic** — Composite build with custom Gradle plugin (`MagiskPlugin`)

Build conventions defined in `app/build-logic/src/main/java/Setup.kt`:
- `compileSdk` 37, `minSdk` 24, `targetSdk` 37
- Java 21 source/target compatibility
- NDK path hardcoded to `$ANDROID_HOME/ndk/magisk`
- Custom `TransformApkTask` adds EOCD comments with version metadata
- JNI libs renamed: `magisk` → `libmagisk.so`, etc.
- BusyBox downloaded at build time from GitHub releases

Version config: `app/gradle.properties` (`magisk.versionCode`, `magisk.stubVersion`) and `config.prop` (overrides). `build.py` generates `app/build/flags.prop` with `version`, `versionCode`, `abiList`.

### Navigation & UI

- Navigation3 with custom type-safe `Navigator` and spring-physics transitions
- Miuix component library (not standard Material3)
- Liquid Glass effects: `CombinedBackdrop`, `InnerShadow`, `Lens`, `Vibrancy`
- Dual home layouts: Classic and Weavsk, switchable in settings

## Config

`config.prop` (gitignored content, sample at `config.prop.sample`): version, outdir, abiList, signing configs. All optional.

`.github/ci.prop`: `abiList=arm64-v8a` — used in CI test builds to speed up.

## Testing

- AVD tests: `scripts/avd.sh test <api_version>` — requires pre-built APKs
- Cuttlefish tests: `scripts/cuttlefish.sh` — for virtual device testing
- Test APK: `./build.py test` — builds UIAutomator test APK, always as release
- CI runs AVD tests on API 30-36 + 36.1 + 37.0 + CANARY (x86_64), API 30 (x86)
- CI test jobs only run on `workflow_dispatch` (manual trigger), not on push
- `test_common.sh` uses `$self` (TestRunner) for `testAppHide`/`testAppRestore`, `$app` (AppTestRunner) for others
- `BaseTest.prerequisite()` must match upstream: `Shell.getShell().isRoot` + `Connection.await()`. No UI operations (launchTargetApp, grant prompts, UiDevice)
- `AppMigrationTest` must stay in `app/test/` module (not `app/core/`) — `TestRunner` classloader can't find classes in core module after app repackaging
- API 23-29 removed from CI matrix: API 23 < minSdk 24; API 24-29 have RootService compatibility issues

## Code Style

- Kotlin: follow existing Compose patterns in `app/apk/src/`
- Rust: `rustfmt.toml` enforces `imports_granularity = "Module"`, edition 2024
- C++: C++23, `-Oz` optimization, `-Wall`
- No `unwrap()` in Rust — clippy denies it
- Translation strings go in `app/core/src/main/res/values/strings.xml` and `app/stub-res/src/main/res/values/strings.xml`

## Windows Quirks

- ONDK cargo DLLs need to be on PATH at runtime (handled by `scripts/env.py`)
- Build uses `shell=True` for subprocess on Windows (PATHEXT support)
- Read-only file cleanup requires `chmod` before unlink

## Repository

- Branch: `master`
- CI: GitHub Actions on `macos-26` (release builds), `windows-2025` + `ubuntu-24.04` (test builds)
- License: GPLv3+
- Upstream: Magisk by topjohnwu
