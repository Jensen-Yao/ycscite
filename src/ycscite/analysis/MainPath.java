package ycscite.analysis;

import ycscite.model.CitedRef;
import ycscite.model.Project;
import ycscite.model.Record;

import java.util.*;

/**
 * 主路径分析（Main Path Analysis，Hummon & Doreian 1989，原版 "Add Main Path Layers"）：
 *   1. 引文 DAG：数据集内记录 A 被记录 B 引用（CR 首作者+年+期刊匹配）→ 有向边 A→B；
 *   2. SPC（Search Path Count）：s(v)=源到 v 的路径数（前向拓扑序），t(v)=v 到汇的路径数（后向），
 *      边 SPC = s(u)·t(v)；
 *   3. 主路径：从最大 s 的源出发，贪心走 SPC 最大的出边直至汇。
 * CNKI/CSSCI 无 CR 字段时返回空（与原版限制一致）。
 */
public final class MainPath {

    public static final class Edge {
        public final String from, to;   // 记录标识（UT/DOI/序号）
        public final int spc;
        Edge(String f, String t, int s) { from = f; to = t; spc = s; }
    }

    public static final class Result {
        public final List<Edge> allEdges = new ArrayList<>();       // DAG 全部引用边（含 SPC）
        public final List<Edge> mainPath = new ArrayList<>();       // 主路径边
        public final boolean hasData;
        Result(boolean has) { hasData = has; }
    }

    public static Result compute(Project p) {
        Map<String, Integer> idx = new HashMap<>();
        List<Record> recs = new ArrayList<>();
        for (Record r : p.records) {
            String id = recordId(r, recs.size());
            idx.put(id, recs.size());
            recs.add(r);
        }
        // 引用签名（首作者, 年, 期刊）→ 记录索引
        Map<String, Integer> sig = new HashMap<>();
        for (int i = 0; i < recs.size(); i++) {
            Record r = recs.get(i);
            if (r.allAuthors().isEmpty() || r.year <= 0) continue;
            String s = (r.allAuthors().get(0) + ", " + r.year + ", " + r.source).toUpperCase();
            sig.putIfAbsent(s, i);
        }
        // DAG 边（去重）
        Map<String, Integer> edgeW = new LinkedHashMap<>();
        for (int b = 0; b < recs.size(); b++)
            for (String raw : recs.get(b).citedRefs) {
                CitedRef cr = CitedRef.parse(raw);
                if (cr == null) continue;
                Integer a = sig.get((cr.author + ", " + cr.year + ", " + cr.source).toUpperCase());
                if (a == null || a == b) continue;
                edgeW.merge(a + "\u0001" + b, 1, Integer::sum);
            }
        Result out = new Result(!edgeW.isEmpty());
        if (edgeW.isEmpty()) return out;

        int n = recs.size();
        Map<Integer, List<Integer>> outAdj = new HashMap<>(), inAdj = new HashMap<>();
        Map<Long, Integer> spcMap = new HashMap<>();
        for (var en : edgeW.entrySet()) {
            int a = Integer.parseInt(en.getKey().substring(0, en.getKey().indexOf('\u0001')));
            int b = Integer.parseInt(en.getKey().substring(en.getKey().indexOf('\u0001') + 1));
            outAdj.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
            inAdj.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            spcMap.put(key(a, b), en.getValue());
        }
        // 拓扑序（Kahn）
        int[] indeg = new int[n];
        for (int b = 0; b < n; b++) indeg[b] = inAdj.getOrDefault(b, List.of()).size();
        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (indeg[i] == 0) q.add(i);
        List<Integer> topo = new ArrayList<>();
        while (!q.isEmpty()) {
            int u = q.poll();
            topo.add(u);
            for (int v : outAdj.getOrDefault(u, List.of())) if (--indeg[v] == 0) q.add(v);
        }
        // s(v)：源到 v 路径数；t(v)：v 到汇路径数
        long[] s = new long[n], t = new long[n];
        for (int u : topo) {
            List<Integer> in = inAdj.getOrDefault(u, List.of());
            s[u] = in.isEmpty() ? 1 : 0;
            for (int pi : in) s[u] += s[pi];
        }
        for (int i = topo.size() - 1; i >= 0; i--) {
            int u = topo.get(i);
            List<Integer> outL = outAdj.getOrDefault(u, List.of());
            t[u] = outL.isEmpty() ? 1 : 0;
            for (int v : outL) t[u] += t[v];
        }
        for (var en : edgeW.entrySet()) {
            int a = Integer.parseInt(en.getKey().substring(0, en.getKey().indexOf('\u0001')));
            int b = Integer.parseInt(en.getKey().substring(en.getKey().indexOf('\u0001') + 1));
            out.allEdges.add(new Edge(recordId(recs.get(a), a), recordId(recs.get(b), b),
                    (int) Math.min(s[a] * t[b], Integer.MAX_VALUE)));
        }
        // 主路径：从 s 最大源贪心 max-SPC 出边
        int cur = -1;
        long bestS = -1;
        for (int i = 0; i < n; i++)
            if (s[i] > bestS && !outAdj.getOrDefault(i, List.of()).isEmpty()) { bestS = s[i]; cur = i; }
        int guard = 0;
        while (cur >= 0 && guard++ < n) {
            List<Integer> outL = outAdj.getOrDefault(cur, List.of());
            if (outL.isEmpty()) break;
            int next = outL.get(0);
            long best = -1;
            for (int v : outL) {
                long spc = s[cur] * t[v];
                if (spc > best) { best = spc; next = v; }
            }
            out.mainPath.add(new Edge(recordId(recs.get(cur), cur), recordId(recs.get(next), next), (int) Math.min(best, Integer.MAX_VALUE)));
            cur = next;
        }
        return out;
    }

    private static long key(int a, int b) { return ((long) a << 32) | b; }

    private static String recordId(Record r, int i) {
        if (r.ut != null && !r.ut.isBlank()) return r.ut;
        if (r.doi != null && !r.doi.isBlank()) return r.doi;
        return "DOC" + i;
    }

    public static String toTsv(Result r) {
        StringBuilder sb = new StringBuilder("type\tfrom\tto\tspc\n");
        Set<String> mp = new HashSet<>();
        for (Edge e : r.mainPath) mp.add(e.from + "\u0001" + e.to);
        for (Edge e : r.allEdges)
            sb.append(mp.contains(e.from + "\u0001" + e.to) ? "MAIN" : "edge").append('\t')
              .append(e.from).append('\t').append(e.to).append('\t').append(e.spc).append('\n');
        return sb.toString();
    }
}
