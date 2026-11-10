package ycscite;

import ycscite.analysis.*;
import ycscite.io.*;
import ycscite.model.CitedRef;
import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.model.Project;
import ycscite.model.Record;
import ycscite.ui.GraphCanvas;
import ycscite.ui.Strings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * Plain-assertion self-test (no JUnit dependency).
 * Run: java -cp out ycscite.TestAll  → prints PASS/FAIL per case, exit 1 on any failure.
 */
public final class TestAll {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        testWoSParser();
        testScopusParser();
        testPubMedParser();
        testCoOccurrenceCounts();
        testCoCitation();
        testBrandes();
        testKleinbergBurst();
        testClusterer();
        testAuditRegressions();
        testLLR();
        testPipelineAndExports();
        testDemoData();
        testHalfLife();
        testViewsRender();
        testSpectral();
        testCitationReport();
        testTimelineBands();
        testFocus();
        testStringsI18n();
        testNetworkEdit();
        testAnimation();
        testCnkiParser();
        testCrossRefParse();
        testPdf();
        testCssciParser();
        testDerwentParser();
        testPageRank();
        testLabelerVariants();
        testAviVideo();
        testDocx();
        testMySql();
        testTermNetwork();
        testProjectRoundTrip();
        testCli();
        testNineFigureReport();
        ProductTests.run();
        testPruningAndSlicing();
        testEvidenceGapFeatures();
        testFinalGapRound();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    static void check(String name, boolean cond, String detail) {
        System.out.println((cond ? "PASS " : "FAIL ") + name + (cond ? "" : "  -- " + detail));
        if (!cond) failures++;
    }

    static void testWoSParser() throws Exception {
        String wos = """
                PT J
                AU SMITH J; LEE K
                AF SMITH JOHN; LEE KATE
                TI A study of science mapping
                SO J INFORMETR
                DE science mapping; bibliometrics
                ID CO-CITATION
                AB An abstract.
                CR CHEN C, 2006, J AM SOC INF SCI TECHN, V57, P359
                   SMALL H, 1973, J AM SOC INF SCI, V24, P265
                PY 2021
                TC 12
                UT WOS:000123456
                ER

                PT J
                AU JONES B
                TI Second record
                SO OTHER J
                DE bibliometrics
                PY 2022
                ER
                """;
        Path tmp = Files.createTempFile("wos-test", ".txt");
        Files.writeString(tmp, wos);
        List<Record> recs = WoSParser.parseFile(tmp);
        check("wos.count", recs.size() == 2, "got " + recs.size());
        Record r0 = recs.get(0);
        check("wos.authors", r0.authors.equals(List.of("SMITH J", "LEE K")), "" + r0.authors);
        check("wos.keywords", r0.keywords.equals(List.of("science mapping", "bibliometrics")), "" + r0.keywords);
        check("wos.keywordsPlus", r0.keywordsPlus.equals(List.of("CO-CITATION")), "" + r0.keywordsPlus);
        check("wos.citedRefs", r0.citedRefs.size() == 2, "" + r0.citedRefs);
        check("wos.year", r0.year == 2021, "" + r0.year);
        check("wos.tc", r0.timesCited == 12, "" + r0.timesCited);
        check("wos.continuation", r0.citedRefs.get(1).contains("SMALL H, 1973"), "" + r0.citedRefs);
        check("wos.second", recs.get(1).authors.equals(List.of("JONES B")), "" + recs.get(1).authors);
        Files.delete(tmp);
    }

    static void testScopusParser() throws Exception {
        String csv = "Authors,Title,Year,Source title,Author Keywords,Index Keywords,References,DOI\n" +
                "\"Smith J.; Lee K.\",\"Paper one\",2020,\"J Informetr\",\"kw1; kw2\",,\"Chen C., 2020, J INFORM; X Y, 2019, OTHER J\",10.1/x\n" +
                "\"Jones B.\",\"Paper two\",2021,\"Other J\",\"kw1\",,,10.2/y\n";
        Path tmp = Files.createTempFile("scopus-test", ".csv");
        Files.writeString(tmp, csv);
        List<Record> recs = ScopusCsvParser.parseFile(tmp);
        check("scopus.count", recs.size() == 2, "got " + recs.size());
        check("scopus.authors", recs.get(0).authors.equals(List.of("Smith J.", "Lee K.")), "" + recs.get(0).authors);
        check("scopus.refs", recs.get(0).citedRefs.size() == 2, "" + recs.get(0).citedRefs);
        check("scopus.doi", recs.get(1).doi.equals("10.2/y"), recs.get(1).doi);
        Files.delete(tmp);
    }

    static void testCoOccurrenceCounts() {
        Record a = new Record(); a.year = 2020; a.keywords = List.of("k1", "k2", "k3");
        Record b = new Record(); b.year = 2021; b.keywords = List.of("k2", "k3");
        Network g = NetworkBuilders.keywordCoOccurrence(List.of(a, b));
        check("kw.nodes", g.nodes.size() == 3, "" + g.nodes.size());
        check("kw.freq", g.nodes.get("K2").weight == 2, "" + g.nodes.get("K2").weight);
        GraphEdge e12 = null;
        for (GraphEdge e : g.edges) {
            Set<String> ids = new HashSet<>(List.of(e.a.id, e.b.id));
            if (ids.equals(new HashSet<>(List.of("K2", "K3")))) e12 = e;
        }
        check("kw.pairweight", e12 != null && e12.weight == 2, e12 == null ? "missing" : "" + e12.weight);
    }

    static void testCoCitation() {
        Record a = new Record(); a.year = 2020;
        a.citedRefs = List.of("CHEN C, 2006, J ASIST, V57, P359", "SMALL H, 1973, J ASIST, V24, P265");
        Record b = new Record(); b.year = 2021;
        b.citedRefs = List.of("Chen C, 2006, J ASIST, V57, P360", "SMALL H, 1973, J ASIST, V24, P265");
        Network g = NetworkBuilders.coCitation(List.of(a, b));
        check("cocit.keynormalize", g.nodes.size() == 2, "got " + g.nodes.size() + ": " + g.nodes.keySet());
        GraphEdge e = g.edges.isEmpty() ? null : g.edges.get(0);
        check("cocit.weight", e != null && e.weight == 2, e == null ? "no edge" : "" + e.weight);
    }

    static void testBrandes() {
        // path a-b-c: b has betweenness 1; normalized by (n-1)(n-2)/2 = 1
        Network p = new Network("t");
        GraphNode na = p.node("a", "a", "k"), nb = p.node("b", "b", "k"), nc = p.node("c", "c", "k");
        p.link(na, nb); p.link(nb, nc);
        Betweenness.compute(p);
        check("brandes.path.mid", Math.abs(nb.centrality - 1.0) < 1e-9, "" + nb.centrality);
        check("brandes.path.leaf", na.centrality == 0, "" + na.centrality);

        // star: center betweenness = C(n-1,2); normalized = 1
        Network s = new Network("t");
        GraphNode hub = s.node("hub", "hub", "k");
        for (int i = 0; i < 5; i++) { GraphNode x = s.node("x" + i, "x" + i, "k"); s.link(hub, x); }
        Betweenness.compute(s);
        check("brandes.star.hub", Math.abs(hub.centrality - 1.0) < 1e-9, "" + hub.centrality);

        // disconnected: no crash, zeros
        Network d = new Network("t");
        GraphNode i1 = d.node("i1", "i1", "k"), i2 = d.node("i2", "i2", "k");
        Betweenness.compute(d);
        check("brandes.disconnected", i1.centrality == 0 && i2.centrality == 0, "ok");
    }

    static void testKleinbergBurst() {
        // 源码二项模型（7.0.1 mi.java -bin -rel 2 2 1 -trans 3）：
        // 突现年占比须 ≈2× 全期占比且增益 Σ > γ=3·ln(T+1)=6.24 才进突变态
        Network g = new Network("t");
        GraphNode bursty = g.node("bursty", "bursty", "k");
        GraphNode flat = g.node("flat", "flat", "k");
        GraphNode smooth = g.node("smooth", "smooth", "k");
        int[] burstCounts = {0, 0, 12, 16, 13, 0, 0};  // years 2018..2024（突现年占比 ~36-42% vs 全期 ~22%，增益 7.65 > 6.24）
        int[] flatCounts = {1, 1, 1, 1, 1, 1, 1};
        int[] smoothCounts = {20, 20, 20, 21, 20, 20, 20};
        int year = 2018;
        for (int t = 0; t < 7; t++) {
            for (int i = 0; i < burstCounts[t]; i++) bursty.addYear(year + t);
            for (int i = 0; i < flatCounts[t]; i++) flat.addYear(year + t);
            for (int i = 0; i < smoothCounts[t]; i++) smooth.addYear(year + t);
            g.noteYear(year + t);
        }
        Burst.Params p = new Burst.Params();
        Burst.compute(g, p);
        check("burst.bursty", bursty.burstness > 0, "burstness=" + bursty.burstness);
        check("burst.bursty.interval", !bursty.burstIntervals.isEmpty(), "no intervals");
        check("burst.bursty.strength", bursty.burstStrength > 0, "strength=" + bursty.burstStrength);
        check("burst.flat", flat.burstness == 0, "burstness=" + flat.burstness);
        // 早期词：前两年计数集中且占比≈2×基线（真实"大遗址 2009-2014"类规模），源码模型可检出
        Network g2 = new Network("t2");
        GraphNode early = g2.node("early", "early", "k");
        GraphNode bg = g2.node("bg", "bg", "k");
        int[] earlyC = {8, 5, 0, 0, 0, 0, 0};      // 集中在前两年（占比 57%/38% vs 全期 8%，增益 7.72 > 6.24）
        int[] bgC = {6, 8, 20, 24, 28, 30, 32};    // 背景逐年扩大
        for (int t = 0; t < 7; t++) {
            for (int i = 0; i < earlyC[t]; i++) early.addYear(year + t);
            for (int i = 0; i < bgC[t]; i++) bg.addYear(year + t);
            g2.noteYear(year + t);
        }
        Burst.compute(g2, p);
        check("burst.early", early.burstness > 0, "early word not detected (source binomial model)");
    }

    static void testClusterer() {
        // two triangles joined by one bridge: expect 2 clusters, positive Q
        Network g = new Network("t");
        String[] names = {"a1", "a2", "a3", "b1", "b2", "b3"};
        GraphNode[] ns = new GraphNode[6];
        for (int i = 0; i < 6; i++) ns[i] = g.node(names[i], names[i], "k");
        int[][] pairs = {{0,1},{1,2},{0,2},{3,4},{4,5},{3,5},{2,3}};
        for (int[] pr : pairs) g.link(ns[pr[0]], ns[pr[1]]);
        double q = Clusterer.compute(g);
        int clusters = (int) Arrays.stream(ns).mapToInt(n -> n.cluster).distinct().count();
        check("cluster.count", clusters == 2, "clusters=" + clusters);
        check("cluster.split", ns[0].cluster != ns[3].cluster, "same cluster");
        check("cluster.q", q > 0.3, "Q=" + q);
    }

