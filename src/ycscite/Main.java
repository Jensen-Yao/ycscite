package ycscite;

import ycscite.io.DemoData;
import ycscite.model.Project;
import ycscite.ui.MainFrame;

import javax.swing.*;
import java.awt.Graphics2D;
import ycscite.io.ProjectStore;
import ycscite.io.NineFigureReport;
import java.nio.file.Files;
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
                    UIManager.put("control",new java.awt.Color(248,245,238));
                    UIManager.put("nimbusBase",new java.awt.Color(209,199,181));
                    UIManager.put("nimbusBlueGrey",new java.awt.Color(227,220,205));
                    UIManager.put("nimbusFocus",new java.awt.Color(174,89,61));
                    UIManager.put("nimbusSelectionBackground",new java.awt.Color(174,89,61));
                    UIManager.put("text",new java.awt.Color(48,46,41));
                    UIManager.setLookAndFeel(info.getClassName());
                    // 统一控件圆角与间距
                    UIManager.put("Panel.background", new java.awt.Color(250, 249, 245));
                    UIManager.put("TabbedPane.contentOpaque", true);
                    UIManager.put("Slider.paintValue", false);
                    var uiFont=new javax.swing.plaf.FontUIResource(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,13);
                    UIManager.put("defaultFont",uiFont);for(Object key:java.util.Collections.list(UIManager.getLookAndFeelDefaults().keys()))if(key.toString().endsWith(".font"))UIManager.put(key,uiFont);
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
        var initialInputs=new java.util.ArrayList<String>();String language=null;Path config=null,projectFile=null,smokeOutput=null;Boolean logoOverride=null;
        for(int i=0;i<args.length;i++){
            switch(args[i]){
                case "--input"->{if(++i==args.length)throw new IllegalArgumentException("--input needs a path");initialInputs.add(args[i]);}
                case "--lang"->{if(++i==args.length)throw new IllegalArgumentException("--lang needs zh or en");language=args[i];}
                case "--config"->{if(++i==args.length)throw new IllegalArgumentException("--config needs a file");config=Path.of(args[i]);}
                case "--project"->{if(++i==args.length)throw new IllegalArgumentException("--project needs a file");projectFile=Path.of(args[i]);}
                case "--smoke-output"->{if(++i==args.length)throw new IllegalArgumentException("--smoke-output needs a directory");smokeOutput=Path.of(args[i]);}
                case "--logo"->{if(Boolean.FALSE.equals(logoOverride))throw new IllegalArgumentException("Choose --logo or --no-logo");logoOverride=true;}
                case "--no-logo"->{if(Boolean.TRUE.equals(logoOverride))throw new IllegalArgumentException("Choose --logo or --no-logo");logoOverride=false;}
                case "--demo"->{}
                default->throw new IllegalArgumentException("Unknown argument: "+args[i]);
            }
        }
        if(projectFile!=null&&(!initialInputs.isEmpty()||config!=null||demo))throw new IllegalArgumentException("--project cannot be combined with input, config or demo");
        final Settings startupSettings=config==null?null:Settings.read(config);
        String chosenLanguage=language!=null?language:startupSettings!=null?startupSettings.text("language"):"zh";
        if(!java.util.Set.of("zh","en").contains(chosenLanguage))throw new IllegalArgumentException("Language must be zh or en");
        ycscite.ui.Strings.setLang(chosenLanguage.equals("en")?ycscite.ui.Strings.Lang.EN:ycscite.ui.Strings.Lang.ZH);
        final ProjectStore.Bundle startupBundle=projectFile==null?null:ProjectStore.load(projectFile);
        Project loaded=initialInputs.isEmpty()?(demo?DemoData.generate(120):null):Cli.loadInputs(initialInputs,startupSettings==null?"auto":startupSettings.text("format"));
        final Project initial=loaded!=null&&startupSettings!=null?NineFigureReport.prepare(loaded,startupSettings).project():loaded;
        final Path diagnostics=smokeOutput;final Boolean requestedLogo=logoOverride;
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
                String ver = "v"+Cli.VERSION+" · Java 21";
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
            MainFrame frame=new MainFrame();
            if(startupBundle!=null)frame.setBundle(startupBundle);
            else {if(initial!=null)frame.setProject(initial);if(startupSettings!=null)frame.applySettings(startupSettings);if(initial!=null)frame.runPipeline();}
            if(requestedLogo!=null)frame.setExportLogo(requestedLogo);
            frame.setVisible(true);
            if(diagnostics!=null){var capture=new javax.swing.Timer(100,event->{if(frame.isRendering())return;((javax.swing.Timer)event.getSource()).stop();try{
                if(frame.renderFailure()!=null)throw new IllegalStateException("Editor layout failed",frame.renderFailure());
                Files.createDirectories(diagnostics);
                var image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=image.createGraphics();frame.paint(graphics);graphics.dispose();javax.imageio.ImageIO.write(image,"png",diagnostics.resolve("editor.png").toFile());
                frame.saveDiagnostics(diagnostics);
                var layout=(java.awt.BorderLayout)frame.getContentPane().getLayout();var west=(javax.swing.JTabbedPane)layout.getLayoutComponent(java.awt.BorderLayout.WEST);west.setSelectedIndex(west.getTabCount()-1);frame.validate();
                var controls=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);var drawing=controls.createGraphics();frame.paint(drawing);drawing.dispose();javax.imageio.ImageIO.write(controls,"png",diagnostics.resolve("export-controls.png").toFile());
                frame.dispose();System.exit(0);
            }catch(Exception error){error.printStackTrace();frame.dispose();System.exit(1);}});capture.start();}
        });
    }
}
