package ycscite.io;

import ycscite.analysis.Pipeline;
import ycscite.model.GraphNode;
import ycscite.model.Project;
import ycscite.model.Record;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * 补齐 CiteSpace 常见图表的绘制器（零依赖 Java2D）：
 * ① 年度发文量趋势（柱状图）；② 聚类演化山峰图（逐年各聚类活动量堆叠面积）；
 * ③ 突现 Top-N 表格图（关键词 + 年份跨度 + 强度红条）。
 */
public final class Charts {

    private static final Color[] PALETTE = {
        new Color(78, 121, 167), new Color(242, 142, 43), new Color(225, 87, 89),
        new Color(118, 183, 178), new Color(89, 161, 79), new Color(237, 201, 72),
        new Color(176, 122, 161), new Color(255, 157, 167), new Color(156, 117, 95)
    };

    // ---------------- ① 年度发文量趋势 ----------------

    public static void publicationTrend(Project p, Path out) throws IOException {
        Map<Integer, Integer> byYear = new TreeMap<>();
        for (Record r : p.records)
            if (r.year > 0) byYear.merge(r.year, 1, Integer::sum);
        render(out, 1200, 620, (g, w, h) -> {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(30, 30, 30));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
            g.drawString(ycscite.I18n.text("年度发文量趋势"), 30, 40);
            if (byYear.isEmpty()) return;
            int max = Collections.max(byYear.values());
            int y0 = 70, hAxis = h - 90, x0 = 70, wPlot = w - 110;
            int n = byYear.size();
            int bw = Math.max(4, wPlot / n - 4);
            int i = 0;
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
            for (var e : byYear.entrySet()) {
                int bh = (int) (hAxis * e.getValue() / (double) max);
                int x = x0 + (wPlot - bw) * i / Math.max(n - 1, 1);
                g.setColor(new Color(78, 121, 167));
                g.fillRect(x, y0 + hAxis - bh, bw, bh);
                g.setColor(new Color(90, 90, 90));
                if (n <= 16 || i % (n / 8) == 0)
                    g.drawString(ycscite.I18n.text(String.valueOf(e.getKey())), x - 8, y0 + hAxis + 18);
                i++;
            }
            g.setColor(new Color(120, 120, 120));
            g.drawLine(x0 - 6, y0 + hAxis, x0 + wPlot, y0 + hAxis);
            g.setColor(new Color(110, 110, 110));
            g.drawString(ycscite.I18n.text("max = " + max), x0 + 4, y0 + 14);
        });
    }

    // ---------------- ② 聚类演化山峰图 ----------------

    /** x=年份；各聚类每年的活动量（节点逐年计数之和）顺序堆叠成面积"山峰"。 */
    public static void clusterEvolution(Pipeline.Result r, Path out) throws IOException {
        if (r == null || r.network == null || r.network.minYear == Integer.MAX_VALUE) return;
        int y0 = r.network.minYear, y1 = r.network.maxYear;
        List<Integer> years = new ArrayList<>();
        for (int y = y0; y <= y1; y++) years.add(y);
        // cluster -> year -> activity
        Map<Integer, Map<Integer, Integer>> act = new TreeMap<>();
        for (GraphNode n : r.network.nodeCollection())
            for (var e : n.countsByYear.entrySet())
                act.computeIfAbsent(n.cluster, k -> new TreeMap<>())
                   .merge(e.getKey(), e.getValue(), Integer::sum);
        render(out, 1440, 1119, (g, w, h) -> {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(30, 30, 30));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 20f));
            g.drawString(ycscite.I18n.text("聚类演化山峰图（各聚类逐年活动量山脊）"), 36, 44);
            // 取活动量前 15 的簇（原版只画主要山脊）
            record Cl(int id, int[] series, int peak) { }
            List<Cl> cls = new ArrayList<>();
            for (var en : act.entrySet()) {
                int[] s = new int[years.size()];
                int pk = 0;
                for (int i = 0; i < years.size(); i++) {
                    s[i] = en.getValue().getOrDefault(years.get(i), 0);
                    pk = Math.max(pk, s[i]);
                }
                if (pk > 0) cls.add(new Cl(en.getKey(), s, pk));
            }
            cls.sort((a, b) -> Integer.compare(b.peak(), a.peak()));
            if (cls.size() > 15) cls = cls.subList(0, 15);
            int maxV = cls.stream().mapToInt(Cl::peak).max().orElse(1);
            int top = 80, bottom = h - 120, left = 70, right = w - 330;
            int n = years.size();
            // 每簇独立山峰轮廓（半透明叠加，原版视觉：独立山脊+清晰描边+峰顶标签）
            for (int ci = 0; ci < cls.size(); ci++) {
                Cl c = cls.get(ci);
                Color base = PALETTE[Math.floorMod(c.id(), PALETTE.length)];
                Path2D path = new Path2D.Double();
                path.moveTo(left, bottom);
                for (int i = 0; i < n; i++) {
                    double x = left + (right - left) * i / Math.max(n - 1, 1);
                    double v = c.series()[i];
                    if (i > 0 && i < n - 1)
                        v = (c.series()[i - 1] + 2.0 * c.series()[i] + c.series()[i + 1]) / 4.0;
                    double yy = bottom - (bottom - top) * v / (double) maxV;
                    path.lineTo(x, yy);
                }
                path.lineTo(right, bottom);
                path.closePath();
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 110));
                g.fill(path);
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 235));
                g.setStroke(new BasicStroke(2.2f));
                g.draw(path);
                // 峰顶标签（#N 词）
                int pi = 0;
                for (int i = 1; i < n; i++) if (c.series()[i] > c.series()[pi]) pi = i;
                double px = left + (right - left) * pi / Math.max(n - 1, 1);
                double py = bottom - (bottom - top) * c.series()[pi] / (double) maxV;
                var ls = r.clusterLabels == null ? null : r.clusterLabels.get(c.id());
                String label = "#" + c.id() + (ls != null && !ls.isEmpty() ? " " + ls.get(0).term : "");
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 255));
                g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
                g.drawString(ycscite.I18n.text(label), (int) px - g.getFontMetrics().stringWidth(label) / 2, (int) py - 8);
            }
            // 轴
            g.setColor(new Color(110, 110, 110));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(left - 4, bottom, right, bottom);
            for (int i = 0; i < n; i++)
                if (n <= 15 || i % Math.max(1, n / 12) == 0) {
                    double x = left + (right - left) * i / Math.max(n - 1, 1);
                    g.drawString(ycscite.I18n.text(String.valueOf(years.get(i))), (int) x - 16, bottom + 20);
                }
            // 右侧图例（原版：右侧色块+#N 词标签）
            int ly = top + 10;
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 14f));
            for (Cl c : cls) {
                g.setColor(PALETTE[Math.floorMod(c.id(), PALETTE.length)]);
                g.fillRect(right + 30, ly - 10, 14, 14);
                g.setColor(new Color(50, 50, 50));
                String label = "# " + c.id();
                var ls = r.clusterLabels == null ? null : r.clusterLabels.get(c.id());
                if (ls != null && !ls.isEmpty()) label += " " + ls.get(0).term;
                g.drawString(ycscite.I18n.text(label), right + 52, ly + 2);
                ly += 24;
                if (ly > bottom + 40) break;
            }
        });
    }

    // ---------------- ③ 突现 Top-N 表格图 ----------------

    public static void burstTable(Pipeline.Result r, Path out, int topN) throws IOException {
        if (r == null || r.network == null) return;
        record Row(String term, int from, int to, double strength, double sigma) { }
        List<Row> rows = new ArrayList<>();
        for (GraphNode n : burstSource(r))
            for (int i = 0; i < n.burstIntervals.size(); i++) {
                int[] b = n.burstIntervals.get(i);
                double strength = i < n.intervalStrengths.size()
                        ? n.intervalStrengths.get(i) : n.burstStrength;
                rows.add(new Row(n.label, b[0], b[1], strength, n.sigma));
            }
        rows.sort((a, b) -> Double.compare(b.strength(), a.strength()));
        if (rows.size() > topN) rows = rows.subList(0, topN);
        int rh = 36, w = 1400, h = 110 + rows.size() * rh;   // 视觉审计：字号加大可读
        final List<Row> data = rows;
        render(out, w, h, (g, width, height) -> {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(30, 30, 30));
            g.setFont(g.getFont().deriveFont(Font.BOLD, 20f));
            g.drawString(ycscite.I18n.text("关键词突现 Top " + data.size()), 40, 46);
            if (data.isEmpty()) {
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 14f));
                g.setColor(new Color(130, 130, 130));
                g.drawString(ycscite.I18n.text("（本数据未检测到突现词）"), 40, 90);
                return;
            }
            int y0 = 90, left = 40, termW = 320, spanW = width - termW - 140;
            // 统一年份轴：用整个网络的时间范围，红条位置与真实年份对应（与原版一致）
            int minYear = r.network.minYear == Integer.MAX_VALUE
                    ? data.stream().mapToInt(Row::from).min().orElse(0) : r.network.minYear;
            int maxYear = r.network.maxYear == Integer.MIN_VALUE
                    ? data.stream().mapToInt(Row::to).max().orElse(1) : r.network.maxYear;
            double maxS = data.stream().mapToDouble(Row::strength).max().orElse(1);
            int span = Math.max(1, maxYear - minYear);
            // 年份刻度
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
            g.setColor(new Color(110, 110, 110));
            for (int y = minYear; y <= maxYear; y++) {
                int x = left + termW + (int) (spanW * (y - minYear) / (double) span);
                g.drawLine(x, y0 - 16, x, y0 + data.size() * rh - 6);
                if ((y - minYear) % Math.max(1, span / 10) == 0) g.drawString(ycscite.I18n.text(String.valueOf(y)), x - 14, y0 - 22);
            }
            int i = 0;
            for (Row row : data) {
                int y = y0 + i * rh;
                g.setColor((i & 1) == 0 ? new Color(248, 248, 248) : Color.WHITE);
                g.fillRect(0, y, width, rh - 4);
                g.setColor(new Color(30, 30, 30));
                g.setFont(g.getFont().deriveFont(Font.BOLD, 17f));
                String term = row.term().length() > 20 ? row.term().substring(0, 19) + "…" : row.term();
                g.drawString(ycscite.I18n.text(term), left, y + 18);
                int x1 = left + termW + (int) (spanW * (row.from() - minYear) / (double) span);
                int x2 = left + termW + (int) (spanW * (row.to() - minYear + 1) / (double) span);
                int bh = (int) (18 * row.strength() / maxS) + 4;
                g.setColor(new Color(220, 30, 60));
                g.fillRect(Math.min(x1, x2 - 6), y + 12, Math.max(6, x2 - x1), bh);
                g.setColor(new Color(120, 120, 120));
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
                g.drawString(ycscite.I18n.text(String.format("%.1f", row.strength())), x2 + 8, y + 22);
                i++;
            }
            g.setColor(new Color(110, 110, 110));
            g.drawLine(left + termW, y0 - 16, left + termW, y0 + data.size() * rh - 6);
        });
    }

    /** 突现表 TSV（term, from, to, strength, sigma），供表格化分析。 */
    public static void burstTsv(Pipeline.Result r, Path out) throws IOException {
        StringBuilder sb = new StringBuilder("term\tfrom\tto\tstrength\tsigma\n");
        record Row(String term, int from, int to, double strength, double sigma) { }
        List<Row> rows = new ArrayList<>();
        for (GraphNode n : burstSource(r))
            for (int i = 0; i < n.burstIntervals.size(); i++) {
                int[] b = n.burstIntervals.get(i);
                double strength = i < n.intervalStrengths.size()
                        ? n.intervalStrengths.get(i) : n.burstStrength;
                rows.add(new Row(n.label, b[0], b[1], strength, n.sigma));
            }
        rows.sort((a, b) -> Double.compare(b.strength(), a.strength()));
        for (Row row : rows)
            sb.append(row.term()).append('\t').append(row.from()).append('\t').append(row.to())
              .append('\t').append(String.format("%.4f", row.strength())).append('\t')
              .append(String.format("%.4f", row.sigma())).append('\n');
        java.nio.file.Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------- 渲染骨架 ----------------

    /** 突现表数据源：全词表结果优先（原版 word.list 口径，含网络外词），回退网络节点。 */
    private static Iterable<GraphNode> burstSource(Pipeline.Result r) {
        if (r.burstRows != null && !r.burstRows.isEmpty()) return r.burstRows;
        return r.network.nodeCollection();
    }

    private interface Painter { void paint(Graphics2D g, int w, int h); }

    private static void render(Path out, int w, int h, Painter painter) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 指定中文字体优先（宋体/微软雅黑），避免默认 Dialog 缺字
        Font base = new Font("Microsoft YaHei", Font.PLAIN, 14);
        if (!"Microsoft YaHei".equalsIgnoreCase(base.getFamily())) base = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        g.setFont(base);
        painter.paint(g, w, h);
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }
}
