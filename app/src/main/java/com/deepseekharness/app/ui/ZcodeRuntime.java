package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.Base64;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.UiText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * ZCode 运行时的安装与就绪检查。
 *
 * <p>ZCode 桌面版的 Web 服务嵌在 Electron 的 host 进程里，依赖 IPC 启动，无法在纯 Node 下独立运行；
 * 官方也没有公开 arm64 的 CLI 发行包。因此这里只安装运行时（{@code /root/.zcode/glm}），
 * 由 DSHA 既有终端以 PTY 方式运行它的 TUI —— 功能完整、无 Electron、无虚拟显示。
 *
 * <p>安装脚本来自 APK assets，在 guest 内执行；失败时原样返回输出，不吞错。
 */
final class ZcodeRuntime {

  static final String HOME = "/root/.zcode";
  private static final String READY = HOME + "/.ready";
  private static final String CLI = HOME + "/glm/zcode.cjs";

  private ZcodeRuntime() {}

  /** 成功返回 null；失败返回要展示给用户的原始输出。 */
  static String ensureReady(Context ctx, Consumer<String> stage) {
    try {
      if (exists(ctx, READY) && exists(ctx, CLI)) return null;
      stage.accept(UiText.choose(
          "首次使用，正在安装 ZCode 运行时（约 90MB）…",
          "First run: installing ZCode runtime (~90MB)…"));
      String out = exec(ctx, installCommand(ctx), TimeUnit.MINUTES.toMillis(40));
      if (out.contains("INSTALL_OK") || out.contains("RUNTIME_READY")) {
        if (exists(ctx, CLI)) return null;
        return UiText.choose("安装完成但找不到 glm/zcode.cjs", "Installed but glm/zcode.cjs is missing");
      }
      return out.isEmpty() ? UiText.choose("安装脚本没有输出", "Installer produced no output") : out;
    } catch (Throwable error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      return String.valueOf(error);
    }
  }

  private static String installCommand(Context ctx) throws IOException {
    String script = readAsset(ctx, "zcode-install.sh");
    String encoded = Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    return "mkdir -p " + HOME + " && printf '%s' '" + encoded + "' | base64 -d > " + HOME
        + "/install.sh && chmod +x " + HOME + "/install.sh && bash " + HOME + "/install.sh";
  }

  private static boolean exists(Context ctx, String path) {
    try {
      return exec(ctx, "test -f " + path + " && echo YES || echo NO", 60_000).contains("YES");
    } catch (Exception unknown) {
      return false;
    }
  }

  /** 在 guest 里执行并回收全部输出；超时强杀，绝不无限等待。 */
  private static String exec(Context ctx, String command, long timeoutMs) throws IOException {
    ProotBootstrap boot = new ProotBootstrap(ctx, false);
    Process process = boot.execRootfs(command, Map.of());
    StringBuilder output = new StringBuilder();
    Thread reader = new Thread(() -> {
      try (InputStream in = process.getInputStream();
           BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = lines.readLine()) != null) {
          synchronized (output) {
            output.append(line).append('\n');
            if (output.length() > 40_000) output.delete(0, 20_000);
          }
        }
      } catch (IOException ignored) {
        // 进程退出时流关闭属正常。
      }
    }, "zcode-reader");
    reader.setDaemon(true);
    reader.start();
    try {
      if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly();
      reader.join(2_000);
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
    synchronized (output) {
      return output.toString();
    }
  }

  private static String readAsset(Context ctx, String name) throws IOException {
    try (InputStream in = ctx.getAssets().open(name)) {
      byte[] buffer = new byte[8192];
      StringBuilder text = new StringBuilder();
      int read;
      while ((read = in.read(buffer)) > 0) {
        text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
      }
      return text.toString();
    }
  }
}
