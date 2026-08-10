package com.quest.stream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class StreamService extends Service {
    public static final String ACTION_START_STREAM = "com.quest.stream.START";
    public static final String ACTION_STOP_STREAM = "com.quest.stream.STOP";

    private static final String CHANNEL_ID = "quest_stream_channel";
    private static final int NOTIFICATION_ID = 42;
    private static final int PORT = 8080;
    private static final String TAG = "QuestStreamService";
    private static final int BADGE_MARGIN_X = 36;
    private static final int BADGE_MARGIN_Y = 22;

    private static volatile int sharedResultCode = 0;
    private static volatile Intent sharedProjectionData;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicLong lastFrameAtMs = new AtomicLong(0L);

    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private byte[] blackFrame;

    public static void setProjectionGrant(int resultCode, Intent data) {
        sharedResultCode = resultCode;
        sharedProjectionData = data;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EZC Quest Stream")
                .setContentText("Preparing stream...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "onStartCommand with null intent; running=" + running.get());
            return running.get() ? START_STICKY : START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.i(TAG, "onStartCommand action=" + action + " running=" + running.get());

        if (ACTION_STOP_STREAM.equals(action)) {
            stopStreaming();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START_STREAM.equals(action)) {
            Log.w(TAG, "Ignoring unsupported action");
            return START_NOT_STICKY;
        }

        if (running.get()) {
            // If service is already alive but capture was lost, allow a new START to reattach.
            if (mediaProjection == null && hasProjectionGrant()) {
                Log.i(TAG, "Reattaching capture to existing stream service");
                if (initializeProjectionFromGrant()) {
                    startCapture();
                }
            } else {
                Log.i(TAG, "Service already running");
            }
            return START_STICKY;
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EZC Quest Stream")
                .setContentText("Streaming at http://" + getLocalIpAddress() + ":" + PORT)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        running.set(true);
        executor = Executors.newCachedThreadPool();

        if (!initializeProjectionFromGrant()) {
            Log.w(TAG, "Missing MediaProjection grant data; serving black fallback until grant is available");
            ensureBlackFallbackFrame(960, 540);
            startHttpServer();
            startBlackFrameWatchdog();
            return START_STICKY;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection stopped by system");
                stopCaptureOnly();
                if (blackFrame != null) {
                    latestJpeg.set(blackFrame);
                }
            }
        }, null);

        startCapture();
        startHttpServer();
        startBlackFrameWatchdog();
        Log.i(TAG, "Streaming initialized on http://" + getLocalIpAddress() + ":" + PORT);

        return START_STICKY;
    }

    private void startCapture() {
        stopDisplayOnly();

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        int width = Math.max(960, metrics.widthPixels);
        int height = Math.max(540, metrics.heightPixels);
        int density = metrics.densityDpi;

        ensureBlackFallbackFrame(width, height);
        latestJpeg.set(blackFrame);
        lastFrameAtMs.set(System.currentTimeMillis());

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "quest_stream_display",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                imageReader.getSurface(),
                null,
                null
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) {
                    return;
                }

                Image.Plane[] planes = image.getPlanes();
                if (planes.length == 0) {
                    return;
                }

                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * image.getWidth();

                Bitmap bitmap = Bitmap.createBitmap(
                        image.getWidth() + rowPadding / pixelStride,
                        image.getHeight(),
                        Bitmap.Config.ARGB_8888
                );
                bitmap.copyPixelsFromBuffer(buffer);

                Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
                drawLiveBadge(cropped);
                ByteArrayOutputStream jpegStream = new ByteArrayOutputStream();
                cropped.compress(Bitmap.CompressFormat.JPEG, 70, jpegStream);
                latestJpeg.set(jpegStream.toByteArray());
                lastFrameAtMs.set(System.currentTimeMillis());

                cropped.recycle();
                bitmap.recycle();
            } catch (Throwable t) {
                Log.w(TAG, "Frame capture error", t);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, null);
    }

    private void ensureBlackFallbackFrame(int width, int height) {
        if (blackFrame == null) {
            blackFrame = createBlackJpeg(width, height);
        }
    }

    private boolean hasProjectionGrant() {
        return sharedProjectionData != null && sharedResultCode != 0;
    }

    private boolean initializeProjectionFromGrant() {
        if (projectionManager == null) {
            projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        }
        if (projectionManager == null || !hasProjectionGrant()) {
            return false;
        }
        try {
            mediaProjection = projectionManager.getMediaProjection(sharedResultCode, sharedProjectionData);
            return mediaProjection != null;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize MediaProjection from grant", t);
            mediaProjection = null;
            return false;
        }
    }

    private void stopCaptureOnly() {
        stopDisplayOnly();

        if (mediaProjection != null) {
            mediaProjection = null;
        }
        sharedProjectionData = null;
        sharedResultCode = 0;
    }

    private void stopDisplayOnly() {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    private void startBlackFrameWatchdog() {
        executor.execute(() -> {
            while (running.get()) {
                long delta = System.currentTimeMillis() - lastFrameAtMs.get();
                if (delta > 1200 && blackFrame != null) {
                    // When headset sleeps or display is blank, keep stream alive with black frames.
                    latestJpeg.set(blackFrame);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        });
    }

    private static byte[] createBlackJpeg(int width, int height) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.BLACK);
        drawLiveBadge(bmp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static void drawLiveBadge(Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        float scale = Math.max(1f, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 1100f);
        float dotRadius = 12f * scale;
        float pillHeight = 34f * scale;
        float pillWidth = 112f * scale;
        float marginX = BADGE_MARGIN_X * scale;
        float marginY = BADGE_MARGIN_Y * scale;

        float right = bitmap.getWidth() - marginX;
        float top = marginY;
        float left = right - pillWidth;
        float bottom = top + pillHeight;

        Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pillPaint.setColor(Color.argb(170, 10, 15, 26));
        canvas.drawRoundRect(new RectF(left, top, right, bottom), 18f * scale, 18f * scale, pillPaint);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.rgb(234, 56, 76));
        float dotCenterX = left + (16f * scale);
        float dotCenterY = top + (pillHeight / 2f);
        canvas.drawCircle(dotCenterX, dotCenterY, dotRadius, dotPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(18f * scale);
        textPaint.setFakeBoldText(true);
        canvas.drawText("LIVE", left + (34f * scale), top + (22f * scale), textPaint);
    }

    private void startHttpServer() {
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "HTTP server listening on " + PORT);
                while (running.get()) {
                    Socket client = serverSocket.accept();
                    executor.execute(() -> handleClient(client));
                }
            } catch (IOException e) {
                Log.e(TAG, "HTTP server failed", e);
            }
        });
    }

    private void handleClient(Socket client) {
        try {
            OutputStream out = client.getOutputStream();
            String requestLine = readRequestLine(client);
            if (requestLine == null) {
                client.close();
                return;
            }

            if (requestLine.contains("GET /stream")) {
                writeMjpegHeaders(out);
                while (running.get() && !client.isClosed()) {
                    byte[] frame = latestJpeg.get();
                    if (frame == null) {
                        Thread.sleep(25);
                        continue;
                    }

                    out.write(("--frame\r\n"
                            + "Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + frame.length + "\r\n\r\n").getBytes());
                    out.write(frame);
                    out.write("\r\n".getBytes());
                    out.flush();
                    Thread.sleep(50);
                }
            } else {
                String ip = getLocalIpAddress();
                String body = "<!doctype html><html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                    + "<title>EZC Quest Stream</title>"
                    + "<style>"
                    + "body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:linear-gradient(140deg,#0f1422,#111827,#1d2535);color:#ecf2ff;}"
                    + ".wrap{max-width:960px;margin:28px auto;padding:18px;}"
                    + ".card{background:rgba(8,12,20,.72);backdrop-filter:blur(8px);border:1px solid rgba(255,255,255,.15);border-radius:22px;padding:20px;box-shadow:0 24px 54px rgba(0,0,0,.35);}"
                    + "h1{margin:0 0 6px;font-size:32px;}"
                    + "p{margin:6px 0 0;color:#b9c5df;}"
                    + ".slogan{font-size:18px;color:#d7e3ff;margin-bottom:14px;}"
                    + ".url{margin:14px 0;padding:12px;border-radius:14px;background:#0f1729;border:1px solid #2a3753;word-break:break-all;}"
                    + "img{display:block;width:100%;height:auto;border-radius:16px;border:1px solid rgba(255,255,255,.18);box-shadow:0 16px 36px rgba(0,0,0,.35);transition:transform .18s ease,box-shadow .18s ease;}"
                    + "img:hover{transform:translateY(-2px);box-shadow:0 22px 44px rgba(0,0,0,.42);}"
                    + "</style></head><body><div class='wrap'><div class='card'>"
                    + "<h1>EZC Quest Stream</h1><p class='slogan'>Easy, see?</p>"
                    + "<p>Built to simplify Meta Quest streaming to Mac without Meta Horizon or Android Developer Hub.</p>"
                    + "<div class='url'>http://" + ip + ":" + PORT + "/stream</div>"
                    + "<img src='/stream' alt='Live stream preview'></div></div></body></html>";
                byte[] bytes = body.getBytes();
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + bytes.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes());
                out.write(bytes);
                out.flush();
            }
        } catch (Throwable t) {
            if (t instanceof SocketException) {
                Log.i(TAG, "Client disconnected");
            } else {
                Log.e(TAG, "Client handler failed", t);
            }
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readRequestLine(Socket client) {
        try {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = client.getInputStream().read()) != -1) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    sb.append((char) c);
                }
                if (sb.length() > 512) {
                    break;
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMjpegHeaders(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Pragma: no-cache\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").getBytes());
        out.flush();
    }

    private void stopStreaming() {
        Log.i(TAG, "Stopping stream service");
        running.set(false);

        stopCaptureOnly();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        sharedProjectionData = null;
        sharedResultCode = 0;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Quest Stream",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }

    public static String getLocalIpAddress() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress() != null && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }
}
