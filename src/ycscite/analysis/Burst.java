package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * Kleinberg 突现检测 —— CiteSpace 7.0.1 原版 mi/mp 引擎的逐式移植，
 * 并经原版 JAR 差分测试（直接调用原版 mp/mi 跑同一 burst.in）数值验证：
 *
 * 参数链（mp.a 默认）："-in … -bin -eps -rel 2.0 2.0 1 -trans 3 -slength 3"。
 * 运行时真值（反射读原版实例字段 + 阈值实验，2026-10 差分验证）：
 *   f = ΣN/ΣD（记录总量/词计数总量，即词占比的倒数）；
 *   状态 S=2：state 1=基线 rate=f（p1=1/f），state 0=突现 rate=f/2（p0=2/f）；
 *   发射 = 二项 NLL：−[lnC(n,k) + k·ln p + (n−k)·ln(1−p)]，p≥1 → 1e6；
 *   转移罚 γ = ln(T+1)（T=年份跨度；原版 -trans 3 实际未生效，k 运行时=1.0，
 *     实测 m 字段 = ln(27)=3.2958 @T=26、ln(102)=4.6250 @T=101）；
 *   仅进入突现态计 (i−s)·γ，回落免费；初始 cost[0][s] = emit + (S−1−s)·γ；
 *   Strength = 区间内 NLL 增益 Σ[k·ln(p0/p1) + (n−k)·ln((1−p0)/(1−p1))]
 *     （差分验证：非遗 2017-2018 → 4.8651 vs 原版 4.8646；合成 4.8891/5.9787 精确一致）。
 * 数据基础（GUI 侧 kh.java 7040-7090 GraphPanel 突现视图，源码验证）：
 *   实体 = 当前显示的网络节点；N[t] = 这些节点年度计数之和，空年置 1（if 0 → 1）；
 *   T = 时间切片完整跨度（项目起始年..结束年，稠密）。
 */
public final class Burst {

    public static class Params {
        public double relI = 2.0;   // -rel i：突变态率 = f/i
        public double relJ = 2.0;   // -rel j：后续状态率链除数
        public int relC = 1;        // -rel V：状态数-1（mp.a-1）
        public double transK = 1.0; // γ = transK·ln(T+1)；原版运行时实测 k=1.0
        public int spanFrom = 0, spanTo = 0; // 时间跨度（0=按网络年份）
    }

