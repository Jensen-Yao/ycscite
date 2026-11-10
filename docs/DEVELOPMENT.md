# Development and releases

## Architecture

- `src/ycscite/analysis`: Java21 analysis algorithms.
- `src/ycscite/io`: parsers, report/chart renderers and exporters.
- `src/ycscite/Settings.java`: validated shared configuration.
- `src/ycscite/Headless.java`: NDJSON service, method dispatch and capability inventory.
- `src/ycscite/ui`: preserved editable interactive workbench.
- `native/src`: Rust CLI, runtime discovery, local service and Windows shell.
- `native/web`: embedded offline-first application UI, Chinese/English text and settings controls.
- `examples`: fictional, redistributable records.

CLI/Web/desktop report generation uses `NineFigureReport`, not three separate implementations. Typed JSON defaults and a per-run manifest make each report inspectable. The native client calls the engine as an owned process; cancellation and app shutdown terminate only its analysis child.

## Build

Windows: install JDK21+ and Rust/MSVC tools. Run `build.cmd`, `cargo test --manifest-path native/Cargo.toml --locked`, then `cargo build --release --manifest-path native/Cargo.toml --locked`. Windows packaging uses `tools/package-windows.ps1` with an optional `-Jdk` argument. `-SkipBuild` is intended only after tests and release compilation already passed.

Linux/macOS: `bash build.sh`; then Cargo CLI/Web build. A system Chinese-capable font is recommended. `JAVA_HOME` or Java on PATH must point to21+. `YCSCITE_HOME` can locate engine classes/JAR outside the executable directory. Development binaries inside this checkout's `native/target` can locate the checkout automatically; portable binaries require their own engine/runtime.

Packaging compiles a fresh engine class directory so stale experiment/test classes cannot enter the JAR. The ZIP includes the client, separate desktop EXE, engine JAR, jlink runtime (including character sets), user logo assets, public docs/examples and dependency notices. It excludes local research data, screenshots used as private references, original software binaries, decompiler material, and external journal basemaps.

## Tests

`build.cmd` / `build.sh` runs `ycscite.TestAll`, including `ProductTests`. The latter covers mixed formats, settings validation, field inspection, additional views, strict JSON/NDJSON behavior, exact integer IDs, thematic metric definitions, parameter changes, branding variants and optional exports. Rust tests cover local server file boundaries and parameter parsing. `tools/smoke-workspace.py --cli <absolute client path>` checks real local-server persistence across ports, validation, immutable run folders, explicit-report startup and corruption recovery. `tools/smoke-product.py --cli <absolute client path>` exercises the public CLI/headless contract using fictional examples.

Browser QA should verify real control flows, console health, desktop and narrow layouts: import or demo, setting validation, chart/network choice, run/cancel, chart/data tabs, logo toggles and actual downloads, language switch, settings JSON round trip and report history. Merely taking a screenshot or finding nine files is insufficient.

## Release

Run tests, build release, package, then use the packaged client from a separate working directory. Confirm `doctor` says `bundled_runtime:true`. Test `report`, `inspect`, `headless`, Web and Windows desktop startup. Publish source and release ZIP/checksum only after those checks. Keep dependencies locked in Cargo.lock. The GitHub workflow provides source-build regression checks across Windows/Linux.

`native/src/workspace.rs` stores validated application state independently of WebView/browser origins. Settings are written before a successful remembered-settings response; state files are staged and replaced, and failed writes leave the previous in-memory state intact. Loading state never launches a report. Web/desktop result directories are immutable per run even when a user chooses a common parent folder. The CLI/headless exact-output contract is unchanged.

`tools/smoke-editor.py --cli <client> [--evidence-dir <folder>]` starts real Swing windows with nondefault shared settings and saved projects, waits for actual layout completion, compares node/edge/metric/option state and captures the UI. It requires a desktop-capable Java environment and is intentionally separate from the no-GUI tests. `ProjectFidelityTests` covers all pipeline option fields, source record/TSV round trips, manual edits, burst rows, deduplication year bounds and weighted-PageRank invariants.

The Windows editor smoke explicitly enables Java Access Bridge in its own JVM process. Portable packaging verifies required runtime modules and includes `jdk.accessibility`; when upgrading an incomplete generated runtime it builds the replacement first and retains the previous runtime in its owned staging folder.
