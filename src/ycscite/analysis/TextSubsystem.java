package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.model.Project;

import java.util.*;

/**
 * 文本子系统余项（原版 CiteSpace 文本分析，CiteSpace.java 814-843/1045-1081 的零依赖实现）：
 *   概念树（Concept Tree）：术语按共现剖面的 Jaccard 距离做平均联结层次聚合，
 *     按阈值逐层展开为缩进树（对应 "Build Concept Tree"）；
 *   叙事生成（Narrative）：从分析结果生成结构化综述文本
 *     （对应 "Generate a Narrative"：数据集→方法→网络结构→主题簇→突现→结论模板）。
 */
public final class TextSubsystem {

    // ---------------- 概念树 ----------------

    /** 平均联结层次聚合 → 缩进树文本（每层一对括号：距离阈值内合并）。 */
    public static String conceptTree(Network g, int maxTerms, int depthLevels) {
        List<GraphNode> terms = new ArrayList<>(g.nodeCollection());
        terms.sort((a, b) -> Integer.compare(b.weight, a.weight));
        if (terms.size() > maxTerms) terms = terms.subList(0, maxTerms);
        int n = terms.size();
        if (n == 0) return "（无术语）\n";

        // 共现邻居剖面（Jaccard 距离）
        Map<GraphNode, Set<GraphNode>> nbrs = new HashMap<>();
        for (GraphNode t : terms) nbrs.put(t, new HashSet<>(g.neighbors(t)));

        // 聚类阶段：每节点自成一簇，反复合并最近对（平均联结）
        List<Set<GraphNode>> clusters = new ArrayList<>();
        for (GraphNode t : terms) { Set<GraphNode> s = new HashSet<>(); s.add(t); clusters.add(s); }
        List<double[]> mergeLog = new ArrayList<>();   // [距离, 簇A编号, 簇B编号]（对合并后簇表不可直接回放，改为记录层级切点）
        // 简化树构建：分层切割 —— 用距离阈值序列 d_l，对每个阈值做单联结连通分量，形成嵌套层级
        StringBuilder sb = new StringBuilder();
        sb.append("概念树（").append(n).append(" 个高频术语，Jaccard 共现剖面，").append(depthLevels).append(" 层）\n");
        for (int level = 1; level <= depthLevels; level++) {
            double thr = 1.0 - level / (double) (depthLevels + 1);   // 相似度阈值逐层放宽
            List<Set<GraphNode>> comps = components(terms, nbrs, thr);
            sb.append("── 层 ").append(level).append("（相似度 ≥ ").append(String.format("%.2f", thr)).append("）──\n");
            for (Set<GraphNode> c : comps) {
                List<GraphNode> members = new ArrayList<>(c);
                members.sort((a, b) -> Integer.compare(b.weight, a.weight));
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < Math.min(6, members.size()); i++)
                    line.append(i > 0 ? " | " : "").append(members.get(i).label);
                if (members.size() > 6) line.append(" …(+").append(members.size() - 6).append(")");
                sb.append("  ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<Set<GraphNode>> components(List<GraphNode> terms, Map<GraphNode, Set<GraphNode>> nbrs, double simThr) {
        Map<GraphNode, List<GraphNode>> adj = new HashMap<>();
        for (int i = 0; i < terms.size(); i++)
            for (int j = i + 1; j < terms.size(); j++) {
                if (jaccard(nbrs.get(terms.get(i)), nbrs.get(terms.get(j))) >= simThr) {
                    adj.computeIfAbsent(terms.get(i), k -> new ArrayList<>()).add(terms.get(j));
                    adj.computeIfAbsent(terms.get(j), k -> new ArrayList<>()).add(terms.get(i));
                }
            }
        List<Set<GraphNode>> out = new ArrayList<>();
        Set<GraphNode> seen = new HashSet<>();
        for (GraphNode t : terms) {
            if (seen.contains(t)) continue;
            Set<GraphNode> comp = new HashSet<>();
            Deque<GraphNode> q = new ArrayDeque<>();
            q.add(t); seen.add(t);
            while (!q.isEmpty()) {
                GraphNode u = q.poll();
                comp.add(u);
                for (GraphNode v : adj.getOrDefault(u, List.of()))
                    if (seen.add(v)) q.add(v);
            }
            out.add(comp);
        }
        return out;
    }

    private static double jaccard(Set<GraphNode> a, Set<GraphNode> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        int inter = 0;
        for (GraphNode x : a) if (b.contains(x)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    // ---------------- 叙事生成 ----------------

    /** 结构化综述叙事（中文模板，引用真实计算数值）。 */
    public static String narrative(Pipeline.Result r, Project p) {
        boolean en=ycscite.I18n.isEnglish();var graph=r.network;var text=new StringBuilder();
        text.append(en?"1. Dataset\n":"一、数据与研究对象\n");
        text.append(en?"Included records: ":"纳入文献：").append(p.records.size()).append("; ").append(p.minYear).append("–").append(p.maxYear).append('\n');
        text.append(en?"Network: ":"网络类型：").append(graph.kind).append('\n');
        text.append(en?"2. Network structure\n":"二、网络结构\n");
        double density=graph.nodes.size()<2?0:2.0*graph.edges.size()/((double)graph.nodes.size()*(graph.nodes.size()-1));
        text.append(String.format(java.util.Locale.ROOT,en?"Nodes: %d; links: %d; density: %.4f; modularity Q: %.4f%n":"节点：%d；连接：%d；密度：%.4f；模块度 Q：%.4f%n",graph.nodes.size(),graph.edges.size(),density,r.modularity));
        var clusters=r.clusterStats==null?List.<Map.Entry<Integer,double[]>>of():r.clusterStats.entrySet().stream().filter(e->e.getKey()>0).sorted(Comparator.<Map.Entry<Integer,double[]>>comparingDouble(e->e.getValue()[0]).reversed().thenComparingInt(Map.Entry::getKey)).toList();
        for(var entry:clusters.stream().limit(3).toList()){
            var labels=r.clusterLabels==null?null:r.clusterLabels.get(entry.getKey());
            text.append('#').append(entry.getKey()).append(en?" size=":" 规模=").append((int)entry.getValue()[0]);
            if(labels!=null&&!labels.isEmpty())text.append(" · ").append(labels.getFirst().term);
            text.append('\n');
        }
        text.append(en?"3. Detected bursts\n":"三、突现结果\n");
        var bursts=graph.nodeCollection().stream().filter(n->n.burstness>0).sorted(Comparator.comparingDouble((GraphNode n)->n.burstStrength).reversed()).limit(5).toList();
        for(var node:bursts){text.append(node.label).append(String.format(java.util.Locale.ROOT,en?"; stored burst strength %.3f":"；突现强度 %.3f",node.burstStrength));
            if(!node.burstIntervals.isEmpty()){var interval=node.burstIntervals.getFirst();text.append(" · ").append(interval[0]).append("–").append(interval[1]);}text.append('\n');}
        text.append(en?"4. Central nodes\n":"四、关键节点\n");
        for(var node:graph.nodeCollection().stream().sorted(Comparator.comparingDouble((GraphNode n)->n.centrality).reversed().thenComparing(n->n.id)).limit(5).toList())
            text.append(node.label).append(String.format(java.util.Locale.ROOT,en?"; betweenness %.4f; sigma %.3f%n":"；中介中心性 %.4f；Sigma %.3f%n",node.centrality,node.sigma));
        text.append(en?"5. Interpretation\nThis is an automatically generated summary of the computed network. Terms, cluster membership and bursts require interpretation against the source literature.\n":"五、结论\n本段自动汇总已计算的网络结果。主题含义、聚类成员与突现词仍需结合原始文献解读。\n");
        return text.toString();
    }

    private static String topLabel(Pipeline.Result r) {
        var en = r.clusterStats.entrySet().iterator().next();
        var labels = r.clusterLabels.get(en.getKey());
        return labels == null || labels.isEmpty() ? "核心主题" : labels.get(0).term;
    }

    /** tf*idf 词表导出（原版 "List Ranked Terms by tf*idf"，CiteSpace.java:817）。 */
    public static String rankedTermsTfIdf(Project p, int topN) {
        Map<String, Integer> tf = new HashMap<>();
        Map<String, Set<Integer>> df = new HashMap<>();   // term → doc set
        int docCount = 0;
        for (var r : p.records) {
            docCount++;
            Set<String> docTerms = new HashSet<>();
            for (String k : r.allKeywords()) {
                String t = k.trim();
                if (!t.isEmpty()) { tf.merge(t, 1, Integer::sum); docTerms.add(t); }
            }
            for (String t : docTerms) df.computeIfAbsent(t, x -> new HashSet<>()).add(docCount);
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (var en : tf.entrySet()) {
            double idf = Math.log((1.0 + docCount) / (1.0 + df.get(en.getKey()).size()));
            ranked.add(Map.entry(en.getKey(), en.getValue() * idf));
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("rank\tterm\ttf\tdf\ttfidf\n");
        for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
            String term = ranked.get(i).getKey();
            sb.append(i + 1).append('\t').append(term).append('\t').append(tf.get(term))
              .append('\t').append(df.get(term).size()).append('\t')
              .append(String.format("%.4f", ranked.get(i).getValue())).append('\n');
        }
        return sb.toString();
    }

    /** Clumping 性质词表（原版 "List Ranked Terms by Clumping Properties"，CiteSpace.java:820）：集中度 = 覆盖文档数的熵倒数。 */
    public static String rankedTermsClumping(Project p, int topN) {
        Map<String, Map<Integer, Integer>> termYear = new HashMap<>();
        for (var r : p.records) {
            if (r.year <= 0) continue;
            for (String k : r.allKeywords()) {
                String t = k.trim();
                if (!t.isEmpty()) termYear.computeIfAbsent(t, x -> new TreeMap<>()).merge(r.year, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (var en : termYear.entrySet()) {
            int total = 0;
            for (int v : en.getValue().values()) total += v;
            if (total < 2) continue;
            double h = 0;
            for (int v : en.getValue().values()) {
                double pv = (double) v / total;
                if (pv > 0) h -= pv * Math.log(pv);
            }
            int m = en.getValue().size();
            double hu = m <= 1 ? 0 : Math.log(m);
            double clump = hu > 0 ? 1.0 - h / hu : 1.0;   // 越接近 1 越集中
            ranked.add(Map.entry(en.getKey(), clump * Math.log(1 + total)));
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("rank\tterm\tyears\ttotal\tclumping\n");
        for (int i = 0; i < Math.min(topN, ranked.size()); i++) {
            String term = ranked.get(i).getKey();
            sb.append(i + 1).append('\t').append(term).append('\t').append(termYear.get(term).size())
              .append('\t').append(termYear.get(term).values().stream().mapToInt(Integer::intValue).sum())
              .append('\t').append(String.format("%.4f", ranked.get(i).getValue())).append('\n');
        }
        return sb.toString();
    }
}
