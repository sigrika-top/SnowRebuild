package snow.rebuild;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.nio.ByteBuffer;

public final class CaptureService extends Service {
    static final String CONSENT = "consent";
    private static final String STOP = "snow.rebuild.STOP";
    private static final String CHANNEL = "capture";
    static volatile boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;
    private Handler capture;
    private WindowManager windows;
    private WindowManager.LayoutParams window;
    private LinearLayout overlay;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader images;
    private Recognizer recognizer;
    private volatile long captureAfter;
    private volatile int request;
    private volatile int obscuredFrames;
    private boolean busy;
    private boolean closed;
    private int width, height;

    @Override public void onCreate() {
        super.onCreate();
        windows = getSystemService(WindowManager.class);
        captureThread = new HandlerThread("rebuild-capture");
        captureThread.start();
        capture = new Handler(captureThread.getLooper());
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY;
        Intent token = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(CONSENT, Intent.class) : intent.getParcelableExtra(CONSENT);
        startForeground(1, notification());
        try {
            recognizer = new Recognizer(getAssets().open("shapes.txt"), OpenCvBoardVision.load());
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(android.app.Activity.RESULT_OK, token);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
                @Override public void onCapturedContentResize(int w, int h) { resize(w,h); }
            }, main);
            DisplayMetrics metrics = new DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            resize(metrics.widthPixels, metrics.heightPixels);
            display = projection.createVirtualDisplay("SnowRebuild", width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, images.getSurface(), null, capture);
            addOverlay();
            running = true;
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, "悬浮窗启动失败，请重新授权。", Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "重建助手", NotificationManager.IMPORTANCE_LOW));
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class)
                .setAction(STOP), pendingFlags);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), pendingFlags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("雪境重建助手运行中")
                .setContentText("点游戏上的「分析」读取当前回合")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"停止",stop).build())
                .build();
    }

    private void resize(int originalWidth, int originalHeight) {
        if (closed || originalWidth <= 0 || originalHeight <= 0) return;
        float scale = Math.min(1f, 2700f/Math.max(originalWidth,originalHeight));
        int w = Math.round(originalWidth*scale), h = Math.round(originalHeight*scale);
        if (w == width && h == height) return;
        ImageReader old = images;
        ImageReader next = ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
        next.setOnImageAvailableListener(this::onImage,capture);
        width = w;
        height = h;
        images = next;
        if (display != null) {
            display.resize(w,h,getResources().getDisplayMetrics().densityDpi);
            display.setSurface(next.getSurface());
        }
        if (old != null) capture.post(old::close);
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (projection != null && Build.VERSION.SDK_INT < 34) {
            DisplayMetrics metrics = new DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            resize(metrics.widthPixels, metrics.heightPixels);
        }
    }

    private void addOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        window = new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        window.gravity = Gravity.TOP | Gravity.LEFT;
        window.x = dp(16);
        window.y = dp(70);
        showBubble();
        windows.addView(overlay,window);
    }

    private void showBubble() {
        overlay.removeAllViews();
        overlay.setPadding(0,0,0,0);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        TextView button = label("分析", 16, 0xff10242d);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(background(0xffa4e8ef,24));
        overlay.addView(button,new LinearLayout.LayoutParams(dp(66),dp(45)));
        button.setOnClickListener(v -> analyze());
        button.setOnLongClickListener(v -> { showPanel(null, "在回合开始时点「分析」。", null); return true; });
        drag(button);
        fitOverlay(dp(66), dp(45));
    }

    private void analyze() {
        if (busy || closed) return;
        busy = true;
        obscuredFrames = 0;
        int id = ++request;
        if (overlay.isAttachedToWindow()) windows.removeViewImmediate(overlay);
        captureAfter = System.nanoTime() + 120_000_000L;
        main.postDelayed(() -> {
            if (id == request && busy && captureAfter != 0 && display != null) {
                display.setSurface(null);
                display.setSurface(images.getSurface());
            }
        }, 250);
        main.postDelayed(() -> {
            if (id == request && busy) {
                request++;
                finish(null,obscuredFrames > 0 ? "画面仍有遮挡，请关闭其他弹窗后重试。"
                        : "未读到屏幕画面，请返回横屏游戏后重试。",null);
            }
        }, 4500);
    }

    private void onImage(ImageReader source) {
        int id = -1;
        try (Image image = source.acquireLatestImage()) {
            if (image == null || captureAfter == 0) return;
            long since = captureAfter;
            if (image.getTimestamp() < since) return;
            captureAfter = 0;
            id = request;
            Solver.Board board = recognizer.read(pixels(image),image.getWidth(),image.getHeight());
            Solver.Result result = Solver.solve(board);
            int completed = id;
            main.post(() -> { if (request == completed) finish(board,null,result); });
        } catch (Recognizer.ObscuredFrame e) {
            int retry = id;
            main.postDelayed(() -> {
                if (request != retry || !busy || display == null) return;
                if (++obscuredFrames >= 3) {
                    finish(null,e.getMessage(),null);
                } else {
                    captureAfter = System.nanoTime();
                    display.setSurface(null);
                    display.setSurface(images.getSurface());
                }
            }, 200);
        } catch (RuntimeException e) {
            if (id == -1) return;
            int failed = id;
            String message = e instanceof IllegalArgumentException ? e.getMessage() : "分析失败，请重试。";
            main.post(() -> { if (request == failed && busy) finish(null,message,null); });
        }
    }

    private int[] pixels(Image image) {
        int w = image.getWidth(), h = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int[] rgb = new int[w*h];
        byte[] row = new byte[w*plane.getPixelStride()];
        for (int y=0; y<h; y++) {
            buffer.position(y*plane.getRowStride());
            buffer.get(row);
            for (int x=0; x<w; x++) {
                int i = x*plane.getPixelStride();
                rgb[y*w+x] = (row[i]&255)<<16 | (row[i+1]&255)<<8 | row[i+2]&255;
            }
        }
        return rgb;
    }

    private void finish(Solver.Board board, String error, Solver.Result result) {
        busy = false;
        captureAfter = 0;
        if (!closed) {
            showPanel(board,error,result);
            if (!overlay.isAttachedToWindow()) windows.addView(overlay,window);
        }
    }

    private void showPanel(Solver.Board board, String message, Solver.Result result) {
        overlay.removeAllViews();
        overlay.setVisibility(View.VISIBLE);
        overlay.setPadding(dp(12),dp(8),dp(12),dp(8));
        overlay.setBackground(background(0xf0192934,14));
        LinearLayout header = new LinearLayout(this);
        TextView title = label("本回合走法",16,Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(34),1));
        drag(title);
        action(header,"收起",this::showBubble);
        overlay.addView(header);
        if (result != null) {
            StringBuilder detail = new StringBuilder();
            if (board.cards().length == 0) detail.append("手牌已用完。点游戏里的「结束回合」。");
            else if (result.steps().isEmpty()) detail.append(Solver.explainNoProgress(board));
            int n = 1;
            for (Solver.Step step : result.steps()) {
                if (n > 1) detail.append('\n');
                detail.append(n++).append(".  ").append(step.card()).append(" 点　");
                switch (step.mode()) {
                    case MOVE:
                        detail.append("前进 · ").append(Solver.MATERIAL[board.tiles()[step.position()].material()]);
                        break;
                    case COLLECT: detail.append("收集"); break;
                    case COMBO: detail.append("收集（COMBO+）"); break;
                }
            }
            if (result.points() > 0) detail.append("\n现有计划预计 +").append(result.points()).append(" 分");
            if (result.chests() > 0) detail.append(" · 经过 ").append(result.chests()).append(" 个宝箱");
            message = detail.toString();
        }
        ScrollView scroll = new ScrollView(this);
        TextView text = label(message,15,0xffe5f2f6);
        text.setLineSpacing(dp(3),1);
        text.setPadding(0,dp(5),0,dp(8));
        scroll.addView(text);
        int shown = result == null ? dp(82)
                : result.steps().isEmpty() && board.cards().length > 0 ? dp(115)
                : dp(Math.max(66,Math.min(184,26*(result.steps().size()+1))));
        overlay.addView(scroll,new LinearLayout.LayoutParams(-1,shown));
        LinearLayout actions = new LinearLayout(this);
        action(actions,"重新分析",this::analyze);
        action(actions,"关闭",this::stopSelf);
        overlay.addView(actions);
        fitOverlay(dp(278),dp(88)+shown);
    }

    private void action(LinearLayout parent, String caption, Runnable run) {
        TextView button = label(caption,14,0xffa4e8ef);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6),0,dp(6),0);
        button.setOnClickListener(v -> run.run());
        parent.addView(button,new LinearLayout.LayoutParams(-2,dp(36)));
    }

    private void fitOverlay(int w, int h) {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        window.x = Math.min(window.x,Math.max(0,metrics.widthPixels-w));
        window.y = Math.min(window.y,Math.max(0,metrics.heightPixels-h-dp(28)));
        if (overlay.isAttachedToWindow()) windows.updateViewLayout(overlay,window);
    }

    private void drag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int beforeX, beforeY;
            boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
                    startX=e.getRawX(); startY=e.getRawY();
                    beforeX=window.x; beforeY=window.y; dragging=false;
                } else if (e.getActionMasked()==MotionEvent.ACTION_MOVE) {
                    float dx=e.getRawX()-startX, dy=e.getRawY()-startY;
                    if (Math.abs(dx)+Math.abs(dy)>dp(7)) dragging=true;
                    if (dragging) {
                        v.cancelLongPress();
                        v.setPressed(false);
                        window.x=Math.max(0,beforeX+(int)dx);
                        window.y=Math.max(0,beforeY+(int)dy);
                        fitOverlay(overlay.getWidth(),overlay.getHeight());
                        return true;
                    }
                } else if (e.getActionMasked()==MotionEvent.ACTION_UP && dragging) {
                    return true;
                }
                return false;
            }
        });
        handle.setClickable(true);
    }

    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private GradientDrawable background(int color,int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(color);
        return drawable;
    }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }

    @Override public void onDestroy() {
        closed = true;
        running = false;
        captureAfter = 0;
        main.removeCallbacksAndMessages(null);
        if (overlay != null && overlay.isAttachedToWindow()) windows.removeView(overlay);
        if (display != null) display.release();
        if (projection != null) projection.stop();
        if (images != null) capture.post(images::close);
        captureThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
