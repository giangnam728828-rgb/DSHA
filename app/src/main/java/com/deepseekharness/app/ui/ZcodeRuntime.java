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
 * ZCode 运行时的安装、启动与就绪检查。
 *
 * <p>用社区的独立发行版 {@code @ljwei-stak/zcode-cli}：它把官方 Electron 包里的 Agent 与
 * Web 服务抽成纯 Node 发行版，{@code zcode --web} 直接监听本机端口，不需要 Electron，
 * 也不需要虚拟显示。DSHA 的 WebPreviewActivity 指向该端口，就是与 DeepSeek 同形态的界面。
 *
 * <p>安装与启动脚本来自 APK assets，在 guest 内执行；失败时原样返回输出，不吞错。
 */
final class ZcodeRuntime {

  static final String HOME = "/root/.zcode-cli";
  static final int PORT = 3030;
  private static final String READY = HOME + "/.ready";
  private static final String SERVER = HOME + "/package/runtime/server/entry-http.js";

  private ZcodeRuntime() {}

  /** 装好并把服务拉起来；成功返回 null，失败返回要展示给用户的原始输出。 */
  static String prepare(Context ctx, Consumer<String> stage) {
    try {
      if (!(exists(ctx, READY) && exists(ctx, SERVER))) {
        stage.accept(UiText.choose(
            "首次使用，正在安装 ZCode 运行时（约 85MB）…",
            "First run: installing ZCode runtime (~85MB)…"));
        String out = exec(ctx, script(ctx, "zcode-install.sh"), TimeUnit.MINUTES.toMillis(30));
        if (!out.contains("RUNTIME_READY") && !out.contains("INSTALL_OK")) {
          return out.isEmpty() ? UiText.choose("安装脚本没有输出", "Installer produced no output") : out;
        }
      }
      stage.accept(UiText.choose("正在启动 ZCode…", "Starting ZCode…"));
      String started = exec(ctx, script(ctx, "zcode-start.sh"), TimeUnit.MINUTES.toMillis(3));
      if (started.contains("SERVER_READY") || started.contains("SERVER_ALREADY_RUNNING")) return null;
      return started.isEmpty() ? UiText.choose("启动脚本没有输出", "Launcher produced no output") : started;
    } catch (Throwable error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      return String.valueOf(error);
    }
  }

  /** 把资产脚本写到 guest 再执行；每次覆盖，保证升级后跑的是新脚本。 */
  private static String script(Context ctx, String name) throws IOException {
    String body = readAsset(ctx, name);
    String encoded = Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    String path = HOME + "/" + name;
    return "mkdir -p " + HOME + " && printf '%s' '" + encoded + "' | base64 -d > " + path
        + " && chmod +x " + path + " && bash " + path;
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
