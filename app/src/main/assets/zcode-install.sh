#!/bin/bash
# ZCode 运行时安装器（在 DSHA 的 Ubuntu guest 内执行）。
#
# 只做四件事：下载官方 arm64 桌面包、解包、提取 glm 运行时、写就绪标记。
# 不安装 Electron、不保留 340MB 的 app.asar —— ZCode 通过 DSHA 自带终端以 TUI 方式运行。
# 任一环节失败都返回非零并打印可读原因，不静默降级。
set -u

Z="${ZCODE_HOME:-/root/.zcode}"
VERSIONS="${ZCODE_VERSIONS:-4.0.0 3.15.1}"
BASE="https://cdn-zcode.z.ai/zcode/electron/releases"
say() { echo "[zcode] $*"; }

mkdir -p "$Z" || { say "MKDIR_FAILED"; exit 10; }
cd "$Z" || { say "CD_FAILED"; exit 10; }

if [ -f "$Z/.ready" ] && [ -f "$Z/glm/zcode.cjs" ]; then
  say "RUNTIME_READY"
  exit 0
fi

fetch() {
  # $1=url $2=目标文件；优先 curl，缺失时退回 wget。
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 -o "$2" "$1" && return 0
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -O "$2" "$1" && return 0
  fi
  return 1
}

extract() {
  # $1=AppImage；优先内置自解包，其次 unsquashfs。
  rm -rf "$Z/squashfs-root"
  chmod +x "$1" 2>/dev/null || true
  if "$1" --appimage-extract >/dev/null 2>&1; then return 0; fi
  if ! command -v unsquashfs >/dev/null 2>&1; then
    apt-get update >/dev/null 2>&1
    apt-get install -y squashfs-tools >/dev/null 2>&1
  fi
  command -v unsquashfs >/dev/null 2>&1 || { say "NO_EXTRACTOR"; return 1; }
  OFFSET=$(grep -abo 'hsqs' "$1" | head -1 | cut -d: -f1)
  [ -n "$OFFSET" ] || { say "NO_SQUASHFS_OFFSET"; return 1; }
  unsquashfs -o "$OFFSET" -d "$Z/squashfs-root" "$1" >/dev/null 2>&1
}

INSTALLED=0
for V in $VERSIONS; do
  say "尝试 ZCode $V（arm64）"
  rm -f "$Z/payload.AppImage"
  if ! fetch "$BASE/$V/linux-arm64/ZCode-$V-linux-arm64.AppImage" "$Z/payload.AppImage"; then
    say "DOWNLOAD_FAILED $V"
    continue
  fi
  say "解包 $V"
  if ! extract "$Z/payload.AppImage"; then
    say "EXTRACT_FAILED $V"
    continue
  fi
  R="$Z/squashfs-root/resources"
  if [ ! -d "$R/glm" ] || [ ! -f "$R/glm/zcode.cjs" ]; then
    say "NO_GLM_RUNTIME $V"
    continue
  fi

  say "安装 $V 的运行时"
  rm -rf "$Z/glm" "$Z/config" "$Z/tools"
  mv "$R/glm" "$Z/glm" || { say "MOVE_GLM_FAILED"; continue; }
  mv "$R/config" "$Z/config" 2>/dev/null || true
  [ -d "$R/tools" ] && mv "$R/tools" "$Z/tools"

  # CLI 会在 glm/provider 与 /config/provider 两处找内置 provider 清单。
  mkdir -p "$Z/glm/provider"
  cp "$Z/config/provider/"*.json "$Z/glm/provider/" 2>/dev/null || true

  # Electron 包体（app.asar 与解包树）一律丢弃：TUI 用不到，白占 600MB 以上。
  rm -rf "$Z/squashfs-root" "$Z/payload.AppImage"
  echo "$V" > "$Z/.version"
  INSTALLED=1
  break
done

if [ "$INSTALLED" != "1" ]; then
  say "ALL_VERSIONS_FAILED"
  exit 11
fi

[ -f "$Z/glm/zcode.cjs" ] || { say "MISSING_ENTRY"; exit 14; }
touch "$Z/.ready"
say "INSTALL_OK $(cat "$Z/.version" 2>/dev/null)"
exit 0
