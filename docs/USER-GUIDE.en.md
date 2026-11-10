# User guide

1. **Import literature.** Choose a folder, select/drop files, or use the fictional sample. Check record counts, date coverage and reference availability.
2. **Choose a view.** The central tabs directly select one of the nine established charts. Additional views are in the More views selector.
3. **Set parameters.** The left panel exposes dates, time slices, node selection, pruning and clustering. Author and institution views use their dedicated networks; additional views use the network selector.
4. **Run the selected view.** Each run generates only the current view. Run another view independently and switch back to an existing result without generating a bundle.
5. **Inspect the result.** The right panel shows relevant metrics, searchable nodes and a reading guide. Select a node for its details. The Data tab provides understandable column names, search and full TSV downloads.
6. **Export.** PNG is the current chart. PDF contains the figures from that run. SVG is the focus network overview, explicitly labeled in the selector. The logo switch selects a prepared variant without changing chart data.

Batch export is a separate action: select the views and formats you need. There is no mandatory four-view bundle. CLI `analyze` defaults to one network view; `report` retains the nine-chart default. Both accept `--charts` for an explicit selection.

Changed parameters mark existing charts as needing an update. Run the view to apply them. Analysis history restores saved parameters and results; Open result loads a saved report folder. Settings can be exported as JSON and reused in CLI/headless. Closing the settings dialog discards unapplied changes.

Results default to the local Ycscite data directory (`%LOCALAPPDATA%/Ycscite` on Windows). Web/desktop treat the chosen location as a parent directory and create a separate subdirectory for every run. CLI/headless output paths remain exact report directories: explicitly reusing one replaces matching files. Each manifest describes only its own run.

- Reduce dense graphs with lower g-index k, Top N, or MST/Pathfinder pruning. Larger labels do not reduce network complexity.
- Use label count, node size, label size and opacity controls for presentation. Exclusions accept commas, semicolons and newlines, with case-insensitive matching.
- Co-citation and coupling require references. Missing keywords can be addressed by choosing a title/abstract term network in an additional view.
- The annual cluster activity heatmap uses a shared linear scale of raw term counts; audit `cluster-activity.tsv`. Landscapes normalize each cluster to its own peak.
- t-SNE and approximate UMAP are exploratory graph layouts. Perplexity, iteration counts and seed are configurable; distances do not establish semantic similarity.
- Journal citation flows use actual parsable journals, with coverage reported. Keywords never stand in for missing cited journals. This is not a JCR disciplinary dual-map overlay.
- Switching language updates the interface. Regenerate figures for translated titles and axes; bibliographic content remains in its original language.
- The advanced editor restores the current editable project when available, or analyzes the inputs using the current settings. Save manual node edits, bookmarks and annotations as a project there; existing report figures are retained.
- Stopping a run terminates its analysis child. Partial files can remain but are not marked as a complete result.

## Workspace recovery and provenance

Language, settings, input paths, output location, active view, chart/data tab, selected result and logo choice are saved locally. The toolbar reports saving, saved or unsaved changes. Restarting restores the workspace without launching another analysis. On a save failure, existing results stay usable and settings can still be exported as JSON.

`workspace.json` is private application state containing local paths; use the separate settings JSON for CLI/headless. The default folder is `%LOCALAPPDATA%/Ycscite` on Windows, `$XDG_DATA_HOME/Ycscite` or `~/.local/share/Ycscite` on Linux, and `~/Library/Application Support/Ycscite` on macOS. `YCSCITE_DATA_HOME` overrides it. Corrupt state receives a recovery copy; report files are retained.

On import/reopen, the app checks parsed record identity. Editing a file in place marks old figures as historical until regenerated. Reports predating identity tracking are explicitly marked as unverified historical results. Their images remain available for viewing and export.

## Reusable editable results

Choose **Editable project** in the export selector to download the current view's network as `.csl-proj`. Reports keep separate files under `projects/`, with links on the offline report page. Projects include the analyzed nodes/links, metrics, clusters, labels, complete pipeline options and the included source records. They can be reopened without the original input directory. Manual annotations, bookmarks and renamed labels survive a save/reload and an editor language switch. Rerunning reconstructs the network, so save manual edits separately first.

Projects contain included records, rather than the full unfiltered corpus. Keep original exports when you need to change the year range or restore excluded records. `source_fingerprint` identifies the original parsed corpus; `analysis_fingerprint` identifies the included records.

New `records.tsv` exports use a version marker and JSON string arrays for multivalue fields, preserving embedded semicolons, control characters and spacing. The older 17-column format remains readable.

Label source K uses the selected labeling algorithm. Other document sources (T/A/WC/R/TKA) currently use document-level LLR; the algorithm and source selectors do not imply arbitrary combinations are all implemented.

The advanced editor’s Standard nine-chart report action is a separate file-import workflow using the standard preset. It follows the current interface language and creates a separate result subfolder. Use the main workbench for individually selected views and custom report settings.
