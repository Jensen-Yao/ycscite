package ycscite.ui;

import ycscite.analysis.LLRLabeler;
import ycscite.model.GraphEdge;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Network canvas aligned with CiteSpace's visual language:
 * Timeline view = year columns on x, cluster bands on y, citation-history year rings on nodes,
 * curved links, legend panel (clusters + year color scale + symbol key), purple ring for high
 * betweenness centrality, red ring for bursts. Wheel zoom, drag pan, hover tooltips,
 * focus highlighting with search-and-center.
 */
public final class GraphCanvas extends JPanel {

    public enum Mode { TIMELINE, TIMEZONE, MERGED, TSNE, CIRCULAR, HEATMAP, LANDSCAPE, UMAP, CIRCLE_PACK }
    public enum SizeMode { FREQ, SIGMA, CENTRALITY, DEGREE, EIGEN, PAGERANK, ENTROPY, RELATIVE_ENTROPY, INFO_BIAS, UNIFORM }

    private static final Color[] PALETTE = {
        new Color(78, 121, 167), new Color(242, 142, 43), new Color(225, 87, 89),
        new Color(118, 183, 178), new Color(89, 161, 79), new Color(237, 201, 72),
        new Color(176, 122, 161), new Color(255, 157, 167), new Color(156, 117, 95)
    };

    /**
     * 原版簇色表（7.0.1 mM.c 逐式）：350 色彩虹环（洋红→蓝→青→绿→黄→红），
     * 前 10 簇按 step=350/9 均匀采样（倒序填充），第 11+ 簇灰度渐变。
     */
    private static Color[] mMClusterPalette(int n) {
        Color[] out = new Color[n];
        if (n <= 1) { out[0] = new Color(250, 250, 250, 100); return out; }
        java.util.List<Color> ring = new ArrayList<>(350);
        for (int i = 50; i > 0; i--) ring.add(new Color(i * 255 / 100, 0, 255));
        for (int i = 0; i < 100; i++) ring.add(new Color(0, i * 255 / 100, 255));
        for (int i = 100; i > 0; i--) ring.add(new Color(0, 255, i * 255 / 100));
        for (int i = 0; i < 100; i++) ring.add(new Color(i * 255 / 100, 255, 0));
        for (int i = 100; i > 0; i--) ring.add(new Color(255, i * 255 / 100, 0));
        int n10 = Math.min(n, 10);
        int step = ring.size() / (n10 - 1);
        for (int i = 0; i < n10; i++) {
            int idx = i * step - (i > 0 ? 1 : 0);
            out[n10 - 1 - i] = ring.get(idx);
        }
        for (int i = 10; i < n; i++) {
            int g = i * 255 / n;
            out[i] = new Color(g, g, g);
        }
        return out;
    }

    /** 当前网络的簇色缓存（原版 mM 口径）。 */
    private Color[] clusterPalette = null;
    private int clusterPaletteN = -1;

    /** 簇色（原版 mM.c：按簇总数生成）。 */
    private Color clusterColorOf(int cluster, int totalClusters) {
        if (cluster < 0) cluster = 0;
        if (totalClusters != clusterPaletteN || clusterPalette == null) {
            clusterPalette = mMClusterPalette(Math.max(1, totalClusters));
            clusterPaletteN = totalClusters;
        }
        return clusterPalette[Math.floorMod(cluster, clusterPalette.length)];
    }

    /**
     * 原版弧线控制点（7.0.1 aq.a(Point,Point) 逐式）：
     * 中点 + (d/4)·(sin,cos)θ 同侧上拱，d=弦长，θ=atan(|dy|/|dx|)。
     */
    private static Point2D.Double aqControl(Point2D.Double p1, Point2D.Double p2) {
        double mx = (p1.x + p2.x) / 2, my = (p1.y + p2.y) / 2;
        double d = Math.hypot(p1.x - p2.x, p1.y - p2.y);
        double ang = Math.atan(Math.abs(p1.y - p2.y) / Math.max(1e-9, Math.abs(p1.x - p2.x)));
        double off = d / 4.0;
        double dx = off * Math.sin(ang), dy = off * Math.cos(ang);
        double cx, cy;
        if (p1.x <= p2.x) {
            if (p1.y >= p2.y) { cx = mx - dx; cy = my - dy; }
            else { cx = mx + dx; cy = my - dy; }
        } else {
            if (p1.y <= p2.y) { cx = mx + dx; cy = my + dy; }
            else { cx = mx - dx; cy = my + dy; }
        }
        return new Point2D.Double(cx, cy);
    }
    private static final double WORLD_W = 1150, WORLD_H = 700;
    private static final int BAND_LABEL_MAX = 2;

    private Network network;
    private Mode mode = Mode.TIMELINE;
    private SizeMode sizeMode = SizeMode.FREQ;
    private String title = "";
    /** 原版风格左上参数面板行（Pipeline.paramLines 口径） */
    public java.util.List<String> paramPanel = new java.util.ArrayList<>();
    private Map<Integer, List<LLRLabeler.Scored>> clusterLabels = Collections.emptyMap();
    private final Map<GraphNode, Point2D.Double> pos = new HashMap<>();
    private final Map<GraphNode, Double> radius = new HashMap<>();
    /** cluster band extents in world coords, for TIMELINE layout and the report/tests */
    private final Map<Integer, double[]> bands = new TreeMap<>();
    private int labelBudget = 25; // 原版显示 25-30 个标签 // 原版仅标最高频词 // 原版仅标高频词（视觉对比校准：原版墨水密度 22% vs 我方 43%）
    private float labelFontSize = 11f;               // 标签字号（原版 ph:1327 Label Font Size）
    private boolean fisheye = false;                 /** 鱼眼视图开关 */
    private double fisheyeFocusX, fisheyeFocusY;     /** 鱼眼焦点（世界坐标）*/
    private double fisheyeStrength = 3.0;
    private int labelAngle = 0;                      /** 标签旋转角度 0/30/45/60 */
    private boolean convexHull = false;              /** 聚类凸包轮廓 */
    private boolean originalStyle = false;           // 原版 CiteSpace 外观：白底/红标签/45°/大节点动态
    public enum Theme { DARK, LIGHT, SEPIA }
    private Theme theme = Theme.DARK;
    public enum NodeShape { CIRCLE, DIAMOND, SQUARE }
    private NodeShape nodeShape = NodeShape.CIRCLE;    // 节点形状（原版 ph:1099-1108）
    private float linkTransparency = 0.18f;           // 链接透明度（视觉校准：降低到接近原版密度）            // 链接透明度 0-1（原版 ph:1091）
    private boolean linkWeightedWidth = true;         // 链接宽度按权重（原版 ph:1094 Weighted）
    private boolean curvedLines = false;              // 曲线链接（原版 ph:1417-1428）
    private boolean linkColorByCluster = false;       // 链接按聚类着色（原版 ph:1506）
    private boolean effects3d = false;                // 3D 节点效果（原版 ph:1551）
    private boolean linkLabels = false;               // 链接权重标签（原版 ph:1493）
    private boolean showClusterId = false;            // 节点旁显示簇号（原版 ph:1565）
    private boolean showCitationCount = false;        // 节点旁显示频次（原版 ph:1563）
    private boolean labelColorByCluster = false;      // 标签色=聚类色（原版 ph:1342）
    private double nodeScale = 1.0;                   // 节点缩放（原版 ph:1257 Rescale）
    public enum LayoutAlgo { LINLOG, KAMADA_KAWAI, FORCE_DIRECTED }
    private LayoutAlgo layoutAlgo = LayoutAlgo.LINLOG;    // 布局算法（原版 ph:1039-1042）
    private int kcoreHighlight = 0;                    // k-Core 高亮层级（原版 ph:1591-1598，0=off）
    private boolean linkSingleColor = false;           // 链接单色模式（原版 ph:1495-1501）
    private boolean showPubType = false;               // 显示出版类型（原版 ph:1088）
    // 四类标签底色（原版 ph:1362-1381：Cluster/Article/Term/Overlay 四种标签独立底色）
    private Color clusterLabelBg = null, articleLabelBg = null, termLabelBg = null, overlayLabelBg = null;
    // Areas Fill Patterns（原版 ph:1605-1621：凸包填充模式）
    private int hullFillPattern = 0;                   // 0=纯色 1=交叉线 2=点阵 3=水平线
    // Label Placement 参数（原版 ph:1383-1408）
    private int labelPlacementRate = 100;              // 防重叠尝试次数
    private double[][] tsnePos;                      /** t-SNE 坐标（与 nodeCollection 顺序对齐）*/
    private final java.util.List<GraphNode> tsneOrder = new ArrayList<>();
    private GraphNode focusNode;
    /** 年份演化动画：只显示 firstYear <= yearCutoff 的节点与边 */
    private int yearCutoff = Integer.MAX_VALUE;
    private GraphNode expandedNode;          // 右键"展开邻居"的高亮节点
    private int highlightCluster = Integer.MIN_VALUE; // 聚类列表点击高亮某簇
    private final Set<String> highlightIds = new HashSet<>();   // .list 叠加高亮（原版 Add Node Highlights）
    private boolean labelShadow = true;                     // 标签阴影（原版视觉选项）
    private boolean transparentBg = false;                  // 透明背景导出
    private final List<String[]> overlayEdges = new ArrayList<>();   // .layer 叠加边 from/to（原版 Add a Network Layer）
    /** 主路径叠加（原版 "Add Main Path Layers"）：from	to 对 */
    private final Set<String> overlayKeys = new HashSet<>();
    private GraphNode selectedNode;          // 单击选中的节点（信息面板）
    public void setSelectedNode(GraphNode n) { this.selectedNode = n; repaint(); }
    private java.util.function.Consumer<GraphNode> selectionListener;
    private GraphNode mergeSource;           // 右键"合并到…"的源节点（等待左键目标）
    private final Set<GraphNode> hiddenNodes = new HashSet<>(); // 右键"过滤"的隐藏节点
    private Runnable networkChanged;
    private Runnable beforeEdit;             // 编辑动作前调用（撤销快照）
    private double zoom = 1, offX = 0, offY = 0;

