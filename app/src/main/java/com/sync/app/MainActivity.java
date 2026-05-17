// MainActivity.java 전체 교체
package com.sync.app;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SYNC";
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private LocalMediaStore localMediaStore;
    private MediaSessionHelper mediaSessionHelper;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(true)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        localMediaStore = new LocalMediaStore(getFilesDir());

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/res/", new WebViewAssetLoader.ResourcesPathHandler(this))
                .build();

        executor.submit(() -> {
            try {
                LocalMediaDownloader.ensureInitialized(getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "yt-dlp pre-init failed (will retry on download)", e);
            }
        });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setDefaultTextEncodingName("UTF-8");
        ws.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36");

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(Color.parseColor("#08080D"));

        mediaSessionHelper = new MediaSessionHelper(this, cmd ->
                runOnUiThread(() -> webView.evaluateJavascript(
                        "window.__mediaCmd&&window.__mediaCmd('" + cmd + "')", null)));

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path != null && path.startsWith("/local/")) {
                        String videoId = path.substring("/local/".length());
                        if (!videoId.isEmpty() && !videoId.contains("..")) {
                            File file = localMediaStore.findMediaFile(videoId);
                            if (file != null) {
                                try { return LocalMediaStore.openWithRange(file, request); }
                                catch (IOException e) { Log.e(TAG, "local media range failed", e); }
                            }
                        }
                    }
                }
                WebResourceResponse response = assetLoader.shouldInterceptRequest(uri);
                if (response == null) return null;
                String mime = response.getMimeType();
                if (mime == null) mime = "text/plain";
                if (mime.contains(";")) mime = mime.substring(0, mime.indexOf(";")).trim();
                String encoding = (mime.startsWith("text/") || mime.contains("javascript")
                        || mime.contains("json") || mime.contains("xml")) ? "UTF-8" : null;
                return new WebResourceResponse(mime, encoding, response.getData());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    }

    public class AndroidBridge {
        @JavascriptInterface
        public boolean hasLocalMedia(String videoId) {
            return localMediaStore.findMediaFile(videoId) != null;
        }

        @JavascriptInterface
        public void postMessage(String json) {
            try {
                JSONObject msg = new JSONObject(json);
                String type = msg.optString("type");
                switch (type) {
                    case "search":        executor.submit(() -> doSearch(msg));        break;
                    case "suggest":       executor.submit(() -> doSuggest(msg));       break;
                    case "fetchLyrics":   executor.submit(() -> doFetchLyrics(msg));   break;
                    case "downloadVideo": executor.submit(() -> doDownloadVideo(msg)); break;
                    case "mediaState":
                        runOnUiThread(() -> { if (mediaSessionHelper != null) mediaSessionHelper.update(msg); });
                        break;
                    case "orientation":
                        String orient = msg.optString("value", "sensor");
                        runOnUiThread(() -> setOrientation(orient));
                        break;
                }
            } catch (JSONException e) { Log.e(TAG, "postMessage parse error", e); }
        }
    }

    private void sendToJs(JSONObject payload) {
        String b64 = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String js = "(function(){" +
                "var b=atob('" + b64 + "');" +
                "var bytes=new Uint8Array(b.length);" +
                "for(var i=0;i<b.length;i++) bytes[i]=b.charCodeAt(i);" +
                "var s=new TextDecoder('utf-8').decode(bytes);" +
                "window.__sync&&window.__sync(s);" +
                "})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void setOrientation(String mode) {
        if ("landscape".equals(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUI();
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            showSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null)
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // ════════════════════════════════════════════════════
    //  SEARCH
    // ════════════════════════════════════════════════════
    private void doSearch(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
            String URL = "https://www.youtube.com/youtubei/v1/search?key=" + KEY + "&prettyPrint=false";
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20240101.00.00");
            client.put("hl", "ko");
            client.put("gl", "KR");
            JSONObject context = new JSONObject();
            context.put("client", client);
            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("query", query);
            body.put("params", "EgIQAQ==");
            Request req = new Request.Builder()
                    .url(URL)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20240101.00.00")
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/")
                    .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) throw new IOException("HTTP " + resp.code());
                JSONArray tracks = parseSearchResults(readUtf8(resp));
                JSONObject result = new JSONObject();
                result.put("type", "searchResult");
                result.put("id", id);
                result.put("success", true);
                result.put("tracks", tracks);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "searchResult"); err.put("id", id);
                err.put("success", false); err.put("error", e.getMessage());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private JSONArray parseSearchResults(String json) throws JSONException {
        JSONArray list = new JSONArray();
        JSONObject doc = new JSONObject(json);
        if (!doc.has("contents")) return list;
        JSONArray sections;
        try {
            sections = doc.getJSONObject("contents")
                    .getJSONObject("twoColumnSearchResultsRenderer")
                    .getJSONObject("primaryContents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
        } catch (JSONException e) { return list; }
        for (int s = 0; s < sections.length() && list.length() < 20; s++) {
            JSONObject sec = sections.getJSONObject(s);
            if (!sec.has("itemSectionRenderer")) continue;
            JSONArray items = sec.getJSONObject("itemSectionRenderer").getJSONArray("contents");
            for (int k = 0; k < items.length() && list.length() < 20; k++) {
                JSONObject item = items.getJSONObject(k);
                if (!item.has("videoRenderer")) continue;
                JSONObject vr = item.getJSONObject("videoRenderer");
                String vid = vr.optString("videoId", "");
                if (vid.isEmpty()) continue;
                String title = extractText(vr, "title");
                String ch = extractText(vr, "ownerText");
                if (ch.isEmpty()) ch = extractText(vr, "shortBylineText");
                String durStr = "";
                try { if (vr.has("lengthText")) durStr = vr.getJSONObject("lengthText").optString("simpleText", ""); }
                catch (JSONException ignored) {}
                int dur = parseDur(durStr);
                if (!isMusicVideo(title, ch, dur)) continue;
                JSONObject t = new JSONObject();
                t.put("id", vid); t.put("title", title); t.put("channel", ch);
                t.put("dur", dur); t.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
                list.put(t);
            }
        }
        return list;
    }

    private String extractText(JSONObject vr, String key) {
        try {
            if (!vr.has(key)) return "";
            JSONObject obj = vr.getJSONObject(key);
            if (obj.has("runs")) return obj.getJSONArray("runs").getJSONObject(0).optString("text", "");
            return obj.optString("simpleText", "");
        } catch (JSONException e) { return ""; }
    }

    private int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private boolean isMusicVideo(String title, String channel, int durSec) {
        String tl = title.toLowerCase(), cl = channel.toLowerCase();
        for (String kw : new String[]{"vevo","topic","music","records","entertainment","sound","audio","official","label","studio"})
            if (cl.contains(kw)) return true;
        for (String kw : new String[]{"official","mv","m/v","music video","audio","lyrics","lyric","visualizer","live","performance","concert","feat","뮤직비디오","음원","공식","노래"})
            if (tl.contains(kw)) return true;
        return durSec >= 60 || durSec == 0;
    }

    private void doSuggest(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&hl=ko";
            Request req = new Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0 Firefox/124.0").build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) throw new IOException("empty");
                String json = readUtf8(resp);
                if (json.startsWith("window."))
                    json = json.replaceFirst("^[^(]+\\(", "").replaceFirst("\\)\\s*$", "");
                JSONArray arr = new JSONArray(json);
                JSONArray sugs = new JSONArray();
                if (arr.length() > 1) {
                    JSONArray inner = arr.getJSONArray(1);
                    for (int i = 0; i < inner.length() && sugs.length() < 8; i++) {
                        Object o = inner.get(i);
                        String sv = (o instanceof JSONArray) ? ((JSONArray) o).optString(0, "") : o.toString();
                        if (!sv.isEmpty()) sugs.put(sv);
                    }
                }
                JSONObject result = new JSONObject();
                result.put("type", "suggestResult"); result.put("id", id);
                result.put("success", true); result.put("suggestions", sugs);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "suggestResult"); err.put("id", id);
                err.put("success", false); err.put("suggestions", new JSONArray());
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    private void doDownloadVideo(JSONObject msg) {
        String videoId = msg.optString("videoId");
        String id = msg.optString("id", "0");
        try {
            if (videoId.isEmpty()) throw new IOException("videoId 없음");
            File existing = localMediaStore.findMediaFile(videoId);
            if (existing != null) {
                JSONObject result = new JSONObject();
                result.put("type", "downloadResult"); result.put("id", id);
                result.put("success", true); result.put("videoId", videoId);
                result.put("fileName", existing.getName()); result.put("size", existing.length());
                sendToJs(result);
                return;
            }
            LocalMediaDownloader.download(getApplicationContext(), localMediaStore, videoId, percent -> {
                try {
                    JSONObject prog = new JSONObject();
                    prog.put("type", "downloadProgress"); prog.put("videoId", videoId); prog.put("percent", percent);
                    sendToJs(prog);
                } catch (JSONException ignored) {}
            });
            File saved = localMediaStore.findMediaFile(videoId);
            if (saved == null) throw new IOException("저장 파일 없음");
            JSONObject result = new JSONObject();
            result.put("type", "downloadResult"); result.put("id", id);
            result.put("success", true); result.put("videoId", videoId);
            result.put("fileName", saved.getName()); result.put("size", saved.length());
            sendToJs(result);
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + videoId, e);
            try {
                JSONObject err = new JSONObject();
                err.put("type", "downloadResult"); err.put("id", id);
                err.put("success", false); err.put("videoId", videoId);
                err.put("error", e.getMessage() != null ? e.getMessage() : "다운로드 실패");
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════
    //  LYRICS — 메인 진입점
    //
    //  전략:
    //  P0  원본 무변형 lrclib /api/get  (rawTitle + rawArtist, duration 유무 모두)
    //  P1  정제 제목 lrclib /api/get    (cleanTitle + cleanArtist, 다중 콤보)
    //  P2  lrclib /api/search           (다중 쿼리 변형, synced 우선 채점)
    //  P3  NetEase                      (다중 쿼리, 전역 후보 취합)
    //  P4  lrclib /api/search 폴백      (duration 무시, 제목 유사도 기반)
    // ════════════════════════════════════════════════════
    private void doFetchLyrics(JSONObject msg) {
        String rawTitle  = msg.optString("title", "").trim();
        String rawArtist = msg.optString("channel", "").trim();
        double dur       = msg.optDouble("duration", 0);
        String id        = msg.optString("id", "0");

        // ── 전처리 ──
        String ct       = cleanTitle(rawTitle);
        String ca       = cleanArtist(rawArtist);
        String stripped = stripBrackets(ct);
        String rawStrip = stripBrackets(rawTitle);
        String feat     = extractFeat(rawTitle);
        String firstSeg = extractFirstSegment(ct);
        String refTitle  = ct.isEmpty()  ? rawTitle  : ct;
        String refArtist = ca.isEmpty()  ? rawArtist : ca;

        Log.d(TAG, "[Lyrics] raw='" + rawTitle + "' ct='" + ct
                + "' ca='" + ca + "' stripped='" + stripped
                + "' feat='" + feat + "' dur=" + dur);

        JSONArray lines = null;

        // ── P0: 원본 무변형 lrclib /api/get (최우선) ──
        lines = tryLrclibGetRaw(rawTitle, rawArtist, dur, refTitle, refArtist);
        if (isGood(lines)) { send(lines, id); return; }

        // ── P1: 정제 제목 lrclib /api/get ──
        lines = tryLrclibGetCleaned(rawTitle, rawArtist, ct, ca, stripped, rawStrip, firstSeg, feat, dur, refTitle, refArtist);
        if (isGood(lines)) { send(lines, id); return; }

        // ── P2: lrclib /api/search 다중 쿼리 ──
        List<String> variants = buildQueryVariants(rawTitle, rawArtist, ct, ca, stripped, rawStrip, feat, firstSeg);
        lines = tryLrclibSearch(variants, refTitle, refArtist, dur, false);
        if (isGood(lines)) { send(lines, id); return; }

        // ── P3: NetEase ──
        lines = tryNetEase(variants, refTitle, refArtist, dur);
        if (isGood(lines)) { send(lines, id); return; }

        // ── P4: lrclib /api/search 폴백 (duration 무시) ──
        lines = tryLrclibSearch(variants, refTitle, refArtist, 0, true);

        send(lines, id);
    }

    private void send(JSONArray lines, String id) {
        try {
            JSONObject result = new JSONObject();
            result.put("type", "lyricsResult");
            result.put("id", id);
            if (isGood(lines)) {
                result.put("success", true);
                result.put("lines", lines);
            } else {
                result.put("success", false);
                result.put("lines", new JSONArray());
            }
            sendToJs(result);
        } catch (JSONException ignored) {}
    }

    private boolean isGood(JSONArray arr) {
        return arr != null && arr.length() >= 3;
    }

    // ════════════════════════════════════════════════════
    //  P0: 원본 무변형 lrclib /api/get
    //  rawTitle + rawArtist → rawTitle만 → duration 유무 각각
    // ════════════════════════════════════════════════════
    private JSONArray tryLrclibGetRaw(
            String rawTitle, String rawArtist, double dur,
            String refTitle, String refArtist) {

        // 시도 순서: (원본제목+원본아티스트), (원본제목만)
        // 각각 duration 포함 → duration 없이
        String[][] combos = {
            {rawTitle, rawArtist},
            {rawTitle, ""},
        };

        for (String[] combo : combos) {
            String t = combo[0].trim(), a = combo[1].trim();
            if (t.isEmpty()) continue;

            // duration 포함 시도
            JSONObject item = lrclibGet(t, a, dur);
            // 실패 시 duration 없이 재시도
            if (item == null && dur > 0) item = lrclibGet(t, a, 0);
            if (item == null) continue;

            JSONArray parsed = extractLrcFromItem(item, refTitle, refArtist, dur, 20.0);
            if (isGood(parsed)) {
                Log.d(TAG, "[P0-raw] HIT raw='" + t + "' artist='" + a + "'");
                return parsed;
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════
    //  P1: 정제 제목 lrclib /api/get — 다중 콤보
    // ════════════════════════════════════════════════════
    private JSONArray tryLrclibGetCleaned(
            String rawTitle, String rawArtist,
            String ct, String ca,
            String stripped, String rawStrip,
            String firstSeg, String feat,
            double dur, String refTitle, String refArtist) {

        // 시도 콤보 (중복 방지, 우선순위순)
        LinkedHashSet<String[]> combos = new LinkedHashSet<String[]>() {{
            // 원본 아티스트 + 정제 제목 조합
            add(new String[]{ct, rawArtist});
            add(new String[]{ct, ca});
            add(new String[]{ct, ""});
            // 괄호 제거 + 정제 아티스트
            if (!rawStrip.equals(rawTitle)) {
                add(new String[]{rawStrip, rawArtist});
                add(new String[]{rawStrip, ca});
                add(new String[]{rawStrip, ""});
            }
            // stripped
            if (!stripped.equals(ct) && !stripped.isEmpty()) {
                add(new String[]{stripped, ca});
                add(new String[]{stripped, rawArtist});
                add(new String[]{stripped, ""});
            }
            // firstSeg
            if (!firstSeg.equals(ct) && firstSeg.length() > 1) {
                add(new String[]{firstSeg, ca});
                add(new String[]{firstSeg, rawArtist});
                add(new String[]{firstSeg, ""});
            }
            // feat 아티스트
            if (!feat.isEmpty()) {
                add(new String[]{stripped.isEmpty() ? ct : stripped, feat});
                add(new String[]{ct, feat});
            }
        }};

        double bestScore = Double.NEGATIVE_INFINITY;
        JSONArray bestLines = null;

        for (String[] combo : combos) {
            String t = combo[0].trim(), a = combo[1].trim();
            if (t.isEmpty()) continue;

            JSONObject item = lrclibGet(t, a, dur);
            if (item == null && dur > 0) item = lrclibGet(t, a, 0);
            if (item == null) continue;

            String lrcText = item.optString("syncedLyrics", "");
            boolean synced = !lrcText.isEmpty();
            if (!synced) lrcText = item.optString("plainLyrics", "");
            if (lrcText == null || lrcText.isEmpty()) continue;

            double candidateDur = getLrcLastTimestamp(lrcText);
            if (candidateDur <= 0) candidateDur = item.optDouble("duration", 0);

            double score = score(item.optString("trackName",""), item.optString("artistName",""),
                    candidateDur, dur, synced, refTitle, refArtist);

            Log.d(TAG, "[P1] t='" + t + "' a='" + a + "' score=" + score + " synced=" + synced);

            if (score > bestScore) {
                bestScore = score;
                try { bestLines = parseLrc(lrcText); } catch (JSONException ignored) {}
            }
            // 고품질 즉시 반환
            if (score >= 60 && synced) break;
        }

        double threshold = dur > 0 ? 10.0 : 0.0;
        return (bestScore >= threshold && isGood(bestLines)) ? bestLines : null;
    }

    /** lrclib /api/get 단일 호출 */
    private JSONObject lrclibGet(String trackName, String artistName, double dur) {
        try {
            StringBuilder sb = new StringBuilder("https://lrclib.net/api/get?track_name=");
            sb.append(java.net.URLEncoder.encode(trackName, "UTF-8"));
            if (!artistName.isEmpty())
                sb.append("&artist_name=").append(java.net.URLEncoder.encode(artistName, "UTF-8"));
            if (dur > 0)
                sb.append("&duration=").append(Math.round(dur));
            Request req = new Request.Builder()
                    .url(sb.toString())
                    .addHeader("User-Agent", "SYNCApp/1.0")
                    .addHeader("Accept", "application/json")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                Object parsed = new org.json.JSONTokener(readUtf8(resp)).nextValue();
                return (parsed instanceof JSONObject) ? (JSONObject) parsed : null;
            }
        } catch (Exception e) { return null; }
    }

    /** item에서 가사 추출 + 최소 점수 필터 */
    private JSONArray extractLrcFromItem(JSONObject item, String refTitle, String refArtist,
                                          double ytDur, double minScore) {
        String lrcText = item.optString("syncedLyrics", "");
        boolean synced = !lrcText.isEmpty();
        if (!synced) lrcText = item.optString("plainLyrics", "");
        if (lrcText == null || lrcText.isEmpty()) return null;
        double candidateDur = getLrcLastTimestamp(lrcText);
        if (candidateDur <= 0) candidateDur = item.optDouble("duration", 0);
        double s = score(item.optString("trackName",""), item.optString("artistName",""),
                candidateDur, ytDur, synced, refTitle, refArtist);
        if (s < minScore) return null;
        try { return parseLrc(lrcText); } catch (JSONException e) { return null; }
    }

    // ════════════════════════════════════════════════════
    //  쿼리 변형 목록 구성
    // ════════════════════════════════════════════════════
    private List<String> buildQueryVariants(
            String rawTitle, String rawArtist,
            String ct, String ca,
            String stripped, String rawStrip,
            String feat, String firstSeg) {

        LinkedHashSet<String> set = new LinkedHashSet<>();

        // ① 원본 무변형 최우선 (P0에서 실패했어도 search에서 재시도)
        if (!rawArtist.isEmpty()) set.add(rawTitle + " " + rawArtist);
        set.add(rawTitle);

        // ② 원본 괄호 제거
        if (!rawStrip.equals(rawTitle)) {
            if (!rawArtist.isEmpty()) set.add(rawStrip + " " + rawArtist);
            if (!ca.isEmpty())        set.add(rawStrip + " " + ca);
            set.add(rawStrip);
        }

        // ③ 정제 제목 + 아티스트
        if (!ca.isEmpty()) set.add(ct + " " + ca);
        if (!rawArtist.isEmpty() && !rawArtist.equals(ca)) set.add(ct + " " + rawArtist);
        set.add(ct);

        // ④ 완전 괄호 제거
        if (!stripped.equals(ct) && !stripped.isEmpty()) {
            if (!ca.isEmpty())        set.add(stripped + " " + ca);
            if (!rawArtist.isEmpty()) set.add(stripped + " " + rawArtist);
            set.add(stripped);
        }

        // ⑤ 첫 세그먼트
        if (!firstSeg.equals(ct) && firstSeg.length() > 1) {
            if (!ca.isEmpty())        set.add(firstSeg + " " + ca);
            if (!rawArtist.isEmpty()) set.add(firstSeg + " " + rawArtist);
            set.add(firstSeg);
        }

        // ⑥ feat 아티스트
        if (!feat.isEmpty()) {
            set.add((stripped.isEmpty() ? ct : stripped) + " " + feat);
            set.add(ct + " " + feat);
            if (!ca.isEmpty()) set.add(feat + " " + ca);
        }

        // ⑦ 아티스트 역순
        if (!ca.isEmpty()) {
            set.add(ca + " " + ct);
            if (!stripped.equals(ct)) set.add(ca + " " + stripped);
        }

        // ⑧ 특수문자 정규화
        String norm = ct.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s{2,}", " ").trim();
        if (!norm.isEmpty() && !norm.equals(ct)) {
            if (!ca.isEmpty()) set.add(norm + " " + ca);
            set.add(norm);
        }

        // ⑨ 첫 3단어
        String[] words = ct.trim().split("\\s+");
        if (words.length > 3) {
            String w3 = words[0] + " " + words[1] + " " + words[2];
            if (!ca.isEmpty()) set.add(w3 + " " + ca);
            set.add(w3);
        }

        // ⑩ 첫 단어 + 아티스트
        String fw = firstWord(ct);
        if (fw.length() > 1 && !fw.equals(ct)) {
            if (!ca.isEmpty()) set.add(fw + " " + ca);
        }

        return new ArrayList<>(set);
    }

    // ════════════════════════════════════════════════════
    //  P2/P4: lrclib /api/search
    //  fallback=true 이면 duration 무시
    // ════════════════════════════════════════════════════
    private JSONArray tryLrclibSearch(
            List<String> variants, String refTitle, String refArtist,
            double ytDur, boolean fallback) {

        List<String>  lrcs   = new ArrayList<>();
        List<Double>  scores = new ArrayList<>();
        List<Boolean> synced = new ArrayList<>();

        for (int qi = 0; qi < variants.size(); qi++) {
            String q = variants.get(qi);
            JSONArray results = lrclibSearch(q);
            if (results == null || results.length() == 0) continue;

            boolean hasSynced = false;

            for (int i = 0; i < results.length(); i++) {
                try {
                    JSONObject item = results.getJSONObject(i);
                    String lrc = item.optString("syncedLyrics", "");
                    boolean isSynced = !lrc.isEmpty();
                    if (!isSynced) lrc = item.optString("plainLyrics", "");
                    if (lrc == null || lrc.isEmpty()) continue;
                    if (isSynced) hasSynced = true;

                    double candidateDur = getLrcLastTimestamp(lrc);
                    if (candidateDur <= 0) candidateDur = item.optDouble("duration", 0);

                    double effectiveDur = fallback ? 0 : ytDur;
                    double s = score(
                            item.optString("trackName",""),
                            item.optString("artistName",""),
                            candidateDur, effectiveDur, isSynced, refTitle, refArtist);
                    s -= qi * 0.3; // 뒤 쿼리 소폭 패널티

                    Log.d(TAG, "[lrclib-search" + (fallback?"(fb)":"") + "] q='" + q
                            + "' t='" + item.optString("trackName","")
                            + "' s=" + s + " synced=" + isSynced);

                    lrcs.add(lrc);
                    scores.add(s);
                    synced.add(isSynced);
                } catch (JSONException ignored) {}
            }

            // synced 있고 점수 충분 → 조기 종료
            double curBest = scores.isEmpty() ? Double.NEGATIVE_INFINITY
                    : scores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (hasSynced && curBest >= (fallback ? 25 : 40)) break;
        }

        if (lrcs.isEmpty()) return null;

        Integer[] idx = sortCandidates(scores, synced);
        double topScore = scores.get(idx[0]);
        double threshold = fallback ? 5.0 : (ytDur > 0 ? 5.0 : -5.0);
        if (topScore < threshold) return null;

        try { return parseLrc(lrcs.get(idx[0])); }
        catch (JSONException e) { return null; }
    }

    private JSONArray lrclibSearch(String query) {
        try {
            String url = "https://lrclib.net/api/search?q=" + java.net.URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "SYNCApp/1.0")
                    .addHeader("Accept", "application/json")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return new JSONArray();
                Object parsed = new org.json.JSONTokener(readUtf8(resp)).nextValue();
                return parsed instanceof JSONArray ? (JSONArray) parsed : new JSONArray();
            }
        } catch (Exception e) { return new JSONArray(); }
    }

    // ════════════════════════════════════════════════════
    //  P3: NetEase
    // ════════════════════════════════════════════════════
    private JSONArray tryNetEase(List<String> variants, String refTitle, String refArtist, double ytDur) {
        List<long[]> allIds    = new ArrayList<>();
        List<Double> allScores = new ArrayList<>();

        for (int qi = 0; qi < Math.min(variants.size(), 10); qi++) {
            List<long[]> ids    = new ArrayList<>();
            List<Double> scs    = new ArrayList<>();
            netEaseSearch(variants.get(qi), refTitle, refArtist, ytDur, ids, scs);

            for (int i = 0; i < ids.size(); i++) {
                long sid = ids.get(i)[0];
                double s = scs.get(i) - qi * 0.3;
                int ex = indexOfId(allIds, sid);
                if (ex >= 0) { if (s > allScores.get(ex)) allScores.set(ex, s); }
                else { allIds.add(ids.get(i)); allScores.add(s); }
            }
            double max = allScores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (max >= 65) break;
        }

        if (allIds.isEmpty()) return null;

        Integer[] idx = new Integer[allIds.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        final List<Double> fs = allScores;
        Arrays.sort(idx, (a, b) -> Double.compare(fs.get(b), fs.get(a)));

        for (int i = 0; i < Math.min(5, idx.length); i++) {
            double s = fs.get(idx[i]);
            if (s < (ytDur > 0 ? 12 : 3)) break;
            Log.d(TAG, "[NetEase] fetch id=" + allIds.get(idx[i])[0] + " score=" + s);
            JSONArray lines = netEaseFetchLrc(allIds.get(idx[i])[0]);
            if (isGood(lines)) return lines;
        }
        return null;
    }

    private void netEaseSearch(String query, String refTitle, String refArtist,
            double ytDur, List<long[]> ids, List<Double> scores) {
        try {
            String url = "https://music.163.com/api/search/get?s="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&type=1&limit=15";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return;
                JSONObject doc = new JSONObject(readUtf8(resp));
                if (!doc.has("result")) return;
                JSONArray songs = doc.getJSONObject("result").optJSONArray("songs");
                if (songs == null) return;
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    long   sid      = song.optLong("id");
                    String st       = song.optString("name", "");
                    double duration = song.optDouble("duration", 0) / 1000.0;
                    StringBuilder ab = new StringBuilder();
                    JSONArray art = song.optJSONArray("artists");
                    if (art != null)
                        for (int j = 0; j < art.length(); j++)
                            ab.append(art.getJSONObject(j).optString("name","")).append(" ");
                    double s = score(st, ab.toString().trim(), duration, ytDur, false, refTitle, refArtist);
                    ids.add(new long[]{sid});
                    scores.add(s);
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONArray netEaseFetchLrc(long songId) {
        try {
            String url = "https://music.163.com/api/song/lyric?id=" + songId + "&lv=1&kv=1&tv=-1";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return null;
                JSONObject doc = new JSONObject(readUtf8(resp));
                String lrc = null;
                if (doc.has("klyric")) lrc = doc.getJSONObject("klyric").optString("lyric", null);
                if (lrc == null || lrc.isEmpty())
                    if (doc.has("lrc")) lrc = doc.getJSONObject("lrc").optString("lyric", null);
                return (lrc != null && !lrc.isEmpty()) ? parseLrc(lrc) : null;
            }
        } catch (Exception e) { return null; }
    }

    // ════════════════════════════════════════════════════
    //  통합 채점 함수
    //  duration 매칭 +60, 제목 유사도 +45, 아티스트 +25,
    //  synced +15, 동시일치 보너스 +10, 제목 최저 패널티 -25
    // ════════════════════════════════════════════════════
    private double score(
            String candidateTitle, String candidateArtist,
            double candidateDur, double ytDur,
            boolean isSynced, String refTitle, String refArtist) {

        double s = 0;

        // 1. duration 매칭 (ytDur=0이면 스킵)
        if (ytDur > 0 && candidateDur > 0) {
            double diff = Math.abs(candidateDur - ytDur);
            if      (diff <= 1)  s += 60;
            else if (diff <= 3)  s += 50;
            else if (diff <= 5)  s += 40;
            else if (diff <= 10) s += 25;
            else if (diff <= 20) s += 10;
            else if (diff <= 40) s +=  2;
            else                 s -= 30;
        }

        // 2. 제목 유사도 (비중 높임)
        double ts = titleSim(refTitle, candidateTitle);
        s += ts * 45;

        // 3. 아티스트 유사도
        double as = 0;
        if (!refArtist.isEmpty() && !candidateArtist.isEmpty())
            as = titleSim(refArtist, candidateArtist);
        s += as * 25;

        // 4. synced 보너스
        if (isSynced) s += 15;

        // 5. 제목 최저선 패널티
        if (!refTitle.isEmpty() && !candidateTitle.isEmpty() && ts < 0.12)
            s -= 25;

        // 6. 동시 일치 보너스
        if (ts >= 0.55 && as >= 0.45) s += 10;

        return s;
    }

    // ════════════════════════════════════════════════════
    //  후보 정렬 — synced 우선, 점수 내림차순
    // ════════════════════════════════════════════════════
    private Integer[] sortCandidates(List<Double> scores, List<Boolean> syncedList) {
        Integer[] idx = new Integer[scores.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> {
            if (syncedList.get(b) && !syncedList.get(a)) return 1;
            if (syncedList.get(a) && !syncedList.get(b)) return -1;
            return Double.compare(scores.get(b), scores.get(a));
        });
        return idx;
    }

    // ════════════════════════════════════════════════════
    //  LRC 파서
    // ════════════════════════════════════════════════════
    private static final Pattern CREDIT_RX = Pattern.compile(
            "^\\s*(?:作词|作曲|编曲|混音|制作人|出品|录音|母带" +
            "|OP|SP|厂牌|发行|监制|制作|ISRC|专辑|歌手)\\s*[：:].{0,80}$");

    private static final Pattern TS_RX = Pattern.compile(
            "\\[(\\d+):(\\d{2})[.:](\\d{2,3})\\]");

    private JSONArray parseLrc(String lrc) throws JSONException {
        List<double[]> times = new ArrayList<>();
        List<String>   texts = new ArrayList<>();
        for (String line : lrc.split("\n")) {
            String trimmed  = line.trim();
            String textPart = trimmed.replaceAll("\\[\\d+:\\d{2}[.:]\\d{2,3}\\]", "").trim();
            if (textPart.isEmpty() || CREDIT_RX.matcher(textPart).matches()) continue;
            Matcher scanner = TS_RX.matcher(trimmed);
            while (scanner.find()) {
                String msStr = scanner.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0 : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(scanner.group(1)) * 60.0
                         + Integer.parseInt(scanner.group(2)) + ms;
                times.add(new double[]{t});
                texts.add(textPart);
            }
        }
        Integer[] idx = new Integer[times.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(times.get(a)[0], times.get(b)[0]));
        JSONArray result = new JSONArray();
        for (int i = 0; i < idx.length; i++) {
            int    ci    = idx[i];
            double start = times.get(ci)[0];
            double end   = (i + 1 < idx.length) ? times.get(idx[i + 1])[0] : start + 5.0;
            if (end - start < 0.1) end = start + 0.5;
            JSONObject obj = new JSONObject();
            obj.put("start", start); obj.put("end", end); obj.put("text", texts.get(ci));
            result.put(obj);
        }
        return result;
    }

    // ════════════════════════════════════════════════════
    //  헬퍼
    // ════════════════════════════════════════════════════

    private double titleSim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        a = normalize(a); b = normalize(b);
        if (a.equals(b)) return 1.0;
        if (b.contains(a) || a.contains(b)) return 0.88;
        String[] wa = a.split("[^\\p{L}\\p{N}]+");
        String[] wb = b.split("[^\\p{L}\\p{N}]+");
        Set<String> sa = new HashSet<>(), sb2 = new HashSet<>();
        for (String w : wa) if (!w.isEmpty()) sa.add(w);
        for (String w : wb) if (!w.isEmpty()) sb2.add(w);
        if (sa.isEmpty() || sb2.isEmpty()) return 0;
        long inter = sa.stream().filter(sb2::contains).count();
        if (inter == 0) return 0;
        double jaccard = (double) inter / (sa.size() + sb2.size() - inter);
        double dice    = (2.0 * inter) / (sa.size() + sb2.size());
        return Math.max(sa.size(), sb2.size()) <= 2 ? jaccard : (jaccard + dice) / 2.0;
    }

    private String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[·•‧]", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stripBrackets(String t) {
        return t.replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private String extractFeat(String raw) {
        Matcher m = Pattern.compile("(?i)\\(\\s*(?:feat\\.?|ft\\.?)\\s+([^)]+)\\)").matcher(raw);
        if (m.find()) return m.group(1).trim();
        Matcher m2 = Pattern.compile("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+([^(\\[]+)").matcher(raw);
        if (m2.find()) return m2.group(1).trim();
        return "";
    }

    private String extractFirstSegment(String t) {
        int i = t.indexOf(" - ");
        if (i > 0) return t.substring(0, i).trim();
        i = t.indexOf(" | ");
        if (i > 0) return t.substring(0, i).trim();
        return t;
    }

    private String firstWord(String t) {
        if (t == null || t.isEmpty()) return "";
        String[] parts = t.trim().split("\\s+");
        return parts.length > 0 ? parts[0] : t;
    }

    private double getLrcLastTimestamp(String lrc) {
        double last = 0;
        for (String line : lrc.split("\n")) {
            Matcher m = TS_RX.matcher(line.trim());
            while (m.find()) {
                String msStr = m.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0 : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(m.group(1)) * 60.0 + Integer.parseInt(m.group(2)) + ms;
                if (t > last) last = t;
            }
        }
        return last;
    }

    private int indexOfId(List<long[]> list, long id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i)[0] == id) return i;
        return -1;
    }

    /** 제목 정제 — 영문 태그 제거, 한글 보존 */
    private String cleanTitle(String t) {
        final String TAG_INNER =
            "(?:official\\s*(?:music\\s*)?(?:video|audio|mv|lyric(?:s)?|visualizer)?" +
            "|m/?v|music\\s*video|audio(?:\\s*only)?|lyrics?(?:\\s*(?:video|ver(?:sion)?))?|visualizer" +
            "|live(?:\\s+(?:performance|version|session|ver\\.?))?|performance(?:\\s+video)?" +
            "|(?:hd|4k|1080p|720p|fhd)|remaster(?:ed)?(?:\\s+version)?|re-?upload" +
            "|eng(?:lish)?\\s*(?:ver\\.?|version|sub(?:title)?s?)?|kor(?:ean)?\\s*(?:ver\\.?|version)?" +
            "|jp(?:n)?\\s*(?:ver\\.?|version)?|prod(?:uced)?(?:\\s+by)?\\s+[^)\\]|]+?" +
            "|color\\s*coded|han\\s*rom\\s*eng|rom\\s*eng|han\\s*eng" +
            "|short\\s*ver(?:sion)?\\.?|full\\s*ver(?:sion)?\\.?|album\\s*ver(?:sion)?\\.?" +
            "|radio\\s*edit|explicit|clean\\s*ver(?:sion)?\\.?" +
            "|inst(?:rumental)?\\.?|acoustic(?:\\s+ver(?:sion)?)?\\.?" +
            "|mv\\s*ver(?:sion)?\\.?|single|title\\s*track)";

        t = t.replaceAll("(?i)\\(\\s*" + TAG_INNER + "[^)]*\\)", "").trim();
        t = t.replaceAll("(?i)\\[\\s*" + TAG_INNER + "[^\\]]*\\]", "").trim();
        t = t.replaceAll("(?i)\\s*[-|]\\s*(?:" + TAG_INNER + ")\\s*$", "").trim();
        t = t.replaceAll("(?i)\\s*\\(?\\s*(?:feat\\.?|ft\\.?|featuring)\\s+.+?(?:\\)|$)", "").trim();
        t = t.replaceAll("(?i)\\s*\\(?\\s*(?:prod(?:uced)?(?:\\.)?\\s*(?:by)?)\\s+[^)]+?(?:\\)|$)", "").trim();
        t = t.replaceAll("[\\u2013\\u2014]+", "-").trim();
        return t.replaceAll("\\s{2,}", " ").trim();
    }

    private String cleanArtist(String c) {
        c = c.replaceAll("(?i)\\s*[-–]\\s*Topic\\s*$", "").trim();
        c = c.replaceAll("(?i)VEVO$", "").trim();
        c = c.replaceAll("(?i)\\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\\s*$", "").trim();
        return c.replaceAll("\\s{2,}", " ").trim();
    }

    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (mediaSessionHelper != null) { mediaSessionHelper.release(); mediaSessionHelper = null; }
        executor.shutdown();
        webView.destroy();
    }

    private String readUtf8(Response resp) throws IOException {
        byte[] bytes = resp.body().bytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.__onAndroidBack&&window.__onAndroidBack()", null);
    }
}
