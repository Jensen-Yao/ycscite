package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.model.Project;
import ycscite.model.Record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full analysis pipeline, aligned with CiteSpace's workflow:
 * time span filter → per-slice build + per-slice Top N → union → global Top N →
 * link pruning (MST / Pathfinder) → min cluster size → centrality/burst/PageRank →
 * clustering → labels → silhouette.
 */
public final class Pipeline {

    public static class Options {
        public String networkKind = NetworkBuilders.KEYWORD_COOCCURRENCE;
        public int topN = 50;             // 0 = all
        public String selection = TOP_N;  // topn | topnpct | gindex（原版选择标准）
        public double topPercent = 50;    // Top N% 的百分比
        public String similarity = "raw"; // raw | cosine | dice | jaccard | inclusion（链接相似度归一）
        public boolean strictSelection = false;  // 严格硬截（预设拟合 362/721 的最优口径）
        public java.util.Set<String> excludeWords = new java.util.HashSet<>();  // 排除表（原版 Exclusion List）
        public double gindexK = 1.0;      // g-index 的 scale factor k
        public int minLinkStrength = 1;   // 链接强度阈值
        public int maxLinksPerNode = 0;   // 每节点最大链接数（原版 MaxLinksPerNode，0=不限）
        public int nounPhraseMin = 0;     // 候选词长下限（原版 NounPhraseWordsMin，中文=字数；0=关）
        public int nounPhraseMax = 0;     // 候选词长上限（原版 NounPhraseWordsMax；0=关）
        public int largestKComponents = 0;// 保留最大 K 个连通分量（0=全部，1=LCC）
        public boolean singularKeywords = false; // 关键词复数→单数归一
        public int fromYear = 0;          // 时间范围（含），0 = 不限
        public int toYear = 9999;         // 时间范围（含）
        public int sliceYears = 1;        // 每片年数
        public int topNPerSlice = 0;      // 每片 Top N（0 = 关闭）
        public String pruning = Pruning.NONE; // none | mst | pathfinder
        public int minClusterSize = 1;    // 小于此规模的簇归入 #0（未归类）
        public Burst.Params burstParams = new Burst.Params();
        public boolean burstFromSelectedNetwork = false;
        public int labelsPerCluster = 3;
        public String labelSource = Labelers.SRC_K;   // 标注词源 K|T|A|WC|R|TKA
        public String clustering = MODULARITY;  // 原版默认（kh.at→kX/la 模块度多级优化）| SPECTRAL
        public String labeling = Labelers.LLR;  // LLR (默认) | LSI | ISI-KW | TFIDF
    }

    public static final String SPECTRAL = "spectral";
    public static final String MODULARITY = "modularity";
    public static final String TOP_N = "topn";
    public static final String G_INDEX = "gindex";
    public static final String TOP_N_PCT = "topnpct";
    public static final String EM = "em";

    public static class Result {
        public Network network;
        public double modularity;
        public Map<Integer, List<LLRLabeler.Scored>> clusterLabels;
        /** cluster -> {size, silhouette, meanYear} */
        public Map<Integer, double[]> clusterStats;
        public int slices;
        /** 突现表数据源：全词表中有突现区间的节点（含未入选网络的词，原版 word.list 口径） */
        public java.util.List<GraphNode> burstRows = new ArrayList<>();
        /** 原版风格图左上参数面板行（CiteSpace 信息框口径） */
        public java.util.List<String> paramLines = new ArrayList<>();
    }