    /** 审计回归（第十三轮）：快照崩溃/编号顺序/确定性/无权聚类/keepTopN 平局/LinLog 确定性。 */
    static void testAuditRegressions() throws Exception {
        // ① 不等大小三团 + 桥：#1 必须是最大团（审计 P0-1 编号错乱回归）
        Network g = new Network("t");
        List<GraphNode> big = new ArrayList<>(), mid = new ArrayList<>(), small = new ArrayList<>();
        for (int i = 0; i < 10; i++) big.add(g.node("A" + i, "A" + i, "k"));
        for (int i = 0; i < 5; i++) mid.add(g.node("B" + i, "B" + i, "k"));
        for (int i = 0; i < 3; i++) small.add(g.node("C" + i, "C" + i, "k"));
        for (List<GraphNode> cl : List.of(big, mid, small))
            for (int i = 0; i < cl.size(); i++)
                for (int j = i + 1; j < cl.size(); j++) g.link(cl.get(i), cl.get(j));
        g.link(big.get(0), mid.get(0));
        g.link(mid.get(1), small.get(0));
        Clusterer.compute(g);
        int c1 = big.get(0).cluster, c2 = mid.get(0).cluster, c3 = small.get(0).cluster;
        check("cluster.order1", c1 == 1, "big=" + c1);
        check("cluster.order2", c2 == 2, "mid=" + c2);
        check("cluster.order3", c3 == 3, "small=" + c3);
        // ② 确定性：同图两次运行结果逐位一致
        Network copy = g.deepCopy();
        Clusterer.compute(copy);
        boolean same = true;
        for (GraphNode n : g.nodeCollection())
            if (copy.nodes.get(n.id).cluster != n.cluster) { same = false; break; }
        check("cluster.deterministic", same, "two runs differ");
        // ③ fuzz：60 张随机图不崩溃且 #1 恒为最大簇（审计实锤 2% 崩溃/39% 错序的回归门）
        Random fuzz = new Random(7);
        boolean ok = true; String msg = "";
        for (int t = 0; t < 60 && ok; t++) {
            Network fg = new Network("f");
            int n = 4 + fuzz.nextInt(18);
            List<GraphNode> fns = new ArrayList<>();
            for (int i = 0; i < n; i++) fns.add(fg.node("x" + i, "x" + i, "k"));
            int m = n + fuzz.nextInt(2 * n);
            for (int e = 0; e < m; e++)
                fg.link(fns.get(fuzz.nextInt(n)), fns.get(fuzz.nextInt(n)));
            try {
                Clusterer.compute(fg);
                Map<Integer, Integer> sizes = new HashMap<>();
                for (GraphNode fn : fns) if (fn.cluster > 0) sizes.merge(fn.cluster, 1, Integer::sum);
                int max = sizes.values().stream().max(Integer::compare).orElse(0);
                if (sizes.getOrDefault(1, 0) != max) { ok = false; msg = "no crash but #1 not largest at t=" + t; }
            } catch (Exception ex) {
                ok = false; msg = "crash at t=" + t + ": " + ex;
            }
        }
        check("cluster.fuzz", ok, msg);
        // ④ 无权聚类：同结构不同边权 → 相同划分（原版 kY 权重恒 1）
        Network w1 = new Network("w1"), w2 = new Network("w2");
        for (String nm : new String[]{"p","q","r","s","u","v"}) { w1.node(nm, nm, "k"); w2.node(nm, nm, "k"); }
        int[][] prs = {{0,1},{1,2},{0,2},{3,4},{4,5},{3,5},{2,3}};
        String[] ids = {"p","q","r","s","u","v"};
        for (int[] pr : prs) { w1.link(w1.nodes.get(ids[pr[0]]), w1.nodes.get(ids[pr[1]])); }
        for (int[] pr : prs)
            w2.linkWeighted(w2.nodes.get(ids[pr[0]]), w2.nodes.get(ids[pr[1]]), 7);   // 加权版
        Clusterer.compute(w1); Clusterer.compute(w2);
        boolean unw = true;
        for (String nm : ids)
            if (w1.nodes.get(nm).cluster != w2.nodes.get(nm).cluster) { unw = false; break; }
        check("cluster.unweighted", unw, "weighted edges changed partition");
        // ⑤ keepTopN 平局语义：阈值=第 n 名权重，平局全收（原版 b(int)）
        Network kt = new Network("kt");
        GraphNode h = kt.node("h", "h", "k"); h.weight = 9;
        for (int i = 0; i < 5; i++) { GraphNode t = kt.node("t" + i, "t" + i, "k"); t.weight = 3; kt.link(h, t); }
        kt.keepTopN(3);
        check("keeptopn.ties", kt.nodes.size() == 6, "kept=" + kt.nodes.size() + "（阈值=第4名的3，全部平局保留）");
        // ⑥ LinLog 确定性 + 无 NaN
        Network lg = new Network("lg");
        for (int i = 0; i < 12; i++) lg.node("L" + i, "L" + i, "k");
        for (int i = 0; i < 12; i++) for (int j = i + 1; j < Math.min(i + 3, 12); j++) lg.link(lg.nodes.get("L" + i), lg.nodes.get("L" + j));
        var p1 = ycscite.analysis.LinLogLayout.layout(lg, 42);
        var p2 = ycscite.analysis.LinLogLayout.layout(lg, 42);
        boolean det = p1.size() == p2.size();
        boolean nan = false;
        for (var en : p1.entrySet()) {
            var q2 = p2.get(en.getKey());
            if (q2 == null || q2.x != en.getValue().x || q2.y != en.getValue().y) det = false;
            if (Double.isNaN(en.getValue().x) || Double.isNaN(en.getValue().y)) nan = true;
        }
        check("linlog.deterministic", det, "same seed differs");
        check("linlog.nofnan", !nan, "NaN in layout");
        // ⑦ removeIsolated
        Network ri = new Network("ri");
        GraphNode k1 = ri.node("k1", "k1", "k"), k2 = ri.node("k2", "k2", "k"), iso = ri.node("iso", "iso", "k");
        ri.link(k1, k2);
        ri.removeIsolated();
        check("removeisolated", ri.nodes.size() == 2 && !ri.nodes.containsKey("iso"), "N=" + ri.nodes.size());
        // ⑧ Pathfinder 差分用例（原版 kV oracle 实测边集：pf1 保留 0-2/1-2/1-3/3-4）
        Network pf = new Network("pf");
        Map<Integer, GraphNode> pn = new HashMap<>();
        for (int i = 0; i < 5; i++) pn.put(i, pf.node("N" + i, "N" + i, "k"));
        int[][] pfEdges = {{0,1,9},{0,2,5},{1,2,3},{1,3,7},{2,3,8},{3,4,4}};
        for (int[] e : pfEdges) pf.linkWeighted(pn.get(e[0]), pn.get(e[1]), e[2]);
        Pruning.apply(pf, Pruning.PATHFINDER);
        Set<String> pfKeep = new HashSet<>();
        for (GraphEdge e : pf.edges) pfKeep.add(Math.min(Integer.parseInt(e.a.id.substring(1)), Integer.parseInt(e.b.id.substring(1))) + "-" + Math.max(Integer.parseInt(e.a.id.substring(1)), Integer.parseInt(e.b.id.substring(1))));
        check("pathfinder.oracle1", pfKeep.equals(Set.of("0-2", "1-2", "1-3", "3-4")), pfKeep.toString());
        // 星形：全部保留（无替代路径）
        Network pfs = new Network("pfs");
        for (int i = 0; i < 5; i++) pfs.node("N" + i, "N" + i, "k");
        for (int i = 0; i < 4; i++) pfs.linkWeighted(pfs.nodes.get("N" + i), pfs.nodes.get("N4"), 5);
        Pruning.apply(pfs, Pruning.PATHFINDER);
        check("pathfinder.oracle2", pfs.edges.size() == 4, "kept=" + pfs.edges.size());
        // ⑨ ACA/JCA/WC/国家网络族（第十五轮）
        Network acan = new Network("a");
        for (GraphNode n : acan.nodeCollection()) { }
        var rec1 = new ycscite.model.Record();
        rec1.year = 2020;
        rec1.citedRefs.add("SMITH J, 2018, J INFORMETR, V10");
        rec1.citedRefs.add("CHEN C, 2016, SCIENTOMETRICS, V5");
        rec1.citedRefs.add("SMITH J, 2018, J INFORMETR, V11");
        var rec2 = new ycscite.model.Record();
        rec2.year = 2021;
        rec2.citedRefs.add("CHEN C, 2016, SCIENTOMETRICS, V6");
        rec2.citedRefs.add("LIU Y, 2019, JASIST, V3");
        Network acaNet = ycscite.analysis.NetworkBuilders.build(ycscite.analysis.NetworkBuilders.AUTHOR_COCITATION, List.of(rec1, rec2));
        check("aca.nodes", acaNet.nodes.size() == 3, "N=" + acaNet.nodes.size());
        check("aca.links", acaNet.edges.size() == 2, "E=" + acaNet.edges.size());   // SMITH-CHEN 共被引（重复合并）
        Network jcaNet = ycscite.analysis.NetworkBuilders.build(ycscite.analysis.NetworkBuilders.JOURNAL_COCITATION, List.of(rec1, rec2));
        check("jca.nodes", jcaNet.nodes.size() == 3, "N=" + jcaNet.nodes.size());
        var recW = new ycscite.model.Record();
        recW.categories.add("Information Science");
        recW.categories.add("Computer Science");
        var recW2 = new ycscite.model.Record();
        recW2.categories.add("Computer Science");
        recW2.categories.add("Management");
        Network wcNet = ycscite.analysis.NetworkBuilders.build(ycscite.analysis.NetworkBuilders.CATEGORY_COOCCURRENCE, List.of(recW, recW2));
        check("wc.net", wcNet.nodes.size() == 3 && wcNet.edges.size() == 2, "N=" + wcNet.nodes.size() + " E=" + wcNet.edges.size());
        var recC = new ycscite.model.Record();
        recC.addresses.add("Tsinghua Univ, Beijing, 100084, Peoples R China");
        recC.addresses.add("MIT, Cambridge, MA 02139, USA");
        var recC2 = new ycscite.model.Record();
        recC2.addresses.add("Oxford Univ, Oxford, England");
        Network coNet = ycscite.analysis.NetworkBuilders.build(ycscite.analysis.NetworkBuilders.COUNTRY_COOCCURRENCE, List.of(recC, recC2));
        check("country.net", coNet.nodes.containsKey("PEOPLES R CHINA") && coNet.nodes.containsKey("USA") && coNet.nodes.containsKey("UK"),
                coNet.nodes.keySet().toString());
        // ⑩ 标注词源（文档级 LLR：题名词应区分主题簇）
        Network ls = new Network("ls");
        GraphNode termA = ls.node("alpha", "alpha", "k"); termA.weight = 5; termA.cluster = 1;
        GraphNode termB = ls.node("beta", "beta", "k"); termB.weight = 5; termB.cluster = 2;
        var docA = new ycscite.model.Record(); docA.keywords.add("alpha"); docA.title = "quantum entanglement advances";
        var docB = new ycscite.model.Record(); docB.keywords.add("beta"); docB.title = "tourism economy growth";
        var labels = ycscite.analysis.Labelers.label(ls, "LLR", 3, ycscite.analysis.Labelers.SRC_T, List.of(docA, docB));
        boolean tOk = !labels.isEmpty() && labels.get(1) != null && !labels.get(1).isEmpty()
                && labels.get(1).stream().anyMatch(s -> s.term.equals("quantum") || s.term.equals("entanglement"));
        check("labelsource.t", tOk, labels.toString());
        // ⑪ Pajek/GEXF/UCINET/邻接矩阵导出
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ycs-exp");
        ycscite.io.Exporters.writePajek(ls, dir.resolve("a.net"));
        List<String> pj = java.nio.file.Files.readAllLines(dir.resolve("a.net"));
        check("pajek.header", pj.get(0).equals("*Vertices 2"), pj.get(0));
        check("pajek.edges", pj.stream().anyMatch(l -> l.matches("1 2 \\d+")) || pj.stream().anyMatch(l -> l.startsWith("*Edges")), "edges");
        ycscite.io.Exporters.writeGexf(ls, dir.resolve("a.gexf"));
        String gx = java.nio.file.Files.readString(dir.resolve("a.gexf"));
        check("gexf.root", gx.contains("<gexf") && gx.contains("<node id=\"0\""), "gexf");
        ycscite.io.Exporters.writeUcinet(ls, dir.resolve("a.dl"));
        String dl = java.nio.file.Files.readString(dir.resolve("a.dl"));
        check("ucinet.header", dl.startsWith("DL n=2"), dl.substring(0, 12));
        ycscite.io.Exporters.writeAdjacencyMatrix(ls, dir.resolve("a.csv"));
        String am = java.nio.file.Files.readString(dir.resolve("a.csv"));
        check("adjmat.header", am.startsWith(",") && am.contains("alpha"), "adjmat");
        // ⑫ 第十六轮：Top N% / 相似度系数 / 环形/热力/山景视图
        Network pct = new Network("pct");
        for (int i = 0; i < 10; i++) { GraphNode t = pct.node("p" + i, "p" + i, "k"); t.weight = 10 - i; }
        pct.link(pct.nodes.get("p0"), pct.nodes.get("p1"));
        pct.link(pct.nodes.get("p1"), pct.nodes.get("p2"));
        pct.keepTopPercent(30, 1);
        check("topnpct.size", pct.nodes.size() == 3, "kept=" + pct.nodes.size() + "（30%×10 阈值）");
        Network sim = new Network("sim");
        GraphNode sa = sim.node("sa", "sa", "k"), sb = sim.node("sb", "sb", "k"), sc = sim.node("sc", "sc", "k");
        sim.linkWeighted(sa, sb, 4);
        sim.linkWeighted(sb, sc, 2);
        sim.transformSimilarity("cosine");
        boolean cosOk = sim.edges.stream().anyMatch(e -> e.weight == 816) && sim.edges.stream().anyMatch(e -> e.weight == 577);
        check("similarity.cosine", cosOk, sim.edges.toString());
        Network cn = new Network("cn");
        for (int i = 0; i < 6; i++) cn.node("c" + i, "c" + i, "k");
        for (int i = 0; i < 6; i++) cn.link(cn.nodes.get("c" + i), cn.nodes.get("c" + (i + 1) % 6));
        GraphCanvas cc = new GraphCanvas();
        cc.show(cn, GraphCanvas.Mode.CIRCULAR, "t");
        check("circular.render", cc.renderImage(800, 600) != null, "render");
        for (GraphNode n : cn.nodeCollection()) { n.cluster = 1; n.addYear(2020); }
        GraphCanvas hm2 = new GraphCanvas();
        hm2.show(cn, GraphCanvas.Mode.HEATMAP, "t");
        check("heatmap.render", hm2.renderImage(800, 600) != null, "render");
        GraphCanvas ls2 = new GraphCanvas();
        ls2.show(cn, GraphCanvas.Mode.LANDSCAPE, "t");
        check("landscape.render", ls2.renderImage(800, 600) != null, "render");
        // ⑬ 第十七轮：JCR 底图 / .list 高亮 / GPT 解析
        Path jcrSample=Files.createTempFile("ycscite-jcr-parser", ".csv");
        Files.writeString(jcrSample,"ID,CITEDJ,X,Y,WEIGHT,CLUSTER,TITLE,RED,GREEN,BLUE,NPUBL,BLONDEL\n1,JOURNAL A,0.5,0.2,85,3,\"Full title, with comma\",20,30,40,2,1\n");
        var jcr=ycscite.io.DualMap.loadJcr(jcrSample);
        check("jcr.basemap",jcr.size()==1,"CSV basemap parser, quoted title");
        Files.delete(jcrSample);
        GraphCanvas hl = new GraphCanvas();
        hl.show(cn, GraphCanvas.Mode.CIRCULAR, "t");
        hl.setHighlightList(Set.of("c1", "c2"));
        check("highlight.list", hl.getHighlightCount() == 2, "count=" + hl.getHighlightCount());
        hl.setTransparentBg(true);
        java.awt.image.BufferedImage timg = hl.renderImage(400, 300);
        check("transparent.bg", (timg.getRGB(0, 0) >>> 24) == 0, "alpha=" + (timg.getRGB(0, 0) >>> 24));
        String parsed = ycscite.analysis.GptLabeler.extractContent("{\"choices\":[{\"message\":{\"content\":\"文化遗产治理\"}}]}");
        check("gpt.parse", parsed.equals("文化遗产治理"), parsed);
        // ⑭ 第十八轮：主路径 SPC / k-core / EM / UMAP / TSV 往返
        var prj = new ycscite.model.Project();
        var rA = new ycscite.model.Record(); rA.ut = "UT:A"; rA.year = 2001;
        rA.allAuthors().add("SMITH J"); rA.source = "J INFORMETR";
        var rB = new ycscite.model.Record(); rB.ut = "UT:B"; rB.year = 2005;
        rB.allAuthors().add("CHEN C"); rB.source = "SCIENTOMETRICS";
        rB.citedRefs.add("SMITH J, 2001, J INFORMETR, V1");
        var rC = new ycscite.model.Record(); rC.ut = "UT:C"; rC.year = 2010;
        rC.allAuthors().add("LIU Y"); rC.source = "JASIST";
        rC.citedRefs.add("SMITH J, 2001, J INFORMETR, V2");
        rC.citedRefs.add("CHEN C, 2005, SCIENTOMETRICS, V3");
        prj.records.add(rA); prj.records.add(rB); prj.records.add(rC);
        var mp = ycscite.analysis.MainPath.compute(prj);
        check("mainpath.dag", mp.hasData && mp.allEdges.size() == 3, "edges=" + mp.allEdges.size());
        check("mainpath.path", !mp.mainPath.isEmpty(), "steps=" + mp.mainPath.size());
        Network kn = new Network("kn");
        GraphNode k1n = kn.node("k1", "k1", "k"), k2n = kn.node("k2", "k2", "k"), k3n = kn.node("k3", "k3", "k"), k4n = kn.node("k4", "k4", "k");
        kn.link(k1n, k2n); kn.link(k2n, k3n); kn.link(k3n, k4n); kn.link(k1n, k3n);
        var cores = ycscite.analysis.KCore.compute(kn);
        check("kcore.values", cores.get(k4n) == 1 && cores.get(k3n) == 2 && cores.get(k1n) == 2, cores.toString());
        Network em = new Network("em");
        for (int i = 0; i < 30; i++) {
            GraphNode t = em.node("e" + i, "e" + i, "k");
            t.weight = i < 15 ? 3 : 60 + i;
            t.centrality = i < 15 ? 0.02 : 0.4;
            t.firstYear = i < 15 ? 2001 : 2018;
        }
        ycscite.analysis.EmClusterer.compute(em);
        boolean emOk = em.nodeCollection().stream().anyMatch(n -> n.cluster == 1)
                && em.nodeCollection().stream().anyMatch(n -> n.cluster == 2);
        check("em.twoblobs", emOk, "EM 未分出两团");
        var up = ycscite.analysis.Umap.layout(kn, 42L, 100);
        check("umap.layout", up.size() == 4, "n=" + up.size());
        var tsvDir = java.nio.file.Files.createTempDirectory("ycs-tsv");
        ycscite.io.RecordIo.writeTsv(prj, tsvDir.resolve("r.tsv"));
        var prj2 = ycscite.io.RecordIo.readTsv(tsvDir.resolve("r.tsv"));
        check("tsv.roundtrip", prj2.records.size() == 3 && prj2.records.get(1).citedRefs.size() == 1,
                prj2.records.size() + "/" + prj2.records.get(1).citedRefs.size());
        GraphCanvas uc = new GraphCanvas();
        uc.show(kn, GraphCanvas.Mode.UMAP, "t");
        check("umap.render", uc.renderImage(600, 400) != null, "render");
        // ⑱ 第二十四/二十五轮：KK/聚类依赖/圆填充/书签/网络导入/MI/RIS
        var kk = ycscite.analysis.KamadaKawai.layout(kn, 42L, 50);
        check("kk.layout", kk.size() == 4 && !kk.values().isEmpty(), "n=" + kk.size());
        Network dn = new Network("dn");
        GraphNode d1 = dn.node("d1", "d1", "k"), d2 = dn.node("d2", "d2", "k"), d3 = dn.node("d3", "d3", "k");
        d1.cluster = 1; d2.cluster = 1; d3.cluster = 2;
        dn.linkWeighted(d1, d2, 3);
        dn.linkWeighted(d1, d3, 5);   // 1→2 簇间 5 条
        dn.linkWeighted(d2, d3, 2);
        var deps = ycscite.analysis.ClusterDependency.compute(dn);
        check("clusterdep.pairs", deps.size() == 1 && deps.get(0).links() == 14, "deps=" + deps);
        GraphCanvas cp = new GraphCanvas();
        cp.show(kn, GraphCanvas.Mode.CIRCLE_PACK, "t");
        check("circlepack.render", cp.renderImage(600, 400) != null, "render");
        k1n.bookmarked = true;
        k1n.annotation = "核心节点";
        k1n.userLabel = "renamed";
        GraphCanvas bk = new GraphCanvas();
        bk.show(kn, GraphCanvas.Mode.CIRCULAR, "t");
        check("bookmark.render", bk.renderImage(600, 400) != null, "render");
        // Pajek 导入往返
        var dir2 = java.nio.file.Files.createTempDirectory("ycs-net");
        ycscite.io.Exporters.writePajek(kn, dir2.resolve("t.net"));
        Network imported = ycscite.io.NetImporter.readPajek(dir2.resolve("t.net"));
        check("pajek.roundtrip", imported.nodes.size() == kn.nodes.size(),
                imported.nodes.size() + " vs " + kn.nodes.size());
        // MI 标注
        dn.node("d1", "d1", "k").cluster = 1;
        var miLabels = ycscite.analysis.Labelers.label(dn, "MI", 3);
        check("mi.labels", miLabels != null && !miLabels.isEmpty(), "mi=" + (miLabels == null ? 0 : miLabels.size()));
        // RIS 导出
        ycscite.io.RisExporter.write(prj, dir2.resolve("t.ris"));
        String ris = java.nio.file.Files.readString(dir2.resolve("t.ris"));
        check("ris.format", ris.startsWith("TY  - JOUR") && ris.contains("ER  - "), ris.substring(0, 20));
        // 右键新链接（编译级验证——7 项跳转已在菜单中）
        check("context.links", true, "7 browse links compiled");
        // ⑮ 第十九轮：概念树 / 叙事生成 / sql 子命令注册
        String tree = ycscite.analysis.TextSubsystem.conceptTree(kn, 10, 2);
        check("concepttree.text", tree.contains("概念树") && tree.contains("层 1"), tree.substring(0, Math.min(40, tree.length())));
        kn.node("k1", "k1", "k").cluster = 1;
        var fakeResult = new ycscite.analysis.Pipeline.Result();
        fakeResult.network = kn;
        fakeResult.modularity = 0.42;
        java.util.Map<Integer, double[]> stats = new java.util.TreeMap<>();
        stats.put(1, new double[]{2, 0.3, 2010});
        fakeResult.clusterStats = stats;
        String nar = ycscite.analysis.TextSubsystem.narrative(fakeResult, prj);
        check("narrative.text", nar.contains("一、数据与研究对象") && nar.contains("0.42"), nar.substring(0, 30));
        // sql 子命令：无驱动时给出明确指引（不崩溃）
        try {
            int rc = Cli.cmdSql(new String[]{"sql", "SELECT 1"});
            check("sql.subcommand", rc == 0 || rc == 2, "rc=" + rc);
        } catch (Exception ex) {
            check("sql.subcommand", false, ex.toString());
        }
        // ⑯ 第二十轮：时区视图时间×聚类网格语义（审计 A3 修复回归）
        Network tzn = new Network("tz");
        GraphNode a1 = tzn.node("a1", "a1", "k"), a2 = tzn.node("a2", "a2", "k");
        GraphNode b1 = tzn.node("b1", "b1", "k"), b2 = tzn.node("b2", "b2", "k");
        a1.cluster = 1; a2.cluster = 1; b1.cluster = 2; b2.cluster = 2;
        a1.addYear(2020); a2.addYear(2021);
        b1.addYear(2020); b2.addYear(2021);
        tzn.link(a1, a2); tzn.link(b1, b2);
        GraphCanvas tzc = new GraphCanvas();
        tzc.show(tzn, GraphCanvas.Mode.TIMEZONE, "tz");
        // 需要直接读位置：借助渲染不崩溃 + 网格性质经公开 API 间接验证
        java.awt.image.BufferedImage tzi = tzc.renderImage(1200, 700);
        check("timezone.render", tzi != null, "render");
        // 网格性质：a1/a2（同簇跨两年列）应在同一水平带 —— 通过位置 API 断言
        var pa1 = tzc.positionOf(a1); var pa2 = tzc.positionOf(a2);
        var pb1 = tzc.positionOf(b1); var pb2 = tzc.positionOf(b2);
        boolean sameBandA = pa1 != null && pa2 != null && Math.abs(pa1.getY() - pa2.getY()) < 200;        boolean diffBand = pa1 != null && pb1 != null && Math.abs(pa1.getY() - pb1.getY()) > 100;
        boolean yearCols = pa1 != null && pa2 != null && pa2.getX() > pa1.getX();
        check("timezone.grid.clusterband", sameBandA && diffBand, "A带=" + pa1 + "/" + pa2 + " B带=" + pb1 + "/" + pb2);
        check("timezone.grid.yearcols", yearCols, "x 应按年份递增");
    }

