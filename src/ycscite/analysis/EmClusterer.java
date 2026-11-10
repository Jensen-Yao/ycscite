package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.util.*;

/**
 * EM 聚类（原版 "EM on bibliographic attributes"，ph.java 1756-1758，WEKA 实现的零依赖替代）：
 * 对角协方差高斯混合，特征 = [log 频次, 中介中心性, 首现年] 归一化；
 * k 默认 clamp(√(n/2), 2, 12)，最多 120 轮 EM（对数似然变化 < 1e-4 早停）；
 * 簇号按规模降序（与 Clusterer 口径一致，#1 最大）。
 */
public final class EmClusterer {

    public static double compute(Network g) {
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        if (nodes.size() < 2) { for (GraphNode n : nodes) n.cluster = 1; return 0; }
        int n = nodes.size();
        double[][] X = new double[n][3];
        double[] max = {1, 1e-9, 1}, min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        for (int i = 0; i < n; i++) {
            GraphNode v = nodes.get(i);
            X[i][0] = Math.log(1 + v.weight);
            X[i][1] = v.centrality;
            X[i][2] = v.firstYear == Integer.MAX_VALUE ? 0 : v.firstYear;
            for (int d = 0; d < 3; d++) {
                max[d] = Math.max(max[d], X[i][d]);
                min[d] = Math.min(min[d], X[i][d]);
            }
        }
        for (int i = 0; i < n; i++)
            for (int d = 0; d < 3; d++)
                X[i][d] = max[d] > min[d] ? (X[i][d] - min[d]) / (max[d] - min[d]) : 0.5;

        int k = Math.max(2, Math.min(12, (int) Math.sqrt(n / 2.0)));
        Random rnd = new Random(0);
        double[][] mean = new double[k][3];
        double[][] var = new double[k][3];
        double[] pi = new double[k];
        for (int c = 0; c < k; c++) {
            System.arraycopy(X[rnd.nextInt(n)], 0, mean[c], 0, 3);
            Arrays.fill(var[c], 0.25);
            pi[c] = 1.0 / k;
        }
        double[][] resp = new double[n][k];
        for (int it = 0; it < 120; it++) {
            double ll = 0;
            for (int i = 0; i < n; i++) {
                double sum = 0;
                for (int c = 0; c < k; c++) {
                    double d2 = 0;
                    for (int d = 0; d < 3; d++) {
                        double diff = X[i][d] - mean[c][d];
                        d2 += diff * diff / Math.max(var[c][d], 1e-6);
                    }
                    double det = Math.max(var[c][0], 1e-6) * Math.max(var[c][1], 1e-6) * Math.max(var[c][2], 1e-6);
                    resp[i][c] = pi[c] * Math.exp(-0.5 * d2) / Math.sqrt(det * Math.pow(2 * Math.PI, 3));
                    sum += resp[i][c];
                }
                if (sum <= 0) { Arrays.fill(resp[i], 1.0 / k); sum = 1; }
                for (int c = 0; c < k; c++) resp[i][c] /= sum;
                ll += Math.log(sum + 1e-300);
            }
            // M 步
            double[] nEff = new double[k];
            for (int i = 0; i < n; i++) for (int c = 0; c < k; c++) nEff[c] += resp[i][c];
            for (int c = 0; c < k; c++) {
                if (nEff[c] < 1e-6) { System.arraycopy(X[rnd.nextInt(n)], 0, mean[c], 0, 3); continue; }
                pi[c] = nEff[c] / n;
                for (int d = 0; d < 3; d++) {
                    double m = 0;
                    for (int i = 0; i < n; i++) m += resp[i][c] * X[i][d];
                    m /= nEff[c];
                    mean[c][d] = m;
                    double v = 1e-4;
                    for (int i = 0; i < n; i++) v += resp[i][c] * (X[i][d] - m) * (X[i][d] - m);
                    var[c][d] = v / nEff[c];
                }
            }
            if (it > 0 && Math.abs(ll) < 1e-4) break;
        }
        int[] assign = new int[n];
        for (int i = 0; i < n; i++) {
            int best = 0;
            for (int c = 1; c < k; c++) if (resp[i][c] > resp[i][best]) best = c;
            assign[i] = best;
        }
        // 按规模降序重编号 1..k
        Map<Integer, Integer> sizes = new TreeMap<>();
        for (int a : assign) sizes.merge(a, 1, Integer::sum);
        List<Integer> ids = new ArrayList<>(sizes.keySet());
        ids.sort((x, y) -> sizes.get(y) - sizes.get(x));
        Map<Integer, Integer> relabel = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) relabel.put(ids.get(i), i + 1);
        for (int i = 0; i < n; i++) nodes.get(i).cluster = relabel.get(assign[i]);
        return logLikelihood(X, n, k, mean, var, pi);
    }

    private static double logLikelihood(double[][] X, int n, int k, double[][] mean, double[][] var, double[] pi) {
        double ll = 0;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int c = 0; c < k; c++) {
                double d2 = 0;
                for (int d = 0; d < 3; d++) {
                    double diff = X[i][d] - mean[c][d];
                    d2 += diff * diff / Math.max(var[c][d], 1e-6);
                }
                double det = Math.max(var[c][0], 1e-6) * Math.max(var[c][1], 1e-6) * Math.max(var[c][2], 1e-6);
                sum += pi[c] * Math.exp(-0.5 * d2) / Math.sqrt(det * Math.pow(2 * Math.PI, 3));
            }
            ll += Math.log(sum + 1e-300);
        }
        return ll;
    }
}
