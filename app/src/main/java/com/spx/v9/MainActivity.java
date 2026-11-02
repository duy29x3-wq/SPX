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
                    obj.put("preview", makePreview(uri));
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
