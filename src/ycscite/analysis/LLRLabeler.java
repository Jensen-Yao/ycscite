package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * Cluster labeling by log-likelihood ratio (Dunning 1993), the "LLR" labeling
 * CiteSpace uses: rank candidate labels by G² of their concentration inside a
 * cluster versus the rest of the network.
 */
public final class LLRLabeler {

    public static Map<Integer, List<Scored>> compute(Network g, int topPerCluster) {
        Map<Integer, List<Scored>> out = new TreeMap<>();
        Map<Integer, List<GraphNode>> clusters = new HashMap<>();
        for (GraphNode n : g.nodeCollection())
            if (n.cluster >= 0) clusters.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);

        // term = node label; frequency measured by node weight
        Map<String, Integer> termTotal = new HashMap<>();
        for (GraphNode n : g.nodeCollection())
            for (String t : terms(n)) termTotal.merge(t, n.weight, Integer::sum);
        long grand = 0;
        for (int v : termTotal.values()) grand += v;

        for (Map.Entry<Integer, List<GraphNode>> en : clusters.entrySet()) {
            int c = en.getKey();
            List<GraphNode> in = en.getValue();
            Map<String, Integer> termIn = new HashMap<>();
            long inTotal = 0;
            for (GraphNode n : in) {
                for (String t : terms(n)) termIn.merge(t, n.weight, Integer::sum);
                inTotal += n.weight;
            }
            long outTotal = grand - inTotal;
            List<Scored> scored = new ArrayList<>();
            for (Map.Entry<String, Integer> te : termIn.entrySet()) {
                int a = te.getValue();                       // term in cluster
                int b = termTotal.get(te.getKey()) - a;      // term outside
                long cc = inTotal - a;                       // other terms in cluster
                long dd = outTotal - b;                      // other terms outside
                double g2 = llr2(a, b, (int) cc, (int) dd);
                if (g2 > 0) scored.add(new Scored(te.getKey(), g2));
            }
            scored.sort((x, y) -> Double.compare(y.g2, x.g2));
            out.put(c, scored.subList(0, Math.min(topPerCluster, scored.size())));
        }
        return out;
    }

    /** Terms for a node: the whole label plus its significant words. */
    static List<String> terms(GraphNode n) {
        List<String> out = new ArrayList<>();
        String l = n.label == null ? n.id : n.label;
        if (!l.isBlank()) out.add(l);
        return out;
    }

    /** 2x2 log-likelihood ratio G² (Dunning 1993): 2 * Σ o·ln(o/e) over the four cells. */
    static double llr2(int a, int b, int c, int d) {
        double n = a + b + c + d;
        if (n <= 0 || a < 0 || b < 0 || c < 0 || d < 0) return 0;
        double k1 = a + b, k2 = a + c;
        double p2 = k2 / n;                 // column probability
        double eA = k1 * p2;                // expected a
        double eB = k1 * (1 - p2);          // expected b
        double eC = (n - k1) * p2;          // expected c
        double eD = (n - k1) * (1 - p2);    // expected d
        return 2 * (term(a, eA) + term(b, eB) + term(c, eC) + term(d, eD));
    }

    private static double term(int o, double e) {
        if (o == 0 || e <= 0) return 0;
        return o * Math.log(o / e);
    }

    public static final class Scored {
        public final String term;
        public final double g2;
        public Scored(String term, double g2) { this.term = term; this.g2 = g2; }
    }
}
