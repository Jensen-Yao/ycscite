package ycscite.ui;

import ycscite.analysis.LLRLabeler;
import ycscite.model.GraphNode;
import ycscite.model.Network;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

/** 右侧面板：节点信息（指标表 + 年度计数条形图）与聚类列表（点击高亮）。 */
public final class InfoPanels {

    /** 单击节点后的信息面板：名称、指标、逐年计数条形图、突现区间。 */
    public static final class NodeInfoPanel extends JPanel {
        private final JLabel title = new JLabel(" ");
        private final JLabel metrics = new JLabel(" ");
        private final JLabel bursts = new JLabel(" ");
        private final YearBars bars = new YearBars();

        public NodeInfoPanel() {
            setLayout(new BorderLayout(6, 6));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(Strings.get("info.node")),
                    BorderFactory.createEmptyBorder(4, 6, 6, 6)));
            title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
            metrics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JPanel top = new JPanel(new GridLayout(0, 1));
            top.add(title);
            top.add(metrics);
            top.add(bursts);
            add(top, BorderLayout.NORTH);
            add(bars, BorderLayout.CENTER);
        }

        public void show(GraphNode n) {
            if (n == null) {
                title.setText(" ");
                metrics.setText(Strings.get("info.hint"));
                bursts.setText(" ");
                bars.setData(null, null);
                return;
            }
            title.setText(n.label);
            metrics.setText(String.format("<html><body style='width:210px'>%s <b>%d</b>  %s <b>#%d</b><br>"
                            + "%s <b>%.3f</b>  PageRank <b>%.4f</b><br>σ <b>%.2f</b>  %s <b>%d</b></body></html>",
                    Strings.get("info.freq"), n.weight, Strings.get("info.cluster"), n.cluster,
                    Strings.get("info.centrality"), n.centrality, n.pageRank,
                    n.sigma, Strings.get("info.halflife"), n.halfLife));
            StringBuilder b = new StringBuilder();
            for (int[] iv : n.burstIntervals)
                b.append(Strings.get("info.burst")).append(' ').append(iv[0]).append('-').append(iv[1]).append("   ");
            bursts.setText(b.toString().isEmpty() ? " " : b.toString());
            List<Integer> years = n.countsByYear.keySet().stream().sorted().toList();
            bars.setData(years, years.stream().map(n.countsByYear::get).toList());
        }
    }

    /** 迷你年度条形图。 */
    private static final class YearBars extends JComponent {
        private List<Integer> years;
        private List<Integer> counts;
        void setData(List<Integer> years, List<Integer> counts) {
            this.years = years;
            this.counts = counts;
            repaint();
        }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (years == null || years.isEmpty()) {
                g0.setColor(new Color(130, 130, 130));
                g0.drawString(Strings.get("info.noyears"), 6, 16);
                return;
            }
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int max = 1;
            for (int c : counts) max = Math.max(max, c);
            int w = Math.max(4, (getWidth() - 16) / years.size());
            int x = 8;
            for (int i = 0; i < years.size(); i++) {
                int h = (int) ((getHeight() - 34) * counts.get(i) / (double) max);
                g.setColor(new Color(78, 121, 167));
                g.fillRect(x, getHeight() - 22 - h, w - 2, h);
                if (years.size() <= 12 || i % (years.size() / 8) == 0) {
                    g.setColor(new Color(150, 150, 150));
                    g.setFont(g.getFont().deriveFont(9f));
                    String y = String.valueOf(years.get(i));
                    g.drawString(y.substring(2), x, getHeight() - 8);
                }
                x += w;
            }
        }
    }

    /** 聚类列表：#编号 规模 轮廓系数 首词；点击高亮该聚类。 */
    public static final class ClusterPanel extends JPanel {
        public interface Highlighter { void highlight(int cluster); }
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);

        public ClusterPanel(Highlighter h) {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder(Strings.get("info.clusters")));
            list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            list.addListSelectionListener(e -> {
                int i = list.getSelectedIndex();
                if (i >= 0 && i < keys.size()) h.highlight(keys.get(i));
            });
            add(new JScrollPane(list), BorderLayout.CENTER);
        }

        private final java.util.List<Integer> keys = new java.util.ArrayList<>();

        public void setData(Network g, Map<Integer, double[]> stats, Map<Integer, List<LLRLabeler.Scored>> labels) {
            model.clear();
            keys.clear();
            if (g == null) return;
            Map<Integer, Integer> sizes = new java.util.TreeMap<>();
            for (GraphNode n : g.nodeCollection()) sizes.merge(n.cluster, 1, Integer::sum);
            for (var en : sizes.entrySet()) {
                double[] st = stats == null ? null : stats.get(en.getKey());
                double sil = st == null ? 0 : st[1];
                double meanY = st == null || st[0] == 0 ? 0 : st[2];
                List<LLRLabeler.Scored> ls = labels.get(en.getKey());
                String top = ls == null || ls.isEmpty() ? "" : ls.get(0).term;
                model.addElement(String.format("#%-2d %3d  S=%.2f  %s  %s",
                        en.getKey(), en.getValue(), sil,
                        meanY > 0 ? String.format("~%.0f", meanY) : "  ?", top));
                keys.add(en.getKey());
            }
        }
    }
}
