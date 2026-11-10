package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * UMAP 视图布局（原版 7.0 "Structural UMAP / UMAP on toolbar" 的零依赖简化实现，UMAP-lite）：
 * kNN 图（k=15）→ 模糊并集权重 → 吸引-排斥力布局（初始随机，固定种子），
 * 与完整 UMAP 的 SGD 目标同向但为一阶近似（结构一致、非逐式）。
 */
public final class Umap {

    public static Map<GraphNode, Point2D.Double> layout(Network g, long seed, int iterations) {
        Map<GraphNode, Point2D.Double> out = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        if (n == 0) return out;
        if (n == 1) { out.put(nodes.get(0), new Point2D.Double(0, 0)); return out; }
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);
        double[][] w = new double[n][n];
        for (GraphEdge e : g.edges) {
            int i = idx.get(e.a), j = idx.get(e.b);
            double v = e.weight;
            w[i][j] = Math.max(w[i][j], v);
            w[j][i] = w[i][j];
        }
        // kNN 模糊并集：w_umap(i,j) = w_ij / (min_kNN(i) + min_kNN(j) − w_ij)（UMAP 集合并公式）
        int k = Math.min(15, n - 1);
        double[] kth = new double[n];
        for (int i = 0; i < n; i++) {
            double[] row = w[i].clone();
            Arrays.sort(row);
            kth[i] = row[n - 1 - k] > 0 ? row[n - 1 - k] : 1;
        }
        double[][] uw = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (w[i][j] > 0) {
                    double u = w[i][j] / Math.max(kth[i] + kth[j] - w[i][j], 1e-9);
                    uw[i][j] = uw[j][i] = Math.min(1, u);
                }
        Random rnd = new Random(seed);
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = rnd.nextDouble() - 0.5; y[i] = rnd.nextDouble() - 0.5; }
        // 吸引（kNN 边）+ 均匀排斥（采样近似：每轮每节点随机 m 个对手）
        for (int it = 0; it < iterations; it++) {
            double lr = 0.5 * (1 - it / (double) iterations) + 0.02;
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++) {
                    if (uw[i][j] <= 0) continue;
                    double dx = x[j] - x[i], dy = y[j] - y[i];
                    double d2 = dx * dx + dy * dy + 1e-9;
                    double f = lr * uw[i][j] * Math.min(d2, 2) * 0.5;
                    x[i] += dx * f; y[i] += dy * f;
                    x[j] -= dx * f; y[j] -= dy * f;
                }
            for (int i = 0; i < n; i++)
                for (int m = 0; m < 6; m++) {
                    int j = rnd.nextInt(n);
                    if (j == i) continue;
                    double dx = x[j] - x[i], dy = y[j] - y[i];
                    double d2 = dx * dx + dy * dy + 1e-9;
                    double rep = lr * 0.02 / d2;
                    x[i] -= dx * rep; y[i] -= dy * rep;
                    x[j] += dx * rep; y[j] += dy * rep;
                }
            // 微弱向心，防漂移
            for (int i = 0; i < n; i++) { x[i] *= 0.999; y[i] *= 0.999; }
        }
        for (int i = 0; i < n; i++) out.put(nodes.get(i), new Point2D.Double(x[i] * 100, y[i] * 100));
        return out;
    }
}
