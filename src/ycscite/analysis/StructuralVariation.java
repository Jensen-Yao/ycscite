package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.model.Project;
import ycscite.model.Record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 结构变异分析（对应原版 "Analytics: Structural Variation Analysis (SVA)"，Chen 2012）：
 * 按年累计构建网络，统计每年新增节点/新增链接与变异分（variation = newNodes + newLinks）。
 */
public final class StructuralVariation {

    public record Row(int year, int nodes, int newNodes, int newLinks, double variation) { }

    public static List<Row> compute(Project p, String networkKind) {
        List<Record> recs = new ArrayList<>(p.records);
        recs.removeIf(r -> r.year <= 0);
        recs.sort((a, b) -> Integer.compare(a.year, b.year));
        List<Row> out = new ArrayList<>();
        if (recs.isEmpty()) return out;

        Map<String, Integer> seenNodes = new TreeMap<>();
        Map<String, String> seenEdges = new TreeMap<>();
        List<Record> acc = new ArrayList<>();
        int yearCursor = recs.get(0).year;
        for (Record r : recs) {
            if (r.year != yearCursor) {
                out.add(diff(acc, networkKind, seenNodes, seenEdges, yearCursor));
                yearCursor = r.year;
            }
            acc.add(r);
        }
        out.add(diff(acc, networkKind, seenNodes, seenEdges, yearCursor));
        return out;
    }

    private static Row diff(List<Record> acc, String kind, Map<String, Integer> seenNodes,
                            Map<String, String> seenEdges, int year) {
        Network g = NetworkBuilders.build(kind, acc);
        int newNodes = 0;
        for (GraphNode n : g.nodeCollection())
            if (!seenNodes.containsKey(n.id)) { seenNodes.put(n.id, 1); newNodes++; }
        int newLinks = 0;
        for (var e : g.edges) {
            String k = e.a.id.compareTo(e.b.id) < 0 ? e.a.id + "|" + e.b.id : e.b.id + "|" + e.a.id;
            if (!seenEdges.containsKey(k)) { seenEdges.put(k, k); newLinks++; }
        }
        return new Row(year, g.nodes.size(), newNodes, newLinks, newNodes + newLinks);
    }

    /** TSV 输出。 */
    public static String toTsv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("year\tnodes\tnew_nodes\tnew_links\tvariation\n");
        for (Row r : rows)
            sb.append(r.year()).append('\t').append(r.nodes()).append('\t')
              .append(r.newNodes()).append('\t').append(r.newLinks()).append('\t')
              .append(r.variation()).append('\n');
        return sb.toString();
    }
}
