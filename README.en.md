<p align="center"><img src="native/assets/logo-256.png" width="110" alt="Ycscite"></p>

# Ycscite · Bibliometric workbench

[中文](README.md) · [User guide](docs/USER-GUIDE.en.md) · [CLI / headless](docs/HEADLESS.md) · [Methods and capabilities](docs/CAPABILITIES.md)

Import literature locally, choose the individual view you need, and export charts with auditable data. The Windows desktop app, browser workbench, Rust CLI, and GUI-free NDJSON service share one configurable analysis engine.

The nine-chart preset covers publications, keyword co-occurrence, clusters, time zones, timelines, landscapes, bursts, authors, and institutions. Nine additional views are independently selectable: network overview, circular layout, heatmap, t-SNE, UMAP, circle packing, thematic map, contours, and observed journal citation flows.

Features include import diagnostics, 13 named input formats, 10 network types, 3 clustering algorithms, 6 labelers, reusable JSON settings, optional analytical tables, bilingual controls/captions, and downloadable PNG/SVG/PDF variants with or without the user-provided brand mark. Original bibliographic labels remain in their source language.

![Ycscite workbench](docs/images/workbench-en.png)

## Get started

Download `Ycscite-0.3.5-windows-x64.zip` from Releases and extract the **entire folder**. Launch `Ycscite.exe` or use the browser launcher. Run analysis generates the selected view; Batch export lets you choose any combination. A compact Java 21 runtime is included; the desktop shell uses system Edge WebView2. CLI/headless need neither a browser nor WebView2.

Builds from the main branch also provide Generate default nine charts in the command bar. It uses the current data and analysis settings to produce the complete nine-chart set while preserving individual and custom batch actions.

```powershell
.\ycscite-cli.exe --json doctor
.\ycscite-cli.exe inspect "D:\literature" --json
.\ycscite-cli.exe report "D:\literature" --out "D:\report" --json
.\ycscite-cli.exe analyze "D:\papers.bib" --network author --charts network,circular --lang en --out "D:\authors"
.\ycscite-cli.exe config --out settings.json
.\ycscite-cli.exe headless --request examples/request.json
.\ycscite-cli.exe batch examples/batch.json
```

In the app, **Use sample** loads 72 fictional records. Choose a view, adjust the left-hand parameters, run it, and inspect the chart or data. **Batch export** explicitly selects multiple views. The **Logo** switch selects an existing variant without erasing chart pixels. **Advanced editor** preserves the separate node-editing interface.

## Build

JDK21+ and stable Rust are required. Windows desktop builds also need the MSVC build tools.

```powershell
.\build.cmd
cargo test --manifest-path native/Cargo.toml --locked
cargo build --release --manifest-path native/Cargo.toml --locked
native\target\release\ycscite.exe serve
powershell -ExecutionPolicy Bypass -File tools/package-windows.ps1
```

Use `bash build.sh` for the Java engine on Linux/macOS. CLI/Web can be built with Cargo on those systems; the distributed desktop target is Windows x64. Rust provides the client and local service; Java21 provides analysis and rendering. The algorithms have not all been rewritten in Rust.

## Scope and methods

Charts and tables are computed from the imported records and selected settings. Reports retain analysis parameters, source-data identities and auditable tables. Simplified and experimental analyses are identified in the capability inventory and method notes. Community detection is not probabilistic topic modeling. Users import their own literature data and can configure an optional journal basemap; see `resources/jcr/README.md`.

Data and default analysis remain local. Advanced online search and explicitly configured remote-model tools contact their corresponding services only when invoked. See the [method inventory](docs/CAPABILITIES.md), [protocol](docs/HEADLESS.md), and [development guide](docs/DEVELOPMENT.md).

The local workspace remembers language, analysis settings, input paths, the active view and result across restarts and port changes. Web/desktop runs use separate result subdirectories. Parsed-record SHA-256 identities in `inspect` and manifests detect changed source data; existing images remain available as explicitly marked historical results.

Editable `.csl-proj` files retain the included corpus, full analysis options, exact network and manual annotations. Download the current network as an Editable project or open it in the advanced editor; changed settings are passed to the editor rather than replaced with its older defaults. Versioned record TSV preserves multivalue content and control characters.

Static PNG/SVG/PDF branding is consistent in the app, advanced editor and legacy CLI. Both variants retain the chart body; transparent PNG retains its alpha channel, and PDF uses lossless, aspect-preserving image pages with readable CJK summary labels.

Saved editor projects now retain per-view manual coordinates, styles, filters and camera, with graph-bound validation. Use `project inspect` or `project export --out figures --formats png,pdf` to restore visual results without a GUI or analysis recomputation. The default is one PNG.
