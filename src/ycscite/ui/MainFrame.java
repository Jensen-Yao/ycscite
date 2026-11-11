package ycscite.ui;

import ycscite.analysis.CitationReport;
import ycscite.analysis.LLRLabeler;
import ycscite.analysis.NetworkBuilders;
import ycscite.analysis.Pipeline;
import ycscite.io.CnkiParser;
import ycscite.io.Branding;
import ycscite.io.CssciParser;
import ycscite.io.CrossRefClient;
import ycscite.io.DemoData;
import ycscite.io.DerwentParser;
import ycscite.io.DimensionsCsvParser;
import ycscite.io.KciParser;
import ycscite.io.NsfAwardsParser;
import ycscite.io.ProQuestRisParser;
import ycscite.io.DocxExporter;
import ycscite.io.Exporters;
import ycscite.io.MySqlStore;
import ycscite.io.NineFigureReport;
import ycscite.io.PdfExporter;
import ycscite.io.ProjectStore;
import ycscite.io.MiniJson;
import ycscite.io.ViewState;
import ycscite.io.PubMedParser;
import ycscite.io.ScopusCsvParser;
import ycscite.io.WoSParser;
import ycscite.model.Project;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 主窗口（默认中文，可切换 English）：数据加载、分析控制、三个 CiteSpace 视图、引文报告。 */
public final class MainFrame extends JFrame {

