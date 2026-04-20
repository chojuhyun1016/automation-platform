#!/bin/bash
# 코드 파일 변경 수가 5개 이상이면 문서 업데이트 안내
COUNT=$(git diff --name-only HEAD 2>/dev/null | grep -cE '\.(java|kt|py|ts|js)$' || true)
if [ "$COUNT" -ge 5 ]; then
  echo "코드 파일 ${COUNT}개 변경됨 — 관련 문서 업데이트를 권장합니다"
fi
