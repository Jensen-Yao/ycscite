package ycscite.analysis;

import ycscite.model.GraphNode;
import ycscite.model.Network;
import javax.imageio.ImageIO;
import java.awt.*;
import java.util.List;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * 轮廓图（原版 "Contour Map" 全套 ph.java:1158-1213）：
 * x=年份、y=聚类带，活动量高斯核密度平滑后画等高线（Bins/分辨率/平滑度/色板可调）。
 */
public final class ContourMap {

    public record Params(int bins, int resolution, int smooth, String colormap, boolean byCitation) {
        public static Params of(int b, int r, int s, String c, boolean bc) {
            return new Params(b, r, s, c, bc);
        }
        public static Params defaults() { return new Params(120, 15, 24, "heatmap", false); }
    }

    public static void render(Network g, Path out, Params p) throws IOException {
        if (g == null || g.minYear == Integer.MAX_VALUE) return;
        int y0 = g.minYear, y1 = g.maxYear, years = y1 - y0 + 1;
        Map<Integer, List<GraphNode>> byCluster = new TreeMap<>();
        for (GraphNode n : g.nodeCollection()) byCluster.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);
        int k = byCluster.size();
        if (k < 2 || years < 2) return;

        // 密度矩阵 [k][years]
        double[][] density = new double[k][years];
        int ci = 0;
        for (var en : byCluster.entrySet()) {
            for (GraphNode n : en.getValue())
                for (var ye : n.countsByYear.entrySet()) {
                    int idx = ye.getKey() - y0;
                    if (idx >= 0 && idx < years) density[ci][idx] += ye.getValue();
                }
            ci++;
        }
        // 高斯平滑
        double[][] smooth = smooth(density, p.smooth());
        // 归一化
        double max = 0;
        for (double[] row : smooth) for (double v : row) max = Math.max(max, v);
        if (max <= 0) return;

        int w = 1400, h = 800;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);
        g2.setColor(new Color(30, 30, 30));
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 16f));
        g2.drawString(ycscite.I18n.text("Contour Map"), 30, 30);

        int left = 100, top = 60, cw = w - left - 60, ch = h - top - 60;
        // 色板渲染（简化版等高线：色块 + 等值线）
        for (int yy = 0; yy < k; yy++) {
            for (int xx = 0; xx < years; xx++) {
                double t = smooth[yy][xx] / max;
                if (t <= 0) continue;
                Color c = colormap(p.colormap(), t);
                int px = left + xx * cw / years, py = top + yy * ch / k;
                int pw = cw / years + 1, ph2 = ch / k + 1;
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(200 * t)));
                g2.fillRect(px, py, pw, ph2);
            }
        }
        // 等值线（每 0.2 一条）
        g2.setColor(new Color(80, 80, 80, 120));
        g2.setStroke(new BasicStroke(0.8f));
        for (int yy = 0; yy < k - 1; yy++)
            for (int xx = 0; xx < years - 1; xx++) {
                double v1 = smooth[yy][xx] / max, v2 = smooth[yy + 1][xx] / max;
                double v3 = smooth[yy][xx + 1] / max;
                for (double thr = 0.2; thr < 1.0; thr += 0.2) {
                    boolean a = v1 >= thr, b = v2 >= thr, c = v3 >= thr;
                    if (a != b || a != c) {
                        int px = left + xx * cw / years, py = top + yy * ch / k;
                        g2.drawOval(px + cw / years / 2, py + ch / k / 2, 3, 3);
                        break;
                    }
                }
            }
        // 轴标签
        g2.setColor(new Color(110, 110, 110));
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        for (int y = y0; y <= y1; y += Math.max(1, years / 12))
            g2.drawString(ycscite.I18n.text(String.valueOf(y)), left + (y - y0) * cw / years - 10, top - 8);
        int i = 0;
        for (var en : byCluster.entrySet()) {
            g2.drawString(ycscite.I18n.text("#" + en.getKey()), 50, top + i * ch / k + ch / k / 2 + 4);
            i++;
        }
        g2.dispose();
        ImageIO.write(img, "png", out.toFile());
    }

    private static double[][] smooth(double[][] src, int sigma) {
        if (sigma <= 0) return src;
        int rows = src.length, cols = src[0].length;
        double[][] out = new double[rows][cols];
        int r = Math.min(sigma, 3);
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) {
                double sum = 0, wsum = 0;
                for (int di = -r; di <= r; di++)
                    for (int dj = -r; dj <= r; dj++) {
                        int ii = i + di, jj = j + dj;
                        if (ii < 0 || ii >= rows || jj < 0 || jj >= cols) continue;
                        double w = Math.exp(-(di * di + dj * dj) / (2.0 * sigma * sigma / 9.0));
                        sum += src[ii][jj] * w;
                        wsum += w;
                    }
                out[i][j] = wsum > 0 ? sum / wsum : 0;
            }
        return out;
    }

    private static Color colormap(String name, double t) {
        return switch (name.toLowerCase()) {
            case "heatmap" -> new Color((int)(255 * t), (int)(80 * (1 - t) + 40), (int)(80 * (1 - t) + 40));
            case "plasma" -> Color.getHSBColor((float)(0.8 - 0.8 * t), 0.8f, (float)(0.3 + 0.7 * t));
            case "viridis" -> Color.getHSBColor((float)(0.55 + 0.2 * t), 0.7f, (float)(0.4 + 0.5 * t));
            case "rainbow" -> Color.getHSBColor((float)(0.7 * (1 - t)), 0.85f, 0.95f);
            default -> new Color((int)(200 * t), (int)(200 * t), (int)(255 * t));
        };
    }
}
