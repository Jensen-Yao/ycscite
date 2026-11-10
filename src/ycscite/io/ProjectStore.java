package ycscite.io;

import ycscite.analysis.LLRLabeler;
import ycscite.analysis.Pipeline;
import ycscite.model.GraphNode;
import ycscite.model.Project;
import ycscite.model.Record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Project persistence (.csl-proj, UTF-8 JSON): full round-trip of Project records,
 * analysis options, the analyzed Network (structure + metrics + clusters + bursts)
 * and cluster labels — reload restores the exact analyzed state without recompute.
 */
public final class ProjectStore {

    public static final String FORMAT = "ycscite-project-1";
    /** 旧格式标识（citespace-clone 时期），载入时兼容。 */
    public static final String LEGACY_FORMAT = "citespace-clone-project-1";
    public static final String EXTENSION = "csl-proj";

    public static final class Bundle {
        public Project project;
        public Pipeline.Result result;   // null when saved without analysis
        public Pipeline.Options options;
    }

    public static void save(Path file, Project p, Pipeline.Result r, Pipeline.Options o) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("project", projectJson(p));
        if (r != null) root.put("analysis", analysisJson(r, o));
        Files.writeString(file, MiniJson.write(root), StandardCharsets.UTF_8);
    }

    public static Bundle load(Path file) throws IOException {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(Files.readString(file)));
        if (!FORMAT.equals(root.get("format")) && !LEGACY_FORMAT.equals(root.get("format")))
            throw new IOException("不是 csl-proj 项目文件（format=" + root.get("format") + "）");
        Bundle b = new Bundle();
        b.project = projectFrom(MiniJson.obj(root.get("project")));
        Object analysis = root.get("analysis");
        if (analysis != null) {
            b.options = new Pipeline.Options();
            b.result = new Pipeline.Result();
            analysisFrom(MiniJson.obj(analysis), b);
        }
        return b;
    }

    // ---------------- project ----------------

    private static Map<String, Object> projectJson(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name);
        m.put("minYear", p.minYear);
        m.put("maxYear", p.maxYear);
        List<Object> records = new ArrayList<>();
        for (Record r : p.records) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("pt", r.pt);
            rm.put("authors", new ArrayList<>(r.authors));
            rm.put("authorsFull", new ArrayList<>(r.authorsFull));
            rm.put("title", r.title);
            rm.put("source", r.source);
            rm.put("keywords", new ArrayList<>(r.keywords));
            rm.put("keywordsPlus", new ArrayList<>(r.keywordsPlus));
            rm.put("abstract", r.abstractText);
            rm.put("addresses", new ArrayList<>(r.addresses));
            rm.put("citedRefs", new ArrayList<>(r.citedRefs));
            rm.put("year", r.year);
            rm.put("volume", r.volume);
            rm.put("beginPage", r.beginPage);
            rm.put("doi", r.doi);
            rm.put("ut", r.ut);
            rm.put("tc", r.timesCited);
            records.add(rm);
        }
        m.put("records", records);
        return m;
    }

    private static Project projectFrom(Map<String, Object> m) {
        Project p = new Project();
        p.name = str(m.get("name"));
        p.minYear = (int) MiniJson.num(m.getOrDefault("minYear", Double.valueOf(Integer.MAX_VALUE)));
        p.maxYear = (int) MiniJson.num(m.getOrDefault("maxYear", Double.valueOf(Integer.MIN_VALUE)));
        for (Object ro : MiniJson.arr(m.get("records"))) {
            Map<String, Object> rm = MiniJson.obj(ro);
            Record r = new Record();
            r.pt = str(rm.get("pt"));
            r.authors = strList(rm.get("authors"));
            r.authorsFull = strList(rm.get("authorsFull"));
            r.title = str(rm.get("title"));
            r.source = str(rm.get("source"));
            r.keywords = strList(rm.get("keywords"));
            r.keywordsPlus = strList(rm.get("keywordsPlus"));
            r.abstractText = str(rm.get("abstract"));
            r.addresses = strList(rm.get("addresses"));
            r.citedRefs = strList(rm.get("citedRefs"));
            r.year = (int) MiniJson.num(rm.get("year"));
            r.volume = str(rm.get("volume"));
            r.beginPage = str(rm.get("beginPage"));
            r.doi = str(rm.get("doi"));
            r.ut = str(rm.get("ut"));
            r.timesCited = (int) MiniJson.num(rm.get("tc"));
            p.records.add(r);
        }
        return p;
    }

    // ---------------- analysis ----------------

    private static Map<String, Object> analysisJson(Pipeline.Result r, Pipeline.Options o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", r.network.kind);
        m.put("modularity", r.modularity);
        m.put("slices", r.slices);
        Map<String, Object> om = new LinkedHashMap<>();
        om.put("networkKind", o.networkKind);
        om.put("topN", o.topN);
        om.put("fromYear", o.fromYear);
        om.put("toYear", o.toYear);
        om.put("sliceYears", o.sliceYears);
        om.put("topNPerSlice", o.topNPerSlice);
        om.put("pruning", o.pruning);
        om.put("minClusterSize", o.minClusterSize);
        om.put("clustering", o.clustering);
        om.put("labeling", o.labeling);
        om.put("labelsPerCluster", o.labelsPerCluster);
        m.put("options", om);
        if (r.clusterStats != null) {
            Map<String, Object> stats = new LinkedHashMap<>();
            for (Map.Entry<Integer, double[]> en : r.clusterStats.entrySet())
                stats.put(String.valueOf(en.getKey()), List.of(en.getValue()[0], en.getValue()[1], en.getValue()[2]));
            m.put("clusterStats", stats);
        }

        List<Object> nodes = new ArrayList<>();
        for (GraphNode n : r.network.nodeCollection()) {
            Map<String, Object> nm = new LinkedHashMap<>();
            nm.put("id", n.id);
            nm.put("label", n.label);
            nm.put("kind", n.kind);
            nm.put("weight", n.weight);
            nm.put("halfLife", n.halfLife);
            nm.put("centrality", n.centrality);
            nm.put("pageRank", n.pageRank);
            nm.put("burstness", n.burstness);
            nm.put("burstStrength", n.burstStrength);
            nm.put("sigma", n.sigma);
            nm.put("cluster", n.cluster);
            nm.put("firstYear", n.firstYear);
            Map<String, Object> cy = new LinkedHashMap<>();
            for (Map.Entry<Integer, Integer> e : n.countsByYear.entrySet())
                cy.put(String.valueOf(e.getKey()), e.getValue());
            nm.put("countsByYear", cy);
            List<Object> bursts = new ArrayList<>();
            for (int i = 0; i < n.burstIntervals.size(); i++) {
                int[] b = n.burstIntervals.get(i);
                double bs = i < n.intervalStrengths.size() ? n.intervalStrengths.get(i) : 0;
                bursts.add(java.util.Arrays.asList(b[0], b[1], b[2], bs));
            }
            nm.put("bursts", bursts);
            nodes.add(nm);
        }
        m.put("nodes", nodes);
        List<Object> edges = new ArrayList<>();
        for (var e : r.network.edges)
            edges.add(e.firstYear > 0 ? List.of(e.a.id, e.b.id, e.weight, e.firstYear)
                    : List.of(e.a.id, e.b.id, e.weight));
        m.put("edges", edges);

        Map<String, Object> labels = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<LLRLabeler.Scored>> en : r.clusterLabels.entrySet()) {
            List<Object> ls = new ArrayList<>();
            for (LLRLabeler.Scored s : en.getValue()) ls.add(List.of(s.term, s.g2));
            labels.put(String.valueOf(en.getKey()), ls);
        }
        m.put("labels", labels);
        return m;
    }

    private static void analysisFrom(Map<String, Object> m, Bundle b) {
        ycscite.model.Network g = new ycscite.model.Network(str(m.get("kind")));
        Map<String, Object> om = MiniJson.obj(m.get("options"));
        b.options.networkKind = str(om.get("networkKind"));
        b.options.topN = (int) MiniJson.num(om.get("topN"));
        b.options.clustering = str(om.get("clustering"));
        b.options.labeling = str(om.get("labeling"));
        b.options.labelsPerCluster = (int) MiniJson.num(om.get("labelsPerCluster"));
        b.options.fromYear = om.get("fromYear") == null ? 0 : (int) MiniJson.num(om.get("fromYear"));
        b.options.toYear = om.get("toYear") == null ? 9999 : (int) MiniJson.num(om.get("toYear"));
        b.options.sliceYears = om.get("sliceYears") == null ? 1 : (int) MiniJson.num(om.get("sliceYears"));
        b.options.topNPerSlice = om.get("topNPerSlice") == null ? 0 : (int) MiniJson.num(om.get("topNPerSlice"));
        b.options.pruning = om.get("pruning") == null ? ycscite.analysis.Pruning.NONE : str(om.get("pruning"));
        b.options.minClusterSize = om.get("minClusterSize") == null ? 1 : (int) MiniJson.num(om.get("minClusterSize"));

        for (Object no : MiniJson.arr(m.get("nodes"))) {
            Map<String, Object> nm = MiniJson.obj(no);
            GraphNode n = new GraphNode(str(nm.get("id")), str(nm.get("label")), str(nm.get("kind")));
            n.weight = (int) MiniJson.num(nm.get("weight"));
            n.halfLife = (int) MiniJson.num(nm.get("halfLife"));
            n.centrality = MiniJson.num(nm.get("centrality"));
            n.pageRank = MiniJson.num(nm.get("pageRank"));
            n.burstness = MiniJson.num(nm.get("burstness"));
            n.burstStrength = MiniJson.num(nm.get("burstStrength"));
            n.sigma = MiniJson.num(nm.get("sigma"));
            n.cluster = (int) MiniJson.num(nm.get("cluster"));
            n.firstYear = (int) MiniJson.num(nm.get("firstYear"));
            for (Map.Entry<String, Object> e : MiniJson.obj(nm.get("countsByYear")).entrySet())
                n.countsByYear.put(Integer.parseInt(e.getKey()), (int) MiniJson.num(e.getValue()));
            for (Object bo : MiniJson.arr(nm.get("bursts"))) {
                List<Object> bl = MiniJson.arr(bo);
                n.burstIntervals.add(new int[]{(int) MiniJson.num(bl.get(0)),
                        (int) MiniJson.num(bl.get(1)), (int) MiniJson.num(bl.get(2))});
                n.intervalStrengths.add(bl.size() > 3 ? MiniJson.num(bl.get(3)) : 0.0);
            }
            g.nodes.put(n.id, n);
        }
        for (Object eo : MiniJson.arr(m.get("edges"))) {
            List<Object> el = MiniJson.arr(eo);
            GraphNode a = g.nodes.get(str(el.get(0)));
            GraphNode bb = g.nodes.get(str(el.get(1)));
            if (a != null && bb != null) g.linkWeighted(a, bb, (int) MiniJson.num(el.get(2)),
                    el.size() >= 4 ? (int) MiniJson.num(el.get(3)) : 0);
        }
        g.minYear = (int) MiniJson.num(m.getOrDefault("netMinYear", Double.valueOf(Integer.MAX_VALUE)));
        g.maxYear = (int) MiniJson.num(m.getOrDefault("netMaxYear", Double.valueOf(Integer.MIN_VALUE)));
        // network year range reconstructible from node years when absent
        for (GraphNode n : g.nodeCollection()) {
            if (n.firstYear != Integer.MAX_VALUE) {
                if (g.minYear == Integer.MAX_VALUE || n.firstYear < g.minYear) g.minYear = n.firstYear;
                if (g.maxYear == Integer.MIN_VALUE || n.firstYear > g.maxYear) g.maxYear = n.firstYear;
            }
        }

        Map<Integer, List<LLRLabeler.Scored>> labels = new TreeMap<>();
        for (Map.Entry<String, Object> en : MiniJson.obj(m.get("labels")).entrySet()) {
            List<LLRLabeler.Scored> ls = new ArrayList<>();
            for (Object so : MiniJson.arr(en.getValue())) {
                List<Object> sl = MiniJson.arr(so);
                ls.add(new LLRLabeler.Scored(str(sl.get(0)), MiniJson.num(sl.get(1))));
            }
            labels.put(Integer.parseInt(en.getKey()), ls);
        }
        // 聚类统计：旧项目缺失时按恢复的网络现算 silhouette
        java.util.TreeMap<Integer, double[]> stats = new TreeMap<>();
        Object statsJson = m.get("clusterStats");
        if (statsJson != null) {
            for (Map.Entry<String, Object> en : MiniJson.obj(statsJson).entrySet()) {
                List<Object> v = MiniJson.arr(en.getValue());
                stats.put(Integer.parseInt(en.getKey()), new double[]{
                        MiniJson.num(v.get(0)), MiniJson.num(v.get(1)), MiniJson.num(v.get(2))});
            }
        } else {
            var perNode = ycscite.analysis.Silhouette.compute(g);
            var perCluster = ycscite.analysis.Silhouette.perCluster(g, perNode);
            for (GraphNode n : g.nodeCollection()) {
                double[] s = stats.computeIfAbsent(n.cluster, k -> new double[3]);
                s[0]++;
                s[2] += n.firstYear == Integer.MAX_VALUE ? 0 : n.firstYear;
            }
            for (var en : stats.entrySet()) {
                en.getValue()[1] = perCluster.getOrDefault(en.getKey(), 0.0);
                if (en.getValue()[0] > 0) en.getValue()[2] /= en.getValue()[0];
            }
        }
        b.result.network = g;
        b.result.modularity = MiniJson.num(m.get("modularity"));
        b.result.slices = (int) MiniJson.num(m.get("slices"));
        b.result.clusterLabels = labels;
        b.result.clusterStats = stats;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        for (Object v : MiniJson.arr(o)) out.add(String.valueOf(v));
        return out;
    }
}
