#!/usr/bin/env bash

# ==============================================================================
# 脚本名称: load_offline_images.sh
# 功能描述: 批量 docker load 离线镜像目录（如 images_offline_amd64_dir）内所有 .tar 文件。
#
# 使用说明:
#   ./load_offline_images.sh
#   ./load_offline_images.sh images_offline_amd64_dir
#
# 注意: 请使用 bash 或直接 ./load_offline_images.sh 执行，勿用 sh（Ubuntu 下 sh 为 dash）。
# ==============================================================================

# 若被 sh 调用，自动切换为 bash 执行
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi

set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 解析镜像目录：显式参数 > 脚本所在目录（含 tar）> docker/images_offline_amd64_dir
resolve_images_dir() {
    if [[ -n "${1:-}" ]]; then
        echo "$1"
        return
    fi

    shopt -s nullglob
    local local_tars=("$SCRIPT_DIR"/*.tar)
    shopt -u nullglob

    if [[ ${#local_tars[@]} -gt 0 ]]; then
        echo "$SCRIPT_DIR"
        return
    fi

    echo "${SCRIPT_DIR}/images_offline_amd64_dir"
}

IMAGES_DIR="$(resolve_images_dir "${1:-}")"

if [[ ! -d "$IMAGES_DIR" ]]; then
    echo -e "${RED}❌ 错误：目录不存在: ${IMAGES_DIR}${NC}"
    exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
    echo -e "${RED}❌ 错误：未找到 docker 命令，请先安装并启动 Docker${NC}"
    exit 1
fi

shopt -s nullglob
TAR_FILES=("$IMAGES_DIR"/*.tar)
IFS=$'\n' TAR_FILES=($(printf '%s\n' "${TAR_FILES[@]}" | sort))
unset IFS

if [[ ${#TAR_FILES[@]} -eq 0 ]]; then
    echo -e "${RED}❌ 错误：目录内没有 .tar 文件: ${IMAGES_DIR}${NC}"
    exit 1
fi

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}📥 离线镜像批量导入${NC}"
echo -e "   目录: ${YELLOW}${IMAGES_DIR}${NC}"
echo -e "   共 ${#TAR_FILES[@]} 个 tar 包"
echo -e "${CYAN}========================================${NC}"

FAILED=0
for tar_path in "${TAR_FILES[@]}"; do
    name="$(basename "$tar_path")"
    echo -e "\n${CYAN}⏳ 正在导入:${NC} ${GREEN}${name}${NC}"
    if docker load -i "$tar_path"; then
        echo -e "${GREEN}   ✅ 完成: ${name}${NC}"
    else
        echo -e "${RED}   ❌ 失败: ${name}${NC}"
        FAILED=$((FAILED + 1))
    fi
done

echo -e "\n${CYAN}========================================${NC}"
if [[ $FAILED -eq 0 ]]; then
    echo -e "${GREEN}✅ 全部 ${#TAR_FILES[@]} 个镜像导入成功${NC}"
    echo -e "下一步可执行: ${GREEN}cd ${SCRIPT_DIR} && docker compose up -d${NC}"
else
    echo -e "${RED}❌ 完成，但有 ${FAILED} 个文件导入失败${NC}"
    exit 1
fi
echo -e "${CYAN}========================================${NC}"
