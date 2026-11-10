"""Public CLI/headless integration checks using only fictional example data (stdlib only)."""
from argparse import ArgumentParser
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tempfile


def main():
    parser = ArgumentParser()
    parser.add_argument("--cli", type=Path, required=True)
    args = parser.parse_args()
    cli = args.cli.resolve()
    root = Path(__file__).resolve().parents[1]
    sample = root / "examples" / "demo-records.tsv"
    with tempfile.TemporaryDirectory(prefix="ycscite-public-smoke-") as temporary:
        work = Path(temporary)

        def run(arguments, expected=0, stdin=None):
            result = subprocess.run([str(cli), *map(str, arguments)], input=stdin,
                                    cwd=work, capture_output=True, encoding="utf-8", timeout=180)
            if result.returncode != expected:
                raise AssertionError(f"{arguments}: exit {result.returncode}, expected {expected}\n{result.stderr}\n{result.stdout}")
            return result

        doctor = json.loads(run(["--json", "doctor"]).stdout)
        assert doctor["engine_available"] and doctor["headless"]
        caps = json.loads(run(["capabilities"]).stdout)
        assert caps["version"] == doctor["version"]
        assert len(caps["charts"]) == 18 and len(caps["formats"]) == 13
        inspection = json.loads(run(["inspect", sample, "--json"]).stdout)
        assert inspection["ok"] and inspection["result"]["records"] == 72
        settings = dict(caps["default_settings"])
        settings.update(language="en", logo=False, image_scale=1,
                        charts=[chart["key"] for chart in caps["charts"]],
                        analysis_modules=[module["key"] for module in caps["analysis_modules"]],
                        exports=["png", "tsv", "html", "pdf", "svg", "docx", "sql", "pajek", "gexf", "ris", "records-tsv"])
        config = work / "settings.json"
        config.write_text(json.dumps(settings), encoding="utf-8")
        out = work / "all-charts"
        report = json.loads(run(["report", sample, "--config", config, "--out", out, "--json"]).stdout)
        manifest = report["manifest"]
        assert manifest["source_fingerprint"] == inspection["result"]["source_fingerprint"]
        assert manifest["engine_version"] == caps["version"]
        assert report["ok"] and manifest["records"] == 72 and len(manifest["figures"]) == 18
        for chart in manifest["figures"]:
            plain, branded = out / chart["no_logo_file"], out / chart["with_logo_file"]
            assert plain.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"
            assert branded.is_file() and hashlib.sha256(plain.read_bytes()).digest() == hashlib.sha256((out / chart["file"]).read_bytes()).digest()
        assert (out / "report.pdf").read_bytes().startswith(b"%PDF")
        assert b"/Count 18" in (out / "report.pdf").read_bytes()
        assert all((out / "data" / name).is_file() for name in manifest["data_files"])
        assert all((out / item["file"]).is_file() for item in manifest["artifacts"])
        for editable in manifest["project_files"]:
            state=json.loads((out/editable["file"]).read_text(encoding="utf-8"))
            stats=next(n for n in manifest["networks"] if n["kind"]==editable["kind"])
            assert len(state["analysis"]["nodes"])==stats["nodes"] and len(state["analysis"]["edges"])==stats["edges"]
            assert state["analysis"]["modularity"]==stats["modularity"]
        record_copy=out/"records.tsv"
        again=json.loads(run(["inspect",record_copy,"--json"]).stdout)["result"]
        assert again["source_fingerprint"]==manifest["analysis_fingerprint"]

        requests = [
            '{"id":9007199254740993,"method":"capabilities"}',
            '{broken',
            '{"id":"invalid","method":"validate_settings","params":{"settings":{"slice_years":0}}}',
            json.dumps({"id":"inspect","method":"inspect","params":{"inputs":[str(sample)]}}),
        ]
        stream = run(["headless"], stdin="\n".join(requests) + "\n")
        responses = [json.loads(line) for line in stream.stdout.splitlines()]
        assert len(responses) == 4 and responses[0]["id"] == 9007199254740993
        assert not responses[1]["ok"] and not responses[2]["ok"] and responses[3]["ok"]
        shutil.copyfile(sample, work / "demo.tsv")
        batch = [
            {"id":"first","method":"inspect","params":{"inputs":["demo.tsv"]}},
            {"id":"bad","method":"inspect","params":{"inputs":["missing.txt"]}},
            {"id":"last","method":"inspect","params":{"inputs":["demo.tsv"]}},
        ]
        (work / "batch.json").write_text(json.dumps(batch), encoding="utf-8")
        result = json.loads(run(["batch", work / "batch.json"], expected=1).stdout)
        assert result["result"]["succeeded"] == 2 and result["result"]["failed"] == 1
        (work / "request.json").write_text(json.dumps(batch[0]), encoding="utf-8")
        assert json.loads(run(["headless", "--request", work / "request.json"]).stdout)["result"]["records"] == 72
        run(["report"], expected=2)
        run(["report", work / "missing.txt", "--json"], expected=1)
        print(json.dumps({"ok":True,"charts":18,"formats":13,"records":72,
                          "checks":["unrelated-cwd", "config", "all-charts", "all-analysis-tables", "all-exports",
                                    "logo-variants", "pdf-pages", "editable-projects", "lossless-records-tsv", "ndjson-framing", "integer-id", "error-recovery",
                                    "batch-isolation", "request-relative-paths", "exit-codes"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
