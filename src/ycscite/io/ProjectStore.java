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
        public Boolean exportLogo;
        public Map<String,Object> presentation=Map.of();
        public ycscite.Settings viewDefaults;
    }

    public static void save(Path file, Project p, Pipeline.Result r, Pipeline.Options o) throws IOException {save(file,p,r,o,null,Map.of(),null);}
    public static void save(Path file, Project p, Pipeline.Result r, Pipeline.Options o,boolean logo)throws IOException{save(file,p,r,o,Boolean.valueOf(logo),Map.of(),null);}
    public static void save(Path file,Project p,Pipeline.Result r,Pipeline.Options o,boolean logo,Map<String,Object> presentation)throws IOException{save(file,p,r,o,Boolean.valueOf(logo),presentation,null);}
    public static void saveFromReport(Path file,Project project,Pipeline.Result result,Pipeline.Options options,ycscite.Settings settings)throws IOException{save(file,project,result,options,settings.bool("logo"),Map.of(),settings);}
    private static void save(Path file, Project p, Pipeline.Result r, Pipeline.Options o,Boolean logo,Map<String,Object> presentation,ycscite.Settings defaults)throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("project", projectJson(p));
        if(logo!=null)root.put("exportLogo",logo);
        if(defaults!=null)root.put("viewDefaults",defaults.map());
        if(!presentation.isEmpty()){if(r==null)throw new IOException("A saved view requires an analyzed network");root.put("presentation",ViewState.editor(presentation,r.network));}
        if (r != null) root.put("analysis", analysisJson(r, o));
        Path target=file.toAbsolutePath().normalize();Path staged=Files.createTempFile(target.getParent(),"ycscite-project-",".new");
        try{
            Files.writeString(staged,MiniJson.write(root),StandardCharsets.UTF_8);
            try{Files.move(staged,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            catch(java.nio.file.AtomicMoveNotSupportedException error){Files.move(staged,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(staged);}
    }

    public static Bundle load(Path file) throws IOException {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(Files.readString(file)));
        if (!FORMAT.equals(root.get("format")) && !LEGACY_FORMAT.equals(root.get("format")))
            throw new IOException("不是 csl-proj 项目文件（format=" + root.get("format") + "）");
        Bundle b = new Bundle();
        b.project = projectFrom(MiniJson.obj(root.get("project")));
        b.exportLogo=root.get("exportLogo") instanceof Boolean logo?logo:null;
        if(root.containsKey("viewDefaults"))b.viewDefaults=new ycscite.Settings(ViewState.object(root.get("viewDefaults"),"viewDefaults"));
        Object analysis = root.get("analysis");
        if (analysis != null) {
            b.options = new Pipeline.Options();
            b.result = new Pipeline.Result();
            analysisFrom(MiniJson.obj(analysis), b);
        }
        if(root.containsKey("presentation")){if(b.result==null)throw new IOException("Saved view has no analyzed network");b.presentation=ViewState.editor(root.get("presentation"),b.result.network);}
        return b;
    }

    /** Deterministic identity of all retained node data and undirected edge data, independent of insertion order. */
    public static String networkFingerprint(ycscite.model.Network graph){
        try{
            var nodes=graph.nodeCollection().stream().sorted(java.util.Comparator.comparing(n->n.id)).map(ProjectStore::nodeJson).toList();
            var edges=graph.edges.stream().map(e->java.util.List.of(e.a.id.compareTo(e.b.id)<=0?e.a.id:e.b.id,e.a.id.compareTo(e.b.id)<=0?e.b.id:e.a.id,Integer.toString(e.weight),Integer.toString(e.firstYear))).sorted(java.util.Comparator.comparing(MiniJson::write)).toList();
            byte[] bytes=MiniJson.write(java.util.List.of("ycscite-network-v1",graph.kind,graph.minYear,graph.maxYear,nodes,edges)).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }

    /** Independent editable analysis state, including its metrics/labels, for undo. */
    public static Pipeline.Result copyAnalysis(Pipeline.Result result){
        var bundle=new Bundle();bundle.result=new Pipeline.Result();
        analysisFrom(analysisJson(result,new Pipeline.Options()),bundle);return bundle.result;
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
            rm.put("categories",new ArrayList<>(r.categories));
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
            r.categories=strList(rm.get("categories"));
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
        m.put("options",PipelineOptionsCodec.write(o));
        m.put("netMinYear",r.network.minYear);m.put("netMaxYear",r.network.maxYear);
        m.put("paramLines",new ArrayList<>(r.paramLines));
        m.put("burstRows",r.burstRows.stream().map(ProjectStore::nodeJson).toList());
        if (r.clusterStats != null) {
            Map<String, Object> stats = new LinkedHashMap<>();
            for (Map.Entry<Integer, double[]> en : r.clusterStats.entrySet())
                stats.put(String.valueOf(en.getKey()), List.of(en.getValue()[0], en.getValue()[1], en.getValue()[2]));
            m.put("clusterStats", stats);
        }

        List<Object> nodes = new ArrayList<>();for(var node:r.network.nodeCollection())nodes.add(nodeJson(node));
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

    private static Map<String,Object> nodeJson(GraphNode n){
        Map<String, Object> nm = new LinkedHashMap<>();
        nm.put("id", n.id);
        nm.put("label", n.label);
        nm.put("kind", n.kind);
        nm.put("weight", n.weight);
        nm.put("halfLife", n.halfLife);
        nm.put("centrality", n.centrality);
        nm.put("pageRank", n.pageRank);
        nm.put("degreeCentrality",n.degreeCentrality);nm.put("eigenCentrality",n.eigenCentrality);
        nm.put("bookmarked",n.bookmarked);nm.put("annotation",n.annotation);nm.put("userLabel",n.userLabel);
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
        return nm;
    }

    private static GraphNode nodeFrom(Map<String,Object> nm){
        GraphNode n = new GraphNode(str(nm.get("id")), str(nm.get("label")), str(nm.get("kind")));
        n.weight = (int) MiniJson.num(nm.get("weight"));
        n.halfLife = (int) MiniJson.num(nm.get("halfLife"));
        n.centrality = MiniJson.num(nm.get("centrality"));
        n.pageRank = MiniJson.num(nm.get("pageRank"));
        n.degreeCentrality=MiniJson.num(nm.get("degreeCentrality"));n.eigenCentrality=MiniJson.num(nm.get("eigenCentrality"));
        n.bookmarked=Boolean.TRUE.equals(nm.get("bookmarked"));n.annotation=str(nm.get("annotation"));n.userLabel=nm.get("userLabel") instanceof String label?label:null;
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
        return n;
    }

    private static void analysisFrom(Map<String, Object> m, Bundle b) {
        ycscite.model.Network g = new ycscite.model.Network(str(m.get("kind")));
        b.options=PipelineOptionsCodec.read(MiniJson.obj(m.get("options")));
        for (Object no : MiniJson.arr(m.get("nodes"))) {
            Map<String, Object> nm = MiniJson.obj(no);
            GraphNode n=nodeFrom(nm);
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
        // Old projects may not have saved explicit network bounds. Use all observed node years.
        for (GraphNode n : g.nodeCollection()) {
            if(!m.containsKey("netMinYear")&&!n.countsByYear.isEmpty())g.minYear=Math.min(g.minYear,n.countsByYear.keySet().stream().mapToInt(Integer::intValue).min().orElse(g.minYear));
            if(!m.containsKey("netMaxYear")&&!n.countsByYear.isEmpty())g.maxYear=Math.max(g.maxYear,n.countsByYear.keySet().stream().mapToInt(Integer::intValue).max().orElse(g.maxYear));
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
        b.result.paramLines=strList(m.get("paramLines"));
        b.result.burstRows=new ArrayList<>();for(Object row:MiniJson.arr(m.get("burstRows")))b.result.burstRows.add(nodeFrom(MiniJson.obj(row)));
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
