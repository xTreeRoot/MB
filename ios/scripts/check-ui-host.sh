#!/bin/bash
# 只检查 SwiftUI 公共模块；不能替代 iOS 构建和 UI 回归。
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$task_root"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
xcrun swift build --target MBInterface
