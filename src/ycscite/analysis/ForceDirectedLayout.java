package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * Force-Directed 布局（原版 ph:1042 "Force Directed"，经典 Fruchterman-Reingold 模型）。
 * 弹簧-电荷模型：节点间库仑斥力 + 边弹簧引力，迭代至平衡。
 */
public final class ForceDirectedLayout {

    public static Map<GraphNode, Point2D.Double> layout(Network g, long seed, int iterations) {
        Map<GraphNode, Point2D.Double> out = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        int n = nodes.size();
        if (n == 0) return out;
        if (n == 1) { out.put(nodes.get(0), new Point2D.Double(0, 0)); return out; }

        Random rnd = new Random(seed);
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = rnd.nextGaussian(); y[i] = rnd.nextGaussian(); }

        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i), i);
        // 邻接表
        @SuppressWarnings("unchecked")
        List<Integer>[] adj = new List[n];
        for (int i = 0; i < n; i++) adj[i] = new ArrayList<>();
        for (GraphEdge e : g.edges) {
            int a = idx.get(e.a), b = idx.get(e.b);
            adj[a].add(b); adj[b].add(a);
        }

        double k = Math.sqrt(1.0 / n);    // 理想距离
        double temperature = 1.0;
        double minTemp = 0.001;

        for (int iter = 0; iter < iterations; iter++) {
            temperature = Math.max(minTemp, 1.0 * (1 - (double) iter / iterations));
            double[] fx = new double[n], fy = new double[n];

            // 斥力（所有节点对）
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++) {
                    double dx = x[j] - x[i], dy = y[j] - y[i];
                    double d2 = dx * dx + dy * dy + 1e-6;
                    double d = Math.sqrt(d2);
                    double force = k * k / d;
                    fx[i] -= dx / d * force; fy[i] -= dy / d * force;
                    fx[j] += dx / d * force; fy[j] += dy / d * force;
                }

            // 弹簧引力（边）
            for (int i = 0; i < n; i++)
                for (int j : adj[i]) {
                    if (i >= j) continue;    // 只算一次
                    double dx = x[j] - x[i], dy = y[j] - y[i];
                    double d = Math.sqrt(dx * dx + dy * dy + 1e-6);
                    double force = d * d / k;
                    fx[i] += dx / d * force; fy[i] += dy / d * force;
                    fx[j] -= dx / d * force; fy[j] -= dy / d * force;
                }

            // 位移（限温）
            for (int i = 0; i < n; i++) {
                double dx = fx[i] * temperature, dy = fy[i] * temperature;
                double dm = Math.sqrt(dx * dx + dy * dy);
                if (dm > temperature) { dx = dx / dm * temperature; dy = dy / dm * temperature; }
                x[i] += dx * 0.1;
                y[i] += dy * 0.1;
            }
        }

        for (int i = 0; i < n; i++)
            out.put(nodes.get(i), new Point2D.Double(x[i] * 100, y[i] * 100));
        return out;
    }
}
