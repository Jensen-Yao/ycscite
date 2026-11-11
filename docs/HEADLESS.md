# CLI and headless / 命令行与无界面自动化

The packaged command is `ycscite-cli.exe`; a development build is `native/target/release/ycscite.exe`. Use an absolute path from another working directory. Analysis commands always set the JVM to `java.awt.headless=true`. Only `desktop`, `serve`, and `workbench` start user interfaces.

## Commands

| Command | Purpose |
|---|---|
| `doctor [--json]` | Runtime/engine location and availability |
| `capabilities` | Actual formats, networks, charts, modules and defaults |
| `inspect <inputs...> [--format ...] --json` | Import records and report field/year coverage |
| `config [--out settings.json]` | Print or save reusable defaults |
| `report <inputs...> [options]` | Nine charts by default, or selected charts |
| `analyze <inputs...> [options]` | One network view by default; choose any view with `--charts` |
| `headless [--request request.json]` | NDJSON stream or one request file |
| `batch batch.json` | Array of requests, preserving each job's outcome |
| `engine <legacy command...> [--json]` | Complete legacy CLI, with captured JSON output if selected |
| `serve [--port N] [--report DIR] [--no-open]` | Local browser workbench |
| `desktop` | Windows app window |
| `workbench [inputs...] [--config JSON] [--lang zh/en]` | Interactive node-editing with shared settings; `--project FILE` restores an editable result |

`report` / `analyze`: `--out`, `--config`, `--lang zh|en`, `--logo` / `--no-logo`, `--charts comma,separated,keys`, `--set key=JSON`, `--network`, `--format`, `--from`, `--to`, `--slice-years`, `--g-index-k`, `--pruning`, `--clustering`, `--labeling`.

CLI options override a config file. Save `settings.json` beside a generated report to reproduce its configuration. Input/output paths in normal CLI and streaming requests are relative to the process working directory. For `--request` and `batch` files, paths are relative to the request file's directory.

## NDJSON protocol v2

Each input line is an independent JSON request. Each output line is one JSON response. No progress text is mixed into stdout. A malformed line produces an error response and processing continues. Request IDs preserve integers beyond JavaScript's 53-bit range; JavaScript clients should use string IDs for interoperability.

```json
{"id":"caps","method":"capabilities"}
{"id":"check","method":"inspect","params":{"inputs":["D:/papers"],"format":"auto"}}
{"id":"config","method":"validate_settings","params":{"settings":{"language":"en","charts":["network"],"g_index_k":15}}}
{"id":"job","method":"analyze","params":{"inputs":["D:/papers"],"output":"D:/results","settings":{"network":"author","charts":["network","circular"],"language":"en","logo":false}}}
```

Responses:

```json
{"id":"check","ok":true,"result":{"records":100,"dated_records":97,"undated_records":3}}
{"id":"bad","ok":false,"error":{"code":"execution_failed","message":"Unknown setting: invalid_key"}}
```

Supported methods: `capabilities`, `inspect`, `validate_settings`, `report`, `analyze`, `batch`, `cli`. `batch.params.jobs` contains request objects, not raw parameter objects. Batch result has `jobs`, `succeeded`, and `failed`. Failure in one job does not prevent later jobs from running. Nested batch operation is supported for ordinary local use; do not feed untrusted job files.

```json
{"id":1,"method":"cli","params":{"arguments":["analyze","D:/papers.txt","--net","keyword","--topn","100","--sva","--cluster-summary","--out","D:/advanced"]}}
```

Legacy output is captured as `result.stdout`, `result.stderr`, and `result.exit_code`; a nonzero legacy exit produces `ok:false`.

Exit codes: successful CLI/request `0`; runtime/request failure `1`; CLI syntax error `2`. A streaming service stays alive after individual errors and exits `0` on clean EOF; inspect each response's `ok`. The `batch` CLI exits `1` if any job failed. Generation progresses on stderr; service mode can be stopped by ending stdin or terminating its owned process.

## Settings

Query the complete, version-specific defaults with `capabilities`. Important fields:

- Input: `format`, `from_year`, `to_year`, `slice_years`, `deduplicate`.
- Network: `network`, `selection` (`gindex`, `topn`, `topnpct`), `g_index_k`, `top_n`, `per_slice`, `top_percent`, `keep_isolated`, `exclude_words`.
- Structure: `pruning`, `similarity`, `min_link`, `max_links`, `clustering`, `labeling`, `label_source`, `min_cluster`, `largest_components`.
- Text/bursts: `noun_phrase_min`, `noun_phrase_max`, `singular_keywords`, `burst_transition`, `burst_min_years`, `burst_top`.
- Exploratory layout: `tsne_perplexity` (1–200; clamped below node count), `tsne_iterations` (100–3000), `umap_iterations` (100–3000), `embedding_seed` (nonnegative integer).
- Output: `charts`, `analysis_modules`, `exports`, `language`, `logo`, `image_scale`, `node_scale`, `label_scale`, `label_budget`, `link_opacity`.

