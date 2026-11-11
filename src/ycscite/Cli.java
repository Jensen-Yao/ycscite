package ycscite;

import ycscite.analysis.CitationReport;
import ycscite.analysis.Labelers;
import ycscite.analysis.NetworkBuilders;
import ycscite.analysis.Pipeline;
import ycscite.analysis.Pruning;
import ycscite.io.*;
import ycscite.model.Project;
import ycscite.model.Record;
import ycscite.ui.GraphCanvas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ycscite 命令行界面 — 与 GUI 同一引擎的完整 CLI：
 *
 *   ycscite analyze <输入文件...> [选项]     分析并导出（核心命令）
 *       --format wos|scopus|pubmed|cnki|cssci|derwent|auto   （默认 auto=按扩展名/内容）
 *       --net keyword|term|author|cocitation|coupling|institution （默认 keyword）
 *       --topn N            Top N 节点（0=全部，默认 50）
 *       --cluster spectral|modularity   聚类算法（默认 modularity，原版即此）
 *       --label llr|lsi|isikw            聚类标注（默认 llr）
 *       --out 目录          输出目录（默认 ./ycscite-out）
 *       --png --html --tsv --pdf --docx --sql --citation-report  选择导出物（默认 tsv+png+html）
 *       --save 文件.csl-proj  同时保存项目
 *   ycscite search "检索词" [--rows N] [--net ...] [--out 目录]  CrossRef 在线检索并分析
 *   ycscite video <输入文件...> [--out evo.avi] [--net ...]     逐年演化 AVI 视频
 *   ycscite open 项目.csl-proj [--png]                          载入项目并重新导出（不重算）
 *   ycscite doctor                                              环境与版本自检
 */
public final class Cli {
    public static final String VERSION = "0.3.5";

    public static int run(String[] args) {
        String language=new Parse(args).opt("lang",I18n.isEnglish()?"en":"zh");
        if(!java.util.Set.of("zh","en").contains(language)){System.err.println("Language must be zh or en");return 2;}
        var previous=ycscite.ui.Strings.getLang();String previousLayout=layoutOpt;
        try(var scope=I18n.language(language)){ycscite.ui.Strings.setLang(language.equals("en")?ycscite.ui.Strings.Lang.EN:ycscite.ui.Strings.Lang.ZH);return dispatch(args);}
        finally{ycscite.ui.Strings.setLang(previous);layoutOpt=previousLayout;}
    }
    private static int dispatch(String[] args) {
        try {
            if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
                printHelp();
                return args.length == 0 ? 2 : 0;
            }
            return switch (args[0]) {
                case "analyze" -> cmdAnalyze(args);
                case "report" -> cmdReport(args);
                case "headless" -> Headless.stdio();
                case "capabilities" -> { System.out.println(MiniJson.write(Headless.capabilities())); yield 0; }
                case "search" -> cmdSearch(args);
                case "video" -> cmdVideo(args);
                case "open" -> cmdOpen(args);
                case "visualize" -> cmdVisualize(args);
                case "convert" -> cmdConvert(args);
                case "batch" -> cmdBatch(args);
                case "openalex" -> cmdOpenAlex(args);
                case "sql" -> cmdSql(args);
                case "doctor" -> cmdDoctor();
                case "version" -> { System.out.println("Ycscite "+VERSION); yield 0; }
                default -> {
                    System.err.println("未知命令: " + args[0] + "（help 查看用法）");
                    yield 2;
                }
            };
        } catch (CliError e) {
            System.err.println("错误: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            System.err.println("失败: " + e);
            return 1;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("gui") || args[0].equals("--gui"))) {
            ycscite.Main.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        System.exit(run(args));
    }

    static final class CliError extends RuntimeException {
        CliError(String m) { super(m); }
    }

    // ---------------- 子命令 ----------------

    static int cmdReport(String[] args) throws Exception {
        if (args.length == 2 && args[1].equals("--help")) {
            System.out.println("ycscite report <files or directories...> [--out DIR] [--config settings.json] [--lang zh|en] [--logo|--no-logo] [--charts key,key] [--set key=JSON]");
            return 0;
        }
        List<String> inputs = new ArrayList<>();
        Path out = Path.of("ycscite-report");
        var overrides = new java.util.LinkedHashMap<String,Object>();
        for (int i=1;i<args.length;i++) if(args[i].equals("--config")) {
            if (++i==args.length) throw new CliError("--config requires a file");
            overrides.putAll(MiniJson.obj(MiniJson.parse(Files.readString(Path.of(args[i])))));
        }
        for (int i=1;i<args.length;i++) {
            String arg=args[i];
            if(arg.equals("--config")){i++;continue;}
            if(arg.equals("--logo")){overrides.put("logo",true);continue;}
            if(arg.equals("--no-logo")){overrides.put("logo",false);continue;}
            if(arg.startsWith("--")){
                if(++i==args.length)throw new CliError(arg+" requires a value");
                String value=args[i];
                switch(arg){
                    case "--out" -> out=Path.of(value);
                    case "--lang" -> overrides.put("language",value);
                    case "--format" -> overrides.put("format",value);
                    case "--charts" -> overrides.put("charts",List.of(value.split(",")));
                    case "--set" -> {int split=value.indexOf('=');if(split<=0)throw new CliError("--set requires key=value");
                        String raw=value.substring(split+1);Object parsed;try{parsed=MiniJson.parse(raw);}catch(Exception ex){parsed=raw;}
                        overrides.put(value.substring(0,split),parsed);}
                    default -> throw new CliError("Unknown report option: "+arg);
                }
            }else inputs.add(arg);
        }
        Settings settings=new Settings(overrides);
        Project p=loadInputs(inputs,settings.text("format"));
        NineFigureReport.write(p,out,System.out::println,settings);
        System.out.println((settings.english()?"Report saved: ":"报告已生成：")+out.toAbsolutePath().normalize());
        return 0;
    }

    public static Project loadReportInputs(List<String> inputs) throws Exception {
        return loadInputs(inputs, "auto");
    }

