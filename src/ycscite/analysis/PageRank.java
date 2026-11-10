package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.List;

/** Weighted PageRank on the undirected network; each positive edge is traversable both ways.
 * Transition weights are divided by incident weight sum (not edge count). Damping .85; sum is 1.
 */
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

        if(!Double.isFinite(damping)||damping<0||damping>1||!Double.isFinite(eps)||eps<=0||maxIter<=0)throw new IllegalArgumentException("Invalid PageRank convergence parameters");
        double[] strength = new double[n];
        for (var e : g.edges) {
            Integer a = idx.get(e.a), b = idx.get(e.b);
            if (a != null && b != null && !a.equals(b) && e.weight>0) { strength[a]+=e.weight;strength[b]+=e.weight; }
        }
        for (int it = 0; it < maxIter; it++) {
            java.util.Arrays.fill(next, 0);
            double dangling = 0;
            for (int i = 0; i < n; i++) if (strength[i] == 0) dangling += pr[i];
            for (var e : g.edges) {
                Integer ai = idx.get(e.a), bi = idx.get(e.b);
                if (ai == null || bi == null || ai.equals(bi) || e.weight<=0) continue;
                int w = e.weight;
                next[ai] += pr[bi] * (w / strength[bi]);
                next[bi] += pr[ai] * (w / strength[ai]);
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
