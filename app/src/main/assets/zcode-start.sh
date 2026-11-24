#!/bin/bash
# 启动 ZCode 本地 Web 服务（在 DSHA 的 Ubuntu guest 内执行）。
#
# 幂等：端口已在监听就直接返回；否则以独立会话拉起 `zcode --web`，并等到端口真正可用
# 再返回，避免 WebView 抢在服务就绪前打开而拿到空白页。
set -u

export PATH="/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin${PATH:+:$PATH}"

Z="${ZCODE_HOME:-/root/.zcode-cli}"
PORT="${ZCODE_PORT:-3030}"
say() { echo "[zcode] $*"; }

ready() { (exec 3<>"/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1; }

if ready; then say "SERVER_ALREADY_RUNNING $PORT"; exit 0; fi

cd "$Z/package" || { say "NO_PACKAGE"; exit 21; }
[ -f bin/zcode.mjs ] || { say "NO_ENTRY"; exit 21; }

: > "$Z/web.log"
if command -v setsid >/dev/null 2>&1; then
  setsid nohup node bin/zcode.mjs --web --port "$PORT" --no-open \
    >> "$Z/web.log" 2>&1 < /dev/null &
else
  nohup node bin/zcode.mjs --web --port "$PORT" --no-open \
    >> "$Z/web.log" 2>&1 < /dev/null &
fi

for _ in $(seq 1 90); do
  sleep 1
  if ready; then say "SERVER_READY $PORT"; exit 0; fi
done

say "SERVER_TIMEOUT"
tail -20 "$Z/web.log" 2>/dev/null
exit 22
