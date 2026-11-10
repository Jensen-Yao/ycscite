package ycscite.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Graph node carrying CiteSpace-style metrics. */
public final class GraphNode {
    public final String id;
    public String label;
    // 原版 TreeMap 键序辅助（小写化词条的字典序，选点平局次序用）
    public String getLexKey() { return label == null ? "" : label.toLowerCase(); }
    public String kind;                       // "keyword" | "author" | "cited-ref" | "institution" | "term"
    public int weight;                        // frequency / citation count
    public int halfLife = 0;                  // citation half-life (cited refs)
    public double centrality = 0;             // normalized betweenness centrality
    public double pageRank = 0;               // PageRank (damping 0.85, sum = 1)
    public double degreeCentrality = 0;       // 强度加权度中心性 / (n-1)
    public double eigenCentrality = 0;        // 特征向量中心性（主特征向量，最大=1）
    public double burstness = 0;
    public boolean bookmarked = false;     // 节点书签（原版 "Bookmark the Node"，星标）
    public String annotation = "";        // 节点批注（原版 "Annotate the Node"）
    public String userLabel = null;       // 用户重命名标签（原版 "Label the Node"）              // max burst strength (levels)
    public double burstStrength = 0;         // 累计超出强度（对齐原版 Strength 口径）
    public double sigma = 1;                  // sigma = (centrality+1)^burstness
    public int cluster = -1;
    public int firstYear = Integer.MAX_VALUE;
    public final Map<Integer, Integer> countsByYear = new TreeMap<>();

    public GraphNode(String id, String label, String kind) {
        this.id = id;
        this.label = label;
        this.kind = kind;
    }

    public void addYear(int year) {
        if (year <= 0) return; // 无年份记录不参与逐年统计（防 year=0 泄漏）
        countsByYear.merge(year, 1, Integer::sum);
        if (year < firstYear) firstYear = year;
    }

    public int yearOfMax() {
        int best = 0, bestN = -1;
        for (Map.Entry<Integer, Integer> e : countsByYear.entrySet())
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        return best;
    }

    public List<int[]> burstIntervals = new ArrayList<>(); // [startYear, endYear, level]
    /// 每个突现区间的强度（与 burstIntervals 顺序对应）
    public final List<Double> intervalStrengths = new ArrayList<>();
}
