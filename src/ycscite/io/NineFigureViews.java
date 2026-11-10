package ycscite.io;

import ycscite.Settings;
import ycscite.analysis.NetworkBuilders;
import ycscite.analysis.Pipeline;
import ycscite.model.GraphNode;
import ycscite.model.Network;
import ycscite.model.Project;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/** White publication views. Coordinates and font sizes are expressed at the same preview scale. */
final class NineFigureViews {
    private static final int W = 1320, H = 818;
    private static final Color INK = new Color(38, 40, 44);
    private static final Color GRID = new Color(226, 229, 233);
    private static final Comparator<GraphNode> IMPORTANCE =
            Comparator.comparingInt((GraphNode n) -> n.weight).reversed()
                    .thenComparing(Comparator.comparingDouble((GraphNode n) -> n.centrality).reversed())
                    .thenComparing(n -> n.id);
    private final int from, to;
    private final Settings settings;
    private final List<Theme> themes;

    private record Theme(int id, int index, String name, Color color, List<GraphNode> nodes) {}
    private record BurstRow(GraphNode node, int first, int begin, int end, double strength) {}
    private enum Look { KEYWORDS, CLUSTERS, TIMEZONE, AUTHORS, INSTITUTIONS }

    NineFigureViews(int from, int to, Network keywords) { this(from, to, keywords, new Settings()); }
    NineFigureViews(int from, int to, Network keywords, Settings settings) {
        this.settings = settings;
        this.from = from;
        this.to = to;
        Map<Integer, List<GraphNode>> groups = new TreeMap<>();
        for (GraphNode n : keywords.nodeCollection())
            if (n.cluster > 0 && !keywords.neighbors(n).isEmpty())
                groups.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);
        var main = groups.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, List<GraphNode>>>comparingInt(e -> e.getValue().size()).reversed()
                        .thenComparingInt(Map.Entry::getKey)).limit(16).toList();
        List<Theme> ts = new ArrayList<>();
        for (var e : main) {
            var ns = e.getValue();
            ns.sort(IMPORTANCE);
            int i = ts.size();
            // Match the reference's size order and representative keywords; TSV retains raw cluster IDs.
            ts.add(new Theme(e.getKey(), i, ns.getFirst().label,
                    spectrum(.99f - .99f * i / Math.max(1, main.size() - 1)), ns));
        }
        themes = List.copyOf(ts);
    }

    void publications(Project p, Path png, Path tsv) throws IOException {
        Map<Integer, Integer> counts = new TreeMap<>();
        p.records.forEach(r -> counts.merge(r.year, 1, Integer::sum));
        StringBuilder data = new StringBuilder("year\tcount\n");
        for (int y = from; y <= to; y++) data.append(y).append('\t').append(counts.getOrDefault(y, 0)).append('\n');
        Files.writeString(tsv, data);
        int maximum = counts.values().stream().mapToInt(i -> i).max().orElse(1);
        int rough = Math.max(1, (maximum + 7) / 8);
        int magnitude = (int) Math.pow(10, (int) Math.log10(rough));
        int tick = (rough <= magnitude ? 1 : rough <= 2 * magnitude ? 2 : rough <= 5 * magnitude ? 5 : 10) * magnitude;
        int upper = ((maximum + tick - 1) / tick) * tick;
        render(png, 1100, 600, g -> {
            int left = 95, right = 1065, top = 52, base = 496;
            title(g, "年度发文量趋势");
            g.setFont(font(14, false, false));
            for (int v = 0; v <= upper; v += tick) {
                double y = base - v * (base - top) / (double)upper;
                line(g, left, y, right, y, GRID, 1);
                String label = Integer.toString(v);
                g.setColor(INK);
                g.drawString(ycscite.I18n.text(label), left - 12 - g.getFontMetrics().stringWidth(label), (float)y + 4);
            }
            Path2D plot = new Path2D.Double();
            int stride = tickStep(28);
            for (int y = from; y <= to; y++) {
                double x = yearX(y, left + 8, right - 12);
                int count = counts.getOrDefault(y, 0);
                double yy = base - count * (base - top) / (double)upper;
                if (y == from) plot.moveTo(x, yy); else plot.lineTo(x, yy);
                if ((y - from) % stride == 0 || y == to) {
                    g.setColor(INK);
                    center(g, Integer.toString(count), x, yy - 10);
                    Graphics2D tickG = (Graphics2D) g.create();
                    tickG.translate(x, base + 14);
                    tickG.rotate(-Math.PI / 4);
                    tickG.drawString(ycscite.I18n.text(Integer.toString(y)), -31, 4);
                    tickG.dispose();
                }
            }
            g.setColor(new Color(62, 109, 178));
            g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(plot);
            if (from == to) circle(g, (left + right - 4) / 2.0, base - maximum * (base - top) / (double)upper, 3, g.getColor());
            g.setColor(INK);
            center(g, "年份（年）", (left + right) / 2.0, 575);
            Graphics2D axis = (Graphics2D) g.create();
            axis.translate(27, (top + base) / 2.0);
            axis.rotate(-Math.PI / 2);
            center(axis, "发文量（篇）", 0, 0);
            axis.dispose();
        });
    }

    void network(Pipeline.Result r, Path png, String title, boolean collaboration) throws IOException {
        boolean institution = NetworkBuilders.INSTITUTION.equals(r.network.kind);
        Look look = institution ? Look.INSTITUTIONS : collaboration ? Look.AUTHORS : Look.KEYWORDS;
        Map<GraphNode, Point2D.Double> pos = ReportNetworkLayout.whole(r.network, collaboration, institution);
        renderDetail(png, g -> {
            referencePanel(g, title, r, pos.size(), !collaboration);
            referenceLegend(g);
            referenceEdges(g, r.network, pos, Map.of(), look);
            referenceNodes(g, pos, Map.of(), look);
            referenceLabels(g, pos, look);
        });
    }

    void clusters(Pipeline.Result r, Path png) throws IOException {
        Map<GraphNode, Point2D.Double> pos = new LinkedHashMap<>();
        Map<GraphNode, Color> colors = themeColors();
        double cx = 666, cy = 425;
        Map<Theme, Point2D.Double> centers = new LinkedHashMap<>();
        double angleSum = themes.stream().skip(1).mapToDouble(NineFigureViews::arcWeight).sum();
        double angle = -Math.PI * .53 - (themes.size() < 2 ? 0 : Math.PI * arcWeight(themes.get(1)) / angleSum);
        for (Theme t : themes) {
            boolean core = t.index == 0;
            double sweep = core ? 0 : 2 * Math.PI * arcWeight(t) / angleSum;
            double a = angle + sweep / 2;
            angle += sweep;
            double x = cx + (core ? 0 : 417 * Math.cos(a)), y = cy + (core ? 13 : 250 * Math.sin(a));
            double radius = core ? 136 : Math.min(119, 32 + 9.6 * Math.sqrt(t.nodes.size()));
            centers.put(t, point(x, y));
            pos.putAll(ReportNetworkLayout.cluster(r.network, t.nodes, x, y, radius, radius * .91, t.index));
        }
        renderDetail(png, g -> {
            referencePanel(g, "关键词聚类", r, pos.size(), true);
            for (Theme t : themes) {
                g.setColor(alpha(t.color, 47));
                g.fill(clusterArea(r.network, t, pos));
            }
            referenceEdges(g, r.network, pos, colors, Look.CLUSTERS);
            referenceNodes(g, pos, colors, Look.CLUSTERS);
            List<Rectangle2D> used = new ArrayList<>();
            for (Theme t : themes) {
                Point2D.Double c = centers.get(t);
                referenceLabel(g, themeName(t), c, (int)(21 * settings.number("label_scale")), themeInk(t.color), used, true);
            }
            themeLegend(g);
        });
    }

    private static double arcWeight(Theme t) { return 12 + Math.sqrt(t.nodes.size()); }

    private static Shape clusterArea(Network net, Theme theme, Map<GraphNode, Point2D.Double> pos) {
        Set<GraphNode> members = new HashSet<>(theme.nodes);
        Area area = new Area();
        double padding = theme.nodes.size() <= 5 ? 24 : 34;
        BasicStroke buffer = new BasicStroke((float)(padding * 1.48), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        for (GraphNode n : theme.nodes) {
            var p = pos.get(n);
            double r = padding + Math.min(12, Math.sqrt(n.weight));
            area.add(new Area(new Ellipse2D.Double(p.x - r, p.y - r, r * 2, r * 2)));
        }
        for (var e : net.edges) if (members.contains(e.a) && members.contains(e.b))
            area.add(new Area(buffer.createStrokedShape(new Line2D.Double(pos.get(e.a), pos.get(e.b)))));
        return area;
    }

    void timezone(Pipeline.Result r, Path png) throws IOException {
        int left = 140, right = 1140, top = 54, bottom = 766;
        Map<Integer, List<GraphNode>> years = new TreeMap<>();
        for (GraphNode n : r.network.nodeCollection())
            years.computeIfAbsent(n.firstYear, k -> new ArrayList<>()).add(n);
        var pos = new LinkedHashMap<GraphNode, Point2D.Double>();
        int columns = to - from + 1;
        double colWidth = (right - left) / (double)Math.max(1, columns - 1);
        int tallest = years.values().stream().mapToInt(List::size).max().orElse(1);
        double row = (bottom - top - 31.0) / Math.max(1, tallest - 1);
        for (var e : years.entrySet()) {
            List<GraphNode> ns = e.getValue();
            ns.sort(IMPORTANCE);
            // kh.au / kM: oldest year is the column; rank each column by frequency from top to bottom.
            double first = bottom - 17 - (ns.size() - 1) * row;
            for (int i = 0; i < ns.size(); i++)
                pos.put(ns.get(i), point(yearX(e.getKey(), left, right), first + i * row));
        }
        separateTimeHubs(pos, top);
        renderDetail(png, g -> {
            int stride = Math.max(1, tickStep(60));
            for (int y = from; y <= to + 1; y += stride * 2) {
                double x = yearX(y, left, right), bw = Math.min(44, colWidth * stride);
                g.setColor(new Color(255, 230, 235));
                g.fill(new Rectangle2D.Double(x, top, bw, bottom - top));
            }
            g.setFont(font(8, true, false));
            g.setColor(new Color(206, 65, 85));
            for (int y = from; y <= to; y += tickStep(36))
                g.drawString(ycscite.I18n.text(Integer.toString(y)), (float)yearX(y, left, right) + 2, bottom - 5);
            referenceLegend(g);
            referenceEdges(g, r.network, pos, Map.of(), Look.TIMEZONE);
            referenceNodes(g, pos, Map.of(), Look.TIMEZONE);
            referenceLabels(g, pos, Look.TIMEZONE);
            referencePanel(g, "关键词时区", r, pos.size(), true);
        });
    }

    /** Keep first-year x coordinates exact while clearing the other hubs in the same column. */
    private void separateTimeHubs(Map<GraphNode, Point2D.Double> pos, int top) {
        List<GraphNode> placed = new ArrayList<>();
        for (GraphNode n : pos.keySet().stream().sorted(IMPORTANCE).toList()) {
            double r = radius(n, Look.TIMEZONE);
            if (r < 12) continue;
            var p = pos.get(n);
            for (GraphNode prior : placed) {
                var q = pos.get(prior);
                double dx = Math.abs(p.x - q.x);
                boolean sameColumn = Math.abs(dx) < .01;
                boolean bothLarge = r >= 40 && radius(prior, Look.TIMEZONE) >= 40;
                if (!sameColumn && Math.max(r, radius(prior, Look.TIMEZONE)) < 40) continue;
                double distance = (r + radius(prior, Look.TIMEZONE)) * (bothLarge && !sameColumn ? .67 : 1.04);
                if (dx >= distance) continue;
                double dy = Math.sqrt(distance * distance - dx * dx);
                if (Math.abs(p.y - q.y) < dy) p.y = Math.max(top + r + 4, q.y - dy);
            }
            placed.add(n);
        }
    }

    void timeline(Pipeline.Result r, Path png) throws IOException {
        int left = 146, right = 1128, top = 207, bottom = 764;
        double gap = (bottom - top) / (double)Math.max(1, themes.size() - 1);
        Map<GraphNode, Point2D.Double> pos = new LinkedHashMap<>();
        for (Theme t : themes) {
            Map<Integer, Integer> counts = new HashMap<>();
            for (GraphNode n : t.nodes) {
                int rank = counts.merge(n.firstYear, 1, Integer::sum) - 1;
                double offset = rank == 0 ? 0 : (rank % 2 == 0 ? 1 : -1) * Math.min(gap * .28, 2.4 * ((rank + 1) / 2));
                pos.put(n, point(yearX(n.firstYear, left, right), top + t.index * gap + offset));
            }
        }
        var colors = themeColors();
        renderDetail(png, g -> {
            referencePanel(g, "关键词时间线", r, pos.size(), true);
            ticks(g, left, right, 143, 10, new Color(200, 65, 181));
            referenceLegend(g);
            for (Theme t : themes) {
                int first = t.nodes.stream().mapToInt(n -> n.firstYear).min().orElse(from);
                int last = t.nodes.stream().flatMap(n -> n.countsByYear.keySet().stream()).mapToInt(y -> y).max().orElse(to);
                double y = top + t.index * gap;
                line(g, yearX(first, left, right), y, yearX(last, left, right), y, alpha(t.color, 215), 1.0);
                g.setFont(font(13, true, false));
                g.setColor(themeInk(t.color));
                g.drawString(ycscite.I18n.text(fit(g, themeName(t), W - right - 24)), right + 15, (float)y + 4);
            }
            // A consistent arch on the earlier-to-later side preserves the long cross-row links.
            for (var e : r.network.edges) {
                var a = pos.get(e.a); var b = pos.get(e.b);
                if (a == null || b == null) continue;
                if (a.x > b.x) { var swap = a; a = b; b = swap; }
                double distance = Math.abs(b.x - a.x);
                double lift = Math.min(130, distance * .23 + Math.abs(b.y - a.y) * .08);
                g.setColor(alpha(referenceYear(e.firstYear > 0 ? e.firstYear : Math.max(e.a.firstYear, e.b.firstYear)), 30));
                g.setStroke(new BasicStroke(.65f));
                g.draw(new QuadCurve2D.Double(a.x, a.y, (a.x + b.x) / 2,
                        Math.min(a.y, b.y) - lift, b.x, b.y));
            }
            referenceNodes(g, pos, Map.of(), Look.KEYWORDS);
            List<Rectangle2D> labels = new ArrayList<>();
            for (Theme t : themes) {
                int limit = t.nodes.size() < 7 ? 1 : 4;
                for (GraphNode n : t.nodes.subList(0, Math.min(limit, t.nodes.size()))) {
                    var p = pos.get(n);
                    int size = 9 + (int)Math.min(15, 2.8 * Math.sqrt(n.weight));
                    referenceLabel(g, n.label, point(p.x, p.y + 15), size, Color.BLACK, labels, false);
                }
            }
        });
    }

    void landscape(Path png) throws IOException {
        int left = 102, right = 1070, top = 156, bottom = 758;
        double gap = (bottom - top) / (double)Math.max(1, themes.size() - 1);
        Map<Theme, Map<Integer, Integer>> counts = new LinkedHashMap<>();
        for (Theme t : themes) {
            Map<Integer, Integer> activity = new TreeMap<>();
            for (GraphNode n : t.nodes)
                n.countsByYear.forEach((y, value) -> activity.merge(y, value, Integer::sum));
            counts.put(t, activity);
        }
        renderDetail(png, g -> {
            g.setFont(font(10, false, false));
            g.setColor(INK);
            g.drawString(ycscite.I18n.text("聚类演化山峰 · 各簇年度关键词频次（各行按自身峰值归一化）"), 16, 19);
            ticks(g, left, right, 66, 10, new Color(24, 142, 143));
            empty(g, themes.isEmpty(), "没有可展示的聚类");
            for (Theme t : themes) {
                Map<Integer, Integer> activity = counts.get(t);
                if (activity.isEmpty()) continue;
                int first = Collections.min(activity.keySet()), last = Collections.max(activity.keySet());
                int peak = activity.values().stream().mapToInt(i -> i).max().orElse(1);
                double y0 = top + t.index * gap;
                Path2D path = new Path2D.Double();
                path.moveTo(yearX(first, left, right), y0);
                for (int y = first; y <= last; y++) {
                    double h = gap * .59 * activity.getOrDefault(y, 0) / Math.max(1.0, peak);
                    double x = yearX(y, left, right);
                    if (first == last) path.lineTo(x - 6, y0);
                    path.lineTo(x, y0 - h);
                    if (first == last) path.lineTo(x + 6, y0);
                }
                path.lineTo(yearX(last, left, right), y0);
                path.closePath();
                g.setColor(t.color);
                g.fill(path);
                g.setStroke(new BasicStroke(.65f));
                g.setColor(themeInk(t.color));
                g.draw(path);
                g.setFont(font(14, true, false));
                g.drawString(ycscite.I18n.text(fit(g, themeName(t), W - right - 27)), right + 19, (float)y0 + 4);
            }
        });
        var table = new StringBuilder("display_cluster\tyear\tkeyword_occurrences\n");
        for (Theme t : themes) for (int y = from; y <= to; y++)
            table.append(t.index).append('\t').append(y).append('\t')
                    .append(counts.get(t).getOrDefault(y, 0)).append('\n');
        Files.writeString(png.getParent().resolve("data/landscape.tsv"), table);
    }

    void bursts(Project p, Pipeline.Result r, Path png, Path tsv) throws IOException {
        Map<String, Integer> firstSeen = new HashMap<>();
        for (var record : p.records)
            for (String word : record.allKeywords())
                firstSeen.merge(word.strip().toUpperCase(Locale.ROOT), record.year, Math::min);
        List<BurstRow> rows = new ArrayList<>();
        for (GraphNode n : r.burstRows) {
            BurstRow best = null;
            for (int i = 0; i < n.burstIntervals.size(); i++) {
                int[] b = n.burstIntervals.get(i);
                if (b[1] - b[0] + 1 < settings.integer("burst_min_years")) continue;
                double s = i < n.intervalStrengths.size() ? n.intervalStrengths.get(i) : n.burstStrength;
                if (best == null || s > best.strength)
                    best = new BurstRow(n, firstSeen.getOrDefault(n.id, n.firstYear), b[0], b[1], s);
            }
            if (best != null) rows.add(best);
        }
        rows.sort(Comparator.comparingDouble(BurstRow::strength).reversed().thenComparing(b -> b.node.id));
        if (rows.size() > 25) rows.subList(25, rows.size()).clear();
        rows.sort(Comparator.comparingInt(BurstRow::begin).thenComparingInt(BurstRow::end).thenComparing(b -> b.node.id));
        StringBuilder data = new StringBuilder("keyword\tfirst_year\tstrength\tbegin\tend\n");
        for (var row : rows) data.append(String.format(Locale.ROOT, "%s\t%d\t%.4f\t%d\t%d%n",
                row.node.label, row.first, row.strength, row.begin, row.end));
        Files.writeString(tsv, data);
        int width = 1150, height = 148 + Math.max(2, rows.size()) * 32;
        render(png, width, height, g -> {
            g.setColor(INK);
            g.setFont(font(32, true, false));
            center(g, "关键词突现 Top " + settings.integer("burst_top"), width / 2.0, 51);
            int left = 635, right = 1100;
            g.setFont(font(17, true, false));
            g.drawString(ycscite.I18n.text("关键词"), 43, 106);
            for (int i = 0; i < 4; i++) center(g, List.of("首现", "强度", "开始", "结束").get(i), 310 + i * 76, 106);
            center(g, from + " – " + to, (left + right) / 2.0, 106);
            line(g, 40, 119, right, 119, GRID, 1);
            empty(g, rows.isEmpty(), "未检测到关键词突现");
            double cell = (right - left) / (double)(to - from + 1);
            for (int i = 0; i < rows.size(); i++) {
                BurstRow row = rows.get(i);
                int y = 146 + i * 32;
                int termSize = 19;
                g.setFont(font(termSize, false, false));
                while (termSize > 11 && g.getFontMetrics().stringWidth(row.node.label) > 235)
                    g.setFont(font(--termSize, false, false));
                g.setColor(INK);
                g.drawString(ycscite.I18n.text(fit(g, row.node.label, 235)), 43, y + 4);
                g.setFont(font(19, false, false));
                center(g, Integer.toString(row.first), 310, y + 4);
                center(g, String.format(Locale.ROOT, "%.2f", row.strength), 386, y + 4);
                center(g, Integer.toString(row.begin), 462, y + 4);
                center(g, Integer.toString(row.end), 538, y + 4);
                line(g, left, y, right, y, new Color(206, 232, 231), 4);
                line(g, left + (row.first - from) * cell, y, right, y, new Color(85, 183, 180), 4);
                line(g, left + (row.begin - from) * cell, y, left + (row.end - from + 1) * cell, y,
                        new Color(218, 27, 33), 6);
            }
        });
    }

    private void referenceEdges(Graphics2D g, Network net, Map<GraphNode, Point2D.Double> pos,
                                Map<GraphNode, Color> theme, Look look) {
        for (var e : net.edges) {
            Point2D.Double a = pos.get(e.a), b = pos.get(e.b);
            if (a == null || b == null) continue;
            if (a.x > b.x || (a.x == b.x && a.y > b.y)) { var p = a; a = b; b = p; }
            int year = e.firstYear > 0 ? e.firstYear : Math.max(e.a.firstYear, e.b.firstYear);
            boolean across = e.a.cluster != e.b.cluster;
            Color c = look == Look.CLUSTERS && !across ? theme.get(e.a) : referenceYear(year);
            double weighted = Math.log1p(e.weight);
            int opacity = switch (look) {
                case KEYWORDS -> 43;
                case TIMEZONE -> 29;
                case CLUSTERS -> across ? 5 : 126;
                case AUTHORS, INSTITUTIONS -> 105;
            };
            g.setColor(alpha(c, (int)Math.round(settings.number("link_opacity") * (opacity + (look == Look.CLUSTERS && across ? 0 : Math.min(30, (int)(weighted * 8)))))));
            double width = (look == Look.CLUSTERS && !across ? 1.08 : .78) + Math.min(.9, .28 * weighted);
            if (look == Look.CLUSTERS && across) width *= .69;
            g.setStroke(new BasicStroke((float)width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double dx = b.x - a.x, dy = b.y - a.y;
            double bend = look == Look.TIMEZONE ? .32 : look == Look.CLUSTERS ? .12 : .25;
            double cx = (a.x + b.x) / 2 - dy * bend, cy = (a.y + b.y) / 2 + dx * bend;
            if (look == Look.TIMEZONE) cy = Math.min(773, cy);
            g.draw(new QuadCurve2D.Double(a.x, a.y, cx, cy, b.x, b.y));
        }
    }

    private double radius(GraphNode n, Look look) {
        double root = Math.sqrt(n.weight);
        return settings.number("node_scale") * switch (look) {
            case KEYWORDS -> Math.min(30, .36 + 1.70 * root);
            case TIMEZONE -> Math.min(110, 1.05 + .53 * n.weight);
            case CLUSTERS -> Math.min(12, 1.01 + .83 * root);
            case AUTHORS -> Math.min(26, 5.0 + 2.25 * root);
            case INSTITUTIONS -> Math.min(27, 6.4 + .72 * Math.pow(Math.max(0, n.weight - 1), .86));
        };
    }

    private void referenceNodes(Graphics2D g, Map<GraphNode, Point2D.Double> pos, Map<GraphNode, Color> theme, Look look) {
        boolean collaboration = look == Look.AUTHORS || look == Look.INSTITUTIONS;
        for (GraphNode n : pos.keySet().stream().sorted(IMPORTANCE.reversed()).toList()) {
            Point2D.Double p = pos.get(n);
            double r = radius(n, look);
            Color cluster = theme.get(n);
            if (cluster != null) {
                circle(g, p.x, p.y, r, alpha(cluster, 213));
                ring(g, p, r, alpha(cluster.darker().darker(), 220), Math.min(1.25, .51 + r * .06));
                continue;
            }
            int total = n.countsByYear.values().stream().mapToInt(i -> i).sum();
            Color outline = referenceYear(n.countsByYear.isEmpty() ? n.firstYear : Collections.max(n.countsByYear.keySet()));
            if (total == 0) circle(g, p.x, p.y, r, alpha(outline, 200));
            else {
                double outer = r;
                int remaining = total;
                // kh uses the newest year on the outside. Radial thickness is proportional to frequency.
                for (var e : n.countsByYear.entrySet().stream()
                        .sorted(Map.Entry.<Integer, Integer>comparingByKey().reversed()).toList()) {
                    remaining -= e.getValue();
                    double inner = r * remaining / total;
                    Path2D band = new Path2D.Double(Path2D.WIND_EVEN_ODD);
                    band.append(new Ellipse2D.Double(p.x - outer, p.y - outer, outer * 2, outer * 2), false);
                    if (inner > 0) band.append(new Ellipse2D.Double(p.x - inner, p.y - inner, inner * 2, inner * 2), false);
                    g.setColor(alpha(referenceYear(e.getKey()), collaboration ? 203 : 240));
                    g.fill(band);
                    outer = inner;
                }
            }
            if (!collaboration && n.centrality >= .10)
                ring(g, p, r, new Color(249, 0, 242), Math.min(7.0, 1.6 + 14 * n.centrality));
            else ring(g, p, r, alpha(outline.darker(), 195), collaboration ? 1.46 : .71);
        }
    }

    private void referenceLabels(Graphics2D g, Map<GraphNode, Point2D.Double> pos, Look look) {
        boolean collaboration = look == Look.AUTHORS || look == Look.INSTITUTIONS;
        int budget = switch (look) {
            case TIMEZONE -> 38;
            case KEYWORDS -> settings.integer("label_budget");
            case INSTITUTIONS -> 13;
            default -> 21;
        };
        int largest = look == Look.KEYWORDS ? 37 : look == Look.TIMEZONE ? 30 : look == Look.INSTITUTIONS ? 41 : 39;
        var ordered = pos.keySet().stream().sorted(IMPORTANCE).toList();
        if (ordered.isEmpty()) return;
        int max = Math.max(1, ordered.getFirst().weight);
        List<Rectangle2D> used = new ArrayList<>();
        for (GraphNode n : ordered) {
            if (used.size() >= budget || n.weight < (collaboration ? 2 : 3)) break;
            Point2D.Double p = pos.get(n);
            double pow = look == Look.TIMEZONE ? .40 : .32;
            int size = 10 + (int)((largest - 10) * Math.pow(n.weight / (double)max, pow));
            double lift = look == Look.TIMEZONE ? radius(n, look) * .10 : Math.min(8, radius(n, look) * .48);
            referenceLabel(g, n.label, point(p.x, p.y - lift), (int)(size * settings.number("label_scale")), Color.BLACK, used, false);
        }
    }

    private static void referenceLabel(Graphics2D g, String text, Point2D.Double p, int size,
                                       Color color, List<Rectangle2D> used, boolean cluster) {
        g.setFont(font(size, true, !cluster));
        int limit = cluster ? 242 : 590;
        while (size > 12 && g.getFontMetrics().stringWidth(text) > limit) g.setFont(font(--size, true, !cluster));
        text = fit(g, text, limit);
        FontMetrics fm = g.getFontMetrics();
        int width = fm.stringWidth(text);
        double rise = size * .28;
        double[][] shifts = {{-width / 2.0, rise}, {-width / 2.0, -size * .63}, {-width / 2.0, size * 1.27},
                {9, rise}, {-width - 9, rise}, {18 - width / 2.0, -size * 1.5}, {-width / 2.0, size * 2.04}};
        for (var shift : shifts) {
            double x = p.x + shift[0], y = p.y + shift[1];
            Rectangle2D bounds = new Rectangle2D.Double(x - 3, y - size * .88, width + 6, size * 1.16);
            if (bounds.getMinX() < 98 || bounds.getMaxX() > W - 20 || bounds.getMinY() < 53
                    || bounds.getMaxY() > H - 21 || used.stream().anyMatch(bounds::intersects)) continue;
            if (cluster) {
                g.setColor(new Color(255, 255, 205, 217));
                g.fill(new RoundRectangle2D.Double(x - 3, y - size * .92, width + 6, size * 1.20, 4, 4));
            } else {
                var glyph = g.getFont().createGlyphVector(g.getFontRenderContext(), text).getOutline((float)x, (float)y);
                g.setColor(new Color(255, 255, 255, 143));
                g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(glyph);
            }
            g.setColor(color);
            g.drawString(ycscite.I18n.text(text), (float)x, (float)y);
            used.add(bounds);
            return;
        }
    }

    private void referencePanel(Graphics2D g, String name, Pipeline.Result r, int shown, boolean quality) {
        g.setFont(font(12, true, false));
        g.setColor(INK);
        g.drawString(ycscite.I18n.text(name + " · Ycscite"), 14, 17);
        g.setFont(font(9, false, false));
        g.drawString(ycscite.I18n.text(from + "–" + to + (settings.english() ? " (slice=" : "（片长=") + settings.integer("slice_years") + (settings.english() ? ")" : "年）")), 14, 29);
        g.drawString(ycscite.I18n.text(settings.text("selection") + " k=" + settings.number("g_index_k") + " · " + settings.text("pruning")), 14, 40);
        int n = r.network.nodes.size(), e = r.network.edges.size();
        double density = n < 2 ? 0 : 2.0 * e / ((double)n * (n - 1));
        g.drawString(ycscite.I18n.text(String.format(Locale.ROOT, "%d 节点 / %d 边 · 密度 %.4f", n, e, density)), 14, 51);
        if (quality) g.drawString(ycscite.I18n.text(String.format(Locale.ROOT, "模块度 Q=%.4f%s", r.modularity,
                shown == n ? "" : " · 显示 " + shown + " 节点")), 14, 62);
        empty(g, shown == 0, "没有可绘制的数据");
    }

    private Color referenceYear(int year) {
        double t = from == to ? 1 : Math.clamp((year - (double)from) / (to - from), 0, 1);
        return new Color((float)Math.min(1, Math.abs(2 * t - .5)), (float)Math.sin(Math.PI * t),
                (float)Math.cos(Math.PI * t / 2));
    }

    private void referenceLegend(Graphics2D g) {
        double left = 17, top = 568, width = 28, height = 185;
        int step = tickStep(31);
        int count = (to - from) / step + 1;
        double h = height / count;
        for (int i = 0; i < count; i++) {
            int year = to - i * step;
            Color c = referenceYear(year);
            double y = top + i * h;
            g.setColor(c);
            g.fill(new Rectangle2D.Double(left, y, width, h + .23));
            g.setFont(font((int)Math.clamp(h * .78, 5, 8), true, false));
            g.setColor(c.getRed() + c.getGreen() * 2 + c.getBlue() > 425 ? INK : Color.WHITE);
            center(g, Integer.toString(year), left + width / 2, y + h * .79);
        }
    }

    private void edges(Graphics2D g, Network net, Map<GraphNode, Point2D.Double> pos, boolean timeline,
                       Map<GraphNode, Color> theme, int opacity) {
        for (var e : net.edges) {
            var a = pos.get(e.a);
            var b = pos.get(e.b);
            if (a == null || b == null) continue;
            GraphNode earlier = e.a.firstYear <= e.b.firstYear ? e.a : e.b;
            int later = Math.max(e.a.firstYear, e.b.firstYear);
            Color c = theme.getOrDefault(earlier, yearColor(later));
            int alpha = opacity + Math.min(24, (int)(3 * Math.sqrt(e.weight)));
            if (!theme.isEmpty() && e.a.cluster != e.b.cluster) alpha /= timeline ? 4 : 9;
            g.setColor(alpha(c, alpha));
            g.setStroke(new BasicStroke((float)Math.min(1.05, .47 + .11 * Math.sqrt(e.weight))));
            double dx = b.x - a.x, dy = b.y - a.y;
            double cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
            if (timeline) cy = Math.min(a.y, b.y) - Math.min(174, 12 + .29 * Math.abs(dx));
            else { cx -= Math.copySign(1, dx) * dy * .12; cy += Math.abs(dx) * .12; }
            g.draw(new QuadCurve2D.Double(a.x, a.y, cx, cy, b.x, b.y));
        }
    }

    private void nodes(Graphics2D g, Map<GraphNode, Point2D.Double> pos, Map<GraphNode, Color> theme,
                       double base, double scale, double max, double power) {
        var ordered = pos.keySet().stream().sorted(IMPORTANCE.reversed()).toList();
        for (GraphNode n : ordered) {
            Point2D.Double p = pos.get(n);
            double r = Math.max(1, Math.min(max, base + Math.pow(n.weight, power) * scale));
            Color cluster = theme.get(n);
            if (cluster != null) circle(g, p.x, p.y, r, alpha(cluster, 190));
            else {
                int total = n.countsByYear.values().stream().mapToInt(Integer::intValue).sum();
                double remaining = total;
                for (var e : n.countsByYear.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByKey().reversed()).toList()) {
                    Color col = yearColor(e.getKey());
                    circle(g, p.x, p.y, Math.max(.65, r * Math.sqrt(remaining / total)), alpha(col, base >= 4 ? 182 : 255));
                    remaining -= e.getValue();
                }
                if (n.centrality >= .1) ring(g, p, r, new Color(177, 46, 184), Math.min(3, .7 + 6 * n.centrality));
                else ring(g, p, r, alpha(yearColor(n.firstYear).darker(), 170), .55);
            }
        }
    }

    private void labels(Graphics2D g, Map<GraphNode, Point2D.Double> pos, int budget, int maxFont,
                        boolean timeline, int minWeight) {
        var ordered = pos.keySet().stream().sorted(IMPORTANCE).limit(budget * 2L).toList();
        int maxWeight = ordered.isEmpty() ? 1 : Math.max(1, ordered.getFirst().weight);
        List<Rectangle2D> used = new ArrayList<>();
        for (GraphNode n : ordered) {
            if (used.size() >= budget || n.weight < minWeight) break;
            Point2D.Double p = pos.get(n);
            int size = 13 + (int)((maxFont - 13) * Math.sqrt(n.weight / (double)maxWeight));
            annotate(g, n.label, p.x, p.y, size, INK, used, false, timeline ? 2 : 0);
        }
    }

    private static void annotate(Graphics2D g, String text, double x, double y, int size,
                                 Color color, List<Rectangle2D> used, boolean cluster, int mode) {
        g.setFont(font(size, true, true));
        if (!cluster && text.length() > 10) {
            while (g.getFontMetrics().stringWidth(text) > 465 && size > 13)
                g.setFont(font(--size, true, true));
        }
        text = fit(g, text, cluster ? 190 : 465);
        FontMetrics fm = g.getFontMetrics();
        int width = fm.stringWidth(text);
        double[][] shifts = cluster ? new double[][]{{-width / 2.0, 4}, {-width / 2.0, -18}, {-width / 2.0, 23}}
                : new double[][]{{9, 5}, {-width - 9, 5}, {-width / 2.0, -15}, {-width / 2.0, size + 12},
                                 {17, -size}, {-width - 17, size + 12}};
        for (double[] d : shifts) {
            double xx = x + d[0], yy = y + d[1];
            Rectangle2D b = new Rectangle2D.Double(xx - 3, yy - fm.getAscent(), width + 6, fm.getHeight() + 2);
            if (b.getMinX() < 100 || b.getMaxX() > (mode == 2 ? 1100 : W - 12)
                    || b.getMinY() < (mode == 2 ? 180 : 82) || b.getMaxY() > H - 29
                    || used.stream().anyMatch(b::intersects)) continue;
            g.setColor(new Color(255, 255, 255, cluster ? 216 : 184));
            var outline = g.getFont().createGlyphVector(g.getFontRenderContext(), text).getOutline((float)xx, (float)yy);
            g.setStroke(new BasicStroke(cluster ? 4f : 3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(outline);
            g.setColor(color);
            g.drawString(ycscite.I18n.text(text), (float)xx, (float)yy);
            used.add(b);
            return;
        }
    }

    private void panel(Graphics2D g, String title, Pipeline.Result r, int shown) {
        title(g, title);
        g.setFont(font(13, false, false));
        g.setColor(INK);
        g.drawString(ycscite.I18n.text(from + "–" + to + " · 每年一片 · g-index k=25 · 无剪枝"), 28, 57);
        g.drawString(ycscite.I18n.text(String.format(Locale.ROOT, "%d 节点 / %d 边 · 模块度 Q=%.4f%s", r.network.nodes.size(),
                r.network.edges.size(), r.modularity, shown == r.network.nodes.size() ? "" : " · 显示 " + shown + " 节点")), 28, 77);
        empty(g, shown == 0, "没有可绘制的数据");
    }

    private Map<GraphNode, Color> themeColors() {
        Map<GraphNode, Color> colors = new HashMap<>();
        for (Theme t : themes) for (GraphNode n : t.nodes) colors.put(n, t.color);
        return colors;
    }

    private void themeLegend(Graphics2D g) {
        int visible = Math.min(10, themes.size());
        int y = H - 54 - visible * 18;
        for (Theme t : themes.subList(0, visible)) {
            g.setColor(t.color);
            g.fillRect(17, y, 30, 18);
            g.setColor(INK);
            g.setFont(font(12, true, false));
            center(g, "#" + t.index, 32, y + 13);
            y += 18;
        }
    }

    void themeTable(Path path) throws IOException {
        var text = new StringBuilder("display_cluster\traw_cluster\tsize\tlabel\n");
        for (Theme t : themes) text.append(t.index).append('\t').append(t.id).append('\t')
                .append(t.nodes.size()).append('\t').append(t.name.replace('\t', ' ')).append('\n');
        Files.writeString(path, text);
    }

    private void yearLegend(Graphics2D g) {
        g.setFont(font(11, false, false));
        g.setColor(INK);
        g.drawString(ycscite.I18n.text("年份"), 29, H - 246);
        for (int i = 0; i < 198; i++) line(g, 29, H - 230 + i, 47, H - 230 + i, spectrum((1 - i / 197f) * .99f), 1);
        for (int i = 0; i < 5; i++) {
            g.setColor(INK);
            g.drawString(ycscite.I18n.text(Integer.toString(to - Math.round(i * (to - from) / 4f))), 54, H - 226 + i * 48);
            if (from == to) break;
        }
    }

    private void ticks(Graphics2D g, int left, int right, int y, int target, Color color) {
        g.setFont(font(13, true, false));
        g.setColor(color);
        int step = tickStep(target);
        for (int year = from; year <= to; year += step)
            if (year == from || year == to || to - year >= step)
                center(g, Integer.toString(year), yearX(year, left, right), y);
        if ((to - from) % step != 0) center(g, Integer.toString(to), yearX(to, left, right), y);
    }

    private int tickStep(int target) { return Math.max(1, (int)Math.ceil((to - from + 1) / (double)target)); }
    private double yearX(int y, int left, int right) {
        return to == from ? (left + right) / 2.0 : left + (right - left) * (y - from) / (double)(to - from);
    }
    private Color yearColor(int year) { return spectrum(.99f * (to == from ? 1 : (year - from) / (float)(to - from))); }
    private static Color spectrum(float p) { return Color.getHSBColor(.78f * (1 - Math.clamp(p, 0f, 1f)), .79f, .96f); }
    private static Color themeInk(Color c) { return new Color((int)(c.getRed() * .65), (int)(c.getGreen() * .57), (int)(c.getBlue() * .66)); }
    private static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }
    private static String themeName(Theme t) { return "#" + t.index + " " + t.name; }
    private static Point2D.Double point(double x, double y) { return new Point2D.Double(x, y); }
    private static final BufferedImage LOGO = loadLogo();
    private static BufferedImage loadLogo() {
        try (var stream = NineFigureViews.class.getResourceAsStream("/ycscite-logo.png")) {
            return stream == null ? null : ImageIO.read(stream);
        } catch (IOException e) { return null; }
    }
    private static final String SANS = family("Microsoft YaHei", Font.SANS_SERIF);
    private static final String KAI = family("KaiTi", SANS);
    private static String family(String wanted, String fallback) {
        Font f = new Font(wanted, Font.PLAIN, 14);
        return f.getFamily(Locale.ROOT).equalsIgnoreCase(wanted) && f.canDisplay('图') ? wanted : fallback;
    }
    private static Font font(int size, boolean bold, boolean kai) {
        return new Font(kai ? KAI : SANS, bold ? Font.BOLD : Font.PLAIN, size);
    }
    private static String fit(Graphics2D g, String text, int limit) {
        var fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= limit) return text;
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end) + "…") > limit)
            end = text.offsetByCodePoints(end, -1);
        return text.substring(0, end) + "…";
    }
    private static void title(Graphics2D g, String s) {
        g.setFont(font(24, true, false));
        g.setColor(INK);
        g.drawString(ycscite.I18n.text(s), 28, 36);
    }
    private static void empty(Graphics2D g, boolean empty, String s) {
        if (!empty) return;
        g.setFont(font(20, false, false));
        g.setColor(INK);
        g.drawString(ycscite.I18n.text(s), 170, 145);
    }
    private static void center(Graphics2D g, String s, double x, double y) {
        g.drawString(ycscite.I18n.text(s), (float)(x - g.getFontMetrics().stringWidth(s) / 2.0), (float)y);
    }
    private static void line(Graphics2D g, double x, double y, double x2, double y2, Color c, double width) {
        g.setColor(c);
        g.setStroke(new BasicStroke((float)width));
        g.draw(new Line2D.Double(x, y, x2, y2));
    }
    private static void circle(Graphics2D g, double x, double y, double r, Color c) {
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
    }
    private static void ring(Graphics2D g, Point2D.Double p, double r, Color c, double width) {
        g.setColor(c);
        g.setStroke(new BasicStroke((float)width));
        g.draw(new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r));
    }
    private void render(Path path, int width, int height, Consumer<Graphics2D> paint) throws IOException {
        render(path, width, height, settings.integer("image_scale"), paint);
    }

    private void renderDetail(Path path, Consumer<Graphics2D> paint) throws IOException {
        render(path, W, H, settings.integer("image_scale"), paint);
    }

    private void render(Path path, int width, int height, int scale, Consumer<Graphics2D> paint) throws IOException {
        var image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(scale, scale);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            paint.accept(g);
        } finally { g.dispose(); }
        if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("无法写入 PNG: " + path);
    }
}
