package com.pure.translate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * الخدمة الحقيقية اللي تشتغل بالخلفية.
 * تسوي ثلاث اشياء بنفس الوقت:
 *  1) فقاعة عائمة صغيرة (بيور) تكدر تسحبها وتضغط عليها لإخفائها.
 *  2) كل 2.5 ثانية: تاخذ سكرين شوت داخلي من MediaProjection، تسوي عليه OCR (ML Kit - مجاني ومحلي بالكامل)،
 *     تترجم كل جملة (ML Kit Translate - يترجم على نفس الجهاز بعد أول تحميل للموديل، بدون أي مفتاح API).
 *  3) ترسم فوق كل جملة صندوق أبيض (تبييض) وتحطه فوقه النص المترجم - بدون ما توقف اللمس عن اللعبة/الفيديو تحتها.
 *
 * الفقاعة تبدي تشتغل توها ما تفتح الخدمة - ما يحتاج تضغط عليها عشان الترجمة تشتغل.
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "pure_translate_channel";
    private static final int NOTIF_ID = 1;
    private static final long CAPTURE_INTERVAL_MS = 2500;
    private static final long HIDE_TAP_WINDOW_MS = 3500; // مهلة الخمس ضغطات
    private static final int HIDE_TAP_TARGET = 5;

    private WindowManager windowManager;

    // الفقاعة (الأيقونة الصغيرة اللي تسحبها)
    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    // لوحة الترجمة الشفافة اللي تغطي الشاشة كلها (بس ما تمسك لمس - تعدي اللمسة لتحتها)
    private FrameLayout translationLayer;
    private WindowManager.LayoutParams layerParams;

    // خيارات الفقاعة (إخفاء / إيقاف) تطلع لما تضغط عليها
    private View optionsPanel;
    private WindowManager.LayoutParams optionsParams;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextRecognizer recognizer;
    private Translator translator;
    private boolean translatorReady = false;

    private int screenWidth, screenHeight, screenDensity;

    // حالة الإخفاء + عداد الخمس ضغطات
    private boolean isHidden = false;
    private int hideTapCount = 0;
    private long firstHideTapTime = 0;

    private boolean isCapturing = false;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        setupTranslator();
        setupTranslationLayer();
        setupBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());

        if (intent != null && mediaProjection == null) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            if (data != null) {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpm.getMediaProjection(resultCode, data);
                setupVirtualDisplay();
                handler.post(captureLoop);
            }
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------------
    // الإشعار الثابت (لازم بأندرويد لأي خدمة تسوي تسجيل شاشة بالخلفية)
    // ---------------------------------------------------------------------
    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "بيور ترجمة", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    // ---------------------------------------------------------------------
    // المترجم (ML Kit) - ينزل موديل اللغة أول مرة بس (يحتاج نت أول مرة)، بعدها يشتغل offline
    // ---------------------------------------------------------------------
    private void setupTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build();
        translator = Translation.getClient(options);
        translator.downloadModelIfNeeded()
                .addOnSuccessListener(v -> translatorReady = true)
                .addOnFailureListener(e -> translatorReady = false);
    }

    // ---------------------------------------------------------------------
    // طبقة الترجمة: تغطي الشاشة كلها بس ما تمسك اللمس (FLAG_NOT_TOUCHABLE)
    // ---------------------------------------------------------------------
    private void setupTranslationLayer() {
        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDensity = dm.densityDpi;

        translationLayer = new FrameLayout(this);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layerParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layerParams.gravity = Gravity.TOP | Gravity.START;

        windowManager.addView(translationLayer, layerParams);
    }

    // ---------------------------------------------------------------------
    // الفقاعة القابلة للسحب + الإخفاء + الخمس ضغطات
    // ---------------------------------------------------------------------
    private void setupBubble() {
        LayoutInflater inflater = LayoutInflater.from(this);
        bubbleView = inflater.inflate(R.layout.layout_bubble, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 20;
        bubbleParams.y = 200;

        windowManager.addView(bubbleView, bubbleParams);

        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean dragged = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        dragged = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            dragged = true;
                        }
                        // وإحنا مخفيين ما نغير المكان إطلاقاً - بس نعد الضغطات
                        if (!isHidden) {
                            bubbleParams.x = initialX + dx;
                            bubbleParams.y = initialY + dy;
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (isHidden) {
                            registerHiddenTap();
                        } else if (!dragged) {
                            toggleOptionsPanel();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    /** كل ضغطة وإحنا مخفيين تزيد العداد؛ لو وصلت 5 ضغطات بمهلة معقولة ترجع الفقاعة تظهر. */
    private void registerHiddenTap() {
        long now = System.currentTimeMillis();
        if (hideTapCount == 0 || (now - firstHideTapTime) > HIDE_TAP_WINDOW_MS) {
            hideTapCount = 1;
            firstHideTapTime = now;
        } else {
            hideTapCount++;
        }
        if (hideTapCount >= HIDE_TAP_TARGET) {
            showBubbleAgain();
        }
    }

    private void showBubbleAgain() {
        isHidden = false;
        hideTapCount = 0;
        bubbleView.setAlpha(1f);
        bubbleView.setScaleX(1f);
        bubbleView.setScaleY(1f);
    }

    /** إخفاء الفقاعة بنفس مكانها - تضل تلمس الضغطات بس ما تبين ولا تتحرك. */
    private void hideBubble() {
        isHidden = true;
        hideTapCount = 0;
        bubbleView.setAlpha(0.02f); // شبه مخفية بالكامل بس تضل قابلة للمس بنفس مكانها
        bubbleView.setScaleX(0.4f);
        bubbleView.setScaleY(0.4f);
        removeOptionsPanel();
    }

    private void toggleOptionsPanel() {
        if (optionsPanel != null) {
            removeOptionsPanel();
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        optionsPanel = inflater.inflate(R.layout.layout_overlay_panel, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        optionsParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        optionsParams.gravity = Gravity.TOP | Gravity.START;
        optionsParams.x = bubbleParams.x;
        optionsParams.y = bubbleParams.y + 90;

        windowManager.addView(optionsPanel, optionsParams);

        optionsPanel.findViewById(R.id.btnHide).setOnClickListener(v -> {
            hideBubble();
        });
        optionsPanel.findViewById(R.id.btnStop).setOnClickListener(v -> {
            stopSelf();
        });
    }

    private void removeOptionsPanel() {
        if (optionsPanel != null) {
            windowManager.removeView(optionsPanel);
            optionsPanel = null;
        }
    }

    // ---------------------------------------------------------------------
    // تسجيل الشاشة (MediaProjection) - هذا اللي يخلي التطبيق "يشوف" شنو موجود بالشاشة
    // ---------------------------------------------------------------------
    private void setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "PureTranslateCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler);
    }

    /** حلقة تتكرر كل 2.5 ثانية: تخفي طبقة الترجمة القديمة، تاخذ صورة نظيفة، تعالجها، ترجعها تظهر. */
    private final Runnable captureLoop = new Runnable() {
        @Override
        public void run() {
            if (!isCapturing) {
                captureAndTranslate();
            }
            handler.postDelayed(this, CAPTURE_INTERVAL_MS);
        }
    };

    private void captureAndTranslate() {
        if (imageReader == null) return;
        isCapturing = true;

        // نخفي الترجمة القديمة لحظياً عشان السكرين شوت ما يلتقط صناديقنا احنا (تجنب حلقة لا نهائية)
        translationLayer.setVisibility(View.INVISIBLE);

        handler.postDelayed(() -> {
            Bitmap bitmap = null;
            try {
                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    bitmap = imageToBitmap(image);
                    image.close();
                }
            } catch (Exception ignored) {
            }

            translationLayer.setVisibility(View.VISIBLE);

            if (bitmap != null) {
                runOcr(bitmap);
            } else {
                isCapturing = false;
            }
        }, 120); // مهلة بسيطة عشان الشاشة تنرسم بدون طبقتنا قبل لا ناخذ الصورة
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;

        Bitmap bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);
    }

    private void runOcr(Bitmap bitmap) {
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(input)
                .addOnSuccessListener(this::handleOcrResult)
                .addOnFailureListener(e -> isCapturing = false);
    }

    private void handleOcrResult(Text result) {
        List<Text.TextBlock> blocks = result.getTextBlocks();
        translationLayer.removeAllViews();

        if (blocks.isEmpty() || !translatorReady) {
            isCapturing = false;
            return;
        }

        final int[] remaining = {blocks.size()};
        for (Text.TextBlock block : blocks) {
            String original = block.getText();
            // نتجاهل الكتل القصيرة جداً أو اللي ماكو فيها احرف حقيقية (زي تأثيرات صوتية رمزية بسيطة)
            if (original.trim().length() < 2) {
                remaining[0]--;
                continue;
            }
            translator.translate(original)
                    .addOnSuccessListener(translated -> {
                        if (block.getBoundingBox() != null) {
                            addTranslatedBox(block.getBoundingBox(), translated);
                        }
                        remaining[0]--;
                        if (remaining[0] <= 0) isCapturing = false;
                    })
                    .addOnFailureListener(e -> {
                        remaining[0]--;
                        if (remaining[0] <= 0) isCapturing = false;
                    });
        }
        if (blocks.isEmpty()) isCapturing = false;
    }

    /** يرسم صندوق أبيض (تبييض) فوق النص الأصلي وبداخله النص المترجم - بنفس مكان وحجم النص الأصلي تقريباً. */
    private void addTranslatedBox(android.graphics.Rect rect, String translatedText) {
        TextView box = new TextView(this);
        box.setText(translatedText);
        box.setBackgroundResource(R.drawable.bg_panel);
        box.setTextColor(getColor(R.color.ink));
        box.setTextSize(11);
        box.setPadding(8, 4, 8, 4);
        box.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.max(rect.width(), 80), FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = rect.left;
        lp.topMargin = rect.top;
        translationLayer.addView(box, lp);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);

        if (translator != null) translator.close();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();

        if (bubbleView != null) windowManager.removeView(bubbleView);
        if (translationLayer != null) windowManager.removeView(translationLayer);
        removeOptionsPanel();
    }
}