For g-index selection, `per_slice>0` enables yearly/sliced selection; its numeric value is not a second Top-N limit. The number of retained nodes is controlled by integer `g_index_k>=1`. For Top-N selection, `per_slice` is the per-slice count. `per_slice=0` selects globally. `from_year=0` / `to_year=0` use the data range. Basic PNG, HTML and audit tables are always produced; `exports` adds other artifacts.

Nine named preset charts keep their respective keyword/author/institution network types. `network` selects the focus network for the extra/custom charts and analytical tables. To analyze a single author or co-citation network, choose a single `charts:["network"]`, `charts:["authors"]`, or any explicit list.

## Local API

The Web/EXE server binds only `127.0.0.1`. Its internal API validates Host, Origin and a per-process session token. Prefer the supported CLI/headless API for automation instead of extracting that token. Files are served only from registered report directories. Generated report HTML remains usable offline.

## Data identity

`inspect.result.source_fingerprint` and `manifest.source_fingerprint` identify the same original parsed records, before time filtering or deduplication. `fingerprint_schema` is `ycscite-records-v1`: SHA-256 of the NUL-terminated schema prefix plus a big-endian 32-bit record count, followed by big-endian 32-bit byte lengths and UTF-8 JSON arrays of PT/AU/AF/TI/SO/DE/ID/AB/C1/CR/PY/VL/BP/DI/UT/TC/WC, in record order. Project names and file paths are excluded. This identifies interpreted input data, rather than raw file bytes or scientific validity. Preserve the original inputs and `settings.json` for reproduction. Manifests also record `engine_version`; capabilities, doctor and engine versions must agree.

Application workspace storage is separate from this public headless protocol. Starting CLI/headless analysis does not open a browser, change UI workspace preferences, or require the desktop runtime.

## Editable analysis projects

Reports include `project_files` entries pointing to `projects/*.csl-proj`. Each file contains the included corpus, the exact analyzed network, metric values, clustering, labels and the complete pipeline options. Choose the entry whose `kind` matches the required keyword/author/institution/focus network. The app's **Editable project** download selects the current view's corresponding network.

```powershell
.\ycscite-cli.exe workbench --project "D:\result\projects\authors.csl-proj" --lang en
.\ycscite-cli.exe workbench "D:\papers.ris" --config "D:\settings.json"
.\ycscite-cli.exe engine open "D:\result\projects\authors.csl-proj" --tsv --out "D:\tables"
```

A project restores its stored network without rerunning selection. `--project` cannot be combined with inputs or `--config`. The input/config form runs the same format, year filtering, deduplication and pipeline settings as a report. GUI operations are intentionally separate from the headless protocol; use `engine open`/the legacy CLI method for non-interactive project exports.

`analysis_fingerprint` identifies the included, filtered/deduplicated records in a report or editable project. It can differ from `source_fingerprint`, which identifies the full original parsed corpus. The exported `records.tsv` corresponds to `analysis_fingerprint`.

New record-level TSV files carry an eighteenth `YCSCITE_TSV_V2` header column and a row version value of `2`. Multivalue fields are JSON string arrays, preserving embedded semicolons, tabs, newlines, empty strings and spacing. Scalar fields escape backslash, tab, CR and LF. The reader still supports the original 17-column semicolon-separated format. These are Ycscite record files, not generic Scopus CSV or WoS tagged exports.

## Consistent visual branding

`report` and `analyze` accept mutually exclusive `--logo` / `--no-logo` overrides, including when a settings file has the opposite default. The legacy `engine analyze`, `engine open`, `engine visualize`, directory-batch and search image paths also apply the logo choice to their generated static figures. `workbench --logo` / `workbench --no-logo` overrides an editor config/project preference. Logo flags do not alter the analyzed data.

```powershell
.\ycscite-cli.exe report papers.ris --config settings.json --logo --out result
.\ycscite-cli.exe --json engine analyze papers.ris --svg --pdf --no-logo --lang en --out figures
.\ycscite-cli.exe --json engine open result/projects/keywords.csl-proj --svg --pdf --no-logo --out figures
.\ycscite-cli.exe workbench --project result/projects/keywords.csl-proj --no-logo
```

Static PNG/SVG/PDF outputs keep both choices under sibling `with-logo/` and `no-logo/` folders; the named primary file follows the selected preference. PNG variants preserve source RGBA (including transparency), SVG adds only a footer/root-viewport extension, and PDF embeds lossless RGB image streams with aspect-preserving pages. Transparent raster data is composited on white for PDF. Both choices reserve the same footer dimensions so switching never crops chart data. Existing unrelated files in a reused output folder are left alone.

The advanced editor's visual PDF contains the current chart followed by a rasterized, CJK-capable metric summary. SVG is a node/edge overview, not a vector recreation of every specialized chart mode. Empty-data exports create an explanatory image rather than reusing an old file. Video, bibliographic data tables and document summaries are separate output types; these flags cover static PNG/SVG/PDF figures.
