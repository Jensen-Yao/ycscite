package ycscite.analysis;

import ycscite.model.Project;
import ycscite.model.Record;
import java.util.*;

/**
 * 引用上下文不确定度（原版 "Computing uncertainties of citation contexts"）：
 * 对每条施引文献，计算其对各被引文献的不确定度 = 1 − (该被引独有词频 / 该施引总被引词频)。
 * 不确定度低的施引对（关系明确）vs 高（关系模糊）。
 */
public final class Uncertainty {

    public record Row(String citingTitle, String citedRef, double uncertainty, int year) { }

    public static List<Row> compute(Project p) {
        List<Row> out = new ArrayList<>();
        for (Record r : p.records) {
            if (r.citedRefs.size() < 2) continue;   // 单一引用无不确定度
            // 每条被引文献的"特征词" = 其首作者+年（简化：原版用引用上下文 NLP）
            Map<String, Integer> refFreq = new HashMap<>();
            for (String raw : r.citedRefs) {
                var cr = ycscite.model.CitedRef.parse(raw);
                if (cr == null) continue;
                String key = cr.key;
                refFreq.merge(key, 1, Integer::sum);
            }
            if (refFreq.size() < 2) continue;
            int total = refFreq.values().stream().mapToInt(Integer::intValue).sum();
            for (var en : refFreq.entrySet()) {
                double certainty = (double) en.getValue() / total;
                double uncertainty = 1.0 - certainty;
                out.add(new Row(r.title, en.getKey(), uncertainty, r.year));
            }
        }
        out.sort(Comparator.comparingDouble(Row::uncertainty).reversed());
        return out;
    }

    public static String toTsv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("citing_title\tcited_ref\tuncertainty\tyear\n");
        for (Row r : rows)
            sb.append(r.citingTitle().replace('\t', ' ')).append('\t')
              .append(r.citedRef()).append('\t')
              .append(String.format("%.4f", r.uncertainty())).append('\t')
              .append(r.year()).append('\n');
        return sb.toString();
    }
}
