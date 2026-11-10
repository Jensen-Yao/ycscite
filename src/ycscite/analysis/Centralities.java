package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 附加中心性指标（原版 Display: Node Labels/Size by Degree/Eigenvector Centrality 的证据对齐）：
 * 度中心性 = 度/(n-1)；特征向量中心性 = 邻接幂迭代主特征向量（归一化最大值=1）。
 */
public final class Centralities {

    public static void compute(Network g) {
        List<GraphNode> nodes = List.copyOf(g.nodeCollection());
        int n = nodes.size();
        if (n == 0) return;
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);

        // 度中心性（含权重为强度；归一化 n-1）
        double[] strength = new double[n];
        for (var e : g.edges) {
            Integer a = idx.get(e.a), b = idx.get(e.b);
            if (a == null || b == null) continue;
            strength[a] += e.weight;
            strength[b] += e.weight;
        }
        for (int i = 0; i < n; i++)
            nodes.get(i).degreeCentrality = n > 1 ? strength[i] / (n - 1) : 0;

        // 特征向量中心性（幂迭代）
        double[] v = new double[n];
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < n; i++) v[i] = rnd.nextDouble() + 0.1;
        double[] next = new double[n];
        for (int it = 0; it < 200; it++) {
            for (var e : g.edges) {
                Integer a = idx.get(e.a), b = idx.get(e.b);
                if (a == null || b == null) continue;
                next[a] += v[b] * e.weight;
                next[b] += v[a] * e.weight;
            }
            double norm = 0;
            for (double x : next) norm += x * x;
            norm = Math.sqrt(norm);
            if (norm < 1e-12) break;
            double diff = 0;
            for (int i = 0; i < n; i++) {
                next[i] /= norm;
                diff += Math.abs(next[i] - v[i]);
                v[i] = next[i];
            }
            java.util.Arrays.fill(next, 0);
            if (diff < 1e-12) break;
        }
        double max = 0;
        for (double x : v) max = Math.max(max, x);
        if (max <= 0) max = 1;
        for (int i = 0; i < n; i++) nodes.get(i).eigenCentrality = v[i] / max;
    }
}
