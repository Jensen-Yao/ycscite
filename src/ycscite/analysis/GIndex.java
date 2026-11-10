package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.ArrayList;
import java.util.List;

/**
 * g-index 节点选择标准（原版 "Node Selection: Selection by g-index, modifiable with a scale factor k"）：
 * g-index = 最大的 g 使按权重降序前 g 个节点的累计权重 ≥ g²；保留前 ceil(g × k) 个节点。
 */
public final class GIndex {

    /** 计算当前网络按节点权重的 g-index。 */
    public static int gIndex(Network g) {
        List<Integer> weights = new ArrayList<>();
        for (GraphNode n : g.nodeCollection()) weights.add(n.weight);
        weights.sort((a, b) -> Integer.compare(b, a));
        long cum = 0;
        int gg = 0;
        for (int i = 0; i < weights.size(); i++) {
            cum += weights.get(i);
            if (cum >= (long) (i + 1) * (i + 1)) gg = i + 1;
            else break;
        }
        return gg;
    }

    /**
     * g-index 选点 —— 原版 CiteSpace.b(int) 的 g-index 分支逐式（行 5144-5152）：
     * 降序（平局按 id 字典序）累计权重，(i+1)² ≤ k·cum 时纳入并更新 g。
     */
    public static int select(Network net, double k) {
        double kk = Math.max(0.1, k);
        List<GraphNode> by = new ArrayList<>(net.nodeCollection());
        by.sort((x, y) -> x.weight != y.weight ? Integer.compare(y.weight, x.weight)
                                               : x.id.compareTo(y.id));
        long cum = 0;
        int g = 0;
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (int i = 0; i < by.size(); i++) {
            cum += by.get(i).weight;
            if ((long) (i + 1) * (i + 1) <= (long) (kk * cum)) {
                g = i + 1;
                keep.add(by.get(i).id);
            } else break;
        }
        if (g == 0 && !by.isEmpty()) { g = 1; keep.add(by.get(0).id); }
        net.nodes.values().removeIf(nd -> !keep.contains(nd.id));
        net.edges.removeIf(e -> !keep.contains(e.a.id) || !keep.contains(e.b.id));
        net.rebuildAdjacency();
        return g;
    }
}
