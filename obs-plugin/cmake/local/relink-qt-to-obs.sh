#!/bin/sh
# Homebrew Qt에 링크된 플러그인을 OBS.app 안의 Qt(@rpath)로 돌려놓는다 (서명은 resources 타깃이 한다).
# 그대로 두면 OBS 안에서 Qt가 두 벌 로드돼 크래시한다.
set -eu
binary="$1"
otool -L "$binary" | awk 'NR>1 {print $1}' | grep -E '^/opt/homebrew/.*/Qt[A-Za-z]+\.framework/' | while read -r lib; do
  framework_path=$(printf '%s' "$lib" | sed -E 's#^.*/(Qt[A-Za-z]+\.framework/.*)$#\1#')
  install_name_tool -change "$lib" "@rpath/$framework_path" "$binary"
done
if otool -L "$binary" | grep -q '/opt/homebrew/'; then
  echo "relink-qt-to-obs: Homebrew 경로가 남았다" >&2
  otool -L "$binary" >&2
  exit 1
fi
