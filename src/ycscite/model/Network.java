package ycscite.model;

import java.util.*;

/** The analysis network: undirected weighted graph with node lookup. */
public final class Network {
    public final String kind; // "Co-authorship" | "Keyword co-occurrence" | "Document co-citation" | "Bibliographic coupling" ...
    public final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    public final List<GraphEdge> edges = new ArrayList<>();
    private final Map<GraphNode, List<GraphNode>> adjacency = new HashMap<>();
    public int minYear = Integer.MAX_VALUE, maxYear = Integer.MIN_VALUE;

    public Network(String kind) { this.kind = kind; }

    public GraphNode node(String id, String label, String nodeKind) {
        GraphNode n = nodes.get(id);
        if (n == null) { n = new GraphNode(id, label, nodeKind); nodes.put(id, n); }
        return n;
    }

    public void link(GraphNode a, GraphNode b) { linkWeighted(a, b, 1); }

    /** Link with an explicit weight (used by node merging: edge weights are carried over). */
    public void linkWeighted(GraphNode a, GraphNode b, int weight) {
        linkWeighted(a, b, weight, 0);
    }

    public void linkWeighted(GraphNode a, GraphNode b, int weight, int year) {
        if (a == b || weight == 0) return;
        GraphEdge e = findEdge(a, b);
        if (e == null) {
            e = new GraphEdge(a, b, weight);
            edges.add(e);
            adjacency.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            adjacency.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
        } else e.weight += weight;
        if (year > 0 && (e.firstYear == 0 || year < e.firstYear)) e.firstYear = year;
    }

    /** Remove a node and all its edges (context-menu delete). */
    public void removeNode(GraphNode n) {
        nodes.values().removeIf(nd -> nd == n);
        edges.removeIf(e -> e.a == n || e.b == n);
        rebuildAdjacency();
    }

    /** Merge src into target: weight adds, edges carry over with their weights, src disappears. */
    public void mergeNodes(GraphNode src, GraphNode target) {
        if (src == target || !nodes.containsKey(src.id)) return;
        target.weight += src.weight;
        for (Map.Entry<Integer, Integer> en : src.countsByYear.entrySet())
            for (int i = 0; i < en.getValue(); i++) target.addYear(en.getKey());
        List<GraphEdge> touching = new ArrayList<>();
        for (GraphEdge e : edges) if (e.a == src || e.b == src) touching.add(e);
        for (GraphEdge e : touching)
            linkWeighted(target, e.other(src), e.weight, e.firstYear);
        removeNode(src);
    }

    public void rebuildAdjacency() {
        adjacency.clear();
        edgeIndex.clear();
        for (GraphEdge e : edges) {
            adjacency.computeIfAbsent(e.a, k -> new ArrayList<>()).add(e.b);
            adjacency.computeIfAbsent(e.b, k -> new ArrayList<>()).add(e.a);
        }
    }

    /** 链接强度过滤（原版 link retaining）：移除权重 < minStrength 的边。返回移除数。 */
    public int removeWeakLinks(int minStrength) {
        int before = edges.size();
        if (minStrength <= 1) return 0;
        edges.removeIf(e -> e.weight < minStrength);
        rebuildAdjacency();
        return before - edges.size();
    }

    /** 只保留最大的 K 个连通分量（原版 Show Largest k Connected Components；K=1 即 LCC）。返回保留节点数。 */
    public int keepLargestComponents(int k) {
        if (k <= 0) return nodes.size();
        Map<GraphNode, Boolean> visited = new HashMap<>();
        List<List<GraphNode>> comps = new ArrayList<>();
        for (GraphNode n : nodeCollection()) {
            if (visited.containsKey(n)) continue;
            List<GraphNode> comp = new ArrayList<>();
            java.util.Deque<GraphNode> stack = new java.util.ArrayDeque<>();
            stack.push(n);
            visited.put(n, true);
            while (!stack.isEmpty()) {
                GraphNode cur = stack.pop();
                comp.add(cur);
                for (GraphNode nb : neighbors(cur))
                    if (!visited.containsKey(nb)) { visited.put(nb, true); stack.push(nb); }
            }
            comps.add(comp);
        }
        comps.sort((a, b) -> Integer.compare(b.size(), a.size()));
        java.util.Set<GraphNode> keep = new java.util.HashSet<>();
        for (int i = 0; i < Math.min(k, comps.size()); i++) keep.addAll(comps.get(i));
        nodes.values().removeIf(nd -> !keep.contains(nd));
        edges.removeIf(e -> !keep.contains(e.a) || !keep.contains(e.b));
        rebuildAdjacency();
        return keep.size();
    }

