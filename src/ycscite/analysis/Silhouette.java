package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Silhouette coefficient per node and per cluster (CiteSpace's cluster quality metric).
 * Distance between adjacent nodes: 1/(1+weight); disconnected pairs use a large constant.
 */
public final class Silhouette {

    /** node -> silhouette in [-1,1]. */
    public static Map<GraphNode, Double> compute(Network g) {
        Map<GraphNode, Double> out = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        if (n == 0) return out;
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);
        final double FAR = 10.0;
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) {
            java.util.Arrays.fill(d[i], FAR);
            d[i][i] = 0;
        }
        for (var e : g.edges) {
            int i = idx.get(e.a), j = idx.get(e.b);
            double dd = 1.0 / (1 + e.weight);
            d[i][j] = Math.min(d[i][j], dd);
            d[j][i] = d[i][j];
        }
        // shortest paths (Floyd, small n)
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (d[i][k] + d[k][j] < d[i][j]) d[i][j] = d[i][k] + d[k][j];

        Map<Integer, List<Integer>> byCluster = new HashMap<>();
        for (int i = 0; i < n; i++)
            byCluster.computeIfAbsent(nodes.get(i).cluster, k -> new ArrayList<>()).add(i);
        int clusters = byCluster.size();
        for (int i = 0; i < n; i++) {
            int ci = nodes.get(i).cluster;
            List<Integer> own = byCluster.get(ci);
            if (own.size() <= 1 || clusters < 2) { out.put(nodes.get(i), 0.0); continue; }
            double a = 0;
            for (int j : own) if (j != i) a += d[i][j];
            a /= (own.size() - 1);
            double b = Double.POSITIVE_INFINITY;
            for (var en : byCluster.entrySet()) {
                if (en.getKey() == ci) continue;
                double m = 0;
                for (int j : en.getValue()) m += d[i][j];
                m /= en.getValue().size();
                b = Math.min(b, m);
            }
            double s = Math.max(a, b) == 0 ? 0 : (b - a) / Math.max(a, b);
            out.put(nodes.get(i), Math.max(-1, Math.min(1, s)));
        }
        return out;
    }

    /** cluster -> mean silhouette of its members. */
    public static Map<Integer, Double> perCluster(Network g, Map<GraphNode, Double> perNode) {
        Map<Integer, Double> sum = new HashMap<>();
        Map<Integer, Integer> cnt = new HashMap<>();
        for (var en : perNode.entrySet()) {
            sum.merge(en.getKey().cluster, en.getValue(), Double::sum);
            cnt.merge(en.getKey().cluster, 1, Integer::sum);
        }
        Map<Integer, Double> out = new java.util.TreeMap<>();
        for (var e : sum.entrySet()) out.put(e.getKey(), e.getValue() / cnt.get(e.getKey()));
        return out;
    }
}
