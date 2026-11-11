# 能力与方法 / Capability and method inventory

This document describes implemented behavior, not equivalence claims about another program. Use the `capabilities` command for the exact machine-readable catalog and defaults of your installed version.

## Inputs

| Key | Input |
|---|---|
| `wos` | Web of Science tagged text |
| `cssci` | CNKI/CSSCI RefWorks tagged text |
| `cnki` | CNKI EndNote percent-tag text |
| `scopus` | Scopus CSV |
| `pubmed` | PubMed MEDLINE text |
| `pubmed-xml` | PubMed XML |
| `ris` | RIS / ProQuest tagged records |
| `bibtex` | BibTeX entries |
| `dimensions` | Dimensions CSV |
| `derwent` | Derwent patent TSV |
| `kci` | KCI tagged exports (shared EndNote/RefWorks parsers) and compatible table input |
| `nsf` | NSF award XML; AwardTitle/AwardEffectiveDate aliases supported |
| `records-tsv` | Ycscite record-level TSV, preserving bibliography fields |

`auto` recognizes common signatures. Explicit format selection remains available for ambiguous exports. Directory mode can mix recognized files; unknown readme files are skipped. Text reading supports UTF-8/UTF-16 BOM and legacy GB18030, with Windows-1252 fallback. External XML entities and DOCTYPE are refused in NSF imports. Binary CAJ/PDF, Excel workbooks, and database-specific HTML exports are not bibliographic input formats in this version.

## Network / clustering / labeling

Ten network kinds: keyword/term co-occurrence, co-authorship, institution collaboration, document co-citation, bibliographic coupling, author co-citation, journal co-citation, category co-occurrence, country co-occurrence. Presence of a feature does not supply missing source fields.

Selection supports g-index, Top-N, Top-N%; yearly or wider slices; exclusions and link thresholds; isolated-node and component choices. Pruning supports none, MST and Pathfinder. Similarity supports raw, cosine, Dice, Jaccard, inclusion and Equivalence. In this engine similarity denominators use incident raw edge-strength sums; Equivalence is `w_ij² / (s_i s_j)`, scaled and rounded to integer thousandths. It is an optional choice; default settings remain raw counts.

Clustering: modularity optimization, spectral, EM. Labels: LLR, LSI, ISI keywords, TF-IDF, mutual information, k-core. Cluster membership and labels vary with selection, pruning, algorithm and source fields. The six labeling algorithms apply to keyword/node source K; document sources T/A/WC/R/TKA currently use document-level LLR. Generic graph metrics include betweenness, PageRank, degree/eigenvector centrality, sigma and detected bursts. Audit node/edge/year tables accompany results. Weighted PageRank normalizes each positive edge by the incident weight sum, treats undirected edges in both directions and redistributes dangling mass uniformly. Its values are finite, nonnegative and sum to one; a global rescaling of all weights leaves the transition probabilities unchanged.

## Chart keys

| Preset | Keys |
|---|---|
| Nine established charts | `publications, keywords, clusters, timezone, timeline, landscape, bursts, authors, institutions` |
| Optional views | `network, circular, heatmap, tsne, umap, circle_pack, thematic, contour, dual_map` |

The default nine retain their current renderer and their keyword/author/institution data meanings. Additional chart generation does not replace those views. The network selector controls the focus of optional views.

