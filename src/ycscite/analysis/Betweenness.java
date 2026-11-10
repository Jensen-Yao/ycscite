package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * Betweenness centrality (Brandes 2001), weighted, undirected.
 * Stored normalized by (n-1)(n-2)/2 like CiteSpace's display convention.
 */
public final class Betweenness {

    public static void compute(Network g) {
        int n = g.nodes.size();
        if (n < 3) { for (GraphNode v : g.nodeCollection()) v.centrality = 0; return; }
        Map<GraphNode, Double> cb = new HashMap<>();
        for (GraphNode v : g.nodeCollection()) cb.put(v, 0.0);

        Map<GraphNode, Map<GraphNode, Double>> dist = new HashMap<>();
        Map<GraphNode, Map<GraphNode, Double>> sigma = new HashMap<>();
        Map<GraphNode, Map<GraphNode, List<GraphNode>>> pred = new HashMap<>();
        for (GraphNode v : g.nodeCollection()) {
            dist.put(v, new HashMap<>()); sigma.put(v, new HashMap<>()); pred.put(v, new HashMap<>());
        }

        for (GraphNode s : g.nodeCollection()) {
            Deque<GraphNode> stack = new ArrayDeque<>();
            Queue<GraphNode> queue = new ArrayDeque<>();
            Map<GraphNode, Double> d = dist.get(s);
            Map<GraphNode, Double> sig = sigma.get(s);
            queue.add(s); d.put(s, 0.0); sig.put(s, 1.0);
            while (!queue.isEmpty()) {
                GraphNode v = queue.poll();
                stack.push(v);
                for (GraphNode w : g.neighbors(v)) {
                    if (!d.containsKey(w)) { d.put(w, d.get(v) + 1); queue.add(w); }
                    if (d.get(w) == d.get(v) + 1) {
                        sig.merge(w, sig.get(v), Double::sum);
                        pred.get(s).computeIfAbsent(w, k -> new ArrayList<>()).add(v);
                    }
                }
            }
            Map<GraphNode, Double> delta = new HashMap<>();
            for (GraphNode v : g.nodeCollection()) delta.put(v, 0.0);
            while (!stack.isEmpty()) {
                GraphNode w = stack.pop();
                List<GraphNode> pw = pred.get(s).getOrDefault(w, Collections.emptyList());
                for (GraphNode v : pw) {
                    double c = (sig.get(v) / sig.get(w)) * (1.0 + delta.get(w));
                    delta.merge(v, c, Double::sum);
                }
                if (w != s) cb.merge(w, delta.get(w), Double::sum);
            }
        }
        double norm = (n - 1) * (n - 2) / 2.0;
        // Brandes over all sources double-counts unordered pairs on undirected graphs
        for (GraphNode v : g.nodeCollection()) v.centrality = cb.get(v) / 2.0 / norm;
    }
}
