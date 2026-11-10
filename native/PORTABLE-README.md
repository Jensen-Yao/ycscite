# Ycscite0.3.2 · Windows portable / Windows 便携版

完整解压目录，双击 `Ycscite.exe`。选择数据，查看预检，在上方选中需要的图，点击「运行分析」。一次只生成当前图；批量导出时自行勾选多图。第一次可以点击「使用示例」。左侧可直接调整常用参数，其他设置在「分析设置」中。English 可切换界面和通用图文标签。下载时可切换 Logo 显隐。

Extract the entire folder and run `Ycscite.exe`. Use the synthetic demo or import your literature, select the view you need, and run it. Each run generates only that view; batch export is a separate action. The local browser launcher uses the same workbench. Do not move only one EXE: retain engine/ and runtime/. Java21 is bundled. The desktop window requires system Edge WebView2; CLI/headless do not.

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

Read docs/USER-GUIDE.en.md or docs/USER-GUIDE.zh.md for the workflow; docs/HEADLESS.md for automation; docs/CAPABILITIES.md for scientific definitions and limitations. Some advanced methods are simplified/experimental and do not claim CiteSpace equivalence. The client is Rust; the bundled analysis engine is Java21.
