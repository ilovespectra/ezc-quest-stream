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
    private Button toggleBtn;
    private TextView liveChip;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean captureRequestInFlight = false;
    private boolean isStreaming = false;
    private static final String TAG = "QuestStreamMain";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        toggleBtn = findViewById(R.id.btn_toggle);
        statusText = findViewById(R.id.status);
        liveChip = findViewById(R.id.chip_live);

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
        updateButtonState();

        toggleBtn.setOnClickListener(v -> handleToggleClick());
    }

    private void handleToggleClick() {
        if (isStreaming) {
            // Stop streaming
            stopStreaming();
        } else {
            // Start streaming
            requestCapturePermission("Toggle clicked");
        }
    }

    private void stopStreaming() {
        Intent serviceIntent = new Intent(this, StreamService.class);
        serviceIntent.setAction(StreamService.ACTION_STOP_STREAM);
        startService(serviceIntent);
        Log.i(TAG, "Stop clicked; stop intent sent");

        isStreaming = false;
        updateButtonState();
        statusText.setText("Stopped\nTap button to go live again.");
    }

    private void requestCapturePermission(String reason) {
        if (captureRequestInFlight) {
            return;
        }
        captureRequestInFlight = true;
        Log.i(TAG, reason + "; requesting MediaProjection permission");
        updateButtonState();
        statusText.setText("Waiting for permission...\nApprove in headset to go live.");
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(intent, PERMISSION_CODE);
    }

    private void updateButtonState() {
        if (isStreaming) {
            toggleBtn.setText("Stop Streaming");
            toggleBtn.setBackground(getDrawable(R.drawable.bg_button_stop));
            toggleBtn.setTextColor(getColor(android.R.color.white));
            liveChip.setText("LIVE");
            liveChip.setBackground(getDrawable(R.drawable.bg_live_chip));
        } else {
            toggleBtn.setText("Start Stream");
            toggleBtn.setBackground(getDrawable(R.drawable.bg_button_primary));
            toggleBtn.setTextColor(getColor(android.R.color.white));
            liveChip.setText("READY");
            liveChip.setBackground(getDrawable(R.drawable.bg_chip_idle));
        }
        toggleBtn.setEnabled(!captureRequestInFlight);
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

            isStreaming = true;
            captureRequestInFlight = false;
            updateButtonState();

            String ip = StreamService.getLocalIpAddress();
            statusText.setText("Streaming live 🔴\nOpen on Mac: http://" + ip + ":8080/stream");
            Log.i(TAG, "Service start intent sent; status set to streaming");
            return;
        }

        statusText.setText("Permission denied or canceled\nTap button to try again.");
        captureRequestInFlight = false;
        updateButtonState();
        Log.w(TAG, "Permission denied/canceled or unexpected result");
    }
}
