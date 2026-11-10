"""Exercise durable app state through real authenticated local servers (stdlib only)."""
from argparse import ArgumentParser
from pathlib import Path
from contextlib import ExitStack
import json
import os
import queue
import re
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request


class LocalServer:
    def __init__(self, cli, store, cwd, report=None):
        env = dict(os.environ, YCSCITE_DATA_HOME=str(store))
        command = [str(cli), "serve", "--port", "0", "--no-open"]
        if report:
            command.extend(["--report", str(report)])
        self.process = subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
        lines = queue.Queue()
        def read_lines():
            for line in self.process.stdout:
                lines.put(line)
        threading.Thread(target=read_lines, daemon=True).start()
        try:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                line = lines.get(timeout=max(.1, deadline-time.monotonic()))
                match = re.search(r"http://127\.0\.0\.1:\d+/", line)
                if match:
                    self.url = match.group(0)
                    break
            else:
                raise RuntimeError("Local server did not publish its URL")
            html = urllib.request.urlopen(self.url, timeout=10).read().decode("utf-8")
            self.token = re.search(r'name="ycscite-session" content="([a-f0-9]+)"', html).group(1)
        except Exception:
            self.close()
            raise

    def request(self, endpoint, data=None, expected=200, token=None):
        request = urllib.request.Request(self.url+"api/"+endpoint,
            data=None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8"),
            headers={"X-Ycscite-Token": token or self.token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as response:
            status, body = response.code, response.read()
        assert status == expected, (endpoint, status, body.decode("utf-8", errors="replace"))
        return json.loads(body)

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        for stream in [self.process.stdout, self.process.stderr]:
            if stream:
                stream.close()


def main():
    parser = ArgumentParser()
    parser.add_argument("--cli", type=Path, required=True)
    args = parser.parse_args()
    cli = args.cli.resolve()
    sample = Path(__file__).resolve().parents[1]/"examples"/"mini.ris"
    with tempfile.TemporaryDirectory(prefix="ycscite-workspace-smoke-") as temporary:
        root = Path(temporary).resolve()
        assert root.parent == Path(tempfile.gettempdir()).resolve() and root.name.startswith("ycscite-workspace-smoke-")
        store = root/"state"
        source = root/"含空格的资料.ris"
        shutil.copyfile(sample, source)
        output = root/"saved result"
        with ExitStack() as stack:
            first = LocalServer(cli, store, root)
            stack.callback(first.close)
            assert not first.request("workspace")["has_saved_state"]
            settings = first.request("validate-settings", {"settings":{
                "language":"en", "g_index_k":19, "image_scale":1, "charts":["timeline"]}, "remember":True})
            assert settings["ok"]
            first.request("workspace", {"inputs":[str(source)], "output":str(output), "view":"timeline",
                                         "download_logo":False, "data_mode":True})
            job = first.request("run", {"inputs":[str(source)], "output":str(output), "settings":settings["result"]})["id"]
            deadline = time.monotonic()+60
            while time.monotonic() < deadline:
                state = first.request("state")
                current = next(j for j in state["jobs"] if j["id"] == job)
                if current["status"] != "running":
                    assert current["status"] == "complete", current
                    break
                time.sleep(.08)
            else:
                raise AssertionError("Analysis did not complete")
            actual_output = Path(current["output"])
            assert actual_output.parent == output and actual_output != output
            assert (actual_output/"manifest.json").is_file()
            first.request("workspace", {"report_id":job})
            original_manifest = (actual_output/"manifest.json").read_bytes()
            original_settings = (actual_output/"settings.json").read_bytes()
            alternate = dict(settings["result"], charts=["keywords"], g_index_k=3)
            next_id = first.request("run", {"inputs":[str(source)], "output":str(output), "settings":alternate})["id"]
            deadline = time.monotonic()+60
            while time.monotonic() < deadline:
                state = first.request("state")
                next_job = next(j for j in state["jobs"] if j["id"] == next_id)
                if next_job["status"] != "running":
                    assert next_job["status"] == "complete", next_job
                    break
                time.sleep(.08)
            else:
                raise AssertionError("Second analysis did not complete")
            assert Path(next_job["output"]) != actual_output and Path(next_job["output"]).parent == output
            assert (actual_output/"manifest.json").read_bytes() == original_manifest
            assert (actual_output/"settings.json").read_bytes() == original_settings
            before = (store/"workspace.json").read_bytes()
            for invalid in [{"settings":{"g_index_k":-1}}, {"view":"../invalid"}, {"language":"fr"},
                            {"inputs":[4]}, {"unknown":True}, {"data_mode":"true"}]:
                assert first.request("workspace", invalid, expected=400)["ok"] is False
                assert (store/"workspace.json").read_bytes() == before
            # Different, concurrently bound ports prove independence from browser origin.
            second = LocalServer(cli, store, root)
            stack.callback(second.close)
            assert second.url != first.url
            saved = second.request("workspace")
            workspace = saved["workspace"]
            assert saved["has_saved_state"] and not saved["warning"]
            assert workspace["language"] == "en" and workspace["settings"]["g_index_k"] == 19
            assert workspace["settings"]["charts"] == ["timeline"] and workspace["settings"]["language"] == "en"
            assert workspace["inputs"] == [str(source)] and workspace["output"] == str(output)
            assert workspace["view"] == "timeline" and workspace["report_id"] == job
            assert workspace["download_logo"] is False and workspace["data_mode"] is True
            assert len(second.request("state")["jobs"]) == len(state["jobs"]), "Restoring must not launch another analysis"
            assert second.request("workspace", expected=403, token=first.token)["ok"] is False
            second.request("workspace", {"language":"zh"})
            assert second.request("workspace")["workspace"]["settings"]["language"] == "zh"
            first.close()
            second.close()
            # Explicit --report is delivered to the UI rather than hidden by the previous workspace.
            explicit = LocalServer(cli, store, root, report=actual_output)
            stack.callback(explicit.close)
            assert explicit.request("workspace")["initial_report_id"]
            explicit.close()
            bad = b"{broken workspace"
            (store/"workspace.json").write_bytes(bad)
            recovered = LocalServer(cli, store, root)
            stack.callback(recovered.close)
            recovered_state = recovered.request("workspace")
            assert recovered_state["warning"] and not recovered_state["has_saved_state"]
            assert any(p.read_bytes() == bad for p in store.glob("workspace-recovery-*.json"))
            recovered.request("workspace", {"language":"en"})
            assert not recovered.request("workspace")["warning"]
            recovered.close()
    print(json.dumps({"ok":True,"checks":["different-ports", "immutable-run-folders", "validated-settings", "unicode-paths", "active-view",
        "report-pointer", "logo-choice", "no-auto-analysis", "invalid-update-isolation", "session-auth",
        "language-consistency", "explicit-report", "corruption-recovery"]}))


if __name__ == "__main__":
    main()
