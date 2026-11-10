package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * 模块度聚类 —— 逐式移植自 CiteSpace 7.0.1 反编译源码 kX.java（Adaptor）+ la.java
 * （CommunityStructure）+ kY/kZ（边收集/元组）。
 *
 * 算法（源码证实，非谱聚类）：加权模块度多级优化（Louvain 风格）——
 *   oneLevel：随机顺序逐节点移动到增益最大的邻社区（增益 = w_nc − d_node·W_c/|E|，
 *             平局取更小社区号；增益全 0 → 进入空社区），整轮无移动则停；
 *   multilevel：oneLevel → 若社区数<节点数则按社区爆裂子图、子图内重置+oneLevel、
 *             凝聚为商图后递归 multilevel，标签逐层映射回；
 *   Q = (社区内边权 + 自环 − Σ_c (Σ度_c)²·d) / total（d = 1/total）；
 *   重编号：按社区规模降序（#1 最大）；
 *   驱动（kX.a）：单一 new Random(0)（固定种子，结果确定），10 次重启，
 *             每次重置后跑 10 遍 multilevel，取 Q 最高者。
 * 边以双向列表进入 CSR（kY），节点权重 = 加权度。
 */
public final class Clusterer {

    public static double compute(Network g) {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        if (nodes.isEmpty()) return 0;
        // 原版 kX 只以边为入口：尾部孤立点不入结构（中部孤立点保留、质量 0）
        List<GraphNode> linked = new ArrayList<>();
        for (GraphNode n : nodes) if (!g.neighbors(n).isEmpty()) linked.add(n);
        for (GraphNode n : nodes) if (g.neighbors(n).isEmpty()) n.cluster = 0;
        if (linked.isEmpty()) { for (GraphNode n : nodes) n.cluster = 1; return 0; }
        Map<GraphNode, Integer> idx = new HashMap<>();
        for (int i = 0; i < linked.size(); i++) idx.put(linked.get(i), i);

        // kY/kX：双向边，权重强制 1（kY.a 字节码写死 1，原版聚类跑无权图）
        List<int[]> dir = new ArrayList<>();
        for (GraphEdge e : g.edges) {
            if (e.a == e.b || !idx.containsKey(e.a) || !idx.containsKey(e.b)) continue;
            int a = idx.get(e.a), b = idx.get(e.b);
            dir.add(new int[]{a, b, 1});
            dir.add(new int[]{b, a, 1});
        }
        dir.sort((x, y) -> x[0] != y[0] ? Integer.compare(x[0], y[0]) : Integer.compare(x[1], y[1]));
        Comm comm = Comm.build(linked.size(), dir);
        double total = comm.total();
        if (!(total > 0)) { for (GraphNode n : nodes) n.cluster = 1; return 0; }

        // kX.a()：固定种子驱动，10 次重启取最优 Q；快照必须在最优重启的结构状态下做
        Random rnd = new Random(0L);
        double invTotal = 1.0 / total;
        int[] bestLabels = null;
        int bestNComm = 0;
        double bestQ = Double.NEGATIVE_INFINITY;
        for (int restart = 0; restart < 10; restart++) {
            comm.reset();
            for (int round = 0; round < 10; round++) comm.multilevel(invTotal, rnd);
            double q = comm.modularity(invTotal);
            if (q > bestQ) {
                bestQ = q;
                // 照抄 kX：在最优状态内先重编号再快照，避免换入旧标签后缓存过期
                comm.renumberBySize();
                bestLabels = comm.labels.clone();
                bestNComm = comm.nComm;
            }
        }
        comm.labels = bestLabels;
        comm.nComm = bestNComm;
        comm.cached = false;

        for (GraphNode node : linked)
            node.cluster = bestLabels[idx.get(node)] + 1;   // 重编号后 0..k-1 → 1..k，0 保留给未归类（孤立点）
        return bestQ;
    }

    /** la（CommunityStructure）逐式移植：CSR + oneLevel + multilevel + Q + 规模重编号。 */
    private static final class Comm {
        final int n;                 // 节点数（当前层）
        final int[] off;             // CSR 偏移（n+1）
        final int[] nbr;             // 邻居
        final double[] w;            // 边权
        final double[] nodeW;        // 节点权重（原度）
        double selfLoop;
        int[] labels;                // 社区标签
        int nComm;                   // 社区数
        private double[] commDegree; // 每社区度和（缓存，ensureCache）
        private int[] commSize;
        private int[][] members;
        private boolean cached;

