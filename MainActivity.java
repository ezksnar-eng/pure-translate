package com.pure.translate;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

/**
 * الشاشة الرئيسية - كل وظيفتها إنها تاخذ صلاحيتين بس:
 * 1) صلاحية النافذة العائمة (Overlay) عشان الفقاعة تكدر تطلع فوق أي تطبيق.
 * 2) صلاحية تسجيل الشاشة (Screen Capture) عشان الخدمة تكدر تقرأ اللي موجود بالشاشة وتترجمه.
 * بعد أخذ الصلاحيتين، تشغل OverlayService وتقفل نفسها - كل الشغل الحقيقي يصير بالخدمة.
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_CAPTURE = 1002;

    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        Button btnGrantOverlay = findViewById(R.id.btnGrantOverlay);
        Button btnStart = findViewById(R.id.btnStart);

        btnGrantOverlay.setOnClickListener(v -> requestOverlayPermission());
        btnStart.setOnClickListener(v -> requestScreenCapture());
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_OVERLAY);
        } else {
            Toast.makeText(this, "صلاحية النافذة العائمة مفعّلة أصلاً", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "لازم أول تفعّل صلاحية النافذة العائمة", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            // نبدي الخدمة ونمررلها صلاحية تسجيل الشاشة - من هسه الخدمة تشتغل لحالها
            // وما يحتاج تفتح التطبيق مرة ثانية إلى إذا كفلت الخدمة يدوياً.
            Intent serviceIntent = new Intent(this, OverlayService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "بدأت نافذة بيور - اضغط هوم وشوفها فوق أي تطبيق", Toast.LENGTH_LONG).show();
            finish();
        } else if (requestCode == REQ_CAPTURE) {
            Toast.makeText(this, "لازم توافق على تسجيل الشاشة عشان الترجمة تشتغل", Toast.LENGTH_LONG).show();
        }
    }
}