    /** 深拷贝（节点全部字段 + 按原边序重建），用于撤销快照。 */
    public Network deepCopy() {
        Network copy = new Network(kind);
        copy.minYear = minYear;
        copy.maxYear = maxYear;
        for (GraphNode n : nodeCollection()) {
            GraphNode c = new GraphNode(n.id, n.label, n.kind);
            c.weight = n.weight;
            c.halfLife = n.halfLife;
            c.centrality = n.centrality;
            c.pageRank = n.pageRank;
            c.burstness = n.burstness;
            c.burstStrength = n.burstStrength;
            c.sigma = n.sigma;
            c.cluster = n.cluster;
            c.firstYear = n.firstYear;
            c.countsByYear.putAll(n.countsByYear);
            for (int[] b : n.burstIntervals) c.burstIntervals.add(b.clone());
            c.intervalStrengths.addAll(n.intervalStrengths);
            copy.nodes.put(c.id, c);
        }
        for (GraphEdge e : edges)
            copy.linkWeighted(copy.nodes.get(e.a.id), copy.nodes.get(e.b.id), e.weight, e.firstYear);
        return copy;
    }

    private final Map<String, GraphEdge> edgeIndex = new HashMap<>();
    private GraphEdge findEdge(GraphNode a, GraphNode b) {
        String k = a.id.compareTo(b.id) < 0 ? a.id + "\u0001" + b.id : b.id + "\u0001" + a.id;
        GraphEdge e = edgeIndex.get(k);
        if (e == null) {
            for (GraphEdge cand : edges) {
                if ((cand.a == a && cand.b == b) || (cand.a == b && cand.b == a)) { edgeIndex.put(k, cand); return cand; }
            }
        }
        return e;
    }

    public List<GraphNode> neighbors(GraphNode n) {
        List<GraphNode> out = adjacency.get(n);
        return out == null ? Collections.emptyList() : out;
    }

    public Collection<GraphNode> nodeCollection() { return nodes.values(); }

    public void noteYear(int year) {
        if (year <= 0) return;
        if (year < minYear) minYear = year;
        if (year > maxYear) maxYear = year;
    }

    /**
     * Top N 选点 —— 原版 CiteSpace.b(int) 逐式（审计 P0-6/P1-1）：
     * 阈值 = 降序第 n 名（0 基 index n，越界取末名）的权重，f = max(f, floor)（全局下限，默认 1），
     * 权重 ≥ f 的全部保留（平局全收），遇到 < f 提前 break；平局次序按 id 字典序（原版 TreeMap 键序）。
     */
    public void keepTopN(int n) { keepTopN(n, 1); }

