package com.quest.stream;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.Manifest;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_CODE = 1001;
    private MediaProjectionManager projectionManager;
    private TextView statusText;
    private Button startBtn;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean captureRequestInFlight = false;
    private static final String TAG = "QuestStreamMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        startBtn = findViewById(R.id.btn_start);
        Button stopBtn = findViewById(R.id.btn_stop);
        statusText = findViewById(R.id.status);

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    // No-op: service can still run on Quest even if notifications are denied.
                });

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        String ip = StreamService.getLocalIpAddress();
        statusText.setText("Ready to stream\nOpen on Mac: http://" + ip + ":8080/stream");

        startBtn.setOnClickListener(v -> requestCapturePermission("Start clicked"));

        stopBtn.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, StreamService.class);
            serviceIntent.setAction(StreamService.ACTION_STOP_STREAM);
            startService(serviceIntent);
            Log.i(TAG, "Stop clicked; stop intent sent");
            statusText.setText("Stopped\nPress Start Stream to go live again.");
            captureRequestInFlight = false;
            startBtn.setEnabled(true);
        });

    }

    private void requestCapturePermission(String reason) {
        if (captureRequestInFlight) {
            return;
        }
        captureRequestInFlight = true;
        Log.i(TAG, reason + "; requesting MediaProjection permission");
        startBtn.setEnabled(false);
        statusText.setText("Waiting for permission...\nApprove in headset to go live.");
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, PERMISSION_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i(TAG, "onActivityResult requestCode=" + requestCode + " resultCode=" + resultCode + " hasData=" + (data != null));
        if (requestCode == PERMISSION_CODE && resultCode == RESULT_OK && data != null) {
            StreamService.setProjectionGrant(resultCode, data);
            Intent serviceIntent = new Intent(this, StreamService.class);
            serviceIntent.setAction(StreamService.ACTION_START_STREAM);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            String ip = StreamService.getLocalIpAddress();
            statusText.setText("Streaming live\nOpen on Mac: http://" + ip + ":8080/stream");
            captureRequestInFlight = false;
            Log.i(TAG, "Service start intent sent; status set to streaming");
            return;
        }

        statusText.setText("Permission denied or canceled\nTap Start Stream and allow capture.");
        captureRequestInFlight = false;
        startBtn.setEnabled(true);
        Log.w(TAG, "Permission denied/canceled or unexpected result");
    }
}