    private Project project;
    private Pipeline.Result result;
    private Pipeline.Options inheritedOptions=new Pipeline.Options();
    private Pipeline.Options analyzedOptions=new Pipeline.Options();
    private final JComboBox<String> networkKind = new JComboBox<>();
    private final JComboBox<String> clusterAlgo = new JComboBox<>();
    private final JComboBox<String> labelAlgo = new JComboBox<>();
    private final JComboBox<String> labelSource = new JComboBox<>();
    private final JSpinner topN = new JSpinner(new SpinnerNumberModel(50, 0, 100000, 10));
    private final JSlider labelSlider = new JSlider(0, 150, 30);
    private final JComboBox<String> sizeMode = new JComboBox<>();
    private final JTextField search = new JTextField(12);
    private final GraphCanvas timeline = new GraphCanvas();
    private final GraphCanvas timezone = new GraphCanvas();
    private final GraphCanvas merged = new GraphCanvas();
    private final JCheckBox fisheyeCheck = new JCheckBox(Strings.get("disp.fisheye"));
    private final JCheckBox hullCheck = new JCheckBox(Strings.get("disp.hull"));
    private final JCheckBox shadowCheck = new JCheckBox(Strings.get("disp.shadow"));
    private final JComboBox<String> shapeBox = new JComboBox<>();
    private final JSpinner fontSpin = new JSpinner(new SpinnerNumberModel(11.0, 6.0, 24.0, .5));
    private final JCheckBox linkLabelsCheck = new JCheckBox(Strings.get("disp.linklabels"));
    private final JCheckBox clusterIdCheck = new JCheckBox(Strings.get("disp.clusterid"));
    private final JCheckBox citeCountCheck = new JCheckBox(Strings.get("disp.citecount"));
    private final JCheckBox labelClusterColorCheck = new JCheckBox(Strings.get("disp.labelclustercolor"));
    private final JSlider rescaleSlider = new JSlider(25, 400, 100);
    private final JSpinner kcoreSpin = new JSpinner(new SpinnerNumberModel(0, 0, 10, 1));
    private final JCheckBox linkMonoCheck = new JCheckBox(Strings.get("disp.linkmono"));
    private final JComboBox<String> fillPatternBox = new JComboBox<>();
    private final JButton labelBgBtn = new JButton(Strings.get("disp.labelbg"));
    private final JCheckBox curvedCheck = new JCheckBox(Strings.get("disp.curved"));
    private final JCheckBox clusterLinkCheck = new JCheckBox(Strings.get("disp.clusterlink"));
    private final JCheckBox effects3dCheck = new JCheckBox(Strings.get("disp.3d"));
    private final JComboBox<Integer> angleBox = new JComboBox<>(new Integer[]{0, 15, 30, 45, 60, 90});
    private final GraphCanvas tsne = new GraphCanvas();
    private final GraphCanvas circular = new GraphCanvas();
    private final GraphCanvas heatmap = new GraphCanvas();
    private final GraphCanvas landscape = new GraphCanvas();
    private final GraphCanvas umap = new GraphCanvas();
    private final GraphCanvas circlePack = new GraphCanvas();
    private final JTextArea report = new JTextArea();
    private final JCheckBox includeLogo=new JCheckBox(Strings.get("export.logo"),true);
    private final JTabbedPane tabs = new JTabbedPane();
    private final AnimationBar anim = new AnimationBar(this::currentCanvas);
    // 新增选项控件：时间范围 / 片长 / 每片 Top N / 剪枝 / 最小聚类
    private final JSpinner fromYear = new JSpinner(new SpinnerNumberModel(2000, 0, 3000, 1));
    private final JSpinner toYear = new JSpinner(new SpinnerNumberModel(2030, 0, 9999, 1));
    private final JSpinner sliceYears = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JSpinner topPerSlice = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 5));
    private final JComboBox<String> pruningBox = new JComboBox<>();
    private final JComboBox<String> layoutAlgo = new JComboBox<>();
    private final JComboBox<String> colorPreset = new JComboBox<>(); // 配色方案（原版 ph:1471-1483）
    private final JComboBox<String> selectionBox = new JComboBox<>();
    private final JSpinner gindexK = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 10000.0, 1.0));
    private final JSpinner minLinkStrength = new JSpinner(new SpinnerNumberModel(1, 1, 10000, 1));
    private final JSpinner largestCC = new JSpinner(new SpinnerNumberModel(0, 0, 100000, 1));
    private final JCheckBox singularCheck = new JCheckBox(Strings.get("singular"));
    private final JSpinner minCluster = new JSpinner(new SpinnerNumberModel(1, 1, 10000, 1));
    // 右侧信息面板 / 状态栏 / 撤销栈
    private final InfoPanels.NodeInfoPanel nodeInfo = new InfoPanels.NodeInfoPanel();
    private final InfoPanels.ClusterPanel clusterPanel = new InfoPanels.ClusterPanel(c -> currentCanvas().highlightCluster(c));
    private final JLabel status = new JLabel(Strings.get("status.ready"));
    private record EditState(Pipeline.Result analysis,Pipeline.Options options,Pipeline.Options controls,Map<String,Object> presentation){}
    private final java.util.Deque<EditState> undoStack = new java.util.ArrayDeque<>();
    private Map<String,Object> pendingPresentation=Map.of();
    private boolean syncingDisplayControls=false;

    public MainFrame() {
        super("Ycscite "+ycscite.Cli.VERSION+" · "+Strings.get("app.title"));
        for(GraphCanvas canvas:new GraphCanvas[]{timeline,timezone,merged,tsne,circular,heatmap,landscape,umap,circlePack})canvas.setTheme(GraphCanvas.Theme.LIGHT);
        var logo = MainFrame.class.getResource("/ycscite-logo.png");
        if (logo != null) setIconImage(new javax.swing.ImageIcon(logo).getImage());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setJMenuBar(buildMenu());
        setLayout(new BorderLayout());
        add(buildWest(), BorderLayout.WEST);

        tabs.addTab(Strings.get("view.timeline"), timeline);
        tabs.addTab(Strings.get("view.timezone"), timezone);
        tabs.addTab(Strings.get("view.merged"), merged);
        tabs.addTab(Strings.get("view.tsne"), tsne);
        tabs.addTab(Strings.get("view.circular"), circular);
        tabs.addTab(Strings.get("view.heatmap"), heatmap);
        tabs.addTab(Strings.get("view.landscape"), landscape);
        tabs.addTab(Strings.get("view.umap"), umap);
        tabs.addTab(Strings.get("view.circlepack"), circlePack);
        tabs.setSelectedComponent(merged);

        // 右侧：节点信息 / 聚类列表
        JTabbedPane east = new JTabbedPane();
        east.addTab(Strings.get("info.node"), nodeInfo);
        east.addTab(Strings.get("info.clusters"), clusterPanel);
        east.setPreferredSize(new Dimension(250, 0));
        add(east, BorderLayout.EAST);
        timeline.setSelectionListener(nodeInfo::show);
        timezone.setSelectionListener(nodeInfo::show);
        merged.setSelectionListener(nodeInfo::show);
        // 施引文献窗（审计 B11）：选词即列出含该术语的记录表（可排序），支持导出 RIS
        javax.swing.JTable citingTable = new javax.swing.JTable();
        east.addTab(Strings.get("info.citing"), new javax.swing.JScrollPane(citingTable));
        java.util.function.Consumer<ycscite.model.GraphNode> citingFill = n -> {
            if (n == null || project == null) { citingTable.setModel(new javax.swing.table.DefaultTableModel()); return; }
            String q = n.label.toLowerCase();
            java.util.List<Object[]> rows = new java.util.ArrayList<>();
            for (var r : project.records)
                if (r.allKeywords().stream().anyMatch(k -> k.toLowerCase().equals(q))
                        || r.title.toLowerCase().contains(q))
                    rows.add(new Object[]{r.year, r.title, r.source, r.timesCited});
            citingTable.setModel(new javax.swing.table.DefaultTableModel(
                    rows.toArray(new Object[0][]),
                    new Object[]{Strings.get("citing.year"), Strings.get("citing.title"), Strings.get("citing.source"), Strings.get("citing.tc")}) {
                 public boolean isCellEditable(int a, int b) { return false; }
            });
        };
        for(var canvas:canvases())canvas.setSelectionListener(n->{nodeInfo.show(n);citingFill.accept(n);});

        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane bottom = new JScrollPane(report);
        bottom.setPreferredSize(new Dimension(0, 150));

        JPanel south = new JPanel(new BorderLayout());
        south.add(anim, BorderLayout.NORTH);
        south.add(bottom, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        south.add(status, BorderLayout.SOUTH);

        add(tabs, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        tabs.addChangeListener(event->{if(result!=null&&!rendering){syncDisplayControls();anim.syncToCanvas();nodeInfo.show(currentCanvas().getSelectedNode());}});
        installShortcuts();
        setSize(1500, 940);
        setLocationRelativeTo(null);
    }

    /** 键盘快捷键：Ctrl+O 打开项目 / Ctrl+S 保存项目 / F5 分析 / Ctrl+Z 撤销。 */
    private void installShortcuts() {
        javax.swing.KeyStroke ks;
        var root = getRootPane();
        var im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = root.getActionMap();
        im.put(ks = javax.swing.KeyStroke.getKeyStroke("ctrl O"), "openProj");
        am.put("openProj", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { openProject(); } });
        im.put(ks = javax.swing.KeyStroke.getKeyStroke("ctrl S"), "saveProj");
        am.put("saveProj", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { saveProject(); } });
        im.put(javax.swing.KeyStroke.getKeyStroke("F5"), "run");
        am.put("run", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { runPipeline(); } });
        im.put(javax.swing.KeyStroke.getKeyStroke("ctrl Z"), "undo");
        am.put("undo", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { undoEdit(); } });
        // 新增快捷键（Phase C）
        im.put(javax.swing.KeyStroke.getKeyStroke("ctrl D"), "toggleTheme");
        am.put("toggleTheme", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) {
            setTheme(timeline.getTheme() == GraphCanvas.Theme.DARK ? GraphCanvas.Theme.LIGHT : GraphCanvas.Theme.DARK);
        } });
        im.put(javax.swing.KeyStroke.getKeyStroke("ctrl E"), "exportPng");
        am.put("exportPng", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { exportPng(); } });
        im.put(javax.swing.KeyStroke.getKeyStroke("ctrl shift E"), "exportPajek");
        am.put("exportPajek", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) {
            if (result != null) try {
                var fc = new JFileChooser();
                fc.setSelectedFile(new File("network.net"));
                if (fc.showSaveDialog(MainFrame.this) == JFileChooser.APPROVE_OPTION)
                    ycscite.io.Exporters.writePajek(result.network, fc.getSelectedFile().toPath());
            } catch (Exception ex) { JOptionPane.showMessageDialog(MainFrame.this, ex.getMessage()); }
        } });
        im.put(javax.swing.KeyStroke.getKeyStroke("ctrl B"), "exportTables");
        am.put("exportTables", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { exportTables(); } });
        im.put(javax.swing.KeyStroke.getKeyStroke("F1"), "help");
        am.put("help", new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) {
            JOptionPane.showMessageDialog(MainFrame.this,
                Strings.isZh()?"Ycscite 快捷键:\n  Ctrl+O  打开项目\n  Ctrl+S  保存项目\n  Ctrl+Z  撤销编辑\n  Ctrl+D  切换暗/亮主题\n  Ctrl+E  导出 PNG\n  Ctrl+Shift+E  导出 Pajek\n  Ctrl+B  导出 TSV\n  F5     运行分析\n  F1     帮助":"Ycscite shortcuts:\n  Ctrl+O  Open project\n  Ctrl+S  Save project\n  Ctrl+Z  Undo edit\n  Ctrl+D  Toggle theme\n  Ctrl+E  Export PNG\n  Ctrl+Shift+E  Export Pajek\n  Ctrl+B  Export TSV\n  F5  Run analysis\n  F1  Help",
                Strings.isZh()?"帮助":"Help", JOptionPane.INFORMATION_MESSAGE);
        } });
    }

    /** 撤销最近一次节点编辑（合并/过滤/删除）。 */
    private void undoEdit() {
        if (undoStack.isEmpty()) {
            JOptionPane.showMessageDialog(this, Strings.get("undo.empty"));
            return;
        }
        if (result == null) return;
        var previous=undoStack.pop();result=previous.analysis();analyzedOptions=previous.options();syncOptions(previous.controls());pendingPresentation=previous.presentation();
        refresh(project == null ? "network" : project.name + " — " + result.network.kind + " (undo)");
        setStatus(Strings.get("undo") + (Strings.isZh()?" ✓  剩余 ":" ✓  Remaining: ") + undoStack.size() + (Strings.isZh()?" 步":" steps"));
    }

    void setStatus(String s) { status.setText(s); }

    private final JMenu[] recentMenuHolder = new JMenu[1];

    /** 根据最近记录重建"最近打开"子菜单。 */
    void rebuildRecentMenu() {
        JMenu recent = recentMenuHolder[0];
        if (recent == null) return;
        recent.removeAll();
        var items = RecentFiles.list();
        if (items.isEmpty()) {
            recent.add(new JMenuItem("—"));
            return;
        }
        for (String path : items) {
            JMenuItem it = new JMenuItem(path);
            it.addActionListener(e -> openRecent(path));
            recent.add(it);
        }
    }

    /** 打开最近记录：.csl-proj 走项目载入，其余按内容识别格式导入。 */
    private void openRecent(String path) {
        try {
            if (path.endsWith(".csl-proj")) {
                openProjectPath(java.nio.file.Path.of(path));
                return;
            }
            Project p = ycscite.Cli.loadInputs(java.util.List.of(path), "auto");
            this.project = p;
            RecentFiles.add(path);
            runPipeline();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("proj.loadfail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JMenuBar buildMenu() {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu(Strings.get("menu.file"));
        JMenuItem openProj = new JMenuItem(Strings.get("menu.openproj"));
        openProj.addActionListener(e -> openProject());
        JMenuItem saveProj = new JMenuItem(Strings.get("menu.saveproj"));
        saveProj.addActionListener(e -> saveProject());
        JMenuItem exit = new JMenuItem(Strings.get("menu.exit"));
        exit.addActionListener(e -> System.exit(0));
        file.add(openProj);
        file.add(saveProj);
        // 最近打开（点击直达项目/数据文件）
        JMenu recent = new JMenu(Strings.get("menu.recent"));
        recentMenuHolder[0] = recent;
        rebuildRecentMenu();
        file.add(recent);
        var logoMenu=new JCheckBoxMenuItem(Strings.get("export.logo"));logoMenu.setModel(includeLogo.getModel());file.add(logoMenu);
        file.addSeparator();
        file.add(exit);
        JMenu view = new JMenu(Strings.get("menu.view"));
        JMenu lang = new JMenu(Strings.get("menu.lang"));
        JMenuItem zh = new JMenuItem(Strings.get("menu.lang.zh"));
        JMenuItem en = new JMenuItem(Strings.get("menu.lang.en"));
        zh.addActionListener(e -> relaunch(Strings.Lang.ZH));
        en.addActionListener(e -> relaunch(Strings.Lang.EN));
        lang.add(zh);
        lang.add(en);
        view.add(lang);
        JMenu themeMenu = new JMenu(Strings.isZh()?"主题":"Theme");
        JMenuItem dark = new JMenuItem(Strings.isZh()?"暗色":"Dark");
        JMenuItem light = new JMenuItem(Strings.isZh()?"亮色":"Light");
        JMenuItem sepia = new JMenuItem(Strings.isZh()?"羊皮纸":"Sepia");
        dark.addActionListener(e -> setTheme(GraphCanvas.Theme.DARK));
        light.addActionListener(e -> setTheme(GraphCanvas.Theme.LIGHT));
        sepia.addActionListener(e -> setTheme(GraphCanvas.Theme.SEPIA));
        themeMenu.add(dark); themeMenu.add(light); themeMenu.add(sepia);
        view.add(themeMenu);
        mb.add(file);
        view.add(lang);
        mb.add(file);
        mb.add(view);
        JMenu tools = new JMenu(Strings.isZh()?"外部工具":"External tools");
        addUrlItem(tools, "HistCite", "https://histcite.sciencewebresearch.net/");
        addUrlItem(tools, "Leydesdorff Toolset", "https://www.leydesdorff.net/");
        addUrlItem(tools, "NWB", "https://nwb.cns.iu.edu/");
        addUrlItem(tools, "Pajek", "https://mrvar.fdv.uni-lj.si/pajek/");
        addUrlItem(tools, "VOSviewer", "https://www.vosviewer.com/");
        addUrlItem(tools, "CitNetExplorer", "https://www.citnetexplorer.nl/");
        addUrlItem(tools, "SCI2", "https://sci2.cns.iu.edu/");
        addUrlItem(tools, "CiteSpace 101", "https://cpo.ciid.cmu.edu/citespace/");
        mb.add(tools);
        JMenu helpM = new JMenu(Strings.isZh()?"帮助":"Help");
        addUrlItem(helpM, "How to Use", "https://cpo.ciid.cmu.edu/citespace/how-to-use/");
        addUrlItem(helpM, "FAQ", "https://cpo.ciid.cmu.edu/citespace/faq/");
        addUrlItem(helpM, "Video Tutorials", "https://www.youtube.com/playlist?list=PLpEDzH9MgGDS4q6hGfVWFcVbS2B6SCrBv");
        addUrlItem(helpM, "Twitter", "https://twitter.com/citespace");
        addUrlItem(helpM, "ResearchGate", "https://www.researchgate.net/project/CiteSpace");
        JMenuItem about = new JMenuItem(Strings.isZh()?"关于 Ycscite":"About Ycscite");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Ycscite "+ycscite.Cli.VERSION+"\n"+(Strings.isZh()?"文献计量与知识图谱分析\n高级窗口可编辑当前网络；方法定义与限制见使用文档。":"Bibliometric and knowledge-network analysis\nEdit the current network here; see the user guide for method definitions and limitations.")));
        helpM.addSeparator();
        helpM.add(about);
        mb.add(helpM);
        return mb;
    }

    private void setTheme(GraphCanvas.Theme t) {
        for (GraphCanvas c : canvases())
            c.setTheme(t);
        repaint();
    }

    private void addUrlItem(JMenu menu, String label, String url) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)); }
            catch (Exception ex) { JOptionPane.showMessageDialog(this, url); }
        });
        menu.add(item);
    }

    /** 语言切换：静态语言位 + 重建窗口（保留当前数据与结果）。 */
    private void relaunch(Strings.Lang language) {
        if(Strings.getLang()==language)return;
        var bundle=new ProjectStore.Bundle();bundle.project=project;bundle.result=result;bundle.options=ycscite.io.PipelineOptionsCodec.copy(analyzedOptions);var pending=currentOptions();bundle.exportLogo=includeLogo.isSelected();bundle.presentation=capturePresentation();
        Strings.setLang(language);
        SwingUtilities.invokeLater(()->{var frame=new MainFrame();if(bundle.project!=null){frame.setBundle(bundle);frame.syncOptions(pending);}frame.setVisible(true);dispose();});
    }

    private String translateNetKind(String old) {
        String[] keys = {NetworkBuilders.KEYWORD_COOCCURRENCE, NetworkBuilders.TERM_COOCCURRENCE,
                NetworkBuilders.CO_AUTHOR, NetworkBuilders.CO_CITATION, NetworkBuilders.BIB_COUPLING,
                NetworkBuilders.INSTITUTION, NetworkBuilders.AUTHOR_COCITATION, NetworkBuilders.JOURNAL_COCITATION,
                NetworkBuilders.CATEGORY_COOCCURRENCE, NetworkBuilders.COUNTRY_COOCCURRENCE};
        String[] zh = {Strings.get("net.keyword"), Strings.get("net.term"), Strings.get("net.author"),
                Strings.get("net.cocitation"), Strings.get("net.coupling"), Strings.get("net.institution"),
                Strings.get("net.aca"), Strings.get("net.jca"), Strings.get("net.category"), Strings.get("net.country")};
        String[] en = {"Keyword co-occurrence", "Term co-occurrence (title/abstract)", "Co-authorship",
                "Document co-citation", "Bibliographic coupling", "Institution co-occurrence",
                "Author co-citation (ACA)", "Journal co-citation (JCA)", "Category co-occurrence (WC)",
                "Country co-occurrence"};
        for (int i = 0; i < keys.length; i++)
            if (keys[i].equals(old) || zh[i].equals(old) || en[i].equals(old))
                return Strings.isZh() ? zh[i] : en[i];
        return null;
    }

    private String translateAlgo(String old) {
        boolean spectral = old != null && (old.equals(Pipeline.SPECTRAL) || old.contains("谱") || old.contains("Spectral"));
        return Strings.isZh() ? (spectral ? Strings.get("algo.spectral") : Strings.get("algo.modularity"))
                              : (spectral ? Strings.get("algo.spectral") : Strings.get("algo.modularity"));
    }

    private JTabbedPane buildWest() {
        networkKind.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("net.keyword"), Strings.get("net.term"), Strings.get("net.author"),
                Strings.get("net.cocitation"), Strings.get("net.coupling"), Strings.get("net.institution"),
                Strings.get("net.aca"), Strings.get("net.jca"), Strings.get("net.category"), Strings.get("net.country")}));
        clusterAlgo.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("algo.modularity"), Strings.get("algo.em"), Strings.get("algo.spectral")}));
        labelAlgo.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("label.llr"), Strings.get("label.lsi"), Strings.get("label.isikw"), Strings.get("label.tfidf"), Strings.get("label.mi"), Strings.get("label.kcore")}));
        sizeMode.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("size.freq"), Strings.get("size.sigma"), Strings.get("size.centrality"),
                Strings.get("size.degree"), Strings.get("size.eigen"), Strings.get("size.pagerank"),
                Strings.get("size.entropy"), Strings.get("size.relent"), Strings.get("size.bias")}));
        pruningBox.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("prune.none"), Strings.get("prune.mst"), Strings.get("prune.pf")}));
        selectionBox.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("sel.topn"), Strings.get("sel.topnpct"), Strings.get("sel.gindex"), Strings.get("sel.thresholds")}));
        layoutAlgo.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("layout.linlog"), Strings.get("layout.kk"), Strings.get("layout.fd")}));
        layoutAlgo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        colorPreset.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("color.classic"), Strings.get("color.white"),
                Strings.get("color.yellowbrown"), Strings.get("color.yellowgreen")}));
        colorPreset.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        colorPreset.addActionListener(e2 -> {if(syncingDisplayControls)return;
            String sel = (String) colorPreset.getSelectedItem();
            boolean isWhite = sel != null && (sel.contains("白底") || sel.contains("White"));
            boolean isYB = sel != null && (sel.contains("黄棕") || sel.contains("Brown"));
            boolean isYG = sel != null && (sel.contains("黄绿") || sel.contains("Green"));
            for (GraphCanvas c : canvases()) {
                c.setOriginalStyle(isWhite);
                c.setConvexHull(!isWhite);
                if (isYB) c.setLinkColorByCluster(false);
                if (isYG) c.setLinkColorByCluster(true);
            }
        });
        layoutAlgo.addActionListener(e -> {if(syncingDisplayControls)return;
            String sel = (String) layoutAlgo.getSelectedItem();
            var algo = sel != null && (sel.contains("Kamada") || sel.contains("KK"))
                    ? GraphCanvas.LayoutAlgo.KAMADA_KAWAI
                    : sel != null && (sel.contains("Force") || sel.contains("力导向"))
                    ? GraphCanvas.LayoutAlgo.FORCE_DIRECTED
                    : GraphCanvas.LayoutAlgo.LINLOG;
            for (GraphCanvas c : canvases())
                c.setLayoutAlgo(algo);
        });

        JTabbedPane west = new JTabbedPane();
        west.setPreferredSize(new Dimension(262, 0));

        // ---------- 数据 ----------
        JPanel data = vPanel();
        JButton reportButton = new JButton(Strings.isZh() ? "标准九图报告…" : "Standard nine-chart report…");
        reportButton.addActionListener(e -> generateNineFigures(reportButton));
        data.add(reportButton);
        data.add(strut());
        JButton openWoS = new JButton(Strings.get("open.wos"));
        openWoS.addActionListener(e -> openFiles(FileKind.WOS));
        data.add(openWoS);
        JButton openScopus = new JButton(Strings.get("open.scopus"));
        openScopus.addActionListener(e -> openFiles(FileKind.SCOPUS));
        data.add(openScopus);
        JButton openPubmed = new JButton(Strings.get("open.pubmed"));
        openPubmed.addActionListener(e -> openFiles(FileKind.PUBMED));
        data.add(openPubmed);
        JButton openCnki = new JButton(Strings.get("open.cnki"));
        openCnki.addActionListener(e -> openFiles(FileKind.CNKI));
        data.add(openCnki);
        JButton openCssci = new JButton(Strings.get("open.cssci"));
        openCssci.addActionListener(e -> openFiles(FileKind.CSSCI));
        data.add(openCssci);
        JButton openDerwent = new JButton(Strings.get("open.derwent"));
        openDerwent.addActionListener(e -> openFiles(FileKind.DERWENT));
        data.add(openDerwent);
        JButton openProquest = new JButton(Strings.get("open.proquest"));
        openProquest.addActionListener(e -> openFiles(FileKind.PROQUEST));
        data.add(openProquest);
        JButton openDimensions = new JButton(Strings.get("open.dimensions"));
        openDimensions.addActionListener(e -> openFiles(FileKind.DIMENSIONS));
        data.add(openDimensions);
        JButton openKci = new JButton(Strings.get("open.kci"));
        openKci.addActionListener(e -> openFiles(FileKind.KCI));
        data.add(openKci);
        JButton openNsf = new JButton(Strings.get("open.nsf"));
        openNsf.addActionListener(e -> openFiles(FileKind.NSF));
        data.add(openNsf);
        data.add(strut());
        JButton demo = new JButton(Strings.get("demo"));
        demo.addActionListener(e -> { project = DemoData.generate(120); runPipeline(); });
        data.add(demo);
        JButton dedup = new JButton(Strings.get("dedup"));
        dedup.addActionListener(e -> dedupData());
        data.add(dedup);
        JButton crossref = new JButton(Strings.get("crossref"));
        crossref.addActionListener(e -> crossrefSearch());
        data.add(crossref);
        west.addTab(Strings.get("tab.data"), wrap(data));

        // ---------- 分析 ----------
        JPanel an = vPanel();
        an.add(label(Strings.get("net.type")));
        networkKind.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(networkKind);
        an.add(strut());
        JPanel span = new JPanel(new GridLayout(2, 2, 4, 2));
        span.add(label(Strings.get("span.from")));
        span.add(label(Strings.get("span.to")));
        span.add(fromYear);
        span.add(toYear);
        span.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        an.add(span);
        an.add(label(Strings.get("slice.years")));
        sliceYears.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(sliceYears);
        an.add(label(Strings.get("per.slice")));
        topPerSlice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(topPerSlice);
        an.add(label(Strings.get("topn")));
        topN.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(topN);
        an.add(label(Strings.get("sel.standard")));
        selectionBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(selectionBox);
        an.add(label(Strings.get("gindex.k")));
        gindexK.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(gindexK);
        an.add(label(Strings.get("min.link")));
        minLinkStrength.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(minLinkStrength);
        an.add(label(Strings.get("largest.cc")));
        largestCC.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(largestCC);
        singularCheck.setAlignmentX(0);
        an.add(singularCheck);
        an.add(label(Strings.get("pruning")));
        pruningBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(pruningBox);
        an.add(label(Strings.get("cluster.algo")));
        clusterAlgo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(clusterAlgo);
        an.add(label(Strings.isZh()?"布局算法:":"Layout:"));
        an.add(layoutAlgo);
        an.add(label(Strings.isZh()?"配色方案:":"Palette:"));
        an.add(colorPreset);
        an.add(label(Strings.get("label.algo")));
        labelAlgo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(labelAlgo);
        an.add(label(Strings.get("label.source")));
        labelSource.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("src.k"), Strings.get("src.t"), Strings.get("src.a"),
                Strings.get("src.wc"), Strings.get("src.r"), Strings.get("src.tka")}));
        labelSource.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(labelSource);
        an.add(label(Strings.get("min.cluster")));
        minCluster.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        an.add(minCluster);
        an.add(strut());
        JButton run = new JButton(Strings.get("run") + "  (F5)");
        run.addActionListener(e -> runPipeline());
        an.add(run);
        an.add(Box.createVerticalGlue());
        west.addTab(Strings.get("tab.analysis"), wrap(an));

        // ---------- 显示 ----------
        JPanel disp = vPanel();
        var fit=new JButton(Strings.get("disp.fit"));fit.setAlignmentX(0);fit.addActionListener(event->{currentCanvas().fitToWindow();});disp.add(fit);
        var allNodes=new JButton(Strings.get("disp.showall"));allNodes.setAlignmentX(0);allNodes.addActionListener(event->{currentCanvas().showAllNodes();});disp.add(allNodes);
        var moveHelp=new JTextArea(Strings.get("disp.movehint"));moveHelp.setEditable(false);moveHelp.setLineWrap(true);moveHelp.setWrapStyleWord(true);moveHelp.setOpaque(false);moveHelp.setFont(UIManager.getFont("Label.font"));moveHelp.setAlignmentX(0);moveHelp.setMinimumSize(new Dimension(0,95));moveHelp.setPreferredSize(new Dimension(220,95));moveHelp.setMaximumSize(new Dimension(Integer.MAX_VALUE,95));moveHelp.setForeground(new Color(105,99,88));disp.add(moveHelp);disp.add(strut());
        disp.add(label(Strings.get("label.budget")));
        labelSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        labelSlider.addChangeListener(e -> {if(syncingDisplayControls)return;
            int v = labelSlider.getValue();
            for(var canvas:canvases())canvas.setLabelBudget(v);
        });
        disp.add(labelSlider);
        disp.add(strut());
        disp.add(label(Strings.get("size.mode")));
        sizeMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        sizeMode.addActionListener(e -> {if(syncingDisplayControls)return;
            Object sel = sizeMode.getSelectedItem();
            GraphCanvas.SizeMode m = sel == null ? GraphCanvas.SizeMode.FREQ
                    : sel.equals(Strings.get("size.sigma")) ? GraphCanvas.SizeMode.SIGMA
                    : sel.equals(Strings.get("size.centrality")) ? GraphCanvas.SizeMode.CENTRALITY
                    : sel.equals(Strings.get("size.degree")) ? GraphCanvas.SizeMode.DEGREE
                    : sel.equals(Strings.get("size.eigen")) ? GraphCanvas.SizeMode.EIGEN
                    : sel.equals(Strings.get("size.pagerank")) ? GraphCanvas.SizeMode.PAGERANK
                    : sel.equals(Strings.get("size.entropy")) ? GraphCanvas.SizeMode.ENTROPY
                    : sel.equals(Strings.get("size.entropy")) ? GraphCanvas.SizeMode.ENTROPY
                    : sel.equals(Strings.get("size.relent")) ? GraphCanvas.SizeMode.RELATIVE_ENTROPY
                    : sel.equals(Strings.get("size.bias")) ? GraphCanvas.SizeMode.INFO_BIAS
                    : sel.equals(Strings.get("size.uniform")) ? GraphCanvas.SizeMode.UNIFORM
                    : GraphCanvas.SizeMode.FREQ;
            for(var canvas:canvases())canvas.setSizeMode(m);
        });
        disp.add(sizeMode);
        disp.add(strut());
        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        searchPanel.add(label(Strings.get("search")), BorderLayout.WEST);
        JButton go = new JButton(Strings.get("search.go"));
        searchPanel.add(go, BorderLayout.EAST);
        searchPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        disp.add(searchPanel);
        disp.add(search);
        Runnable doSearch = () -> {
            String q = search.getText().trim();
            if (q.isEmpty() || tabs.getComponentCount() == 0) return;
            GraphCanvas c = (GraphCanvas) tabs.getSelectedComponent();
            if (!c.focus(q)) JOptionPane.showMessageDialog(this, Strings.get("search.miss"));
        };
        go.addActionListener(e -> doSearch.run());
        search.addActionListener(e -> doSearch.run());
        disp.add(strut());
        // 鱼眼 / 凸包 / 标签角度（实时联动所有视图）
        fisheyeCheck.setAlignmentX(0);
        fisheyeCheck.addActionListener(e -> {if(syncingDisplayControls)return;
            boolean on = fisheyeCheck.isSelected();
            for(var canvas:canvases())canvas.setFisheye(on);
        });
        disp.add(fisheyeCheck);
        hullCheck.setAlignmentX(0);
        hullCheck.addActionListener(e -> {if(syncingDisplayControls)return;
            boolean on = hullCheck.isSelected();
            for(var canvas:canvases())canvas.setConvexHull(on);
        });
        disp.add(hullCheck);
        shadowCheck.setAlignmentX(0);
        shadowCheck.addActionListener(e -> {if(syncingDisplayControls)return;
            boolean on = shadowCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setLabelShadow(on);
        });
        disp.add(shadowCheck);
        disp.add(label(Strings.get("disp.shape")));
        shapeBox.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("shape.circle"), Strings.get("shape.diamond"), Strings.get("shape.square")}));
        shapeBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        shapeBox.addActionListener(e2 -> {if(syncingDisplayControls)return;
            String s = (String) shapeBox.getSelectedItem();
            var sh = s != null && (s.contains("菱") || s.contains("Diamond")) ? GraphCanvas.NodeShape.DIAMOND
                    : s != null && (s.contains("方") && !s.contains("菱") || s.contains("Square")) ? GraphCanvas.NodeShape.SQUARE
                    : GraphCanvas.NodeShape.CIRCLE;
            for (GraphCanvas c : canvases())
                c.setNodeShape(sh);
        });
        disp.add(shapeBox);
        disp.add(label(Strings.get("disp.fontsize")));
        fontSpin.setMaximumSize(new Dimension(60, 26));
        fontSpin.addChangeListener(e2 -> {if(syncingDisplayControls)return;
            float size = (Float) ((Number) fontSpin.getValue()).floatValue();
            for (GraphCanvas c : canvases())
                c.setLabelFontSize(size);
        });
        disp.add(fontSpin);
        linkLabelsCheck.setAlignmentX(0);
        linkLabelsCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = linkLabelsCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setLinkLabels(on);
        });
        disp.add(linkLabelsCheck);
        clusterIdCheck.setAlignmentX(0);
        clusterIdCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = clusterIdCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setShowClusterId(on);
        });
        disp.add(clusterIdCheck);
        citeCountCheck.setAlignmentX(0);
        citeCountCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = citeCountCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setShowCitationCount(on);
        });
        disp.add(citeCountCheck);
        labelClusterColorCheck.setAlignmentX(0);
        labelClusterColorCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = labelClusterColorCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setLabelColorByCluster(on);
        });
        disp.add(labelClusterColorCheck);
        disp.add(label(Strings.get("disp.rescale")));
        rescaleSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        rescaleSlider.addChangeListener(e2 -> {if(syncingDisplayControls)return;
            double s = rescaleSlider.getValue() / 100.0;
            for (GraphCanvas c : canvases())
                c.setNodeScale(s);
        });
        disp.add(rescaleSlider);
        disp.add(label(Strings.get("disp.kcore")));
        kcoreSpin.setMaximumSize(new Dimension(50, 26));
        kcoreSpin.addChangeListener(e2 -> {if(syncingDisplayControls)return;
            int k = (Integer) kcoreSpin.getValue();
            for (GraphCanvas c : canvases())
                c.setKcoreHighlight(k);
        });
        disp.add(kcoreSpin);
        linkMonoCheck.setAlignmentX(0);
        linkMonoCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = linkMonoCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setLinkSingleColor(on);
        });
        disp.add(linkMonoCheck);
        disp.add(label(Strings.get("disp.fillpattern")));
        fillPatternBox.setModel(new DefaultComboBoxModel<>(new String[]{
                Strings.get("fill.solid"), Strings.get("fill.cross"), Strings.get("fill.dots"), Strings.get("fill.horizontal")}));
        fillPatternBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        fillPatternBox.addActionListener(e2 -> {if(syncingDisplayControls)return;
            int idx = fillPatternBox.getSelectedIndex();
            for (GraphCanvas c : canvases())
                c.setHullFillPattern(idx);
        });
        disp.add(fillPatternBox);
        labelBgBtn.setAlignmentX(0);
        labelBgBtn.addActionListener(e2 -> {
            Color c = JColorChooser.showDialog(MainFrame.this, Strings.get("disp.labelbg"), Color.LIGHT_GRAY);
            if (c != null)
                for (GraphCanvas gc : canvases())
                    gc.setClusterLabelBg(c);
        });
        disp.add(labelBgBtn);
        curvedCheck.setAlignmentX(0);
        curvedCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = curvedCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setCurvedLines(on);
        });
        disp.add(curvedCheck);
        clusterLinkCheck.setAlignmentX(0);
        clusterLinkCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = clusterLinkCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setLinkColorByCluster(on);
        });
        disp.add(clusterLinkCheck);
        effects3dCheck.setAlignmentX(0);
        effects3dCheck.addActionListener(e2 -> {if(syncingDisplayControls)return;
            boolean on = effects3dCheck.isSelected();
            for (GraphCanvas c : canvases())
                c.setEffects3d(on);
        });
        disp.add(effects3dCheck);
        JPanel angPanel = new JPanel(new BorderLayout(4, 0));
        angPanel.add(label(Strings.get("disp.angle")), BorderLayout.WEST);
        angleBox.setMaximumSize(new Dimension(80, 26));
        angPanel.add(angleBox, BorderLayout.EAST);
        angPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        angleBox.addActionListener(e -> {if(syncingDisplayControls)return;
            Integer deg = (Integer) angleBox.getSelectedItem();
            timeline.setLabelAngle(deg); timezone.setLabelAngle(deg);
            merged.setLabelAngle(deg); tsne.setLabelAngle(deg);
        });
        disp.add(angPanel);
        disp.add(strut());
        JButton undo = new JButton(Strings.get("undo") + "  (Ctrl+Z)");
        undo.addActionListener(e -> undoEdit());
        disp.add(undo);
        JButton mergeVariants = new JButton(Strings.get("merge.variants"));
        mergeVariants.addActionListener(e -> mergeRefVariants());
        disp.add(mergeVariants);
        disp.add(Box.createVerticalGlue());
        west.addTab(Strings.get("tab.display"), wrap(disp));

        // ---------- 导出 ----------
        JPanel ex = vPanel();
        includeLogo.setAlignmentX(0);includeLogo.setToolTipText(Strings.get("export.logo.hint"));ex.add(includeLogo);ex.add(strut());
        for (Object[] act : new Object[][]{
                {Strings.get("export.png"), (Runnable) this::exportPng},
                {Strings.get("export.svg"), (Runnable) this::exportSvg},
                {Strings.get("export.html"), (Runnable) this::exportHtml},
                {Strings.get("export.tsv"), (Runnable) this::exportTables},
                {Strings.get("export.cluster.summary"), (Runnable) this::exportClusterSummary},
                {Strings.get("export.yby"), (Runnable) this::exportYearByYear},
                {Strings.get("export.sva"), (Runnable) this::exportSva},
                {Strings.get("export.dualmap"), (Runnable) this::exportDualMap},
                {Strings.get("export.highlightlist"), (Runnable) this::loadHighlightList},
                {Strings.get("export.locations"), (Runnable) this::exportLocations},
                {Strings.get("export.kml"), (Runnable) this::exportKml},
                {Strings.get("export.ris"), (Runnable) this::exportRis},
                {Strings.get("report.button"), (Runnable) this::citationReport},
                {Strings.get("export.pdf"), (Runnable) this::exportPdf},
                {Strings.get("export.docx"), (Runnable) this::exportDocx},
                {Strings.get("export.sql"), (Runnable) this::exportSqlDump},
                {Strings.get("anim.video"), (Runnable) this::exportVideo}}) {
            JButton b = new JButton((String) act[0]);
            b.addActionListener(e -> ((Runnable) act[1]).run());
            ex.add(b);
        }
        ex.add(Box.createVerticalGlue());
        west.addTab(Strings.get("tab.export"), wrap(ex));
        return west;
    }

    /** Sidebar columns follow the viewport width and scroll only in the vertical direction. */
    private static final class SettingsColumn extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 18;}
        public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(18,visible.height-18);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
    private JPanel vPanel() {
        JPanel p = new SettingsColumn();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return p;
    }
    private Component strut() { return Box.createVerticalStrut(8); }
    private JScrollPane wrap(JPanel p) {
        for(Component component:p.getComponents())if(component instanceof JComponent child){child.setAlignmentX(0);if(child instanceof JCheckBox)child.setMaximumSize(new Dimension(Integer.MAX_VALUE,child.getPreferredSize().height));}
        JScrollPane sp = new JScrollPane(p);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        return sp;
    }

    /** 数据去重（UT/DOI/题名）。 */
    private void dedupData() {
        if (project == null || project.records.isEmpty()) return;
        int removed = project.dedup();
        setStatus(String.format(Strings.get("dedup.done"), removed));
        if (result != null) runPipeline();
    }

    private void exportClusterSummary() {
        if (result == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("cluster-summary.tsv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Exporters.writeClusterSummary(result, fc.getSelectedFile().toPath());
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportYearByYear() {
        if (result == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("year-by-year-labels.tsv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Exporters.writeYearByYearLabels(result, fc.getSelectedFile().toPath());
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportSva() {
        if (project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("structural-variation.tsv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String netKey = netKindKey((String) networkKind.getSelectedItem());
            var rows = ycscite.analysis.StructuralVariation.compute(project, netKey);
            java.nio.file.Files.writeString(fc.getSelectedFile().toPath(),
                    ycscite.analysis.StructuralVariation.toTsv(rows), java.nio.charset.StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 加载 .list 高亮列表（原版 "Add Node Highlights by DOIs or Node Label Lists"）。 */
    private void loadHighlightList() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            java.util.Set<String> labels = new java.util.HashSet<>(
                    java.nio.file.Files.readAllLines(fc.getSelectedFile().toPath(), java.nio.charset.StandardCharsets.UTF_8));
            labels.removeIf(String::isBlank);
            for (GraphCanvas c : canvases())
                c.setHighlightList(labels);
            setStatus((Strings.isZh()?"高亮列表: ":"Highlight list: ") + labels.size());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 机构位置 CSV（原版 "Export author locations to CSV"，mF 6.1 R6）。 */
    /** 导出 KML（Google Earth）+ Tableau CSV。 */
    private void exportKml() {
        if (project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("map.kml"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            ycscite.io.KmlExporter.export(project, fc.getSelectedFile().toPath(),
                    fc.getSelectedFile().toPath().resolveSibling("map-tableau.csv"));
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 导出 RIS（EndNote/Zotero 兼容）。 */
    private void exportRis() {
        if (project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("references.ris"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            ycscite.io.RisExporter.write(project, fc.getSelectedFile().toPath());
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportLocations() {
        if (project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("locations.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            StringBuilder sb = new StringBuilder("name,affiliation,address\n");
            for (var r : project.records)
                for (int i = 0; i < r.allAuthors().size() && i < r.addresses.size(); i++) {
                    String addr = r.addresses.isEmpty() ? "" : r.addresses.get(Math.min(i, r.addresses.size() - 1));
                    sb.append(csv(r.allAuthors().get(i))).append(',').append(csv(addr)).append('\n');
                }
            java.nio.file.Files.writeString(fc.getSelectedFile().toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String csv(String s) {
        return s.contains(",") ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    private void exportDualMap() {
        if (project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("dual-map.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            ycscite.io.DualMap.renderJcr(project, fc.getSelectedFile().toPath());
            Branding.variants(fc.getSelectedFile().toPath(),includeLogo.isSelected());
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 合并引文变体（共被引网络）：归并同一文献的书写变体并重算。 */
    private void mergeRefVariants() {
        if (result == null || !result.network.kind.contains("Co-citation")) {
            JOptionPane.showMessageDialog(this, Strings.get("net.cocitation") + " only");
            return;
        }
        if (result != null) {
            undoStack.push(new EditState(ProjectStore.copyAnalysis(result),ycscite.io.PipelineOptionsCodec.copy(analyzedOptions),currentOptions(),capturePresentation()));
            int merged = ycscite.analysis.RefVariants.merge(result.network);
            int slices = project == null ? 0 : Math.max(0, project.maxYear - project.minYear + 1);
            var options=currentOptions();result = Pipeline.reanalyze(result.network, ycscite.io.PipelineOptionsCodec.copy(options), slices);analyzedOptions=ycscite.io.PipelineOptionsCodec.copy(options);
            refresh(project.name + " — " + result.network.kind + " (variants merged)");
            setStatus(String.format(Strings.get("merge.variants.done"), merged));
        }
    }

    private JLabel label(String text) { JLabel l = new JLabel(text); l.setAlignmentX(0); return l; }

    private enum FileKind { WOS, SCOPUS, PUBMED, CNKI, CSSCI, DERWENT, PROQUEST, DIMENSIONS, KCI, NSF }

    private void generateNineFigures(JButton button) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(Strings.isZh() ? "选择文献文件或数据目录" : "Select data files or a folder");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] files = chooser.getSelectedFiles();
        if (files.length == 0 && chooser.getSelectedFile() != null) files = new File[]{chooser.getSelectedFile()};
        if (files.length == 0) return;
        List<String> inputs = java.util.Arrays.stream(files).map(File::getAbsolutePath).toList();
        File first = files[0].getAbsoluteFile();
        Path out = (first.isDirectory() ? first : first.getParentFile()).toPath().resolve("ycscite-report").resolve(java.util.UUID.randomUUID().toString());
        var reportSettings=new ycscite.Settings(java.util.Map.of("language",Strings.isZh()?"zh":"en","logo",includeLogo.isSelected()));
        button.setEnabled(false);
        setStatus(Strings.isZh() ? "正在生成九图…" : "Generating figures…");
        new SwingWorker<Path, String>() {
            @Override protected Path doInBackground() throws Exception {
                NineFigureReport.write(ycscite.Cli.loadReportInputs(inputs), out, msg -> publish(msg),reportSettings);
                return out.resolve("index.html");
            }
            @Override protected void process(List<String> messages) { setStatus(messages.getLast()); }
            @Override protected void done() {
                button.setEnabled(true);
                Path index;
                try { index = get(); }
                catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    setStatus(Strings.isZh() ? "生成失败" : "Export failed");
                    JOptionPane.showMessageDialog(MainFrame.this, cause.getMessage());
                    return;
                }
                setStatus((Strings.isZh() ? "九图已生成：" : "Report ready: ") + index.getParent());
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(index.toUri());
                        return;
                    }
                } catch (Exception ignored) { }
                JOptionPane.showMessageDialog(MainFrame.this, index.toString());
            }
        }.execute();
    }

    private void openFiles(FileKind kind) {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        switch (kind) {
            case SCOPUS -> fc.setFileFilter(new FileNameExtensionFilter("Scopus CSV", "csv"));
            case PUBMED -> fc.setFileFilter(new FileNameExtensionFilter("PubMed", "txt", "nbib", "medline"));
            case CNKI -> fc.setFileFilter(new FileNameExtensionFilter("CNKI (EndNote)", "txt"));
            case CSSCI -> fc.setFileFilter(new FileNameExtensionFilter("CSSCI (RefWorks/NoteExpress)", "txt"));
            case DERWENT -> fc.setFileFilter(new FileNameExtensionFilter("Derwent (tab-delimited)", "txt", "tsv", "csv"));
            case PROQUEST -> fc.setFileFilter(new FileNameExtensionFilter("ProQuest RIS", "ris", "txt"));
            case DIMENSIONS -> fc.setFileFilter(new FileNameExtensionFilter("Dimensions CSV", "csv"));
            case KCI -> fc.setFileFilter(new FileNameExtensionFilter("KCI", "txt"));
            case NSF -> fc.setFileFilter(new FileNameExtensionFilter("NSF Awards XML", "xml"));
            default -> fc.setFileFilter(new FileNameExtensionFilter("Web of Science plain text", "txt"));
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Project p = new Project();
            for (File f : fc.getSelectedFiles()) {
                Path path = f.toPath();
                switch (kind) {
                    case SCOPUS -> p.records.addAll(ScopusCsvParser.parseFile(path));
                    case PUBMED -> p.records.addAll(PubMedParser.parseFile(path));
                    case CNKI -> p.records.addAll(CnkiParser.parseFile(path));
                    case CSSCI -> p.records.addAll(CssciParser.parseFile(path));
                    case DERWENT -> p.records.addAll(DerwentParser.parseFile(path));
                    case PROQUEST -> p.records.addAll(ProQuestRisParser.parseFile(path));
                    case DIMENSIONS -> p.records.addAll(DimensionsCsvParser.parseFile(path));
                    case KCI -> p.records.addAll(KciParser.parseFile(path));
                    case NSF -> p.records.addAll(NsfAwardsParser.parseFile(path));
                    default -> p.addAll(WoSParser.parseFile(path));
                }
            }
            p.minYear = p.records.stream().mapToInt(r -> r.year).filter(y -> y > 0).min().orElse(Integer.MAX_VALUE);
            p.maxYear = p.records.stream().mapToInt(r -> r.year).filter(y -> y > 0).max().orElse(Integer.MIN_VALUE);
            p.name = fc.getSelectedFiles()[0].getName();
            this.project = p;
            RecentFiles.add(fc.getSelectedFiles()[0].getAbsolutePath());
            rebuildRecentMenu();
            runPipeline();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void setProject(Project p) { this.project = p;spanInitialized=false; }

    /** Restore the analyzed project as-is; editing starts from the report's nodes and options. */
    public void setBundle(ProjectStore.Bundle bundle) {
        this.project=bundle.project;if(bundle.viewDefaults!=null)applySettings(bundle.viewDefaults);syncOptions(bundle.options);pendingPresentation=bundle.presentation;if(bundle.exportLogo!=null)includeLogo.setSelected(bundle.exportLogo);
        if(bundle.result!=null){this.result=bundle.result;analyzedOptions=ycscite.io.PipelineOptionsCodec.copy(bundle.options);undoStack.clear();refresh(project.name+" — "+(Strings.isZh()?netKindZh(result.network.kind):result.network.kind));}
        else runPipeline();
    }
    public void applySettings(ycscite.Settings settings) {
        syncOptions(settings.pipeline(null));includeLogo.setSelected(settings.bool("logo"));
        labelSlider.setValue(settings.integer("label_budget"));
        fontSpin.setValue(Math.clamp(12*settings.number("label_scale"),6,24));
        for(GraphCanvas canvas:new GraphCanvas[]{timeline,timezone,merged,tsne,circular,heatmap,landscape,umap,circlePack}){
            canvas.setTheme(GraphCanvas.Theme.LIGHT);canvas.setNodeScale(settings.number("node_scale"));canvas.setLabelBudget(settings.integer("label_budget"));
            canvas.setLabelFontSize((float)(12*settings.number("label_scale")));canvas.setLinkTransparency((float)settings.number("link_opacity"));
        }
    }
    /** Reproducible diagnostics for the interactive editor, also used by its UI smoke mode. */
    public Map<String,Object> analysisState() {
        var state=new java.util.LinkedHashMap<String,Object>();state.put("options",ycscite.io.PipelineOptionsCodec.write(result==null?currentOptions():analyzedOptions));if(hasPendingAnalysisOptions())state.put("pending_options",ycscite.io.PipelineOptionsCodec.write(currentOptions()));state.put("export_logo",includeLogo.isSelected());if(result!=null&&!rendering)state.put("presentation",capturePresentation());
        if(project!=null){state.put("source_fingerprint",ycscite.io.DataFingerprint.of(project));state.put("records",project.records.size());}
        if(result!=null){state.put("nodes",result.network.nodes.size());state.put("edges",result.network.edges.size());state.put("modularity",result.modularity);}
        return state;
    }

    public void saveDiagnostics(Path folder)throws Exception{
        java.nio.file.Files.writeString(folder.resolve("state.json"),ycscite.io.MiniJson.write(analysisState()));
        if(project!=null)saveEditable(folder.resolve("analysis.csl-proj"));
        if(result!=null)Exporters.writeTables(result.network,result.clusterLabels,folder.resolve("nodes.tsv"),folder.resolve("edges.tsv"));
        if(result!=null)for(String format:new String[]{"png","svg","pdf"})exportVisual(folder.resolve("network."+format),format);
    }

    private boolean rendering=false;
    private Throwable renderFailure=null;
    public boolean isRendering(){return rendering;}
    public Throwable renderFailure(){return renderFailure;}
    private boolean spanInitialized = false;

    public void runPipeline() {
        if (project == null || project.records.isEmpty()) {
            report.setText(Strings.get("report.nodata"));
            return;
        }
        // 首次分析时把时间范围控件对齐到数据年份范围
        if (!spanInitialized && project.minYear != Integer.MAX_VALUE && project.maxYear != Integer.MIN_VALUE) {
            fromYear.setValue(project.minYear);
            toYear.setValue(project.maxYear);
            spanInitialized = true;
        }
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));pendingPresentation=Map.of();
        try {
            var options=currentOptions();result = Pipeline.run(project, ycscite.io.PipelineOptionsCodec.copy(options));analyzedOptions=ycscite.io.PipelineOptionsCodec.copy(options);
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
        undoStack.clear();
        String t = project.name + " — " + (Strings.isZh() ? netKindZh(result.network.kind) : result.network.kind)
                + " (top " + (currentOptions().topN == 0 ? "all" : currentOptions().topN) + ")";
        refresh(t);
    }

    private Pipeline.Options currentOptions() {
        Pipeline.Options o = ycscite.io.PipelineOptionsCodec.copy(inheritedOptions);
        o.networkKind = netKindKey((String) networkKind.getSelectedItem());
        o.topN = (Integer) topN.getValue();
        o.fromYear = (Integer) fromYear.getValue();
        o.toYear = (Integer) toYear.getValue();
        o.sliceYears = (Integer) sliceYears.getValue();
        o.topNPerSlice = (Integer) topPerSlice.getValue();
        String pr = (String) pruningBox.getSelectedItem();
        o.pruning = pr == null ? ycscite.analysis.Pruning.NONE
                : pr.contains("MST") || pr.contains("生成树") ? ycscite.analysis.Pruning.MST
                : pr.contains("Pathfinder") || pr.contains("PFNET") ? ycscite.analysis.Pruning.PATHFINDER
                : ycscite.analysis.Pruning.NONE;
        o.minClusterSize = (Integer) minCluster.getValue();
        String algoSel = (String) clusterAlgo.getSelectedItem();
        o.clustering = algoSel != null && (algoSel.contains("EM") || algoSel.contains("Expectation"))
                ? Pipeline.EM
                : algoSel != null && (algoSel.contains("模块度") || algoSel.contains("modularity") || algoSel.contains("Greedy"))
                ? Pipeline.MODULARITY : Pipeline.SPECTRAL;
        String labSel = (String) labelAlgo.getSelectedItem();
        o.labeling = labSel != null && (labSel.contains("LSI") || labSel.contains("潜语义"))
                ? ycscite.analysis.Labelers.LSI
                : labSel != null && (labSel.contains("ISI") || labSel.contains("关键词"))
                ? ycscite.analysis.Labelers.ISI_KW
                : labSel != null && labSel.contains("TF-IDF")
                ? ycscite.analysis.Labelers.TFIDF
                : labSel != null && (labSel.equals(Strings.get("label.mi"))||labSel.equalsIgnoreCase("mi")) ? ycscite.analysis.Labelers.MI
                : labSel != null && (labSel.equals(Strings.get("label.kcore"))||labSel.equalsIgnoreCase("kcore")) ? ycscite.analysis.Labelers.KCORE
                : ycscite.analysis.Labelers.LLR;
        String srcSel = (String) labelSource.getSelectedItem();
        o.labelSource=Strings.get("src.t").equals(srcSel)?ycscite.analysis.Labelers.SRC_T
                :Strings.get("src.a").equals(srcSel)?ycscite.analysis.Labelers.SRC_A
                :Strings.get("src.wc").equals(srcSel)?ycscite.analysis.Labelers.SRC_WC
                :Strings.get("src.r").equals(srcSel)?ycscite.analysis.Labelers.SRC_R
                :Strings.get("src.tka").equals(srcSel)?ycscite.analysis.Labelers.SRC_TKA:ycscite.analysis.Labelers.SRC_K;
        String selSel = (String) selectionBox.getSelectedItem();
        o.selection = selSel == null ? Pipeline.TOP_N
                : selSel.contains("g-index") || selSel.contains("gindex") ? Pipeline.G_INDEX
                : selSel.contains("%") ? Pipeline.TOP_N_PCT : Pipeline.TOP_N;
        o.gindexK = (Double) gindexK.getValue();
        o.minLinkStrength = (Integer) minLinkStrength.getValue();
        o.largestKComponents = (Integer) largestCC.getValue();
        o.singularKeywords = singularCheck.isSelected();
        return o;
    }

    /** 右键编辑（合并/过滤/删除）后的重算与刷新。 */
    private void networkEdited() {
        if (result == null) return;
        int slices = project == null ? 0 : Math.max(0, project.maxYear - project.minYear + 1);
        var options=currentOptions();result = Pipeline.reanalyze(result.network, ycscite.io.PipelineOptionsCodec.copy(options), slices);analyzedOptions=ycscite.io.PipelineOptionsCodec.copy(options);
        String t = (project == null ? "network" : project.name) + " — " + result.network.kind + " (edited)";
        refresh(t);
    }

    private void refresh(String t) {
        // 布局（LinLog 5000 迭代等）在后台线程执行，避免冻结 EDT（审计 P0-2）
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));rendering=true;renderFailure=null;for(var canvas:canvases())canvas.setEnabled(false);
        final ycscite.analysis.Pipeline.Result res = result;
        final Map<String,Object> saved=pendingPresentation;pendingPresentation=Map.of();final var savedCanvases=MiniJson.obj(saved.get("canvases"));
        javax.swing.SwingWorker<Void, Void> worker = new javax.swing.SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (GraphCanvas c : new GraphCanvas[]{timeline, timezone, merged, tsne, circular, heatmap, landscape, umap, circlePack})
                    c.show(res.network, c == timeline ? GraphCanvas.Mode.TIMELINE
                            : c == timezone ? GraphCanvas.Mode.TIMEZONE
                            : c == merged ? GraphCanvas.Mode.MERGED
                            : c == circular ? GraphCanvas.Mode.CIRCULAR
                            : c == heatmap ? GraphCanvas.Mode.HEATMAP
                            : c == landscape ? GraphCanvas.Mode.LANDSCAPE
                            : c == umap ? GraphCanvas.Mode.UMAP
                            : c == circlePack ? GraphCanvas.Mode.CIRCLE_PACK
                            : GraphCanvas.Mode.TSNE, t,savedCanvases.containsKey(modeOf(c).name())?MiniJson.obj(savedCanvases.get(modeOf(c).name())):null);
                return null;
            }

            @Override
            protected void done() {
                if(res!=result)return;
                try{get();}catch(Exception error){renderFailure=error;rendering=false;for(var canvas:canvases())canvas.setEnabled(true);setStatus(Strings.isZh()?"布局失败，请检查参数":"Layout failed; review settings");setCursor(java.awt.Cursor.getDefaultCursor());return;}
                for (GraphCanvas c : new GraphCanvas[]{timeline, timezone, merged, tsne, circular, heatmap, landscape, umap, circlePack}) {
                    c.setClusterLabels(res.clusterLabels);
                    c.setLabelBudget(labelSlider.getValue());
                    c.setNetworkChanged(MainFrame.this::networkEdited);
                    c.setBeforeEdit(() -> {
                        if (result != null) {
                            undoStack.push(new EditState(ProjectStore.copyAnalysis(result),ycscite.io.PipelineOptionsCodec.copy(analyzedOptions),currentOptions(),capturePresentation()));
                            if (undoStack.size() > 12) undoStack.removeLast();
                        }
                    });
                    // 显示选项联动（鱼眼/凸包/角度）
                    c.setFisheye(fisheyeCheck.isSelected());
                    c.setConvexHull(hullCheck.isSelected());
                    c.setLabelAngle((Integer) angleBox.getSelectedItem());
                }
                for (GraphCanvas c : canvases())
                    c.setLabelShadow(shadowCheck.isSelected());
                anim.setRange(res.network);
                if(!saved.isEmpty()){
                    for(var canvas:canvases()){var view=savedCanvases.get(canvas.getMode().name());if(view!=null)canvas.restoreState(MiniJson.obj(view));}
                    for(var canvas:canvases())if(canvas.getMode().name().equals(saved.get("active_view")))tabs.setSelectedComponent(canvas);
                    if(saved.get("sidebar_tab") instanceof Number side)((JTabbedPane)((BorderLayout)getContentPane().getLayout()).getLayoutComponent(BorderLayout.WEST)).setSelectedIndex(side.intValue());
                }
                anim.syncToCanvas();syncDisplayControls();
                clusterPanel.setData(res.network, res.clusterStats, res.clusterLabels);
                nodeInfo.show(currentCanvas().getSelectedNode());
                // 动态窗口标题：项目名 · 网络类型
                setTitle("Ycscite "+ycscite.Cli.VERSION+" — " + (project == null ? "" : project.name + " · ")
                        + (Strings.isZh() ? netKindZh(res.network.kind) : res.network.kind));
                report.setText(buildReport());
                report.setCaretPosition(0);
                setStatus(String.format("%s: %d  %s: %d  Q=%.3f  %s: %d",
                        Strings.get("report.nodes2"), res.network.nodes.size(),
                        Strings.get("report.links2"), res.network.edges.size(),
                        res.modularity, Strings.get("report.clusters"),
                        res.clusterStats == null ? 0 : res.clusterStats.size()));
                setCursor(java.awt.Cursor.getDefaultCursor());rendering=false;for(var canvas:canvases())canvas.setEnabled(true);
            }
        };
        worker.execute();
    }

    private String netKindKey(String display) {
        if (display == null) return NetworkBuilders.KEYWORD_COOCCURRENCE;
        if (display.equals(Strings.get("net.term")) || display.equals("Term co-occurrence (title/abstract)")
                || display.equals("Term co-occurrence")) return NetworkBuilders.TERM_COOCCURRENCE;
        if (display.equals(Strings.get("net.author")) || display.equals("Co-authorship")) return NetworkBuilders.CO_AUTHOR;
        if (display.equals(Strings.get("net.cocitation")) || display.equals("Document co-citation")) return NetworkBuilders.CO_CITATION;
        if (display.equals(Strings.get("net.coupling")) || display.equals("Bibliographic coupling")) return NetworkBuilders.BIB_COUPLING;
        if (display.equals(Strings.get("net.institution")) || display.equals("Institution co-occurrence")) return NetworkBuilders.INSTITUTION;
        if (display.equals(Strings.get("net.aca")) || display.contains("ACA")) return NetworkBuilders.AUTHOR_COCITATION;
        if (display.equals(Strings.get("net.jca")) || display.contains("JCA")) return NetworkBuilders.JOURNAL_COCITATION;
        if (display.equals(Strings.get("net.category")) || display.contains("Category")) return NetworkBuilders.CATEGORY_COOCCURRENCE;
        if (display.equals(Strings.get("net.country")) || display.contains("Country")) return NetworkBuilders.COUNTRY_COOCCURRENCE;
        return NetworkBuilders.KEYWORD_COOCCURRENCE;
    }

    private String netKindZh(String key) {
        return switch (key) {
            case NetworkBuilders.TERM_COOCCURRENCE -> Strings.get("net.term");
            case NetworkBuilders.CO_AUTHOR -> Strings.get("net.author");
            case NetworkBuilders.CO_CITATION -> Strings.get("net.cocitation");
            case NetworkBuilders.BIB_COUPLING -> Strings.get("net.coupling");
            case NetworkBuilders.INSTITUTION -> Strings.get("net.institution");
            case NetworkBuilders.AUTHOR_COCITATION -> Strings.get("net.aca");
            case NetworkBuilders.JOURNAL_COCITATION -> Strings.get("net.jca");
            case NetworkBuilders.CATEGORY_COOCCURRENCE -> Strings.get("net.category");
            case NetworkBuilders.COUNTRY_COOCCURRENCE -> Strings.get("net.country");
            default -> Strings.get("net.keyword");
        };
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.get("report.dataset")).append(": ").append(project.name)
          .append("  |  ").append(project.records.size()).append(' ').append(Strings.get("report.records"))
          .append("  |  ").append(Strings.get("report.years")).append(": ")
          .append(project.minYear == Integer.MAX_VALUE ? "?" : project.minYear)
          .append("-").append(project.maxYear == Integer.MIN_VALUE ? "?" : project.maxYear).append('\n');
        sb.append(Strings.get("report.network")).append(": ").append(Strings.isZh() ? netKindZh(result.network.kind) : result.network.kind)
          .append("  |  ").append(result.network.nodes.size()).append(' ').append(Strings.get("report.nodes2"))
          .append("  |  ").append(result.network.edges.size()).append(' ').append(Strings.get("report.links2")).append('\n');
        sb.append(Strings.get("report.modularity")).append(" = ").append(String.format("%.4f%n", result.modularity));
        int clusters = (int) result.network.nodeCollection().stream().mapToInt(n -> n.cluster).distinct().count();
        sb.append(Strings.get("report.clusters")).append(": ").append(clusters).append('\n');
        if (result.clusterStats != null)
            for (Map.Entry<Integer, double[]> en : result.clusterStats.entrySet()) {
                double[] s = en.getValue();
                sb.append(String.format("  #%-2d  %s=%-4d  S=%.3f  %s≈%.1f%n",
                        en.getKey(), Strings.get("report.nodes2"), (int) s[0],
                        s[1], Strings.get("report.years"), s[2]));
            }
        for (Map.Entry<Integer, List<LLRLabeler.Scored>> en : result.clusterLabels.entrySet()) {
            sb.append("  #").append(en.getKey()).append(": ");
            for (int i = 0; i < en.getValue().size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(en.getValue().get(i).term)
                  .append(String.format(" (score %.1f)", en.getValue().get(i).g2));
            }
            sb.append('\n');
        }
        sb.append('\n').append(Strings.get("report.topnodes")).append('\n');
        result.network.nodeCollection().stream()
                .sorted((a, b) -> Integer.compare(b.weight, a.weight))
                .limit(12)
                .forEach(n -> sb.append(String.format("  %-28s %s=%-4d C_B=%.3f σ=%.1f #%d%n",
                        n.label, Strings.isZh() ? "频次" : "freq", n.weight, n.centrality, n.sigma, n.cluster)));
        long bursts = result.network.nodeCollection().stream().filter(n -> n.burstness > 0).count();
        sb.append('\n').append(Strings.get("report.burstnodes")).append(" ").append(bursts).append('\n');
        result.network.nodeCollection().stream()
                .filter(n -> n.burstness > 0)
                .sorted((a, b) -> Double.compare(b.sigma, a.sigma))
                .limit(10)
                .forEach(n -> {
                    StringBuilder iv = new StringBuilder();
                    for (int[] b : n.burstIntervals) {
                        if (iv.length() > 0) iv.append(' ');
                        iv.append(b[0]).append('-').append(b[1]);
                    }
                    sb.append(String.format("  %-28s %s %s  σ=%.1f%n", n.label, Strings.isZh() ? "突现" : "burst", iv, n.sigma));
                });
        return sb.toString();
    }

    private void citationReport() {
        if (result == null) { JOptionPane.showMessageDialog(this, Strings.get("report.nodata")); return; }
        report.setText(CitationReport.render(result.network, 20) + "\n\n" + buildReport());
        report.setCaretPosition(0);
    }

    /** CrossRef 在线检索：后台线程请求，结果直接进入新分析。 */
    private void crossrefSearch() {
        String q = JOptionPane.showInputDialog(this, Strings.get("crossref.prompt"), Strings.get("crossref"), JOptionPane.QUESTION_MESSAGE);
        if (q == null || q.trim().isEmpty()) return;
        final String query = q.trim();
        final JDialog busy = new JDialog(this, Strings.get("crossref"), true);
        busy.add(new JLabel("  " + Strings.get("crossref.searching") + "  ", SwingConstants.CENTER));
        busy.setSize(260, 90);
        busy.setLocationRelativeTo(this);
        SwingWorker<List<ycscite.model.Record>, Void> worker = new SwingWorker<>() {
            @Override protected List<ycscite.model.Record> doInBackground() throws Exception {
                return CrossRefClient.search(query, 100);
            }
            @Override protected void done() {
                busy.dispose();
                try {
                    List<ycscite.model.Record> recs = get();
                    if (recs.isEmpty()) { JOptionPane.showMessageDialog(MainFrame.this, Strings.get("search.miss")); return; }
                    Project p = new Project();
                    p.name = "crossref: " + query;
                    p.addAll(recs);
                    MainFrame.this.project = p;
                    MainFrame.this.runPipeline();
                    JOptionPane.showMessageDialog(MainFrame.this,
                            String.format(Strings.get("crossref.added"), recs.size()));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MainFrame.this,
                            Strings.get("crossref.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
        busy.setVisible(true);
    }

    private void exportPdf() {
        if (result == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("report.pdf"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            exportVisual(fc.getSelectedFile().toPath(),"pdf");
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportDocx() {
        if (result == null || project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("report.docx"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<String> paras = List.of(
                    Strings.get("report.dataset") + ": " + project.name
                            + "  " + Strings.get("report.records") + ": " + project.records.size()
                            + "  " + Strings.get("report.years") + ": " + project.minYear + "-" + project.maxYear,
                    Strings.get("report.network") + ": " + result.network.kind
                            + "  " + Strings.get("report.nodes2") + ": " + result.network.nodes.size()
                            + "  " + Strings.get("report.links2") + ": " + result.network.edges.size(),
                    String.format("%s = %.4f", Strings.get("report.modularity"), result.modularity),
                    Strings.get("report.topnodes"));
            DocxExporter.write(fc.getSelectedFile().toPath(), result.network,
                    "Ycscite — " + project.name, paras);
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportSqlDump() {
        if (result == null || project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("citespace-clone.sql"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String db = project.name.replaceAll("[^A-Za-z0-9_]", "_");
            MySqlStore.writeDump(project, result.network, db, fc.getSelectedFile().toPath());
            String hint = MySqlStore.driverAvailable()
                    ? Strings.get("sql.hint.found") : Strings.get("sql.hint.miss");
            JOptionPane.showMessageDialog(this,
                    Strings.get("dialog.saved") + "\nmysql < " + fc.getSelectedFile().getName() + "\n\n" + hint);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 逐年渲染当前视图并编码为 MJPEG AVI（年份演化视频）。 */
    private void exportVideo() {
        if (result == null || result.network.minYear == Integer.MAX_VALUE) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("evolution.avi"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        GraphCanvas c = currentCanvas();
        try (ycscite.io.AviWriter avi =
                     new ycscite.io.AviWriter(fc.getSelectedFile().toPath(), 1280, 720, 2)) {
            for (int y = result.network.minYear; y <= result.network.maxYear; y++) {
                c.setYearCutoff(y);
                avi.addFrame(c.renderImage(1280, 720));
            }
            c.setYearCutoff(result.network.maxYear);
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved") + "  (" + avi.framesWritten() + (Strings.isZh()?" 帧)":" frames)"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private GraphCanvas currentCanvas() { return (GraphCanvas) tabs.getComponentAt(tabs.getSelectedIndex()); }

    /** 打开项目：恢复数据集与分析状态（网络/指标/聚类/标注），不重算。 */
    private void openProject() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Ycscite project", "csl-proj"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        openProjectPath(fc.getSelectedFile().toPath());
    }

    private void openProjectPath(java.nio.file.Path path) {
        try {
            ProjectStore.Bundle b = ProjectStore.load(path);
            setBundle(b);
            RecentFiles.add(path.toString());
            rebuildRecentMenu();
            if (b.result != null) {
                JOptionPane.showMessageDialog(this, Strings.get("proj.loaded"));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("proj.loadfail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveProject() {
        if (project == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(project.name.replaceAll("[^A-Za-z0-9_\\-\\u4e00-\\u9fff]", "_") + ".csl-proj"));
        fc.setFileFilter(new FileNameExtensionFilter("Ycscite project", "csl-proj"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            saveEditable(fc.getSelectedFile().toPath());
            JOptionPane.showMessageDialog(this, Strings.get("proj.saved")+(hasPendingAnalysisOptions()?(Strings.isZh()?"\n尚未运行的分析参数更改未应用到当前结果。":"\nPending analysis settings have not been applied to this result."):""));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("proj.loadfail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 将载入的选项同步回界面控件。 */
    private void syncOptions(Pipeline.Options o) {
        if(o==null)return;inheritedOptions=ycscite.io.PipelineOptionsCodec.copy(o);
        networkKind.setSelectedItem(netKindZh(o.networkKind));topN.setValue(o.topN);
        clusterAlgo.setSelectedItem(Strings.get(Pipeline.EM.equals(o.clustering)?"algo.em":Pipeline.SPECTRAL.equals(o.clustering)?"algo.spectral":"algo.modularity"));
        String labelKey=switch(o.labeling.toUpperCase(java.util.Locale.ROOT)){case "LSI"->"label.lsi";case "ISI-KW"->"label.isikw";case "TFIDF"->"label.tfidf";case "MI"->"label.mi";case "KCORE"->"label.kcore";default->"label.llr";};labelAlgo.setSelectedItem(Strings.get(labelKey));
        fromYear.setValue(o.fromYear<=0&&project!=null&&project.minYear>0?project.minYear:o.fromYear);
        toYear.setValue(o.toYear>=9999&&project!=null&&project.maxYear>0?project.maxYear:o.toYear);
        sliceYears.setValue(o.sliceYears);topPerSlice.setValue(o.topNPerSlice);gindexK.setValue(o.gindexK);
        selectionBox.setSelectedItem(Strings.get(Pipeline.G_INDEX.equals(o.selection)?"sel.gindex":Pipeline.TOP_N_PCT.equals(o.selection)?"sel.topnpct":"sel.topn"));
        pruningBox.setSelectedItem(Strings.get("mst".equals(o.pruning)?"prune.mst":"pathfinder".equals(o.pruning)?"prune.pf":"prune.none"));
        minLinkStrength.setValue(o.minLinkStrength);largestCC.setValue(o.largestKComponents);minCluster.setValue(o.minClusterSize);singularCheck.setSelected(o.singularKeywords);
        String sourceKey=switch(o.labelSource.toUpperCase(java.util.Locale.ROOT)){case "T"->"src.t";case "A"->"src.a";case "WC"->"src.wc";case "R"->"src.r";case "TKA"->"src.tka";default->"src.k";};labelSource.setSelectedItem(Strings.get(sourceKey));
        spanInitialized=true;
    }

    private GraphCanvas[] canvases(){return new GraphCanvas[]{timeline,timezone,merged,tsne,circular,heatmap,landscape,umap,circlePack};}
    private GraphCanvas.Mode modeOf(GraphCanvas canvas){return canvas==timeline?GraphCanvas.Mode.TIMELINE:canvas==timezone?GraphCanvas.Mode.TIMEZONE:canvas==merged?GraphCanvas.Mode.MERGED:canvas==tsne?GraphCanvas.Mode.TSNE:canvas==circular?GraphCanvas.Mode.CIRCULAR:canvas==heatmap?GraphCanvas.Mode.HEATMAP:canvas==landscape?GraphCanvas.Mode.LANDSCAPE:canvas==umap?GraphCanvas.Mode.UMAP:GraphCanvas.Mode.CIRCLE_PACK;}
    public Map<String,Object> capturePresentation(){
        if(result==null)return Map.of();var views=new java.util.LinkedHashMap<String,Object>();for(var canvas:canvases())views.put(canvas.getMode().name(),canvas.captureState());
        return ViewState.editor(Map.of("schema_version",1,"language",Strings.isZh()?"zh":"en","active_view",currentCanvas().getMode().name(),"sidebar_tab",((JTabbedPane)((BorderLayout)getContentPane().getLayout()).getLayoutComponent(BorderLayout.WEST)).getSelectedIndex(),"canvases",views),result.network);
    }
    public void saveEditable(Path path)throws java.io.IOException{
        if(rendering)throw new java.io.IOException(Strings.isZh()?"布局仍在计算，请稍后保存":"Layout is still running; save when it finishes");
        ProjectStore.save(path,project,result,result==null?currentOptions():analyzedOptions,includeLogo.isSelected(),capturePresentation());
    }
    public boolean hasPendingAnalysisOptions(){
        if(result==null)return false;var actual=ycscite.io.PipelineOptionsCodec.copy(analyzedOptions);var pending=currentOptions();
        if(project!=null){if(actual.fromYear==0)actual.fromYear=project.minYear;if(pending.fromYear==0)pending.fromYear=project.minYear;if(actual.toYear>=9999)actual.toYear=project.maxYear;if(pending.toYear>=9999)pending.toYear=project.maxYear;}
        return !ycscite.io.PipelineOptionsCodec.write(actual).equals(ycscite.io.PipelineOptionsCodec.write(pending));
    }
    private void syncDisplayControls(){
        if(result==null)return;var options=MiniJson.obj(currentCanvas().captureState().get("display"));syncingDisplayControls=true;
        try{
            labelSlider.setValue(((Number)options.get("label_budget")).intValue());fontSpin.setValue(((Number)options.get("font_size")).doubleValue());
            fisheyeCheck.setSelected((Boolean)options.get("fisheye"));hullCheck.setSelected((Boolean)options.get("convex_hull"));shadowCheck.setSelected((Boolean)options.get("label_shadow"));
            linkLabelsCheck.setSelected((Boolean)options.get("link_labels"));clusterIdCheck.setSelected((Boolean)options.get("cluster_ids"));citeCountCheck.setSelected((Boolean)options.get("citation_counts"));labelClusterColorCheck.setSelected((Boolean)options.get("cluster_label_colors"));
            rescaleSlider.setValue((int)Math.round(MiniJson.num(options.get("node_scale"))*100));kcoreSpin.setValue(((Number)options.get("kcore")).intValue());linkMonoCheck.setSelected((Boolean)options.get("single_color_links"));
            fillPatternBox.setSelectedIndex(((Number)options.get("fill_pattern")).intValue());curvedCheck.setSelected((Boolean)options.get("curved_links"));clusterLinkCheck.setSelected((Boolean)options.get("cluster_links"));effects3dCheck.setSelected((Boolean)options.get("effects_3d"));Integer angle=((Number)options.get("label_angle")).intValue();boolean listed=false;for(int i=0;i<angleBox.getItemCount();i++)listed|=angleBox.getItemAt(i).equals(angle);if(!listed)angleBox.addItem(angle);angleBox.setSelectedItem(angle);
            shapeBox.setSelectedItem(Strings.get("shape."+((String)options.get("shape")).toLowerCase(java.util.Locale.ROOT)));
            String size=switch((String)options.get("size_mode")){case "FREQ"->"freq";case "CENTRALITY"->"centrality";case "DEGREE"->"degree";case "EIGEN"->"eigen";case "PAGERANK"->"pagerank";case "ENTROPY"->"entropy";case "RELATIVE_ENTROPY"->"relent";case "INFO_BIAS"->"bias";case "UNIFORM"->"uniform";default->"sigma";};sizeMode.setSelectedItem(Strings.get("size."+size));
            layoutAlgo.setSelectedItem(Strings.get("layout."+switch((String)options.get("layout")){case "KAMADA_KAWAI"->"kk";case "FORCE_DIRECTED"->"fd";default->"linlog";}));
        }finally{syncingDisplayControls=false;}
    }

    public Map<String,Object> displayGeometry(){
        var west=(JTabbedPane)((BorderLayout)getContentPane().getLayout()).getLayoutComponent(BorderLayout.WEST);var scroll=(JScrollPane)west.getComponentAt(2);var viewport=scroll.getViewport();var panel=(JPanel)viewport.getView();
        var controls=new java.util.ArrayList<Map<String,Object>>();for(Component component:panel.getComponents())if(!(component instanceof Box.Filler)){
            var entry=new java.util.LinkedHashMap<String,Object>();entry.put("type",component.getClass().getSimpleName());entry.put("x",component.getX());entry.put("width",component.getWidth());
            if(component instanceof AbstractButton button)entry.put("label",button.getText());else if(component instanceof JLabel label)entry.put("label",label.getText());controls.add(entry);
        }
        return Map.of("viewport_width",viewport.getExtentSize().width,"panel_width",panel.getWidth(),"horizontal_scroll",scroll.getHorizontalScrollBar().isVisible(),"controls",controls);
    }
    public void setExportLogo(boolean value){includeLogo.setSelected(value);}
    /** Shared by the actual export buttons and the editor integration checks. */
    public void exportVisual(Path path,String format)throws Exception{
        if(result==null)throw new IllegalStateException("No analyzed network");
        try(var language=ycscite.I18n.language(Strings.isZh()?"zh":"en")){
            switch(format){
                case "png"->{currentCanvas().snapshot(path,1600,1000);Branding.variants(path,includeLogo.isSelected());}
                case "svg"->{currentCanvas().writeSvg(path,2400,1500);Branding.svgVariants(path,includeLogo.isSelected(),2400,1500);}
                case "pdf"->{var image=currentCanvas().renderImage(1600,1000);try{PdfExporter.variants(path,image,result.network,project==null?"Ycscite":project.name,includeLogo.isSelected());}finally{image.flush();}}
                default->throw new IllegalArgumentException("Unknown visual export: "+format);
            }
        }
    }
    private void exportSvg(){
        if(result==null)return;var chooser=new JFileChooser();chooser.setSelectedFile(new File("network.svg"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        try{exportVisual(chooser.getSelectedFile().toPath(),"svg");JOptionPane.showMessageDialog(this,Strings.get("dialog.saved"));}catch(Exception error){JOptionPane.showMessageDialog(this,Strings.get("dialog.fail")+error.getMessage(),"Error",JOptionPane.ERROR_MESSAGE);}
    }
    private void exportPng() {
        if (result == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("network.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            exportVisual(fc.getSelectedFile().toPath(),"png");
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportHtml() {
        if (result == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("network.html"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Exporters.writeHtml(result.network, result.clusterLabels, fc.getSelectedFile().toPath());
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved"));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportTables() {
        if (result == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("nodes.tsv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path nodes = fc.getSelectedFile().toPath();
            Path edges = nodes.resolveSibling("edges.tsv");
            Exporters.writeTables(result.network, result.clusterLabels, nodes, edges);
            Path cit = nodes.resolveSibling("citation-report.tsv");
            CitationReport.writeTsv(result.network, 0, cit);
            JOptionPane.showMessageDialog(this, Strings.get("dialog.saved") + "\n" + nodes.getFileName()
                    + " + " + edges.getFileName() + " + " + cit.getFileName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, Strings.get("dialog.fail") + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
