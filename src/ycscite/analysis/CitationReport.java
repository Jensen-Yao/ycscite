package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.ui.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Citation report aligned with CiteSpace's citation-report table: per-node annual counts,
 * totals, half-life, and an ASCII bar per year; exportable as TSV.
 */
public final class CitationReport {

    public static String render(Network g, int topN) {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        nodes.sort((a, b) -> Integer.compare(b.weight, a.weight));
        if (g.minYear == Integer.MAX_VALUE) return Strings.get("report.nodata");
        List<Integer> years = new ArrayList<>();
        for (int y = g.minYear; y <= g.maxYear; y++) years.add(y);
        int limit = Math.min(topN <= 0 ? nodes.size() : topN, nodes.size());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Strings.get("report.citreport") + "%n", limit));
        sb.append("node");
        for (int y : years) sb.append('\t').append(y);
        sb.append("\ttotal\thalf-life%n".replace("%n", "\n"));
        for (int i = 0; i < limit; i++) {
            GraphNode n = nodes.get(i);
            sb.append(n.label);
            int maxC = 1;
            for (int y : years) maxC = Math.max(maxC, n.countsByYear.getOrDefault(y, 0));
            for (int y : years) {
                int c = n.countsByYear.getOrDefault(y, 0);
                String bar = c == 0 ? "" : " " + "#".repeat(Math.max(1, c * 10 / maxC));
                sb.append('\t').append(c).append(bar);
            }
            sb.append('\t').append(n.weight).append('\t')
              .append(n.halfLife > 0 ? n.halfLife + "y" : "-").append('\n');
        }
        return sb.toString();
    }

    /** Annual-count matrix as clean TSV (no bars), aligned with CiteSpace's table export. */
    public static void writeTsv(Network g, int topN, Path out) throws IOException {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        nodes.sort((a, b) -> Integer.compare(b.weight, a.weight));
        StringBuilder sb = new StringBuilder("node\tcluster\tfreq\tcentrality\tsigma\thalf_life");
        if (g.minYear != Integer.MAX_VALUE)
            for (int y = g.minYear; y <= g.maxYear; y++) sb.append('\t').append(y);
        sb.append('\n');
        int limit = Math.min(topN <= 0 ? nodes.size() : topN, nodes.size());
        for (int i = 0; i < limit; i++) {
            GraphNode n = nodes.get(i);
            sb.append(n.label).append('\t').append(n.cluster).append('\t')
              .append(n.weight).append('\t')
              .append(String.format("%.4f", n.centrality)).append('\t')
              .append(String.format("%.2f", n.sigma)).append('\t')
              .append(n.halfLife);
            if (g.minYear != Integer.MAX_VALUE)
                for (int y = g.minYear; y <= g.maxYear; y++)
                    sb.append('\t').append(n.countsByYear.getOrDefault(y, 0));
            sb.append('\n');
        }
        java.nio.file.Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }
}
