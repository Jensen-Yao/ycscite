---
name: ycscite
description: 使用本机 Ycscite 按需生成文献分析图或完整九图、复用参数、运行 CLI/headless、打开 Web 工作台。
---

先确定完整便携包中的 `ycscite-cli.exe`，或项目开发构建 `native/target/release/ycscite.exe`。命令可使用绝对路径从任意目录调用。先执行 `--json doctor`，确认 `engine_available=true`；发布包应同时有 `bundled_runtime=true`。

先确定用户要哪张图。单图用 `analyze <输入...> --charts timeline --out <目录> --json`（将 timeline 换成所需 key）；多图用显式逗号列表。用户要求完整九图时，运行 `report <目录或文件...> --out <输出目录> --json`；标准输出是一份 JSON，进度在标准错误，退出码 0 表示成功，1 表示分析失败，2 表示参数错误。检查清单的文献数、年份、网络统计与所选图文件，再实际查看图像。不要把九个文件存在当作图形已达到原图要求。

无需账号、令牌或在线服务。Web 的内部会话令牌由本机进程产生，不写入 CLI 参数。不要读取或输出 Web 会话令牌。

```powershell
& "D:\Ycscite\ycscite-cli.exe" --json doctor
& "D:\Ycscite\ycscite-cli.exe" report "D:\文献目录" --out "D:\九图报告" --json
& "D:\Ycscite\ycscite-cli.exe" serve --report "D:\九图报告"
```

高级逃生入口：`engine analyze ...`、`engine convert ...` 等沿用既有分析器；加 `--json` 会返回包含 stdout/stderr/exit_code 的 JSON 包装。详细选项用 `engine help` 查询。

输入不会上传外部服务。不要擅自更改原始数据、覆盖用户明确要求保留的报告、上传或发布结果。重复使用同一输出目录会更新报告文件。原版参照仅限用户指定原图，不以历史误操作的截图替代。

`capabilities.version` 与 doctor/manifest 的 engine_version 应一致。`inspect.result.source_fingerprint` 应等于报告的 `source_fingerprint`；参数与源数据身份必须对应。Web/EXE 每次运行建立独立子目录并自动保存工作区；CLI/headless 的 --out 保持实际报告目录语义，显式复用可能更新同名文件。

已有用户手工排版的项目时优先使用 `project inspect <文件>` 核对网络与保存视图，再用 `project export <文件> --out <目录> --formats png,pdf` 重导出；默认单张PNG，按用户明确需要选择格式。该入口不重新筛选/聚类。没有保存布局时会计算所选视图的布局，清单中的 restored_saved_view 可区分。视觉隐藏不删除科学网络，表格仍为完整网络。
