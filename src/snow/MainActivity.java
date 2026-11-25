package snow.rebuild;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int CAPTURE = 10;
    private static final int OVERLAY = 11;
    private static final int BACKGROUND = 0xff14222c;
    private TextView status;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(24), dp(30), dp(24), dp(30));
        page.setBackgroundColor(BACKGROUND);
        TextView title = new TextView(this);
        title.setText("雪境重建助手");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        status = new TextView(this);
        status.setTextColor(0xffc2d3dd);
        status.setTextSize(16);
        status.setLineSpacing(dp(3), 1);
        status.setPadding(0, dp(14), 0, dp(20));
        page.addView(status);
        Button start = new Button(this);
        start.setText("开启悬浮窗");
        start.setTextColor(0xff10242d);
        GradientDrawable button = new GradientDrawable();
        button.setCornerRadius(dp(14));
        button.setColor(0xff9ee6ef);
        start.setBackground(button);
        start.setOnClickListener(v -> startCapture());
        page.addView(start, new LinearLayout.LayoutParams(-1,dp(48)));
        Button stop = new Button(this);
        stop.setText("关闭悬浮窗");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            updateStatus();
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1,dp(48));
        stopParams.topMargin = dp(8);
        page.addView(stop, stopParams);
        setContentView(page);
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) updateStatus();
    }

    private void updateStatus() {
        status.setText("开启后返回游戏，在重建回合开始时点「分析」。\n画面仅在手机上处理。");
    }

    private void startCapture() {
        if (CaptureService.running) {
            moveTaskToBack(true);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:"+getPackageName())), OVERLAY);
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(consent, CAPTURE);
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request == OVERLAY && Settings.canDrawOverlays(this)) {
            startCapture();
        } else if (request == CAPTURE && code == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra(CaptureService.CONSENT, data);
            startForegroundService(service);
            moveTaskToBack(true);
        }
    }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
}
