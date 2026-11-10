package ycscite;

import ycscite.io.DemoData;
import ycscite.model.Project;
import ycscite.ui.MainFrame;

import javax.swing.*;
import java.awt.Graphics2D;
import java.awt.Robot;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Entry point.
 *   (no args)         launch GUI with empty project
 *   --demo            launch GUI with demo data, auto-run
 *   --smoke-ui PNG    open GUI with demo data, screenshot to PNG, exit (CI/verification)
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        // 美化：Nimbus LAF（Java 内置，零依赖）
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels())
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    // 统一控件圆角与间距
                    UIManager.put("Panel.background", new java.awt.Color(245, 245, 248));
                    UIManager.put("TabbedPane.contentOpaque", true);
                    UIManager.put("Slider.paintValue", false);
                    break;
                }
        } catch (Exception ignored) { }
        if (args.length >= 2 && args[0].equals("--smoke-ui")) {
            Path png = Path.of(args[1]);
            final MainFrame[] hold = new MainFrame[1];
            SwingUtilities.invokeAndWait(() -> {
                MainFrame f = new MainFrame();
                f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                f.setProject(DemoData.generate(120));
                f.runPipeline();
                f.setVisible(true);
                hold[0] = f;
            });
            Thread.sleep(2200);
            // 直接绘制组件树到图像（与屏幕绘制同一条 paint 链路；
            // 不依赖显示会话状态——远程/虚拟桌面下 Robot 抓屏可能拿到空白表面）
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1320, 900, java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            SwingUtilities.invokeAndWait(() -> {
                hold[0].validate();
                hold[0].paint(g);
            });
            g.dispose();
            javax.imageio.ImageIO.write(img, "png", png.toFile());
            SwingUtilities.invokeAndWait(hold[0]::dispose);
            Thread.sleep(300);
            System.exit(0);
            return;
        }
        boolean demo = Arrays.stream(args).anyMatch(a -> a.equals("--demo"));
        var initialInputs=new java.util.ArrayList<String>();String language="zh";
        for(int i=0;i<args.length;i++){
            if(args[i].equals("--input")&&i+1<args.length)initialInputs.add(args[++i]);
            else if(args[i].equals("--lang")&&i+1<args.length)language=args[++i];
        }
        ycscite.ui.Strings.setLang(language.equals("en")?ycscite.ui.Strings.Lang.EN:ycscite.ui.Strings.Lang.ZH);
        Project initial=initialInputs.isEmpty()?null:Cli.loadReportInputs(initialInputs);
        // 启动画面（闪屏 1.2 秒）
        JWindow splash = new JWindow();
        splash.setSize(420, 260);
        splash.setLocationRelativeTo(null);
        var splashImg = new javax.swing.JPanel() {
            @Override protected void paintComponent(java.awt.Graphics g0) {
                super.paintComponent(g0);
                var g = (Graphics2D) g0;
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new java.awt.Color(28, 34, 48));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new java.awt.Color(68, 140, 255));
                g.fillOval(getWidth()/2 - 36, 50, 72, 72);
                g.setColor(java.awt.Color.WHITE);
                g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 28f));
                var fm = g.getFontMetrics();
                String title = "Ycscite";
                g.drawString(title, (getWidth() - fm.stringWidth(title)) / 2, 108);
                g.setFont(g.getFont().deriveFont(java.awt.Font.PLAIN, 13f));
                g.setColor(new java.awt.Color(160, 170, 190));
                String sub = "Ycscite · Interactive workbench";
                fm = g.getFontMetrics();
                g.drawString(sub, (getWidth() - fm.stringWidth(sub)) / 2, 140);
                g.setColor(new java.awt.Color(80, 90, 110));
                g.setFont(g.getFont().deriveFont(java.awt.Font.PLAIN, 10f));
                String ver = "v0.3.0 · Java 21";
                fm = g.getFontMetrics();
                g.drawString(ver, (getWidth() - fm.stringWidth(ver)) / 2, 170);
                // 进度条
                g.setColor(new java.awt.Color(50, 58, 72));
                g.fillRoundRect(60, 200, 300, 6, 3, 3);
                g.setColor(new java.awt.Color(68, 140, 255));
                g.fillRoundRect(60, 200, 80, 6, 3, 3);
            }
        };
        splash.add(splashImg);
        splash.setVisible(true);
        final JWindow[] splashRef = {splash};
        var timer = new javax.swing.Timer(900, e -> splashRef[0].dispose());
        timer.setRepeats(false);
        timer.start();
        SwingUtilities.invokeLater(() -> {
            MainFrame f = new MainFrame();
            f.setVisible(true);
            if(initial!=null){f.setProject(initial);f.runPipeline();}
            else if (demo) {
                f.setProject(DemoData.generate(120));
                f.runPipeline();
            }
        });
    }
}
