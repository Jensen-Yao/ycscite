package ycscite.analysis;

import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * LinLog 布局 —— 逐式移植自 CiteSpace 7.0.1 反编译源码：
 *   yb.java（LinLogLayout 能量引擎 + 自适应步长线搜索）、yc.java（八叉树多极近似）、
 *   yd/ya（节点质量=度 / 边权重）、xY.java（AdaptorForLinLogLayout：边权双向求和、
 *   初始位置 (rand−0.5, rand−0.5, 0)、输出 ×100）。
 *
 * 源码参数（yb 构造器）：repExponent=−1.0、attrExponent=2.0、gravitationFactor=0.05；
 * 5000 次迭代；维度 3；退火（iter≥50 且起始排斥指数<1 时前 60%/90% 抬升指数）；
 * 逐节点线搜索（步长 32→1 减半 + 64→128 倍增）；每轴步长上限 = 根 cell 宽/16。
 * 原版用 Math.random()（每次运行不同），此处可设种子保证可复现。
 */
public final class LinLogLayout {

    private static final int DIMS = 3;
    private static final int MAX_DEPTH = 18;
    private static final int ITERATIONS = 5000;

    /** 布局并返回 2D 位置（x,y 已 ×100，与源码 yb.c() 一致）。 */
    public static Map<GraphNode, Point2D.Double> layout(Network g, long seed) {
        Map<GraphNode, Point2D.Double> out = new HashMap<>();
        List<GraphNode> nodes = new ArrayList<>(g.nodeCollection());
        if (nodes.isEmpty()) return out;
        if (nodes.size() == 1) { out.put(nodes.get(0), new Point2D.Double(0, 0)); return out; }

        // xY/xZ：边权入口 ×100（xZ 字节码 dmul 100.0）；sym = 单向值（原版 raw 为有向 put，
        // sym = raw[u][v]+raw[v][u] 一次）；质量 = Σsym 行和（= 100×度）
        Map<GraphNode, Map<GraphNode, Double>> w0 = new HashMap<>();
        for (GraphNode n : nodes) w0.put(n, new HashMap<>());
        for (GraphEdge e : g.edges) {
            if (e.a == e.b) continue;
            double w100 = e.weight * 100.0;
            w0.get(e.a).merge(e.b, w100, Double::sum);
            w0.get(e.b).merge(e.a, w100, Double::sum);
        }
        Map<GraphNode, Double> mass = new HashMap<>();
        double massSum = 0;
        for (GraphNode n : nodes) {
            double m = 0;
            for (double v : w0.get(n).values()) m += v;
            mass.put(n, m);
            massSum += m;
        }
        Map<GraphNode, List<GraphNode>> adj = new HashMap<>();
        Map<GraphNode, List<Double>> adjW = new HashMap<>();
        double edgeWeightSum = 0;
        for (GraphNode i : nodes) {
            List<GraphNode> a = new ArrayList<>();
            List<Double> aw = new ArrayList<>();
            for (Map.Entry<GraphNode, Double> en : w0.get(i).entrySet()) {
                a.add(en.getKey());
                double sym = en.getValue();   // 单向值（审计 P0-4：不做二次对称化）
                aw.add(sym);
                edgeWeightSum += sym;
            }
            adj.put(i, a);
            adjW.put(i, aw);
        }

        Random rnd = new Random(seed);
        Map<GraphNode, double[]> pos = new HashMap<>();
        for (GraphNode n : nodes)
            pos.put(n, new double[]{rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, 0.0});

        // yb.a()：系数初始化
        double repExp = -1.0, attrExp = 2.0;
        double gravFactor = 0.05, attrCoeff;
        if (massSum > 0 && edgeWeightSum > 0) {
            double f0 = edgeWeightSum / massSum / massSum;
            attrCoeff = f0 * Math.pow(massSum, 0.5 * (attrExp - repExp));
            gravFactor = f0 * massSum * Math.pow(gravFactor, attrExp - repExp);
        } else attrCoeff = 1.0;
        final double resumeAttr = attrExp, resumeRep = repExp;

        double[] center = new double[DIMS];
        Octree tree = new Octree(pos, mass);
        // 性能优化：自适应迭代 + 收敛早停（原版 5000 次全跑，此处 80% 收敛即停）
        int maxIter = ITERATIONS;
        if (nodes.size() > 200) maxIter = Math.min(3000, ITERATIONS);  // 大图减迭代
        if (nodes.size() > 500) maxIter = Math.min(1500, ITERATIONS);
        double prevEnergy = Double.MAX_VALUE;
        int stagnantCount = 0;
        for (int iter = 1; iter <= maxIter; iter++) {
            centerOfMass(nodes, mass, pos, center);
            Cell root = tree.build(nodes);
            double aE = attrExp, rE = repExp;
            // 审计 P0-5：原版门槛比较的是 iterCount(=5000) 恒真 —— 从第 1 轮即退火
            if (resumeRep < 1.0) {
                aE = resumeAttr;
                rE = resumeRep;
                if (iter <= 0.6 * ITERATIONS) {
                    aE += 1.1 * (1.0 - resumeRep);
                    rE += 0.9 * (1.0 - resumeRep);
                } else if (iter <= 0.9 * ITERATIONS) {
                    double f = (0.9 - (double) iter / ITERATIONS) / 0.3;
                    aE += 1.1 * (1.0 - resumeRep) * f;
                    rE += 0.9 * (1.0 - resumeRep) * f;
                }
            }
            final double AE = aE, RE = rE;
            for (GraphNode n : nodes) {
                if (mass.get(n) == 0) continue;
                double[] p = pos.get(n);
                double oldE = energy(n, root, adj, adjW, mass, pos, center, attrCoeff, AE, RE, gravFactor);
                double[] grad = new double[DIMS];
                double repE = repGrad(n, root, pos, mass, grad, attrCoeff, RE);
                double attE = 0;
                List<GraphNode> a = adj.get(n);
                List<Double> aw = adjW.get(n);
                for (int k = 0; k < a.size(); k++) {
                    double[] op = pos.get(a.get(k));
                    double d = dist(p, op);
                    if (d == 0) continue;
                    double f5 = aw.get(k) * Math.pow(d, AE - 2);
                    attE += f5 * Math.abs(AE - 1);
                    for (int j = 0; j < DIMS; j++) grad[j] += (op[j] - p[j]) * f5;
                }
                double dC = dist(p, center);
                double g8 = gravFactor * attrCoeff * mass.get(n) * Math.pow(dC, AE - 2);
                for (int j = 0; j < DIMS; j++) grad[j] += (center[j] - p[j]) * g8;
                double denom = repE + attE + g8 * Math.abs(AE - 1);
                if (denom != 0) {
                    for (int j = 0; j < DIMS; j++) grad[j] /= denom;
                    double cap = 1.0;
                    for (int j = 0; j < DIMS; j++) {
                        double wj = root.max[j] - root.min[j];
                        if (wj > 0 && grad[j] != 0) cap = Math.min(cap, Math.abs(wj / 16.0 / grad[j]));
                    }
                    for (int j = 0; j < DIMS; j++) grad[j] *= cap;
                } else Arrays.fill(grad, 0);

                double[] oldPos = p.clone();
                double bestE = oldE;
                int bestFactor = 0;
                for (int j = 0; j < DIMS; j++) grad[j] /= 32.0;
                for (int n5 = 32; n5 > 0 && (bestFactor == 0 || bestFactor / 2 == n5); n5 /= 2) {
                    tree.remove(n, p);
                    for (int j = 0; j < DIMS; j++) p[j] = oldPos[j] + grad[j] * n5;
                    tree.add(n, p);
                    double e2 = energy(n, root, adj, adjW, mass, pos, center, attrCoeff, AE, RE, gravFactor);
                    if (e2 < bestE) { bestE = e2; bestFactor = n5; }
                }
                for (int n5 = 64; n5 <= 128 && bestFactor == n5 / 2; n5 <<= 1) {
                    tree.remove(n, p);
                    for (int j = 0; j < DIMS; j++) p[j] = oldPos[j] + grad[j] * n5;
                    tree.add(n, p);
                    double e2 = energy(n, root, adj, adjW, mass, pos, center, attrCoeff, AE, RE, gravFactor);
                    if (e2 < bestE) { bestE = e2; bestFactor = n5; }
                }
                tree.remove(n, p);
                for (int j = 0; j < DIMS; j++) p[j] = oldPos[j] + grad[j] * bestFactor;
                tree.add(n, p);
            }
            // 收敛早停：总位移量 < 前次的 0.1% 且连续 50 次无改善 → 停
            double totalMove = 0;
            for (GraphNode n : nodes) {
                double[] p = pos.get(n);
                for (int j = 0; j < DIMS; j++) totalMove += Math.abs(p[j]);
            }
            if (Math.abs(totalMove - prevEnergy) < prevEnergy * 0.001) {
                stagnantCount++;
                if (stagnantCount >= 50) break;
            } else stagnantCount = 0;
            prevEnergy = totalMove;
        }
        for (GraphNode n : nodes) {   // yb.c()：×100
            double[] p = pos.get(n);
            out.put(n, new Point2D.Double(p[0] * 100.0, p[1] * 100.0));
        }
        return out;
    }

