package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.deepseekharness.app.util.UiText;

/**
 * ZCode 入口页：确保运行时就绪、拉起本地 Web、再用既有的 WebPreviewActivity 以桌面宽度打开。
 *
 * <p>刻意不碰 DSH 的启动目标与页面路由：ZCode 是并行的第二个工作台，互不影响。所有重活都在
 * 后台线程，主线程只更新这一个页面的文案，失败时把真实输出原样展示，不吞错误。
 */
public final class ZcodeActivity extends Activity {

  /** 与 ZcodeRuntime 共用的端口；WebPreviewActivity 直接指向它。 */
  static final int PORT = 3030;

  private final Handler main = new Handler(Looper.getMainLooper());
  private TextView status;
  private TextView detail;
  private ProgressBar bar;
  private Button retry;
  private volatile boolean working;

  public static Intent intent(Context ctx) {
    return new Intent(ctx, ZcodeActivity.class);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(buildUi());
    begin();
  }

  @Override
  protected void onDestroy() {
    working = false;
    super.onDestroy();
  }

  private View buildUi() {
    int pad = dp(24);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER);
    root.setPadding(pad, pad, pad, pad);
    root.setBackgroundColor(Color.parseColor("#101419"));

    TextView title = new TextView(this);
    title.setText("ZCode");
    title.setTextColor(Color.parseColor("#E9ECF1"));
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setGravity(Gravity.CENTER);
    root.addView(title, matchWrap());

    TextView sub = new TextView(this);
    sub.setText(UiText.choose("桌面版工作台 · 独立于 DeepSeek 运行", "Desktop workbench, runs independently of DeepSeek"));
    sub.setTextColor(Color.parseColor("#8A93A0"));
    sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    sub.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams subLp = matchWrap();
    subLp.topMargin = dp(6);
    root.addView(sub, subLp);

    bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setIndeterminate(true);
    LinearLayout.LayoutParams barLp = matchWrap();
    barLp.topMargin = dp(28);
    root.addView(bar, barLp);

    status = new TextView(this);
    status.setTextColor(Color.parseColor("#C9D1DB"));
    status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    status.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams stLp = matchWrap();
    stLp.topMargin = dp(16);
    root.addView(status, stLp);

    ScrollView scroll = new ScrollView(this);
    LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
    scrollLp.topMargin = dp(14);
    detail = new TextView(this);
    detail.setTextColor(Color.parseColor("#6F7885"));
    detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
    detail.setTypeface(Typeface.MONOSPACE);
    detail.setVisibility(View.GONE);
    scroll.addView(detail);
    root.addView(scroll, scrollLp);

    retry = new Button(this);
    retry.setText(UiText.choose("重试", "Retry"));
    retry.setVisibility(View.GONE);
    retry.setOnClickListener(v -> begin());
    LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rLp.topMargin = dp(12);
    root.addView(retry, rLp);

    return root;
  }

  private void begin() {
    if (working) return;
    working = true;
    runOnUiThread(() -> {
      bar.setVisibility(View.VISIBLE);
      retry.setVisibility(View.GONE);
      detail.setVisibility(View.GONE);
      status.setText(UiText.choose("正在检查 ZCode 环境…", "Checking ZCode environment…"));
    });
    new Thread(() -> {
      String log = ZcodeRuntime.prepare(this, stage -> status(stage));
      if (!isFinishing() && !isDestroyed()) {
        if (log == null) {
          status(UiText.choose("正在打开 ZCode…", "Opening ZCode…"));
          if (needsEnvironment()) {
            fail(UiText.choose(
                "DSH 的运行环境还没就绪。请回到首页先启动一次 DeepSeek，再打开 ZCode。",
                "The DSH environment is not ready yet. Start DeepSeek once on the home screen, then open ZCode."));
            working = false;
            return;
          }
          startActivity(WebPreviewActivity.intent(
              this, "http://127.0.0.1:" + ZcodeRuntime.PORT + "/", null));
          finish();
        } else {
          fail(log);
        }
      }
      working = false;
    }, "zcode-bringup").start();
  }

  /** 与首页一致的恢复判定：环境未就绪或存在待办维护时，终端无法可靠启动。 */
  private boolean needsEnvironment() {
    try {
      return com.deepseekharness.app.core.EnvironmentAccess.needsRecovery(
          com.deepseekharness.app.core.HarnessController.get(this));
    } catch (Throwable ignored) {
      // 取不到状态时不阻断用户：终端自己会报出真实原因。
      return false;
    }
  }

  private void status(String text) {
    main.post(() -> {
      if (status != null) status.setText(text);
    });
  }

  private void fail(String output) {
    main.post(() -> {
      bar.setVisibility(View.GONE);
      status.setText(UiText.choose("ZCode 没有启动成功", "ZCode failed to start"));
      detail.setText(output);
      detail.setVisibility(View.VISIBLE);
      retry.setVisibility(View.VISIBLE);
    });
  }

  private LinearLayout.LayoutParams matchWrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
