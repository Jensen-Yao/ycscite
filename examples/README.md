# Synthetic examples / 合成演示数据

All records here are fictional and must not be cited as publications. `demo-records.tsv` contains 72 records, six overlapping topics, collaborating authors/institutions and synthetic cited references. It is suitable for trying all views without supplying personal data.

本目录全部为合成记录。`demo-records.tsv` 可用于体验全部图型；作者、机构、期刊及引文均为虚构，不得用于真实研究结论。`mini.ris` 与 `mini.bib` 是格式导入的独立小样例。

```powershell
.\ycscite-cli.exe inspect "examples/demo-records.tsv" --json
.\ycscite-cli.exe analyze "examples/demo-records.tsv" --out "examples/generated/custom" --lang en
.\ycscite-cli.exe headless --request "examples/request.json"
```

Request-file paths resolve relative to the request file. Streaming NDJSON paths resolve relative to the process working directory.
