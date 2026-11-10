package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.List;

/** PageRank on the undirected network (edges treated both ways), damping 0.85, values sum to 1. */
public final class PageRank {

    public static void compute(Network g) {
        compute(g, 0.85, 1e-10, 200);
    }

    public static void compute(Network g, double damping, double eps, int maxIter) {
        List<GraphNode> nodes = List.copyOf(g.nodeCollection());
        int n = nodes.size();
        if (n == 0) return;
        double[] pr = new double[n];
        double[] next = new double[n];
        java.util.Map<GraphNode, Integer> idx = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) { idx.put(nodes.get(i), i); pr[i] = 1.0 / n; }

        int[] deg = new int[n];
        for (var e : g.edges) {
            Integer a = idx.get(e.a), b = idx.get(e.b);
            if (a != null && b != null && !a.equals(b)) { deg[a]++; deg[b]++; }
            else if (a != null) deg[a]++; // self-loop-ish safety
        }
        for (int it = 0; it < maxIter; it++) {
            java.util.Arrays.fill(next, 0);
            double dangling = 0;
            for (int i = 0; i < n; i++) if (deg[i] == 0) dangling += pr[i];
            for (var e : g.edges) {
                Integer ai = idx.get(e.a), bi = idx.get(e.b);
                if (ai == null || bi == null || ai.equals(bi)) continue;
                int w = e.weight;
                next[ai] += pr[bi] * w / deg[bi];
                next[bi] += pr[ai] * w / deg[ai];
            }
            double base = (1 - damping) / n + damping * dangling / n;
            double diff = 0;
            for (int i = 0; i < n; i++) {
                next[i] = next[i] * damping + base;
                diff += Math.abs(next[i] - pr[i]);
            }
            System.arraycopy(next, 0, pr, 0, n);
            if (diff < eps) break;
        }
        double sum = 0;
        for (double v : pr) sum += v;
        if (sum <= 0) sum = 1;
        for (int i = 0; i < n; i++) nodes.get(i).pageRank = pr[i] / sum;
    }
}