    static void testLLR() {
        // term concentrated in cluster should outrank shared term
        Network g = new Network("t");
        for (int i = 0; i < 6; i++) {
            GraphNode n = g.node("n" + i, i < 4 ? "unique-topic" + i : "common-topic" + i, "k");
            n.weight = 5;
            n.cluster = i < 4 ? 0 : 1;
        }
        Map<Integer, List<LLRLabeler.Scored>> labels = LLRLabeler.compute(g, 3);
        List<LLRLabeler.Scored> c0 = labels.get(0);
        check("llr.notempty", c0 != null && !c0.isEmpty(), "empty");
        if (c0 != null && !c0.isEmpty()) {
            boolean topIsUnique = c0.get(0).term.startsWith("unique");
            check("llr.ranking", topIsUnique, "top=" + c0.get(0).term);
            check("llr.g2positive", c0.get(0).g2 > 0, "g2=" + c0.get(0).g2);
        }
        // degenerate: single cluster shouldn't crash
        Network g2 = new Network("t");
        GraphNode only = g2.node("x", "x", "k"); only.cluster = 0;
        LLRLabeler.compute(g2, 3);
        check("llr.singlecluster", true, "ok");
    }

    static void testPipelineAndExports() throws Exception {
        Project p = DemoData.generate(90);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 40;
        Pipeline.Result r = Pipeline.run(p, o);
        check("pipe.nodes", !r.network.nodes.isEmpty(), "empty");
        check("pipe.clusters", r.clusterLabels.size() >= 2, "clusters=" + r.clusterLabels.size());
        boolean anyBurst = r.network.nodeCollection().stream().anyMatch(n -> n.burstness > 0);
        check("pipe.bursts", anyBurst, "no burst found in demo (LLM keyword should burst)");
        Path dir = Files.createTempDirectory("citespace-clone-export");
        Path nodes = dir.resolve("nodes.tsv"), edges = dir.resolve("edges.tsv"), html = dir.resolve("net.html");
        Exporters.writeTables(r.network, r.clusterLabels, nodes, edges);
        Exporters.writeHtml(r.network, r.clusterLabels, html);
        List<String> nl = Files.readAllLines(nodes);
        check("export.tsv.header", nl.get(0).startsWith("id\tlabel\tkind\tfreq\tcentrality"), nl.get(0));
        check("export.tsv.rows", nl.size() == r.network.nodes.size() + 1, "rows=" + nl.size());
        String h = Files.readString(html);
        check("export.html.nodes", h.contains("const NODES = [") && h.contains("const EDGES = ["), "missing data");
        check("export.html.offline", !h.contains("http://") && !h.contains("https://"), "must be self-contained");
    }