    /** 严格硬截 N（稳定排序，平局保持插入序；保留无边节点）——实测联合拟合最优 373/768（距原版 362/721 差 3%/6.5%）。 */
    public void keepTopNStrict(int n) {
        if (n <= 0 || nodes.isEmpty()) return;
        List<GraphNode> byWeight = new ArrayList<>(nodes.values());
        byWeight.sort(java.util.Comparator.comparingInt(x -> -x.weight));
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < Math.min(n, byWeight.size()); i++) keep.add(byWeight.get(i).id);
        nodes.values().removeIf(nd -> !keep.contains(nd.id));
        edges.removeIf(e -> !keep.contains(e.a.id) || !keep.contains(e.b.id));
        rebuildAdjacency();
    }

    public void keepTopN(int n, int floor) {
        if (n <= 0 || nodes.isEmpty()) return;
        List<GraphNode> byWeight = new ArrayList<>(nodes.values());
        byWeight.sort((x, y) -> x.weight != y.weight ? Integer.compare(y.weight, x.weight)
                                                     : x.id.compareTo(y.id));
        int thr = byWeight.get(Math.min(n, byWeight.size() - 1)).weight;
        thr = Math.max(thr, floor);
        Set<String> keep = new HashSet<>();
        for (GraphNode nd : byWeight) {
            if (nd.weight < thr) break;   // 已降序，后面全部不达标
            keep.add(nd.id);
        }
        nodes.values().removeIf(nd -> !keep.contains(nd.id));
        edges.removeIf(e -> !keep.contains(e.a.id) || !keep.contains(e.b.id));
        rebuildAdjacency();
    }

    /**
     * g-index 选点（原版 CiteSpace.b(int) 5129-5150 逐式）：
     * 降序逐词累加 cum += w；(i+1)² ≤ k·cum 则收且 g=i+1；
     * 否则若最大频次 ==1（全 1 平局）延展继续收，其余 break。
     */
    public void keepGIndex(int k) {
        if (k <= 0 || nodes.isEmpty()) return;
        List<GraphNode> byWeight = new ArrayList<>(nodes.values());
        byWeight.sort((x, y) -> x.weight != y.weight ? Integer.compare(y.weight, x.weight)
                                                     : x.label.compareTo(y.label));
        int maxF = byWeight.get(0).weight;
        long cum = 0;
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < byWeight.size(); i++) {
            GraphNode nd = byWeight.get(i);
            cum += nd.weight;
            if ((long) (i + 1) * (i + 1) <= (long) k * cum) { keep.add(nd.id); continue; }
            if (maxF != 1) break;
            keep.add(nd.id);   // 全 1 频平局延展
        }
        // 注：平局排序键=原样 label（原版 mP 稳定排序保持 TreeMap 原文键序）
        nodes.values().removeIf(nd -> !keep.contains(nd.id));
        edges.removeIf(e -> !keep.contains(e.a.id) || !keep.contains(e.b.id));
        rebuildAdjacency();
    }

    /** Top N% 选点（原版 b(int) 5113-5116：n5=⌊n·pct/100⌋，阈值=第 n5−1 名权重），平局全收。 */
    public void keepTopPercent(double pct, int floor) {
        if (pct <= 0 || nodes.isEmpty()) return;
        List<GraphNode> byWeight = new ArrayList<>(nodes.values());
        byWeight.sort((x, y) -> x.weight != y.weight ? Integer.compare(y.weight, x.weight)
                                                     : x.id.compareTo(y.id));
        int n5 = (int) (byWeight.size() * pct / 100.0);
        int idx = Math.max(n5 - 1, 0);
        int thr = byWeight.get(Math.min(idx, byWeight.size() - 1)).weight;
        thr = Math.max(thr, floor);
        Set<String> keep = new HashSet<>();
        for (GraphNode nd : byWeight) {
            if (nd.weight < thr) break;
            keep.add(nd.id);
        }
        nodes.values().removeIf(nd -> !keep.contains(nd.id));
        edges.removeIf(e -> !keep.contains(e.a.id) || !keep.contains(e.b.id));
        rebuildAdjacency();
    }

    /**
     * 链接相似度归一（原版 similarity.conf / "Link Strength: Cosine|Dice|Jaccard"）：
     * w'(i,j) = round(sim × 1000)（权重为 int 存储，×1000 保三位精度）。
     * deg = 加权度（Σ边权）。cos = w/√(dᵢdⱼ)，dice = 2w/(dᵢ+dⱼ)，jac = w/(dᵢ+dⱼ−w)。
     */
    public void transformSimilarity(String mode) {
        if (mode == null || mode.equalsIgnoreCase("raw") || edges.isEmpty()) return;
        Map<GraphNode, Integer> deg = new HashMap<>();
        for (GraphEdge e : edges) {
            deg.merge(e.a, e.weight, Integer::sum);
            deg.merge(e.b, e.weight, Integer::sum);
        }
        for (GraphEdge e : edges) {
            int di = deg.getOrDefault(e.a, 1), dj = deg.getOrDefault(e.b, 1);
            double w = e.weight, sim;
            switch (mode.toLowerCase()) {
                case "cosine" -> sim = w / Math.sqrt((double) di * dj);
                case "equivalence" -> sim = w * w / ((double) di * dj);
                case "dice" -> sim = 2 * w / (di + dj);
                case "jaccard" -> sim = w / (di + dj - w);
                case "inclusion" -> sim = w / Math.min(di, dj);
                default -> { continue; }
            }
            e.weight = Math.max(1, (int) Math.round(sim * 1000));
        }
        edgeIndex.clear();
    }

    /** 删除无边孤立节点（原版节点只能经链接入场，CiteSpace.java 2172/2310-2317）。 */
    public void removeIsolated() {
        Set<String> keep = new HashSet<>();
        for (GraphNode n : nodes.values()) if (!adjacency.getOrDefault(n, List.of()).isEmpty()) keep.add(n.id);
        nodes.values().removeIf(nd -> !keep.contains(nd.id));
        edges.removeIf(e -> !keep.contains(e.a.id) || !keep.contains(e.b.id));
        rebuildAdjacency();
    }
}
