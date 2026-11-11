# Ycscite · Windows portable / Windows 便携版

完整解压目录，双击 `Ycscite.exe`。选择数据，查看预检，在上方选中需要的图，点击「运行分析」。「运行分析」只生成当前图；顶部「一键默认九图」使用当前数据和分析参数生成完整九图；批量导出时自行勾选多图。第一次可以点击「使用示例」。左侧可直接调整常用参数，其他设置在「分析设置」中。English 可切换界面和通用图文标签。下载时可切换 Logo 显隐。

Extract the entire folder and run `Ycscite.exe`. Use the synthetic demo or import your literature, select the view you need, and run it. Run analysis generates the selected view. Generate default nine charts produces all nine standard charts with the current data and analysis settings; Batch export lets you choose any combination. The local browser launcher uses the same workbench. Do not move only one EXE: retain engine/ and runtime/. Java21 is bundled. The desktop window requires system Edge WebView2; CLI/headless do not.

```powershell
.\ycscite-cli.exe --json doctor
.\ycscite-cli.exe inspect examples/demo-records.tsv --json
.\ycscite-cli.exe report "D:\文献" --out "D:\报告" --no-logo --json
.\ycscite-cli.exe analyze examples/demo-records.tsv --charts network,circular,thematic --lang en --out results
.\ycscite-cli.exe headless --request examples/request.json
.\ycscite-cli.exe batch examples/batch.json
.\ycscite-cli.exe serve
```

`report --json` only prints JSON to stdout; progress goes to stderr. Streaming headless accepts NDJSON on stdin and returns one response per line. Exit0=success,1=failure,2=invalid CLI arguments. A streaming service continues after a per-request error; inspect each response.

数据默认保存在 `%LOCALAPPDATA%/Ycscite`。`YCSCITE_DATA_HOME` 可更改位置。报告包含参数、源数据身份、清单与数据表。Web/EXE 在保存位置下为每次运行建立独立子目录；CLI/headless 的显式输出目录保持精确路径语义，复用时更新同名文件。语言、参数、数据路径、视图和结果会自动保存，重开不会自动重跑。顶部提示保存状态。关闭窗口会停止本次应用启动的分析进程。

Read docs/USER-GUIDE.en.md or docs/USER-GUIDE.zh.md for the workflow; docs/HEADLESS.md for automation; docs/CAPABILITIES.md for scientific definitions and limitations. Advanced analyses identify simplified or experimental methods and their data requirements in the capability inventory. The client is Rust; the bundled analysis engine is Java21.

可在下载格式中选择「可编辑项目」。高级窗口会恢复当前分析网络，或使用当前设置，支持保存批注、书签与重命名。`workbench --project <文件>` 可直接恢复项目。记录级 TSV 新格式保留多值字段内的分号和换行，并兼容旧版导入。

Choose Editable project to retain the current network, corpus and complete options. The advanced editor restores that result or uses the current settings; it preserves bookmarks, annotations and renamed labels when saved. `workbench --project <file>` restores a project directly.

高级窗口「导出」页和「文件」菜单提供图片 Logo 选项。PNG、SVG、PDF 均保留两种版本；页脚独立于数据区，透明 PNG 不失去透明度。CLI 可使用 --logo / --no-logo；PDF 图形不拉伸，中文摘要可读。

The advanced editor's Export tab/File menu includes the logo choice. Static PNG/SVG/PDF keep both variants, preserving chart data; transparent PNG remains transparent. Use --logo / --no-logo in CLI exports. PDF pages preserve aspect ratio and CJK labels.

项目现可保留各视图的手动节点位置、显示样式、筛选及镜头。用 `project inspect <文件>` 查看，`project export <文件> --out figures --formats png,pdf` 无界面重导出；默认只生成PNG。高级窗口拖节点调整，拖空白平移，时间视图锁定年份横坐标。

Saved projects retain per-view manual positions, display settings, filters and camera. Use `project inspect <file>` and `project export <file> --out figures --formats png,pdf` without GUI; one PNG is the default. Time-axis views keep year-encoding horizontal positions fixed.
