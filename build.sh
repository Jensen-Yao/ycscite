#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
sources_file="$(mktemp)"
trap 'rm -f "$sources_file"' EXIT
find src test -name '*.java' -type f | sort > "$sources_file"
javac -encoding UTF-8 --release 21 -d out "@$sources_file"
cp resources/ycscite-logo.png out/ycscite-logo.png
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp out ycscite.TestAll
