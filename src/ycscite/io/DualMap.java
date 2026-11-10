package ycscite.io;

import ycscite.model.Project;
import ycscite.model.Record;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.*;

/**
 * Dual-Map Overlays（原版 "Dual Map Overlays"，Chen 2011/2017）：
 * 左半圆 = 施引文献的期刊分布，右半圆 = 被引期刊分布，弧线 = 施引→被引关系。
 * 两侧仅使用记录的施引期刊和可解析参考文献期刊；缺失的参考期刊不补造。
 * 有 CR 的 WoS 数据则按真实引文期刊对统计。
 */
public final class DualMap {

    public static void render(Project p, Path out) throws IOException {
        // 施引期刊计数（左）与被引期刊计数（右）+ 关系计数
        Map<String, Integer> citing = new TreeMap<>();
        Map<String, Integer> cited = new TreeMap<>();
        Map<String, Map<String, Integer>> links = new HashMap<>();
        boolean anyRef = false;
        for (Record r : p.records) {
            String src = r.source == null || r.source.isBlank() ? "Unknown" : r.source.trim();
            citing.merge(src, 1, Integer::sum);
            for (String raw : r.citedRefs) {
                String[] seg = raw.split(",");
                if (seg.length < 3) continue;
                String refJournal = seg[2].trim();
                if (refJournal.isBlank()) continue;
                anyRef = true;
                cited.merge(refJournal, 1, Integer::sum);
                links.computeIfAbsent(src, k -> new HashMap<>()).merge(refJournal, 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> left = top(citing, 18);
        List<Map.Entry<String, Integer>> right = top(cited, 18);

        int w = 1400, h = 860;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        if (!"Microsoft YaHei".equalsIgnoreCase(g.getFont().getFamily())) g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(30, 30, 30));
        g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
        g.drawString(ycscite.I18n.text("Dual-Map Overlays（左：施引期刊 · 右：被引对象）"), 40, 40);

        // 关系弧线（先画，垫底）
        Random rnd = new Random(7);
        int cxL = w / 4, cxR = 3 * w / 4, cy = h / 2 + 20, R = 300;
        Map<String, double[]> posL = fanPositions(left, cxL, cy, R, true);
        Map<String, double[]> posR = fanPositions(right, cxR, cy, R, false);
        for (var le : links.entrySet()) {
            double[] from = posL.get(le.getKey());
            if (from == null) continue;
            for (var re : le.getValue().entrySet()) {
                double[] to = posR.get(re.getKey());
                if (to == null) continue;
                int weight = re.getValue();
                Color c = Color.getHSBColor(rnd.nextFloat(), 0.55f, 0.75f);
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 110));
                g.setStroke(new BasicStroke((float) Math.min(4, 0.5 + Math.log(1 + weight))));
                // 左扇到右扇的贝塞尔弧
                double mx = (from[0] + to[0]) / 2, my = Math.min(from[1], to[1]) - 140;
                g.draw(new java.awt.geom.CubicCurve2D.Double(from[0], from[1], mx, my, mx, my + 60, to[0], to[1]));
            }
        }
        // 左右半圆底座
        g.setColor(new Color(235, 238, 242));
        g.fill(new Arc2D.Double(cxL - R, cy - R, 2 * R, 2 * R, 90, 180, Arc2D.OPEN));
        g.fill(new Arc2D.Double(cxR - R, cy - R, 2 * R, 2 * R, 270, 180, Arc2D.OPEN));
        g.setColor(new Color(150, 150, 150));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Arc2D.Double(cxL - R, cy - R, 2 * R, 2 * R, 90, 180, Arc2D.OPEN));
        g.draw(new Arc2D.Double(cxR - R, cy - R, 2 * R, 2 * R, 270, 180, Arc2D.OPEN));