    private static double dist(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < DIMS; i++) { double d = a[i] - b[i]; s += d * d; }
        return Math.sqrt(s);
    }

    private static void centerOfMass(List<GraphNode> nodes, Map<GraphNode, Double> mass,
                                     Map<GraphNode, double[]> pos, double[] out) {
        Arrays.fill(out, 0);
        double m = 0;
        for (GraphNode n : nodes) {
            double mn = mass.get(n);
            m += mn;
            double[] p = pos.get(n);
            for (int i = 0; i < DIMS; i++) out[i] += mn * p[i];
        }
        if (m > 0) for (int i = 0; i < DIMS; i++) out[i] /= m;
    }

    /** 能量 = 排斥（八叉树）+ 吸引 + 重力（源码 yb.b(yd, yc)）。 */
    private static double energy(GraphNode n, Cell root, Map<GraphNode, List<GraphNode>> adj,
                                 Map<GraphNode, List<Double>> adjW, Map<GraphNode, Double> mass,
                                 Map<GraphNode, double[]> pos, double[] center, double attrCoeff,
                                 double attrExp, double repExp, double gravFactor) {
        double e = repEnergy(n, root, pos, mass, attrCoeff, repExp);
        double[] p = pos.get(n);
        List<GraphNode> a = adj.get(n);
        List<Double> aw = adjW.get(n);
        for (int k = 0; k < a.size(); k++) {
            double d = dist(p, pos.get(a.get(k)));
            e += aw.get(k) * (attrExp == 0 ? Math.log(d) : Math.pow(d, attrExp) / attrExp);
        }
        double d2 = dist(p, center);
        double gc = gravFactor * mass.get(n);
        e += attrExp == 0 ? gc * Math.log(d2) : gc * Math.pow(d2, attrExp) / attrExp;
        return e;
    }

    /** 排斥能量（yb.a(yd, yc)）：d < 2·cell 宽 → 递归子 cell。 */
    private static double repEnergy(GraphNode n, Cell cell, Map<GraphNode, double[]> pos,
                                    Map<GraphNode, Double> mass, double attrCoeff, double repExp) {
        if (cell == null || cell.owner == n) return 0;
        double mn = mass.get(n);
        if (mn == 0) return 0;
        double d = dist(pos.get(n), cell.com);
        if (cell.childCount > 0 && d < 2.0 * cell.maxWidth()) {
            double e = 0;
            for (Cell c : cell.children) if (c != null) e += repEnergy(n, c, pos, mass, attrCoeff, repExp);
            return e;
        }
        if (d == 0) return 0;
        return repExp == 0
                ? -attrCoeff * mn * cell.mass * Math.log(d)
                : -attrCoeff * mn * cell.mass * Math.pow(d, repExp) / repExp;
    }

    /** 排斥梯度（yb.a(yd, yc, dArray)），返回能量分量 factor·|repExp−1|。 */
    private static double repGrad(GraphNode n, Cell cell, Map<GraphNode, double[]> pos,
                                  Map<GraphNode, Double> mass, double[] grad,
                                  double attrCoeff, double repExp) {
        if (cell == null || cell.owner == n) return 0;
        double mn = mass.get(n);
        if (mn == 0) return 0;
        double[] p = pos.get(n);
        double d = dist(p, cell.com);
        if (cell.childCount > 0 && d < 2.0 * cell.maxWidth()) {
            double e = 0;
            for (Cell c : cell.children) if (c != null) e += repGrad(n, c, pos, mass, grad, attrCoeff, repExp);
            return e;
        }
        if (d == 0) return 0;
        double factor = attrCoeff * mn * cell.mass * Math.pow(d, repExp - 2);
        for (int i = 0; i < DIMS; i++) grad[i] -= (cell.com[i] - p[i]) * factor;
        return factor * Math.abs(repExp - 1);
    }

    // ---------------- 八叉树（yc 语义：质量加权质心、d<2·宽近似、深度 18、增量增删） ----------------

    static final class Cell {
        GraphNode owner;
        Cell[] children = new Cell[8];
        int childCount;
        final double[] com = new double[DIMS];
        double mass;
        final double[] min = new double[DIMS];
        final double[] max = new double[DIMS];

        Cell(double[] mn, double[] mx) {
            System.arraycopy(mn, 0, min, 0, DIMS);
            System.arraycopy(mx, 0, max, 0, DIMS);
        }

        double maxWidth() {
            double w = 0;
            for (int i = 0; i < DIMS; i++) w = Math.max(w, max[i] - min[i]);
            return w;
        }
    }

    static final class Octree {
        private final Map<GraphNode, double[]> pos;
        private final Map<GraphNode, Double> mass;
        private Cell root;

        Octree(Map<GraphNode, double[]> pos, Map<GraphNode, Double> mass) {
            this.pos = pos;
            this.mass = mass;
        }

        Cell build(List<GraphNode> nodes) {
            double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (GraphNode n : nodes) {
                if (mass.get(n) == 0) continue;
                double[] p = pos.get(n);
                for (int i = 0; i < DIMS; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
            }
            for (int i = 0; i < DIMS; i++) { double w = (mx[i] - mn[i]) / 2.0; mx[i] += w; mn[i] -= w; }
            root = new Cell(mn, mx);
            for (GraphNode n : nodes) add(n, pos.get(n));
            return root;
        }

        void add(GraphNode n, double[] p) {
            double m = mass.get(n);
            if (m == 0) return;
            add(root, n, p, m, 0);
        }

        private void add(Cell cell, GraphNode n, double[] p, double m, int depth) {
            // 质心/质量增量更新（yc.a）
            for (int i = 0; i < DIMS; i++) cell.com[i] = (cell.mass * cell.com[i] + m * p[i]) / (cell.mass + m);
            cell.mass += m;
            if (cell.owner == null && cell.childCount == 0) { cell.owner = n; return; }
            if (cell.owner != null) {                 // 下推原节点
                GraphNode old = cell.owner;
                double[] op = pos.get(old);
                cell.owner = null;
                insertDown(cell, old, op, mass.get(old), depth);
            }
            if (depth >= MAX_DEPTH) return;           // 深度 18：不再分（退化为共存）
            insertDown(cell, n, p, m, depth);
        }

        private void insertDown(Cell cell, GraphNode n, double[] p, double m, int depth) {
            int idx = 0;
            for (int i = 0; i < DIMS; i++) if (p[i] > (cell.min[i] + cell.max[i]) / 2.0) idx += 1 << i;
            Cell c = cell.children[idx];
            if (c == null) {
                double[] mn = new double[DIMS], mx = new double[DIMS];
                for (int i = 0; i < DIMS; i++) {
                    boolean hi = (idx & (1 << i)) != 0;
                    mn[i] = hi ? (cell.min[i] + cell.max[i]) / 2.0 : cell.min[i];
                    mx[i] = hi ? cell.max[i] : (cell.min[i] + cell.max[i]) / 2.0;
                }
                c = new Cell(mn, mx);
                c.owner = n;
                System.arraycopy(p, 0, c.com, 0, DIMS);
                c.mass = m;
                cell.children[idx] = c;
                cell.childCount++;
                return;
            }
            add(c, n, p, m, depth + 1);
        }

        void remove(GraphNode n, double[] p) {
            double m = mass.get(n);
            if (m == 0) return;
            remove(root, n, p, m, 0);
        }

        private void remove(Cell cell, GraphNode n, double[] p, double m, int depth) {
            if (cell.mass <= m) {                     // yc.b：清空
                cell.mass = 0;
                cell.owner = null;
                cell.children = new Cell[8];
                cell.childCount = 0;
                return;
            }
            for (int i = 0; i < DIMS; i++) cell.com[i] = (cell.mass * cell.com[i] - m * p[i]) / (cell.mass - m);
            cell.mass -= m;
            if (cell.owner == n) { cell.owner = null; return; }
            int idx = 0;
            for (int i = 0; i < DIMS; i++) if (p[i] > (cell.min[i] + cell.max[i]) / 2.0) idx += 1 << i;
            Cell c = cell.children[idx];
            if (c == null) return;
            remove(c, n, p, m, depth + 1);
            if (c.mass == 0) { cell.children[idx] = null; cell.childCount--; }
        }
    }
}
