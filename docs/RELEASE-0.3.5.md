# Ycscite 0.3.5

本版完善手动排版的保存、恢复和无界面重导出，保留既有九图画法。

## 改进

- 高级窗口可拖动节点调整位置、拖动空白平移；时间线与时区图固定表示年份的横坐标，仅允许纵向调整。
- 项目按视图保存最终坐标、字号、节点/连线样式、透明度、筛选、年份截止、高亮、叠加、镜头中心与相对缩放，以及当前图、设置页和语言。重开、切换语言或撤销网络编辑可恢复相应状态。
- 设置面板按窗口宽度换行，仅纵向滚动，避免说明文本和复选项撑出画面；增加实际控件边界检查。
- 显示页增加「适应窗口」和「显示全部节点」，恢复视角/筛选不重新计算科学指标；可见控件同步所选视图的保存值。
- 新增正式 CLI `project inspect`、`project export` 与同名 headless 方法。可直接读取可编辑项目，无需原始输入目录或窗口，不重跑筛选、聚类和指标计算。
- 项目导出默认只生成一张 PNG；自行选择 SVG、PDF、数据表，指定视图、图片尺寸、中英文和 Logo。清单记录是否恢复了保存视图，以及完整网络和可见节点的数量。
- 保存项目时记录已计算结果的实际分析参数，避免把旧网络与尚未运行的新参数混在一起；另行提示未应用的更改。
- 保存视图绑定节点、连接和指标的网络身份，校验版本、坐标、选项与引用；损坏或不匹配的数据会报错，旧项目仍可打开并计算所选视图排版。
- 项目先写完整临时文件后替换，拒绝的状态不会覆盖旧文件。报告项目在尚无手动排版时也保留用户字号、节点大小和连线透明度等显示参数。
- 修正标签角度控件未生效、部分显示设置未联动全部视图的问题；文献节点名称即使与界面术语同名，也保留原文。

## 验证

- 414 项 Java 检查、10 项 Rust 检查通过。
- 实际 Swing 鼠标事件验证拖动节点；同尺寸画布和导出图像像素一致；跨窗口尺寸保留镜头中心与相对缩放。
- 实际编辑器保存/恢复手工位置、筛选及样式，再与无界面项目导出的 PNG/PDF 逐字节核对；SVG在相同指定尺寸下逐字节一致。
- CLI/headless 测试覆盖项目检查、默认单图、格式选择、相对路径请求、错误隔离和旧项目兼容。
- 完整便携运行时验证编辑器与项目导出，Windows/Linux CI验证源码与无界面入口，Windows额外运行编辑器和便携包测试。
- 真实原始语料的九图27个 PNG 版本与既有0.3.2基线逐个SHA-256一致，本轮未重画用户已认可的九图。

## 使用

完整解压 `Ycscite-0.3.5-windows-x64.zip`，双击 `Ycscite.exe`。Java21已附带；桌面外壳使用系统WebView2。

```powershell
.\ycscite-cli.exe project inspect edited.csl-proj
.\ycscite-cli.exe project export edited.csl-proj --out figures --formats png,pdf --no-logo
.\ycscite-cli.exe project export edited.csl-proj --out vectors --formats svg --width 2400 --height 1500
```

PNG/PDF 使用完整画幅与保留的节点位置/样式，镜头平移缩放用于恢复编辑窗口。数据表与PDF指标页包含完整科学网络；视觉隐藏不删除节点。SVG为节点/连接视图，不是每种特殊图形的矢量重建。原有实验性方法与科学解释限制仍见能力文档。

---

This release preserves manual graph presentation per view and adds GUI-free `project inspect` / `project export` commands, also available through NDJSON. Projects retain final coordinates, styles, filters, camera and editor context, with graph-bound validation and safe replacement. The default export is one PNG; other formats are explicitly selected. Reopening a saved view preserves the analyzed network and metrics without rerunning the pipeline. Established nine-chart PNGs remain unchanged in the real-corpus regression.

Extract the full portable ZIP. Java21 is bundled; the desktop requires WebView2. See the bilingual guides for output scope, method definitions and experimental limitations.
