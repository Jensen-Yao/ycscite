package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * 相似网络（原版 "Draw Similarity Networks (LSA)"，kh:578 / ph:1930-1950）：
 * 对选中节点，计算其与全部其他节点的余弦相似度（基于簇分布向量），
 * 取 Top-K 相似节点生成子网络。
 */
public final class SimilarityNetwork {

    /**
     * 计算节点间的余弦相似度（基于簇×节点权重矩阵的行向量）。
     * 返回 target 的 Top-K 相似节点列表（降序）。
     */
    public static List<Map.Entry<GraphNode, Double>> topSimilar(Network g, GraphNode target, int topK) {
        // 节点 → 聚类分布（本实现用节点邻居的聚类分布作为向量）
        Map<GraphNode, Map<Integer, Double>> vectors = new HashMap<>();
        for (GraphNode n : g.nodeCollection()) {
            Map<Integer, Double> v = new HashMap<>();
            v.merge(n.cluster, (double) n.weight, Double::sum);
            for (GraphNode nb : g.neighbors(n))
                v.merge(nb.cluster, (double) nb.weight * 0.5, Double::sum);
            vectors.put(n, v);
        }
        Map<Integer, Double> tv = vectors.get(target);
        if (tv == null) return List.of();
        double tvNorm = norm(tv);

        List<Map.Entry<GraphNode, Double>> scored = new ArrayList<>();
        for (GraphNode n : g.nodeCollection()) {
            if (n == target) continue;
            Map<Integer, Double> nv = vectors.get(n);
            double dot = 0;
            for (var en : tv.entrySet()) {
                Double other = nv.get(en.getKey());
                if (other != null) dot += en.getValue() * other;
            }
            double nNorm = norm(nv);
            double sim = (tvNorm > 0 && nNorm > 0) ? dot / (tvNorm * nNorm) : 0;
            if (sim > 0.01) scored.add(Map.entry(n, sim));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    /** 从 target 构建 Top-K 相似子网络。 */
    public static Network buildSimilarSubnet(Network g, GraphNode target, int topK) {
        Network sub = new Network("Similarity (LSA) of " + target.label);
        var similar = topSimilar(g, target, topK);
        List<GraphNode> keep = new ArrayList<>();
        keep.add(sub.node(target.id, target.label, target.kind));
        for (var en : similar)
            keep.add(sub.node(en.getKey().id, en.getKey().label, en.getKey().kind));
        // 原网络中保留节点间的边
        Set<String> ids = new HashSet<>();
        for (GraphNode n : keep) ids.add(n.id);
        for (var e : g.edges)
            if (ids.contains(e.a.id) && ids.contains(e.b.id)) {
                var a = sub.nodes.get(e.a.id);
                var b = sub.nodes.get(e.b.id);
                if (a != null && b != null) sub.linkWeighted(a, b, e.weight);
            }
        return sub;
    }

    private static double norm(Map<Integer, Double> v) {
        double s = 0;
        for (double x : v.values()) s += x * x;
        return Math.sqrt(s);
    }
}
