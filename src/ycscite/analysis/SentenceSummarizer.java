package ycscite.analysis;

import ycscite.model.Project;
import ycscite.model.Record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 簇级句子摘要（原版 "Select Cluster-Summarizing Sentences"，ph.java 2178-2193 的零依赖实现）：
 * 簇的施引记录（含该簇任一术语）中，句子与簇 LLR 标签词集的 Jaccard 重叠打分，每簇取 Top-3。
 */
public final class SentenceSummarizer {

    public static String summarize(Pipeline.Result r, Project p, int perCluster) throws IOException {
        StringBuilder sb = new StringBuilder("cluster\tlabel_terms\tsentence\tscore\n");
        Map<String, Integer> termCluster = new HashMap<>();
        for (var n : r.network.nodeCollection())
            if (n.cluster > 0) termCluster.put(n.label.toUpperCase(), n.cluster);
        Map<Integer, Set<String>> clusterTerms = new TreeMap<>();
        Map<Integer, List<String>> clusterDocs = new TreeMap<>();
        for (Record rec : p.records) {
            Integer cl = null;
            for (String k : rec.allKeywords()) {
                Integer c = termCluster.get(k.trim().toUpperCase());
                if (c != null) { cl = c; break; }
            }
            if (cl == null) continue;
            Set<String> terms = clusterTerms.computeIfAbsent(cl, x -> new HashSet<>());
            var labels = r.clusterLabels == null ? null : r.clusterLabels.get(cl);
            if (labels != null) for (var s : labels) terms.addAll(Labelers.tokenize(s.term));
            clusterDocs.computeIfAbsent(cl, x -> new ArrayList<>())
                       .add((rec.title == null ? "" : rec.title) + "。" + (rec.abstractText == null ? "" : rec.abstractText));
        }
        for (var en : clusterDocs.entrySet()) {
            Set<String> terms = clusterTerms.getOrDefault(en.getKey(), Set.of());
            List<Object[]> scored = new ArrayList<>();
            for (String doc : en.getValue())
                for (String sent : doc.split("[。？！.?!;；]+")) {
                    if (sent == null || sent.trim().length() < 8) continue;
                    Set<String> st = new HashSet<>(Labelers.tokenize(sent));
                    if (st.isEmpty()) continue;
                    int hit = 0;
                    for (String t : st) if (terms.contains(t)) hit++;
                    double jac = hit / (double) (st.size() + terms.size() - hit);
                    scored.add(new Object[]{sent.trim(), jac});
                }
            scored.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            var labels = r.clusterLabels == null ? null : r.clusterLabels.get(en.getKey());
            String labelStr = labels == null ? "" : labels.stream().map(x -> x.term).reduce((a, b) -> a + ";" + b).orElse("");
            for (int i = 0; i < Math.min(perCluster, scored.size()); i++)
                sb.append('#').append(en.getKey()).append('\t').append(labelStr).append('\t')
                  .append(((String) scored.get(i)[0]).replace('\t', ' ')).append('\t')
                  .append(String.format("%.4f", (Double) scored.get(i)[1])).append('\n');
        }
        return sb.toString();
    }
}
