# Rust client

Rust CLI, local Web service and Wry Windows shell for Ycscite. The Java21 engine remains the analysis/rendering implementation. See the root README, docs/HEADLESS.md and docs/DEVELOPMENT.md for the supported product contract.

```powershell
cargo test --manifest-path native/Cargo.toml --locked
cargo build --release --manifest-path native/Cargo.toml --locked
native\target\release\ycscite.exe --json doctor
native\target\release\ycscite.exe serve
native\target\release\ycscite-desktop.exe
```

The Web UI and user-provided icon are embedded in both EXEs. A release folder includes a fresh engine JAR and a compact Java21 runtime. API calls are loopback-only, session-authorized, Origin/Host checked, and report file paths are constrained to registered output directories. Use first-class headless commands for automation.
