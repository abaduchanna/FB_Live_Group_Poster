package com.threesverse.fbliveposter;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

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

    private String phase = "";      // import | composer | fill | post
    private int tries = 0;
    private int idx = 0;
    private List<JSONObject> targets = new ArrayList<>();
    private boolean loadPending = false;

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
        statusLine.setText("Log in to Facebook below once (cookies stay saved). Then: 1) Import groups (web)  2) select groups on the main screen  3) Start web posting.");
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
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl("https://m.facebook.com/");
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
        List<JSONObject> selected = CampaignStore.selectedTargets(this);
        List<JSONObject> urls = new ArrayList<>();
        for (JSONObject t : selected) {
            if (CampaignStore.TYPE_URL.equals(t.optString("t"))) urls.add(t);
        }
        if (urls.isEmpty()) {
            Toast.makeText(this, "No URL groups selected — tap 'Import groups (web)' first, then select groups on the main screen", Toast.LENGTH_LONG).show();
            return;
        }
        String text = CampaignStore.postText(this);
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter the live link / message on the main screen first", Toast.LENGTH_LONG).show();
            return;
        }
        targets = urls;
        idx = 0;
        webActive = true;
        Toast.makeText(this, "Web posting started for " + targets.size() + " groups — keep this screen open", Toast.LENGTH_LONG).show();
        loadTarget();
    }

    private void loadTarget() {
        if (!alive()) return;
        if (idx >= targets.size()) {
            webActive = false;
            status("ALL DONE — " + targets.size() + " groups processed.");
            return;
        }
        String url = targets.get(idx).optString("v", "");
        phase = "composer";
        tries = 0;
        status("Group " + (idx + 1) + "/" + targets.size() + ": opening…");
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
                    int added;
                    int found = 0;
                    try {
                        Object o = new JSONTokener(r).nextValue();
                        if (!(o instanceof JSONArray)) throw new Exception("not a list");
                        JSONArray arr = (JSONArray) o;
                        List<String[]> pairs = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONArray pair = arr.optJSONArray(i);
                            if (pair == null || pair.length() < 2) continue;
                            String url = pair.optString(1);
                            if (!url.startsWith("http")) continue;
                            found++;
                            pairs.add(new String[]{CampaignStore.TYPE_URL, url});
                        }
                        added = CampaignStore.mergeTargets(this, pairs);
                    } catch (Exception broken) {
                        status("Import failed to parse DOM — open the groups page below and try again. Raw: " + trim(r, 120));
                        return;
                    }
                    status(found + " group links on page, " + added
                            + " new imported (web). Main screen → select → Start (either engine).");
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
                        phase = "post";
                        tries = 0;
                        ui.postDelayed(this::step, 1500);
                    } else if (++tries <= 2) {
                        ui.postDelayed(this::step, 2000);
                    } else {
                        dumpAndSkip("message box not found");
                    }
                });
                return;

            case "post":
                eval(JS_POST, r -> {
                    String res = field(r, "r");
                    if ("clicked".equals(res)) {
                        long delay = Math.max(45, CampaignStore.prefs(this)
                                .getInt(CampaignStore.KEY_DELAY, 90));
                        status("Posted in group " + (idx + 1) + "/" + targets.size()
                                + ". Next in " + delay + "s…");
                        idx++;
                        ui.postDelayed(this::loadTarget, delay * 1000L);
                    } else if (++tries <= 2) {
                        ui.postDelayed(this::step, 2000);
                    } else {
                        dumpAndSkip("Post button not found");
                    }
                });
                return;

            default:
                // unknown phase (after Stop etc.) — do nothing
        }
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

    private static final String JS_DUMP =
            "(function(){" +
            "var out=[];" +
            "var els=document.querySelectorAll('div[role=\"button\"],[role=\"textbox\"],textarea,div[contenteditable=\"true\"]');" +
            "for(var i=0;i<els.length&&out.length<24;i++){var e=els[i];if(!e.offsetParent)continue;" +
            "var t=(e.getAttribute('aria-label')||e.innerText||e.value||'').trim();" +
            "if(t)out.push(t.substring(0,28));}" +
            "return JSON.stringify(out);})()";
}