        // 期刊节点 + 标签
        drawSide(g, left, posL, new Color(78, 121, 167), true);
        drawSide(g, right, posR, new Color(225, 87, 89), false);
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(m.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(n, list.size()));
    }

    /** 沿半圆扇形排布期刊节点。 */
    private static Map<String, double[]> fanPositions(List<Map.Entry<String, Integer>> items,
                                                      int cx, int cy, int R, boolean leftSide) {
        Map<String, double[]> out = new HashMap<>();
        int n = items.size();
        if (n == 0) return out;
        double span = 160.0, start = leftSide ? 190 : -10; // 度
        for (int i = 0; i < n; i++) {
            double ang = Math.toRadians(start + span * i / Math.max(n - 1, 1));
            double x = cx + R * Math.cos(ang);
            double y = cy - R * Math.sin(ang);
            out.put(items.get(i).getKey(), new double[]{x, y});
        }
        return out;
    }

    private static void drawSide(Graphics2D g, List<Map.Entry<String, Integer>> items,
                                 Map<String, double[]> pos, Color color, boolean leftSide) {
        int max = items.stream().mapToInt(Map.Entry::getValue).max().orElse(1);
        for (var en : items) {
            double[] p = pos.get(en.getKey());
            double r = 4 + 9 * Math.sqrt(en.getValue() / (double) max);
            g.setColor(color);
            g.fill(new Ellipse2D.Double(p[0] - r, p[1] - r, 2 * r, 2 * r));
            g.setColor(new Color(60, 60, 60));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
            String label = en.getKey();
            if (label.length() > 22) label = label.substring(0, 21) + "…";
            int sw = g.getFontMetrics().stringWidth(label);
            double lx = leftSide ? p[0] - sw - 10 : p[0] + 10;
            g.drawString(ycscite.I18n.text(label), (int) lx, (int) (p[1] + 4));
        }
    }

    // ---------------- JCR 期刊底图版（原版 jar 内置 jcr2011，10,331+10,254 期刊，Leydesdorff 授权） ----------------

    static final class JcrPoint {
        final String journal; final double x, y; final int cluster, r, gg, b; final double weight;
        JcrPoint(String j, double x, double y, int c, int r, int gg, int b, double w) {
            this.journal = j; this.x = x; this.y = y; this.cluster = c; this.r = r; this.gg = gg; this.b = b; this.weight = w;
        }
    }

    public static List<JcrPoint> loadJcr(java.nio.file.Path csv) throws IOException {
        List<JcrPoint> out = new ArrayList<>();
        if (!java.nio.file.Files.exists(csv)) return out;
        for (var row : ScopusCsvParser.parseCsv(java.nio.file.Files.readString(csv, java.nio.charset.StandardCharsets.UTF_8))) {
            if(row.isEmpty()||row.getFirst().equals("ID"))continue;
            String[] t=row.toArray(String[]::new);
            if (t.length < 10) continue;
            try {
                out.add(new JcrPoint(t[1].toUpperCase(), Double.parseDouble(t[2]), Double.parseDouble(t[3]),
                        Integer.parseInt(t[5]), Integer.parseInt(t[7]), Integer.parseInt(t[8]), Integer.parseInt(t[9]),
                        Double.parseDouble(t[4])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** JCR 底图渲染：左侧施引期刊图 + 右侧被引期刊图（聚类色点云），叠加数据轨迹弧（期刊名匹配）。 */
    public static void renderJcr(Project p, Path out) throws IOException {
        String configured=System.getProperty("ycscite.jcr.home",System.getenv("YCSCITE_JCR_HOME"));
        if(configured==null||configured.isBlank()){render(p,out);return;}
        java.nio.file.Path home=java.nio.file.Path.of(configured);
        List<JcrPoint> citingMap = loadJcr(home.resolve("citing.csv"));
        List<JcrPoint> citedMap = loadJcr(home.resolve("cited.csv"));
        if (citingMap.isEmpty() || citedMap.isEmpty()) { render(p, out); return; }   // 底图缺失回退自绘版

        // 数据侧计数与链接
        Map<String, Integer> citing = new HashMap<>(), cited = new HashMap<>();
        Map<String, Map<String, Integer>> links = new HashMap<>();
        for (Record r : p.records) {
            String src = r.source == null || r.source.isBlank() ? "Unknown" : r.source.trim().toUpperCase();
            citing.merge(src, 1, Integer::sum);
            for (String raw : r.citedRefs) {
                String[] seg = raw.split(",");
                if (seg.length < 3) continue;
                String rj = seg[2].trim().toUpperCase();
                if (rj.isBlank()) continue;
                cited.merge(rj, 1, Integer::sum);
                links.computeIfAbsent(src, k -> new HashMap<>()).merge(rj, 1, Integer::sum);
            }
        }

        int w = 1600, h = 900;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(30, 30, 30));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
        if (!"Microsoft YaHei".equalsIgnoreCase(g.getFont().getFamily())) g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString(ycscite.I18n.text("Dual-Map Overlays — JCR Base Map")+" · "+citingMap.size()+" / "+citedMap.size(), 40, 36);

        Map<String, double[]> leftPos = drawJcrSide(g, citingMap, 40, 60, w / 2 - 60, h - 120, "施引期刊");
        Map<String, double[]> rightPos = drawJcrSide(g, citedMap, w / 2 + 20, 60, w / 2 - 60, h - 120, "被引期刊");

        // 数据轨迹弧（贝塞尔，匹配底图期刊）
        int drawn = 0, totalLinks = 0;
        for (var le : links.entrySet()) {
            double[] a = leftPos.get(le.getKey());
            if (a == null) continue;
            for (var re : le.getValue().entrySet()) {
                double[] b = rightPos.get(re.getKey());
                if (b == null) continue;
                totalLinks++;
                if (drawn > 400) continue;
                drawn++;
                g.setColor(new Color(210, 60, 60, 90));
                g.setStroke(new BasicStroke(Math.max(1f, Math.min(4f, re.getValue() * 0.4f))));
                g.draw(new Arc2D.Double(Math.min(a[0], b[0]), Math.min(a[1], b[1]) - 120,
                        Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]) + 240, 0, 180, Arc2D.OPEN));
            }
        }
        g.setColor(new Color(110, 110, 110));
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12));
        g.drawString(ycscite.I18n.text("轨迹：施引→被引（期刊名匹配底图，共 " + totalLinks + " 对）"), 40, h - 20);
        ImageIO.write(img, "png", out.toFile());
    }

    private static Map<String, double[]> drawJcrSide(Graphics2D g, List<JcrPoint> pts,
                                                     int x0, int y0, int w, int h, String title) {
        Map<String, double[]> pos = new HashMap<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (JcrPoint p : pts) {
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        }
        double s = Math.min(w / Math.max(maxX - minX, 1e-9), h / Math.max(maxY - minY, 1e-9));
        g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
        g.setColor(new Color(70, 70, 70));
        g.drawString(ycscite.I18n.text(title), x0 + 10, y0 + 18);
        double maxW = 1;
        for (JcrPoint p : pts) maxW = Math.max(maxW, p.weight);
        for (JcrPoint p : pts) {
            double px = x0 + 10 + (p.x - minX) * s, py = y0 + 40 + (maxY - p.y) * s;
            double r = 1.4 + 3.0 * Math.sqrt(p.weight / maxW);
            Color c = new Color(Math.max(p.r, 30), Math.max(p.gg, 30), Math.max(p.b, 30), 150);
            g.setColor(c);
            g.fill(new Ellipse2D.Double(px - r, py - r, 2 * r, 2 * r));
            pos.put(p.journal, new double[]{px, py});
        }
        return pos;
    }
}
