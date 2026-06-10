#!/usr/bin/env bash
# A/B 验证百炼 Anthropic 兼容层：thinking 不传 vs disabled × stream true/false（纯图）
#
# 用法：
#   export DASHSCOPE_API_KEY=sk-xxx
#   export BAILIAN_MODEL=qwen3.7-plus   # 与 j2agent 当前 LLM 配置一致
#   ./bailian-anthropic-thinking-ab.sh
#
# 判定：
#   - B 空、A 有 text → disabled + 非流式 + 图片 为百炼侧问题
#   - C 有 text、B 空 → 非流式路径缺陷，可考虑 Multimodal 改流式聚合
#   - A/B 都有 text → 回到 j2agent 对比 base64 / content 顺序等

set -euo pipefail

API_KEY="${DASHSCOPE_API_KEY:-}"
BASE_URL="${BAILIAN_ANTHROPIC_BASE_URL:-https://dashscope.aliyuncs.com/apps/anthropic/v1/messages}"
MODEL="${BAILIAN_MODEL:-qwen3.7-plus}"
IMAGE_URL="${BAILIAN_TEST_IMAGE_URL:-https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250414/mqqmiy/animal_01.jpg}"
PROMPT_TEXT="${BAILIAN_TEST_PROMPT:-描述这张图片的内容。}"

if [[ -z "$API_KEY" ]]; then
  echo "ERROR: set DASHSCOPE_API_KEY" >&2
  exit 1
fi

USER_CONTENT=$(cat <<EOF
[
  {
    "type": "image",
    "source": {
      "type": "url",
      "url": "${IMAGE_URL}"
    }
  },
  {
    "type": "text",
    "text": "${PROMPT_TEXT}"
  }
]
EOF
)

run_case() {
  local label="$1"
  local stream="$2"
  local thinking_json="$3"

  echo "========== Case ${label}: stream=${stream}, thinking=${thinking_json:-omit} =========="

  local body
  if [[ -n "$thinking_json" ]]; then
    body=$(cat <<EOF
{
  "model": "${MODEL}",
  "max_tokens": 1024,
  "stream": ${stream},
  "thinking": ${thinking_json},
  "messages": [
    {
      "role": "user",
      "content": ${USER_CONTENT}
    }
  ]
}
EOF
)
  else
    body=$(cat <<EOF
{
  "model": "${MODEL}",
  "max_tokens": 1024,
  "stream": ${stream},
  "messages": [
    {
      "role": "user",
      "content": ${USER_CONTENT}
    }
  ]
}
EOF
)
  fi

  if [[ "$stream" == "true" ]]; then
    curl -sS -N -X POST "${BASE_URL}" \
      -H "Content-Type: application/json" \
      -H "x-api-key: ${API_KEY}" \
      -d "${body}" | head -c 4000
    echo ""
  else
    curl -sS -X POST "${BASE_URL}" \
      -H "Content-Type: application/json" \
      -H "x-api-key: ${API_KEY}" \
      -d "${body}" | python3 -m json.tool 2>/dev/null || cat
    echo ""
  fi
}

run_case "A" "false" ""
run_case "B" "false" '{"type":"disabled"}'
run_case "C" "true" '{"type":"disabled"}'
run_case "D" "true" ""

echo "Done. Compare content[].text (non-stream) or content_block_delta text (stream)."
