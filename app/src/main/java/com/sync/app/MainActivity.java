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
                .addPathHandler("/assets/",
                        new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/res/",
                        new WebViewAssetLoader.ResourcesPathHandler(this))
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
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path != null && path.startsWith("/local/")) {
                        String videoId = path.substring("/local/".length());
                        if (!videoId.isEmpty() && !videoId.contains("..")) {
                            File file = localMediaStore.findMediaFile(videoId);
                            if (file != null) {
                                try {
                                    return LocalMediaStore.openWithRange(file, request);
                                } catch (IOException e) {
                                    Log.e(TAG, "local media range failed", e);
                                }
                            }
                        }
                    }
                }

                WebResourceResponse response =
                        assetLoader.shouldInterceptRequest(uri);
                if (response == null) return null;

                String mime = response.getMimeType();
                if (mime == null) mime = "text/plain";
                if (mime.contains(";")) mime = mime.substring(0, mime.indexOf(";")).trim();

                String encoding = (mime.startsWith("text/") || mime.contains("javascript")
                        || mime.contains("json") || mime.contains("xml"))
                        ? "UTF-8" : null;
                return new WebResourceResponse(mime, encoding, response.getData());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return false;
            }
        });

        webView.loadUrl(
                "https://appassets.androidplatform.net/assets/www/index.html");
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
                    case "search":
                        executor.submit(() -> doSearch(msg)); break;
                    case "suggest":
                        executor.submit(() -> doSuggest(msg)); break;
                    case "fetchLyrics":
                        executor.submit(() -> doFetchLyrics(msg)); break;
                    case "downloadVideo":
                        executor.submit(() -> doDownloadVideo(msg)); break;
                    case "mediaState":
                        runOnUiThread(() -> {
                            if (mediaSessionHelper != null) mediaSessionHelper.update(msg);
                        });
                        break;
                    case "orientation":
                        String orient = msg.optString("value", "sensor");
                        runOnUiThread(() -> setOrientation(orient)); break;
                    default: break;
                }
            } catch (JSONException e) {
                Log.e(TAG, "postMessage parse error", e);
            }
        }
    }

    private void sendToJs(JSONObject payload) {
        String b64 = Base64.encodeToString(
                payload.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String js =
            "(function(){" +
            "  var b=atob('" + b64 + "');" +
            "  var bytes=new Uint8Array(b.length);" +
            "  for(var i=0;i<b.length;i++) bytes[i]=b.charCodeAt(i);" +
            "  var s=new TextDecoder('utf-8').decode(bytes);" +
            "  window.__sync && window.__sync(s);" +
            "})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void setOrientation(String mode) {
        if ("landscape".equals(mode)) {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUI();
        } else {
            setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            showSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null)
                c.show(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
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
            String URL = "https://www.youtube.com/youtubei/v1/search?key="
                       + KEY + "&prettyPrint=false";

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
                    .post(RequestBody.create(
                            body.toString(),
                            MediaType.parse("application/json; charset=utf-8")))
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", "2.20240101.00.00")
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/")
                    .addHeader("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                    .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null)
                    throw new IOException("HTTP " + resp.code());
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
                err.put("type", "searchResult");
                err.put("id", id);
                err.put("success", false);
                err.put("error", e.getMessage());
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
            sections = doc
                    .getJSONObject("contents")
                    .getJSONObject("twoColumnSearchResultsRenderer")
                    .getJSONObject("primaryContents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
        } catch (JSONException e) { return list; }

        for (int s = 0; s < sections.length() && list.length() < 20; s++) {
            JSONObject sec = sections.getJSONObject(s);
            if (!sec.has("itemSectionRenderer")) continue;
            JSONArray items = sec.getJSONObject("itemSectionRenderer")
                               .getJSONArray("contents");

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
                try {
                    if (vr.has("lengthText"))
                        durStr = vr.getJSONObject("lengthText").optString("simpleText", "");
                } catch (JSONException ignored2) {}

                int dur = parseDur(durStr);
                if (!isMusicVideo(title, ch, dur)) continue;

                JSONObject t = new JSONObject();
                t.put("id", vid);
                t.put("title", title);
                t.put("channel", ch);
                t.put("dur", dur);
                t.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
                list.put(t);
            }
        }
        return list;
    }

    private String extractText(JSONObject vr, String key) {
        try {
            if (!vr.has(key)) return "";
            JSONObject obj = vr.getJSONObject(key);
            if (obj.has("runs"))
                return obj.getJSONArray("runs").getJSONObject(0).optString("text", "");
            return obj.optString("simpleText", "");
        } catch (JSONException e) { return ""; }
    }

    private int parseDur(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600
                    + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60
                    + Integer.parseInt(p[1]);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private boolean isMusicVideo(String title, String channel, int durSec) {
        String tl = title.toLowerCase(), cl = channel.toLowerCase();
        for (String kw : new String[]{
                "vevo","topic","music","records","entertainment",
                "sound","audio","official","label","studio"})
            if (cl.contains(kw)) return true;
        for (String kw : new String[]{
                "official","mv","m/v","music video","audio","lyrics",
                "lyric","visualizer","live","performance","concert","feat",
                "뮤직비디오","음원","공식","노래"})
            if (tl.contains(kw)) return true;
        return durSec >= 60 || durSec == 0;
    }

    private void doSuggest(JSONObject msg) {
        String query = msg.optString("query");
        String id    = msg.optString("id", "0");
        try {
            String url = "https://suggestqueries.google.com/complete/search"
                    + "?client=firefox&ds=yt&q="
                    + java.net.URLEncoder.encode(query, "UTF-8") + "&hl=ko";
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 Firefox/124.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) throw new IOException("empty");
                String json = readUtf8(resp);
                if (json.startsWith("window."))
                    json = json.replaceFirst("^[^(]+\\(", "")
                               .replaceFirst("\\)\\s*$", "");
                JSONArray arr  = new JSONArray(json);
                JSONArray sugs = new JSONArray();
                if (arr.length() > 1) {
                    JSONArray inner = arr.getJSONArray(1);
                    for (int i = 0; i < inner.length() && sugs.length() < 8; i++) {
                        Object o = inner.get(i);
                        String sv = (o instanceof JSONArray)
                                ? ((JSONArray) o).optString(0, "") : o.toString();
                        if (!sv.isEmpty()) sugs.put(sv);
                    }
                }
                JSONObject result = new JSONObject();
                result.put("type", "suggestResult");
                result.put("id", id);
                result.put("success", true);
                result.put("suggestions", sugs);
                sendToJs(result);
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("type", "suggestResult");
                err.put("id", id);
                err.put("success", false);
                err.put("suggestions", new JSONArray());
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
                result.put("type", "downloadResult");
                result.put("id", id);
                result.put("success", true);
                result.put("videoId", videoId);
                result.put("fileName", existing.getName());
                result.put("size", existing.length());
                sendToJs(result);
                return;
            }

            LocalMediaDownloader.download(
                    getApplicationContext(),
                    localMediaStore,
                    videoId,
                    percent -> {
                        try {
                            JSONObject prog = new JSONObject();
                            prog.put("type", "downloadProgress");
                            prog.put("videoId", videoId);
                            prog.put("percent", percent);
                            sendToJs(prog);
                        } catch (JSONException ignored) {}
                    });

            File saved = localMediaStore.findMediaFile(videoId);
            if (saved == null) throw new IOException("저장 파일 없음");

            JSONObject result = new JSONObject();
            result.put("type", "downloadResult");
            result.put("id", id);
            result.put("success", true);
            result.put("videoId", videoId);
            result.put("fileName", saved.getName());
            result.put("size", saved.length());
            sendToJs(result);
        } catch (Exception e) {
            Log.e(TAG, "download failed: " + videoId, e);
            try {
                JSONObject err = new JSONObject();
                err.put("type", "downloadResult");
                err.put("id", id);
                err.put("success", false);
                err.put("videoId", videoId);
                err.put("error", e.getMessage() != null ? e.getMessage() : "다운로드 실패");
                sendToJs(err);
            } catch (JSONException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════
    //  LYRICS — 진입점
    // ════════════════════════════════════════════════════
    private void doFetchLyrics(JSONObject msg) {
        String rawTitle = msg.optString("title");
        String channel  = msg.optString("channel");
        double dur      = msg.optDouble("duration", 0);
        String id       = msg.optString("id", "0");

        // 제목/아티스트 전처리
        String ct = cleanTitle(rawTitle);
        String ca = cleanArtist(channel);
        String stripped = stripBrackets(ct);
        // 괄호 내 feat 아티스트 추출 시도
        String featArtist = extractFeat(rawTitle);

        Log.d(TAG, "[Lyrics] rawTitle=" + rawTitle + " → ct=" + ct
                + " ca=" + ca + " feat=" + featArtist);

        // ── 쿼리 변형 목록 생성 (중복 제거, 순서 유지) ──
        List<String> queryVariants = buildQueryVariants(ct, ca, stripped, featArtist);

        JSONArray lines = null;

        // 1차: lrclib (synced 우선)
        lines = tryLrclib(queryVariants, dur);

        // 2차: NetEase
        if (lines == null || lines.length() == 0)
            lines = tryNetEase(queryVariants, ca, dur);

        try {
            JSONObject result = new JSONObject();
            result.put("type", "lyricsResult");
            result.put("id", id);
            if (lines != null && lines.length() > 0) {
                result.put("success", true);
                result.put("lines", lines);
            } else {
                result.put("success", false);
                result.put("lines", new JSONArray());
            }
            sendToJs(result);
        } catch (JSONException ignored) {}
    }

    /**
     * 쿼리 변형 목록을 만든다.
     * 단조로운 제목(단어 수 적음)일수록 더 다양한 변형을 시도하고,
     * 불필요한 태그가 많은 제목도 여러 레이어로 정제한다.
     */
    private List<String> buildQueryVariants(String ct, String ca, String stripped, String feat) {
        // LinkedHashSet으로 순서 보존 + 중복 제거
        LinkedHashSet<String> set = new LinkedHashSet<>();

        // 1) 기본: 제목 + 아티스트
        if (!ca.isEmpty()) set.add(ct + " " + ca);
        set.add(ct);

        // 2) 괄호 제거 변형
        if (!stripped.equals(ct)) {
            if (!ca.isEmpty()) set.add(stripped + " " + ca);
            set.add(stripped);
        }

        // 3) feat 아티스트 조합
        if (!feat.isEmpty()) {
            set.add(ct + " " + feat);
            if (!stripped.equals(ct)) set.add(stripped + " " + feat);
        }

        // 4) 아티스트만으로 조합 뒤집기
        if (!ca.isEmpty()) set.add(ca + " " + ct);
        if (!ca.isEmpty() && !stripped.equals(ct)) set.add(ca + " " + stripped);

        // 5) 제목이 단어 2개 이하(단조로울 때) → 아티스트 없는 단독 검색도 추가
        int wordCount = ct.trim().split("\\s+").length;
        if (wordCount <= 2) {
            set.add(ct);
            if (!stripped.equals(ct)) set.add(stripped);
        }

        // 6) 제목에서 첫 번째 구분자(-, |) 앞부분만 추출한 변형
        String firstSegment = extractFirstSegment(ct);
        if (!firstSegment.equals(ct) && firstSegment.length() > 1) {
            if (!ca.isEmpty()) set.add(firstSegment + " " + ca);
            set.add(firstSegment);
        }

        // 7) 숫자/특문 제거 버전
        String normalized = ct.replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                              .replaceAll("\\s{2,}", " ").trim();
        if (!normalized.equals(ct) && !normalized.isEmpty()) {
            if (!ca.isEmpty()) set.add(normalized + " " + ca);
            set.add(normalized);
        }

        return new ArrayList<>(set);
    }

    /** "(feat. XXX)", "(ft. XXX)" 등에서 아티스트명 추출 */
    private String extractFeat(String raw) {
        Pattern p = Pattern.compile(
                "(?i)\\(\\s*(?:feat\\.?|ft\\.?)\\s+([^)]+)\\)");
        Matcher m = p.matcher(raw);
        if (m.find()) return m.group(1).trim();
        Pattern p2 = Pattern.compile(
                "(?i)\\s+(?:feat\\.?|ft\\.?)\\s+([^(\\[]+)");
        Matcher m2 = p2.matcher(raw);
        if (m2.find()) return m2.group(1).trim();
        return "";
    }

    /** "제목 - 아티스트" 형태에서 첫 번째 세그먼트 추출 */
    private String extractFirstSegment(String t) {
        int idx = t.indexOf(" - ");
        if (idx > 0) return t.substring(0, idx).trim();
        idx = t.indexOf(" | ");
        if (idx > 0) return t.substring(0, idx).trim();
        return t;
    }

    // ════════════════════════════════════════════════════
    //  lrclib.net — 다중 쿼리 시도
    // ════════════════════════════════════════════════════
    private JSONArray tryLrclib(List<String> queryVariants, double ytDur) {
        // 후보 수집: (score, lrcText)
        double bestScore = Double.NEGATIVE_INFINITY;
        String bestLrc   = null;

        // synced 결과가 나오면 즉시 채점, 없으면 plain도 채점 후 최종 반환
        for (String q : queryVariants) {
            JSONArray results = searchLrclib(q);
            if (results == null || results.length() == 0) continue;

            for (int i = 0; i < results.length(); i++) {
                try {
                    JSONObject item = results.getJSONObject(i);

                    // synced 우선, 없으면 plain
                    String lrcText = item.optString("syncedLyrics", "");
                    boolean isSynced = !lrcText.isEmpty();
                    if (!isSynced) lrcText = item.optString("plainLyrics", "");
                    if (lrcText == null || lrcText.isEmpty()) continue;

                    double lrcDur = getLrcLastTimestamp(lrcText);
                    if (lrcDur <= 0) lrcDur = item.optDouble("duration", 0);

                    double score = scoreLyricCandidate(
                            item.optString("trackName", ""),
                            item.optString("artistName", ""),
                            lrcDur, ytDur, isSynced,
                            // queryVariants의 첫 번째(가장 정제된 조합)에서 온 결과에 가산
                            queryVariants.indexOf(q));

                    Log.d(TAG, "[lrclib] q=" + q
                            + " track=" + item.optString("trackName","")
                            + " score=" + score + " synced=" + isSynced);

                    if (score > bestScore) {
                        bestScore = score;
                        bestLrc   = lrcText;
                    }
                } catch (JSONException ignored) {}
            }

            // synced + 충분히 높은 점수면 즉시 반환 (추가 검색 생략)
            if (bestScore >= 60 && bestLrc != null
                    && hasSyncedLrc(bestLrc)) break;
        }

        if (bestLrc == null || bestScore < 10) return null;
        try { return parseLrc(bestLrc); } catch (JSONException e) { return null; }
    }

    private boolean hasSyncedLrc(String lrc) {
        return TS_RX.matcher(lrc).find();
    }

    private JSONArray searchLrclib(String query) {
        try {
            String url = "https://lrclib.net/api/search?q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            Request req = new Request.Builder().url(url)
                    .addHeader("User-Agent", "SYNCApp/1.0 (https://github.com/sync)")
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
    //  NetEase — 다중 쿼리 시도
    // ════════════════════════════════════════════════════
    private JSONArray tryNetEase(List<String> queryVariants, String ca, double ytDur) {
        // (score, songId) 전체 후보를 모아 상위 5개 시도
        List<long[]>  allIds    = new ArrayList<>();
        List<Double>  allScores = new ArrayList<>();

        for (String q : queryVariants) {
            List<long[]> ids    = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            searchNetEase(q, extractFirstSegment(q), ca, ytDur, ids, scores);
            for (int i = 0; i < ids.size(); i++) {
                // 이미 수집된 ID면 점수만 갱신
                long sid = ids.get(i)[0];
                int existing = indexOfId(allIds, sid);
                if (existing >= 0) {
                    if (scores.get(i) > allScores.get(existing))
                        allScores.set(existing, scores.get(i));
                } else {
                    allIds.add(ids.get(i));
                    allScores.add(scores.get(i));
                }
            }
            // 높은 점수가 이미 확보되면 조기 탈출
            double max = allScores.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (max >= 70 && allIds.size() >= 1) break;
        }

        if (allIds.isEmpty()) return null;

        // 점수 내림차순 정렬 후 상위 5개 시도
        Integer[] idx = new Integer[allIds.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        final List<Double> fs = allScores;
        Arrays.sort(idx, (a, b) -> Double.compare(fs.get(b), fs.get(a)));

        for (int i = 0; i < Math.min(5, idx.length); i++) {
            if (fs.get(idx[i]) < 15) break;  // 최소 점수 하한 완화
            Log.d(TAG, "[NetEase] try songId=" + allIds.get(idx[i])[0]
                    + " score=" + fs.get(idx[i]));
            JSONArray lines = fetchNetEaseLrc(allIds.get(idx[i])[0]);
            if (lines != null && lines.length() > 0) return lines;
        }
        return null;
    }

    private int indexOfId(List<long[]> list, long id) {
        for (int i = 0; i < list.size(); i++)
            if (list.get(i)[0] == id) return i;
        return -1;
    }

    private void searchNetEase(String query, String ct, String ca,
            double ytDur, List<long[]> ids, List<Double> scores) {
        try {
            String url = "https://music.163.com/api/search/get?s="
                    + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&type=1&limit=15";          // 15개로 확대
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
                            ab.append(art.getJSONObject(j).optString("name",""))
                              .append(" ");
                    double score = scoreLyricCandidate(
                            st, ab.toString().trim(),
                            duration, ytDur, false, 0);
                    // NetEase 특성 상 제목 유사도 가중치 조정
                    score += titleSim(ct, st) * 10;
                    ids.add(new long[]{sid});
                    scores.add(score);
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONArray fetchNetEaseLrc(long songId) {
        try {
            String url = "https://music.163.com/api/song/lyric?id="
                    + songId + "&lv=1&kv=1&tv=-1";
            Request req = new Request.Builder().url(url)
                    .addHeader("Referer", "https://music.163.com")
                    .addHeader("Cookie",  "appver=8.0.0")
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.body() == null) return null;
                JSONObject doc = new JSONObject(readUtf8(resp));
                String lrc = null;
                if (doc.has("klyric"))
                    lrc = doc.getJSONObject("klyric").optString("lyric", null);
                if (lrc == null || lrc.isEmpty())
                    if (doc.has("lrc"))
                        lrc = doc.getJSONObject("lrc").optString("lyric", null);
                return (lrc != null && !lrc.isEmpty()) ? parseLrc(lrc) : null;
            }
        } catch (Exception e) { return null; }
    }

    // ════════════════════════════════════════════════════
    //  통합 채점 함수
    //  candidateTitle/candidateArtist: 가사 소스의 메타데이터
    //  candidateDur: 가사의 실제 길이
    //  ytDur: YouTube 영상 길이
    //  isSynced: synced lyrics 여부
    //  queryRank: 0이면 첫 번째(가장 정제된) 쿼리에서 온 결과
    // ════════════════════════════════════════════════════
    private double scoreLyricCandidate(
            String candidateTitle, String candidateArtist,
            double candidateDur, double ytDur,
            boolean isSynced, int queryRank) {

        double score = 0;

        // 길이 유사도 (가장 중요한 지표)
        if (ytDur > 0 && candidateDur > 0) {
            double diff = Math.abs(candidateDur - ytDur);
            if      (diff <= 2)  score += 60;
            else if (diff <= 5)  score += 50;
            else if (diff <= 10) score += 35;
            else if (diff <= 20) score += 18;
            else if (diff <= 40) score += 6;
            else                 score -= 30;
        }

        // 제목 유사도
        score += titleSim(_lastCleanTitle, candidateTitle) * 35;

        // 아티스트 유사도
        score += titleSim(_lastCleanArtist, candidateArtist) * 20;

        // synced 보너스
        if (isSynced) score += 15;

        // 쿼리 순위 페널티 (뒤로 갈수록 신뢰도 낮음)
        score -= queryRank * 2;

        return score;
    }

    // scoreLyricCandidate가 cleanTitle/cleanArtist에 접근할 수 있도록 스레드 로컬 필드
    // (단일 스레드에서 호출되므로 안전)
    private String _lastCleanTitle  = "";
    private String _lastCleanArtist = "";

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
            String trimmed = line.trim();
            String textPart = trimmed
                    .replaceAll("\\[\\d+:\\d{2}[.:]\\d{2,3}\\]", "").trim();
            if (textPart.isEmpty() || CREDIT_RX.matcher(textPart).matches())
                continue;

            Matcher scanner = TS_RX.matcher(trimmed);
            while (scanner.find()) {
                String msStr = scanner.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(scanner.group(1)) * 60.0
                         + Integer.parseInt(scanner.group(2)) + ms;
                times.add(new double[]{t});
                texts.add(textPart);
            }
        }

        Integer[] idx = new Integer[times.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) ->
                Double.compare(times.get(a)[0], times.get(b)[0]));

        JSONArray result = new JSONArray();
        for (int i = 0; i < idx.length; i++) {
            int    ci    = idx[i];
            double start = times.get(ci)[0];
            double end   = (i + 1 < idx.length)
                    ? times.get(idx[i + 1])[0] : start + 5.0;
            if (end - start < 0.1) end = start + 0.5;
            JSONObject obj = new JSONObject();
            obj.put("start", start);
            obj.put("end",   end);
            obj.put("text",  texts.get(ci));
            result.put(obj);
        }
        return result;
    }

    // ════════════════════════════════════════════════════
    //  헬퍼
    // ════════════════════════════════════════════════════

    /**
     * 제목 유사도 — 정규화 후 다단계 비교.
     * 한글/영문 혼용, 부분 포함, 단어 교집합 모두 고려.
     */
    private double titleSim(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        a = normalize(a); b = normalize(b);
        if (a.equals(b)) return 1.0;
        if (b.contains(a) || a.contains(b)) return 0.88;
        // 단어 교집합
        String[] wa = a.split("[^\\p{L}\\p{N}]+");
        String[] wb = b.split("[^\\p{L}\\p{N}]+");
        Set<String> sa = new HashSet<>(), sb2 = new HashSet<>();
        for (String w : wa) if (w.length() > 0) sa.add(w);
        for (String w : wb) if (w.length() > 0) sb2.add(w);
        if (sa.isEmpty() || sb2.isEmpty()) return 0;
        long inter = sa.stream().filter(sb2::contains).count();
        double jaccard = (double) inter / (sa.size() + sb2.size() - inter);
        // 짧은 제목(1~2단어)은 교집합만으로 충분
        if (Math.max(sa.size(), sb2.size()) <= 2) return jaccard;
        // Dice 계수와 Jaccard 평균
        double dice = (2.0 * inter) / (sa.size() + sb2.size());
        return (jaccard + dice) / 2.0;
    }

    private String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[\\p{Punct}&&[^\\p{L}\\p{N}]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stripBrackets(String t) {
        return t.replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("\\s{2,}", " ").trim();
    }

    private double getLrcLastTimestamp(String lrc) {
        double last = 0;
        for (String line : lrc.split("\n")) {
            Matcher m = TS_RX.matcher(line.trim());
            while (m.find()) {
                String msStr = m.group(3);
                double ms = msStr.length() == 2
                        ? Integer.parseInt(msStr) / 100.0
                        : Integer.parseInt(msStr) / 1000.0;
                double t = Integer.parseInt(m.group(1)) * 60.0
                         + Integer.parseInt(m.group(2)) + ms;
                if (t > last) last = t;
            }
        }
        return last;
    }

    /**
     * 제목 정제 — 영문 태그(Official, MV, Audio, Feat 등) 제거,
     * 한글 제목은 최대한 보존.
     */
    private String cleanTitle(String t) {
        // 괄호 안 태그 제거 (대소문자 무관)
        final String TAG_INNER =
            "(?:official\\s*(?:music\\s*)?(?:video|audio|mv|lyric(?:s)?|visualizer)?" +
            "|m/?v" +
            "|music\\s*video" +
            "|audio(?:\\s*only)?" +
            "|lyrics?(?:\\s*(?:video|ver(?:sion)?))?" +
            "|visualizer" +
            "|live(?:\\s+(?:performance|version|session))?" +
            "|performance(?:\\s+video)?" +
            "|(?:hd|4k|1080p|720p)" +
            "|remaster(?:ed)?(?:\\s+version)?" +
            "|re-?upload" +
            "|eng(?:lish)?\\s*(?:ver\\.?|version|sub(?:title)?s?)?" +
            "|kor(?:ean)?\\s*(?:ver\\.?|version)?" +
            "|jp(?:n)?\\s*(?:ver\\.?|version)?" +
            "|prod(?:uced)?(?:\\s+by)?\\s+[^)\\]]+?" +
            "|color\\s*coded" +
            "|han\\s*rom\\s*eng" +
            "|rom\\s*eng" +
            "|han\\s*eng)";

        t = t.replaceAll("(?i)\\(\\s*" + TAG_INNER + "[^)]*\\)", "").trim();
        t = t.replaceAll("(?i)\\[\\s*" + TAG_INNER + "[^\\]]*\\]", "").trim();
        t = t.replaceAll("(?i)\\s*[-|]\\s*(?:" + TAG_INNER + ")\\s*$", "").trim();
        // feat 제거
        t = t.replaceAll("(?i)\\s+(?:feat\\.?|ft\\.?)\\s+.+$", "").trim();
        // 유니코드 대시 정규화
        t = t.replaceAll("[\\u2013\\u2014]+", "-").trim();
        // 연속 공백
        t = t.replaceAll("\\s{2,}", " ").trim();

        _lastCleanTitle = t;
        return t;
    }

    private String cleanArtist(String c) {
        c = c.replaceAll("(?i)\\s*[-–]\\s*Topic\\s*$", "").trim();
        c = c.replaceAll("(?i)VEVO$", "").trim();
        c = c.replaceAll(
                "(?i)\\s*(?:Records|Entertainment|Music|Official|Label|Studios?)\\s*$",
                "").trim();
        c = c.replaceAll("\\s{2,}", " ").trim();
        _lastCleanArtist = c;
        return c;
    }

    @Override protected void onPause()   { super.onPause();   webView.onPause(); }
    @Override protected void onResume()  { super.onResume();  webView.onResume(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (mediaSessionHelper != null) {
            mediaSessionHelper.release();
            mediaSessionHelper = null;
        }
        executor.shutdown();
        webView.destroy();
    }

    private String readUtf8(Response resp) throws IOException {
        byte[] bytes = resp.body().bytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "window.__onAndroidBack && window.__onAndroidBack()", null);
    }
}
