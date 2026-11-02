package com.spx.v9;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.view.WindowManager;

public class MainActivity extends Activity {
  private WebView web;
  private ValueCallback<Uri[]> files;
  private static final int PICK = 101;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setFlags(1024,1024);
    web = new WebView(this);
    setContentView(web);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(true);
    s.setAllowContentAccess(true);
    web.setWebViewClient(new WebViewClient());
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
        if (files != null) files.onReceiveValue(null);
        files = callback;
        try { startActivityForResult(params.createIntent(), PICK); return true; }
        catch (Exception e) { files = null; callback.onReceiveValue(null); return false; }
      }
    });
    web.loadUrl("file:///android_asset/index.html");
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request,result,data);
    if(request==PICK && files!=null) {
      files.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));
      files=null;
    }
  }
  @Override public void onBackPressed() {
    if(web.canGoBack()) web.goBack(); else super.onBackPressed();
  }
}
