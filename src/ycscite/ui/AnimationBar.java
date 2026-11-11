package ycscite.ui;

import ycscite.model.Network;

import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;

/**
 * 年份演化播放条：播放/暂停 + 年份滑块；驱动当前画布的 yearCutoff，
 * 按 CiteSpace 的时间轴动画逐年揭示网络（节点出现、年轮生长）。
 */
public final class AnimationBar extends JPanel {

    private final Supplier<GraphCanvas> canvas;
    private final JButton play = new JButton(Strings.get("anim.play"));
    private final JSlider year = new JSlider();
    private final JLabel yearLabel = new JLabel();
    private Timer timer;
    private int minYear = 0, maxYear = 0;
    private boolean syncing=false;

    public AnimationBar(Supplier<GraphCanvas> canvas) {
        this.canvas = canvas;
        setLayout(new FlowLayout(FlowLayout.LEFT, 8, 2));
        play.addActionListener(e -> toggle());
        year.addChangeListener(e -> {
            GraphCanvas c = canvas.get();
            if (c != null&&!syncing) c.setYearCutoff(year.getValue());
            yearLabel.setText(Strings.get("anim.year") + " " + year.getValue());
        });
        year.setPreferredSize(new Dimension(360, 26));
        add(play);
        add(year);
        add(yearLabel);
        setBorder(BorderFactory.createEtchedBorder());
    }

    /** 由 MainFrame 在每次分析后同步年份范围（滑块先到最右=全部显示）。 */
    public void setRange(Network g) {
        if (g == null || g.minYear == Integer.MAX_VALUE) { year.setEnabled(false); return; }
        minYear = g.minYear;
        maxYear = g.maxYear;
        year.setMinimum(minYear);
        year.setMaximum(maxYear);
        year.setValue(maxYear);
        year.setEnabled(true);
        GraphCanvas c = canvas.get();
        if (c != null) c.setYearCutoff(maxYear);
    }

    /** Display the selected canvas's saved cutoff without changing another canvas. */
    public void syncToCanvas(){var current=canvas.get();if(current==null||maxYear==0)return;syncing=true;try{year.setValue(Math.clamp(current.getYearCutoff(),minYear,maxYear));yearLabel.setText(Strings.get("anim.year")+" "+year.getValue());}finally{syncing=false;}}

    private void toggle() {
        GraphCanvas c = canvas.get();
        if (c == null || maxYear == 0) return;
        if (timer != null && timer.isRunning()) {
            timer.stop();
            play.setText(Strings.get("anim.play"));
            return;
        }
        if (timer == null) {
            timer = new Timer(700, ev -> {
                int next = year.getValue() + 1;
                if (next > maxYear) next = minYear;
                year.setValue(next);
            });
        }
        if (year.getValue() >= maxYear) year.setValue(minYear);
        timer.start();
        play.setText(Strings.get("anim.pause"));
    }
}