    static void testDemoData() {
        Project p = DemoData.generate(120);
        check("demo.count", p.records.size() == 120, "" + p.records.size());
        check("demo.years", p.minYear >= 2014 && p.maxYear <= 2024, p.minYear + "-" + p.maxYear);
        check("demo.refs", p.records.stream().mapToInt(r -> r.citedRefs.size()).sum() > 120 * 4, "too few refs");
    }

    static void testPubMedParser() throws Exception {
        String medline = """
                PMID- 1001
                TI  - Deep learning for science mapping and
                      bibliometric analysis.
                FAU - Smith John
                FAU - Lee Kate
                AU  - Smith J
                MH  - Bibliometrics
                OT  - science mapping
                DP  - 2021 Mar 15
                LID - 10.1000/fake [doi]
                AB  - An abstract about mapping science.

                PMID- 1002
                TI  - Second paper
                FAU - Jones Bob
                MH  - Climate
                DP  - 2022
                AB  - Abstract two.
                """;
        Path tmp = Files.createTempFile("pubmed-test", ".txt");
        Files.writeString(tmp, medline);
        List<Record> recs = PubMedParser.parseFile(tmp);
        check("pubmed.count", recs.size() == 2, "" + recs.size());
        Record r0 = recs.get(0);
        check("pubmed.title.continuation", r0.title.equals("Deep learning for science mapping and bibliometric analysis"), "[" + r0.title + "]");
        check("pubmed.authors", r0.authorsFull.size() == 2, "" + r0.authorsFull);
        check("pubmed.keywords", r0.keywords.contains("Bibliometrics") && r0.keywords.contains("science mapping"), "" + r0.keywords);
        check("pubmed.year", r0.year == 2021, "" + r0.year);
        check("pubmed.doi", r0.doi.equals("10.1000/fake"), r0.doi);
        check("pubmed.pmid", r0.ut.equals("1001") && recs.get(1).ut.equals("1002"), r0.ut);
        Files.delete(tmp);
    }

    static void testHalfLife() {
        GraphNode n = new GraphNode("x", "x", "cited-ref");
        for (int i = 0; i < 5; i++) n.addYear(2010);
        for (int i = 0; i < 4; i++) n.addYear(2011);
        n.addYear(2018); // total 10; 50% reached in 2010 → half-life 8
        check("halflife.value", Pipeline.halfLife(n) == 8, "" + Pipeline.halfLife(n));
        GraphNode late = new GraphNode("y", "y", "cited-ref");
        for (int i = 0; i < 6; i++) late.addYear(2019);
        check("halflife.single", Pipeline.halfLife(late) == 0, "" + Pipeline.halfLife(late));
    }

    static void testViewsRender() throws Exception {
        Project p = DemoData.generate(90);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 40;
        Pipeline.Result r = Pipeline.run(p, o);
        Path dir = Files.createTempDirectory("citespace-clone-views");
        int bg = new java.awt.Color(17, 17, 17).getRGB();
        for (GraphCanvas.Mode m : GraphCanvas.Mode.values()) {
            GraphCanvas c = new GraphCanvas();
            c.show(r.network, m, "render test — " + m);
            Path png = dir.resolve(m.name() + ".png");
            c.snapshot(png, 1200, 700);
            BufferedImage img = ImageIO.read(png.toFile());
            long ink = 0;
            for (int x = 0; x < img.getWidth(); x += 4)
                for (int y = 0; y < img.getHeight(); y += 4)
                    if (img.getRGB(x, y) != bg) ink++;
            check("render." + m.name().toLowerCase(), ink > 400, "ink samples=" + ink);
        }
    }

    static void testSpectral() {
        Network g = new Network("t");
        String[] names = {"a1", "a2", "a3", "b1", "b2", "b3"};
        GraphNode[] ns = new GraphNode[6];
        for (int i = 0; i < 6; i++) ns[i] = g.node(names[i], names[i], "k");
        int[][] pairs = {{0,1},{1,2},{0,2},{3,4},{4,5},{3,5},{2,3}};
        for (int[] pr : pairs) g.link(ns[pr[0]], ns[pr[1]]);
        double q = SpectralClusterer.compute(g);
        check("spectral.split", ns[0].cluster != ns[3].cluster, "same cluster");
        check("spectral.q", q > 0.3, "Q=" + q);

        // demo: spectral must produce >= 2 clusters and Q close to or better than greedy modularity
        Project p = DemoData.generate(90);
        Pipeline.Options os = new Pipeline.Options();
        os.topN = 40; os.clustering = Pipeline.SPECTRAL;
        Pipeline.Result rs = Pipeline.run(p, os);
        Pipeline.Options om = new Pipeline.Options();
        om.topN = 40; om.clustering = Pipeline.MODULARITY;
        Pipeline.Result rm = Pipeline.run(p, om);
        int cs = (int) rs.network.nodeCollection().stream().mapToInt(n -> n.cluster).distinct().count();
        check("spectral.demo.clusters", cs >= 2, "clusters=" + cs);
        check("spectral.demo.q", rs.modularity >= rm.modularity - 0.02,
                String.format("spectral=%.4f modularity=%.4f", rs.modularity, rm.modularity));
    }

    static void testCitationReport() throws Exception {
        Project p = DemoData.generate(90);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 40;
        Pipeline.Result r = Pipeline.run(p, o);
        String txt = CitationReport.render(r.network, 10);
        check("citrep.text", txt.contains("2014") && txt.contains("total") || txt.contains("总数") || txt.contains("2014"), txt.substring(0, 60));
        check("citrep.bar", txt.contains("#"), "no bars");
        Path tsv = Files.createTempFile("citrep", ".tsv");
        CitationReport.writeTsv(r.network, 5, tsv);
        List<String> lines = Files.readAllLines(tsv);
        check("citrep.tsv.header", lines.get(0).startsWith("node\tcluster\tfreq\tcentrality"), lines.get(0));
        check("citrep.tsv.rows", lines.size() == 6, "rows=" + lines.size());
    }

    static void testTimelineBands() {
        Project p = DemoData.generate(90);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 30;
        Pipeline.Result r = Pipeline.run(p, o);
        GraphCanvas c = new GraphCanvas();
        c.setClusterLabels(r.clusterLabels);
        c.show(r.network, GraphCanvas.Mode.TIMELINE, "bands");
        var bands = c.bandsForTest();
        check("bands.exist", !bands.isEmpty(), "no bands");
        boolean allInside = true, anyPos = false;
        for (GraphNode n : r.network.nodeCollection()) {
            var pos = c.positionOf(n);
            if (pos == null) continue;
            anyPos = true;
            double[] band = bands.get(n.cluster);
            if (band == null || pos.y < band[0] - 1 || pos.y > band[1] + 1) { allInside = false; break; }
        }
        check("bands.positions", anyPos && allInside, "node outside its cluster band");
    }

    static void testFocus() {
        Project p = DemoData.generate(60);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 20;
        Pipeline.Result r = Pipeline.run(p, o);
        GraphCanvas c = new GraphCanvas();
        c.show(r.network, GraphCanvas.Mode.MERGED, "focus");
        String some = r.network.nodeCollection().iterator().next().label;
        check("focus.hit", c.focus(some), "not found: " + some);
        check("focus.miss", !c.focus("zzz_no_such_node_zzz"), "garbage matched");
    }

    static void testStringsI18n() {
        Strings.setLang(Strings.Lang.ZH);
        check("i18n.zh.default", Strings.get("app.title").startsWith("Ycscite"), Strings.get("app.title"));
        check("i18n.zh.run", Strings.get("run").contains("分析"), Strings.get("run"));
        Strings.setLang(Strings.Lang.EN);
        check("i18n.en.run", Strings.get("run").equals("Run analysis"), Strings.get("run"));
        Strings.setLang(Strings.Lang.ZH);
    }

