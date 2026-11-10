package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.Network;
import java.util.*;

/** 聚类依赖（原版 "Show Cluster Dependencies"，ph:2053-2063）：dep(i,j) = (i2j−j2i)/(i2j+j2i)。 */
public final class ClusterDependency {

    public record Dep(int from, int to, double score, int links) { }

    public static List<Dep> compute(Network g) {
        Map<Integer, Map<Integer, Integer>> flow = new TreeMap<>();
        for (GraphEdge e : g.edges) {
            int a = e.a.cluster, b = e.b.cluster;
            if (a == b) continue;
            flow.computeIfAbsent(a, k -> new TreeMap<>()).merge(b, e.weight, Integer::sum);
            flow.computeIfAbsent(b, k -> new TreeMap<>()).merge(a, e.weight, Integer::sum);
        }
        List<Dep> out = new ArrayList<>();
        for (var en : flow.entrySet())
            for (var to : en.getValue().entrySet()) {
                if (en.getKey() >= to.getKey()) continue;   // 只算上三角
                int i2j = to.getValue(), j2i = flow.getOrDefault(to.getKey(), Map.of()).getOrDefault(en.getKey(), 0);
                if (i2j + j2i == 0) continue;
                double score = (double) (i2j - j2i) / (i2j + j2i);
                out.add(new Dep(en.getKey(), to.getKey(), score, i2j + j2i));
            }
        out.sort((x, y) -> Integer.compare(y.links(), x.links()));
        return out;
    }

    public static String toTsv(List<Dep> deps) {
        StringBuilder sb = new StringBuilder("from_cluster\tto_cluster\tscore\tlinks\n");
        for (Dep d : deps)
            sb.append('#').append(d.from()).append('\t').append('#').append(d.to()).append('\t')
              .append(String.format("%.4f", d.score())).append('\t').append(d.links()).append('\n');
        return sb.toString();
    }
}
