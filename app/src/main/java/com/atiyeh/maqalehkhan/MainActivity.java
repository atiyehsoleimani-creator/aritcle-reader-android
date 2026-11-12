package com.atiyeh.maqalehkhan;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private volatile int session = 0;
    private volatile boolean usingNetworkVoice = false;
    private volatile Voice localVoice = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xFF0F172A);
        getWindow().setNavigationBarColor(0xFF020617);

        // Prefer Google's speech engine (most natural voices); Android falls back to the default engine if absent.
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                pickVoices();
                ttsReady = true;
            }
        }, "com.google.android.tts");
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) {
                if (!isCurrent(id)) return;
                runOnUiThread(() -> webView.evaluateJavascript("window.__ttsDone && window.__ttsDone()", null));
            }
            @Override public void onError(String id) {
                if (!isCurrent(id)) return;
                if (usingNetworkVoice && localVoice != null) {
                    // Online voice failed (no connection): switch to the best offline voice and replay.
                    session++;
                    usingNetworkVoice = false;
                    try { tts.setVoice(localVoice); } catch (Exception ignored) { }
                    runOnUiThread(() -> webView.evaluateJavascript("window.__ttsRetry && window.__ttsRetry()", null));
                } else {
                    runOnUiThread(() -> webView.evaluateJavascript("window.__ttsDone && window.__ttsDone()", null));
                }
            }
        });

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF020617);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new TtsBridge(), "AndroidTTS");
        setContentView(webView);

        String html = readAsset("index.html");
        String bridge = readAsset("tts-bridge.js");
        html = html.replaceFirst("<head>", "<head>\n<script>\n" + java.util.regex.Matcher.quoteReplacement(bridge) + "\n</script>");
        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "<html><body>Error loading " + name + "</body></html>";
        }
    }

    private boolean isCurrent(String id) {
        return id != null && id.startsWith("s" + session + "_");
    }

    private static int score(Voice v) {
        int sc = v.getQuality() * 10;
        if ("US".equals(v.getLocale().getCountry())) sc += 50;
        return sc;
    }

    /** Chooses the highest-quality English voice the engine offers (online neural voice if available). */
    private void pickVoices() {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            Voice bestLocal = null, bestNet = null;
            for (Voice v : voices) {
                if (v.getLocale() == null || !"en".equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (v.isNetworkConnectionRequired()) {
                    if (bestNet == null || score(v) > score(bestNet)) bestNet = v;
                } else {
                    if (bestLocal == null || score(v) > score(bestLocal)) bestLocal = v;
                }
            }
            localVoice = bestLocal;
            if (bestNet != null && (bestLocal == null || score(bestNet) >= score(bestLocal))) {
                tts.setVoice(bestNet);
                usingNetworkVoice = true;
            } else if (bestLocal != null) {
                tts.setVoice(bestLocal);
            }
        } catch (Exception ignored) { }
    }

    private class TtsBridge {
        @JavascriptInterface
        public void speakAll(String json, float rate, String lang) {
            if (!ttsReady) return;
            try {
                JSONArray arr = new JSONArray(json);
                int my = ++session;
                tts.setSpeechRate(rate);
                for (int i = 0; i < arr.length(); i++) {
                    tts.speak(arr.getString(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                            null, "s" + my + "_" + i);
                }
            } catch (Exception ignored) { }
        }
        @JavascriptInterface
        public void stop() { session++; if (tts != null) tts.stop(); }
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
            "(function(){var d=document.getElementById('article-detail');" +
            "if(d&&!d.classList.contains('hidden')){showList();return 'list';}return 'exit';})()",
            v -> { if (v != null && v.contains("exit")) MainActivity.super.onBackPressed(); });
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.evaluateJavascript("window.stopAudio && stopAudio()", null);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        webView.destroy();
        super.onDestroy();
    }
}