    public static List<String> expandInputs(List<String> inputs, String format) throws Exception {
        if (inputs.isEmpty()) throw new CliError("No input directory or file was provided");
        var files = new java.util.LinkedHashSet<String>();
        for (String input : inputs) {
            Path path = Path.of(input).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                try (var children = Files.list(path)) {
                    for (Path child : children.filter(Files::isRegularFile).sorted().toList()) {
                        String name = child.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        if (!name.matches(".*\\.(txt|ris|bib|csv|tsv|xml)")) continue;
                        if (!format.equals("auto") || !detectFormat(child).equals("unknown")) files.add(child.toString());
                    }
                }
            } else files.add(path.toString());
        }
        if (files.isEmpty()) throw new CliError("No recognized bibliographic files in the input directory");
        return List.copyOf(files);
    }

    static int cmdAnalyze(String[] args) throws Exception {
        List<String> inputs = new ArrayList<>();
        Parse p = new Parse(args);
        while (p.more()) {
            String a = p.peek();
            if (a.startsWith("--")) break;
            inputs.add(p.next());
        }
        String fmt = p.opt("format", "auto");
        String net = p.opt("net", "keyword");
        int topN = Integer.parseInt(p.opt("topn", "50"));
        String cluster = p.opt("cluster", "modularity");
        String label = p.opt("label", "llr");
        Path out = Path.of(p.opt("out", "ycscite-out"));
        boolean png = p.flag("png"), html = p.flag("html"), tsv = p.flag("tsv"),
                pdf = p.flag("pdf"), docx = p.flag("docx"), sql = p.flag("sql"), cit = p.flag("citation-report"),
                dedup = p.flag("dedup"), summary = p.flag("cluster-summary"),
                trend = p.flag("trend"), evolution = p.flag("evolution"), burstTable = p.flag("burst-table");
        String save = p.optOrNull("save");
        if (inputs.isEmpty()) throw new CliError("analyze 需要至少一个输入文件");

        Project project = loadInputs(inputs, fmt);
        if (dedup) {
            int removed = project.dedup();
            System.out.println("去重: 移除 " + removed + " 条重复记录");
        }
        Pipeline.Options o = options(net, topN, cluster, label);
        o.fromYear = Integer.parseInt(p.opt("from", "0"));
        o.toYear = Integer.parseInt(p.opt("to", "2999"));
        o.sliceYears = Integer.parseInt(p.opt("slice", "1"));
        o.topNPerSlice = Integer.parseInt(p.opt("per-slice", "0"));
        o.pruning = pruningKey(p.opt("pruning", "none"));
        o.minClusterSize = Integer.parseInt(p.opt("min-cluster", "1"));
        o.selection = p.opt("selection", "topn");
        o.topPercent = Double.parseDouble(p.opt("topn-pct", "50"));
        o.similarity = p.opt("similarity", "raw");
        o.gindexK = Double.parseDouble(p.opt("gindex-k", "1.0"));
        o.minLinkStrength = Integer.parseInt(p.opt("min-link", "1"));
        o.largestKComponents = Integer.parseInt(p.opt("largest-cc", "0"));
        o.singularKeywords = p.flag("singular");
        o.maxLinksPerNode = Integer.parseInt(p.opt("max-links", "0"));   // 每节点链接上限（图09校准：机构网 cap=8 → E=1254≈原版1255）
        o.nounPhraseMin = Integer.parseInt(p.opt("np-min", "0"));   // 候选词长下限（原版 NounPhraseWordsMin=2）
        o.nounPhraseMax = Integer.parseInt(p.opt("np-max", "0"));   // 候选词长上限（原版 NounPhraseWordsMax=4）
        o.strictSelection = p.flag("strict");   // 保留无链接孤点（原版语义：散点入图）
        // 标注词源（原版 T/K/A/WC/R/T+K+A；CNKI/CSSCI 题名词源自动降级为 K，行为对齐 vE.java 210-215）
        String ls = p.opt("label-source", "k").toLowerCase();
        o.labelSource = switch (ls) {
            case "t", "title" -> Labelers.SRC_T;
            case "a", "abstract" -> Labelers.SRC_A;
            case "wc", "category" -> Labelers.SRC_WC;
            case "r", "ref", "reference" -> Labelers.SRC_R;
            case "tka", "t+k+a" -> Labelers.SRC_TKA;
            default -> Labelers.SRC_K;
        };
        boolean cnkiLike = project.name != null && (project.name.toUpperCase().contains("CNKI")
                || project.name.toUpperCase().contains("CSSCI"));
        if (cnkiLike && (o.labelSource.equals(Labelers.SRC_T) || o.labelSource.equals(Labelers.SRC_A))) {
            System.out.println("提示: 该数据类型不支持题名词标注，已自动改用关键词（K）标注（原版行为）");
            o.labelSource = Labelers.SRC_K;
        }
        // 原版预设：每片 Top25（阈值+平局语义，CiteSpace.b(int) 逐式）+ Pathfinder 剪枝。
        // 片长默认 3 年（真实数据实测网格：3年片 N=415 E=911 Q=0.723 最接近原版图 N=362 E=721；
        // 1年片在忠实平局语义下 N=1964 偏离。可用 --slice 覆盖）。
        layoutOpt = p.optOrNull("layout");
        boolean originalPreset = "citespace".equalsIgnoreCase(p.opt("preset", "none"));
        // 7.0.1 精确口径预设（2026-11 与原版 JAR 逐片差分对齐：N=527/E=616 双精确）：
        // g-index k=25 每片 + 词长 2-4（英文按词数） + 孤点保留 + cosine 边权 + 无剪枝。
        // 输入须为原版转换的 download*_converted.txt（WoS 格式）。
        if ("citespace701".equalsIgnoreCase(p.opt("preset", "none"))) {
            if (topN == 50 && "keyword".equalsIgnoreCase(net)) o.topN = 0;
            if (!p.hasFlag("per-slice")) o.topNPerSlice = 25;
            o.selection = Pipeline.G_INDEX;
            o.gindexK = 25;
            o.nounPhraseMin = 2;
            o.nounPhraseMax = 4;
            o.strictSelection = true;
            o.fromYear = 1999;
            o.toYear = 2026;
            o.sliceYears = 1;
            o.similarity = "cosine";
            o.pruning = Pruning.NONE;
        }
        if (originalPreset) {
            if (topN == 50 && "keyword".equalsIgnoreCase(net)) o.topN = 0;
            if (!p.hasFlag("per-slice")) o.topNPerSlice = 25;
            o.strictSelection = true;
            o.fromYear = 1999;
            o.excludeWords = new java.util.HashSet<>(java.util.List.of(
                "中国", "发展", "南京", "登录", "西部大开发", "可读性",
                "历史文物", "城市绿环", "明城墙风光带", "合理利用", "有效保护"));
            o.maxLinksPerNode = 0; // 暂禁用（需按片应用，全局应用过度剪枝）                        // MaxLinksPerNode=10（用户 config）
            if (!p.hasFlag("slice")) o.sliceYears = 1;
            if (!p.hasFlag("pruning")) o.pruning = Pruning.PATHFINDER;
        }
        boolean originalStyle = originalPreset || "citespace701".equalsIgnoreCase(p.opt("preset", "none"))
                || "original".equalsIgnoreCase(p.opt("style", "dark"));
        boolean logo=exportLogo(p,true);
        Pipeline.Result r = Pipeline.run(project, o);
        if (originalPreset)
            System.out.println("预设[原版]: 每片 Top25（严格硬截·保留孤立点） · 1年片 · Pathfinder  → N=" + r.network.nodes.size()
                    + " E=" + r.network.edges.size());

        boolean any = !(png || html || tsv || pdf || docx || sql || cit || summary || trend || evolution || burstTable || p.flag("svg") || p.flag("tsne") || p.flag("thematic") || p.flag("contour") || p.flag("dual-map"));
        if (any) { tsv = true; png = true; html = true; }
        Files.createDirectories(out);
        emit(r, out, png, html, tsv, pdf, docx, sql, cit, project, originalStyle,exportLogo(p,true));
        if (png) {
            // 三视图一并输出：时区 / 合并（network.png 保持时间线视图，兼容既有输出）
            GraphCanvas tz = styledCanvas(r, project, GraphCanvas.Mode.TIMEZONE, originalStyle);
            tz.snapshot(out.resolve("network-timezone.png"), originalStyle ? 8000 : 4000, originalStyle ? 5000 : 2500);
            Branding.variants(out.resolve("network-timezone.png"),logo);
            GraphCanvas mg = styledCanvas(r, project, GraphCanvas.Mode.MERGED, originalStyle);
            mg.snapshot(out.resolve("network-merged.png"), originalStyle ? 8000 : 4000, originalStyle ? 5000 : 2500);
            Branding.variants(out.resolve("network-merged.png"),logo);
            GraphCanvas tl = styledCanvas(r, project, GraphCanvas.Mode.TIMELINE, originalStyle);
            tl.snapshot(out.resolve(originalStyle ? "network-timeline-original.png" : "network-tl.png"),
                    originalStyle ? 8000 : 4000, originalStyle ? 5000 : 2500);
            Branding.variants(out.resolve(originalStyle?"network-timeline-original.png":"network-tl.png"),logo);
            GraphCanvas ci = styledCanvas(r, project, GraphCanvas.Mode.CIRCULAR, originalStyle);
            ci.snapshot(out.resolve("network-circular.png"), 1600, 1000);
            Branding.variants(out.resolve("network-circular.png"),logo);
            GraphCanvas hm = styledCanvas(r, project, GraphCanvas.Mode.HEATMAP, originalStyle);
            hm.snapshot(out.resolve("network-heatmap.png"), 1600, 1000);
            Branding.variants(out.resolve("network-heatmap.png"),logo);
            GraphCanvas lc = styledCanvas(r, project, GraphCanvas.Mode.LANDSCAPE, originalStyle);
            lc.snapshot(out.resolve("network-landscape.png"), 1600, 1000);
            Branding.variants(out.resolve("network-landscape.png"),logo);
            System.out.println(originalStyle
                    ? "导出: 原版风格(白底/红标签45°) network-timeline-original.png + network-timezone.png + network-merged.png"
                    : "导出: network-timezone.png + network-merged.png");
            System.out.println("导出: network-circular.png + network-heatmap.png + network-landscape.png（环形/热力图/山景视图）");
        }
        if (trend) {
            Charts.publicationTrend(project, out.resolve("publication-trend.png"));
            Branding.variants(out.resolve("publication-trend.png"),logo);
            System.out.println("导出: publication-trend.png（年度发文量）");
        }
        if (evolution) {
            Charts.clusterEvolution(r, out.resolve("cluster-evolution.png"));
            Branding.variants(out.resolve("cluster-evolution.png"),logo);
            System.out.println("导出: cluster-evolution.png（聚类演化山峰图）");
        }
        if (burstTable) {
            Charts.burstTable(r, out.resolve("burst-top.png"), Integer.parseInt(p.opt("burst-top", "25")));
            Branding.variants(out.resolve("burst-top.png"),logo);
            Charts.burstTsv(r, out.resolve("burst.tsv"));
            System.out.println("导出: burst-top.png + burst.tsv（突现表）");
        }
        if (summary) {
            Exporters.writeClusterSummary(r, out.resolve("cluster-summary.tsv"));
            System.out.println("导出: cluster-summary.tsv");
        }
        if (p.flag("yby")) {
            Exporters.writeYearByYearLabels(r, out.resolve("year-by-year-labels.tsv"));
            System.out.println("导出: year-by-year-labels.tsv（逐年聚类标签）");
        }
        // 互操作格式（原版 Export>Network 子菜单）
        if (p.flag("pajek")) {
            Exporters.writePajek(r.network, out.resolve("network.net"));
            System.out.println("导出: network.net（Pajek，含时间区间）");
        }
        if (p.flag("gexf")) {
            Exporters.writeGexf(r.network, out.resolve("network.gexf"));
            System.out.println("导出: network.gexf（Gephi GEXF）");
        }
        if (p.flag("ucinet")) {
            Exporters.writeUcinet(r.network, out.resolve("network.dl"));
            System.out.println("导出: network.dl（UCINET DL）");
        }
        if (p.flag("adjmat")) {
            Exporters.writeAdjacencyMatrix(r.network, out.resolve("adjacency-matrix.csv"));
            System.out.println("导出: adjacency-matrix.csv（邻接矩阵，R/Orange 兼容）");
        }
        if (p.flag("svg")) {
            GraphCanvas svgCanvas = styledCanvas(r, project, GraphCanvas.Mode.MERGED, originalStyle);
            svgCanvas.writeSvg(out.resolve("network.svg"), 2400, 1500);
            Branding.svgVariants(out.resolve("network.svg"),logo,2400,1500);
            System.out.println("导出: network.svg（矢量图，对应原版 View As SVG）");
        }
        // 主路径分析（原版 "Add Main Path Layers"，Hummon-Doreian SPC；需 WoS 类含 CR 的数据）
        // 记录级 TSV 往返（原版 WoS↔TSV 转换器）
        if (p.flag("records-tsv")) {
            ycscite.io.RecordIo.writeTsv(project, out.resolve("records.tsv"));
            System.out.println("导出: records.tsv（记录级 TSV，可 --format tsv 回读）");
        }
        // 簇级句子摘要（原版 Select Cluster-Summarizing Sentences）
        if (p.flag("sentences")) {
            java.nio.file.Files.writeString(out.resolve("cluster-sentences.tsv"),
                    ycscite.analysis.SentenceSummarizer.summarize(r, project, 3), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("导出: cluster-sentences.tsv（簇级摘要句）");
        }
        // 概念树（原版 "Build Concept Tree"）与叙事生成（原版 "Generate a Narrative"）
        if (p.flag("concept-tree")) {
            java.nio.file.Files.writeString(out.resolve("concept-tree.txt"),
                    ycscite.analysis.TextSubsystem.conceptTree(r.network, 60, 3), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("导出: concept-tree.txt（术语概念树）");
        }
        if (p.flag("narrative")) {
            java.nio.file.Files.writeString(out.resolve("narrative.txt"),
                    ycscite.analysis.TextSubsystem.narrative(r, project), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("导出: narrative.txt（结构化综述叙事）");
        }
        // KML 地理导出（原版 "Generate Google Earth Maps (KML)" + Tableau CSV）
        if (p.flag("kml")) {
            ycscite.io.KmlExporter.export(project, out.resolve("map.kml"), out.resolve("map-tableau.csv"));
            System.out.println("导出: map.kml + map-tableau.csv（Google Earth 地理可视化）");
        }
        // Alias 合并（原版 "Add to the Alias List"，primary#secondary 格式）
        String aliasFile = p.optOrNull("alias");
        if (aliasFile != null) {
            int merged = ycscite.io.AliasFile.apply(r.network, java.nio.file.Path.of(aliasFile));
            System.out.println("Alias 合并: " + merged + " 对（secondary → primary）");
        }
        // 等高线图（原版 Contour Map 全套，ph.java:1158-1213）
        // RIS 导出（原版 "Save References as RIS"，ph.java:956）
        if (p.flag("ris")) {
            ycscite.io.RisExporter.write(project, out.resolve("references.ris"));
            System.out.println("导出: references.ris（EndNote/Zotero 兼容）");
        }
        // R / Orange 专用 TSV（审计[17][18]，ph.java:950-955）
        if (p.flag("nodes-r")) {
            java.util.Map<ycscite.model.GraphNode, double[]> xy = new java.util.HashMap<>();
            GraphCanvas rc = styledCanvas(r, project, GraphCanvas.Mode.MERGED, false);
            for (ycscite.model.GraphNode n : r.network.nodeCollection()) {
                var pt = rc.positionOf(n);
                if (pt != null) xy.put(n, new double[]{pt.x, pt.y});
            }
            ycscite.io.Exporters.writeNodesForR(r, out.resolve("nodes-R.tsv"), xy);
            System.out.println("导出: nodes-R.tsv（R 专用列序）");
        }
        if (p.flag("nodes-orange")) {
            java.util.Map<ycscite.model.GraphNode, double[]> xy = new java.util.HashMap<>();
            GraphCanvas oc = styledCanvas(r, project, GraphCanvas.Mode.MERGED, false);
            for (ycscite.model.GraphNode n : r.network.nodeCollection()) {
                var pt = oc.positionOf(n);
                if (pt != null) xy.put(n, new double[]{pt.x, pt.y});
            }
            ycscite.io.Exporters.writeNodesForOrange(r, out.resolve("nodes-Orange.tsv"), xy);
            System.out.println("导出: nodes-Orange.tsv（Orange 专用列序）");
        }
        // 保存 .list（审计[24]，ph.java:982）
        if (p.flag("save-list")) {
            ycscite.io.Exporters.writeNodeList(r.network, out.resolve("node-keys.list"));
            System.out.println("导出: node-keys.list（节点高亮列表，可 --highlight 加载）");
        }
        // tf*idf / Clumping 词表（原版 "List Ranked Terms by tf*idf/Clumping"，CiteSpace.java:817-820）
        if (p.flag("terms-tfidf")) {
            java.nio.file.Files.writeString(out.resolve("terms-tfidf.tsv"),
                    ycscite.analysis.TextSubsystem.rankedTermsTfIdf(project, Integer.parseInt(p.opt("terms-top", "100"))),
                    java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("导出: terms-tfidf.tsv（tf*idf 词表）");
        }
        if (p.flag("terms-clumping")) {
            java.nio.file.Files.writeString(out.resolve("terms-clumping.tsv"),
                    ycscite.analysis.TextSubsystem.rankedTermsClumping(project, Integer.parseInt(p.opt("terms-top", "100"))),
                    java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("导出: terms-clumping.tsv（Clumping 集中度词表）");
        }
        // SVO 提取（原版 SVO 工具链，CiteSpace.java:785-804）
        // Thematic Map（原版 CS:675，简化版：四象限主题地图）
        if (p.flag("thematic")) {
            ycscite.analysis.ThematicMap.render(r, out.resolve("thematic-map.png"));
            Branding.variants(out.resolve("thematic-map.png"),logo);
            System.out.println("导出: thematic-map.png（主题地图，四象限）");
        }
        // SVO 全文目录（原版 CS:826 Process Multiple Folders）
        String svoDir = p.optOrNull("svo-dir");
        if (svoDir != null) {
            var svos = ycscite.analysis.SvoExtractor.extractFromDirectory(
                    java.nio.file.Path.of(svoDir), Integer.parseInt(p.opt("svo-top", "200")));
            if (!svos.isEmpty()) {
                java.nio.file.Files.writeString(out.resolve("svo-fulltext.tsv"),
                        ycscite.analysis.SvoExtractor.toTsv(svos), java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("导出: svo-fulltext.tsv（全文 SVO，" + svos.size() + " 条）");
            }
        }
        if (p.flag("svo")) {
            var svos = ycscite.analysis.SvoExtractor.extract(project, Integer.parseInt(p.opt("svo-top", "200")));
            if (!svos.isEmpty()) {
                java.nio.file.Files.writeString(out.resolve("svo.tsv"),
                        ycscite.analysis.SvoExtractor.toTsv(svos), java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("导出: svo.tsv（SVO 三元组，" + svos.size() + " 条）");
            }
        }
        // 聚类依赖（原版 "Show Cluster Dependencies"，ph:2053-2063）
        if (p.flag("cluster-dep")) {
            var deps = ycscite.analysis.ClusterDependency.compute(r.network);
            if (!deps.isEmpty()) {
                java.nio.file.Files.writeString(out.resolve("cluster-dependency.tsv"),
                        ycscite.analysis.ClusterDependency.toTsv(deps), java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("导出: cluster-dependency.tsv（聚类依赖矩阵，" + deps.size() + " 对）");
            }
        }
        // 用户自定义标签覆盖（审计[74]，ph.java:658 cluster_labels.tsv）
        String userLabels = p.optOrNull("user-labels");
        if (userLabels != null) {
            var ul = ycscite.io.Exporters.loadUserLabels(java.nio.file.Path.of(userLabels));
            if (!ul.isEmpty()) {
                r.clusterLabels.putAll(ul);
                System.out.println("用户标签: 覆盖 " + ul.size() + " 个聚类");
            }
        }
        if (p.flag("contour")) {
            var cp = ycscite.analysis.ContourMap.Params.defaults();
            var cp2 = new ycscite.analysis.ContourMap.Params(
                    Integer.parseInt(p.opt("contour-bins", "120")),
                    Integer.parseInt(p.opt("contour-res", "15")),
                    Integer.parseInt(p.opt("contour-smooth", "24")),
                    p.opt("contour-cmap", "heatmap"),
                    p.flag("contour-citation"));
            ycscite.analysis.ContourMap.render(r.network, out.resolve("contour-map.png"), cp2);
            Branding.variants(out.resolve("contour-map.png"),logo);
            System.out.println("导出: contour-map.png（等高线图，" + cp2.colormap() + " 色板）");
        }
        // 引用上下文不确定度（原版 "Computing uncertainties of citation contexts"）
        if (p.flag("uncertainty")) {
            var rows = ycscite.analysis.Uncertainty.compute(project);
            if (rows.isEmpty()) System.out.println("不确定度: 数据无 CR 引文关系（CNKI/CSSCI 限制），已跳过");
            else {
                java.nio.file.Files.writeString(out.resolve("uncertainty.tsv"),
                        ycscite.analysis.Uncertainty.toTsv(rows), java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("导出: uncertainty.tsv（引用上下文不确定度，" + rows.size() + " 行）");
            }
        }
        if (p.flag("main-path")) {
            var mp = ycscite.analysis.MainPath.compute(project);
            if (!mp.hasData) System.out.println("主路径: 数据无 CR 引文关系（CNKI/CSSCI 限制，同原版），已跳过");
            else {
                java.nio.file.Files.writeString(out.resolve("main-path.tsv"),
                        ycscite.analysis.MainPath.toTsv(mp), java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("导出: main-path.tsv（SPC 全边+主路径，DAG 边 " + mp.allEdges.size()
                        + "，主路径 " + mp.mainPath.size() + " 步）");
            }
        }
        // .layer 叠加（原版 "Add a Network Layer (.layer)"）：from-to 文本行，画布导出时叠加
        String layerFile = p.optOrNull("layer");
        if (layerFile != null) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Path.of(layerFile),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (r.network != null) {
                    GraphCanvas lay = styledCanvas(r, project, GraphCanvas.Mode.MERGED, originalStyle);
                    lay.setOverlayLayer(lines);
                    lay.snapshot(out.resolve("network-layer.png"), originalStyle ? 8000 : 4000, originalStyle ? 5000 : 2500);
                    Branding.variants(out.resolve("network-layer.png"),logo);
                    System.out.println("导出: network-layer.png（叠加 " + lay.getOverlayEdgeCount() + " 条 .layer 边）");
                }
            } catch (java.io.IOException ex) {
                System.out.println("layer 文件读取失败: " + ex.getMessage());
            }
        }
        if (p.flag("gpt-labels")) {
            ycscite.analysis.GptLabeler.Options go = new ycscite.analysis.GptLabeler.Options();
            go.url = p.opt("gpt-url", go.url);
            go.model = p.opt("gpt-model", go.model);
            go.language = p.opt("gpt-lang", go.language);
            java.util.Map<Integer, String> gl = ycscite.analysis.GptLabeler.label(r.clusterLabels, go);
            if (gl.isEmpty()) System.out.println("GPT 标注: 未配置 API key 或网络不可达，已跳过");
            else {
                ycscite.analysis.GptLabeler.writeTsv(gl, out.resolve("cluster-labels-gpt.tsv"));
                System.out.println("导出: cluster-labels-gpt.tsv（GPT 聚类综述标签，" + gl.size() + " 簇）");
            }
        }
        if (p.flag("sva")) {
            var rows = ycscite.analysis.StructuralVariation.compute(project, o.networkKind);
            java.nio.file.Files.writeString(out.resolve("structural-variation.tsv"),
                    ycscite.analysis.StructuralVariation.toTsv(rows), java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("导出: structural-variation.tsv（结构变异分析）");
        }
        if (p.flag("dual-map")) {
            DualMap.renderJcr(project, out.resolve("dual-map.png"));
            Branding.variants(out.resolve("dual-map.png"),logo);
            System.out.println("导出: dual-map.png（双图叠加）");
        }
        if (p.flag("tsne")) {
            GraphCanvas t = new GraphCanvas();
            t.setClusterLabels(r.clusterLabels);
            t.show(r.network, GraphCanvas.Mode.TSNE, "Ycscite — " + project.name);
            t.snapshot(out.resolve("network-tsne.png"), 1600, 1000);
            Branding.variants(out.resolve("network-tsne.png"),logo);
            System.out.println("导出: network-tsne.png（t-SNE 视图）");
        }
        if (p.flag("merge-variants")) {
            int merged = ycscite.analysis.RefVariants.merge(r.network);
            System.out.println("引文变体合并: " + merged + " 个节点归并");
        }
        if (save != null) {
            ProjectStore.save(Path.of(save), project, r, o,logo);
            System.out.println("已保存项目: " + save);
        }
        return 0;
    }

    // 构造带风格的画布：原版风格=白底/红标签45°/大节点动态范围
    static String layoutOpt = null;

    static GraphCanvas styledCanvas(Pipeline.Result r, Project project, GraphCanvas.Mode mode, boolean originalStyle) {
        GraphCanvas c = new GraphCanvas();
        c.setClusterLabels(r.clusterLabels);
        c.setOriginalStyle(originalStyle);
        c.setConvexHull(false);   // 原版主网络视图默认无聚类糊（图03 聚类图才开，走 --style original + 手动开）
        // 布局算法（原版默认 Force-Directed：节点充分散开；LinLog 核心紧缩）
        String lay = layoutOpt;
        if (lay != null) switch (lay.toLowerCase()) {
            case "fr", "force", "force-directed" -> c.setLayoutAlgo(GraphCanvas.LayoutAlgo.FORCE_DIRECTED);
            case "kk", "kamada" -> c.setLayoutAlgo(GraphCanvas.LayoutAlgo.KAMADA_KAWAI);
            case "linlog" -> c.setLayoutAlgo(GraphCanvas.LayoutAlgo.LINLOG);
        }
        c.paramPanel.addAll(r.paramLines);
        c.show(r.network, mode, "Ycscite — " + project.name);
        return c;
    }

    static String pruningKey(String v) {
        if (v == null) return ycscite.analysis.Pruning.NONE;
        return switch (v.toLowerCase()) {
            case "mst" -> ycscite.analysis.Pruning.MST;
            case "pathfinder", "pfnet", "pf" -> ycscite.analysis.Pruning.PATHFINDER;
            default -> ycscite.analysis.Pruning.NONE;
        };
    }

    static int cmdSearch(String[] args) throws Exception {
        Parse p = new Parse(args);
        String query = p.next();
        int rows = Integer.parseInt(p.opt("rows", "100"));
        Path out = Path.of(p.opt("out", "ycscite-out"));
        String net = p.opt("net", "keyword");
        String label = p.opt("label", "llr");
        System.out.println("CrossRef 检索: " + query + "（" + rows + " 条）…");
        List<Record> recs = CrossRefClient.search(query, rows);
        if (recs.isEmpty()) { System.out.println("无结果。"); return 1; }
        Project project = new Project();
        project.name = "crossref: " + query;
        project.addAll(recs);
        Pipeline.Options o = options(net, 50, "modularity", label);
        Pipeline.Result r = Pipeline.run(project, o);
        Files.createDirectories(out);
        emit(r, out, true, true, true, false, false, false, true, project,false,exportLogo(p,true));
        return 0;
    }

    static int cmdVideo(String[] args) throws Exception {
        List<String> inputs = new ArrayList<>();
        Parse p = new Parse(args);
        while (p.more()) {
            String a = p.peek();
            if (a.startsWith("--")) break;
            inputs.add(p.next());
        }
        Path out = Path.of(p.opt("out", "evolution.avi"));
        String net = p.opt("net", "keyword");
        if (inputs.isEmpty()) throw new CliError("video 需要至少一个输入文件");
        Project project = loadInputs(inputs, p.opt("format", "auto"));
        Pipeline.Result r = Pipeline.run(project, options(net, 50, "modularity", "llr"));
        if (r.network.minYear == Integer.MAX_VALUE) throw new CliError("数据无年份，无法生成演化视频");
        GraphCanvas c = new GraphCanvas();
        c.setClusterLabels(r.clusterLabels);
        c.show(r.network, GraphCanvas.Mode.TIMELINE, project.name);
        try (AviWriter avi = new AviWriter(out, 1280, 720, 2)) {
            for (int y = r.network.minYear; y <= r.network.maxYear; y++) {
                c.setYearCutoff(y);
                avi.addFrame(c.renderImage(1280, 720));
            }
            System.out.println("视频已生成: " + out + "（" + avi.framesWritten() + " 帧）");
        }
        return 0;
    }

    /** ycscite batch <dir> [--preset citespace] [--out dir]：批量分析目录下全部数据文件（新增能力）。 */
    /** ycscite openalex <query> [--limit 50]：OpenAlex API 检索（新增在线数据源）。 */
    static int cmdOpenAlex(String[] args) throws Exception {
        String query = null;
        for (int i = 1; i < args.length; i++)
            if (!args[i].startsWith("--") && query == null) query = args[i];
        if (query == null) throw new CliError("openalex 需要检索词");
        int limit = 50;
        for (int i = 1; i < args.length - 1; i++)
            if (args[i].equals("--limit")) limit = Integer.parseInt(args[i + 1]);
        System.out.println("OpenAlex 检索: " + query + " (最多 " + limit + " 条)");
        var records = ycscite.io.OpenAlexClient.search(query, limit);
        if (records.isEmpty()) { System.out.println("未获取到结果（检查网络）"); return 1; }
        System.out.println("获取 " + records.size() + " 条记录");
        Project project = new Project();
        project.name = query.replaceAll("[^a-zA-Z0-9]", "_");
        project.records.addAll(records);
        Path out = Path.of("openalex-out");
        Files.createDirectories(out);
        Pipeline.Options o = options("keyword", 50, "modularity", "llr");
        Pipeline.Result r = Pipeline.run(project, o);
        ycscite.io.Exporters.writeTables(r.network, r.clusterLabels, out.resolve("nodes.tsv"), out.resolve("edges.tsv"));
        System.out.println("导出: " + out + "/ (N=" + r.network.nodes.size() + " E=" + r.network.edges.size() + ")");
        return 0;
    }

    static int cmdBatch(String[] args) throws Exception {
        Parse p = new Parse(args);
        String dir = null;
        for (int i = 1; i < args.length; i++)
            if (!args[i].startsWith("--") && dir == null) dir = args[i];
        if (dir == null) throw new CliError("batch 需要数据目录路径");
        Path dataDir = Path.of(dir);
        if (!Files.isDirectory(dataDir)) throw new CliError("不是目录: " + dir);
        String preset = p.opt("preset", "none");
        Path outRoot = Path.of(p.opt("out", "batch-out"));
        Files.createDirectories(outRoot);
        // 收集所有 .txt 文件
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dataDir)) {
            stream.filter(f -> f.toString().toLowerCase().endsWith(".txt")).sorted().forEach(files::add);
        }
        if (files.isEmpty()) { System.out.println("目录中无 .txt 文件"); return 1; }
        System.out.println("批量分析 " + files.size() + " 个文件 → " + outRoot);
        int done = 0, failed = 0;
        for (Path f : files) {
            String name = f.getFileName().toString().split("[.]")[0];
            Path out = outRoot.resolve(name);
            try {
                Files.createDirectories(out);
                List<String> single = List.of(f.toString());
                Project project = loadInputs(single, "auto");
                Pipeline.Options o = options("keyword", 50, "modularity", "llr");
                if ("citespace".equalsIgnoreCase(preset)) {
                    o.topN = 0; o.topNPerSlice = 25; o.sliceYears = 1;
                    o.pruning = Pruning.PATHFINDER; o.strictSelection = true; o.fromYear = 2002;
                }
                Pipeline.Result r = Pipeline.run(project, o);
                ycscite.io.Exporters.writeTables(r.network, r.clusterLabels, out.resolve("nodes.tsv"), out.resolve("edges.tsv"));
                var pngCanvas = new GraphCanvas();
                pngCanvas.show(r.network, GraphCanvas.Mode.MERGED, name);
                pngCanvas.snapshot(out.resolve("network.png"), 1600, 1000);
                Branding.variants(out.resolve("network.png"),exportLogo(p,true));
                done++;
                System.out.printf("  [%d/%d] %s → N=%d E=%d%n", done, files.size(), name, r.network.nodes.size(), r.network.edges.size());
            } catch (Exception e) {
                failed++;
                System.out.printf("  [FAIL] %s: %s%n", name, e.getMessage());
            }
        }
        System.out.printf("完成: %d 成功, %d 失败 → %s%n", done, failed, outRoot);
        return failed == 0 ? 0 : 1;
    }

    /** ycscite convert <in.csv|in.xml> [--to wos] [--out out.txt]：格式转换（原版 DialogWeb/ISI-XML→WoS）。 */
    static int cmdConvert(String[] args) throws Exception {
        Parse p = new Parse(args);
        String in = null;
        for (int i = 1; i < args.length; i++)
            if (!args[i].startsWith("--") && in == null) in = args[i];
        if (in == null) throw new CliError("convert 需要输入文件（.csv 或 .xml）");
        Path inPath = Path.of(in);
        if (!Files.exists(inPath)) throw new CliError("文件不存在: " + in);
        Path out = Path.of(p.opt("out", in.replaceAll("\\.[^.]+$", "") + "_wos.txt"));
        if (in.toLowerCase().endsWith(".xml"))
            ycscite.io.FormatConverter.xmlToWos(inPath, out);
        else
            ycscite.io.FormatConverter.csvToWos(inPath, out);
        System.out.println("转换完成: " + in + " → " + out);
        return 0;
    }

    /** ycscite visualize <net.gexf|net.net> [--out dir] [--png]：导入外部网络文件可视化（原版 Visualize Network）。 */
    static int cmdVisualize(String[] args) throws Exception {
        Parse p = new Parse(args);
        String file = null;
        for (int i = 1; i < args.length; i++)
            if (!args[i].startsWith("--") && file == null) file = args[i];
        if (file == null) throw new CliError("visualize 需要 .net/.gexf/.graphml 文件");
        Path in = Path.of(file);
        if (!Files.exists(in)) throw new CliError("文件不存在: " + file);
        var g = file.toLowerCase().endsWith(".gexf") || file.toLowerCase().endsWith(".graphml")
                ? ycscite.io.NetImporter.readGraphMl(in)
                : ycscite.io.NetImporter.readPajek(in);
        if (g.nodes.isEmpty()) throw new CliError("未导入任何节点（检查文件格式）");
        System.out.println("导入: " + g.nodes.size() + " 节点 " + g.edges.size() + " 连接");
        Path out = Path.of(p.opt("out", "visualize-out"));
        Files.createDirectories(out);
        var res = ycscite.analysis.Pipeline.reanalyze(g, new ycscite.analysis.Pipeline.Options(), 1);
        GraphCanvas c = new GraphCanvas();
        c.show(g, GraphCanvas.Mode.MERGED, in.getFileName().toString());
        c.snapshot(out.resolve("network.png"), 1600, 1000);
        Branding.variants(out.resolve("network.png"),exportLogo(p,true));
        System.out.println("导出: " + out.resolve("network.png"));
        return 0;
    }

    static int cmdOpen(String[] args) throws Exception {
        Parse p = new Parse(args);
        Path projFile = Path.of(p.next());
        boolean png = p.flag("png"), html = p.flag("html"), tsv = p.flag("tsv"), cit = p.flag("citation-report");
        ProjectStore.Bundle b = ProjectStore.load(projFile);
        if (b.result == null) throw new CliError("项目不含分析状态（请用 analyze 重新分析）");
        Path out = Path.of(p.opt("out", "ycscite-out"));
        Files.createDirectories(out);
        boolean any = !(png || html || tsv || cit || p.flag("pdf") || p.flag("svg"));
        if(!b.presentation.isEmpty()||p.hasFlag("view")){
            var formats=new ArrayList<String>();if(png||any)formats.add("png");if(p.flag("svg"))formats.add("svg");if(p.flag("pdf"))formats.add("pdf");
            emit(b.result,out,false,html||any,tsv||any,false,false,false,cit,b.project);
            if(!formats.isEmpty())ProjectExports.dispatch(java.util.Map.of("action","export","path",projFile.toString(),"output",out.toString(),"formats",formats,"view",p.opt("view","saved"),"language",p.opt("lang",java.util.Objects.toString(b.presentation.get("language"),I18n.isEnglish()?"en":"zh")),"logo",exportLogo(p,b.exportLogo==null||b.exportLogo)));
            return 0;
        }
        emit(b.result, out, png || any, html || any, tsv || any, p.flag("pdf"), false, false, cit, b.project,false,exportLogo(p,b.exportLogo==null||b.exportLogo));
        if(p.flag("svg")){var canvas=styledCanvas(b.result,b.project,GraphCanvas.Mode.MERGED,false);canvas.writeSvg(out.resolve("network.svg"),2400,1500);Branding.svgVariants(out.resolve("network.svg"),exportLogo(p,b.exportLogo==null||b.exportLogo),2400,1500);}

        return 0;
    }

    /**
     * ycscite sql "SELECT ..." [--url jdbc:mysql://...] [--user u] [--pass p]
     * 对应原版 "Find Clusters (Advanced using MySQL)" 的查询通道；需 classpath 提供 MySQL Connector/J。
     */
    static int cmdSql(String[] args) throws Exception {
        Parse p = new Parse(args);
        String sql = null;
        for (int i = 1; i < args.length; i++)
            if (!args[i].startsWith("--") && sql == null) sql = args[i];
        if (sql == null) throw new CliError("sql 需要 SQL 语句，如: ycscite sql \"SELECT * FROM nodes LIMIT 5\" --url jdbc:mysql://localhost:3306/db --user root");
        if (!MySqlStore.driverAvailable()) {
            System.out.println("未检测到 MySQL Connector/J。请将 mysql-connector-j.jar 加入 classpath：");
            System.out.println("  java -cp \"mysql-connector-j.jar;out\" ycscite.Cli sql \"...\"");
            return 2;
        }
        String url = p.opt("url", "jdbc:mysql://localhost:3306/citespace?useSSL=false&characterEncoding=utf8");
        String user = p.opt("user", "root"), pass = p.opt("pass", "");
        try {
            for (String[] row : MySqlStore.query(url, user, pass, sql)) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.length; i++) sb.append(i > 0 ? '\t' : "").append(row[i] == null ? "" : row[i]);
                System.out.println(sb);
            }
            return 0;
        } catch (Exception ex) {
            System.out.println("查询失败: " + ex.getMessage());
            return 1;
        }
    }

    static int cmdDoctor() {
        System.out.println("Ycscite "+VERSION+" 自检");
        System.out.println("  Java: " + System.getProperty("java.version") + " (" + System.getProperty("os.name") + ")");
        System.out.println("  MySQL Connector/J: " + (MySqlStore.driverAvailable() ? "已检测到（可直连）" : "未检测到（SQL dump 方式可用）"));
        System.out.println("  网络类型: keyword/term/author/cocitation/coupling/institution");
        System.out.println("  导入格式: wos/scopus/pubmed/cnki/cssci/derwent（auto 自动识别）");
        System.out.println("  导出: tsv png html pdf docx sql citation-report avi csl-proj");
        System.out.println("  CrossRef: " + CrossRefClient.ENDPOINT);
        return 0;
    }

    // ---------------- 共用 ----------------

    static Pipeline.Options options(String net, int topN, String cluster, String label) {
        Pipeline.Options o = new Pipeline.Options();
        o.networkKind = switch (net == null ? "keyword" : net.toLowerCase()) {
            case "term" -> NetworkBuilders.TERM_COOCCURRENCE;
            case "author", "coauthor" -> NetworkBuilders.CO_AUTHOR;
            case "cocitation", "co-citation", "cited" -> NetworkBuilders.CO_CITATION;
            case "coupling" -> NetworkBuilders.BIB_COUPLING;
            case "institution" -> NetworkBuilders.INSTITUTION;
            case "aca", "author-cocitation" -> NetworkBuilders.AUTHOR_COCITATION;
            case "jca", "journal", "journal-cocitation" -> NetworkBuilders.JOURNAL_COCITATION;
            case "category", "wc" -> NetworkBuilders.CATEGORY_COOCCURRENCE;
            case "country" -> NetworkBuilders.COUNTRY_COOCCURRENCE;
            default -> NetworkBuilders.KEYWORD_COOCCURRENCE;
        };
        o.topN = topN;
        o.clustering = "em".equalsIgnoreCase(cluster) ? Pipeline.EM
                : "modularity".equalsIgnoreCase(cluster) ? Pipeline.MODULARITY : Pipeline.SPECTRAL;
        o.labeling = "lsi".equalsIgnoreCase(label) ? Labelers.LSI
                : "isikw".equalsIgnoreCase(label) || "isi-kw".equalsIgnoreCase(label) ? Labelers.ISI_KW
                : "kcore".equalsIgnoreCase(label) ? Labelers.KCORE
                : "mi".equalsIgnoreCase(label) ? Labelers.MI
                : "tfidf".equalsIgnoreCase(label) ? Labelers.TFIDF
                : Labelers.LLR;
        return o;
    }

    public static Project loadInputs(List<String> inputs, String fmt) throws Exception {
        inputs = expandInputs(inputs, fmt);
        Project project = new Project();
        project.name = Path.of(inputs.get(0)).getFileName().toString().replaceAll("\\.[^.]+$", "");
        for (String in : inputs) {
            Path path = Path.of(in);
            if (!Files.exists(path)) throw new CliError("文件不存在: " + in);
            String f = fmt.equalsIgnoreCase("auto") ? detectFormat(path) : fmt.toLowerCase();
            List<Record> recs = switch (f) {
                case "records-tsv" -> RecordIo.readTsv(path).records;
                case "unknown" -> throw new CliError("Unrecognized format: " + path + " (choose --format)");
                case "scopus" -> ScopusCsvParser.parseFile(path);
                case "pubmed" -> PubMedParser.parseFile(path);
                case "cnki" -> detectFormat(path).equals("cssci") ? CssciParser.parseFile(path) : CnkiParser.parseFile(path);
                case "cssci" -> CssciParser.parseFile(path);
                case "derwent" -> DerwentParser.parseFile(path);
                case "proquest", "ris" -> ProQuestRisParser.parseFile(path);
                case "dimensions" -> DimensionsCsvParser.parseFile(path);
                case "kci" -> KciParser.parseFile(path);
                case "nsf" -> NsfAwardsParser.parseFile(path);
                case "bibtex", "bib" -> BibTeXParser.parse(path).records;
                case "pubmed-xml" -> PubMedXmlParser.parseFile(path);
                default -> WoSParser.parseFile(path);
            };
            project.addAll(recs);
        }
        if (project.records.isEmpty()) throw new CliError("未解析到任何记录（检查 --format）");
        return project;
    }

    public static String detectFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        try {
            String head = WoSParser.readText(path);
            if (head.length() > 12000) head = head.substring(0, 12000);
            String low = head.toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".bib") || low.matches("(?s).*@(article|book|inproceedings|incollection|misc)\\s*\\{.*")) return "bibtex";
            if(low.contains("<award>")||low.contains("<award "))return "nsf";
            if (low.contains("<pubmedarticle") || low.contains("<pubmedbookarticle")) return "pubmed-xml";
            if (head.lines().anyMatch(l -> l.startsWith("TY  -")) || name.endsWith(".ris")) return "ris";
            if (head.lines().anyMatch(l -> l.startsWith("RT "))) return "cssci";
            if (head.startsWith("PT\tAU\tAF\tTI")) return "records-tsv";
            if (head.lines().anyMatch(l -> l.startsWith("PT ") || l.startsWith("AU "))) return "wos";
            if (head.contains("PMID- ")) return "pubmed";
            if (head.contains("%0 ")) return "cnki";
            String first = head.lines().filter(l -> !l.isBlank()).findFirst().orElse("").toLowerCase(java.util.Locale.ROOT);
            if (first.contains("publication year") || first.contains("fields of research")) return "dimensions";
            if (first.contains("논문") || first.contains("발행") || first.contains("저자")) return "kci";
            if (first.contains("pn\t") || first.contains("专利号") || first.contains("\tipc")) return "derwent";
            if (name.endsWith(".csv") && (first.contains("title") || first.contains("authors"))) return "scopus";
        } catch (Exception ignored) { }
        return "unknown";
    }

    static void emit(Pipeline.Result r, Path out, boolean png, boolean html, boolean tsv,
                     boolean pdf, boolean docx, boolean sql, boolean cit, Project project) throws Exception {
        emit(r, out, png, html, tsv, pdf, docx, sql, cit, project, false);
    }

    static void emit(Pipeline.Result r, Path out, boolean png, boolean html, boolean tsv,
                     boolean pdf, boolean docx, boolean sql, boolean cit, Project project,
                     boolean originalStyle) throws Exception {
        emit(r,out,png,html,tsv,pdf,docx,sql,cit,project,originalStyle,true);
    }
    static void emit(Pipeline.Result r,Path out,boolean png,boolean html,boolean tsv,boolean pdf,boolean docx,boolean sql,boolean cit,Project project,boolean originalStyle,boolean showLogo)throws Exception {
        System.out.println("数据集: " + project.name + "  记录: " + project.records.size()
                + "  年份: " + (project.minYear == Integer.MAX_VALUE ? "?" : project.minYear)
                + "-" + (project.maxYear == Integer.MIN_VALUE ? "?" : project.maxYear));
        int n = r.network.nodes.size(), e = r.network.edges.size();
        System.out.println("网络: " + r.network.kind + "  节点: " + n + "  连接: " + e
                + "  密度: " + String.format("%.4f", e / Math.max(1.0, n * (n - 1) / 2.0)));
        System.out.printf("模块度 Q = %.4f%n", r.modularity);
        for (var en : r.clusterLabels.entrySet()) {
            StringBuilder sb = new StringBuilder("  #" + (en.getKey()) + ": ");
            for (int i = 0; i < Math.min(3, en.getValue().size()); i++) {
                if (i > 0) sb.append("; ");
                sb.append(en.getValue().get(i).term);
            }
            System.out.println(sb);
        }
        if (tsv) {
            Exporters.writeTables(r.network, r.clusterLabels, out.resolve("nodes.tsv"), out.resolve("edges.tsv"));
            System.out.println("导出: nodes.tsv + edges.tsv");
        }
        if (cit) {
            CitationReport.writeTsv(r.network, 0, out.resolve("citation-report.tsv"));
            System.out.println("导出: citation-report.tsv");
        }
        if (png) {
            GraphCanvas c = styledCanvas(r, project, GraphCanvas.Mode.TIMELINE, originalStyle);
            c.snapshot(out.resolve("network.png"), originalStyle ? 8000 : 4000, originalStyle ? 5000 : 2500);
            Branding.variants(out.resolve("network.png"),showLogo);
            System.out.println("导出: network.png" + (originalStyle ? "（原版风格）" : ""));
        }
        if (html) {
            Exporters.writeHtml(r.network, r.clusterLabels, out.resolve("network.html"));
            System.out.println("导出: network.html（自包含离线）");
        }
        if (pdf) {
            GraphCanvas c = new GraphCanvas();
            c.setClusterLabels(r.clusterLabels);
            c.show(r.network, GraphCanvas.Mode.TIMELINE, "Ycscite — " + project.name);
            PdfExporter.variants(out.resolve("report.pdf"), c.renderImage(1600, 1000), r.network, project.name,showLogo);
            System.out.println("导出: report.pdf");
        }
        if (docx) {
            DocxExporter.write(out.resolve("report.docx"), r.network, "Ycscite — " + project.name,
                    List.of("记录: " + project.records.size(), "网络: " + r.network.kind,
                            String.format("模块度 Q = %.4f", r.modularity)));
            System.out.println("导出: report.docx");
        }
        if (sql) {
            MySqlStore.writeDump(project, r.network, project.name.replaceAll("[^A-Za-z0-9_]", "_"),
                    out.resolve("dump.sql"));
            System.out.println("导出: dump.sql（mysql < dump.sql 导入）");
        }
    }

    private static boolean exportLogo(Parse arguments,boolean fallback){
        if(arguments.flag("logo")&&arguments.flag("no-logo"))throw new CliError("Choose --logo or --no-logo, not both");
        return arguments.flag("logo")||(!arguments.flag("no-logo")&&fallback);
    }
    static void printHelp() {
        System.out.println("""
                Ycscite — 科学知识图谱分析（CLI）
                用法:
                  ycscite report <数据目录或文件...> [--out 输出目录]   一键生成九图
                  ycscite analyze <输入文件...> [--format wos|scopus|pubmed|cnki|cssci|derwent|auto]
                      [--net keyword|term|author|cocitation|coupling|institution] [--topn N]
                      [--cluster spectral|modularity] [--label llr|lsi|isikw] [--out 目录]
                      [--from 年] [--to 年] [--slice 年数] [--per-slice N] [--pruning none|mst|pathfinder]
                      [--min-cluster N] [--dedup] [--cluster-summary]
                      [--png --html --tsv --pdf --docx --sql --citation-report] [--logo|--no-logo] [--save 项目.csl-proj]
                  ycscite search "检索词" [--rows N] [--net ...] [--out 目录]
                  ycscite video <输入文件...> [--out evo.avi] [--net ...]
                  ycscite open 项目.csl-proj [--png --svg --pdf --html --tsv --citation-report] [--logo|--no-logo] [--out 目录]
                  ycscite doctor
                  ycscite gui        启动图形界面
                """);
    }

    // ---------------- 参数解析 ----------------

    static final class Parse {
        private final String[] a;
        private int i = 1; // 跳过子命令
        Parse(String[] a) { this.a = a; }
        boolean more() { return i < a.length && !a[i].startsWith("--"); }
        String peek() { return a[i]; }
        String next() { if (i >= a.length) throw new CliError("缺少参数"); return a[i++]; }
        String opt(String name, String dflt) {
            for (int k = i; k < a.length - 1; k++)
                if (a[k].equals("--" + name)) return a[k + 1];
            return dflt;
        }
        String optOrNull(String name) {
            for (int k = i; k < a.length - 1; k++)
                if (a[k].equals("--" + name)) return a[k + 1];
            return null;
        }
        boolean hasFlag(String name) {
            for (String s : a) if (s.equals("--" + name)) return true;
            return false;
        }
        boolean flag(String name) {
            for (String s : a) if (s.equals("--" + name)) return true;
            return false;
        }
    }
}
