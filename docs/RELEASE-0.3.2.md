# Ycscite 0.3.2

本地文献计量工作台，支持 Windows EXE、Web、Rust CLI 和无 GUI 的 NDJSON/headless。

## 本次交付

- 可以独立生成任意视图；九种常用图保持既有绘制方式，扩展图独立选择，批量导出按勾选列表运行。
- 左侧参数、中央图谱与数据表、右侧指标/节点/解释的学术工作台；暖白、炭灰和陶土色界面，内置 Noto Sans SC 与 Lora 字体。
- 提高暖色界面中的小字号辅助文字对比度，减少浅色文字造成的阅读负担。
- 本机工作区自动保存语言、参数、数据路径、当前视图与结果；跨端口与桌面重启恢复，不会自动重跑。
- Web/EXE 每次运行创建独立结果目录，保留历史参数与图片；保存失败有明确反馈，损坏的工作区保留恢复副本。
- 解析文献的 SHA-256 身份与引擎版本写入清单；同一路径下的数据更新会把原结果标为待更新，旧报告保留可查看。
- 13 种输入格式目录、10 类网络、3 种聚类、6 类标注、18 种图型及附加分析表；参数可通过 CLI/headless 复用。
- PNG、SVG 网络总览和 PDF 图册有带 Logo / 无 Logo 两种版本；保留原始数据区。
- 修正 Rust JSON 浮点往返造成的指标末位变化，历史清单以磁盘原文件为准。

## 验证

- 335 项 Java 检查、7 项 Rust 检查通过。
- 开发构建和完整便携包通过 CLI/headless 与工作区集成检查。
- 实际 Windows WebView2/EXE 中验证单图、任意批量选择、双语、表格、字体加载、Logo 与 PNG/PDF/SVG 下载；1440、1024、768、390 宽度检查通过。
- 原生窗口重启后更换了本机端口，仍恢复语言、视图、参数和已有结果，未额外启动分析。
- 本机真实语料以相同设置从 EXE、CLI 和独立 Web 服务生成九图，27 个 PNG 版本逐个 SHA-256 一致。真实语料与报告未随源码分发。

## 使用

完整解压 `Ycscite-0.3.2-windows-x64.zip`，双击 `Ycscite.exe`。Java 21 运行时已附带；桌面窗口需要系统 WebView2。CLI/headless 不需要浏览器或 WebView2。

客户端与本机服务为 Rust，分析绘图引擎为 Java 21。t-SNE 为图结构探索，UMAP 为近似实现，其他方法的数据条件与限制见 `docs/CAPABILITIES.md`。不宣称像素级复刻 CiteSpace；报告中的期刊引文流不使用 JCR 学科底图。

---

Local bibliometric workbench with a Windows EXE, Web UI, Rust CLI and GUI-free headless protocol. Select any individual view or explicitly choose a batch. This version adds durable workspace restoration, separate Web/desktop run folders, source-record identity, precise metric round trips, bilingual scientific controls and bundled offline typography. The original nine-chart renderer is preserved.

Extract the entire portable ZIP. Java 21 is bundled; the desktop requires system WebView2. See the bilingual user guides and capability definitions before interpreting exploratory analyses.
