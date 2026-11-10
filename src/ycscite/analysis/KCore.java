package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/** k-core 分解（原版 "k-Cores Highlight L1-L5" / "Labeling with k-Core Citers"）。 */
public final class KCore {

    /** 剥洋葱算法：反复删除度 < k 的节点，coreness(v) = 最后所在 shell 层号。 */
    public static Map<GraphNode, Integer> compute(Network g) {
        Map<GraphNode, Integer> core = new HashMap<>();
        Map<GraphNode, Integer> deg = new HashMap<>();
        Set<GraphNode> alive = new HashSet<>();
        for (GraphNode n : g.nodeCollection()) {
            deg.put(n, g.neighbors(n).size());
            alive.add(n);
        }
        for (int k = 1; k <= deg.size(); k++) {
            Deque<GraphNode> q = new ArrayDeque<>();
            for (GraphNode n : alive) if (deg.get(n) < k) q.add(n);
            while (!q.isEmpty()) {
                GraphNode u = q.poll();
                if (!alive.contains(u)) continue;
                alive.remove(u);
                core.put(u, k - 1);
                for (GraphNode v : g.neighbors(u)) {
                    if (!alive.contains(v)) continue;
                    deg.merge(v, -1, Integer::sum);
                    if (deg.get(v) < k) q.add(v);
                }
            }
            if (alive.isEmpty()) break;
        }
        for (GraphNode n : alive) core.put(n, deg.size());
        return core;
    }
}
