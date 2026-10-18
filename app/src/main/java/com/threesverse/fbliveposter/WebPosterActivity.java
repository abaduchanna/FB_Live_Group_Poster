package com.threesverse.fbliveposter;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.7.0 WEB POSTER — the alternative engine (owner: "alternative dhundo, 100% fix").
 *
 * Why a second engine: every broken release so far failed in the SAME place —
 * walking the Facebook APP's side drawer (Feeds → Menu → See more → Groups),
 * whose accessibility tree changes with every FB update. This engine never
 * touches that tree. It drives m.facebook.com inside a WebView:
 *   - fixed, real DOM with real hyperlinks (no name-guessing),
 *   - login once, cookies persist in the app profile (we never ask for the
 *     password — the owner types it into Facebook itself),
 *   - on ANY failure the status line shows a compact DOM snapshot, so ONE
 *     screenshot is enough to diagnose remotely (deterministic debugging —
 *     something the accessibility engine never had).
 *
 * Flow:
 *   1) Import groups (web): loads the groups page, collects every /groups/
 *      link into the same target store the main screen uses (type=url).
 *   2) Start web posting: for each selected URL target → load the group page
 *      → click the composer → insert the message (contenteditable or textarea)
 *      → click Post → safety delay → next group.
 *
 * The main screen's accessibility engine stays available and now ALSO
 * deep-links (v0.7.0 direct jump) — the two engines are independent.
 */
public class WebPosterActivity extends Activity {

    private static final String FB_BLUE = "#1877F2";
    private static final long PAGE_SETTLE_MS = 2500L;
    private static final long LOAD_WATCHDOG_MS = 20000L;

    private WebView web;
    private TextView statusLine;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String phase = "";      // import | resolve | composer | fill | media | post
    private int tries = 0;
    private int idx = 0;
    private List<JSONObject> targets = new ArrayList<>();
    private boolean loadPending = false;

    // v0.8.0 MEDIA PIPELINE — photos/videos attached automatically in every
    // group. Primary path: a REAL touch is injected on FB's "Photo/video"
    // button (a genuine user gesture, so the WebView file chooser opens) and
    // onShowFileChooser is answered instantly with the pre-attached SAF URIs —
    // the system picker never shows. Fallback path for images: the same
    // DataTransfer DOM injection the Chrome extension uses.
    private List<Uri> mediaUris = new ArrayList<>();
    private volatile boolean chooserAuto = false;     // armed right before the injected tap
    private volatile boolean chooserAnswered = false; // flipped when the auto-answer fired
    private int mediaTries = 0;
    private static final int MAX_INJECT_BYTES = 6 * 1024 * 1024; // images only, per file

    // v0.8.0 import list update: scroll the groups page and re-collect until
    // no NEW group links appear for 2 consecutive rounds (list update karde).
    private int importRounds = 0;
    private int staleRounds = 0;
    private int totalFound = 0;

    /** MainActivity media-run routing: start posting as soon as the screen opens. */
    static final String EXTRA_AUTO_START = "auto_start";

    /** v0.7.5: pending WebView file chooser (FB composer Photo/video button). */
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER_REQ = 4101;

    /** Master kill-switch for the loop, also flipped by MainActivity Stop. */
    volatile static boolean webActive = false;

    // ---------- UI ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(240, 242, 245));

        TextView title = new TextView(this);
        title.setText("Web poster — m.facebook.com engine");
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(Color.parseColor(FB_BLUE));
        title.setPadding(dp(14), dp(10), dp(14), dp(2));
        root.addView(title);

