package com.spx.v9;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
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
        if (total == 0) { runJS("window.spxNativeCancelled&&window.spxNativeCancelled()"); return; }
        runJS("window.spxNativeStart&&window.spxNativeStart(" + total + ")");
        processNext();
    }

    private void processNext() {
        if (queue.isEmpty()) { runJS("window.spxNativeDone&&window.spxNativeDone()"); return; }
        Uri uri = queue.remove(0);
        try {
            InputImage image = InputImage.fromFilePath(this, uri);
            recognizer.process(image).addOnSuccessListener(result -> {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("text", result.getText());
                    obj.put("width", image.getWidth());
                    obj.put("height", image.getHeight());
                    JSONArray lines = new JSONArray();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            Rect r = line.getBoundingBox();
                            if (r == null) continue;
                            JSONObject item = new JSONObject();
                            item.put("text", line.getText());
                            item.put("x", r.left); item.put("y", r.top);
                            item.put("w", r.width()); item.put("h", r.height());
                            lines.put(item);
                        }
                    }
                    obj.put("lines", lines);
                    // Preview is only for the user's manual verification, not OCR.
                    String preview = makePreview(uri);
                     obj.put("preview", preview);
                     // Detect active tab from its orange underline in the original screenshot.
                     obj.put("activeType", detectActiveTab(preview));
                    done++;
                    runJS("window.spxNativeResult&&window.spxNativeResult(" + obj.toString() + "," + done + "," + total + ")");
                } catch (Exception e) { sendError("Lỗi chuyển kết quả: " + e.getMessage()); }
                processNext();
            }).addOnFailureListener(e -> {
                sendError("Không đọc được ảnh: " + e.getMessage());
                done++;
                processNext();
            });
        } catch (Exception e) { sendError("Ảnh không hợp lệ: " + e.getMessage()); done++; processNext(); }
    }

    // Read the orange underline below the three SPX tabs. Return null if ambiguous.
    private String detectActiveTab(String preview) {
        try {
            if (preview == null || preview.isEmpty()) return null;
            int comma = preview.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(preview.substring(comma + 1), Base64.DEFAULT);
            Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (original == null) return null;
            int w = 420;
            int h = Math.max(1, Math.round(original.getHeight() * (w / (float)original.getWidth())));
            Bitmap b = Bitmap.createScaledBitmap(original, w, h, true);
            if (b != original) original.recycle();
            int[] pixels = new int[w * h];
            b.getPixels(pixels, 0, w, 0, 0, w, h);
            b.recycle();
            double[] scores = new double[3];
            int minY = Math.round(h * .11f), maxY = Math.min(h - 1, Math.round(h * .31f));
            for (int y = minY; y <= maxY; y++) {
                int start = -1;
                for (int x = 12; x < w - 11; x++) {
                    int c = pixels[y * w + x];
                    int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                    boolean orange = r > 165 && r > g * 1.35 && g > bl * 1.15 && g < 175 && bl < 145;
                    if (orange && start < 0) start = x;
                    if ((!orange || x == w - 12) && start >= 0) {
                        int len = x - start;
                        if (len >= 13 && len <= 85) {
                            float mid = start + len / 2f;
                            int col = mid < w * .31f ? 0 : mid < w * .53f ? 1 : mid < w * .78f ? 2 : -1;
                            if (col >= 0) scores[col] += len;
                        }
                        start = -1;
                    }
                }
            }
            int best = 0;
            for (int i = 1; i < 3; i++) if (scores[i] > scores[best]) best = i;
            double other = Math.max(scores[(best + 1) % 3], scores[(best + 2) % 3]);
            if (scores[best] < 26 || (other > 0 && scores[best] <= other * 1.65)) return null;
            return new String[]{"giao", "lay", "hoan"}[best];
        } catch (Exception e) { Log.w("SPX", "Cannot identify selected tab", e); return null; }
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
