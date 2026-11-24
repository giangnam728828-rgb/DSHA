#!/bin/bash
# ZCode 运行时安装器（在 DSHA 的 Ubuntu guest 内执行）。
#
# 装的是社区的独立发行版 @ljwei-stak/zcode-cli：把官方 Electron 包里的 Agent 与 Web
# 服务抽出来，不需要 Electron、不需要虚拟显示，`zcode --web` 直接起 HTTP 服务。
# 任一环节失败都返回非零并打印可读原因，不静默降级。
set -u

export PATH="/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"

Z="${ZCODE_HOME:-/root/.zcode-cli}"
V="${ZCODE_VERSION:-3.17.1}"
PRIMARY="https://registry.npmjs.org/@ljwei-stak/zcode-cli/-/zcode-cli-$V.tgz"
MIRROR="https://registry.npmmirror.com/@ljwei-stak/zcode-cli/-/zcode-cli-$V.tgz"
say() { echo "[zcode] $*"; }

mkdir -p "$Z" || { say "MKDIR_FAILED"; exit 10; }
cd "$Z" || { say "CD_FAILED"; exit 10; }

NODE=$(command -v node 2>/dev/null || true)
if [ -z "$NODE" ]; then say "NO_NODE"; exit 12; fi
NV=$("$NODE" -p "process.versions.node" 2>/dev/null || echo "0.0.0")
MAJOR=${NV%%.*}
REST=${NV#*.}
MINOR=${REST%%.*}
if [ "$MAJOR" -lt 24 ] || { [ "$MAJOR" -eq 24 ] && [ "$MINOR" -lt 14 ]; }; then
  say "NODE_TOO_OLD $NV"
  exit 13
fi

if [ -f "$Z/.ready" ] && [ -f "$Z/package/runtime/server/entry-http.js" ]; then
  say "RUNTIME_READY $NV"
  exit 0
fi

say "下载 ZCode CLI $V（约 85MB）"
rm -f "$Z/pkg.tgz"
if ! curl -fL --retry 3 --connect-timeout 20 -o "$Z/pkg.tgz" "$PRIMARY"; then
  say "主源不可用，改用镜像"
  if ! curl -fL --retry 3 --connect-timeout 20 -o "$Z/pkg.tgz" "$MIRROR"; then
    say "DOWNLOAD_FAILED"
    exit 14
  fi
fi

say "解包"
rm -rf "$Z/package"
if ! tar -xzf "$Z/pkg.tgz" -C "$Z"; then say "UNTAR_FAILED"; exit 15; fi
[ -f "$Z/package/scripts/postinstall.mjs" ] || { say "NO_POSTINSTALL"; exit 15; }

say "展开运行时（约 500MB，需要几分钟）"
cd "$Z/package" || { say "CD_FAILED"; exit 15; }
if ! "$NODE" scripts/postinstall.mjs > "$Z/install.log" 2>&1; then
  say "POSTINSTALL_FAILED"
  tail -15 "$Z/install.log"
  exit 16
fi

if [ ! -f "$Z/package/runtime/server/entry-http.js" ]; then
  say "NO_SERVER_ENTRY"
  tail -15 "$Z/install.log"
  exit 17
fi

# 装完就删掉两个中间包：85MB 的 npm 包与 84MB 的运行时归档，运行时已展开。
rm -f "$Z/pkg.tgz" "$Z/package/payload/zcode-runtime.tar.gz"

touch "$Z/.ready"
say "INSTALL_OK $V (node $NV)"
exit 0