        statusLine = new TextView(this);
        statusLine.setTextSize(13);
        statusLine.setTextColor(Color.rgb(5, 5, 5));
        statusLine.setPadding(dp(14), dp(2), dp(14), dp(6));
        statusLine.setText("Log in to Facebook below once (cookies stay saved). Then: 1) Import groups (web)  2) select groups on the main screen  3) Start web posting — your text, hashtags and attached photos/videos are posted in every group automatically.");
        root.addView(statusLine);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button importBtn = btn("Import groups (web)");
        importBtn.setOnClickListener(v -> importGroups());
        buttons.addView(importBtn, row());
        Button startBtn = btn("Start web posting");
        startBtn.setOnClickListener(v -> startWebPosting());
        buttons.addView(startBtn, row());
        Button stopBtn = btn("Stop");
        stopBtn.setOnClickListener(v -> {
            webActive = false;
            status("Stopped.");
        });
        buttons.addView(stopBtn, row());
        root.addView(buttons);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!loadPending) return; // watchdog or secondary loads — ignore
                loadPending = false;
                ui.removeCallbacks(watchdog);
                ui.postDelayed(WebPosterActivity.this::step, PAGE_SETTLE_MS);
            }
        });
        // v0.7.5: file chooser with MULTI-SELECT (owner: "just single file select
        // karta hai"). Without this, FB's composer Photo/video button either did
        // nothing or fell back to a legacy single-file picker on some WebViews.
        // v0.8.0: when the pipeline arms chooserAuto (right before it injects a
        // real tap on FB's Photo/video button), this callback is answered
        // IMMEDIATELY with the pre-attached SAF URIs — the system picker never
        // shows and the upload starts by itself.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (chooserAuto) {
                    chooserAuto = false;
                    chooserAnswered = true;
                    try {
                        callback.onReceiveValue(mediaUris.toArray(new Uri[0]));
                        long wait = Math.min(60000L, 6000L + 4000L * mediaUris.size());
                        status("Media handed to FB (" + mediaUris.size() + " file(s)) — upload shuru, "
                                + (wait / 1000) + "s wait, then Post…");
                        phase = "post";
                        tries = 0;
                        ui.postDelayed(WebPosterActivity.this::step, wait);
                    } catch (Exception broken) {
                        try {
                            callback.onReceiveValue(null);
                        } catch (Exception ignored) {
                        }
                        status("Media handoff failed: " + broken.getMessage());
                    }
                    return true;
                }
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null); // drop a stale callback
                }
                fileCallback = callback;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("image/*");
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    pick.putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"image/*", "video/*"});
                    startActivityForResult(
                            Intent.createChooser(pick, "Select photos/videos (multiple allowed)"),
                            FILE_CHOOSER_REQ);
                } catch (Exception broken) {
                    fileCallback = null;
                    status("File picker failed: " + broken.getMessage());
                    return false;
                }
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl("https://m.facebook.com/");

        // v0.8.0: MainActivity routed a media run here — start posting without
        // a second tap.
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_START, false)) {
            ui.postDelayed(this::startWebPosting, 1500);
        }
    }

    private Button btn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        return b;
    }

    private LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void status(String msg) {
        statusLine.setText(msg);
        webStatus = msg;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQ) return;
        ValueCallback<Uri[]> cb = fileCallback;
        fileCallback = null;
        if (cb == null) return;
        List<Uri> uris = new ArrayList<>();
        try {
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        Uri u = clip.getItemAt(i).getUri();
                        if (u != null) uris.add(u);
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
        } catch (Exception ignored) {
        }
        cb.onReceiveValue(uris.toArray(new Uri[0]));
        if (!uris.isEmpty()) {
            status(uris.size() + " file(s) selected — FB upload shuru karega.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        webActive = false;
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** MainActivity "Stop campaign" also kills the web loop. */
    static void stopAll() {
        webActive = false;
    }

    volatile static String webStatus = "";

    // ---------- 1) IMPORT (web) ----------

    private void importGroups() {
        webActive = true; // master switch also gates the import step
        importRounds = 0;
        staleRounds = 0;
        totalFound = 0;
        status("Loading Facebook groups page…");
        loadPending = true;
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, LOAD_WATCHDOG_MS);
        phase = "import";
        tries = 0;
        web.loadUrl("https://m.facebook.com/groups/");
    }

    // ---------- 2) CAMPAIGN (web) ----------

    private void startWebPosting() {
        // v0.8.0: ALL selected targets are used — URL targets load directly,
        // NAME targets (imported from the Facebook app by the a11y engine) are
        // resolved through FB group search. Media, text and hashtags attach
        // automatically in every group.
        List<JSONObject> selected = CampaignStore.selectedTargets(this);
        if (selected.isEmpty()) {
            Toast.makeText(this, "No groups selected — import groups, then select them on the main screen", Toast.LENGTH_LONG).show();
            return;
        }
        mediaUris = validateMediaUris(CampaignStore.loadMediaUris(this));
        String text = CampaignStore.postText(this);
        if (text.isEmpty() && mediaUris.isEmpty()) {
            Toast.makeText(this, "Enter the post text/hashtags on the main screen, or attach photos/videos first", Toast.LENGTH_LONG).show();
            return;
        }
        targets = selected;
        idx = 0;
        webActive = true;
        Toast.makeText(this, "Web posting started for " + targets.size() + " groups"
                        + (mediaUris.isEmpty() ? "" : " + " + mediaUris.size() + " media file(s)")
                        + " — keep this screen open",
                Toast.LENGTH_LONG).show();
        loadTarget();
    }

    /** Drops media URIs whose grant died (app update, reboot on some ROMs) so
     * the run never stalls on an unreadable file. */
    private List<Uri> validateMediaUris(List<Uri> uris) {
        List<Uri> good = new ArrayList<>();
        for (Uri u : uris) {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in != null) good.add(u);
            } catch (Exception dead) {
                // unreadable — dropped
            }
        }
        if (good.size() != uris.size()) {
            CampaignStore.saveMediaUris(this, good);
        }
        return good;
    }

    private void loadTarget() {
        if (!alive()) return;
        if (idx >= targets.size()) {
            webActive = false;
            status("ALL DONE — " + targets.size() + " groups processed.");
            return;
        }
        JSONObject t = targets.get(idx);
        mediaTries = 0;
        chooserAuto = false;
        chooserAnswered = false;
        String label = (idx + 1) + "/" + targets.size();
        String url;
        if (CampaignStore.TYPE_NAME.equals(t.optString("t"))) {
            // v0.8.0: resolve the group NAME through FB group search, then post.
            phase = "resolve";
            tries = 0;
            String name = t.optString("v", "");
            status("Group " + label + ": searching \"" + name + "\" on Facebook…");
            url = "https://www.facebook.com/search/groups/?q=" + android.net.Uri.encode(name);
        } else {
            phase = "composer";
            tries = 0;
            url = t.optString("v", "");
            status("Group " + label + ": opening…");
        }
        loadPending = true;
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, LOAD_WATCHDOG_MS);
        web.loadUrl(url);
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!alive() || !loadPending) return;
            loadPending = false; // slow network — try the DOM anyway
            step();
        }
    };

    private boolean alive() {
        return webActive && !isFinishing() && !isDestroyed() && web != null;
    }

    /** One state-machine step. Scheduled by page-finished (+settle) or retries. */
    private void step() {
        if (!alive()) return;
        switch (phase) {
            case "import":
                eval(JS_COLLECT, r -> {
                    List<String[]> pairs = parsePairs(r);
                    int added = CampaignStore.mergeTargets(this, pairs);
                    totalFound += pairs.size();
                    if (added > 0) {
                        staleRounds = 0;
                    } else if (++staleRounds >= 2 || importRounds >= 15) {
                        status(totalFound + " group links collected — list updated (web). Main screen → select → Start (either engine).");
                        return;
                    }
                    // v0.8.0: keep scrolling — the groups page loads more rows
                    // lazily, one screenful is never the full list.
                    importRounds++;
                    status("Import: " + totalFound + " links so far — scrolling for more…");
                    eval(JS_SCROLL, x -> {
                    });
                    ui.postDelayed(this::step, 2500);
                });
                return;

            case "resolve":
                eval(JS_COLLECT, r -> {
                    List<String[]> pairs = parsePairs(r);
                    String name = targets.get(idx).optString("v", "").toLowerCase();
                    String pick = null;
                    for (String[] pair : pairs) {
                        String n = pair[0].toLowerCase();
                        if (n.contains(name) || name.contains(n)) {
                            pick = pair[1];
                            break;
                        }
                    }
                    if (pick == null && !pairs.isEmpty()) pick = pairs.get(0)[1];
                    if (pick != null) {
                        status("Group " + (idx + 1) + ": resolved — opening…");
                        phase = "composer";
                        tries = 0;
                        loadPending = true;
                        ui.removeCallbacks(watchdog);
                        ui.postDelayed(watchdog, LOAD_WATCHDOG_MS);
                        web.loadUrl(pick);
                    } else if (++tries <= 2) {
                        ui.postDelayed(this::step, 3000);
                    } else {
                        dumpAndSkip("group not found in FB search");
                    }
                });
                return;

            case "composer":
                eval(JS_COMPOSER, r -> {
                    String res = field(r, "r");
                    if ("clicked".equals(res) || "composer-open".equals(res)) {
                        phase = "fill";
                        tries = 0;
                        ui.postDelayed(this::step, 2200);
                    } else if (++tries <= 4) {
                        status("Group " + (idx + 1) + ": composer not found yet (try " + tries + "/4)…");
                        ui.postDelayed(this::step, 2500);
                    } else {
                        dumpAndSkip("composer button not found");
                    }
                });
                return;

            case "fill":
                eval(fillJs(), r -> {
                    String res = field(r, "r");
                    if ("filled".equals(res)) {
                        phase = "media";
                        tries = 0;
                        mediaTries = 0;
                        ui.postDelayed(this::step, 1500);
                    } else if (++tries <= 2) {
                        ui.postDelayed(this::step, 2000);
                    } else {
                        dumpAndSkip("message box not found");
                    }
                });
                return;

            case "media":
                // v0.8.0: attach the pre-selected photos/videos BEFORE pressing Post.
                if (mediaUris.isEmpty()) {
                    phase = "post";
                    tries = 0;
                    ui.postDelayed(this::step, 300);
                    return;
                }
                eval(JS_MEDIA, r -> {
                    String res = field(r, "r");
                    if ("found".equals(res)) {
                        float x = numField(r, "x");
                        float y = numField(r, "y");
                        status("Group " + (idx + 1) + ": attaching " + mediaUris.size() + " media file(s)…");
                        chooserAuto = true;
                        chooserAnswered = false;
                        injectTap(x, y);
                        // If the chooser never fires (button changed / gesture
                        // blocked), disarm and retry, then fall back to DOM injection.
                        ui.postDelayed(() -> {
                            if (!chooserAnswered) {
                                chooserAuto = false;
                                mediaStepFailed();
                            }
                        }, 7000);
                    } else {
                        mediaStepFailed();
                    }
                });
                return;

            case "post":
                eval(JS_POST, r -> {
                    String res = field(r, "r");
                    if ("clicked".equals(res)) {
                        // v0.7.5: safety delay min 45s → 10s (owner: "30 sec wait
                        // time ko reduce kar 10-15 sec kar de"). Saved value used
                        // as-is (15s default for new installs).
                        long delay = Math.max(10, CampaignStore.prefs(this)
                                .getInt(CampaignStore.KEY_DELAY, 15));
                        status("Posted in group " + (idx + 1) + "/" + targets.size()
                                + ". Next in " + delay + "s…");
                        idx++;
                        ui.postDelayed(this::loadTarget, delay * 1000L);
                    } else if (++tries <= (mediaUris.isEmpty() ? 2 : 10)) {
                        // With media the Post button stays disabled until the
                        // upload finishes — keep polling, don't skip early.
                        ui.postDelayed(this::step, 3000);
                    } else {
                        dumpAndSkip("Post button not found");
                    }
                });
                return;

            default:
                // unknown phase (after Stop etc.) — do nothing
        }
    }

    /** Media attach did not happen through the chooser — retry, then fall back
     * to the image-only DataTransfer injection (the extension-proven path). */
    private void mediaStepFailed() {
        if (++mediaTries <= 3) {
            ui.postDelayed(this::step, 2500);
            return;
        }
        String js = buildInjectJs();
        if (js == null) {
            dumpAndSkip("media attach failed — video files need the file chooser, which did not open");
            return;
        }
        eval(js, r -> {
            String res = field(r, "r");
            if ("injected".equals(res)) {
                status("Media injected via DOM (" + field(r, "n") + " image(s)) — waiting for upload…");
                phase = "post";
                tries = 0;
                ui.postDelayed(this::step, 6000);
            } else {
                dumpAndSkip("media attach failed (no file chooser and no file input in DOM)");
            }
        });
    }

    /** REAL touch injection on the WebView — a genuine user gesture, so FB's
     * Photo/video button is allowed to open the file chooser. Coordinates are
     * CSS px from JS, converted through the view offset + density. */
    private void injectTap(float cssX, float cssY) {
        try {
            int[] loc = new int[2];
            web.getLocationOnScreen(loc);
            float d = getResources().getDisplayMetrics().density;
            final float x = loc[0] + cssX * d;
            final float y = loc[1] + cssY * d;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            web.dispatchTouchEvent(down);
            down.recycle();
            ui.postDelayed(() -> {
                try {
                    long t2 = SystemClock.uptimeMillis();
                    MotionEvent up = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_UP, x, y, 0);
                    web.dispatchTouchEvent(up);
                    up.recycle();
                } catch (Exception ignored) {
                }
            }, 90);
        } catch (Exception ignored) {
            chooserAuto = false;
        }
    }

    /** Image-only fallback: read bytes in Java, hand them to JS as File
     * objects through a DataTransfer (same mechanism the Chrome extension
     * uses on www.facebook.com). Videos are too big for this path. */
    private String buildInjectJs() {
        StringBuilder sb = new StringBuilder();
        int budget = MAX_INJECT_BYTES;
        try {
            for (Uri u : mediaUris) {
                String type = getContentResolver().getType(u);
                if (type == null || !type.startsWith("image/")) continue; // videos: chooser path only
                byte[] bytes = readUriBytes(u);
                if (bytes == null || bytes.length == 0 || bytes.length > budget) continue;
                budget -= bytes.length;
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String name = readUriName(u);
                String safeName = org.json.JSONObject.quote(name).toString();
                if (sb.length() == 0) {
                    sb.append("(function(){function mk(b64,name,type){var bin=atob(b64);")
                      .append("var buf=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++)buf[i]=bin.charCodeAt(i);")
                      .append("return new File([buf],name,{type:type});}var dt=new DataTransfer();");
                }
                sb.append("dt.items.add(mk('").append(b64).append("','")
                  .append(safeName).append("','").append(type).append("'));");
            }
        } catch (Exception broken) {
            return null;
        }
        if (sb.length() == 0) return null;
        sb.append("var inp=document.querySelector('input[type=\"file\"]');")
          .append("if(!inp)return JSON.stringify({r:'noinput'});")
          .append("inp.files=dt.files;")
          .append("inp.dispatchEvent(new Event('change',{bubbles:true}));")
          .append("return JSON.stringify({r:'injected',n:dt.files.length});})()");
        return sb.toString();
    }

    private byte[] readUriBytes(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception broken) {
            return null;
        }
    }

    private String readUriName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    String name = c.getString(col);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        return "media_" + System.currentTimeMillis();
    }

    /** Shared parse for JS_COLLECT results: [[name, url], ...]. */
    private List<String[]> parsePairs(String raw) {
        List<String[]> pairs = new ArrayList<>();
        try {
            Object o = new JSONTokener(raw).nextValue();
            if (o instanceof String) o = new JSONTokener(o.toString()).nextValue();
            if (!(o instanceof JSONArray)) return pairs;
            JSONArray arr = (JSONArray) o;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray pair = arr.optJSONArray(i);
                if (pair == null || pair.length() < 2) continue;
                String url = pair.optString(1);
                if (!url.startsWith("http")) continue;
                pairs.add(new String[]{pair.optString(0), url});
            }
        } catch (Exception ignored) {
        }
        return pairs;
    }

    /** Deterministic failure diagnostics: compact DOM snapshot into the status
     *  line, then move on. One screenshot now explains the whole failure. */
    private void dumpAndSkip(String why) {
        eval(JS_DUMP, r -> {
            String detail = "";
            try {
                Object o = new JSONTokener(r).nextValue();
                if (o instanceof JSONArray) detail = ((JSONArray) o).join(" | ");
            } catch (Exception ignored) {
            }
            status("SKIPPED group " + (idx + 1) + " — " + why
                    + (detail.isEmpty() ? "" : "\nDOM: " + trim(detail, 400)));
            idx++;
            ui.postDelayed(this::loadTarget, 3000);
        });
    }

    // ---------- JS engine ----------

    private interface JsCallback {
        void run(String result);
    }

    private void eval(String script, final JsCallback cb) {
        runOnUiThread(() -> {
            if (!alive()) return;
            try {
                web.evaluateJavascript(script, result -> {
                    if (!alive()) return;
                    cb.run(result == null ? "null" : result);
                });
            } catch (Exception dead) {
                status("WebView error: " + dead.getMessage());
            }
        });
    }

    /** unwrap evaluateJavascript's JSON-encoded string, then read a field. */
    private static String field(String raw, String key) {
        try {
            Object o = new JSONTokener(raw).nextValue();
            if (o instanceof JSONObject) return ((JSONObject) o).optString(key, "");
            if (o instanceof String) {
                Object inner = new JSONTokener(o.toString()).nextValue();
                if (inner instanceof JSONObject) return ((JSONObject) inner).optString(key, "");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** unwrap evaluateJavascript's JSON-encoded string, then read a number. */
    private float numField(String raw, String key) {
        try {
            Object o = new JSONTokener(raw).nextValue();
            if (o instanceof String) {
                o = new JSONTokener(o.toString()).nextValue();
            }
            if (o instanceof JSONObject) return (float) ((JSONObject) o).optDouble(key, 0.0);
        } catch (Exception ignored) {
        }
        return 0f;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    private String fillJs() {
        // JSONObject.quote → a valid JS string literal (handles newlines/quotes)
        return JS_FILL.replace("__TEXT__", org.json.JSONObject.quote(CampaignStore.postText(this)));
    }

    private static final String JS_COMPOSER =
            "(function(){" +
            "var words=['write something','what\\'s on your mind','create a public post','create post','say something','kuch likhein','کچھ لکھیں'];" +
            "var els=document.querySelectorAll('div[role=\"button\"],span[role=\"button\"],a[role=\"button\"],[role=\"textbox\"]');" +
            "for(var i=0;i<els.length;i++){var e=els[i];if(!e.offsetParent)continue;" +
            "var t=((e.getAttribute('aria-label')||'')+' '+(e.innerText||'')).toLowerCase();" +
            "for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0){e.click();" +
            "return JSON.stringify({r:'clicked',t:t.trim().substring(0,40)});}}}" +
            "if(document.querySelector('div[contenteditable=\"true\"]'))return JSON.stringify({r:'composer-open'});" +
            "return JSON.stringify({r:'notfound'});})()";

    private static final String JS_FILL =
            "(function(){" +
            "var text=__TEXT__;" +
            "var ce=document.querySelector('div[contenteditable=\"true\"]');" +
            "if(ce){ce.focus();document.execCommand('insertText',false,text);" +
            "return JSON.stringify({r:'filled',n:ce.textContent.length});}" +
            "var ta=document.querySelector('textarea');" +
            "if(ta){ta.focus();ta.value=text;ta.dispatchEvent(new Event('input',{bubbles:true}));" +
            "return JSON.stringify({r:'filled',n:text.length});}" +
            "return JSON.stringify({r:'notfound'});})()";

    private static final String JS_POST =
            "(function(){" +
            "var words=['post','publish','پوسٹ'];" +
            "var els=document.querySelectorAll('div[role=\"button\"],button,input[type=\"submit\"]');" +
            "for(var i=0;i<els.length;i++){var e=els[i];" +
            "if(e.getAttribute('aria-disabled')==='true'||e.disabled)continue;" +
            "if(!e.offsetParent)continue;" +
            "var t=((e.getAttribute('aria-label')||'')+' '+(e.innerText||'')+' '+(e.value||'')).toLowerCase().trim();" +
            "for(var j=0;j<words.length;j++){var w=words[j];" +
            "if(t===w||t.indexOf(w+' ')===0||t.indexOf(' '+w+' ')>=0){" +
            "e.click();return JSON.stringify({r:'clicked',t:t.substring(0,30)});}}}" +
            "return JSON.stringify({r:'notfound'});})()";

    private static final String JS_COLLECT =
            "(function(){" +
            "var seen={},out=[];" +
            "var links=document.querySelectorAll('a[href*=\"/groups/\"]');" +
            "for(var i=0;i<links.length;i++){var a=links[i];var href=a.href||'';" +
            "var m=href.match(/facebook\\.com\\/groups\\/([^\\/?#]+)/);if(!m)continue;" +
            "var slug=m[1];" +
            "if(slug==='feed'||slug==='discover'||slug==='yours'||slug==='joins'||slug==='groups')continue;" +
            "var name=(a.innerText||'').trim().split('\\n')[0].trim();" +
            "if(!name||name.length<3||name.length>90)continue;" +
            "if(seen[slug])continue;seen[slug]=1;" +
            "out.push([name,href.split('?')[0].split('#')[0]]);}" +
            "return JSON.stringify(out.slice(0,200));})()";

    /** v0.8.0: find FB's visible "Photo/video" button, scroll it into view and
     * return its centre in CSS px — the Java side turns it into a REAL tap
     * (user gesture) so the file chooser is allowed to open. */
    private static final String JS_MEDIA =
            "(function(){" +
            "var words=['photo/video','photo / video','photo/video or emoji','فوٹو/ویڈیو','فوٹو / ویڈیو'];" +
            "var els=document.querySelectorAll('div[role=\"button\"],a[role=\"button\"],span[role=\"button\"]');" +
            "for(var i=0;i<els.length;i++){var e=els[i];if(!e.offsetParent)continue;" +
            "var t=((e.getAttribute('aria-label')||'')+' '+(e.innerText||'')).toLowerCase();" +
            "for(var j=0;j<words.length;j++){if(t.indexOf(words[j])>=0){" +
            "try{e.scrollIntoView({block:'center'});}catch(x){}" +
            "var r=e.getBoundingClientRect();" +
            "return JSON.stringify({r:'found',x:r.left+r.width/2,y:r.top+r.height/2});}}}" +
            "return JSON.stringify({r:'notfound'});})()";

    private static final String JS_SCROLL =
            "(function(){window.scrollTo(0,document.body.scrollHeight);return 'scrolled';})()";

    private static final String JS_DUMP =
            "(function(){" +
            "var out=[];" +
            "var els=document.querySelectorAll('div[role=\"button\"],[role=\"textbox\"],textarea,div[contenteditable=\"true\"]');" +
            "for(var i=0;i<els.length&&out.length<24;i++){var e=els[i];if(!e.offsetParent)continue;" +
            "var t=(e.getAttribute('aria-label')||e.innerText||e.value||'').trim();" +
            "if(t)out.push(t.substring(0,28));}" +
            "return JSON.stringify(out);})()";
}
