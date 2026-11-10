package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * Spectral clustering (Shi–Malik / NJW style, aligned with CiteSpace's default clustering):
 * normalized similarity matrix S = D^{-1/2} A D^{-1/2}, top-k eigenvectors by power iteration
 * with deflation, then k-means. The cluster count k is chosen in 2..kmax by the best
 * resulting modularity. Falls back to greedy modularity for large networks or degenerate input.
 */
public final class SpectralClusterer {

    public static final int MAX_N = 300;

    public static double compute(Network g) {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        if (n < 3 || n > MAX_N || g.edges.isEmpty()) return Clusterer.compute(g);

        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);
        double[][] s = new double[n][n];
        double[] d = new double[n];
        for (GraphEdge e : g.edges) {
            int i = idx.get(e.a), j = idx.get(e.b);
            s[i][j] += e.weight;
            s[j][i] += e.weight;
            d[i] += e.weight;
            d[j] += e.weight;
        }
        double[] invSqrt = new double[n];
        for (int i = 0; i < n; i++) invSqrt[i] = d[i] > 0 ? 1.0 / Math.sqrt(d[i]) : 0;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                s[i][j] *= invSqrt[i] * invSqrt[j];

        int kmax = Math.min(10, n - 1);
        double[][] vecs = new double[kmax + 1][n];   // vecs[1..kmax]
        double[] eigs = new double[kmax + 1];
        double[][] work = new double[n][n];
        for (int i = 0; i < n; i++) work[i] = s[i].clone();

        for (int k = 1; k <= kmax; k++) {
            double[] v = powerIterate(work, k * 7 + 13);
            eigs[k] = v == null ? 0 : rayleigh(work, v);
            vecs[k] = v;
            if (v != null) deflate(work, v, eigs[k]);
        }

        // try k = 2..kmax, keep the partition with the best modularity
        int bestK = 2;
        int[] bestAssign = null;
        double bestQ = Double.NEGATIVE_INFINITY;
        for (int k = 2; k <= kmax; k++) {
            double[][] emb = new double[n][k];
            for (int i = 0; i < n; i++)
                for (int d2 = 1; d2 <= k; d2++)
                    emb[i][d2 - 1] = vecs[d2] == null ? 0 : vecs[d2][i];
            int[] assign = kmeans(emb, k, k);
            double q = qOf(g, idx, assign);
            if (q > bestQ) { bestQ = q; bestAssign = assign; bestK = k; }
        }
        if (bestAssign == null || bestQ <= Double.NEGATIVE_INFINITY) return Clusterer.compute(g);

        // compare against greedy modularity; keep the better partition (quality guard)
        double qMod = Clusterer.compute(g);
        Map<GraphNode, Integer> modAssign = new HashMap<>();
        for (GraphNode nd : nodes) modAssign.put(nd, nd.cluster);
        if (qMod > bestQ + 0.02) {
            return qMod; // Clusterer already assigned node.cluster
        }

        Map<Integer, Integer> sizes = new HashMap<>();
        for (int a : bestAssign) sizes.merge(a, 1, Integer::sum);
        List<Integer> ids = new ArrayList<>(sizes.keySet());
        ids.sort((x, y) -> Integer.compare(sizes.get(y), sizes.get(x)));
        Map<Integer, Integer> relabel = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) relabel.put(ids.get(i), i + 1);   // 1..k（0=未归类，与 Clusterer 口径统一）
        for (int i = 0; i < n; i++) nodes.get(i).cluster = relabel.get(bestAssign[i]);
        return bestQ;
    }

    private static double qOf(Network g, Map<GraphNode, Integer> idx, int[] assign) {
        long m2 = 0;
        for (GraphEdge e : g.edges) m2 += e.weight;
        if (m2 == 0) return 0;
        Map<Integer, Long> sTot = new HashMap<>(), sIn = new HashMap<>();
        for (GraphNode nd : g.nodeCollection()) {
            long deg = 0;
            for (GraphNode nb : g.neighbors(nd))
                for (GraphEdge e : g.edges)
                    if ((e.a == nd && e.b == nb) || (e.b == nd && e.a == nb)) deg += e.weight;
            sTot.merge(assign[idx.get(nd)], deg, Long::sum);
        }
        for (GraphEdge e : g.edges)
            if (assign[idx.get(e.a)] == assign[idx.get(e.b)])
                sIn.merge(assign[idx.get(e.a)], (long) e.weight, Long::sum);
        double q = 0;
        for (Long w : sIn.values()) q += w / (double) m2;
        for (Long t : sTot.values()) q -= (t / (double) m2) * (t / (double) m2);
        return q;
    }

    private static double[] powerIterate(double[][] m, long seed) {
        int n = m.length;
        Random rnd = new Random(seed);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = rnd.nextGaussian();
        normalize(v);
        double[] next = new double[n];
        for (int it = 0; it < 128; it++) {
            for (int i = 0; i < n; i++) {
                double s = 0;
                for (int j = 0; j < n; j++) s += m[i][j] * v[j];
                next[i] = s;
            }
            double norm = norm(next);
            if (norm < 1e-12) return null;
            for (int i = 0; i < n; i++) next[i] /= norm;
            double diff = 0;
            for (int i = 0; i < n; i++) diff += Math.abs(next[i] - v[i]);
            System.arraycopy(next, 0, v, 0, n);
            if (diff < 1e-10) break;
        }
        return v;
    }

    private static double rayleigh(double[][] m, double[] v) {
        int n = m.length;
        double num = 0;
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += m[i][j] * v[j];
            num += v[i] * s;
        }
        return num;
    }

    private static void deflate(double[][] m, double[] v, double lambda) {
        int n = m.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                m[i][j] -= lambda * v[i] * v[j];
    }

    private static int[] kmeans(double[][] pts, int k, long seed) {
        int n = pts.length, dim = pts[0].length;
        Random rnd = new Random(seed);
        double[][] cent = new double[k][dim];
        for (int c = 0; c < k; c++) cent[c] = pts[rnd.nextInt(n)].clone();
        int[] assign = new int[n];
        for (int it = 0; it < 40; it++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = 0;
                double bestD = Double.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    double dd = 0;
                    for (int t = 0; t < dim; t++) { double df = pts[i][t] - cent[c][t]; dd += df * df; }
                    if (dd < bestD) { bestD = dd; best = c; }
                }
                if (assign[i] != best) { assign[i] = best; changed = true; }
            }
            double[][] sum = new double[k][dim];
            int[] cnt = new int[k];
            for (int i = 0; i < n; i++) {
                cnt[assign[i]]++;
                for (int t = 0; t < dim; t++) sum[assign[i]][t] += pts[i][t];
            }
            for (int c = 0; c < k; c++)
                if (cnt[c] > 0)
                    for (int t = 0; t < dim; t++) cent[c][t] = sum[c][t] / cnt[c];
            if (!changed) break;
        }
        return assign;
    }

    private static void normalize(double[] v) { double nn = norm(v); if (nn > 0) for (int i = 0; i < v.length; i++) v[i] /= nn; }
    private static double norm(double[] v) { double s = 0; for (double x : v) s += x * x; return Math.sqrt(s); }
}
