#!/bin/bash
# 使用选定 Xcode 的 SwiftPM 验证公共业务与平台合同。
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$task_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
xcrun swift test
