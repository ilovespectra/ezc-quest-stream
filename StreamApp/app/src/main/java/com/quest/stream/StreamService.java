package com.quest.stream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import java.nio.charset.StandardCharsets;
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

    private static volatile int sharedResultCode = 0;
    private static volatile Intent sharedProjectionData;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<byte[]> latestJpeg = new AtomicReference<>();
    private final AtomicReference<byte[]> latestJpegBalanced = new AtomicReference<>();
    private final AtomicReference<byte[]> latestJpegSaver = new AtomicReference<>();
    private final AtomicLong lastFrameAtMs = new AtomicLong(0L);
    private final AtomicLong streamEpoch = new AtomicLong(0L);
    private final AtomicLong lastRecoverAttemptAtMs = new AtomicLong(0L);
    private final AtomicLong frameCounter = new AtomicLong(0L);

    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private byte[] blackFrame;
    private byte[] blackFrameBalanced;
    private byte[] blackFrameSaver;
    private final AtomicLong lastCaptureRestartAtMs = new AtomicLong(0L);

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
        streamEpoch.incrementAndGet();

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "quest_stream_display",
                width,
                height,
                density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
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
                byte[] high = encodeJpeg(cropped, 72);
                byte[] balanced = encodeScaledJpeg(cropped, 0.72f, 62);
                byte[] saver = encodeScaledJpeg(cropped, 0.52f, 54);

                latestJpeg.set(high);
                latestJpegBalanced.set(balanced);
                latestJpegSaver.set(saver);
                lastFrameAtMs.set(System.currentTimeMillis());
                frameCounter.incrementAndGet();

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
        if (blackFrame == null || blackFrameBalanced == null || blackFrameSaver == null) {
            blackFrame = createBlackJpeg(width, height);
            blackFrameBalanced = createBlackJpeg(Math.max(640, (int) (width * 0.72f)), Math.max(360, (int) (height * 0.72f)));
            blackFrameSaver = createBlackJpeg(Math.max(480, (int) (width * 0.52f)), Math.max(270, (int) (height * 0.52f)));
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
                    latestJpegBalanced.set(blackFrameBalanced);
                    latestJpegSaver.set(blackFrameSaver);
                }
                attemptCaptureRestartIfStale(delta);
                attemptProjectionRecovery(delta);
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out);
        bmp.recycle();
        return out.toByteArray();
    }

    private static byte[] encodeJpeg(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private static byte[] encodeScaledJpeg(Bitmap source, float scale, int quality) {
        int targetWidth = Math.max(480, Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(270, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        byte[] bytes = encodeJpeg(scaled, quality);
        scaled.recycle();
        return bytes;
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
                String quality = parseQualityFromRequestLine(requestLine);
                int frameDelayMs = "saver".equals(quality) ? 110 : ("balanced".equals(quality) ? 80 : 50);
                writeMjpegHeaders(out);
                while (running.get() && !client.isClosed()) {
                    byte[] frame = selectFrameForQuality(quality);
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
                    Thread.sleep(frameDelayMs);
                }
            } else if (requestLine.contains("GET /status")) {
                writeStatusJson(out);
            } else {
                String ip = getLocalIpAddress();
                String body = "<!doctype html><html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                    + "<title>EZC Quest Stream</title>"
                    + "<style>"
                    + "body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:linear-gradient(145deg,#0b1220,#0f1a31,#13243f);color:#ecf2ff;}"
                    + ".wrap{max-width:1200px;margin:18px auto;padding:16px;}"
                    + ".card{background:rgba(6,10,18,.72);backdrop-filter:blur(8px);border:1px solid rgba(255,255,255,.12);border-radius:18px;padding:16px;box-shadow:0 22px 48px rgba(0,0,0,.38);}"
                    + "h1{margin:0 0 4px;font-size:28px;}"
                    + "p{margin:4px 0 0;color:#b9c5df;}"
                    + ".slogan{font-size:16px;color:#d7e3ff;margin-bottom:12px;}"
                    + ".toolbar{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0 14px;align-items:center;}"
                    + "button,select{border:1px solid #304261;background:#15243f;color:#e9f0ff;border-radius:999px;padding:8px 12px;font-weight:600;cursor:pointer;}"
                    + "button:hover,select:hover{background:#1d3257;}"
                    + ".active{background:#2b4c80;border-color:#4f78bf;}"
                    + ".url{margin:0 0 12px;padding:10px;border-radius:12px;background:#0d172a;border:1px solid #273a5f;word-break:break-all;}"
                    + ".status{font-size:13px;color:#9eb1d6;margin-left:auto;}"
                    + ".stageWrap{position:relative;}"
                    + ".stage{display:flex;justify-content:center;align-items:center;height:70vh;background:#03070f;border:1px solid rgba(255,255,255,.18);border-radius:14px;padding:8px;overflow:hidden;transition:border-color .2s ease, box-shadow .2s ease;}"
                    + ".stage:hover{border-color:#7ab5ff;box-shadow:0 0 0 2px rgba(122,181,255,.28),0 20px 38px rgba(0,0,0,.42);}"
                    + "img{display:block;width:100%;height:100%;border-radius:10px;border:1px solid rgba(255,255,255,.14);box-shadow:0 12px 30px rgba(0,0,0,.35);background:#000;}"
                    + ".fit-all img{object-fit:contain;}"
                    + ".fit-crop img{object-fit:cover;}"
                    + ".fsBtn{position:absolute;right:12px;top:12px;border-radius:10px;padding:8px 10px;background:rgba(10,20,36,.8);border:1px solid rgba(146,182,235,.55);}"
                    + ".w1080{max-width:1920px}.w720{max-width:1280px}.w540{max-width:960px}"
                    + "</style></head><body><div class='wrap'><div class='card'>"
                    + "<h1>EZC Quest Stream</h1><p class='slogan'>Easy, see?</p>"
                    + "<p>Built to simplify Meta Quest streaming to Mac without Meta Horizon or Android Developer Hub.</p>"
                    + "<div class='url'>http://" + ip + ":" + PORT + "/stream</div>"
                    + "<div class='toolbar'>"
                    + "<button onclick=\"setSize('w1080')\">1920</button>"
                    + "<button onclick=\"setSize('w720')\">1280</button>"
                    + "<button onclick=\"setSize('w540')\">960</button>"
                    + "<button id='fitCropBtn' class='active' onclick=\"setFit('crop')\">Crop</button>"
                    + "<button id='fitAllBtn' onclick=\"setFit('all')\">All</button>"
                    + "<select id='qualitySel' onchange=\"setQuality(this.value)\">"
                    + "<option value='high'>Quality: High (best)</option>"
                    + "<option value='balanced'>Quality: Balanced</option>"
                    + "<option value='saver'>Quality: Saver</option>"
                    + "</select>"
                    + "<span id='statusText' class='status'>Connecting...</span>"
                    + "</div>"
                    + "<div id='stageWrap' class='stageWrap w1080'>"
                    + "<button class='fsBtn' title='Fullscreen' onclick=\"toggleFullscreen()\">⛶</button>"
                    + "<div id='stage' class='stage fit-crop'><img id='feed' src='/stream?quality=high' alt='Live stream preview'></div>"
                    + "</div>"
                    + "<script>"
                    + "let currentQuality='high';"
                    + "function setSize(c){const s=document.getElementById('stageWrap');s.classList.remove('w1080','w720','w540');s.classList.add(c);}"
                    + "function setFit(mode){const s=document.getElementById('stage');const c=document.getElementById('fitCropBtn');const a=document.getElementById('fitAllBtn');if(mode==='all'){s.classList.remove('fit-crop');s.classList.add('fit-all');a.classList.add('active');c.classList.remove('active');}else{s.classList.remove('fit-all');s.classList.add('fit-crop');c.classList.add('active');a.classList.remove('active');}}"
                    + "function setQuality(q){currentQuality=q;refreshFeed();}"
                    + "function toggleFullscreen(){const el=document.getElementById('stageWrap');if(!document.fullscreenElement){el.requestFullscreen&&el.requestFullscreen();}else{document.exitFullscreen&&document.exitFullscreen();}}"
                    + "let lastEpoch=-1,lastFrame=-1,stalePolls=0;"
                    + "function refreshFeed(){const img=document.getElementById('feed');img.src='/stream?quality='+encodeURIComponent(currentQuality)+'&ts='+Date.now();}"
                    + "function markState(t){document.getElementById('statusText').textContent=t;}"
                    + "async function pollStatus(){try{const r=await fetch('/status?ts='+Date.now(),{cache:'no-store'});const s=await r.json();"
                    + "const isRunning=s.state==='running';markState(isRunning?'Live':'Reconnecting');"
                    + "if(lastEpoch!==-1&&s.epoch!==lastEpoch){refreshFeed();}"
                    + "if(lastFrame!==-1&&s.frame===lastFrame){stalePolls+=1;}else{if(stalePolls>=2&&isRunning){refreshFeed();}stalePolls=0;}"
                    + "if(!isRunning&&stalePolls>=2){refreshFeed();}"
                    + "lastEpoch=s.epoch;lastFrame=s.frame;"
                    + "}catch(e){markState('Reconnecting');stalePolls+=1;if(stalePolls>=2){refreshFeed();stalePolls=0;}}}"
                    + "setInterval(pollStatus,1200);setInterval(refreshFeed,20000);pollStatus();"
                    + "</script></div></div></body></html>";
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

    private void attemptCaptureRestartIfStale(long deltaMs) {
        if (mediaProjection == null || deltaMs < 2500) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastRestart = lastCaptureRestartAtMs.get();
        if (now - lastRestart < 4000) {
            return;
        }
        if (lastCaptureRestartAtMs.compareAndSet(lastRestart, now)) {
            Log.w(TAG, "No fresh frames; restarting capture pipeline");
            try {
                startCapture();
            } catch (Throwable t) {
                Log.e(TAG, "Capture restart failed", t);
                mediaProjection = null;
            }
        }
    }

    private void attemptProjectionRecovery(long deltaMs) {
        if (!running.get() || mediaProjection != null || !hasProjectionGrant() || deltaMs < 1200) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastAttempt = lastRecoverAttemptAtMs.get();
        if (now - lastAttempt < 2500) {
            return;
        }
        if (lastRecoverAttemptAtMs.compareAndSet(lastAttempt, now)) {
            Log.i(TAG, "Attempting projection recovery from cached grant");
            if (initializeProjectionFromGrant()) {
                startCapture();
            }
        }
    }

    private String parseQualityFromRequestLine(String requestLine) {
        String lower = requestLine.toLowerCase();
        if (lower.contains("quality=saver")) {
            return "saver";
        }
        if (lower.contains("quality=balanced")) {
            return "balanced";
        }
        return "high";
    }

    private byte[] selectFrameForQuality(String quality) {
        if ("saver".equals(quality)) {
            byte[] saver = latestJpegSaver.get();
            return saver != null ? saver : latestJpeg.get();
        }
        if ("balanced".equals(quality)) {
            byte[] balanced = latestJpegBalanced.get();
            return balanced != null ? balanced : latestJpeg.get();
        }
        return latestJpeg.get();
    }

    private void writeStatusJson(OutputStream out) throws IOException {
        long ageMs = Math.max(0L, System.currentTimeMillis() - lastFrameAtMs.get());
        String state = (running.get() && ageMs < 2500) ? "running" : "recovering";
        String json = "{\"state\":\"" + state + "\",\"epoch\":" + streamEpoch.get() + ",\"ageMs\":" + ageMs + ",\"frame\":" + frameCounter.get() + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
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