    public GraphCanvas() {
        setBackground(new Color(17, 17, 17));
        setToolTipText("");
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { fitView(); if (network != null) repaint(); }
        });
        addMouseWheelListener(e -> {
            double factor = e.getWheelRotation() < 0 ? 1.15 : 1 / 1.15;
            double mx = e.getX(), my = e.getY();
            zoom = Math.min(12, Math.max(0.15, zoom * factor));
            offX = mx - (mx - offX) * factor;
            offY = my - (my - offY) * factor;
            repaint();
        });
        MouseAdapter pan = new MouseAdapter() {
            Point last;
            @Override public void mousePressed(MouseEvent e) { last = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (last == null) return;
                offX += e.getX() - last.x;
                offY += e.getY() - last.y;
                last = e.getPoint();
                repaint();
            }
            @Override public void mouseMoved(MouseEvent e) {
                if (fisheye) setFisheyeFocusAtScreen(e.getX(), e.getY());
            }
        };
        addMouseListener(pan);
        addMouseMotionListener(pan);

        // ---- 节点右键上下文菜单（合并/展开/过滤/删除）与合并目标点选 ----
        MouseAdapter ctx = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && mergeSource != null && network != null) {
                    GraphNode t = hitNode(e.getX(), e.getY());
                    if (t != null && t != mergeSource) {
                        if (beforeEdit != null) beforeEdit.run();
                        network.mergeNodes(mergeSource, t);
                        hiddenNodes.remove(mergeSource);
                        afterEdit();
                    }
                    mergeSource = null;
                    repaint();
                    return;
                }
                // 单击选中节点（供节点信息面板），点空白取消选中
                if (SwingUtilities.isLeftMouseButton(e) && mergeSource == null) {
                    selectedNode = hitNode(e.getX(), e.getY());
                    if (selectionListener != null) selectionListener.accept(selectedNode);
                    repaint();
                }
            }
            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger() || network == null) return;
                GraphNode n = hitNode(e.getX(), e.getY());
                if (n == null) return;
                JPopupMenu menu = new JPopupMenu(n.label);
                JMenuItem merge = new JMenuItem(Strings.get("ctx.merge"));
                merge.addActionListener(ev -> {
                    mergeSource = n;
                    JOptionPane.showMessageDialog(GraphCanvas.this, Strings.get("ctx.mergehint"));
                });
                JMenuItem expand = new JMenuItem(expandedNode == n ? Strings.get("ctx.collapse") : Strings.get("ctx.expand"));
                expand.addActionListener(ev -> { expandedNode = expandedNode == n ? null : n; repaint(); });
                JMenuItem filter = new JMenuItem(Strings.get("ctx.filter"));
                filter.addActionListener(ev -> { if (beforeEdit != null) beforeEdit.run(); hiddenNodes.add(n); afterEdit(); });
                JMenuItem hideCluster = new JMenuItem(Strings.get("ctx.hidecluster"));
                hideCluster.addActionListener(ev -> {
                    if (beforeEdit != null) beforeEdit.run();
                    for (GraphNode m : network.nodeCollection()) if (m.cluster == n.cluster) hiddenNodes.add(m);
                    afterEdit();
                });
                JMenuItem restore = new JMenuItem(Strings.get("ctx.restore"));
                restore.addActionListener(ev -> { hiddenNodes.clear(); afterEdit(); });
                JMenuItem exclude = new JMenuItem(Strings.get("ctx.exclude"));
                exclude.addActionListener(ev -> {
                    if (beforeEdit != null) beforeEdit.run();
                    network.removeNode(n);           // 排除 = 从网络删除（重跑分析时经排除表过滤）
                    afterEdit();
                });
                JMenuItem delete = new JMenuItem(Strings.get("ctx.delete"));
                delete.addActionListener(ev -> { if (beforeEdit != null) beforeEdit.run(); network.removeNode(n); afterEdit(); });
                JMenuItem details = new JMenuItem(Strings.get("ctx.details"));
                JMenuItem bookmark = new JMenuItem(n.bookmarked ? Strings.get("ctx.unbookmark") : Strings.get("ctx.bookmark"));
                bookmark.addActionListener(ev -> { n.bookmarked = !n.bookmarked; repaint(); });
                JMenuItem annotate = new JMenuItem(Strings.get("ctx.annotate"));
                annotate.addActionListener(ev -> {
                    String txt = JOptionPane.showInputDialog(GraphCanvas.this, Strings.get("ctx.annotate.prompt"), n.annotation);
                    if (txt != null) { n.annotation = txt; repaint(); }
                });
                JMenuItem rename = new JMenuItem(Strings.get("ctx.rename"));
                JMenuItem similar = new JMenuItem(Strings.get("ctx.similar"));
                JMenuItem newWindow = new JMenuItem(Strings.get("ctx.newwindow"));
                newWindow.addActionListener(ev -> {
                    JFrame nwFrame = new JFrame("Ycscite — " + n.label);
                    GraphCanvas nwCanvas = new GraphCanvas();
                    nwCanvas.show(network, mode, title);
                    nwCanvas.setSelectedNode(n);
                    nwFrame.add(nwCanvas);
                    nwFrame.setSize(800, 600);
                    nwFrame.setLocationRelativeTo(GraphCanvas.this);
                    nwFrame.setVisible(true);
                });
                similar.addActionListener(ev -> {
                    var sub = ycscite.analysis.SimilarityNetwork.buildSimilarSubnet(network, n, 10);
                    if (sub.nodes.size() > 1) {
                        JFrame simFrame = new JFrame("Similarity Network: " + n.label + " (" + sub.nodes.size() + " nodes)");
                        GraphCanvas simCanvas = new GraphCanvas();
                        simCanvas.show(sub, GraphCanvas.Mode.MERGED, n.label);
                        simFrame.add(simCanvas);
                        simFrame.setSize(600, 400);
                        simFrame.setLocationRelativeTo(GraphCanvas.this);
                        simFrame.setVisible(true);
                    }
                });
                rename.addActionListener(ev -> {
                    String txt = JOptionPane.showInputDialog(GraphCanvas.this, Strings.get("ctx.rename.prompt"), n.userLabel == null ? n.label : n.userLabel);
                    if (txt != null && !txt.isBlank()) { n.userLabel = txt; repaint(); }
                });
                details.addActionListener(ev -> {
                    selectedNode = n;
                    if (selectionListener != null) selectionListener.accept(n);
                    repaint();
                });
                // 溯源跳转（原版 Go to URL/Google Scholar/PubMed/DOI/The Lens/Patents/ACM/CiteSeer）
                JMenuItem scholar = new JMenuItem(Strings.get("ctx.scholar"));
                scholar.addActionListener(ev -> browse("https://scholar.google.com/scholar?q=" + enc(n.label)));
                JMenuItem pubmed = new JMenuItem(Strings.get("ctx.pubmed"));
                pubmed.addActionListener(ev -> browse("https://pubmed.ncbi.nlm.nih.gov/?term=" + enc(n.label)));
                JMenuItem cnki = new JMenuItem(Strings.get("ctx.cnki"));
                cnki.addActionListener(ev -> browse("https://kns.cnki.net/kns8s/defaultresult/index?kw=" + enc(n.label)));
                JMenuItem doi = new JMenuItem(Strings.get("ctx.doi"));
                doi.addActionListener(ev -> browse("https://doi.org/" + enc(n.id)));
                JMenuItem lens = new JMenuItem(Strings.get("ctx.lens"));
                lens.addActionListener(ev -> browse("https://www.lens.org/lens/search/scholar/list?q=" + enc(n.label)));
                JMenuItem patents = new JMenuItem(Strings.get("ctx.patents"));
                patents.addActionListener(ev -> browse("https://patents.google.com/?q=" + enc(n.label)));
                JMenuItem acm = new JMenuItem(Strings.get("ctx.acm"));
                acm.addActionListener(ev -> browse("https://dl.acm.org/action/doSearch?AllField=" + enc(n.label)));
                menu.add(details);
                menu.add(bookmark);
                menu.add(annotate);
                menu.add(rename);
                menu.add(similar);
                menu.add(newWindow);
                menu.addSeparator();
                menu.add(merge);
                menu.add(expand);
                menu.add(filter);
                menu.add(hideCluster);
                menu.add(restore);
                menu.addSeparator();
                menu.add(exclude);
                menu.add(delete);
                menu.addSeparator();
                menu.add(scholar);
                menu.add(doi);
                menu.add(lens);
                menu.add(patents);
                menu.add(acm);
                menu.add(pubmed);
                menu.add(cnki);
                menu.show(GraphCanvas.this, e.getX(), e.getY());
            }
        };
        addMouseListener(ctx);
    }

    // ---------- 新显示开关（鱼眼/标签角度/凸包/t-SNE） ----------
    public void setFisheye(boolean on) { this.fisheye = on; repaint(); }

    /** 用系统浏览器打开溯源链接（原版 Go to… 系列动作）。 */
    private static void browse(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, url);
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
    public boolean isFisheye() { return fisheye; }
    public void setFisheyeFocusAtScreen(int mx, int my) {
        Point2D.Double w = toWorld(mx, my);
        this.fisheyeFocusX = w.x;
        this.fisheyeFocusY = w.y;
        if (fisheye) repaint();
    }
    public void setLabelAngle(int deg) { this.labelAngle = 0; repaint(); }
    public int getLabelAngle() { return labelAngle; }
    public void setConvexHull(boolean on) { this.convexHull = on; repaint(); }
    public boolean isConvexHull() { return convexHull; }

    /** 原版 CiteSpace 外观（白底、红色全显标签、45°、节点动态 5-10 倍、灰细弧线）。 */
    public void setTheme(Theme t) { theme = t; repaint(); }
    public Theme getTheme() { return theme; }
    /** 主题对应背景色 */
    private Color themeBg() {
        return switch (theme) { case LIGHT -> Color.WHITE; case SEPIA -> new Color(245, 240, 230); default -> new Color(17, 17, 17); };
    }
    private Color themeFg() {
        return switch (theme) { case LIGHT, SEPIA -> new Color(30, 30, 30); default -> Color.WHITE; };
    }
    public void setOriginalStyle(boolean on) {
        this.originalStyle = on;
        if (on) {
            labelAngle = 45;
            labelBudget = Integer.MAX_VALUE;
        } else {
            labelAngle = 0;
            labelBudget = 30;
        }
        repaint();
    }
    public boolean isOriginalStyle() { return originalStyle; }

    /** t-SNE 布局（TSNE 模式专用）。 */
    /** UMAP-lite 布局（kNN 模糊并集 + 吸引/排斥，固定种子）。 */
    /** Circle Packing 视图（原版 ph:1623-1626）：聚类圆按规模嵌套排列（简化：各簇圆相邻排布）。 */
    private void circlePackLayout() {
        if (network == null) return;
        Map<Integer, List<GraphNode>> byCluster = new TreeMap<>();
        for (GraphNode n : network.nodeCollection())
            byCluster.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);
        // 各簇的半径 ∝ √节点数；沿水平方向依次排布（横排堆叠版）
        double cx = 80, maxR = 0;
        for (var en : byCluster.entrySet()) {
            List<GraphNode> members = en.getValue();
            members.sort((a, b) -> Integer.compare(b.weight, a.weight));
            double R = Math.max(20, 12 * Math.sqrt(members.size()));
            double cy = WORLD_H / 2 + (en.getKey() % 2 == 0 ? -1 : 1) * (R + 16) * (en.getKey() / 2);
            // 簇内螺旋排布
            double golden = Math.PI * (3 - Math.sqrt(5));
            for (int i = 0; i < members.size(); i++) {
                double rr = R * Math.sqrt((i + 0.5) / members.size());
                double th = i * golden;
                pos.put(members.get(i), new Point2D.Double(cx + rr * Math.cos(th), cy + rr * Math.sin(th)));
            }
            cx += 2 * R + 20;
            maxR = Math.max(maxR, R);
        }
    }

    private void umapLayout() {
        if (network == null || network.nodes.isEmpty()) return;
        var p = ycscite.analysis.Umap.layout(network, 42L, 300);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (var v : p.values()) {
            minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
            minY = Math.min(minY, v.y); maxY = Math.max(maxY, v.y);
        }
        double pad = 80;
        double s = Math.min((WORLD_W - 2 * pad) / Math.max(maxX - minX, 1e-9),
                            (WORLD_H - 2 * pad) / Math.max(maxY - minY, 1e-9));
        for (var en : p.entrySet())
            pos.put(en.getKey(), new Point2D.Double(pad + (en.getValue().x - minX) * s,
                                                    pad + (en.getValue().y - minY) * s));
    }

    private void tsneLayout() {
        if (network == null || network.nodes.isEmpty()) return;
        tsneOrder.clear();
        tsneOrder.addAll(network.nodeCollection());
        tsnePos = ycscite.analysis.Tsne.embed(network, 260);
        pos.clear();
        double R = Math.min(WORLD_W, WORLD_H) * 0.42;
        for (int i = 0; i < tsneOrder.size() && i < tsnePos.length; i++)
            pos.put(tsneOrder.get(i), new Point2D.Double(WORLD_W / 2 + R * tsnePos[i][0],
                    WORLD_H / 2 + R * tsnePos[i][1]));
    }

    /** 鱼眼变换：焦点附近放大，远处压缩。 */
    private Point2D.Double fisheyeTransform(Point2D.Double p) {
        if (!fisheye) return p;
        double dx = p.x - fisheyeFocusX, dy = p.y - fisheyeFocusY;
        double d = Math.sqrt(dx * dx + dy * dy);
        double maxD = Math.max(WORLD_W, WORLD_H) * 0.6;
        if (d < 1e-6 || d > maxD * 2) return p;
        double s = (fisheyeStrength + 1) / (fisheyeStrength + Math.min(d, maxD) / maxD);
        return new Point2D.Double(fisheyeFocusX + dx * s, fisheyeFocusY + dy * s);
    }

    /** Graham scan 凸包（static 供测试）。 */
    /** 凸包从质心向外扩 margin 像素（聚类糊状填充/分离用的带边距包络）。 */
    static List<Point2D.Double> expandHull(List<Point2D.Double> hull, double margin) {
        double cx = 0, cy = 0;
        for (Point2D.Double p : hull) { cx += p.x; cy += p.y; }
        cx /= hull.size(); cy /= hull.size();
        List<Point2D.Double> out = new ArrayList<>(hull.size());
        for (Point2D.Double p : hull) {
            double dx = p.x - cx, dy = p.y - cy;
            double d = Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-9) { out.add(p); continue; }
            out.add(new Point2D.Double(p.x + dx / d * margin, p.y + dy / d * margin));
        }
        return out;
    }

    public static List<Point2D.Double> convexHull(List<Point2D.Double> pts) {
        if (pts.size() < 3) return new ArrayList<>(pts);
        List<Point2D.Double> p = new ArrayList<>(pts);
        p.sort((a, b) -> a.x != b.x ? Double.compare(a.x, b.x) : Double.compare(a.y, b.y));
        List<Point2D.Double> lower = new ArrayList<>(), upper = new ArrayList<>();
        for (Point2D.Double pt : p) {
            while (lower.size() >= 2 && !ccw(lower.get(lower.size() - 2), lower.get(lower.size() - 1), pt))
                lower.remove(lower.size() - 1);
            lower.add(pt);
        }
        for (int i = p.size() - 1; i >= 0; i--) {
            Point2D.Double pt = p.get(i);
            while (upper.size() >= 2 && !ccw(upper.get(upper.size() - 2), upper.get(upper.size() - 1), pt))
                upper.remove(upper.size() - 1);
            upper.add(pt);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        return lower;
    }

    /** o→a→b 是否逆时针（凸包扫描谓词）。 */
    private static boolean ccw(Point2D.Double o, Point2D.Double a, Point2D.Double b) {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x) > 0;
    }

    /** 网络被右键操作修改后：重排并通知宿主（MainFrame 重算指标）。 */
    private void afterEdit() {
        computeLayout();
        if (networkChanged != null) networkChanged.run();
        repaint();
    }

    public void setNetworkChanged(Runnable r) { this.networkChanged = r; }
    public void setBeforeEdit(Runnable r) { this.beforeEdit = r; }

    /** 单击选中的节点与监听（节点信息面板）。 */
    public GraphNode getSelectedNode() { return selectedNode; }
    public void setSelectionListener(java.util.function.Consumer<GraphNode> l) { this.selectionListener = l; }

    /** 聚类列表点击：高亮/取消高亮某个簇。 */
    public void highlightCluster(int cluster) {
        this.highlightCluster = (this.highlightCluster == cluster) ? Integer.MIN_VALUE : cluster;
        repaint();
    }
    public int getHighlightCluster() { return highlightCluster; }

    /** .list 叠加高亮：标签或 id 命中即高亮（原版 "Add Node Highlights by DOIs or Node Label Lists"）。 */
    public void setHighlightList(Set<String> labelsOrIds) {
        highlightIds.clear();
        if (labelsOrIds != null) for (String s : labelsOrIds) highlightIds.add(s.trim().toUpperCase());
        repaint();
    }


    // ---------- 可视化细调选项（原版 ph:1091-1551 对应项） ----------
    public void setNodeShape(NodeShape s) { nodeShape = s; repaint(); }
    public NodeShape getNodeShape() { return nodeShape; }
    public void setLinkTransparency(float t) { linkTransparency = Math.max(0f, Math.min(1f, t)); repaint(); }
    public void setLinkWeightedWidth(boolean on) { linkWeightedWidth = on; repaint(); }
    public void setCurvedLines(boolean on) { curvedLines = on; repaint(); }
    public void setLinkColorByCluster(boolean on) { linkColorByCluster = on; repaint(); }
    public void setEffects3d(boolean on) { effects3d = on; repaint(); }
    public void setLinkLabels(boolean on) { linkLabels = on; repaint(); }
    public void setShowClusterId(boolean on) { showClusterId = on; repaint(); }
    public void setShowCitationCount(boolean on) { showCitationCount = on; repaint(); }
    public void setLabelColorByCluster(boolean on) { labelColorByCluster = on; repaint(); }
    public void setLayoutAlgo(LayoutAlgo a) { layoutAlgo = a; computeLayout(); repaint(); }
    public LayoutAlgo getLayoutAlgo() { return layoutAlgo; }

    public void setNodeScale(double s) { nodeScale = Math.max(0.3, Math.min(3.0, s)); repaint(); }
    public void setKcoreHighlight(int k) { kcoreHighlight = k; repaint(); }
    public void setLinkSingleColor(boolean on) { linkSingleColor = on; repaint(); }
    public void setShowPubType(boolean on) { showPubType = on; repaint(); }
    public void setClusterLabelBg(Color c) { clusterLabelBg = c; repaint(); }
    public void setHullFillPattern(int p) { hullFillPattern = Math.max(0, Math.min(3, p)); repaint(); }
    public void setLabelPlacementRate(int r) { labelPlacementRate = Math.max(10, Math.min(1000, r)); repaint(); }
    public int getHighlightCount() { return highlightIds.size(); }

    public void setLabelShadow(boolean on) { labelShadow = on; repaint(); }
    public boolean isLabelShadow() { return labelShadow; }
    public void setTransparentBg(boolean on) { transparentBg = on; repaint(); }

    /** .layer 叠加：from,to 文本行（空白/逗号/制表分隔）；按节点标签或 id 匹配画橙色边。 */
    public void setOverlayLayer(List<String> fromToLines) {
        overlayEdges.clear();
        for (String l : fromToLines) {
            String[] t = l.trim().split("[\\s,;]+");
            if (t.length >= 2) overlayEdges.add(new String[]{t[0], t[1]});
        }
        repaint();
    }

    /** 主路径叠加：from,to 键集合，绿色高亮边。 */
    public void setOverlayKeys(Set<String> keys) {
        overlayKeys.clear();
        if (keys != null) overlayKeys.addAll(keys);
        repaint();
    }

    public int getOverlayEdgeCount() { return overlayEdges.size(); }
    private int firstYearOf(GraphNode n) { return n.firstYear == Integer.MAX_VALUE ? 0 : n.firstYear; }

    /** 节点最近活动年（链接着色用：原版链接色偏暖=近期共现，视觉对照确认）。 */
    private int lastYearOf(GraphNode n) {
        int last = 0;
        for (int y : n.countsByYear.keySet()) if (y > last) last = y;
        return last == 0 ? firstYearOf(n) : last;
    }

    /** 当前 cutoff 下会绘制的节点（供测试与动画断言）。 */
    public List<GraphNode> visibleNodes() {
        List<GraphNode> out = new ArrayList<>();
        if (network == null) return out;
        for (GraphNode n : network.nodeCollection())
            if (!hiddenNodes.contains(n) && firstYearOf(n) <= yearCutoff) out.add(n);
        return out;
    }

    public void setYearCutoff(int year) { this.yearCutoff = year; repaint(); }
    public int getYearCutoff() { return yearCutoff; }

    // ---------- public API ----------

    public void show(Network n, Mode m, String title) {
        this.network = n;
        this.mode = m;
        this.title = title;
        computeLayout();
        fitView();
        repaint();
    }

    public void setClusterLabels(Map<Integer, List<LLRLabeler.Scored>> labels) {
        this.clusterLabels = labels == null ? Collections.emptyMap() : labels;
        if (mode == Mode.TIMELINE && network != null) computeLayout();
        repaint();
    }

    public void setLabelBudget(int n) { this.labelBudget = n; repaint(); }
    public void setLabelFontSize(float size) { labelFontSize = Math.max(6f, Math.min(24f, size)); repaint(); }
    public float getLabelFontSize() { return labelFontSize; }
    public void setSizeMode(SizeMode m) { this.sizeMode = m; if (network != null) { computeRadius(); repaint(); } }

    /** Center the view on a node and mark it as focused (search-and-locate). Returns false if absent. */
    public boolean focus(String labelOrId) {
        if (network == null) return false;
        GraphNode hit = null;
        String q = labelOrId.trim().toLowerCase();
        for (GraphNode n : network.nodeCollection()) {
            if (n.label.toLowerCase().equals(q) || n.id.toLowerCase().equals(q)) { hit = n; break; }
            if (hit == null && (n.label.toLowerCase().contains(q) || n.id.toLowerCase().contains(q))) hit = n;
        }
        if (hit == null) return false;
        focusNode = hit;
        Point2D.Double p = pos.get(hit);
        if (p != null) {
            zoom = Math.max(zoom, 2.0);
            offX = getWidth() / 2.0 - p.x * zoom;
            offY = getHeight() / 2.0 - p.y * zoom;
        }
        repaint();
        return true;
    }

    public Map<Integer, double[]> bandsForTest() { return Collections.unmodifiableMap(bands); }

    /** World-space position of a node after layout (for tests). */
    public Point2D.Double positionOf(GraphNode n) { return pos.get(n); }

    // ---------- layout ----------

    private void fitView() {
        zoom = Math.min(getWidth() / WORLD_W, getHeight() / WORLD_H);
        if (zoom <= 0 || Double.isNaN(zoom)) zoom = 1;
        offX = (getWidth() - WORLD_W * zoom) / 2.0;
        offY = (getHeight() - WORLD_H * zoom) / 2.0;
    }

    private double sizeValue(GraphNode n) {
        return switch (sizeMode) {
            case SIGMA -> Math.max(n.sigma, 1);
            case CENTRALITY -> n.centrality * 10 + 0.2;
            case DEGREE -> n.degreeCentrality * 5 + 0.2;
            case EIGEN -> n.eigenCentrality * 9 + 0.5;
            case PAGERANK -> Math.max(n.pageRank * 1000, 0.5);
            // 熵族（原版 ph.java 1271-1286：Entropy / Relative Entropy / Information Bias）
            case ENTROPY -> entropy(n) + 0.05;
            case RELATIVE_ENTROPY -> {
                int m = n.countsByYear.size();
                yield m <= 1 ? 0.05 : entropy(n) / Math.log(m) + 0.05;
            }
            case INFO_BIAS -> {
                int m = n.countsByYear.size();
                double h = entropy(n), hu = m <= 1 ? 0 : Math.log(m);
                yield Math.abs(hu - h) + 0.05;   // 偏离均匀分布的程度
            }
            case UNIFORM -> 1.0;   // 统一尺寸（原版 "Uniform Size"）
            default -> n.weight;
        };
    }

    /** 年度计数分布的香农熵 H = −Σ p·ln p。 */
    private static double entropy(GraphNode n) {
        double total = 0;
        for (int v : n.countsByYear.values()) total += v;
        if (total <= 0) return 0;
        double h = 0;
        for (int v : n.countsByYear.values()) {
            double p = v / total;
            if (p > 0) h -= p * Math.log(p);
        }
        return h;
    }

    private void computeRadius() {
        radius.clear();
        if (network == null) return;
        double mx = 1;
        for (GraphNode n : network.nodeCollection()) mx = Math.max(mx, sizeValue(n));
        for (GraphNode n : network.nodeCollection())
                        radius.put(n, originalStyle
                    ? Math.max(0.4, Math.ceil(Math.sqrt(n.weight + 1)) * 0.16)   // 原版 ph.c：ceil(sqrt(base×(freq+1))) kh 3398
                    : (3 + 6) * Math.sqrt(sizeValue(n) / mx));
    }

    void computeLayout() {
        pos.clear(); bands.clear();
        computeRadius();
        if (network == null || network.nodes.isEmpty()) return;
        switch (mode) {
            case MERGED -> forceLayout();
            case TIMEZONE -> columnLayout(true);
            case TIMELINE -> bandLayout();
            case TSNE -> tsneLayout();
            case UMAP -> umapLayout();
            case CIRCLE_PACK -> circlePackLayout();
            case CIRCULAR -> circularLayout();
            case HEATMAP, LANDSCAPE -> { /* 直接按数据绘制，无需节点坐标 */ }
        }
    }

    /** CiteSpace Timeline: x = first-active year, y = inside the node's cluster band. */
    private void bandLayout() {
        Map<Integer, List<GraphNode>> byCluster = new TreeMap<>();
        int unclassed = -1;
        for (GraphNode n : network.nodeCollection())
            byCluster.computeIfAbsent(n.cluster >= 0 ? n.cluster : (unclassed = -2), k -> new ArrayList<>()).add(n);
        if (byCluster.containsKey(-2)) byCluster.put(byCluster.size() + 100, byCluster.remove(-2)); // 未归类放最下

        int k = byCluster.size();
        double padY = 46, bandGap = 8;
        double bandH = (WORLD_H - 2 * padY - (k - 1) * bandGap) / k;
        int y0 = network.minYear == Integer.MAX_VALUE ? 0 : network.minYear;
        int y1 = network.maxYear == Integer.MIN_VALUE ? y0 : network.maxYear;
        double padX = 90;
        double dx = (WORLD_W - 2.0 * padX) / Math.max(y1 - y0, 1);

        int band = 0;
        for (Map.Entry<Integer, List<GraphNode>> en : byCluster.entrySet()) {
            double top = padY + band * (bandH + bandGap);
            bands.put(en.getKey(), new double[]{top, top + bandH});
            List<GraphNode> ns = en.getValue();
            ns.sort(Comparator.comparingInt(n -> n.firstYear == Integer.MAX_VALUE ? 9999 : n.firstYear));
            double mid = top + bandH / 2;
            double innerH = Math.max(bandH * 0.66, 12);
            double step = Math.min(26, innerH / Math.max(1, (ns.size() + 1) / 2));
            for (int i = 0; i < ns.size(); i++) {
                GraphNode n = ns.get(i);
                int fy = n.firstYear == Integer.MAX_VALUE ? y0 : n.firstYear;
                double x = padX + Math.min(Math.max(fy - y0, 0), Math.max(y1 - y0, 1)) * dx;
                double yy = mid + ((i % 2 == 0) ? -1 : 1) * ((i + 1) / 2) * step;
                yy = Math.max(top + 10, Math.min(top + bandH - 10, yy));
                pos.put(n, new Point2D.Double(x, yy));
            }
            band++;
        }
    }

    /** Circular 视图（原版 z=7）：节点按聚类分段分布在圆周上。 */
    private void circularLayout() {
        Map<Integer, List<GraphNode>> byCluster = new TreeMap<>();
        for (GraphNode n : network.nodeCollection())
            byCluster.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);
        double cx = WORLD_W / 2, cy = WORLD_H / 2, R = Math.min(WORLD_W, WORLD_H) * 0.40;
        List<GraphNode> flat = new ArrayList<>();
        for (List<GraphNode> c : byCluster.values()) {
            c.sort((a, b) -> Integer.compare(b.weight, a.weight));
            flat.addAll(c);
        }
        int i = 0;
        for (GraphNode n : flat) {
            double a = 2 * Math.PI * (i++) / Math.max(flat.size(), 1) - Math.PI / 2;
            pos.put(n, new Point2D.Double(cx + R * Math.cos(a), cy + R * Math.sin(a)));
        }
    }

    /** 聚类×年份 活动量矩阵（Heatmap/Landscape 共用数据）。 */
    private Map<Integer, Map<Integer, Integer>> clusterYearActivity() {
        Map<Integer, Map<Integer, Integer>> act = new TreeMap<>();
        if (network == null) return act;
        for (GraphNode n : network.nodeCollection())
            for (var e : n.countsByYear.entrySet())
                act.computeIfAbsent(n.cluster, k -> new TreeMap<>()).merge(e.getKey(), e.getValue(), Integer::sum);
        return act;
    }

    /** Heatmap 视图（原版 z=8）：聚类×年份活动量色块矩阵。 */
    private void paintHeatmap(Graphics2D g) {
        Map<Integer, Map<Integer, Integer>> act = clusterYearActivity();
        if (act.isEmpty() || network == null || network.minYear == Integer.MAX_VALUE) return;
        int y0 = network.minYear, y1 = network.maxYear, years = y1 - y0 + 1;
        List<Integer> clusters = new ArrayList<>(act.keySet());
        double left = 120, top = 70, cw = (WORLD_W - left - 60) / years, ch = Math.min(26, (WORLD_H - top - 80) / Math.max(clusters.size(), 1));
        int max = 1;
        for (var c : act.values()) for (int v : c.values()) max = Math.max(max, v);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
        g.setColor(new Color(30, 30, 30));
        g.drawString(ycscite.I18n.text(Strings.get("view.heatmap")), 30, 40);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(new Color(110, 110, 110));
        for (int y = y0; y <= y1; y++) {
            int x = (int) (left + (y - y0) * cw);
            if ((y - y0) % Math.max(1, years / 12) == 0) g.drawString(ycscite.I18n.text(String.valueOf(y)), x - 12, (int) (top - 8));
            g.drawLine(x, (int) (top - 4), x, (int) (top + clusters.size() * ch + 4));
        }
        int r = 0;
        for (int c : clusters) {
            double yTop = top + r * ch;
            g.setColor(new Color(110, 110, 110));
            g.drawString(ycscite.I18n.text("#" + c), 70, (int) (yTop + ch * 0.75));
            for (int y = y0; y <= y1; y++) {
                int v = act.get(c).getOrDefault(y, 0);
                float t = (float) Math.pow(v / (double) max, 0.5);
                Color col = Color.getHSBColor(0.62f * (1 - t), 0.75f, 0.25f + 0.7f * t);
                g.setColor(v > 0 ? col : new Color(244, 244, 244));
                g.fill(new java.awt.geom.Rectangle2D.Double(left + (y - y0) * cw + 1, yTop + 1, Math.max(cw - 2, 2), ch - 2));
            }
            r++;
        }
    }

    /** Landscape 视图（原版 z=6）：年份×聚类活动量的透视曲面（等距网格 + 高度着色）。 */
    private void paintLandscape(Graphics2D g) {
        Map<Integer, Map<Integer, Integer>> act = clusterYearActivity();
        if (act.isEmpty() || network == null || network.minYear == Integer.MAX_VALUE) return;
        int y0 = network.minYear, y1 = network.maxYear, years = y1 - y0 + 1;
        List<Integer> clusters = new ArrayList<>(act.keySet());
        int k = clusters.size();
        int max = 1;
        for (var c : act.values()) for (int v : c.values()) max = Math.max(max, v);
        double ox = 140, oy = WORLD_H - 120, ax = 12.0, ay = 8.0;    // 透视基向量
        double sx = (WORLD_W - ox - 320) / Math.max(years - 1, 1);   // x 方向步长
        double sy = (WORLD_H - 200) / Math.max(k - 1, 1);            // 深度方向步长
        g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
        g.setColor(new Color(30, 30, 30));
        g.drawString(ycscite.I18n.text(Strings.get("view.landscape")), 30, 40);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        // 深度轴：聚类号
        for (int i = 0; i < k; i++) {
            int c = clusters.get(i);
            double px = ox - ay * i, py = oy - sy * i - ay * i * 0;   // 左侧标签
            g.setColor(new Color(110, 110, 110));
            g.drawString(ycscite.I18n.text("#" + c), (int) (ox - 60 - ay * i), (int) (oy - sy * i + 4));
        }
        for (int t = 0; t < years; t++) {
            for (int i = 0; i < k; i++) {
                int c = clusters.get(i);
                int v = act.get(c).getOrDefault(y0 + t, 0);
                int v2 = t + 1 < years ? act.get(c).getOrDefault(y0 + t + 1, 0) : v;
                int cNext = i + 1 < k ? clusters.get(i + 1) : c;
                int vD = act.get(cNext).getOrDefault(y0 + t, 0);
                float h1 = (float) Math.pow(v / (double) max, 0.5), h2 = (float) Math.pow(v2 / (double) max, 0.5);
                float hD = (float) Math.pow(vD / (double) max, 0.5);
                double x1 = ox + t * sx + i * ay, y1p = oy - i * sy - v * 3.2;
                double x2 = ox + (t + 1) * sx + i * ay, y2p = oy - i * sy - v2 * 3.2;
                double xD = ox + t * sx + (i + 1) * ay, yDp = oy - (i + 1) * sy - vD * 3.2;
                g.setColor(Color.getHSBColor(0.62f * (1 - h1), 0.7f, 0.85f));
                g.fill(new java.awt.Polygon(
                        new int[]{(int) x1, (int) x2, (int) xD, (int) x1},
                        new int[]{(int) y1p, (int) y2p, (int) yDp, (int) y1p}, 4));
                g.setColor(Color.getHSBColor(0.62f * (1 - Math.max(h1, h2)), 0.7f, 0.55f));
                g.draw(new java.awt.geom.Line2D.Double(x1, y1p, x2, y2p));
                if ((y0 + t - y0) % Math.max(1, years / 10) == 0 && i == 0) {
                    g.setColor(new Color(110, 110, 110));
                    g.drawString(ycscite.I18n.text(String.valueOf(y0 + t)), (int) x1 - 10, (int) (oy + 20));
                }
            }
        }
    }

    /** Timezone/Merged column layout: columns = years, nodes stacked in column. */
    /**
     * 时区视图（原版 kh.java K() z=4 语义）：时间×聚类网格 ——
     * x = 首现年列；y = 全局聚类带（同一聚类的带在所有年份列中位置一致，带内堆叠）。
     * 此前实现为"按权重堆叠"（审计 A3 判不同构），已按原版网格语义重写。
     */
    private void columnLayout(boolean byFirstYear) {
        Map<Integer, List<GraphNode>> byCluster = new TreeMap<>();
        for (GraphNode n : network.nodeCollection())
            byCluster.computeIfAbsent(n.cluster > 0 ? n.cluster : 0, k -> new ArrayList<>()).add(n);
        int k = byCluster.size();
        if (k == 0) return;
        double pad = 80, bandGap = 8;
        double bandH = (WORLD_H - 2 * pad - (k - 1) * bandGap) / k;
        List<Integer> bandOrder = new ArrayList<>(byCluster.keySet());

        Map<Integer, List<GraphNode>> cols = new TreeMap<>();
        for (GraphNode n : network.nodeCollection()) {
            int y = byFirstYear
                    ? (n.firstYear == Integer.MAX_VALUE ? 0 : n.firstYear)
                    : Math.max(n.yearOfMax(), n.firstYear == Integer.MAX_VALUE ? 0 : n.firstYear);
            cols.computeIfAbsent(y, k2 -> new ArrayList<>()).add(n);
        }
        List<Integer> years = new ArrayList<>(cols.keySet());
        if (years.isEmpty()) return;
        double dx = (WORLD_W - 2.0 * pad) / Math.max(years.size() - 1, 1);

        // 每列内按聚类带放置：同一簇的节点在所有年份列中落在同一水平带
        for (int i = 0; i < years.size(); i++) {
            List<GraphNode> col = cols.get(years.get(i));
            Map<Integer, List<GraphNode>> colByCluster = new TreeMap<>();
            for (GraphNode n : col)
                colByCluster.computeIfAbsent(n.cluster > 0 ? n.cluster : 0, x -> new ArrayList<>()).add(n);
            for (Map.Entry<Integer, List<GraphNode>> en : colByCluster.entrySet()) {
                int band = bandOrder.indexOf(en.getKey());
                double top = pad + band * (bandH + bandGap);
                List<GraphNode> members = en.getValue();
                members.sort((a, b) -> Integer.compare(b.weight, a.weight));
                double step = Math.min(24, bandH / Math.max(1, members.size()));
                double mid = top + bandH / 2;
                for (int j = 0; j < members.size(); j++) {
                    double yy = mid + ((j % 2 == 0) ? -1 : 1) * ((j + 1) / 2) * step;
                    yy = Math.max(top + 8, Math.min(top + bandH - 8, yy));
                    pos.put(members.get(j), new Point2D.Double(pad + i * dx, yy));
                }
            }
        }
    }

    private void forceLayout() {
        // 布局算法选择（原版 ph:1039-1042：Kamada-Kawai / Force-Directed / LinLog）
        Map<GraphNode, Point2D.Double> lin = switch (layoutAlgo) {
            case KAMADA_KAWAI -> ycscite.analysis.KamadaKawai.layout(network, 42L, 200);
            case FORCE_DIRECTED -> ycscite.analysis.ForceDirectedLayout.layout(network, 42L, 1500);   // 充分散开（prefuse 语义默认时长）
            default -> ycscite.analysis.LinLogLayout.layout(network, 42L);
        };
        // 稳健包络（2%–98% 分位）：离群长臂不再把主体压缩成左侧小团（原版星系状铺满画幅）
        double[] xs = new double[lin.size()], ys = new double[lin.size()];
        int i = 0;
        for (Point2D.Double p : lin.values()) { xs[i] = p.x; ys[i] = p.y; i++; }
        java.util.Arrays.sort(xs); java.util.Arrays.sort(ys);
        double minX = q(xs, 0.02), maxX = q(xs, 0.98);
        double minY = q(ys, 0.02), maxY = q(ys, 0.98);
        double pad = 55;   // 原版铺满画幅（视觉审计：填充率 65%→75%）
        // FR（prefuse 语义）：纯等比适配 —— 不做聚类分离/轴向拉伸/钳制（后处理会压扁自然分布致"糊团"）
        if (layoutAlgo == LayoutAlgo.FORCE_DIRECTED) {
            double s = Math.min((WORLD_W - 2 * pad) / Math.max(maxX - minX, 1e-9),
                                (WORLD_H - 2 * pad) / Math.max(maxY - minY, 1e-9));
            double w = (maxX - minX) * s, h = (maxY - minY) * s;
            double ox = (WORLD_W - w) / 2, oy = (WORLD_H - h) / 2;
            for (Map.Entry<GraphNode, Point2D.Double> en : lin.entrySet()) {
                pos.put(en.getKey(), new Point2D.Double(ox + (en.getValue().x - minX) * s,
                                                        oy + (en.getValue().y - minY) * s));
            }
            return;
        }
        // x/y 独立缩放铺满世界矩形（关键词图受益，88% 视觉判定）；
        // 多分量合作网保持等比（视觉对照：原版为自然有机团块，拉伸会变扁/对角化）
        boolean multiComp = componentCount() >= 8;
        double sx = (WORLD_W - 2 * pad) / Math.max(maxX - minX, 1e-9);
        double sy = (WORLD_H - 2 * pad) / Math.max(maxY - minY, 1e-9);
        if (multiComp) { double s = Math.min(sx, sy); sx = s; sy = s; }
        for (Map.Entry<GraphNode, Point2D.Double> en : lin.entrySet()) {
            // 离群点夹回画幅内（保留在边缘而不是丢失）
            double x = Math.max(pad, Math.min(WORLD_W - pad, pad + (en.getValue().x - minX) * sx));
            double y = Math.max(pad, Math.min(WORLD_H - pad, pad + (en.getValue().y - minY) * sy));
            pos.put(en.getKey(), new Point2D.Double(x, y));
        }
        separateClusters();
        // 多分量网络（作者/机构合作网）：视觉对照确认原版为有机 force-directed 团块
        // （非径向圆环），此处仅做等比适配 —— 见下方 forceLayout 末尾的分支
        // 分离可能把聚类推出画幅/挤向一侧 —— 最终重适配：x/y 独立缩放铺满 + 质心居中
        double nx0 = Double.MAX_VALUE, ny0 = Double.MAX_VALUE, nx1 = -Double.MAX_VALUE, ny1 = -Double.MAX_VALUE;
        for (Point2D.Double p : pos.values()) {
            nx0 = Math.min(nx0, p.x); nx1 = Math.max(nx1, p.x);
            ny0 = Math.min(ny0, p.y); ny1 = Math.max(ny1, p.y);
        }
        double sx2 = (WORLD_W - 2 * pad) / Math.max(nx1 - nx0, 1e-9);
        double sy2 = (WORLD_H - 2 * pad) / Math.max(ny1 - ny0, 1e-9);
        if (multiComp) { double s2 = Math.min(sx2, sy2); sx2 = s2; sy2 = s2; }
        if (sx2 < 1 || sy2 < 1) {
            double fx = Math.min(1, sx2), fy = Math.min(1, sy2);
            for (Point2D.Double p : pos.values()) {
                p.x = (p.x - nx0) * fx;
                p.y = (p.y - ny0) * fy;
            }
        }
        // 无论是否缩放都把质量质心平移到画幅中心（消除左/右半空白）
        double cx = 0, cy = 0;
        for (Point2D.Double p : pos.values()) { cx += p.x; cy += p.y; }
        cx /= Math.max(1, pos.size()); cy /= Math.max(1, pos.size());
        double tx = WORLD_W / 2 - cx, ty = WORLD_H / 2 - cy;
        for (Point2D.Double p : pos.values()) {
            p.x = Math.max(pad, Math.min(WORLD_W - pad, p.x + tx));
            p.y = Math.max(pad, Math.min(WORLD_H - pad, p.y + ty));
        }
    }

    /** 分位数（0..1），输入须已排序。 */
    private static double q(double[] sorted, double t) {
        if (sorted.length == 0) return 0;
        double idx = t * (sorted.length - 1);
        int lo = (int) Math.floor(idx), hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (idx - lo) * (sorted[hi] - sorted[lo]);
    }

    /** 连通分量数（多分量网络 = 合作网等，用于布局适配分支）。 */
    private int componentCount() {
        if (network == null || pos.isEmpty()) return 1;
        java.util.Set<GraphNode> seen = new java.util.HashSet<>();
        int comps = 0;
        for (GraphNode root : pos.keySet()) {
            if (seen.contains(root)) continue;
            comps++;
            java.util.Deque<GraphNode> dq = new java.util.ArrayDeque<>();
            dq.add(root); seen.add(root);
            while (!dq.isEmpty()) {
                GraphNode cur = dq.poll();
                for (GraphNode nb : network.neighbors(cur))
                    if (pos.containsKey(nb) && seen.add(nb)) dq.add(nb);
            }
        }
        return comps;
    }

    /**
     * 聚类分离（原版 pg.java "Separate Clusters" 逐式移植）：
     * 逐聚类检查与已放置聚类的凸包碰撞，碰撞则沿两聚类锚点连线方向以 10px 步进推开。
     */
    private void separateClusters() {
        Map<Integer, List<GraphNode>> byCluster = new TreeMap<>();
        for (GraphNode n : network.nodeCollection())
            byCluster.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);
        if (byCluster.size() < 2) return;
        java.util.List<java.awt.geom.Area> areas = new ArrayList<>();
        java.util.List<Point2D.Double> anchors = new ArrayList<>();
        for (List<GraphNode> cluster : byCluster.values()) {
            List<Point2D.Double> pts = new ArrayList<>();
            double rMax = 10;
            for (GraphNode n : cluster) {
                Point2D.Double p = pos.get(n);
                if (p == null) continue;
                pts.add(p);
                rMax = Math.max(rMax, radius.getOrDefault(n, 10.0));
            }
            if (pts.isEmpty()) { areas.add(null); anchors.add(new Point2D.Double(0, 0)); continue; }
            Point2D.Double anchor = pts.get(0);
            List<Point2D.Double> hull = convexHull(pts);
            java.awt.geom.Area area;
            if (hull.size() >= 3) {
                hull = expandHull(hull, 18);
                java.awt.Polygon poly = new java.awt.Polygon();
                for (Point2D.Double h : hull) poly.addPoint((int) h.x, (int) h.y);
                area = new java.awt.geom.Area(poly);
            } else {
                double r = rMax;
                area = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(
                        pts.get(0).x - r, pts.get(0).y - r, 2 * r, 2 * r));
            }
            areas.add(area);
            anchors.add(anchor);
        }
        for (int i = 1; i < areas.size(); i++) {
            java.awt.geom.Area area = areas.get(i);
            if (area == null) continue;
            for (int j = 0; j < i; j++) {
                java.awt.geom.Area other = areas.get(j);
                if (other == null || !area.getBounds2D().intersects(other.getBounds2D())) continue;
                Point2D.Double ai = anchors.get(i), aj = anchors.get(j);
                double dx = ai.x - aj.x, dy = ai.y - aj.y;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d == 0) { dx = 1; dy = 0; d = 1; }
                double ux = dx / d, uy = dy / d;
                int guard = 0;
                while (intersects(area, other) && guard++ < 500) {
                    // 整个聚类移动 10px（源码步长）
                    for (GraphNode n : clusterOf(byCluster, i)) {
                        Point2D.Double p = pos.get(n);
                        if (p != null) { p.x += 10 * ux; p.y += 10 * uy; }
                    }
                    area.transform(java.awt.geom.AffineTransform.getTranslateInstance(10 * ux, 10 * uy));
                    anchors.get(i).x += 10 * ux;
                    anchors.get(i).y += 10 * uy;
                }
            }
        }
    }

    private List<GraphNode> clusterOf(Map<Integer, List<GraphNode>> byCluster, int idx) {
        int i = 0;
        for (List<GraphNode> c : byCluster.values()) if (i++ == idx) return c;
        return java.util.Collections.emptyList();
    }

    private static boolean intersects(java.awt.geom.Area a, java.awt.geom.Area b) {
        if (!a.getBounds2D().intersects(b.getBounds2D())) return false;
        java.awt.geom.Area t = new java.awt.geom.Area(a);
        t.intersect(b);
        return !t.isEmpty();
    }

    // ---------- interaction ----------

    private Point2D.Double toWorld(double mx, double my) { return new Point2D.Double((mx - offX) / zoom, (my - offY) / zoom); }

    private GraphNode hitNode(double mx, double my) {
        if (network == null) return null;
        Point2D.Double w = toWorld(mx, my);
        GraphNode hit = null;
        double bestD2 = Double.MAX_VALUE;
        for (GraphNode n : network.nodeCollection()) {
            Point2D.Double p = pos.get(n);
            if (p == null) continue;
            double d2 = (p.x - w.x) * (p.x - w.x) + (p.y - w.y) * (p.y - w.y);
            double r = radius.get(n) + 6;
            if (d2 < r * r && d2 < bestD2) { bestD2 = d2; hit = n; }
        }
        return hit;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        GraphNode hit = hitNode(e.getX(), e.getY());
        if (hit == null) return null;
        StringBuilder sb = new StringBuilder("<html><b>").append(hit.label).append("</b><br/>")
            .append(hit.weight).append(" 次  |  聚类 #").append(hit.cluster).append("<br/>")
            .append(String.format("中介中心性 %.3f   σ %.2f", hit.centrality, hit.sigma));
        if (hit.halfLife > 0) sb.append("<br/>半衰期: ").append(hit.halfLife).append(" 年");
        for (int[] b : hit.burstIntervals)
            sb.append("<br/>突现 ").append(b[0]).append('-').append(b[1]).append("（级别 ").append(b[2]).append("）");
        return sb.append("</html>").toString();
    }

    // ---------- painting ----------

    /** Blue (early) to red (late) year color, used by the year rings and the legend scale. */
    private Color yearColor(int year) {
        int y0 = network.minYear == Integer.MAX_VALUE ? year : network.minYear;
        int y1 = network.maxYear == Integer.MIN_VALUE ? year : network.maxYear;
        float t = y1 == y0 ? 0f : (float) (year - y0) / (y1 - y0);
        return Color.getHSBColor(0.67f * (1 - t) + 0.02f, 0.75f, 0.95f);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(originalStyle ? Color.WHITE : themeBg());
        if (!transparentBg) g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(originalStyle ? new Color(30, 30, 30) : themeFg());
        g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
        // 原版风格：左上参数信息面板（白底半透明小框，视觉分析确认的原版信息框）
        // 面板字号随画幅缩放（8000px 导出时 13px 太小不可读）
        float panelScale = Math.min(1.6f, Math.max(1f, getWidth() / 1400f));
        if (originalStyle && !paramPanel.isEmpty()) {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f * panelScale));
            var fm2 = g.getFontMetrics();
            int pw = 0;
            for (String s : paramPanel) pw = Math.max(pw, fm2.stringWidth(s));
            int lh = (int) (18 * panelScale);
            int ph = paramPanel.size() * lh + (int) (12 * panelScale);
            g.setColor(new Color(255, 255, 255, 235));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(12, 10, pw + 20 * panelScale, ph, 8, 8));
            g.setColor(new Color(120, 120, 120));
            g.draw(new java.awt.geom.RoundRectangle2D.Double(12, 10, pw + 20 * panelScale, ph, 8, 8));
            g.setColor(new Color(40, 40, 40));
            int yy = (int) (28 * panelScale);
            for (String s : paramPanel) { g.drawString(ycscite.I18n.text(s), 22, yy); yy += lh; }
        }
        String head = (title.isEmpty() ? Strings.get("canvas.empty") : title)
                + (network == null ? "" : "  —  " + network.nodes.size() + Strings.get("canvas.nodes")
                + network.edges.size() + Strings.get("canvas.links")
                + "  [" + Strings.get("mode." + mode.name().toLowerCase()) + Strings.get("canvas.hint") + "]");
        if (!originalStyle || paramPanel.isEmpty())
            g.drawString(ycscite.I18n.text(head), 14, 20);
        else
            g.drawString(ycscite.I18n.text(head), 14, (int) (20 + paramPanel.size() * 18 * panelScale + 18 * panelScale));
        if (mode == Mode.HEATMAP && network != null) { paintHeatmap(g); return; }
        if (mode == Mode.LANDSCAPE && network != null) { paintLandscape(g); return; }
        if (network == null || pos.isEmpty()) {
            // 空状态引导：居中三步提示
            g.setColor(theme==Theme.DARK?new Color(190,190,190):new Color(105,99,88));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 16f));
            var fm = g.getFontMetrics();
            String[] guide = {Strings.get("empty.1"), Strings.get("empty.2"), Strings.get("empty.3")};
            int cy = getHeight() / 2 - 40;
            for (String line : guide) {
                g.drawString(ycscite.I18n.text(line), (getWidth() - fm.stringWidth(line)) / 2, cy);
                cy += 34;
            }
            return;
        }

        AffineTransform saved = g.getTransform();
        g.translate(offX, offY);
        g.scale(zoom, zoom);

        // 聚类凸包轮廓（Graham scan，按簇填充半透明多边形）
        if (convexHull && mode != Mode.TIMELINE) {
            Map<Integer, List<Point2D.Double>> byCluster = new HashMap<>();
            for (GraphNode n : network.nodeCollection()) {
                Point2D.Double p0 = pos.get(n);
                if (p0 != null) byCluster.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(p0);
            }
            // 原版风格：只画规模前 15 的大簇（作者网等有上百微簇，全画会铺满 #N 编号）
            List<Map.Entry<Integer, List<Point2D.Double>>> hullOrder = new ArrayList<>(byCluster.entrySet());
            if (originalStyle) {
                hullOrder.sort((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()));
                hullOrder = hullOrder.subList(0, Math.min(15, hullOrder.size()));
            }
            for (Map.Entry<Integer, List<Point2D.Double>> en : hullOrder) {
                if (en.getValue().size() < 3) continue;
                List<Point2D.Double> hull = convexHull(en.getValue());
                if (hull.size() < 3) continue;
                hull = expandHull(hull, 18);
                Color c = PALETTE[Math.floorMod(en.getKey(), PALETTE.length)];
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 6));
                var poly = new java.awt.Polygon();
                for (Point2D.Double h : hull) poly.addPoint((int) h.x, (int) h.y);
                g.fillPolygon(poly);
                // Areas Fill Patterns（原版 ph:1605-1621：交叉线/点阵/水平线叠加在纯色上）
                if (hullFillPattern > 0) {
                    var bounds = poly.getBounds();
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
                    g.setStroke(new BasicStroke(0.8f));
                    g.setClip(poly);
                    switch (hullFillPattern) {
                        case 1 -> {   // 交叉线
                            for (int px = bounds.x; px < bounds.x + bounds.width; px += 8) {
                                g.drawLine(px, bounds.y, px - bounds.height, bounds.y + bounds.height);
                                g.drawLine(px, bounds.y, px + bounds.height, bounds.y + bounds.height);
                            }
                        }
                        case 2 -> {   // 点阵
                            for (int px = bounds.x + 4; px < bounds.x + bounds.width; px += 10)
                                for (int py = bounds.y + 4; py < bounds.y + bounds.height; py += 10)
                                    g.fillOval(px, py, 2, 2);
                        }
                        case 3 -> {   // 水平线
                            for (int py = bounds.y; py < bounds.y + bounds.height; py += 8)
                                g.drawLine(bounds.x, py, bounds.x + bounds.width, py);
                        }
                    }
                    g.setClip(null);
                }
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
                g.setStroke(new BasicStroke(1.6f));
                g.drawPolygon(poly);
                // 聚类编号（原版在凸包上方标 #1 #2 …，按规模降序）
                Point2D.Double top = hull.get(0);
                for (Point2D.Double h : hull) if (h.y < top.y) top = h;
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
                g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
                g.drawString(ycscite.I18n.text("#" + en.getKey()), (float) top.x - 6, (float) top.y - 5);
            }
        }

        // cluster bands (Timeline only): translucent band + label strip
        if (mode == Mode.TIMELINE) {
            for (Map.Entry<Integer, double[]> en : bands.entrySet()) {
                Color c = PALETTE[Math.floorMod(en.getKey(), PALETTE.length)];
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 26));
                double top = en.getValue()[0], h = en.getValue()[1] - en.getValue()[0];
                g.fill(new java.awt.geom.RoundRectangle2D.Double(60, top, WORLD_W - 100, h, 14, 14));
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 220));
                g.setFont(g.getFont().deriveFont(Font.BOLD, 11f));
                StringBuilder lb = new StringBuilder("#").append(en.getKey());
                List<LLRLabeler.Scored> ls = clusterLabels.get(en.getKey());
                if (ls != null)
                    for (int i = 0; i < Math.min(BAND_LABEL_MAX, ls.size()); i++)
                        lb.append("  ").append(ls.get(i).term);
                g.drawString(ycscite.I18n.text(lb.toString()), 68, (float) (top + 14));
            }
        }

        // links（原版 7.0.1 SVG 导出/主绘制逐式：QuadCurve + aq.a 控制点，
        // 同簇→簇色、异簇→白（白底隐形），透明度 0.25×linkAlpha，双层 黑阴影+彩）
        Color linkBase = originalStyle ? new Color(150, 170, 190) : new Color(160, 160, 160);
        int alpha = (int) (255 * linkTransparency * (originalStyle ? 0.25 : 0.7));
        int nClusters = (int) network.nodeCollection().stream().mapToInt(n -> n.cluster).filter(c -> c >= 0).distinct().count();
        for (GraphEdge e : network.edges) {
            Point2D.Double a0 = pos.get(e.a), b0 = pos.get(e.b);
            if (a0 == null || b0 == null) continue;
            if (hiddenNodes.contains(e.a) || hiddenNodes.contains(e.b)) continue;
            if (firstYearOf(e.a) > yearCutoff || firstYearOf(e.b) > yearCutoff) continue;
            Point2D.Double a = fisheyeTransform(a0), b = fisheyeTransform(b0);
            if (linkSingleColor) {
                g.setColor(new Color(100, 100, 100, Math.min(255, alpha + 30)));   // 单色模式（原版 ph:1495-1501）
            } else if (originalStyle) {
                // 原版主视图（视觉对照+截图核实）：链接按年份切片着色（红=近期），跨簇自由连接；
                // "同簇色/异簇白"是 SVG 导出路径（kh 5252），主绘制不同
                int ly = Math.max(lastYearOf(e.a), lastYearOf(e.b));
                // 原版截图：链接=高饱和年份色（红主导）；yearColor 转 HSV 后饱和度拉满
                int y0 = network.minYear == Integer.MAX_VALUE ? ly : network.minYear;
                int y1 = network.maxYear == Integer.MIN_VALUE ? ly : network.maxYear;
                float t = y1 == y0 ? 0f : (float) (ly - y0) / (y1 - y0);
                Color c = Color.getHSBColor(0.67f * (1 - t) + 0.02f, 0.6f, 1.0f);   // 低饱和（AA 后趋白）
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                        Math.max(1, (int) (255 * 0.055 * Math.max(0.4, linkTransparency)))));   // 原版 1.4% ink 实测：边近隐形
            } else if (linkColorByCluster && e.a.cluster == e.b.cluster) {
                Color c = PALETTE[Math.floorMod(e.a.cluster, PALETTE.length)];
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, alpha + 40)));
            } else {
                g.setColor(new Color(linkBase.getRed(), linkBase.getGreen(), linkBase.getBlue(), alpha));
            }
            float baseW = linkWeightedWidth ? (float) Math.min(1.2, 0.3 * e.weight) : 1.0f;
            if (originalStyle && zoom > 0.1f) baseW = Math.max(0.08f, baseW * 0.55f / (float) zoom);   // 原版像素坐标（world→screen 换算，细于1px）
            g.setStroke(new BasicStroke(baseW));
            if (originalStyle) {
                // 原版 aq.a 控制点：中点 + (d/4)(sin,cos)θ 同侧上拱
                Point2D.Double c = aqControl(a, b);
                g.draw(new QuadCurve2D.Double(a.x, a.y, c.x, c.y, b.x, b.y));
            } else if (curvedLines || mode == Mode.TIMELINE) {
                double cy = (a.y + b.y) / 2 - 0.22 * Math.abs(b.y - a.y) - 0.08 * Math.abs(b.x - a.x);
                g.draw(new QuadCurve2D.Double(a.x, a.y, (a.x + b.x) / 2, cy, b.x, b.y));
            } else {
                g.draw(new java.awt.geom.Line2D.Double(a.x, a.y, b.x, b.y));
            }
            // 链接权重标签（原版 ph:1493 Link Labels）
            if (linkLabels) {
                g.setColor(new Color(100, 100, 100, 160));
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 9f));
                g.drawString(ycscite.I18n.text(String.valueOf(e.weight)), (float) ((a.x + b.x) / 2 + 3), (float) ((a.y + b.y) / 2 - 3));
            }
        }

        // .layer 叠加边（橙色）与主路径叠加（绿色），按标签或 id 匹配节点对
        if ((!overlayEdges.isEmpty() || !overlayKeys.isEmpty()) && network != null) {
            Map<String, Point2D.Double> byLabel = new HashMap<>();
            for (GraphNode n : network.nodeCollection()) {
                Point2D.Double p0 = pos.get(n);
                if (p0 == null) continue;
                Point2D.Double p = fisheyeTransform(p0);
                byLabel.put(n.label.toUpperCase(), p);
                byLabel.putIfAbsent(n.id.toUpperCase(), p);
            }
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(new Color(230, 140, 20, 190));
            for (String[] e : overlayEdges) {
                Point2D.Double a = byLabel.get(e[0].toUpperCase()), b = byLabel.get(e[1].toUpperCase());
                if (a != null && b != null) g.draw(new java.awt.geom.Line2D.Double(a.x, a.y, b.x, b.y));
            }
            g.setColor(new Color(30, 160, 60, 220));
            for (String k : overlayKeys) {
                String[] t = k.split("\\u0001");
                if (t.length != 2) continue;
                Point2D.Double a = byLabel.get(t[0].toUpperCase()), b = byLabel.get(t[1].toUpperCase());
                if (a != null && b != null) g.draw(new java.awt.geom.Line2D.Double(a.x, a.y, b.x, b.y));
            }
        }

        // nodes: year rings first (citation history), then core fill and metric rings
        List<GraphNode> byWeight = new ArrayList<>(network.nodeCollection());
        java.util.List<java.awt.Rectangle> placedLabels = new ArrayList<>();
        byWeight.sort((x, y) -> Integer.compare(y.weight, x.weight));
        int budget = labelBudget;
        Font labelFont = g.getFont().deriveFont(Font.PLAIN, (float)(labelFontSize/Math.min(1,Math.max(.01,zoom))));
        for (GraphNode n : byWeight) {
            if (hiddenNodes.contains(n) || firstYearOf(n) > yearCutoff) continue;
            Point2D.Double p0 = pos.get(n);
            Point2D.Double p = fisheyeTransform(p0);
            double r = radius.get(n) * nodeScale;   // Rescale（原版 ph:1257）
            boolean inHighlight = !highlightIds.isEmpty()
                    && (highlightIds.contains(n.label.toUpperCase()) || highlightIds.contains(n.id.toUpperCase()));
            boolean dimmed = (expandedNode != null && n != expandedNode && !network.neighbors(expandedNode).contains(n))
                    || (highlightCluster != Integer.MIN_VALUE && n.cluster != highlightCluster)
                    || (!highlightIds.isEmpty() && !inHighlight);
            Composite oldComp = null;
            if (dimmed) {
                oldComp = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
            }
            List<Integer> years = new ArrayList<>(n.countsByYear.keySet());
            Collections.sort(years);
            double coreR = years.size() > 1 ? r * 0.32 : r;
            if (originalStyle) {
                // 原版 7.0.1 kh 3668-3695 逐式：年份环=弧段堆叠（Arc2D），
                // 每年厚度 t_i = base×count_i（ph.c 时 /sqrt(total)），从外向内填色，色=年色表
                int[] cnt = new int[years.size()];
                for (int i = 0; i < years.size(); i++) cnt[i] = n.countsByYear.getOrDefault(years.get(i), 0);
                double denom = Math.sqrt(Math.max(1, n.weight));
                float[] thick = new float[years.size()];
                float tot = 0;
                for (int i = 0; i < years.size(); i++) { thick[i] = (float) (cnt[i] / denom); tot += thick[i]; }
                if (tot > 0) {
                    double outer = Math.min(r, tot);
                    // 中心性环厚（原版 n13 = ceil(g×5)，g=centrality）画最外
                    double cRing = Math.ceil(n.centrality * 5.0);
                    if (cRing > 0) {
                        g.setColor(new Color(148, 0, 211));
                        g.fill(new java.awt.geom.Arc2D.Double(p.x - outer - cRing, p.y - outer - cRing,
                                2 * (outer + cRing), 2 * (outer + cRing), 0, 360, java.awt.geom.Arc2D.CHORD));
                        // 挖内圆露中心（画节点色填充）
                    }
                    double cur = outer;
                    for (int i = years.size() - 1; i >= 0 && cur > 0; i--) {
                        double t = Math.min(cur, thick[i]);
                        g.setColor(yearColor(years.get(i)));
                        g.fill(new java.awt.geom.Arc2D.Double(p.x - cur, p.y - cur, 2 * cur, 2 * cur, 0, 360, java.awt.geom.Arc2D.CHORD));
                        cur -= t;
                    }
                }
            } else {
                // year rings（普通风格：同心整圆描边）
                double ringStep = years.size() > 1 ? (r - coreR) / years.size() : 0;
                for (int i = 0; i < years.size(); i++) {
                    if (years.get(i) > yearCutoff) break;
                    double rr = coreR + ringStep * (i + 1);
                    g.setColor(yearColor(years.get(i)));
                    g.setStroke(new BasicStroke((float) Math.max(0.5, ringStep * 0.5)));
                    g.draw(new Ellipse2D.Double(p.x - rr, p.y - rr, 2 * rr, 2 * rr));
                }
            }
            // 节点主色：原版=簇色表（mM.c），普通=内置调色板
            Color nodeColor = originalStyle
                    ? clusterColorOf(n.cluster, (int) network.nodeCollection().stream().mapToInt(x -> x.cluster).filter(c -> c >= 0).distinct().count())
                    : PALETTE[Math.floorMod(n.cluster < 0 ? 0 : n.cluster, PALETTE.length)];
            // 原版：弧段已填满圆盘，只画簇色描边（kh 3488-3500 逐式），不做实心填充
            if (originalStyle) {
                g.setColor(nodeColor);
                g.setStroke(new BasicStroke(1.0f));
                g.draw(new Ellipse2D.Double(p.x - r - 1, p.y - r - 1, 2 * r + 2, 2 * r + 2));
            } else {
            g.setColor(nodeColor);
            // 节点形状（原版 ph:1099-1108：Color/Diamond/Circle/Square）
            switch (nodeShape) {
                case DIAMOND -> {
                    var dp = new java.awt.Polygon();
                    dp.addPoint((int) p.x, (int) (p.y - coreR * 1.3));
                    dp.addPoint((int) (p.x + coreR * 1.3), (int) p.y);
                    dp.addPoint((int) p.x, (int) (p.y + coreR * 1.3));
                    dp.addPoint((int) (p.x - coreR * 1.3), (int) p.y);
                    if (effects3d) {
                        g.setColor(new Color(0, 0, 0, 50));
                        g.fillPolygon(dp.xpoints, dp.ypoints, dp.npoints);
                    }
                    g.setColor(nodeColor);
                    g.fillPolygon(dp);
                }
                case SQUARE -> {
                    if (effects3d) {
                        g.setColor(new Color(0, 0, 0, 50));
                        g.fill(new java.awt.geom.Rectangle2D.Double(p.x - coreR + 2, p.y - coreR + 2, 2 * coreR, 2 * coreR));
                    }
                    g.setColor(nodeColor);
                    g.fill(new java.awt.geom.Rectangle2D.Double(p.x - coreR, p.y - coreR, 2 * coreR, 2 * coreR));
                }
                default -> {
                    if (effects3d) {
                        g.setColor(new Color(0, 0, 0, 50));
                        g.fill(new Ellipse2D.Double(p.x - coreR + 2, p.y - coreR + 2, 2 * coreR, 2 * coreR));
                    }
                    g.setColor(nodeColor);
                    g.fill(new Ellipse2D.Double(p.x - coreR, p.y - coreR, 2 * coreR, 2 * coreR));
                }
            }
            }   // end !originalStyle 节点填充
            if (n.centrality >= 0.1) {
                g.setColor(new Color(148, 0, 211));
                // 原版：紫环紧贴节点（大节点下 +3 环显得空旷）
                float cs = originalStyle ? 1.8f : 2.5f;
                int cd = originalStyle ? 2 : 3;
                g.setStroke(new BasicStroke(cs));
                g.draw(new Ellipse2D.Double(p.x - r - cd, p.y - r - cd, 2 * r + 2 * cd, 2 * r + 2 * cd));
            }
            if (n.burstness > 0) {
                g.setColor(originalStyle ? new Color(255, 0, 255) : new Color(220, 20, 60));   // 原版 burst=品红 kh 3660
                // 原版：红环紧贴外缘（时区图审计：+6 大环会吞掉相邻标签）；
                // 环粗细编码突现强度（strength 0-5+ → 线宽 1.2-3.2，原版视觉语义）
                float bs = originalStyle
                        ? (float) (1.2 + 2.0 * Math.min(n.burstStrength, 5) / 5) : 2.5f;
                int bd = originalStyle ? 4 : 6;
                g.setStroke(new BasicStroke(bs));
                g.draw(new Ellipse2D.Double(p.x - r - bd, p.y - r - bd, 2 * r + 2 * bd, 2 * r + 2 * bd));
            }
            if (n == focusNode) {
                g.setColor(Color.YELLOW);
                g.setStroke(new BasicStroke(3f));
                g.draw(new Ellipse2D.Double(p.x - r - 10, p.y - r - 10, 2 * r + 20, 2 * r + 20));
            }
            if (n == selectedNode) {
                g.setColor(new Color(80, 180, 255));
                g.setStroke(new BasicStroke(3f));
                g.draw(new Ellipse2D.Double(p.x - r - 13, p.y - r - 13, 2 * r + 26, 2 * r + 26));
            }
            boolean wantLabel = originalStyle
                    ? (budget > 0 || n == focusNode || n == selectedNode) && !dimmed
                    : (budget > 0 || n.centrality >= 0.1 || n.burstness > 0
                       || n == focusNode || n == selectedNode) && !dimmed;
            // 原版 7.0.1 kh 4002 逐式：对数字号 n11 = base × 0.5 × (ln(w²+1)+1)；
            // 过小标签不显示且不耗预算；CNKI 数据用楷体 KaiTi（kh 4059）
            float scaledFs = 0f;
            if (wantLabel && originalStyle) {
                scaledFs = (float) Math.min(10.0, labelFontSize * 0.5 * (Math.log((double) n.weight * n.weight + 1) + 1.0));
                if (scaledFs < 8.5f && n != focusNode && n != selectedNode) wantLabel = false;
            }
            if (wantLabel) {
                budget--;
                Color labelFg;
                if (labelColorByCluster && n.cluster > 0) {
                    Color lc = PALETTE[Math.floorMod(n.cluster, PALETTE.length)];
                    labelFg = new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), 220);
                } else
                    labelFg = originalStyle ? new Color(20, 20, 20) : themeFg();
                // 四类标签底色（原版 ph:1362-1381 Label Background Color）
                Color labelBg = clusterLabelBg;   // 当前按节点类型选底色
                if (n.kind != null && n.kind.contains("cited")) labelBg = articleLabelBg != null ? articleLabelBg : clusterLabelBg;
                if (originalStyle) {
                    g.setFont(new Font("KaiTi", Font.BOLD, (int) Math.max(9f, scaledFs)));
                } else {
                    g.setFont(labelFont);
                }
                if (labelBg != null) {
                    var fm = g.getFontMetrics();
                    String lbl = showClusterId ? "#" + n.cluster + " " : "";
                    lbl += (n.userLabel != null ? n.userLabel : n.label);
                    if (showCitationCount) lbl += " (" + n.weight + ")";
                    int bw = fm.stringWidth(lbl) + 4;
                    g.setColor(labelBg);
                    g.fillRect((int) (p.x + r + 1), (int) (p.y - 9), bw, 14);
                }
                g.setColor(labelFg);
                // 原版 kh.a(int,String,Graphics2D,int,int) 逐式：标签防重叠=螺旋搜索
                // 初始锚点碰撞 → 半径每圈+2、角度步 5°(0.0873rad) 环绕寻找空位；找不到则放弃
                var fm = g.getFontMetrics();
                String drawLbl = n.userLabel != null ? n.userLabel : n.label;
                int lx0 = (int) (p.x + r + 3), ly0 = (int) (p.y - 9);
                java.awt.Rectangle rect = new java.awt.Rectangle(lx0, ly0, fm.stringWidth(drawLbl), fm.getHeight());
                boolean clash = false;
                for (var pr : placedLabels)
                    if (pr.intersects(new java.awt.Rectangle(rect.x - 2, rect.y - 2, rect.width + 4, rect.height + 4))) { clash = true; break; }
                double sx = lx0, sy = ly0;
                int spiralR = 1;
                boolean placed = !clash;
                while (clash && spiralR < Math.max(40, rect.width / 2)) {
                    boolean found = false;
                    for (double ang = -Math.PI; ang < Math.PI; ang += 0.08726646259971647) {
                        sx = lx0 + spiralR * Math.cos(ang);
                        sy = ly0 + spiralR * Math.sin(ang);
                        var cand = new java.awt.Rectangle((int) sx, (int) sy, rect.width, rect.height);
                        boolean hit = false;
                        for (var pr : placedLabels)
                            if (pr.intersects(new java.awt.Rectangle(cand.x - 2, cand.y - 2, cand.width + 4, cand.height + 4))) { hit = true; break; }
                        if (!hit) { found = true; break; }
                    }
                    if (found) { placed = true; break; }
                    spiralR += 2;
                }
                if (!placed && n != selectedNode && n != focusNode) budget++; // 螺旋找不到空位：不消耗预算
                else {
                    placedLabels.add(new java.awt.Rectangle((int) sx, (int) sy, rect.width, rect.height));
                    // 绘制位置 = 螺旋搜索到的 (sx, sy)（原版 kh.a 返回的 Rectangle2D 原点）
                    if (labelAngle != 0 && !originalStyle) {
                        // 旋转标签（原版 Timeline 45°/30°/60° 选项）
                        AffineTransform labelSaved = g.getTransform();
                        g.rotate(Math.toRadians(labelAngle), sx, sy + 13);
                        if (labelShadow) {   // 标签阴影（原版 "Label Shadow" 视觉选项）
                            Color c0 = g.getColor();
                            g.setColor(new Color(0, 0, 0, 90));
                            g.drawString(ycscite.I18n.text(n.label), (float) (sx + 1), (float) (sy + 14));
                            g.setColor(c0);
                        }
                        String dispLabel = n.userLabel != null ? n.userLabel : n.label;
                        if (showClusterId) dispLabel = "#" + n.cluster + " " + dispLabel;
                        if (showCitationCount) dispLabel = dispLabel + " (" + n.weight + ")";
                        g.drawString(ycscite.I18n.text(dispLabel), (float) sx, (float) (sy + 13));
                        g.setTransform(labelSaved);
                    } else {
                        if (labelShadow) {
                            Color c0 = g.getColor();
                            g.setColor(new Color(0, 0, 0, 90));
                            g.drawString(ycscite.I18n.text(n.label), (float) (sx + 1), (float) (sy + 14));
                            g.setColor(c0);
                        }
                        String dispLabel = n.userLabel != null ? n.userLabel : n.label;
                        if (showClusterId) dispLabel = "#" + n.cluster + " " + dispLabel;
                        if (showCitationCount) dispLabel = dispLabel + " (" + n.weight + ")";
                        g.drawString(ycscite.I18n.text(dispLabel), (float) sx, (float) (sy + 13));
                    }
                }
            }
            if (dimmed) g.setComposite(oldComp);
            // k-Core 高亮环（原版 ph:1591-1598 Highlight k>=m，色环按核数层级）
            if (kcoreHighlight > 0) {
                var cores = ycscite.analysis.KCore.compute(network);
                int core = cores.getOrDefault(n, 0);
                if (core >= kcoreHighlight) {
                    Point2D.Double pk = fisheyeTransform(p0);
                    double kr = radius.getOrDefault(n, 8.0) * nodeScale;
                    g.setColor(Color.getHSBColor(0.33f * (core % 5) / 5f, 0.8f, 0.9f));
                    g.setStroke(new BasicStroke(2f));
                    g.drawOval((int) (pk.x - kr - 4), (int) (pk.y - kr - 4), (int) (2 * kr + 8), (int) (2 * kr + 8));
                }
            }
            // 书签星标 + 批注气泡（原版 "Bookmark/Annotate the Node"）
            // Pennant Diagram（原版 kh:578）：书签节点画小旗帜（三角旗替代/补充星标）
            if (n.bookmarked) {
                Point2D.Double pp = fisheyeTransform(p0);
                double pr = radius.getOrDefault(n, 8.0) * nodeScale;
                int[] px = {(int)(pp.x + pr), (int)(pp.x + pr + 10), (int)(pp.x + pr)};
                int[] py = {(int)(pp.y - pr - 4), (int)(pp.y - pr), (int)(pp.y - pr + 4)};
                g.setColor(new Color(255, 100, 0, 200));
                g.fillPolygon(px, py, 3);
                g.setColor(new Color(180, 60, 0));
                g.setStroke(new BasicStroke(1f));
                g.drawLine((int)(pp.x + pr), (int)(pp.y - pr - 4), (int)(pp.x + pr), (int)(pp.y - pr + 4));
            }
            if (n.bookmarked || (n.annotation != null && !n.annotation.isEmpty())) {
                Point2D.Double ps = fisheyeTransform(p0);
                if (n.bookmarked) {
                    g.setColor(new Color(255, 200, 0));
                    g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
                    g.drawString(ycscite.I18n.text("★"), (float) (ps.x - 5), (float) (ps.y - radius.getOrDefault(n, 8.0) - 6));
                }
                if (n.annotation != null && !n.annotation.isEmpty()) {
                    g.setColor(new Color(60, 120, 220, 200));
                    g.fillOval((int) (ps.x + radius.getOrDefault(n, 8.0) * 0.7) - 3,
                               (int) (ps.y - radius.getOrDefault(n, 8.0) - 14), 7, 7);
                    g.setColor(new Color(60, 60, 60));
                    g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
                    g.drawString(ycscite.I18n.text(n.annotation.length() > 20 ? n.annotation.substring(0, 19) + "…" : n.annotation),
                            (float) (ps.x + radius.getOrDefault(n, 8.0) + 8), (float) (ps.y - radius.getOrDefault(n, 8.0) - 8));
                }
            }
        }
        g.setTransform(saved);

        // x-axis year labels
        if (mode != Mode.MERGED && network.minYear != Integer.MAX_VALUE) {
            g.setColor(new Color(160, 160, 160));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            int span = Math.max(network.maxYear - network.minYear, 1);
            for (int y = network.minYear; y <= network.maxYear; y++) {
                double wx = 90 + (WORLD_W - 180.0) * (y - network.minYear) / span;
                g.drawString(ycscite.I18n.text(String.valueOf(y)), (int) (offX + wx * zoom) - 14, getHeight() - 8);
            }
        }
        drawLegend(g);
    }

    /** Top-right legend (dark theme) / left light legend (original style: original figure 02's left vertical color band + cluster list). */
    private void drawLegend(Graphics2D g) {
        if (network == null) return;
        List<Integer> cs = new ArrayList<>(clusterLabels.keySet());
        if (cs.isEmpty()) {
            for (GraphNode n : network.nodeCollection()) if (n.cluster >= 0 && !cs.contains(n.cluster)) cs.add(n.cluster);
            Collections.sort(cs);
        }
        if (originalStyle && !Boolean.getBoolean("ycscite.legend701")) {
            return;   // 原版 kh 导出无图例面板（仅左上参数摘要+底部 logo，实测 1.4% ink）
        }
        if (originalStyle) {
            // 原版风格图例（仅 -Dycscite.legend701=true 时启用）
            float ls = Math.max(1f, getWidth() / 1000f);
            int x = 12;
            int y = (int) ((20 + (paramPanel.isEmpty() ? 30 : paramPanel.size() * 18 + 36)) * ls);
            int rows = Math.min(cs.size(), 15);
            int w = (int) (250 * ls);
            int h = (int) ((26 + rows * 15 + 30 + 40) * ls);
            g.setColor(new Color(255, 255, 255, 230));
            g.fillRoundRect(x, y, w, h, 8, 8);
            g.setColor(new Color(160, 160, 160));
            g.drawRoundRect(x, y, w, h, 8, 8);
            g.setColor(new Color(50, 50, 50));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 12f * ls));
            g.drawString(ycscite.I18n.text(Strings.get("legend.clusters")), (int) (x + 10 * ls), (int) (y + 17 * ls));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f * ls));
            int yy = (int) (y + 30 * ls);
            for (int i = 0; i < rows; i++) {
                int c = cs.get(i);
                g.setColor(PALETTE[Math.floorMod(c, PALETTE.length)]);
                g.fillRect((int) (x + 10 * ls), (int) (yy - 9 * ls), (int) (9 * ls), (int) (9 * ls));
                g.setColor(new Color(60, 60, 60));
                StringBuilder lb = new StringBuilder("#").append(c);
                List<LLRLabeler.Scored> ls2 = clusterLabels.get(c);
                if (ls2 != null && !ls2.isEmpty()) lb.append("  ").append(ls2.get(0).term);
                g.drawString(ycscite.I18n.text(lb.toString()), (int) (x + 25 * ls), yy);
                yy += (int) (15 * ls);
            }
            // 竖向年份彩虹色带（原版图02 左侧竖条）
            int barX = (int) (x + 10 * ls), barY = (int) (yy + 6 * ls);
            int barH = h - (barY - y) - (int) (46 * ls), barW = (int) (14 * ls);
            int span = Math.max(network.maxYear - network.minYear, 1);
            for (int i = 0; i < barH; i++) {
                int yr = network.maxYear - (int) Math.round((double) i / Math.max(barH - 1, 1) * span);
                g.setColor(yearColor(yr));
                g.fillRect(barX, barY + i, barW, 1);
            }
            g.setColor(new Color(60, 60, 60));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f * ls));
            g.drawString(ycscite.I18n.text(String.valueOf(network.maxYear)), (int) (barX + barW + 4 * ls), (int) (barY + 9 * ls));
            g.drawString(ycscite.I18n.text(String.valueOf(network.minYear)), (int) (barX + barW + 4 * ls), barY + barH);
            // 紫环=中心性，红环=突现
            g.setColor(new Color(148, 0, 211));
            g.setStroke(new BasicStroke(2.5f * ls));
            g.drawOval((int) (x + w - 118 * ls), (int) (yy + 8 * ls), (int) (10 * ls), (int) (10 * ls));
            g.setColor(new Color(60, 60, 60));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f * ls));
            g.drawString(ycscite.I18n.text(Strings.get("legend.centrality")), (int) (x + w - 100 * ls), (int) (yy + 17 * ls));
            g.setColor(new Color(220, 20, 60));
            g.drawOval((int) (x + w - 118 * ls), (int) (yy + 24 * ls), (int) (10 * ls), (int) (10 * ls));
            g.drawString(ycscite.I18n.text(Strings.get("legend.burst")), (int) (x + w - 100 * ls), (int) (yy + 33 * ls));
            return;
        }
        int w = 250, x = getWidth() - w - 12, y = 30;
        int clusters = (int) network.nodeCollection().stream().mapToInt(n -> n.cluster).filter(c -> c >= 0).distinct().count();
        int h = 34 + Math.min(clusters,12) * 16 + 46 + 44 + (clusters>12?16:0);
        g.setColor(theme==Theme.DARK?new Color(20,20,20,205):new Color(250,249,245,238));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(theme==Theme.DARK?new Color(110,110,110):new Color(210,201,185));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setColor(themeFg());
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.drawString(ycscite.I18n.text(Strings.get("legend.clusters")), x + 10, y + 17);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        int yy = y + 32;
        for (int i = 0; i < Math.min(cs.size(), 12); i++) {
            int c = cs.get(i);
            g.setColor(PALETTE[Math.floorMod(c, PALETTE.length)]);
            g.fillRect(x + 10, yy - 9, 10, 10);
            g.setColor(themeFg());
            StringBuilder lb = new StringBuilder("#").append(c);
            List<LLRLabeler.Scored> ls = clusterLabels.get(c);
            if (ls != null && !ls.isEmpty()) lb.append("  ").append(ls.get(0).term);
            g.drawString(ycscite.I18n.text(lb.toString()), x + 26, yy);
            yy += 16;
        }
        if(clusters>12){g.setColor(themeFg());g.drawString(Strings.isZh()?"另有 "+(clusters-12)+" 个聚类":"+ "+(clusters-12)+" more clusters",x+10,yy);yy+=16;}
        yy += 6;
        g.setColor(themeFg());
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.drawString(ycscite.I18n.text(Strings.get("legend.years")), x + 10, yy);
        yy += 8;
        for (int i = 0; i <= 10; i++) {
            g.setColor(yearColor(network.minYear + (network.maxYear - network.minYear) * i / 10));
            g.fillRect(x + 10 + i * 22, yy, 22, 10);
        }
        g.setColor(themeFg());
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10f));
        g.drawString(ycscite.I18n.text(String.valueOf(network.minYear)), x + 10, yy + 22);
        String maxY = String.valueOf(network.maxYear);
        g.drawString(ycscite.I18n.text(maxY), x + 10 + 11 * 22 - g.getFontMetrics().stringWidth(maxY), yy + 22);
        yy += 38;
        g.setColor(new Color(148, 0, 211));
        g.setStroke(new BasicStroke(2.5f));
        g.drawOval(x + 14, yy, 10, 10);
        g.setColor(themeFg());
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString(ycscite.I18n.text(Strings.get("legend.centrality")), x + 32, yy + 10);
        g.setColor(new Color(220, 20, 60));
        g.drawOval(x + 14, yy + 16, 10, 10);
        g.drawString(ycscite.I18n.text(Strings.get("legend.burst")), x + 32, yy + 26);
    }

    /** Render into an image at a fixed size (headless-safe, for tests, PDF and PNG export). */
    /** SVG 矢量导出（对应原版 "View As SVG"）：白底、聚类配色、按权重节点半径、边按权重。 */
    public void writeSvg(java.nio.file.Path out, int w, int h) throws java.io.IOException {
        if (network == null || pos.isEmpty()) {
            java.nio.file.Files.writeString(out,"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\""+w+"\" height=\""+h+"\" viewBox=\"0 0 "+w+" "+h+"\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/><text x=\"60\" y=\"100\" font-size=\"24\">"+ycscite.I18n.text("没有可绘制的数据")+"</text></svg>");
            return;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point2D.Double p : pos.values()) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        }
        double s = Math.min((w - 80) / Math.max(maxX - minX, 1e-9), (h - 80) / Math.max(maxY - minY, 1e-9));
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(w).append("\" height=\"").append(h)
          .append("\" viewBox=\"0 0 ").append(w).append(' ').append(h).append("\">\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
        for (GraphEdge e : network.edges) {
            Point2D.Double a = pos.get(e.a), b = pos.get(e.b);
            if (a == null || b == null) continue;
            sb.append(String.format("<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"#b8c4d0\" stroke-width=\"%.2f\" opacity=\"0.5\"/>%n",
                    40 + (a.x - minX) * s, 40 + (a.y - minY) * s, 40 + (b.x - minX) * s, 40 + (b.y - minY) * s,
                    Math.min(3, 0.5 + 0.3 * e.weight)));
        }
        for (GraphNode n : network.nodeCollection()) {
            Point2D.Double p = pos.get(n);
            if (p == null) continue;
            double r = radius.getOrDefault(n, 6.0);
            Color c = PALETTE[Math.floorMod(Math.max(n.cluster, 0), PALETTE.length)];
            sb.append(String.format("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"rgb(%d,%d,%d)\" stroke=\"#333\" stroke-width=\"0.6\"/>%n",
                    40 + (p.x - minX) * s, 40 + (p.y - minY) * s, r, c.getRed(), c.getGreen(), c.getBlue()));
            if (n.weight >= 2)
                sb.append(String.format("<text x=\"%.1f\" y=\"%.1f\" font-size=\"10\" fill=\"#222\" font-family=\"sans-serif\">%s</text>%n",
                        40 + (p.x - minX) * s + r + 2, 40 + (p.y - minY) * s + 3,
                        n.label.replace("&", "&amp;").replace("<", "&lt;")));
        }
        sb.append("</svg>\n");
        java.nio.file.Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public BufferedImage renderImage(int w, int h) { return renderImage(w,h,1); }

    public BufferedImage renderImage(int w,int h,int scale) {
        if(scale<1||scale>5)throw new IllegalArgumentException("Image scale must be1..5");
        int oldW = getWidth(), oldH = getHeight();
        setSize(w, h);
        // 布局已由 show() 完成（世界坐标），此处只重适配视口 —— 避免每张导出重跑 5000 迭代 LinLog
        if (pos.isEmpty() && network != null) computeLayout();
        fitView();
        BufferedImage img = new BufferedImage(w*scale, h*scale, transparentBg ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(scale,scale);
        setBounds(0, 0, w, h);
        boolean oldOpaque = isOpaque();
        if (transparentBg) setOpaque(false);   // 透明导出：阻止 UI 委托填充背景
        paintComponent(g);
        setOpaque(oldOpaque);
        g.dispose();
        setSize(oldW, oldH);
        return img;
    }

    public void snapshotScaled(java.nio.file.Path png,int w,int h,int scale)throws java.io.IOException{
        var image=renderImage(w,h,scale);javax.imageio.ImageIO.write(image,"png",png.toFile());image.flush();
    }

    public void snapshot(java.nio.file.Path png, int w, int h) throws java.io.IOException {
        javax.imageio.ImageIO.write(renderImage(w, h), "png", png.toFile());
    }
}
