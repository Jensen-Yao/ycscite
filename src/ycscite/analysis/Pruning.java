package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * Link pruning, aligned with CiteSpace's signature options:
 *   MST       — maximum-weight spanning tree (Kruskal, keeps n-1 strongest links);
 *   Pathfinder — 原版 kV/kC 逐式（Pajek PFNET, q=n−1，经 PfDriver 差分验证）：
 *               权重视为距离（越大越远），瓶颈合成 D_k[i][j] = min_m max(D_{k−1}[i][m], W[m][j])
 *               （每轮与原始权重合成，k=2..n−1），best 逐步取小；
 *               保留边 iff 原始权重 == best[i][j]（浮点数仅经 max/min 选择，等值精确成立）。
 *               强边可被更弱的复合路径支配而删除 —— 与原版行为一致（实测：9/8 边被删、5/3/7/4 保留）。
 */
public final class Pruning {

    public static final String NONE = "none";
    public static final String MST = "mst";
    public static final String PATHFINDER = "pathfinder";

    public static void apply(Network g, String method) {
        if (g == null || g.edges.isEmpty()) return;
        switch (method == null ? NONE : method.toLowerCase()) {
            case MST -> mst(g);
            case PATHFINDER -> pathfinder(g);
            default -> { }
        }
    }

    /** Kruskal maximum spanning tree by edge weight (ties by id for determinism). */
    static void mst(Network g) {
        List<GraphEdge> sorted = new ArrayList<>(g.edges);
        sorted.sort((a, b) -> {
            int c = Integer.compare(b.weight, a.weight);
            return c != 0 ? c : (a.a.id + a.b.id).compareTo(b.a.id + b.b.id);
        });
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) idx.put(nodes.get(i), i);
        int[] parent = new int[nodes.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        Set<GraphEdge> keep = new HashSet<>();
        for (GraphEdge e : sorted) {
            int ra = root(parent, idx.get(e.a)), rb = root(parent, idx.get(e.b));
            if (ra != rb) { parent[ra] = rb; keep.add(e); }
        }
        g.edges.removeIf(e -> !keep.contains(e));
        rebuild(g);
    }

    /** PFNET (q=∞): keep (i,j) iff w_ij equals the widest-path weight between i and j. */
    static void pathfinder(Network g) {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        if (n < 3) return;
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);
        final double INF = 1001.0;                       // 原版 kC 哨兵值
        double[][] w0 = new double[n][n];                // 原始权重（缺边 0）
        for (GraphEdge e : g.edges) {
            int i = idx.get(e.a), j = idx.get(e.b);
            if (e.weight > w0[i][j]) { w0[i][j] = e.weight; w0[j][i] = e.weight; }
        }
        // D1：有边=权重，无边=INF（对角线保持 0，与 V 未设对角一致）
        double[][] d = new double[n][n], best = new double[n][n];
        for (double[] row : d) Arrays.fill(row, INF);
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                double v = i == j ? w0[i][j] : (w0[i][j] > 0 ? w0[i][j] : INF);
                d[i][j] = v;
                best[i][j] = v;
            }
        // 合成用的权重矩阵 wd：缺边=INF（无免费穿越），对角=0；保留判定对照原权重
        double[][] wd = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                wd[i][j] = i == j ? 0 : (w0[i][j] > 0 ? w0[i][j] : INF);
        // 稀疏邻接表（合成只走真实边 + 对角自由项），等价于 min_m max(d[i][m], wd[m][j])
        @SuppressWarnings("unchecked")
        List<Integer>[] adj = new List[n];
        double[][] aw = new double[n][n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (i != j && w0[i][j] > 0) { adj[j].add(i); aw[i][j] = w0[i][j]; }
        // k = 2..n−1：与原始权重逐轮瓶颈合成，best 取小；不动点早停（值只减且取自有限集）
        for (int k = 2; k <= n - 1; k++) {
            double[][] next = new double[n][n];
            boolean changed = false;
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++) {
                    double f = d[i][j];                       // m=j：max(d[i][j], wd[j][j]=0)
                    for (int m : adj[j]) {                    // m 使 w0[m][j] 存在
                        double mm = Math.max(d[i][m], aw[m][j]);
                        if (mm < f) f = mm;
                    }
                    next[i][j] = next[j][i] = f;
                    if (f < best[i][j]) { best[i][j] = f; best[j][i] = f; }
                    if (f < d[i][j]) changed = true;
                }
            if (!changed) break;                              // D 已收敛：后续轮不会再降
            d = next;
        }
        // 保留：原始权重 == best（浮点值仅经 max/min 选择传递，精确等值可靠）
        Set<GraphEdge> keep = new HashSet<>();
        for (GraphEdge e : g.edges) {
            int i = idx.get(e.a), j = idx.get(e.b);
            if (w0[i][j] == best[i][j]) keep.add(e);
        }
        g.edges.removeIf(e -> !keep.contains(e));
        rebuild(g);
    }

    private static String key(GraphNode a, GraphNode b) {
        return a.id.compareTo(b.id) < 0 ? a.id + "\u0001" + b.id : b.id + "\u0001" + a.id;
    }

    private static int root(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void rebuild(Network g) {
        g.rebuildAdjacency();
    }
}
