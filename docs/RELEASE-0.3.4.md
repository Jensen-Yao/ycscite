# Ycscite 0.3.4

本版统一静态图片导出的 Logo 选择，并修复 PDF 的比例、清晰度和中文摘要问题；原有九图画法保持不变。

## 改进

- 报告下载、高级窗口、旧分析 CLI、已保存项目与 headless 调用中的静态 PNG/SVG/PDF 均支持带 Logo / 无 Logo 两种版本。新增对称的 `--logo` / `--no-logo` 覆盖，冲突参数会报错。
- 高级窗口的「导出」页和「文件」菜单提供共享 Logo 开关，增加网络 SVG 导出；项目保存保留选择，从主工作台打开高级窗口也会传递当前下载选择。
- Logo 仅放在独立页脚，图形数据区保持相同。透明 PNG 保留原始 RGBA；SVG 仅扩展根视口，保留内部几何尺寸；反复切换不会累加 SVG 页脚。
- PDF 按图像比例排版，改用无损 RGB/Flate 图像流。高级窗口和旧 CLI 的第二页指标摘要保留中文名称，不再替换成问号。透明栅格在 PDF 中合成到白底。
- 导出不重置高级窗口中的缩放、平移或组件尺寸；SVG 保留自定义名称并使用与系统区域设置无关的小数坐标。
- 旧 CLI 显式请求 SVG、t-SNE 等独立图时不附带无关的默认 PNG 套件。数据不足时生成解释性空状态图片，避免对旧目录中残留的同名图重复加标识。

## 验证

- 381 项 Java 检查与 9 项 Rust 检查通过。
- PNG RGBA 与数据区逐像素检查、SVG 内部尺寸/重复调用/区域设置检查、PDF 解压后的 RGB 与比例检查，以及导出后界面视口保持检查通过。
- 实际 EXE 中核对 Logo 两种版本的数据区哈希一致、图片尺寸一致、PDF/SVG 下载与高级窗口 Logo 参数传递。
- 实际 Swing 窗口与完整便携运行时验证 Logo 设置保存、覆盖和 PNG/SVG/PDF 导出；开发与便携包的 CLI/headless/工作区回归通过。
- 真实原始语料的九图共27个 PNG 版本与既有0.3.2基线逐个SHA-256相同，另保留可编辑项目。
- Windows/Linux CI构建验证源码，Windows额外验证实际编辑器与完整便携包。

## 使用

完整解压 `Ycscite-0.3.4-windows-x64.zip`，双击 `Ycscite.exe`。Java21已附带；桌面外壳使用系统WebView2。

```powershell
.\ycscite-cli.exe report papers.ris --config settings.json --logo --out result
.\ycscite-cli.exe --json engine analyze papers.ris --svg --pdf --no-logo --lang en --out figures
.\ycscite-cli.exe workbench --project result/projects/keywords.csl-proj --no-logo
```

两种图片文件分别保存在 `with-logo/` 和 `no-logo/` 中。PDF仍为图像页面，完整数值与名称可从数据表取得；网络SVG不等同于每一种特殊视图的矢量重建。视频与数据表采用各自的输出契约。客户端为Rust，分析引擎为Java21；方法范围与实验性限制仍以能力文档为准。

---

This release makes static PNG/SVG/PDF logo choice consistent across report downloads, the advanced editor, legacy CLI, saved projects and headless calls. It preserves chart data and transparent PNG alpha, uses lossless aspect-preserving PDF pages with readable CJK summaries, and keeps the live editor viewport unchanged during export. Explicit single-view legacy requests avoid unrelated image bundles, and insufficient-data figures replace stale outputs with clear explanations.

Extract the full portable ZIP. Java21 is bundled; the desktop requires system WebView2. Refer to the bilingual guides and capability definitions for method scope and experimental limitations.
