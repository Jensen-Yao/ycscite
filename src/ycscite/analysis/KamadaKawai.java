package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.awt.geom.Point2D;
import java.util.*;

/** Kamada-Kawai 布局（原版 ph:1039，经典弹簧模型：全对最短路做理想距离）。 */
public final class KamadaKawai {

    public static Map<GraphNode, Point2D.Double> layout(Network g, long seed, int iterations) {
        Map<GraphNode, Point2D.Double> out = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        if (n == 0) return out;
        if (n == 1) { out.put(nodes.get(0), new Point2D.Double(0, 0)); return out; }

        // 全对最短路（Floyd，小图专用；大图退化用跳数=权重倒数）
        double[][] dist = new double[n][n];
        for (double[] row : dist) Arrays.fill(row, Double.POSITIVE_INFINITY);
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) { idx.put(nodes.get(i), i); dist[i][i] = 0; }
        for (GraphEdge e : g.edges) {
            int a = idx.get(e.a), b = idx.get(e.b);
            double w = 1.0 / Math.max(e.weight, 1);
            dist[a][b] = Math.min(dist[a][b], w);
            dist[b][a] = dist[a][b];
        }
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    if (dist[i][k] + dist[k][j] < dist[i][j]) dist[i][j] = dist[i][k] + dist[k][j];
        // 无限距离（不连通）设为 3
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (Double.isInfinite(dist[i][j])) dist[i][j] = 3.0;

        // 初始位置（圆周）
        Random rnd = new Random(seed);
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n + rnd.nextDouble() * 0.1;
            x[i] = Math.cos(a);
            y[i] = Math.sin(a);
        }

        // KK 迭代：每次找 Δ 最大的节点做局部 Newton 步
        double L = 1.0, K = 1.0;
        for (int it = 0; it < iterations; it++) {
            int worst = -1;
            double worstD = 0;
            for (int i = 0; i < n; i++) {
                double dx = 0, dy = 0;
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    double dxij = x[i] - x[j], dyij = y[i] - y[j];
                    double d = Math.sqrt(dxij * dxij + dyij * dyij) + 1e-9;
                    double target = L * dist[i][j];
                    double kij = K / (dist[i][j] * dist[i][j]);
                    dx += kij * (dxij - target * dxij / d);
                    dy += kij * (dyij - target * dyij / d);
                }
                double mag = Math.sqrt(dx * dx + dy * dy);
                if (mag > worstD) { worstD = mag; worst = i; }
            }
            if (worst < 0 || worstD < 1e-4) break;
            // 对 worst 做 10 步梯度下降
            int m = worst;
            for (int step = 0; step < 10; step++) {
                double fx = 0, fy = 0;
                for (int j = 0; j < n; j++) {
                    if (m == j) continue;
                    double dx = x[m] - x[j], dy = y[m] - y[j];
                    double d = Math.sqrt(dx * dx + dy * dy) + 1e-9;
                    double target = L * dist[m][j];
                    double kij = K / (dist[m][j] * dist[m][j]);
                    fx += kij * dx * (1 - target / d);
                    fy += kij * dy * (1 - target / d);
                }
                x[m] -= 0.01 * fx;
                y[m] -= 0.01 * fy;
            }
        }
        for (int i = 0; i < n; i++) out.put(nodes.get(i), new Point2D.Double(x[i] * 100, y[i] * 100));
        return out;
    }
}
