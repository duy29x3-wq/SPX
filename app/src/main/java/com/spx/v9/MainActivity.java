package com.spx.v9;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Matrix;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int PICK_NATIVE = 102;
    private static final int PICK_WEB = 101;
    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;
    private final ArrayList<Uri> queue = new ArrayList<>();
    private TextRecognizer recognizer;
    private int done = 0, total = 0;
    private boolean scanning = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(1024, 1024);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // Bridge is only exposed to our packaged, local HTML file.
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void pickImages() {
                runOnUiThread(() -> openNativePicker());
            }
        }, "SPXNative");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = cb;
                try { startActivityForResult(p.createIntent(), PICK_WEB); return true; }
                catch (Exception e) { pendingFiles = null; cb.onReceiveValue(null); return true; }
            }
        });
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void openNativePicker() {
        if (scanning) { sendError("Đang quét ảnh, vui lòng chờ hoàn tất."); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, PICK_NATIVE); }
        catch (Exception e) { sendError("Không mở được thư viện ảnh: " + e.getMessage()); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_WEB) {
            if (pendingFiles != null) {
                pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
                pendingFiles = null;
            }
            return;
        }
        if (request != PICK_NATIVE) return;
        queue.clear(); done = 0;
        if (result == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) for (int i=0; i<clip.getItemCount(); i++) queue.add(clip.getItemAt(i).getUri());
            if (queue.isEmpty() && data.getData() != null) queue.add(data.getData());
        }
        total = queue.size();
        if (total > 0) scanning = true;
        if (total == 0) { runJS("window.spxNativeCancelled&&window.spxNativeCancelled()"); return; }
        runJS("window.spxNativeStart&&window.spxNativeStart(" + total + ")");
        processNext();
    }

    private static final String OCR_VERSION = "SPX-TOTAL-ROI-20261108-V5";
    private void processNext() {
        if (queue.isEmpty()) { scanning = false; runJS("window.spxNativeDone&&window.spxNativeDone()"); return; }
        Uri uri = queue.remove(0);
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            recognizer.process(image).addOnSuccessListener(result -> {
                // Only run a second pass when the full-screen OCR missed the total header.
                if (hasTotalHeader(result.getText())) {
                    deliverResult(uri, image, result, null, null);
                } else {
                    scanTotalRegion(uri, image, result);
                }
            }).addOnFailureListener(e -> {
                sendError("Không đọc được ảnh: " + e.getMessage());
                done++;
                processNext();
            });
        } catch (Exception e) { sendError("Ảnh không hợp lệ: " + e.getMessage()); done++; processNext(); }
    }

    private boolean hasTotalHeader(String text) {
        if (text == null) return false;
        for (String line : text.split("\\r?\\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).matches("(?s).*tổng\\s*[:：-]?\\s*\\d+\\s*(?:đơn|don).*")) return true;
        }
        return false;
    }

    // V4: crop the actual total line, excluding the large dropdown and weight cards.
    // Retry with a wider crop only if the focused OCR fails to find the total.
    private void scanTotalRegion(Uri uri, InputImage image, Text fullResult) {
        scanTotalRegionPass(uri, image, fullResult, 0);
    }

    private void scanTotalRegionPass(Uri uri, InputImage image, Text fullResult, int pass) {
        Bitmap source = null;
        try {
            source = loadUprightBitmap(uri);
            if (source == null) {
                deliverResult(uri, image, fullResult, null, null, "decode-failed"); return;
            }
            int w = source.getWidth(), h = source.getHeight();
            // Typical SPX screenshots put 'Tổng N đơn hàng' at ~26% height.
            // First crop is text-only; second allows different screen layouts.
            float xRight = pass == 0 ? 0.95f : 0.97f;
            float yTop = pass == 0 ? 0.215f : 0.165f;
            float yBottom = pass == 0 ? 0.345f : 0.425f;
            int left = Math.max(0, Math.round(w * 0.035f));
            int top = Math.max(0, Math.round(h * yTop));
            int right = Math.min(w, Math.round(w * xRight));
            int bottom = Math.min(h, Math.round(h * yBottom));
            if (right <= left || bottom <= top) {
                source.recycle(); deliverResult(uri, image, fullResult, null, null, "invalid-crop"); return;
            }
            Bitmap crop = Bitmap.createBitmap(source, left, top, right-left, bottom-top);
            source.recycle(); source = null;
            int scale = 3;
            Bitmap enlarged = Bitmap.createScaledBitmap(crop, crop.getWidth()*scale, crop.getHeight()*scale, true);
            crop.recycle();
            recognizer.process(InputImage.fromBitmap(enlarged, 0))
                .addOnSuccessListener(extra -> {
                    try {
                        if (pass == 0 && !hasTotalHeader(extra.getText())) {
                            scanTotalRegionPass(uri, image, fullResult, 1);
                        } else {
                            deliverResult(uri, image, fullResult, extra,
                                    new int[]{left, top, scale}, "pass-"+(pass+1));
                        }
                    } finally { enlarged.recycle(); }
                })
                .addOnFailureListener(e -> {
                    enlarged.recycle();
                    if (pass == 0) scanTotalRegionPass(uri, image, fullResult, 1);
                    else deliverResult(uri, image, fullResult, null, null, "ocr-failed:"+e.getClass().getSimpleName());
                });
        } catch (Exception e) {
            if (source != null && !source.isRecycled()) source.recycle();
            Log.w("SPX", "V4 total crop failed", e);
            if (pass == 0) scanTotalRegionPass(uri, image, fullResult, 1);
            else deliverResult(uri, image, fullResult, null, null, "crop-failed:"+e.getClass().getSimpleName());
        }
    }

    private Bitmap loadUprightBitmap(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 2000) sample *= 2;
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(is, null, options);
        }
        if (bitmap == null) return null;
        int orientation = 1;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            orientation = new android.media.ExifInterface(is).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, 1);
        } catch (Exception ignored) { }
        int degrees = orientation == 3 ? 180 : orientation == 6 ? 90 : orientation == 8 ? 270 : 0;
        if (degrees == 0) return bitmap;
        Matrix m = new Matrix(); m.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        bitmap.recycle();
        return rotated;
    }

    private void deliverResult(Uri uri, InputImage image, Text fullResult, Text extra, int[] offset) {
        deliverResult(uri, image, fullResult, extra, offset, "full-image");
    }

    private void deliverResult(Uri uri, InputImage image, Text fullResult, Text extra, int[] offset, String roiStatus) {
        try {
            JSONObject obj = new JSONObject();
            String combined = fullResult.getText();
            // Append only lines from the total-header crop, never overwrite the
            // eight weight buckets already extracted from the original image.
            if (extra != null && extra.getText() != null && !extra.getText().trim().isEmpty()) {
                combined += "\n" + extra.getText();
            }
            obj.put("text", combined);
            obj.put("width", image.getWidth());
            obj.put("height", image.getHeight());
            JSONArray lines = new JSONArray();
            appendLines(lines, fullResult, null);
            if (extra != null) appendLines(lines, extra, offset);
            obj.put("lines", lines);
            obj.put("preview", makePreview(uri));
            obj.put("activeType", detectActiveTab(uri));
            obj.put("spxDetectorVersion", OCR_VERSION);
            obj.put("totalRegionOCR", roiStatus);
            obj.put("totalRegionText", extra == null ? "" : extra.getText());
            obj.put("totalRegionAvailable", extra != null && extra.getText() != null && !extra.getText().trim().isEmpty());
            done++;
            runJS("window.spxNativeResult&&window.spxNativeResult(" + obj.toString() + "," + done + "," + total + ")");
        } catch (Exception e) {
            sendError("Lỗi chuyển kết quả: " + e.getMessage());
        }
        processNext();
    }

    private void appendLines(JSONArray lines, Text result, int[] offset) throws Exception {
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r == null) continue;
                JSONObject item = new JSONObject();
                item.put("text", line.getText());
                if (offset == null) {
                    item.put("x", r.left); item.put("y", r.top);
                    item.put("w", r.width()); item.put("h", r.height());
                } else {
                    item.put("x", offset[0] + r.left / offset[2]);
                    item.put("y", offset[1] + r.top / offset[2]);
                    item.put("w", r.width() / offset[2]);
                    item.put("h", r.height() / offset[2]);
                }
                lines.put(item);
            }
        }
    }

    // Detect the selected SPX tab from the ORIGINAL screenshot (not the JPEG preview).
    // Return null on uncertain images; never guess a task from OCR text alone.
    private String detectActiveTab(Uri uri) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 1600) sample *= 2;
            opts.inSampleSize = sample;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(stream, null, opts);
            }
            if (bitmap == null) return null;
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            if (w < 180 || h < 300) return null;

            // SPX screenshots: underline is around 17.7% of image height.
            // Search a narrow vertical band to exclude orange logos and other controls.
            int y0 = Math.max(0, Math.round(h * 0.145f));
            int y1 = Math.min(h - 1, Math.round(h * 0.215f));
            int x0 = Math.round(w * 0.07f), x1 = Math.round(w * 0.73f);
            double[] scores = new double[3];
            int[] row = new int[w];
            for (int y = y0; y <= y1; y++) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1);
                int start = -1;
                for (int x = x0; x <= x1 + 1; x++) {
                    boolean orange = false;
                    if (x <= x1) {
                        int c = row[x];
                        int r = (c >>> 16) & 255, g = (c >>> 8) & 255, bl = c & 255;
                        // Orange-red SPX underline; permit JPEG variations and antialiasing.
                        orange = r >= 165 && r > g * 1.30 && g > bl * 1.10
                                && g >= 45 && g <= 185 && bl <= 155;
                    }
                    if (orange && start < 0) start = x;
                    if (!orange && start >= 0) {
                        int len = x - start;
                        if (len >= w * 0.035f && len <= w * 0.11f) {
                            float cx = (start + x) / (2f * w);
                            int task = cx < .29f ? 0 : cx < .48f ? 1 : cx < .70f ? 2 : -1;
                            if (task >= 0) scores[task] += len;
                        }
                        start = -1;
                    }
                }
            }
            int best = 0;
            for (int i = 1; i < 3; i++) if (scores[i] > scores[best]) best = i;
            double runnerUp = Math.max(scores[(best + 1) % 3], scores[(best + 2) % 3]);
            Log.d("SPX", "Orange tab scores: " + scores[0] + "," + scores[1] + "," + scores[2]);
            if (scores[best] < w * 0.05 || (runnerUp > 0 && scores[best] < runnerUp * 1.8)) return null;
            return new String[]{"giao", "lay", "hoan"}[best];
        } catch (Exception e) {
            Log.w("SPX", "Cannot identify selected tab", e);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private String makePreview(Uri uri) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(is, null, opt); }
            int sample = 1;
            while (Math.max(opt.outWidth / sample, opt.outHeight / sample) > 1100) sample *= 2;
            opt.inJustDecodeBounds = false; opt.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream is = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(is, null, opt); }
            if (bitmap == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out);
            bitmap.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    private void sendError(String message) {
        try { runJS("window.spxNativeError&&window.spxNativeError(" + JSONObject.quote(message) + ")"); }
        catch (Exception e) { Log.e("SPX", message, e); }
    }
    private void runJS(String script) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(script, null); });
    }
    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.close();
        if (pendingFiles != null) pendingFiles.onReceiveValue(null);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