- Landscapes show annual occurrences within the displayed keyword clusters. Each row is normalized to its own peak; absolute comparisons require `landscape.tsv`.
- The annual cluster activity heatmap shows raw term counts by cluster and year on one shared linear color scale, with `cluster-activity.tsv` containing all clusters. It is not a co-occurrence matrix.
- Circular views group nodes by cluster, preserve observed links, and place main labels outside the ring. Node size reflects frequency; curvature does not encode a metric.
- t-SNE uses weighted graph shortest-path distances (edge length `1/sqrt(weight)`; disconnected pairs use twice the maximum finite distance), entropy-matched conditional affinities and the Student-t objective. Coordinates are normalized once after optimization. Perplexity, iteration count and seed are configurable. This is graph exploration, not text embedding. The current UMAP view is explicitly an approximate graph layout. Both preserve aspect ratio in report rendering.
- Circle packing uses non-overlapping circles within cluster circles, showing the largest 12 clusters. Inner-circle area encodes frequency; outer-circle area includes packing gaps and must not be read as aggregate frequency.
- Contours smooth annual term counts over time and cluster order. Cluster order is categorical, not a continuous semantic axis; use the unsmoothed activity table for numerical comparisons.
- The thematic map explicitly plots **external incident link-strength sum** against **100 × within-cluster link strength / cluster size**, bubble size proportional to cluster size. The dividing lines use mean coordinates. Underlying values are exported in `thematic-metrics.tsv`; do not compare them blindly to software using differently normalized association matrices. The implementation follows the centrality/density distinction used in strategic thematic maps; it no longer substitutes cluster age for centrality.
- The report `dual_map` key now renders observed **journal citation flows**, with full counts in `journal-flows.tsv`. It requires a citing journal plus a reference with a parsable year and journal; missing journals are never inferred from keywords. The image reports parsed-reference coverage and the displayed top-12 subset. This is not a disciplinary JCR dual-map overlay. The separate legacy editor can use a user-supplied licensed basemap through `YCSCITE_JCR_HOME`.
- SVG exports the focus network as editable vector circles/edges/text. It is a network export rather than a vector reconstruction of every raster chart.
- PDF is a chart atlas with one aspect-preserving, lossless raster page per chosen figure. DOCX is a summary of records/network/node metrics. It does not embed all figures. The advanced editor/legacy visual PDF retains its chart plus a CJK-capable raster metric-summary page.

## Additional modules

| Key | Actual result / interpretation |
|---|---|
| `citation` | Node/citation metric table |
| `cluster_summary` | Cluster size, silhouette, mean year and label candidates |
| `year_labels` | Year-by-year cluster labels |
| `cluster_links` | Undirected links between clusters; no inferred causal direction |
| `network_growth` | Yearly cumulative nodes, new nodes and new links; **not the full CiteSpace SVA metric family** |
| `reference_diversity` | Simplified distribution-based reference diversity; **not citation-context NLP uncertainty** |
| `citation_path` | Experimental within-dataset citation path/SPC analysis; matching and direction must be checked |
| `sentences` | Extractive sentences associated with cluster terms |
| `terms_tfidf` | Ranked TF-IDF terms |
| `terms_clumping` | Ranked temporal clumping terms |
| `concept_tree` | Heuristic term relation tree |
| `narrative` | Bilingual template summary of computed metrics; no inferred scientific conclusion |

The advanced CLI additionally exposes existing geospatial KML, node-list/interoperability exports, project persistence, SVO, structural summaries, optional remote labeling and data search. These are discoverable through `engine help` and the source inventory. Calls to external search/model services are separate from ordinary local analysis.

## Verification and limits

Regression checks cover parsers, graph metrics on known small graphs, deterministic community detection, file round trips, parameter effects, headless framing/error recovery, paired-logo pixel invariants, export pages and real local UI flows. This is evidence of those tested behaviors, not proof of universal bibliometric validity.

Default sample comparisons continue to differ from the supplied reference in some nodes/edges, cluster composition and burst intervals. No claim of pixel-identical CiteSpace replication is made. LDA, BERTopic, calibrated forecasting, KBCA fusion, and full structural-variation reproduction remain potential independent extensions; they are not advertised as implemented modules.

Reference concepts: Brandes betweenness; Kleinberg bursts; modularity community detection; Dunning likelihood-ratio labels; strategic thematic-map centrality/density. Optional reference-project ideas and deliberate limits are in `EXTENSIONS.md`.

Editable projects preserve the included bibliographic records, full pipeline options (including selection, similarity, exclusions and burst controls), all stored network metrics, clustering, labels, burst rows, parameter metadata, manual annotations, bookmarks and renamed labels. Opening a matching report in the advanced editor restores the analyzed network; it does not silently switch to the editor's Top-N defaults. The legacy file identifier remains readable with additive fields for backward compatibility.

Visual branding is consistent across report downloads, the advanced editor and legacy static PNG/SVG/PDF exports. PNG source RGBA is unchanged; SVG footer updates are scoped to the root viewport rather than descendant dimensions; PDF source image pixels are encoded with Flate rather than JPEG. Saved projects can retain an optional export-logo preference, with explicit CLI or main-workbench overrides. Export capture preserves the live editor's viewport. These presentation choices do not change the network's quantitative analysis.