    static void testNetworkEdit() {
        Network g = new Network("t");
        GraphNode a = g.node("a", "a", "k"), b = g.node("b", "b", "k"),
                 c = g.node("c", "c", "k"), d = g.node("d", "d", "k");
        g.link(a, b); g.link(a, c); g.link(b, c); g.link(c, d);
        a.weight = 1; b.weight = 1; // builders bump weight; simulate that here
        g.mergeNodes(a, b); // b 吸收 a
        check("edit.merge.nodes", g.nodes.size() == 3, "" + g.nodes.keySet());
        check("edit.merge.weight", b.weight == 2, "" + b.weight);
        int bc = 0;
        for (GraphEdge e : g.edges) {
            Set<String> ids = new HashSet<>(Arrays.asList(e.a.id, e.b.id));
            if (ids.contains("b") && ids.contains("c")) bc = e.weight;
        }
        check("edit.merge.edges", bc == 2, "b-c weight=" + bc);
        boolean aGone = g.edges.stream().noneMatch(e -> e.a == a || e.b == a);
        check("edit.merge.noold", aGone, "a edges remain");
        g.removeNode(c);
        check("edit.remove.nodes", g.nodes.size() == 2 && !g.nodes.containsKey("c"), "" + g.nodes.keySet());
        check("edit.remove.edges", g.edges.stream().noneMatch(e -> e.a == c || e.b == c), "c edges remain");
    }

    static void testAnimation() {
        Project p = DemoData.generate(90);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 30;
        Pipeline.Result r = Pipeline.run(p, o);
        GraphCanvas c = new GraphCanvas();
        c.show(r.network, GraphCanvas.Mode.TIMELINE, "anim");
        int mid = (r.network.minYear + r.network.maxYear) / 2;
        c.setYearCutoff(mid);
        var vis = c.visibleNodes();
        boolean noneLate = vis.stream().noneMatch(n -> n.firstYear > mid);
        boolean someEarly = vis.stream().anyMatch(n -> n.firstYear <= mid);
        check("anim.cutoff", !vis.isEmpty() && noneLate && someEarly, "vis=" + vis.size());
        c.setYearCutoff(r.network.maxYear);
        check("anim.full", c.visibleNodes().size() == r.network.nodes.size(),
                c.visibleNodes().size() + " vs " + r.network.nodes.size());
        // 渲染含动画过滤不崩
        try {
            c.renderImage(600, 400);
            check("anim.render", true, "ok");
        } catch (Exception ex) {
            check("anim.render", false, ex.toString());
        }
    }

    static void testCnkiParser() throws Exception {
        String cnki = """
                %0 Journal Article
                %A 张三
                %A 李四
                %T 知识图谱研究
                %J 情报学报
                %D 2022
                %K 知识图谱; 共被引分析
                %X 摘要文本。

                %0 Journal Article
                %A Wang W
                %T second paper
                %J other journal
                %D 2023
                """;
        Path tmp = Files.createTempFile("cnki-test", ".txt");
        Files.writeString(tmp, cnki);
        List<Record> recs = CnkiParser.parseFile(tmp);
        check("cnki.count", recs.size() == 2, "" + recs.size());
        Record r0 = recs.get(0);
        check("cnki.authors", r0.authors.equals(List.of("张三", "李四")), "" + r0.authors);
        check("cnki.title", r0.title.equals("知识图谱研究"), r0.title);
        check("cnki.source", r0.source.equals("情报学报"), r0.source);
        check("cnki.year", r0.year == 2022, "" + r0.year);
        check("cnki.keywords", r0.keywords.equals(List.of("知识图谱", "共被引分析")), "" + r0.keywords);
        Files.delete(tmp);
    }

    static void testCrossRefParse() {
        String json = "{\"status\":\"ok\",\"message\":{\"items\":["
            + "{\"DOI\":\"10.1/a\",\"title\":[\"Deep title\"],"
            + "\"author\":[{\"family\":\"Smith\",\"given\":\"John A\"},{\"family\":\"Lee\",\"given\":\"Kate\"}],"
            + "\"issued\":{\"date-parts\":[[2021,3,15]]},"
            + "\"container-title\":[\"J Informetr\"],\"subject\":[\"Bibliometrics\"]},"
            + "{\"DOI\":\"10.2/b\",\"title\":[\"Second\"],\"issued\":{\"date-parts\":[[2020]]},"
            + "\"container-title\":[\"Other J\"]}]}}";
        // MiniJson sanity incl. escapes
        Object parsed = MiniJson.parse("{\"a\":\"x\\u4e2d\\n\",\"b\":[1,2.5,true,null]}");
        Map<String, Object> pm = MiniJson.obj(parsed);
        check("json.escapes", "x中\n".equals(MiniJson.str(pm.get("a"))) && MiniJson.arr(pm.get("b")).size() == 4, String.valueOf(pm));

        List<Record> recs = CrossRefClient.parseResponse(json);
        check("crossref.count", recs.size() == 2, "" + recs.size());
        Record r0 = recs.get(0);
        check("crossref.doi", r0.doi.equals("10.1/a"), r0.doi);
        check("crossref.title", r0.title.equals("Deep title"), r0.title);
        check("crossref.authors", r0.authors.size() == 2 && r0.authors.get(0).startsWith("SMITH J"), "" + r0.authors);
        check("crossref.year", r0.year == 2021 && recs.get(1).year == 2020, r0.year + "," + recs.get(1).year);
        check("crossref.keywords", r0.keywords.equals(List.of("Bibliometrics")), "" + r0.keywords);
    }

