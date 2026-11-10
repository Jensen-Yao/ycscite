package ycscite.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A loaded dataset: name, records, year range. */
public final class Project {
    public String name = "untitled";
    public final List<Record> records = new ArrayList<>();
    public int minYear = Integer.MAX_VALUE, maxYear = Integer.MIN_VALUE;

    public void add(Record r) {
        records.add(r);
        if (r.year > 0) {
            if (r.year < minYear) minYear = r.year;
            if (r.year > maxYear) maxYear = r.year;
        }
    }

    public void addAll(java.util.List<Record> rs) {
        for (Record r : rs) add(r);
    }

    /**
     * 按 UT / DOI / 规范化题名 去重（CiteSpace 数据清洗对应能力），保留首条。
     * 返回移除的重复记录数。
     */
    public int dedup() {
        Set<String> seen = new HashSet<>();
        List<Record> kept = new ArrayList<>();
        int removed = 0;
        for (Record r : records) {
            String key = dedupKey(r);
            if (seen.add(key)) kept.add(r);
            else removed++;
        }
        if (removed > 0) {
            records.clear();
            records.addAll(kept);
            minYear=Integer.MAX_VALUE;maxYear=Integer.MIN_VALUE;
            for(var record:records)if(record.year>0){minYear=Math.min(minYear,record.year);maxYear=Math.max(maxYear,record.year);}
        }
        return removed;
    }

    static String dedupKey(Record r) {
        if (r.ut != null && !r.ut.isBlank()) return "UT:" + r.ut.trim().toUpperCase(Locale.ROOT);
        if (r.doi != null && !r.doi.isBlank()) return "DOI:" + r.doi.trim().toLowerCase(Locale.ROOT);
        return "TI:" + (r.title == null ? "" : r.title.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s]+", " ").trim());
    }
}