        private Comm(int n, int[] off, int[] nbr, double[] w, double[] nodeW, double selfLoop) {
            this.n = n; this.off = off; this.nbr = nbr; this.w = w;
            this.nodeW = nodeW; this.selfLoop = selfLoop;
            labels = new int[n];
            reset();
        }

        /** 主构造：从排序双向边表建 CSR。 */
        static Comm build(int n, List<int[]> dir) {
            int[] off = new int[n + 1];
            int[] nbr = new int[dir.size()];
            double[] w = new double[dir.size()];
            double[] nodeW = new double[n];
            int fill = 0, cur = 1;
            for (int[] e : dir) {
                if (e[0] >= cur) while (cur <= e[0]) { off[cur] = fill; cur++; }
                nbr[fill] = e[1];
                w[fill] = e[2];
                nodeW[e[0]] += e[2];
                fill++;
            }
            while (cur <= n) { off[cur] = fill; cur++; }
            return new Comm(n, off, nbr, w, nodeW, 0);
        }

        double total() { double t = selfLoop; for (double v : w) t += v; return t; }

        void reset() {                              // la.a()：全部单点社区
            for (int i = 0; i < n; i++) labels[i] = i;
            nComm = n;
            cached = false;
        }

        /** Q（la.a(double d)）：(同社区边权 + 自环 − Σ 度_c²·d) / total。 */
        double modularity(double d) {
            ensureCache();
            double in = selfLoop;
            for (int i = 0; i < n; i++) {
                int ci = labels[i];
                for (int j = off[i]; j < off[i + 1]; j++)
                    if (labels[nbr[j]] == ci) in += w[j];
            }
            for (int c = 0; c < nComm; c++) in -= commDegree[c] * commDegree[c] * d;
            return in / total();
        }

        /** la.b(double, Random)：oneLevel 局部移动。 */
        boolean oneLevel(double d, Random rnd) {
            if (n <= 1) return false;
            ensureCache();
            boolean movedAny = false;
            double[] commW = new double[n];
            int[] commCnt = new int[n];
            for (int i = 0; i < n; i++) { commW[labels[i]] += nodeW[i]; commCnt[labels[i]]++; }
            int nEmpty = 0;
            int[] empty = new int[n];
            for (int c = 0; c < n; c++) if (commCnt[c] == 0) empty[nEmpty++] = c;
            int[] order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
            for (int i = 0; i < n; i++) {
                int j = rnd.nextInt(n);
                int t = order[i]; order[i] = order[j]; order[j] = t;
            }
            double[] acc = new double[n];
            int[] touched = new int[n];
            int streak = 0, ptr = 0;
            do {
                int i = order[ptr];
                ptr = ptr < n - 1 ? ptr + 1 : 0;
                int nT = 0;
                for (int j = off[i]; j < off[i + 1]; j++) {
                    int cj = labels[nbr[j]];
                    if (acc[cj] == 0) touched[nT++] = cj;
                    acc[cj] += w[j];
                }
                int ci = labels[i];
                commW[ci] -= nodeW[i];
                commCnt[ci]--;
                if (commCnt[ci] == 0) empty[nEmpty++] = ci;
                int best = -1;
                double bestGain = 0;
                for (int t = 0; t < nT; t++) {
                    int cj = touched[t];
                    double gain = acc[cj] - nodeW[i] * commW[cj] * d;
                    if (gain > bestGain || (gain == bestGain && cj < best)) { best = cj; bestGain = gain; }
                    acc[cj] = 0;
                }
                if (bestGain == 0) best = empty[--nEmpty];
                commW[best] += nodeW[i];
                commCnt[best]++;
                if (best == labels[i]) streak++;
                else { labels[i] = best; streak = 1; movedAny = true; }
            } while (streak < n);
            int[] map = new int[n];
            nComm = 0;
            for (int c = 0; c < n; c++) if (commCnt[c] > 0) map[c] = nComm++;
            for (int i = 0; i < n; i++) labels[i] = map[labels[i]];
            cached = false;
            return movedAny;
        }

        /** la.a(double, Random)：multilevel —— oneLevel + 爆裂子图 + 商图递归。 */
        boolean multilevel(double d, Random rnd) {
            if (n <= 1) return false;
            boolean changed = oneLevel(d, rnd);
            if (nComm < n) {
                ensureCache();
                Comm[] subs = explode();
                nComm = 0;
                for (int c = 0; c < subs.length; c++) {
                    subs[c].reset();
                    subs[c].oneLevel(d, rnd);
                    int[] sl = subs[c].labels;
                    for (int k = 0; k < sl.length; k++)
                        labels[members[c][k]] = nComm + sl[k];
                    nComm += subs[c].nComm;
                }
                cached = false;
                Comm quotient = condense();
                int[] origin = new int[quotient.n];
                int q = 0;
                for (int c = 0; c < subs.length; c++)
                    for (int k = 0; k < subs[c].nComm; k++) origin[q++] = c;
                quotient.labels = origin;
                quotient.nComm = subs.length;
                changed |= quotient.multilevel(d, rnd);
                relabel(quotient.labels);
            }
            cached = false;
            return changed;
        }