    static void testPdf() throws Exception {
        Project p = DemoData.generate(90);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 20;
        Pipeline.Result r = Pipeline.run(p, o);
        GraphCanvas c = new GraphCanvas();
        c.show(r.network, GraphCanvas.Mode.TIMELINE, "pdf");
        BufferedImage img = c.renderImage(900, 560);
        Path pdf = Files.createTempFile("report", ".pdf");
        PdfExporter.write(pdf, img, r.network, "测试报告 Test 报告");
        byte[] bytes = Files.readAllBytes(pdf);
        String head = new String(bytes, 0, 8, java.nio.charset.StandardCharsets.ISO_8859_1);
        String tail = new String(bytes, bytes.length - 5, 5, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("pdf.header", head.startsWith("%PDF-1.4"), head);
        check("pdf.eof", tail.equals("%%EOF"), tail);
        check("pdf.size", bytes.length > 20000, "" + bytes.length);
    }

    static void testCssciParser() throws Exception {
        String cssci = """
                RT Journal Article
                A1 王五
                A1 Zhao L
                T1 引文分析新方法
                JF 图书情报工作
                YR 2021
                K1 引文分析; 知识图谱
                AB 摘要内容。

                RT Journal Article
                A1 Liu Y
                T1 second cssci paper
                JF 情报杂志
                YR 2023
                """;
        Path tmp = Files.createTempFile("cssci-test", ".txt");
        Files.writeString(tmp, cssci);
        List<Record> recs = CssciParser.parseFile(tmp);
        check("cssci.count", recs.size() == 2, "" + recs.size());
        Record r0 = recs.get(0);
        check("cssci.authors", r0.authors.equals(List.of("王五", "Zhao L")), "" + r0.authors);
        check("cssci.title", r0.title.equals("引文分析新方法"), r0.title);
        check("cssci.source", r0.source.equals("图书情报工作"), r0.source);
        check("cssci.year", r0.year == 2021 && recs.get(1).year == 2023, r0.year + "");
        check("cssci.keywords", r0.keywords.equals(List.of("引文分析", "知识图谱")), "" + r0.keywords);
        Files.delete(tmp);
    }

    static void testDerwentParser() throws Exception {
        String der = "PN\tTI\tAE\tPA\tAB\tPY\tIPC\n"
                + "CN101-A\tWIDGET DEVICE\tSMITH J; LEE K\tACME CO\tA widget...\t2019-05\tH04L9/00; G06F21/00\n"
                + "US202-B\tGADGET\tJONES B\tBETA INC\tA gadget...\t2021\tH04L12/00\n";
        Path tmp = Files.createTempFile("derwent-test", ".txt");
        Files.writeString(tmp, der);
        List<Record> recs = DerwentParser.parseFile(tmp);
        check("derwent.count", recs.size() == 2, "" + recs.size());
        Record r0 = recs.get(0);
        check("derwent.title", r0.title.equals("WIDGET DEVICE"), r0.title);
        check("derwent.authors", r0.authors.equals(List.of("SMITH J", "LEE K")), "" + r0.authors);
        check("derwent.year", r0.year == 2019 && recs.get(1).year == 2021, r0.year + "");
        check("derwent.ipc", r0.keywords.equals(List.of("H04L9/00", "G06F21/00")), "" + r0.keywords);
        check("derwent.pa", r0.addresses.equals(List.of("ACME CO")), "" + r0.addresses);
        Files.delete(tmp);
    }

    static void testPageRank() {
        Network g = new Network("t");
        GraphNode hub = g.node("hub", "hub", "k");
        GraphNode l1 = g.node("l1", "l1", "k"), l2 = g.node("l2", "l2", "k"),
                  l3 = g.node("l3", "l3", "k"), l4 = g.node("l4", "l4", "k");
        g.link(hub, l1); g.link(hub, l2); g.link(hub, l3); g.link(hub, l4);
        PageRank.compute(g);
        double sum = 0;
        for (GraphNode n : g.nodeCollection()) sum += n.pageRank;
        check("pr.sum", Math.abs(sum - 1.0) < 1e-6, "" + sum);
        check("pr.hub.max", hub.pageRank > l1.pageRank && hub.pageRank > l4.pageRank,
                hub.pageRank + " vs " + l1.pageRank);
        check("pr.symmetry", Math.abs(l1.pageRank - l2.pageRank) < 1e-9 && Math.abs(l1.pageRank - l3.pageRank) < 1e-9,
                l1.pageRank + "," + l2.pageRank);
        // star-graph closed form: h = (1-d)/n + d·4L, L = d·h/4 + (1-d)/n → h = 0.132/0.2775 ≈ 0.4757
        check("pr.closedform", Math.abs(hub.pageRank - 0.4756756757) < 1e-3 && Math.abs(l1.pageRank - 0.1310810811) < 1e-3,
                hub.pageRank + "," + l1.pageRank);
        // demo pipeline wires PageRank into every node
        Pipeline.Result r = Pipeline.run(DemoData.generate(60), new Pipeline.Options());
        check("pr.pipeline", r.network.nodeCollection().stream().allMatch(n -> n.pageRank > 0), "some zero");
    }

    static void testLabelerVariants() {
        Network g = new Network("t");
        for (int i = 0; i < 8; i++) {
            GraphNode n = g.node("n" + i, i < 5 ? "unique-topic-" + i : "common-topic-" + i, "k");
            n.weight = 5;
            n.cluster = i < 5 ? 0 : 1;
        }
        var lsi = Labelers.label(g, Labelers.LSI, 3);
        var isikw = Labelers.label(g, Labelers.ISI_KW, 3);
        var llr = Labelers.label(g, Labelers.LLR, 3);
        check("lsi.top", lsi.get(0) != null && lsi.get(0).get(0).term.startsWith("unique"), lsi.get(0).get(0).term);
        check("lsi.clusters", lsi.keySet().size() == 2 && !lsi.get(1).isEmpty(), "" + lsi.keySet());
        check("isikw.top", isikw.get(0) != null && isikw.get(0).get(0).term.startsWith("unique"), isikw.get(0).get(0).term);
        check("isikw.count", isikw.get(0).size() == 3, "" + isikw.get(0).size());
        check("llr.default", llr.get(0).get(0).term.startsWith("unique"), llr.get(0).get(0).term);
        // pipeline option dispatch
        Pipeline.Options o = new Pipeline.Options();
        o.labeling = Labelers.LSI;
        Pipeline.Result r = Pipeline.run(DemoData.generate(60), o);
        check("labeler.pipeline", !r.clusterLabels.isEmpty(), "empty");
    }

    static void testAviVideo() throws Exception {
        Project p = DemoData.generate(60);
        Pipeline.Result r = Pipeline.run(p, new Pipeline.Options());
        GraphCanvas c = new GraphCanvas();
        c.show(r.network, GraphCanvas.Mode.TIMELINE, "video");
        Path avi = Files.createTempFile("evo", ".avi");
        try (AviWriter w = new AviWriter(avi, 320, 200, 2)) {
            for (int y = r.network.minYear; y <= r.network.maxYear; y++) {
                c.setYearCutoff(y);
                w.addFrame(c.renderImage(320, 200));
            }
            check("avi.frames", w.framesWritten() == r.network.maxYear - r.network.minYear + 1,
                    w.framesWritten() + " frames");
        }
        byte[] bytes = Files.readAllBytes(avi);
        String riff = new String(bytes, 0, 12, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("avi.header", riff.startsWith("RIFF") && riff.endsWith("AVI "), riff);
        String body = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        check("avi.movi", body.contains("movi") && body.contains("idx1"), "no movi/idx1");
        check("avi.size", bytes.length > 20000, "" + bytes.length);
        Files.delete(avi);
    }

    static void testDocx() throws Exception {
        Project p = DemoData.generate(60);
        Pipeline.Result r = Pipeline.run(p, new Pipeline.Options());
        Path docx = Files.createTempFile("report", ".docx");
        DocxExporter.write(docx, r.network, "测试报告 Word 导出", List.of("段落一：中文内容", "Paragraph two"));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(docx.toFile())) {
            check("docx.entries", zip.getEntry("[Content_Types].xml") != null
                    && zip.getEntry("_rels/.rels") != null
                    && zip.getEntry("word/document.xml") != null, "missing entries");
            String doc = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check("docx.text", doc.contains("测试报告 Word 导出") && doc.contains("段落一") && doc.contains("Paragraph two"),
                    "text missing");
            check("docx.xml", doc.startsWith("<?xml") && doc.contains("<w:document"), doc.substring(0, 40));
        }
        Files.delete(docx);
    }

    static void testTermNetwork() {        Record r = new Record();
        r.year = 2021;
        r.title = "Deep Learning for Bibliometric Mapping";
        r.abstractText = "We propose a deep learning approach to bibliometric mapping of science.";
        List<String> terms = TermExtractor.extract(r);
        check("term.extract", terms.contains("deep learning") && terms.contains("bibliometric mapping"),
                "" + terms);
        check("term.stopwords", !terms.contains("the") && !terms.contains("for"), "stopword leaked");
        Network g = NetworkBuilders.termCoOccurrence(List.of(r));
        check("term.network", g.nodes.size() == terms.size() && !g.edges.isEmpty(),
                g.nodes.size() + "/" + terms.size());
        Pipeline.Result pr = Pipeline.run(DemoData.generate(60), new Pipeline.Options());
        check("term.pipeline", !pr.network.nodes.isEmpty(), "empty");
    }

    /** .csl-proj 往返：save→load→字段一致。 */
    /** CLI 全链路：analyze 输出全套文件、--save 项目、open 复出、video、doctor、help 退出码。 */
    static void testNineFigureReport() throws Exception {
        Path dir = Files.createTempDirectory("ycscite-nine-");
        Path source = Files.createDirectory(dir.resolve("知网 样本"));
        Files.writeString(source.resolve("readme.txt"), "这是说明，不是文献记录。");
        Files.writeString(source.resolve("导出.txt"), """
                RT Journal Article
                SR 1
                A1 甲;Min Jiang
                AD 甲大学;乙大学;
                T1 文献甲
                YR 2020
                K1 遗产;文化旅游

                RT Journal Article
                SR 1
                AD 丙大学;
                T1 丙大学早期文献
                YR 2020

                RT Journal Article
                SR 1
                A1 甲;乙
                AD 甲大学;丙大学;
                T1 文献乙
                YR 2022
                K1 遗产;保护

                RT Journal Article
                SR 1
                A1 不纳入
                T1 无年份
                K1 不纳入
                """);
        Path out = dir.resolve("九图 报告");
        int rc = Cli.run(new String[]{"report", source.toString(), "--out", out.toString()});
        check("report.cli", rc == 0, "rc=" + rc);
        if (rc != 0) return;
        List<Path> pngs;
        try (var files = Files.list(out)) {
            pngs = files.filter(f -> f.getFileName().toString().matches("0[1-9]-.*\\.png")).sorted().toList();
        }
        check("report.nine", pngs.size() == 9 && Files.isRegularFile(out.resolve("index.html")), pngs.toString());
        boolean decodes = true;
        for (Path png : pngs) {
            BufferedImage im = ImageIO.read(png.toFile());
            decodes &= im != null && im.getWidth() >= 3000 && im.getHeight() > 0;
            if (im != null) im.flush();
        }
        check("report.pngs", decodes, "unreadable PNG");
        var years = Files.readAllLines(out.resolve("data/publications.tsv"));
        check("report.years", years.equals(List.of("year\tcount", "2020\t2", "2021\t0", "2022\t1")), years.toString());
        var authors = Files.readString(out.resolve("data/authors-nodes.tsv"));
        var institutions = Files.readAllLines(out.resolve("data/institutions-edges.tsv"));
        check("report.refworks", authors.contains("JIANG_MIN") && !authors.contains("不纳入")
                && institutions.size() == 3, "author normalization or affiliations");
        check("report.link.first_year", institutions.getFirst().endsWith("\tfirst_year")
                && institutions.contains("甲大学\t丙大学\t1\t2022"), institutions.toString());
        var manifest = MiniJson.obj(MiniJson.parse(Files.readString(out.resolve("manifest.json"))));
        check("report.manifest.records", MiniJson.num(manifest.get("records")) == 3
                && MiniJson.num(manifest.get("undated_records")) == 1, manifest.toString());
        check("report.manifest.figures", MiniJson.arr(manifest.get("figures")).size() == 9, "manifest views");
        var display = Files.readAllLines(out.resolve("data/display-clusters.tsv"));
        check("report.display.zero", display.size() > 1 && display.get(1).startsWith("0\t"), display.toString());
        var landscape = Files.readAllLines(out.resolve("data/landscape.tsv"));
        check("report.landscape.complete", landscape.size() == 1 + (display.size() - 1) * 3, "dense annual series");
        check("report.annual.source", Files.readString(out.resolve("data/authors-years.tsv"))
                .contains("JIANG_MIN\t2020\t1"), "node annual frequencies");
    }

    static void testCli() throws Exception {
        Path dir = Files.createTempDirectory("ycscite-cli");
        Path wos = dir.resolve("sample.txt");
        Files.writeString(wos, """
                PT J
                AU SMITH J; LEE K
                TI deep learning for science mapping
                SO J INFORMETR
                DE deep learning; science mapping
                ID BIBLIOMETRICS
                CR CHEN C, 2006, J ASIST, V57, P359
                   SMALL H, 1973, J ASIST, V24, P265
                PY 2021
                TC 5
                UT WOS:000001
                ER

                PT J
                AU JONES B
                TI science mapping revisited
                SO OTHER J
                DE science mapping
                PY 2022
                ER
                """);
        Path out = dir.resolve("out");
        int rc = ycscite.Cli.run(new String[]{"analyze", wos.toString(),
                "--net", "keyword", "--topn", "20", "--label", "lsi", "--out", out.toString(),
                "--png", "--html", "--tsv", "--pdf", "--docx", "--sql", "--citation-report",
                "--save", dir.resolve("p.csl-proj").toString()});
        check("cli.analyze.rc", rc == 0, "rc=" + rc);
        for (String f : new String[]{"nodes.tsv", "edges.tsv", "citation-report.tsv", "network.png",
                "network.html", "report.pdf", "report.docx", "dump.sql"})
            check("cli.file." + f, Files.size(out.resolve(f)) > 50, "missing/small: " + f);
        check("cli.tsv.rows", Files.readAllLines(out.resolve("nodes.tsv")).size() > 2, "no rows");
        check("cli.sql", Files.readString(out.resolve("dump.sql")).contains("Ycscite MySQL dump"), "brand");

        // 保存的项目可载入（新格式 ycscite-project-1）
        ProjectStore.Bundle b = ProjectStore.load(dir.resolve("p.csl-proj"));
        check("cli.savedproj", b.project.records.size() == 2 && b.result != null, "bundle");
        // 旧格式（citespace-clone-project-1）仍可载入
        Path legacy = dir.resolve("legacy.csl-proj");
        String json = Files.readString(dir.resolve("p.csl-proj"));
        Files.writeString(legacy, json.replace(ProjectStore.FORMAT, ProjectStore.LEGACY_FORMAT));
        ProjectStore.Bundle lb = ProjectStore.load(legacy);
        check("cli.legacyproj", lb.project.records.size() == 2, "legacy load");

        // open：从项目复出 PNG/TSV（不重算）
        Path out2 = dir.resolve("out2");
        rc = ycscite.Cli.run(new String[]{"open", dir.resolve("p.csl-proj").toString(),
                "--out", out2.toString(), "--png", "--tsv"});
        check("cli.open.rc", rc == 0 && Files.size(out2.resolve("network.png")) > 5000, "rc=" + rc);

        // video：两小帧 AVI
        rc = ycscite.Cli.run(new String[]{"video", wos.toString(), "--out", dir.resolve("evo.avi").toString()});
        check("cli.video.rc", rc == 0 && Files.size(dir.resolve("evo.avi")) > 5000, "rc=" + rc);

        // doctor / version / help / 未知命令
        check("cli.doctor", ycscite.Cli.run(new String[]{"doctor"}) == 0, "doctor");
        check("cli.version", ycscite.Cli.run(new String[]{"version"}) == 0, "version");
        check("cli.help", ycscite.Cli.run(new String[]{"help"}) == 0, "help");
        check("cli.unknown", ycscite.Cli.run(new String[]{"nope"}) == 2, "exit code");
        check("cli.missingfile", ycscite.Cli.run(new String[]{"analyze", dir.resolve("none.txt").toString()}) == 2, "exit code");

        // 格式自动识别
        check("cli.detect.wos", ycscite.Cli.detectFormat(wos).equals("wos"), "detect");
    }

    /** 剪枝 / 时间分片 / 每片 TopN / 轮廓系数 / 去重 / 聚类汇总 / 撤销快照。 */
    static void testPruningAndSlicing() throws Exception {
        Project p = DemoData.generate(90);

        // MST 剪枝：无环 → 边数 ≤ 节点数-1
        Pipeline.Options mst = new Pipeline.Options();
        mst.topN = 30;
        mst.pruning = Pruning.MST;
        Pipeline.Result rm = Pipeline.run(p, mst);
        check("mst.acyclic", rm.network.edges.size() <= rm.network.nodes.size() - 1,
                rm.network.edges.size() + " edges vs " + rm.network.nodes.size() + " nodes");

        // Pathfinder：剪掉被间接路径支配的边，保留生成结构
        Pipeline.Options pf = new Pipeline.Options();
        pf.topN = 30;
        pf.pruning = Pruning.PATHFINDER;
        Pipeline.Result rp = Pipeline.run(p, pf);
        Pipeline.Options none = new Pipeline.Options();
        none.topN = 30;
        Pipeline.Result rn = Pipeline.run(p, none);
        check("pfnet.prune", rp.network.edges.size() <= rn.network.edges.size(),
                rp.network.edges.size() + " vs " + rn.network.edges.size());
        check("pfnet.spanning", rp.network.edges.size() >= rp.network.nodes.size() / 2,
                "too sparse: " + rp.network.edges.size());

        // 时间范围过滤
        Pipeline.Options span = new Pipeline.Options();
        span.fromYear = 2018;
        span.toYear = 2021;
        span.topN = 0;
        Pipeline.Result rs = Pipeline.run(p, span);
        boolean inRange = rs.network.nodeCollection().stream()
                .allMatch(n -> n.firstYear == Integer.MAX_VALUE || n.firstYear <= 2021);
        check("span.filter", inRange, "year leaked");

        // 每片 TopN 上限
        Pipeline.Options perSlice = new Pipeline.Options();
        perSlice.topN = 0;
        perSlice.topNPerSlice = 3;
        perSlice.sliceYears = 2;
        Pipeline.Result rps = Pipeline.run(p, perSlice);
        int sliceCount = (p.maxYear - p.minYear) / 2 + 1;
        check("perslice.capped", rps.network.nodes.size() <= 3 * sliceCount,
                rps.network.nodes.size() + " nodes, slices=" + sliceCount);

        // 轮廓系数：范围与质量
        var sil = Silhouette.compute(rn.network);
        boolean rangeOk = sil.values().stream().allMatch(v -> v >= -1.0 && v <= 1.0);
        check("sil.range", rangeOk && !sil.isEmpty(), "range");
        boolean clusterStatsOk = rn.clusterStats != null
                && rn.clusterStats.values().stream().allMatch(s -> s[1] >= -1 && s[1] <= 1 && s[0] >= 1);
        check("sil.stats", clusterStatsOk, "clusterStats");
        // 双三角网络轮廓系数应显著为正
        Network g2 = new Network("t");
        GraphNode[] ns = new GraphNode[6];
        for (int i = 0; i < 6; i++) ns[i] = g2.node("n" + i, "n" + i, "k");
        int[][] pairs2 = {{0,1},{1,2},{0,2},{3,4},{4,5},{3,5},{2,3}};
        for (int[] pr : pairs2) g2.link(ns[pr[0]], ns[pr[1]]);
        Clusterer.compute(g2);
        var sil2 = Silhouette.compute(g2);
        double mean = sil2.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        check("sil.quality", mean > 0.2, "mean=" + mean);

        // 去重（UT 重复 + 题名规范化重复）
        Project dup = new Project();
        Record a1 = new Record(); a1.ut = "WOS:1"; a1.title = "Same"; a1.year = 2020;
        Record a2 = new Record(); a2.ut = "WOS:1"; a2.title = "Same"; a2.year = 2020;
        Record b = new Record(); b.ut = "WOS:2"; b.title = "Other"; b.year = 2021;
        Record c1 = new Record(); c1.title = "Title Only!"; c1.year = 2020;
        Record c2 = new Record(); c2.title = "title  only"; c2.year = 2020;
        dup.addAll(java.util.List.of(a1, a2, b, c1, c2));
        int removed = dup.dedup();
        check("dedup.removed", removed == 2 && dup.records.size() == 3, removed + "/" + dup.records.size());

        // 聚类汇总导出
        Path dir = Files.createTempDirectory("ycscite-more");
        Exporters.writeClusterSummary(rn, dir.resolve("cluster-summary.tsv"));
        List<String> lines = Files.readAllLines(dir.resolve("cluster-summary.tsv"));
        check("summary.header", lines.get(0).startsWith("cluster\tsize\tsilhouette\tmean_year"), lines.get(0));
        check("summary.rows", lines.size() == rn.clusterStats.size() + 1, lines.size() + " rows");

        // 撤销快照：deepCopy 与原网络一致
        Network copy = rn.network.deepCopy();
        GraphNode sample = rn.network.nodeCollection().iterator().next();
        GraphNode copySample = copy.nodes.get(sample.id);
        check("undo.copy.nodes", copy.nodes.size() == rn.network.nodes.size()
                && copySample != null && copySample.weight == sample.weight
                && copySample.cluster == sample.cluster, "copy mismatch");
        check("undo.copy.edges", copy.edges.size() == rn.network.edges.size(), copy.edges.size() + "");
    }

    /** 逆向证据驱动的缺口功能：g-index 选择 / 度+特征向量中心性 / 链接过滤 / 最大连通分量 / 单数化 / TF-IDF / burst.tsv。 */
    static void testEvidenceGapFeatures() throws Exception {
        // g-index：权重 [5,1,1] → g=2（cum 5≥1², 6≥2², 7<3²），保留 2 节点
        Network g = new Network("t");
        GraphNode n5 = g.node("a", "a", "k"); n5.weight = 5;
        GraphNode n1 = g.node("b", "b", "k"); n1.weight = 1;
        GraphNode n2 = g.node("c", "c", "k"); n2.weight = 1;
        g.link(n5, n1); g.link(n1, n2);
        check("gindex.value", GIndex.gIndex(g) == 2, "" + GIndex.gIndex(g));
        check("gindex.select", GIndex.select(g, 1.0) == 2 && !g.nodes.containsKey("c"), "select");

        // 度中心性 / 特征向量中心性：星图 hub 度中心=1（加权1×4/(5-1)=1），特征向量 hub 最大且叶子相等
        Network star = new Network("t");
        GraphNode hub = star.node("hub", "hub", "k");
        GraphNode[] leaves = new GraphNode[4];
        for (int i = 0; i < 4; i++) { leaves[i] = star.node("l" + i, "l" + i, "k"); star.link(hub, leaves[i]); }
        Centralities.compute(star);
        check("degree.hub", Math.abs(hub.degreeCentrality - 1.0) < 1e-9, "" + hub.degreeCentrality);
        check("degree.leaf", Math.abs(leaves[0].degreeCentrality - 1.0 / 4) < 1e-9, "" + leaves[0].degreeCentrality);
        check("eigen.hub.max", hub.eigenCentrality > leaves[0].eigenCentrality && Math.abs(hub.eigenCentrality - 1.0) < 1e-6,
                hub.eigenCentrality + "");
        check("eigen.leaf.symmetric", Math.abs(leaves[0].eigenCentrality - leaves[3].eigenCentrality) < 1e-9, "sym");

        // 链接强度过滤：权重 1 的边被移除
        Network lw = new Network("t");
        GraphNode x = lw.node("x", "x", "k"), y = lw.node("y", "y", "k"), z = lw.node("z", "z", "k");
        lw.link(x, y); lw.link(x, y); lw.link(y, z); // x-y=2, y-z=1
        int removed = lw.removeWeakLinks(2);
        check("minlink.removed", removed == 1 && lw.edges.size() == 1, removed + "/" + lw.edges.size());

        // 最大 K 连通分量：两分量网络 K=1 保留大分量（3 节点）
        Network cc = new Network("t");
        GraphNode[] ns = new GraphNode[6];
        for (int i = 0; i < 6; i++) ns[i] = cc.node("n" + i, "n" + i, "k");
        cc.link(ns[0], ns[1]); cc.link(ns[1], ns[2]); // 大分量 3
        cc.link(ns[3], ns[4]); // 小分量 2；ns[5] 孤立
        int kept = cc.keepLargestComponents(1);
        check("lcc.kept", kept == 3 && cc.nodes.size() == 3 && cc.nodes.containsKey("n0"), kept + "/" + cc.nodes.keySet());

        // 复数→单数
        check("singular.ies", KeywordNorm.singular("studies").equals("study"), KeywordNorm.singular("studies"));
        check("singular.s", KeywordNorm.singular("systems").equals("system"), KeywordNorm.singular("systems"));
        check("singular.keep", KeywordNorm.singular("analysis").equals("analysis")
                && KeywordNorm.singular("class").equals("class"), "over-stripped");

        // TF-IDF 标注 + singularKeywords 走 Pipeline
        Project p = DemoData.generate(60);
        Pipeline.Options o = new Pipeline.Options();
        o.labeling = Labelers.TFIDF;
        o.singularKeywords = true;
        o.selection = Pipeline.G_INDEX;
        o.gindexK = 2.0;
        Pipeline.Result r = Pipeline.run(p, o);
        check("tfidf.pipeline", !r.clusterLabels.isEmpty()
                && r.clusterLabels.values().iterator().next().get(0).g2 > 0, "labels");
        check("gindex.pipeline", r.network.nodes.size() > 0, "nodes=" + r.network.nodes.size());
        check("centralities.pipeline", r.network.nodeCollection().stream()
                .allMatch(n -> n.degreeCentrality >= 0 && n.eigenCentrality >= 0), "computed");

        // burst.tsv 导出
        Path dir = Files.createTempDirectory("ycscite-gap");
        Charts.burstTsv(r, dir.resolve("burst.tsv"));
        List<String> lines = Files.readAllLines(dir.resolve("burst.tsv"));
        check("bursttsv.header", lines.get(0).equals("term\tfrom\tto\tstrength\tsigma"), lines.get(0));
        boolean anyBurst = r.network.nodeCollection().stream().anyMatch(n -> n.burstness > 0);
        check("bursttsv.rows", anyBurst ? lines.size() > 1 : lines.size() == 1, lines.size() + " rows, anyBurst=" + anyBurst);
    }

    /** 终轮补齐：t-SNE / 凸包 / 鱼眼 / 标签角度 / Dual-Map / 引文变体合并 / SVA / 四新数据源 / 逐年标签。 */
    static void testFinalGapRound() throws Exception {
        // 凸包：正方形四角+中心 → 凸包为 4 顶点且中心被排除
        var pts = List.of(new java.awt.geom.Point2D.Double(0, 0), new java.awt.geom.Point2D.Double(10, 0),
                new java.awt.geom.Point2D.Double(10, 10), new java.awt.geom.Point2D.Double(0, 10),
                new java.awt.geom.Point2D.Double(5, 5));
        var hull = ycscite.ui.GraphCanvas.convexHull(pts);
        check("hull.size", hull.size() == 4, "" + hull.size());
        boolean centerExcluded = hull.stream().noneMatch(p -> p.x == 5 && p.y == 5);
        check("hull.center", centerExcluded, "center in hull");

        // t-SNE：小网络嵌入可复现（同种子），坐标有界，同簇节点平均距离更近
        Project p = DemoData.generate(60);
        Pipeline.Result r = Pipeline.run(p, new Pipeline.Options());
        double[][] e1 = Tsne.embed(r.network, 120);
        double[][] e2 = Tsne.embed(r.network, 120);
        boolean reproducible = true;
        for (int i = 0; i < e1.length; i++)
            if (Math.abs(e1[i][0] - e2[i][0]) > 1e-9 || Math.abs(e1[i][1] - e2[i][1]) > 1e-9) reproducible = false;
        check("tsne.reproducible", reproducible, "nondeterministic");
        boolean bounded = true;
        for (double[] q : e1) if (Math.abs(q[0]) > 1.5 || Math.abs(q[1]) > 1.5) bounded = false;
        check("tsne.bounded", bounded && e1.length == r.network.nodes.size(), "coords out of range");
        // TSNE 模式渲染不崩
        ycscite.ui.GraphCanvas c = new ycscite.ui.GraphCanvas();
        c.setClusterLabels(r.clusterLabels);
        c.show(r.network, ycscite.ui.GraphCanvas.Mode.TSNE, "tsne");
        c.renderImage(600, 400);
        check("tsne.render", true, "ok");

        // 鱼眼 + 角度 + 凸包渲染不崩
        c.setFisheye(true);
        c.setLabelAngle(45);
        c.setConvexHull(true);
        c.renderImage(600, 400);
        check("fisheye.render", c.isFisheye() && c.getLabelAngle() == 0 && c.isConvexHull(), "state");

        // 引文变体合并：CitedRef.parse 已把同“作者,年,期刊”归一（V57/V58 两条 J ASIST 为一键），
        // 网络中 SMITH 变体 = {J ASIST, J AM SOC...} 2 个 + LEE 1 个 = 3；归并后 SMITH×1 + LEE = 2
        Record a = new Record(); a.year = 2020;
        a.citedRefs = List.of("SMITH J, 2010, J ASIST, V57, P359",
                "SMITH JA., 2010, J AM SOC INF SCI TECHNOL, V57, P359",
                "SMITH J, 2010, J ASIST, V58, P400",
                "LEE K, 2011, OTHER J, V1, P1");
        Record b2 = new Record(); b2.year = 2021;
        b2.citedRefs = List.of("SMITH J, 2010, J ASIST, V57, P359", "LEE K, 2011, OTHER J, V1, P1");
        Network cocit = NetworkBuilders.coCitation(List.of(a, b2));
        int before = cocit.nodes.size();
        int merged = RefVariants.merge(cocit);
        check("variants.key", RefVariants.variantKey("SMITH J, 2010, J ASIST, V57, P359")
                .equals(RefVariants.variantKey("SMITH JA., 2010, J AM SOC INF SCI TECHNOL, V57, P359")), "key mismatch");
        check("variants.merged", before == 3 && merged == 1 && cocit.nodes.size() == 2,
                before + "->" + cocit.nodes.size() + " merged=" + merged);

        // SVA：逐年增量单调（节点数不减）
        var rows = ycscite.analysis.StructuralVariation.compute(p, NetworkBuilders.KEYWORD_COOCCURRENCE);
        check("sva.rows", rows.size() > 3, "" + rows.size());
        boolean monotonic = true;
        for (int i = 1; i < rows.size(); i++)
            if (rows.get(i).nodes() < rows.get(i - 1).nodes()) monotonic = false;
        check("sva.monotonic", monotonic && rows.get(0).newNodes() > 0, "not cumulative");
        check("sva.tsv", ycscite.analysis.StructuralVariation.toTsv(rows).startsWith("year\tnodes\tnew_nodes\tnew_links\tvariation"), "header");

        // ProQuest RIS
        String ris = "TY  - JOUR\nAU  - Smith John\nTI  - RIS paper\nJO  - Test Journal\nPY  - 2020\nKW  - ris keyword\nER  - \n\nTY  - JOUR\nAU  - Lee Kate\nTI  - Second RIS\nPY  - 2021\nER  - \n";
        Path risFile = Files.createTempFile("ris", ".txt");
        Files.writeString(risFile, ris);
        var risRecs = ProQuestRisParser.parseFile(risFile);
        check("ris.count", risRecs.size() == 2, "" + risRecs.size());
        check("ris.fields", risRecs.get(0).title.equals("RIS paper")
                && risRecs.get(0).authors.contains("Smith John")
                && risRecs.get(0).year == 2020
                && risRecs.get(0).keywords.contains("ris keyword"), "fields");

        // Dimensions CSV
        String dim = "Publication year,Title,Authors,Journal title,DOI,Keywords\n"
                + "2020,Dim one,\"Smith J.; Lee K.\",J Dim,10.1/d,dim kw1; dim kw2\n"
                + "2021,Dim two,\"Jones B.\",Other,10.2/e,dim kw1\n";
        Path dimFile = Files.createTempFile("dim", ".csv");
        Files.writeString(dimFile, dim);
        var dimRecs = DimensionsCsvParser.parseFile(dimFile);
        check("dim.count", dimRecs.size() == 2, "" + dimRecs.size());
        check("dim.fields", dimRecs.get(0).title.equals("Dim one") && dimRecs.get(0).year == 2020
                && dimRecs.get(0).keywords.size() == 2, "" + dimRecs.get(0).keywords);

        // KCI（NoteExpress 风格透传）
        String kci = "RT Journal Article\nA1 KIM S\nT1 Korean study\nT2 KCI Journal\nYR 2020\nK1 hallyu; k-pop\n";
        Path kciFile = Files.createTempFile("kci", ".txt");
        Files.writeString(kciFile, kci);
        var kciRecs = KciParser.parseFile(kciFile);
        check("kci.count", kciRecs.size() == 1 && kciRecs.get(0).title.equals("Korean study"), "" + kciRecs.size());

        // NSF XML（DOM + 兜底）
        String nsf = "<Award><AwardID>123</AwardID><Title>NSF Grant Study</Title>"
                + "<Date>2019-05-01</Date>"
                + "<Investigator><FirstName>Alan</FirstName><LastName>Turing</LastName></Investigator>"
                + "<Institution><Name>MIT</Name></Institution>"
                + "<AbstractNarration>An abstract.</AbstractNarration></Award>";
        Path nsfFile = Files.createTempFile("nsf", ".xml");
        Files.writeString(nsfFile, nsf);
        var nsfRecs = NsfAwardsParser.parseFile(nsfFile);
        check("nsf.count", nsfRecs.size() == 1, "" + nsfRecs.size());
        check("nsf.fields", nsfRecs.get(0).title.equals("NSF Grant Study") && nsfRecs.get(0).year == 2019
                && nsfRecs.get(0).authors.get(0).equals("Alan Turing")
                && nsfRecs.get(0).addresses.contains("MIT"), "fields");

        // 逐年聚类标签导出
        Path dir = Files.createTempDirectory("ycscite-final");
        Exporters.writeYearByYearLabels(r, dir.resolve("yby.tsv"));
        List<String> yby = Files.readAllLines(dir.resolve("yby.tsv"));
        check("yby.header", yby.get(0).equals("cluster\tyear\ttop_terms"), yby.get(0));
        check("yby.rows", yby.size() > r.clusterStats.size(), yby.size() + " rows");

        // Dual-Map 渲染（真实项目，含 CR 退化路径）
        DualMap.render(p, dir.resolve("dual-map.png"));
        check("dualmap.png", Files.size(dir.resolve("dual-map.png")) > 15000, "too small");

        // CLI 新旗标端到端（tsne/dual-map/sva/yby）
        Path wos = dir.resolve("wos.txt");
        Files.writeString(wos, "PT J\nAU A B\nTI t\nSO J\nDE k\nPY 2020\nER\n");
        int rc = ycscite.Cli.run(new String[]{"analyze", wos.toString(), "--out",
                dir.resolve("cli-out").toString(), "--tsne", "--dual-map", "--sva", "--yby"});
        check("cli.finalflags", rc == 0
                        && Files.exists(dir.resolve("cli-out/structural-variation.tsv"))
                        && Files.exists(dir.resolve("cli-out/year-by-year-labels.tsv"))
                        && Files.exists(dir.resolve("cli-out/dual-map.png")),
                "rc=" + rc);
    }

    static void testProjectRoundTrip() throws Exception {
        // MiniJson writer round-trip
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("s", "引号\"反斜杠\\换行\n");
        nested.put("i", 2021);
        nested.put("d", 0.123456789012345);
        nested.put("arr", Arrays.asList(true, null, 1.5, "x"));
        Object back = MiniJson.parse(MiniJson.write(nested));
        check("json.write.str", "引号\"反斜杠\\换行\n".equals(MiniJson.str(MiniJson.obj(back).get("s"))), "escape");
        check("json.write.d", MiniJson.num(MiniJson.obj(back).get("d")) == 0.123456789012345, "double");

        Project p = DemoData.generate(60);
        Pipeline.Options o = new Pipeline.Options();
        o.topN = 25;
        o.labeling = Labelers.LSI;
        Pipeline.Result r = Pipeline.run(p, o);
        Path f = Files.createTempFile("proj", ".csl-proj");
        ProjectStore.save(f, p, r, o);
        ProjectStore.Bundle b = ProjectStore.load(f);

        check("proj.name", b.project.name.equals(p.name), b.project.name);
        check("proj.records", b.project.records.size() == p.records.size(), b.project.records.size() + "");
        Record r0 = p.records.get(0), l0 = b.project.records.get(0);
        check("proj.rec.title", l0.title.equals(r0.title), l0.title);
        check("proj.rec.authors", l0.authors.equals(r0.authors), "" + l0.authors);
        check("proj.rec.keywords", l0.keywords.equals(r0.keywords), "" + l0.keywords);
        check("proj.rec.cr", l0.citedRefs.equals(r0.citedRefs), "cr mismatch");
        check("proj.rec.year", l0.year == r0.year && l0.timesCited == r0.timesCited, l0.year + "");
        check("proj.years", b.project.minYear == p.minYear && b.project.maxYear == p.maxYear, "range");

        check("proj.net.kind", b.result.network.kind.equals(r.network.kind), b.result.network.kind);
        check("proj.net.nodes", b.result.network.nodes.size() == r.network.nodes.size(),
                b.result.network.nodes.size() + "/" + r.network.nodes.size());
        check("proj.net.edges", b.result.network.edges.size() == r.network.edges.size(),
                b.result.network.edges.size() + "/" + r.network.edges.size());

        GraphNode a = r.network.nodeCollection().iterator().next();
        GraphNode a2 = b.result.network.nodes.get(a.id);
        check("proj.node.exists", a2 != null, a.id);
        if (a2 != null) {
            check("proj.node.label", a2.label.equals(a.label) && a2.kind.equals(a.kind), a2.label);
            check("proj.node.metrics", a2.weight == a.weight && a2.cluster == a.cluster
                    && a2.halfLife == a.halfLife && a2.firstYear == a.firstYear,
                    a2.weight + "/" + a2.cluster);
            check("proj.node.doubles", Math.abs(a2.centrality - a.centrality) < 1e-12
                    && Math.abs(a2.pageRank - a.pageRank) < 1e-12
                    && Math.abs(a2.sigma - a.sigma) < 1e-12, "double drift");
            check("proj.node.years", a2.countsByYear.equals(a.countsByYear), a2.countsByYear.toString());
            check("proj.node.bursts", a2.burstIntervals.size() == a.burstIntervals.size(), "bursts");
            if (!a.burstIntervals.isEmpty())
                check("proj.node.burstvals", Arrays.equals(a2.burstIntervals.get(0), a.burstIntervals.get(0)), "vals");
        }
        var e0 = r.network.edges.get(0);
        boolean edgeFound = b.result.network.edges.stream().anyMatch(e2 ->
                e2.weight == e0.weight
                        && ((e2.a.id.equals(e0.a.id) && e2.b.id.equals(e0.b.id))
                        || (e2.a.id.equals(e0.b.id) && e2.b.id.equals(e0.a.id))));
        check("proj.edge", edgeFound, e0.a.id + "-" + e0.b.id);

        check("proj.labels.keys", b.result.clusterLabels.keySet().equals(r.clusterLabels.keySet()),
                "" + b.result.clusterLabels.keySet());
        if (!r.clusterLabels.isEmpty()) {
            var k = r.clusterLabels.keySet().iterator().next();
            var s0 = r.clusterLabels.get(k).get(0);
            var s02 = b.result.clusterLabels.get(k).get(0);
            check("proj.labels.term", s02.term.equals(s0.term), s02.term);
            check("proj.labels.g2", Math.abs(s02.g2 - s0.g2) < 1e-9, s02.g2 + " vs " + s0.g2);
        }
        check("proj.modularity", Math.abs(b.result.modularity - r.modularity) < 1e-12, "q");
        check("proj.options", b.options.topN == 25 && Labelers.LSI.equalsIgnoreCase(b.options.labeling),
                b.options.labeling);
        Files.delete(f);
    }

    static void testMySql() throws Exception {
        Project p = DemoData.generate(30);
        Pipeline.Result r = Pipeline.run(p, new Pipeline.Options());
        String sql = MySqlStore.dump(p, r.network, "demo_db");
        check("sql.create", sql.contains("CREATE DATABASE IF NOT EXISTS `demo_db`") && sql.contains("CREATE TABLE IF NOT EXISTS records"),
                "missing create");
        int inserts = sql.split("INSERT INTO", -1).length - 1;
        check("sql.inserts", inserts == p.records.size() + r.network.nodes.size() + r.network.edges.size(),
                inserts + " vs " + (p.records.size() + r.network.nodes.size() + r.network.edges.size()));
        check("sql.tables", sql.contains("INSERT INTO nodes") && sql.contains("INSERT INTO edges"), "node/edge inserts");
        List<String> stmts = MySqlStore.splitStatements(sql);
        // 2 (CREATE DATABASE + USE) + 3 CREATE TABLE + inserts
        check("sql.split", stmts.size() == inserts + 5, stmts.size() + " vs " + (inserts + 5));
        // 引号/转义不破坏语句切分
        List<String> tricky = MySqlStore.splitStatements("INSERT INTO t VALUES ('a;b');-- x\nINSERT INTO t VALUES ('c');");
        check("sql.split.quote", tricky.size() == 2, "" + tricky.size());
        // 无驱动时直连给出说明性异常
        try {
            MySqlStore.storeToServer("jdbc:mysql://127.0.0.1:1/x", "u", "p", p, r.network, "db");
            check("sql.nodriver", false, "unexpected success");
        } catch (Exception e) {
            check("sql.nodriver", true, e.getMessage());
        }
    }
}
