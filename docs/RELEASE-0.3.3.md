# Ycscite 0.3.3

本版完善可编辑分析流程与文献数据完整性，保留既有九图绘制风格。

## 用户可见变化

- 高级交互窗口可以恢复当前结果的实际网络与完整参数，避免重新变成 Top N=50 等默认设置。当前参数已修改时，按新设置打开分析。
- 每个报告在 `projects/` 中保存关键词、作者、机构或扩展网络的可编辑 `.csl-proj` 项目；Web/EXE 可直接下载当前视图对应的项目，离线报告页也提供链接。
- 项目保存保留学科字段、完整选点/相似度/剪枝/标注/突现参数、额外中心性、节点书签、批注、自定义名称、完整突现列表和时间范围。高级窗口切换中英文和撤销网络编辑时也保留当前分析与元数据。
- 记录级 TSV v2 保留多值字段内的分号、换行、制表符与空白，同时继续读取旧版17列文件。去重后重新计算实际年份范围。
- 修正加权 PageRank：以边权总和归一化，避免大权重或相似度网络中的无效数值；已加入闭式小图解、边权单位缩放不变性和大权重有限性验证。
- 高级窗口使用浅暖色控件和明亮画布，改善标签大小与对比度，移除过期的“1.0/全功能复刻”说明。
- 便携运行时补齐 `jdk.accessibility`，修复启用 Java 辅助功能的机器上高级窗口无法启动的问题；不修改系统辅助功能配置。
- 清单新增 `analysis_fingerprint`，区分原始语料和筛选/去重后实际纳入的记录。

## 验证

- 358 项 Java 检查与 8 项 Rust 检查通过。
- 实际 Swing 窗口验证非默认设置、可编辑网络恢复、中英文界面和节点/连接/指标精确一致。
- 本机 WebView2/EXE 验证当前项目下载、恢复已有项目、传递修改后参数和拒绝未注册项目路径。
- 真实原始语料生成九图，与0.3.2对照的27个PNG版本逐个SHA-256一致；本轮未改变用户已认可的九图画法。
- 开发版和完整便携版均执行 CLI/headless、工作区和编辑器回归；远端 Windows/Linux CI检查源码构建，Windows额外验证实际编辑器和便携包。

## 使用

完整解压 `Ycscite-0.3.3-windows-x64.zip`，双击 `Ycscite.exe`。Java21运行时已附带；桌面外壳需要系统WebView2。

```powershell
.\ycscite-cli.exe workbench --project "D:\结果\projects\authors.csl-proj" --lang en
.\ycscite-cli.exe workbench "D:\文献.ris" --config settings.json
```

客户端为Rust，分析引擎为Java21。实验性方法与标注词源的实际限制见能力文档，本版不宣称完整复刻或全面优于CiteSpace。

---

This release connects the advanced editor to the current analysis rather than its older defaults. Reports now include reusable editable projects, preserving the included corpus, exact network, complete options and manual annotations. Record TSV v2 prevents delimiter/control-character data loss while remaining compatible with older imports. Weighted PageRank now uses properly normalized transitions. The established nine-chart images are unchanged in the real-corpus regression.

Extract the entire portable ZIP. Java21 is bundled; the desktop shell requires WebView2. Refer to the bilingual user guides and capability definitions for method scope and experimental limitations.