    public static void compute(Network g, Params p) {
        TreeSet<Integer> allYears = new TreeSet<>();
        for (GraphNode n : g.nodeCollection()) allYears.addAll(n.countsByYear.keySet());
        if (allYears.isEmpty()) return;
        // T = 完整稠密跨度（GUI 用切片年份区间；优先 Params，回退网络年份）
        int lo = allYears.first(), hi = allYears.last();
        if (p.spanFrom > 0) lo = Math.min(lo, p.spanFrom);
        if (p.spanTo > 0) hi = Math.max(hi, p.spanTo);
        if (g.minYear != Integer.MAX_VALUE && g.minYear > 0) lo = Math.min(lo, g.minYear);
        if (g.maxYear != Integer.MIN_VALUE) hi = Math.max(hi, g.maxYear);
        List<Integer> years = new ArrayList<>(hi - lo + 1);
        for (int y = lo; y <= hi; y++) years.add(y);
        int T = years.size();
        Map<Integer, Integer> yearIdx = new HashMap<>();
        for (int t = 0; t < T; t++) yearIdx.put(years.get(t), t);

        // 背景暴露量：网络节点年度计数和；空年置 1（GUI 源码 kh.java 7070-7072）
        int[] N = new int[T];
        for (GraphNode n : g.nodeCollection())
            for (Map.Entry<Integer, Integer> e : n.countsByYear.entrySet()) {
                Integer idx = yearIdx.get(e.getKey());
                if (idx != null) N[idx] += e.getValue();
            }
        for (int t = 0; t < T; t++) if (N[t] == 0) N[t] = 1;
        long totalN = 0;
        for (int v : N) totalN += v;

        int S = Math.max(2, p.relC + 1);
        for (GraphNode n : g.nodeCollection()) {
            n.burstIntervals.clear();
            n.intervalStrengths.clear();
            n.burstness = 0;
            n.burstStrength = 0;
            if (T < 2 || totalN <= 0) continue;
            long totalD = 0;
            for (int v : n.countsByYear.values()) totalD += v;
            if (totalD <= 0) continue;

            int[] d = new int[T];
            for (int t = 0; t < T; t++) d[t] = n.countsByYear.getOrDefault(years.get(t), 0);

            // 状态率链（源码 -rel 分支）：a[S-1]=f, a[S-2]=f/i, a[s]=a[s+1]/j
            double f = (double) totalN / (double) totalD;
            double[] rate = new double[S];
            rate[S - 1] = f;
            if (S >= 2) rate[S - 2] = f / p.relI;
            for (int s = S - 3; s >= 0; s--) rate[s] = rate[s + 1] / p.relJ;
            double[] prob = new double[S];
            for (int s = 0; s < S; s++) prob[s] = 1.0 / rate[s];

            // 转移罚（运行时实测）：γ = ln(T+1)
            double m = Math.max(0.0, p.transK * Math.log(T + 1));

            // DP（源码 619-637）：仅进入低状态（突现方向）计 (i-s)·m
            double[][] cost = new double[T][S];
            int[][] back = new int[T][S];
            for (int s = 0; s < S; s++)
                cost[0][s] = binomNll(d[0], N[0], prob[s]) + (S - 1 - s) * m;
            for (int t = 1; t < T; t++) {
                for (int s = 0; s < S; s++) {
                    double best = Double.POSITIVE_INFINITY; int bestI = 0;
                    for (int i = 0; i < S; i++) {
                        double c = cost[t - 1][i] + (i > s ? (i - s) * m : 0);
                        if (c < best) { best = c; bestI = i; }
                    }
                    cost[t][s] = best + binomNll(d[t], N[t], prob[s]);
                    back[t][s] = bestI;
                }
            }
            int[] state = new int[T];
            int cur = 0;
            double bestC = Double.POSITIVE_INFINITY;
            for (int s = 0; s < S; s++) if (cost[T - 1][s] < bestC) { bestC = cost[T - 1][s]; cur = s; }
            state[T - 1] = cur;
            for (int t = T - 1; t > 0; t--) { cur = back[t][cur]; state[t - 1] = cur; }

            // 状态 → 突现级别：state S-1=0（基线），state 0=S-1（最高）
            int[] level = new int[T];
            int maxLevel = 0;
            for (int t = 0; t < T; t++) {
                level[t] = S - 1 - state[t];
                if (level[t] > maxLevel) maxLevel = level[t];
            }

            // 区间提取 + Strength = 区间内 NLL 增益（原版权重口径，差分验证一致）
            double strength = 0;
            for (int lvl = 1; lvl < S; lvl++) {
                int start = -1;
                for (int t = 0; t <= T; t++) {
                    boolean in = t < T && level[t] >= lvl;
                    if (in && start < 0) start = t;
                    else if (!in && start >= 0) {
                        double w = nllGain(d, N, prob, lvl, start, t - 1);
                        n.burstIntervals.add(new int[]{years.get(start), years.get(t - 1), lvl});
                        n.intervalStrengths.add(w);
                        strength += w;
                        start = -1;
                    }
                }
            }
            n.burstness = maxLevel;
            // 显示口径：取最强单区间强度（原版 GUI 显示单区间 weight，非区间之和）
            double maxInterval = 0;
            for (double w : n.intervalStrengths) maxInterval = Math.max(maxInterval, w);
            n.burstStrength = maxInterval > 0 ? maxInterval : strength;
        }
    }

    /**
     * 区间 NLL 增益（原版 Strength）：从基线概率提升到该级概率的似然增益
     * Σ[k·ln(p_lvl/p_base) + (n−k)·ln((1−p_lvl)/(1−p_base))]。
     */
    private static double nllGain(int[] d, int[] N, double[] prob, int lvl, int from, int to) {
        double pBase = prob[prob.length - 1];
        double pBurst = prob[Math.max(0, prob.length - 1 - lvl)];
        if (pBase <= 0 || pBase >= 1 || pBurst >= 1) return 0;
        double g = 0;
        for (int t = from; t <= to; t++)
            g += d[t] * Math.log(pBurst / pBase)
               + (N[t] - d[t]) * Math.log((1 - pBurst) / (1 - pBase));
        return g;
    }

    /**
     * 二项 NLL（源码 -bin 分支逐式）：p = 1/rate ≥ 1 → 1e6；
     * 否则 −[lnC(n,k) + k·ln p + (n−k)·ln(1−p)]。
     */
    private static double binomNll(int k, int n, double prob) {
        if (prob >= 1.0) return 1e6;
        double logChoose = 0;
        for (int q = n; q > n - k; q--) logChoose += Math.log(q);
        for (int q = 1; q <= k; q++) logChoose -= Math.log(q);
        return -(logChoose + k * Math.log(prob) + (n - k) * Math.log(1.0 - prob));
    }
}