        private Comm[] explode() {                  // la.a()：按社区爆裂子图
            int[] local = new int[n];
            int[] subNbr = new int[nbr.length];
            double[] subW = new double[w.length];
            Comm[] out = new Comm[nComm];
            for (int c = 0; c < nComm; c++) {
                int m = members[c].length;
                if (m == 1) {
                    out[c] = new Comm(1, new int[]{0, 0}, new int[0], new double[0],
                            new double[]{nodeW[members[c][0]]}, 0);
                    continue;
                }
                for (int k = 0; k < m; k++) local[members[c][k]] = k;
                int[] soff = new int[m + 1];
                double[] snw = new double[m];
                int fill = 0;
                for (int k = 0; k < m; k++) {
                    int v = members[c][k];
                    for (int j = off[v]; j < off[v + 1]; j++)
                        if (labels[nbr[j]] == c) { subNbr[fill] = local[nbr[j]]; subW[fill] = w[j]; fill++; }
                    soff[k + 1] = fill;
                    snw[k] = nodeW[v];
                }
                out[c] = new Comm(m, soff,
                        Arrays.copyOf(subNbr, fill), Arrays.copyOf(subW, fill), snw, 0);
            }
            return out;
        }

        private Comm condense() {                   // la.a()：凝聚为商图（源码先 c() 重建缓存）
            ensureCache();                          // 合并标签后 members 需按新标签重建
            int k = nComm;
            double[] acc = new double[k];
            int[] seen = new int[Math.max(k - 1, 1)];
            List<Integer> qN = new ArrayList<>();
            List<Double> qW = new ArrayList<>();
            int[] qoff = new int[k + 1];
            double[] qNodeW = new double[k];
            double qSelf = 0;
            for (int c = 0; c < k; c++) {
                int nT = 0;
                for (int v : members[c])
                    for (int j = off[v]; j < off[v + 1]; j++) {
                        int c2 = labels[nbr[j]];
                        if (c2 != c) {
                            if (acc[c2] == 0) seen[nT++] = c2;
                            acc[c2] += w[j];
                        } else qSelf += w[j];
                    }
                for (int v : members[c]) qNodeW[c] += nodeW[v];
                for (int t = 0; t < nT; t++) { qN.add(seen[t]); qW.add(acc[seen[t]]); acc[seen[t]] = 0; }
                qoff[c + 1] = qN.size();
            }
            int[] qn = new int[qN.size()];
            double[] qw = new double[qW.size()];
            for (int i = 0; i < qn.length; i++) { qn[i] = qN.get(i); qw[i] = qW.get(i); }
            return new Comm(k, qoff, qn, qw, qNodeW, qSelf);
        }

        private void relabel(int[] map) {           // la.b(int[])
            int max = 0;
            for (int i = 0; i < n; i++) if (map[labels[i]] > max) max = map[labels[i]];
            for (int i = 0; i < n; i++) labels[i] = map[labels[i]];
            nComm = max + 1;
            cached = false;
        }

        /** la.b()：按社区规模降序重编号（截断空社区）。 */
        void renumberBySize() {
            ensureCache();
            Integer[] ids = new Integer[nComm];
            for (int c = 0; c < nComm; c++) ids[c] = c;
            Arrays.sort(ids, (x, y) -> Integer.compare(commSize[y], commSize[x]));
            int[] map = new int[nComm];
            int k = 0;
            do { map[ids[k]] = k; }
            while (++k < nComm && commSize[ids[k]] > 0);
            nComm = k;
            for (int i = 0; i < n; i++) labels[i] = map[labels[i]];
            cached = false;
        }

        private void ensureCache() {                // la.c()
            if (cached) return;
            int cap = Math.max(nComm, 1);
            commDegree = new double[cap];
            commSize = new int[cap];
            members = new int[cap][];
            for (int i = 0; i < n; i++) { commDegree[labels[i]] += nodeW[i]; commSize[labels[i]]++; }
            for (int c = 0; c < cap; c++) members[c] = new int[commSize[c]];
            int[] fill = new int[cap];
            for (int i = 0; i < n; i++) { int c = labels[i]; members[c][fill[c]++] = i; }
            cached = true;
        }
    }
}
