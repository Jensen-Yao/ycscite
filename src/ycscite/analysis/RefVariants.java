package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 引文变体合并（原版 "Import/Export: Export: Merge variants of references"）：
 * 同一被引文献的书写变体（作者缩写差异、期刊名缩写差异、页码差异）归并为一个节点。
 * 归一键 = 首作者姓氏 + 年份 + 期刊名前 10 个字母字符（去全部非字母）。
 */
public final class RefVariants {

    /** 变体的规范键（如 "SMITH J, 2010, J ASIST, V57, P359" 与 "SMITH JA., 2010, J AM SOC INF SCI TECHNOL" → 同键）。
     *  期刊归一：取期刊名首词（缩写刊名的第一个缩写单元，如 J/SCI/PROC），大小写不敏感。 */
    public static String variantKey(String rawId) {
        String s = rawId.toUpperCase(Locale.ROOT);
        String[] seg = s.split(",");
        if (seg.length < 3) return s.trim();
        String author = seg[0].trim();
        String surname = author.replaceAll("\\b[A-Z]{1,2}\\b", "").replaceAll("[^A-Z ]", "").trim();
        if (surname.isEmpty()) surname = author;
        String year = seg[1].replaceAll("[^0-9]", "").trim();
        String[] srcWords = seg[2].trim().split("\\s+");
        String src = srcWords.length > 0 ? srcWords[0].replaceAll("[^A-Z]", "") : "";
        return (surname + "|" + year + "|" + src).trim();
    }

    /** 在共被引网络内做变体合并：同 variantKey 的节点并入该键中权重最大者。返回合并掉的节点数。 */
    public static int merge(Network g) {
        if (!g.kind.contains("co-citation") && !g.kind.contains("Co-citation")) return 0;
        Map<String, GraphNode> canonical = new HashMap<>();
        List<GraphNode> toRemove = new java.util.ArrayList<>();
        for (GraphNode n : g.nodeCollection()) {
            String key = variantKey(n.id);
            GraphNode main = canonical.get(key);
            if (main == null) {
                canonical.put(key, n);
            } else {
                // 并入权重更大的表示（保持主变体为权重最高者）
                if (n.weight > main.weight) {
                    swapInto(g, main, n);
                    canonical.put(key, n);
                    toRemove.add(main);
                } else {
                    swapInto(g, n, main);
                    toRemove.add(n);
                }
            }
        }
        for (GraphNode n : toRemove) g.removeNode(n);
        return toRemove.size();
    }

    /** 把 src 的权重与逐年计数并入 target（边随后由 removeNode 断开，计数保留在 target）。 */
    private static void swapInto(Network g, GraphNode src, GraphNode target) {
        target.weight += src.weight;
        for (var e : src.countsByYear.entrySet())
            for (int i = 0; i < e.getValue(); i++) target.addYear(e.getKey());
    }
}