    public static Result run(Project p, Options o) {
        List<Record> recs = filterSpan(p, o);
        if (recs.isEmpty()) throw new IllegalArgumentException("时间范围内没有记录");
        if (o.singularKeywords)
            for (Record r : recs) {
                r.keywords.replaceAll(KeywordNorm::singular);
                r.keywordsPlus.replaceAll(KeywordNorm::singular);
            }
        Network g;
        if (o.topNPerSlice > 0) {
            g = buildPerSlice(recs, o);
            // 选择标准已逐片应用（原版 b(int) 语义），不再全局重复筛选
        } else {
            g = NetworkBuilders.KEYWORD_COOCCURRENCE.equals(o.networkKind)
                    ? NetworkBuilders.keywordCoOccurrence(recs, o.nounPhraseMin, o.nounPhraseMax)
                    : NetworkBuilders.build(o.networkKind, recs);
            applyExclusions(g,o.excludeWords);
            if (G_INDEX.equalsIgnoreCase(o.selection))
                GIndex.select(g, o.gindexK);
            else if (TOP_N_PCT.equalsIgnoreCase(o.selection))
                g.keepTopPercent(o.topPercent, 1);
            else if (o.topN > 0)
                g.keepTopN(o.topN);
        }
        g.transformSimilarity(o.similarity);
        // 原版语义（审计 P1-5）：节点只能经保留链接入场 —— 选点后剔除无边孤立点
        // （strictSelection 拟合口径保留孤立点，对应原版图中的散点）
        if (!o.strictSelection && (o.topN > 0 || o.topNPerSlice > 0)) g.removeIsolated();
        g.removeWeakLinks(o.minLinkStrength);
        // MaxLinksPerNode（原版链接选择：按权重降序保留每节点最多 K 条）
        if (o.maxLinksPerNode > 0) {
            Map<GraphNode, Integer> cnt = new java.util.HashMap<>();
            g.edges.sort((x, y) -> x.weight != y.weight ? Integer.compare(y.weight, x.weight)
                    : (x.a.id + x.b.id).compareTo(y.a.id + y.b.id));
            var it = g.edges.iterator();
            while (it.hasNext()) {
                var e = it.next();
                int ca = cnt.getOrDefault(e.a, 0), cb = cnt.getOrDefault(e.b, 0);
                if (ca >= o.maxLinksPerNode || cb >= o.maxLinksPerNode) { it.remove(); continue; }
                cnt.put(e.a, ca + 1); cnt.put(e.b, cb + 1);
            }
            g.rebuildAdjacency();
        }
        if (o.largestKComponents > 0) g.keepLargestComponents(o.largestKComponents);
        Pruning.apply(g, o.pruning);
        int slices = sliceCount(recs, o);
        // 突现时间跨度 = 项目全部记录的年份范围（原版 GUI 用完整切片区间，不受 fromYear 过滤影响）
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Record r : p.records)
            if (r.year > 0) { lo = Math.min(lo, r.year); hi = Math.max(hi, r.year); }
        if (lo != Integer.MAX_VALUE) {
            o.burstParams.spanFrom = lo;
            o.burstParams.spanTo = hi;
        }
        return finish(g, o, slices, recs, p);
    }

    /** Recompute metrics on an already-built (possibly user-edited) network. */
    public static Result reanalyze(Network g, Options o, int slices) {
        return finish(g, o, slices, null, null);
    }

    private static List<Record> filterSpan(Project p, Options o) {
        List<Record> out = new ArrayList<>();
        for (Record r : p.records)
            if (r.year <= 0 || (r.year >= o.fromYear && r.year <= o.toYear)) out.add(r);
        return out;
    }

    private static int sliceCount(List<Record> recs, Options o) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Record r : recs)
            if (r.year > 0) { min = Math.min(min, r.year); max = Math.max(max, r.year); }
        if (min == Integer.MAX_VALUE) return 0;
        int len = Math.max(1, o.sliceYears);
        return (max - min) / len + 1;
    }

    private static void applyExclusions(Network network,java.util.Set<String> exclusions){
        if(exclusions.isEmpty())return;
        var normalized=new java.util.HashSet<String>();for(String word:exclusions)normalized.add(word.strip().toUpperCase(java.util.Locale.ROOT));
        network.nodes.values().removeIf(node->normalized.contains(node.label.strip().toUpperCase(java.util.Locale.ROOT))||normalized.contains(node.id.toUpperCase(java.util.Locale.ROOT)));
        network.edges.removeIf(edge->!network.nodes.containsKey(edge.a.id)||!network.nodes.containsKey(edge.b.id));
        network.rebuildAdjacency();
    }

    /** 每片构建 → 片内 Top N → 并入全局网络（对应 CiteSpace "Top N per slice"）。 */
    private static Network buildPerSlice(List<Record> recs, Options o) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Record r : recs)
            if (r.year > 0) { min = Math.min(min, r.year); max = Math.max(max, r.year); }
        if (min == Integer.MAX_VALUE) return NetworkBuilders.build(o.networkKind, recs);
        int len = Math.max(1, o.sliceYears);
        Network union = null;
        for (int s = min; s <= max; s += len) {
            List<Record> slice = new ArrayList<>();
            for (Record r : recs)
                if (r.year >= s && r.year < s + len) slice.add(r);
            if (slice.isEmpty()) continue;
            Network part = NetworkBuilders.KEYWORD_COOCCURRENCE.equals(o.networkKind)
                    ? NetworkBuilders.keywordCoOccurrence(slice, o.nounPhraseMin, o.nounPhraseMax)
                    : NetworkBuilders.build(o.networkKind, slice);
            applyExclusions(part,o.excludeWords);
            // 原版选择标准逐片分发（CiteSpace.b(int)：g-index / Top N 阈值 / Top N% / 严格硬截）
            if (G_INDEX.equalsIgnoreCase(o.selection)) part.keepGIndex((int) Math.max(1, o.gindexK));
            else if (TOP_N_PCT.equalsIgnoreCase(o.selection)) part.keepTopPercent(o.topPercent, 1);
            else if (TOP_N.equalsIgnoreCase(o.selection)) part.keepTopN(o.topNPerSlice > 0 ? o.topNPerSlice : o.topN, 1);
            else if (o.strictSelection) part.keepTopNStrict(o.topNPerSlice);
            else part.keepTopN(o.topNPerSlice);
            // 逐片链接选择（原版 b(int)：n8=(int)(MLTR×n10), B=MaxLinksPerNode）
            if (o.maxLinksPerNode > 0) {
                int n10 = part.nodes.size();
                int n8 = (int)(2.0 * n10);
                part.edges.sort((x, y) -> x.weight != y.weight ? Integer.compare(y.weight, x.weight)
                        : (x.a.id + x.b.id).compareTo(y.a.id + y.b.id));
                Map<GraphNode, Integer> cnt = new java.util.HashMap<>();
                var it = part.edges.iterator();
                int added = 0;
                while (it.hasNext() && added < n8) {
                    var e = it.next();
                    int ca = cnt.getOrDefault(e.a, 0), cb = cnt.getOrDefault(e.b, 0);
                    if (ca >= o.maxLinksPerNode || cb >= o.maxLinksPerNode) { it.remove(); continue; }
                    cnt.put(e.a, ca + 1); cnt.put(e.b, cb + 1);
                    added++;
                }
                while (it.hasNext()) { it.remove(); }
                part.rebuildAdjacency();
            }
            if (union == null) union = part;
            else mergeInto(union, part);
        }
        return union == null ? NetworkBuilders.build(o.networkKind, recs) : union;
    }

    /** part 并入 union：节点权重相加、逐年计数合并、边按权重累加、年份跨度传播。 */
    private static void mergeInto(Network union, Network part) {
        if (part.minYear < union.minYear) union.minYear = part.minYear;
        if (part.maxYear > union.maxYear) union.maxYear = part.maxYear;
        for (GraphNode n : part.nodeCollection()) {
            GraphNode u = union.node(n.id, n.label, n.kind);
            u.weight += n.weight;
            for (var e : n.countsByYear.entrySet())
                for (int i = 0; i < e.getValue(); i++) u.addYear(e.getKey());
            if (n.firstYear < u.firstYear) u.firstYear = n.firstYear;
        }
        for (var e : part.edges)
            union.linkWeighted(union.nodes.get(e.a.id), union.nodes.get(e.b.id), e.weight, e.firstYear);
    }

    private static Result finish(Network g, Options o, int slices, List<Record> labelRecords, Project fullProject) {
        Result r = new Result();
        Betweenness.compute(g);
        // 突现：用全跨度 Top25/slice 网络做背景（原版 V 矩阵节点，含 1999-2001 数据）
        // 这比 fromYear 过滤后的网络更准确，也比全词表更接近原版 N[t]
        if (!o.burstFromSelectedNetwork && fullProject != null && !fullProject.records.isEmpty()) {
            Options burstOpts = new Options();
            burstOpts.topNPerSlice = o.topNPerSlice > 0 ? o.topNPerSlice : 25;
            burstOpts.sliceYears = o.sliceYears;
            burstOpts.strictSelection = true;
            Network burstNet = null;
            if (burstOpts.topNPerSlice > 0) {
                // 逐片 Top25 → 并入（与网络构建一致但用全时间跨度）
                int lo2 = Integer.MAX_VALUE, hi2 = Integer.MIN_VALUE;
                for (Record r2 : fullProject.records)
                    if (r2.year > 0) { lo2 = Math.min(lo2, r2.year); hi2 = Math.max(hi2, r2.year); }
                for (int s = lo2; s <= hi2; s += Math.max(1, burstOpts.sliceYears)) {
                    List<Record> slice = new ArrayList<>();
                    for (Record r2 : fullProject.records)
                        if (r2.year >= s && r2.year < s + Math.max(1, burstOpts.sliceYears)) slice.add(r2);
                    if (slice.isEmpty()) continue;
                    Network part = NetworkBuilders.build(NetworkBuilders.KEYWORD_COOCCURRENCE, slice);
                    part.keepTopNStrict(burstOpts.topNPerSlice);
                    if (burstNet == null) burstNet = part;
                    else {
                        for (GraphNode nn : part.nodeCollection()) {
                            GraphNode u = burstNet.node(nn.id, nn.label, nn.kind);
                            u.weight += nn.weight;
                            for (var ee : nn.countsByYear.entrySet()) for (int ii = 0; ii < ee.getValue(); ii++) u.addYear(ee.getKey());
                        }
                        for (var ee : part.edges) burstNet.linkWeighted(burstNet.nodes.get(ee.a.id), burstNet.nodes.get(ee.b.id), ee.weight);
                    }
                }
            }
            if (burstNet == null) burstNet = NetworkBuilders.build(NetworkBuilders.KEYWORD_COOCCURRENCE, fullProject.records);
            Burst.compute(burstNet, o.burstParams);
            for (GraphNode n : g.nodeCollection()) {
                GraphNode src = burstNet.nodes.get(n.id);
                if (src != null) {
                    n.burstness = src.burstness;
                    n.burstStrength = src.burstStrength;
                    n.burstIntervals.clear();
                    n.burstIntervals.addAll(src.burstIntervals);
                    n.intervalStrengths.clear();
                    n.intervalStrengths.addAll(src.intervalStrengths);
                }
            }
            java.util.List<GraphNode> burstRows = new ArrayList<>();
            for (GraphNode b : burstNet.nodeCollection())
                if (!b.burstIntervals.isEmpty()) burstRows.add(b);
            r.burstRows = burstRows;
        } else {
            Burst.compute(g, o.burstParams);
            java.util.List<GraphNode> burstRows = new ArrayList<>();
            for (GraphNode n : g.nodeCollection())
                if (!n.burstIntervals.isEmpty()) burstRows.add(n);
            r.burstRows = burstRows;
        }
        if (EM.equalsIgnoreCase(o.clustering))
            EmClusterer.compute(g);                      // EM：无模块度口径
        else
            r.modularity = MODULARITY.equalsIgnoreCase(o.clustering)
                    ? Clusterer.compute(g)
                    : SpectralClusterer.compute(g);
        applyMinClusterSize(g, o.minClusterSize);
        r.clusterLabels = Labelers.label(g, o.labeling, o.labelsPerCluster, o.labelSource, labelRecords);
        PageRank.compute(g);
        Centralities.compute(g);
        for (var n : g.nodeCollection()) {
            n.sigma = Math.pow(n.centrality + 1, n.burstness);
            n.halfLife = halfLife(n);
        }
        Map<GraphNode, Double> sil = Silhouette.compute(g);
        Map<Integer, Double> silByCluster = Silhouette.perCluster(g, sil);
        java.util.TreeMap<Integer, double[]> stats = new java.util.TreeMap<>();
        for (GraphNode n : g.nodeCollection()) {
            double[] s = stats.computeIfAbsent(n.cluster, k -> new double[3]);
            s[0]++;
            s[2] += n.firstYear == Integer.MAX_VALUE ? 0 : n.firstYear;
        }
        for (var en : stats.entrySet()) {
            double size = en.getValue()[0];
            en.getValue()[1] = silByCluster.getOrDefault(en.getKey(), 0.0);
            en.getValue()[2] = size == 0 ? 0 : en.getValue()[2] / size;
        }
        r.clusterStats = stats;
        r.network = g;
        r.slices = slices;
        // 原版风格参数面板（CiteSpace 左上信息框口径）
        r.paramLines.add("Ycscite 7.0.1 (Java 21)");
        r.paramLines.add("Timespan: " + (g.minYear == Integer.MAX_VALUE ? "?" : g.minYear)
                + "-" + (g.maxYear == Integer.MIN_VALUE ? "?" : g.maxYear)
                + "  (Slice Length = " + Math.max(1, o.sliceYears) + ")");
        r.paramLines.add("Node Types: " + g.kind);
        String sel = o.topNPerSlice > 0 ? "Top " + o.topNPerSlice + " per slice"
                : G_INDEX.equalsIgnoreCase(o.selection) ? "g-index (k=" + o.gindexK + ")"
                : TOP_N_PCT.equalsIgnoreCase(o.selection) ? "Top " + o.topPercent + "%"
                : o.topN > 0 ? "Top " + o.topN : "All";
        r.paramLines.add("Selection Criteria: " + sel);
        r.paramLines.add("Links: " + g.edges.size() + "  Nodes: " + g.nodes.size());
        r.paramLines.add("Pruning: " + switch (o.pruning) {
            case Pruning.MST -> "Minimum Spanning Tree";
            case Pruning.PATHFINDER -> "Pathfinder (q=n, PFNet)";
            default -> "None";
        } + "  Modularity Q=" + String.format("%.4f", r.modularity));
        return r;
    }

    /** 小簇（<minClusterSize）归入 #0"未归类"（对应 CiteSpace 的最小聚类规模过滤）。 */
    private static void applyMinClusterSize(Network g, int min) {
        if (min <= 1) return;
        Map<Integer, Integer> sizes = new java.util.HashMap<>();
        for (GraphNode n : g.nodeCollection()) sizes.merge(n.cluster, 1, Integer::sum);
        for (GraphNode n : g.nodeCollection())
            if (sizes.get(n.cluster) < min) n.cluster = 0;
    }

    /** Citation half-life: years between the year that reaches 50% of cumulative weight and the last year seen. */
    public static int halfLife(ycscite.model.GraphNode n) {
        if (n.countsByYear.isEmpty()) return 0;
        int total = 0, last = 0;
        for (var e : n.countsByYear.entrySet()) { total += e.getValue(); last = Math.max(last, e.getKey()); }
        int cum = 0;
        for (var e : n.countsByYear.entrySet()) {
            cum += e.getValue();
            if (cum * 2 >= total) return Math.max(0, last - e.getKey());
        }
        return 0;
    }
}
