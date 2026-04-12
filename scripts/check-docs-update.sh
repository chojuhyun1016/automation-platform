#!/bin/bash
# 코드 파일 변경 수가 5개 이상이면 /update-docs 실행 안내
COUNT=$(git diff --name-only HEAD 2>/dev/null | grep -cE '\.(java|py)$' || true)
if [ "$COUNT" -ge 5 ]; then
  echo "⚠️ 코드 파일 ${COUNT}개 변경됨 — /update-docs 실행을 권장합니다"
fi
