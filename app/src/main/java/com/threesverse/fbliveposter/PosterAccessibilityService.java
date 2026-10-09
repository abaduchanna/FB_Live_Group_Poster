package com.threesverse.fbliveposter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PosterAccessibilityService extends AccessibilityService {
    private static final List<String> COMPOSER_LABELS = Arrays.asList(
            "Write something", "What's on your mind", "Create a public post", "Create post", "Say something",
            "How are you feeling", "Post to Facebook"
    );
    private static final List<String> POST_LABELS = Arrays.asList("Post", "POST", "Publish");
    private static final List<String> GROUPS_LABELS = Arrays.asList("Groups");
    private static final List<String> MENU_LABELS = Arrays.asList("Menu");
    private static final List<String> SEE_MORE_LABELS = Arrays.asList("See more", "See all");
    private static final List<String> YOUR_GROUPS_HINTS = Arrays.asList("Your groups", "Groups you manage");
    // Owner flow v0.4.4: FB khulne ke baad pehle Feeds tab (clean screen), phir
    // side drawer. Feeds tab ke label variants — naye FB versions me home tab
    // "Feeds" hai, purane me "Feed"/"Home".
    private static final List<String> FEEDS_LABELS = Arrays.asList("Feeds", "Feed", "Home");
    // Side drawer / Menu tab page khula hone ka signal — ye items sirf Menu
    // screen pe hote hain (feed posts pe kabhi nahi).
    private static final List<String> MENU_SCREEN_HINTS = Arrays.asList(
            "Settings & privacy", "Help & support", "Log out", "All shortcuts");
    // v0.6.4 REVERT of v0.6.3's "Memories"/"Saved" hints: FB feed par
    // kabhi-kabhi "Memories" card render hota hai — false menuOpen navigator
    // ko feed ke top "Groups" chip pe tap karwa sakta tha (Groups FEED khulta
    // hai, list nahi). Sirf feed-proof hints: ye 4 items feed pe KABHI nahi
    // milte, aur owner device par inhi se menuOpen match hota aaya hai.
    // "Your groups" tab ki list ke akhir me FB "Suggested for you" dikhata hai — ye
    // bottom ka natural signal hai; suggested junk groups import hone se pehle ruk jate hain.
    private static final List<String> SUGGESTED_LABELS = Arrays.asList("Suggested for you", "Suggested groups");
    private static final Set<String> NOISE_LABELS = new HashSet<>(Arrays.asList(
            "home", "feed", "menu", "notifications", "search", "marketplace", "watch", "gaming",
            "reels", "video", "profile", "friends", "groups", "group", "events", "pages",
            "memories", "saved", "dating", "kids", "avatar", "create", "see more", "see all",
            "see less", "back", "settings", "unpin group", "pin group", "your activity",
            "manage", "more", "about", "view members", "invite friends", "notification",
            "write something", "what's on your mind", "your groups", "groups you manage",
            "suggested for you", "suggested", "settings & privacy", "help & support", "help",
            "log out", "dark mode", "messages", "chats", "shortcuts", "recent activity",
            "invite", "join", "joined", "cancel request", "request", "discover", "announcements",
            "members", "moderator", "admin", "search groups", "create group", "new group",
            "post", "comment", "like", "share", "live", "camera", "greetings", "rooms"
    ));
    private static final long STAGE_TIMEOUT_MS = 45000L;
    private static final long IMPORT_TIMEOUT_MS = 90000L;
    // Owner-audit (136 groups): 12 screens se bara list kabhi khatam nahi hota tha —
    // group #13+ ke liye "list me nahi mili — skip" aa jata tha. 60 screens ≈ 300+ groups.
    private static final int MAX_GROUP_SCANS = 60;
    // Import scan ka cap ab sirf safety net hai — asli rukne ka signal = list ka bottom
    // (lagataar IDLE_SCANS_TO_STOP ticks me koi naya naam nahi) ya "Suggested for you"
    // section. Purana hard cap 30 pe 136-group list aadhi kat jati thi (owner: "30n
    // scroll nh puray groups").
    private static final int MAX_IMPORT_SCANS = 250;
    // Owner report v0.6.1: on a slow connection FB needs well over 2s to
    // load the next batch, and 4 idle ticks (~8s) declared the list "done"
    // after 2-3 screens. 8 idle ticks at a 3s backoff gives ~24s of real
    // patience; the true bottom is still detected by the "Suggested for you"
    // ceiling and the scroll-refusal fallback, so extra patience cannot
    // cause an endless scan (MAX_IMPORT_SCANS stays the hard cap).
    private static final int IDLE_SCANS_TO_STOP = 8;
    // Facebook's feed can expose thousands of virtual Litho nodes. Accessibility
    // callbacks run on the app's main thread, so an unlimited walk freezes this
    // service and Android shows "isn't responding". Every lookup is deliberately
    // bounded; later accessibility events continue the work on a fresh tree.
    private static final int MAX_LOOKUP_NODES = 900;
    private static final long LOOKUP_BUDGET_NS = 18_000_000L;
    private static final int MAX_COLLECTION_NODES = 2200;
    private static final long COLLECTION_BUDGET_NS = 35_000_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean processing;
    private final Set<String> importedNames = new LinkedHashSet<>();

    // v0.6.0 LIVE diagnostics — the service and MainActivity run in the same
    // process, so plain statics are safe (isMinifyEnabled=false). These exist
    // because a green toggle in the settings list does not prove the service
    // is actually CONNECTED and receiving events, and on Android 13+ a
    // side-loaded app's accessibility switch can stay blocked by "Restricted
    // setting" — which was the most likely cause of "nothing clicks at all".
    private static PosterAccessibilityService instance;
    volatile static boolean connected = false;
    volatile static long lastEventAt = 0L;
    volatile static long lastGestureAt = 0L;
    volatile static String lastGestureResult = "";
    private static int tapFailStreak = 0;
    private static boolean gestureBroken = false;

    // v0.6.3 navigation trace — updated on every navigateTowardGroups tick.
    // Same-process read by the MainActivity status line: a report like
    // "drawer opens but Groups is never found" is now diagnosable from ONE
    // screenshot (menu= / grp= / sMore= / smN= / act= show the exact stuck
    // step and what the navigator decided to do about it).
    volatile static String lastNavTrace = "";
    // v0.6.4: exact screen coordinates of the last dispatched tap — remote
    // geometry debugging (owner screenshot pe dikhega tap kahan land hua).
    volatile static String lastTapPoint = "";

    // v0.7.3 CCLEANER-PORT — engine decoded from com.avast.android.cleanercore2
    // (CCleaner v26.12.2, classes3.dex: AccessibilityNodeInfoUtil /
    // AccessibilityOperation / AccessibilityService). Techniques adopted:
    // (1) normalized label matching, (2) focus-then-click tier, (3) stale-click
    // freshness guard, (4) first-text-child-of-clickable-parent target
    // resolution, (5) tap stroke timed by the system tap timeout.
    // CCleaner skips accessibility events OLDER than the last successful click
    // (lastSuccessfulClick timestamp). This engine is tick-driven, so the
    // equivalent guard: a tick that re-finds the SAME label within one tick
    // window after a successful tap is looking at a stale tree mid-transition
    // — the duplicate tap is suppressed (this kills the double-tap bounce
    // class, e.g. Menu tapped twice while the drawer was already opening).
    volatile static long lastSuccessfulClickAt = 0L;
    volatile static String lastSuccessfulClickLabel = "";

    /** v0.7.3 CCleaner-port (AccessibilityNodeInfoUtil text normalizer):
     * strip every non-letter/non-digit character and lowercase. "Write
     * something…", "What's on your mind?", "Menu." all collapse to the same
     * letter core, so punctuation/locale variants stop breaking label
     * matches. \\p{L} covers Urdu/Arabic script, so localized labels still
     * normalize correctly. Spaces are stripped too — matching is pure
     * letter-core containment/equality. */
    private static String ccNormalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[^\\p{L}\\p{Nd}]", "").toLowerCase();
    }

    /** v0.7.3 CCleaner-port (tapNodeWithGesture): CCleaner times its tap
     * stroke with ViewConfiguration.getTapTimeout() instead of a magic
     * number. Clamped so odd ROM values cannot make taps too short to
     * register or too slow to feel like a tap. */
    private int tapStrokeMs() {
        try {
            int t = android.view.ViewConfiguration.get(getApplicationContext()).getTapTimeout();
            return t < 100 ? 100 : Math.min(t, 150);
        } catch (Exception e) {
            return 110;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        lastEventAt = System.currentTimeMillis();
        SharedPreferences p = CampaignStore.prefs(this);
        boolean running = p.getBoolean(CampaignStore.KEY_RUNNING, false);
        boolean importing = p.getBoolean(CampaignStore.KEY_IMPORT_MODE, false);
        if (!running && !importing) return;
        if (!isTrackedPackage(event.getPackageName().toString(), p)) return;

        if (running
                && CampaignStore.STAGE_PRESS_POST.equals(p.getString(CampaignStore.KEY_STAGE, ""))
                && !p.getBoolean(CampaignStore.KEY_AUTO_POST, false)
                && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                && matchesPostLabel(event)) {
            setStage(p, CampaignStore.STAGE_WAIT_NEXT);
            int delaySeconds = p.getInt(CampaignStore.KEY_DELAY, 90);
            toast("Post detected. Next group in " + delaySeconds + " seconds");
            handler.postDelayed(this::advanceToNextGroup, delaySeconds * 1000L);
            return;
        }
        scheduleProcess(900);
    }

    @Override
    public void onInterrupt() {
        processing = false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        connected = true;
        lastEventAt = 0L;
        keepAliveForeground();
    }

    // v0.6.1 (owner: after closing the app the toggle showed ON but the
    // service was dead — battery killer ate the process, so every session
    // started with a manual OFF/ON). Promoting the service to FOREGROUND
    // with a silent ongoing notification makes the system treat it as
    // something worth keeping, which is the standard fix for accessibility
    // services being killed on swipe-away / aggressive battery savers.
    private void keepAliveForeground() {
        try {
            String channel = "fbposter_live";
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(channel,
                    "Automation active", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Silent keep-alive so the poster service is not killed");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            Notification notif = new Notification.Builder(this, channel)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("FB Live Group Poster active")
                    .setContentText("Automation running — keep this notification")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(2001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(2001, notif);
            }
        } catch (Exception denied) {
            // A few ROMs restrict foreground promotion from a11y contexts —
            // the service still works, it just loses the keep-alive boost.
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private boolean isTrackedPackage(String pkg, SharedPreferences p) {
        if (pkg == null) return false;
        String selected = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
        // More than one Facebook-family app may be installed. Only the app
        // explicitly selected by the user is allowed to drive this session.
        return !selected.isEmpty() && pkg.equals(selected);
    }

    private void scheduleProcess(long delayMs) {
        if (processing) return;
        processing = true;
        handler.postDelayed(() -> {
            // Clear before processing so a state-machine step can schedule its
            // own next retry. Clearing afterwards silently dropped slow-load
            // retries made from importTick()/navigateTowardGroups().
            processing = false;
            processCurrentScreen();
        }, delayMs);
    }

    private void processCurrentScreen() {
        SharedPreferences p = CampaignStore.prefs(this);
        boolean running = p.getBoolean(CampaignStore.KEY_RUNNING, false);
        boolean importing = p.getBoolean(CampaignStore.KEY_IMPORT_MODE, false);
        if (!running && !importing) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleProcess(1600L);
            return;
        }

        if (importing) {
            importTick(p, root);
            return;
        }
        campaignTick(p, root);
    }

    // ================= IMPORT MODE =================

    private void importTick(SharedPreferences p, AccessibilityNodeInfo root) {
        String stage = p.getString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_NAV_GROUPS);

        if (CampaignStore.STAGE_NAV_GROUPS.equals(stage)) {
            if (onGroupsScreen(root)) {
                // Device flow (owner-audit): Groups screen kholte hi default tab
                // "For you" (suggested) hota hai — pehle header ka "Your groups"
                // tab click karo, USKE BAAD scan shuru, warna galat list uthati.
                setStage(p, CampaignStore.STAGE_PICK_TAB);
                return;
            }
            // v0.7.1 DIRECT JUMP: the drawer walk (Feeds→Menu→See more→Groups)
            // is what broke in every release so far. The applink lands straight
            // on the Groups screen — navigateTowardGroups is now only fallback.
            long now = System.currentTimeMillis();
            if (now - p.getLong(CampaignStore.KEY_WEBJUMP_AT, 0L) > 15000L) {
                String pkg = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
                p.edit().putLong(CampaignStore.KEY_WEBJUMP_AT, now).apply();
                if (!pkg.isEmpty() && openGroupsScreen(this, pkg)) {
                    setNavTrace("act=import-jump");
                    return;
                }
            }
            if (stageTimedOut(p, IMPORT_TIMEOUT_MS)) {
                finishImport(p);
                return;
            }
            navigateTowardGroups(p, root);
            scheduleProcess(1800L);
            return;
        }

        if (CampaignStore.STAGE_PICK_TAB.equals(stage)) {
            if (stageTimedOut(p, IMPORT_TIMEOUT_MS)) {
                finishImport(p);
                return;
            }
            AccessibilityNodeInfo tab = findByLabels(root, YOUR_GROUPS_HINTS, false);
            if (tab != null && clickNodeOrParent(tab)) {
                setStage(p, CampaignStore.STAGE_IMPORT_SCAN);
                p.edit()
                        .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                        .putInt(CampaignStore.KEY_NO_NEW_SCANS, 0)
                        .putInt(CampaignStore.KEY_SCROLL_INDEX, 0)
                        .apply();
                scheduleProcess(1500);
            } else {
                // Your-groups tab can arrive late on mobile data.
                scheduleProcess(2200L);
            }
            return;
        }

        if (CampaignStore.STAGE_IMPORT_SCAN.equals(stage)) {
            if (stageTimedOut(p, IMPORT_TIMEOUT_MS)) {
                finishImport(p);
                return;
            }
            // "Suggested for you" ka sar nazar aaye to list khatam — us se neeche wale
            // rows suggested junk hoti hain: unhe skip kar ke wahi tak scan (chhoti
            // lists pe header pehli screen pe hi aa sakta hai, is liye kam az kam 3 naam).
            int ceiling = -1;
            AccessibilityNodeInfo suggested = findByLabels(root, SUGGESTED_LABELS, false);
            if (suggested != null && importedNames.size() >= 3) {
                android.graphics.Rect sr = new android.graphics.Rect();
                suggested.getBoundsInScreen(sr);
                ceiling = sr.top;
            }
            int before = importedNames.size();
            collectGroupNames(root, ceiling);
            if (ceiling >= 0) {
                finishImport(p);
                return;
            }
            int gained = importedNames.size() - before;
            int scans = p.getInt(CampaignStore.KEY_SCAN_COUNT, 0);
            if (gained > 0) {
                // Progress: timeout refresh (90s ab sirf "naya kuch nahi" ka safety hai,
                // poori scan ki deadline nahi) aur scroll candidate reset.
                p.edit()
                        .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                        .putInt(CampaignStore.KEY_NO_NEW_SCANS, 0)
                        .putInt(CampaignStore.KEY_SCROLL_INDEX, 0)
                        .apply();
            } else {
                int idle = p.getInt(CampaignStore.KEY_NO_NEW_SCANS, 0) + 1;
                p.edit()
                        .putInt(CampaignStore.KEY_NO_NEW_SCANS, idle)
                        .putInt(CampaignStore.KEY_SCROLL_INDEX, p.getInt(CampaignStore.KEY_SCROLL_INDEX, 0) + 1)
                        .apply();
                if (idle >= IDLE_SCANS_TO_STOP) {
                    finishImport(p); // 8 ticks me koi naya naam nahi = list ka bottom
                    return;
                }
            }
            if (scans < MAX_IMPORT_SCANS && scrollForward(p, root)) {
                // A successful scroll IS progress on a slow network — refresh
                // the stage clock so the 90s safety timeout cannot fire while
                // content is still streaming in.
                p.edit().putInt(CampaignStore.KEY_SCAN_COUNT, scans + 1)
                        .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                        .apply();
                scheduleProcess(idleBackoff(p));
            } else {
                // A temporarily non-scrollable list usually means Facebook is
                // still loading the next batch. Do not declare completion on
                // the first refusal; wait through the same idle budget.
                int idle = p.getInt(CampaignStore.KEY_NO_NEW_SCANS, 0);
                if (scans >= MAX_IMPORT_SCANS || idle >= IDLE_SCANS_TO_STOP) {
                    finishImport(p);
                } else {
                    scheduleProcess(idleBackoff(p));
                }
            }
        }
    }

    private boolean onGroupsScreen(AccessibilityNodeInfo root) {
        return findByLabels(root, YOUR_GROUPS_HINTS, false) != null;
    }

    /** maxY = screen-space ceiling (is se neeche wale rows suggested junk hain, subtree skip); −1 = limit nahi. */
    private void collectGroupNames(AccessibilityNodeInfo root, int maxY) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        android.graphics.Rect bounds = new android.graphics.Rect();
        int visited = 0;
        long deadline = System.nanoTime() + COLLECTION_BUDGET_NS;
        while (!queue.isEmpty() && visited++ < MAX_COLLECTION_NODES
                && System.nanoTime() < deadline) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (maxY >= 0) {
                node.getBoundsInScreen(bounds);
                if (bounds.top >= maxY) continue; // suggested section — na naam, na children
            }
            String candidate = firstNonEmpty(node.getText(), node.getContentDescription());
            if (isGroupName(candidate) && hasClickableAncestor(node, 3)) {
                importedNames.add(candidate.trim());
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
    }

    private String firstNonEmpty(CharSequence a, CharSequence b) {
        if (a != null && a.toString().trim().length() > 0) return a.toString().trim();
        if (b != null && b.toString().trim().length() > 0) return b.toString().trim();
        return "";
    }

    private boolean hasClickableAncestor(AccessibilityNodeInfo node, int hops) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i <= hops; i++) {
            if (current.isClickable()) return true;
            current = current.getParent();
        }
        return false;
    }

    private boolean isGroupName(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() < 3 || v.length() > 90) return false;
        String low = v.toLowerCase();
        if (NOISE_LABELS.contains(low)) return false;
        if (low.contains("·") || low.contains("members") || low.contains("member ")) return false;
        if (low.startsWith("http") || low.contains("facebook.com") || low.contains("/groups/")) return false;
        // Device-audit junk that slipped through before: Groups-screen tab
        // counters ("For you, 1 of 5"), badge labels ("Settings, 91 new") and
        // chrome/menu leftovers ("Back", "Unpin group"…) — none are group names.
        if (low.matches(".*\\d+\\s+of\\s+\\d+.*")) return false;
        if (low.matches(".*,\\s*\\d+\\s+new.*")) return false;
        if (low.startsWith("back") || low.startsWith("settings")
                || low.startsWith("unpin") || low.startsWith("your activity")
                || low.startsWith("for you") || low.startsWith("your groups")) return false;
        if (low.matches(".*\\d{4,}.*")) return false; // ids, timestamps, big counts
        return true;
    }

    private void finishImport(SharedPreferences p) {
        List<String> names = new ArrayList<>(importedNames);
        CampaignStore.putImportResult(this, names);
        importedNames.clear();
        p.edit()
                .putBoolean(CampaignStore.KEY_IMPORT_MODE, false)
                .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_GROUP)
                .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                .putInt(CampaignStore.KEY_NO_NEW_SCANS, 0)
                .putInt(CampaignStore.KEY_SCROLL_INDEX, 0)
                .apply();
        if (names.isEmpty()) {
            toast("No groups found — open the Groups screen in Facebook, then run Import again");
        } else {
            toast(names.size() + " groups imported — go back to the app to select them");
        }
    }

    // ================= CAMPAIGN MODE =================

    private void campaignTick(SharedPreferences p, AccessibilityNodeInfo root) {
        String stage = p.getString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_COMPOSER);
        long lastAction = p.getLong(CampaignStore.KEY_LAST_ACTION, 0L);
        if (System.currentTimeMillis() - lastAction < 800) return;

        if (!CampaignStore.STAGE_WAIT_NEXT.equals(stage) && stageTimedOut(p, STAGE_TIMEOUT_MS)) {
            toast("Group skipped — unrecognized screen. Next group…");
            advanceToNextGroup();
            return;
        }

        if (CampaignStore.STAGE_OPEN_GROUP.equals(stage)) {
            // v0.7.4 BUGFIX (owner: "har jagah click kar raha hai, jo kaam hai
            // wo nahi kar raha"): OPEN_GROUP used to be handled identically to
            // OPEN_COMPOSER — it hunted for a composer on whatever screen
            // Facebook had open. On the FEED that match is the TIMELINE
            // composer ("What's on your mind?"), so the campaign text was
            // typed into the personal-profile composer and the group was never
            // navigated to. OPEN_GROUP is now a pure ROUTER: URL targets go
            // straight to OPEN_COMPOSER (the deep link already landed inside
            // the group), name targets go to NAV_GROUPS (jump / drawer walk,
            // then FIND_GROUP by name).
            JSONObject target = CampaignStore.selectedTargetAt(this,
                    p.getInt(CampaignStore.KEY_INDEX, 0));
            boolean urlTarget = target != null
                    && CampaignStore.TYPE_URL.equals(target.optString("t"));
            setStage(p, urlTarget
                    ? CampaignStore.STAGE_OPEN_COMPOSER
                    : CampaignStore.STAGE_NAV_GROUPS);
            scheduleProcess(600L);
            return;
        }

        if (CampaignStore.STAGE_OPEN_COMPOSER.equals(stage)) {
            // v0.7.4 guard: if the FEED bottom-nav tab is the selected
            // destination, the only composer on screen is the TIMELINE
            // composer. Clicking it posts the campaign message to the
            // personal profile — never do it. Recover instead: URL targets
            // re-fire their deep link, name targets go back to navigation.
            if (isFeedTabSelected(root)) {
                setNavTrace("act=composer-feedguard");
                long now = System.currentTimeMillis();
                if (now - p.getLong(CampaignStore.KEY_WEBJUMP_AT, 0L) > 15000L) {
                    p.edit().putLong(CampaignStore.KEY_WEBJUMP_AT, now).apply();
                    JSONObject target = CampaignStore.selectedTargetAt(this,
                            p.getInt(CampaignStore.KEY_INDEX, 0));
                    if (target != null && CampaignStore.TYPE_URL.equals(target.optString("t"))) {
                        String url = target.optString("v", "");
                        if (!url.isEmpty()) {
                            Intent jump = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            jump.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            jump.setPackage(p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, ""));
                            try {
                                startActivity(jump);
                            } catch (Exception ignored) {
                            }
                        }
                    } else {
                        setStage(p, CampaignStore.STAGE_NAV_GROUPS);
                    }
                }
                scheduleProcess(2000L);
                return;
            }
            AccessibilityNodeInfo composer = findByLabels(root, COMPOSER_LABELS, false);
            // v0.7.4: visibility guard — BFS also returns off-screen composer
            // nodes with stale bounds; tapping those was another random-tap
            // source on transition frames.
            if (composer != null && isVisibleOnScreen(root, composer)
                    && clickNodeOrParent(composer)) {
                setStage(p, CampaignStore.STAGE_FILL_COMPOSER);
                scheduleProcess(1400);
            } else {
                scheduleProcess(1800L);
            }
            return;
        }

        if (CampaignStore.STAGE_NAV_GROUPS.equals(stage)) {
            if (onGroupsScreen(root)) {
                // Groups screen ka default tab "For you" ho sakta hai — "Your
                // groups" tab pe ek click (already active ho to no-op), warna
                // group name "For you" suggestions me kabhi nahi milega.
                AccessibilityNodeInfo tab = findByLabels(root, YOUR_GROUPS_HINTS, false);
                if (tab != null) clickNodeOrParent(tab);
                p.edit().putLong(CampaignStore.KEY_YGTAB_AT, 1L).apply(); // tab handled for the next stage
                setStage(p, CampaignStore.STAGE_FIND_GROUP);
                scheduleProcess(1800L);
                return;
            }
            // v0.7.1 DIRECT JUMP first (rate-limited) — drawer walk only fallback.
            long now = System.currentTimeMillis();
            if (now - p.getLong(CampaignStore.KEY_WEBJUMP_AT, 0L) > 15000L) {
                String pkg = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
                p.edit().putLong(CampaignStore.KEY_WEBJUMP_AT, now).apply();
                if (!pkg.isEmpty() && openGroupsScreen(this, pkg)) {
                    setNavTrace("act=direct-jump");
                    return;
                }
            }
            navigateTowardGroups(p, root);
            scheduleProcess(1800L);
            return;
        }

        if (CampaignStore.STAGE_FIND_GROUP.equals(stage)) {
            android.content.SharedPreferences.Editor e = p.edit();
            JSONObject target = CampaignStore.selectedTargetAt(this, p.getInt(CampaignStore.KEY_INDEX, 0));
            String name = target == null ? "" : target.optString("v", "");
            if (name.isEmpty()) {
                advanceToNextGroup();
                return;
            }
            AccessibilityNodeInfo row = findGroupRow(root, name);
            if (row != null && isVisibleOnScreen(root, row) && clickNodeOrParent(row)) {
                e.remove(CampaignStore.KEY_SCAN_COUNT)
                        .remove(CampaignStore.KEY_NO_NEW_SCANS)
                        .apply();
                setStage(p, CampaignStore.STAGE_OPEN_COMPOSER);
                toast("Found group: " + name);
                scheduleProcess(1500);
            } else {
                // v0.7.1 recovery order after the direct jump:
                // (1) 8s on an unrecognized screen → drawer/coordinate path as fallback;
                // (2) FIRST Groups-screen tick → click "Your groups" tab ONCE
                //     (the applink may land on the "For you" tab — group rows
                //     never appear there, so scrolling it would be useless);
                // (3) scroll the list for the row (idle patience below).
                if (!onGroupsScreen(root)) {
                    if (stageTimedOutSince(p, 8000L)) {
                        setStage(p, CampaignStore.STAGE_NAV_GROUPS);
                        setNavTrace("act=jump-fallback-nav");
                    } else {
                        setNavTrace("act=jump-land-wait");
                    }
                    return;
                }
                if (p.getLong(CampaignStore.KEY_YGTAB_AT, 0L) == 0L) {
                    AccessibilityNodeInfo tab = findByLabels(root, YOUR_GROUPS_HINTS, false);
                    if (tab != null && isVisibleOnScreen(root, tab)) {
                        p.edit().putLong(CampaignStore.KEY_YGTAB_AT, 1L).apply();
                        clickNodeOrParent(tab);
                        setNavTrace("act=your-groups-tab");
                        scheduleProcess(1500);
                        return;
                    }
                }
                int scans = p.getInt(CampaignStore.KEY_SCAN_COUNT, 0);
                if (scrollForward(root) && scans < MAX_GROUP_SCANS) {
                    // Lambi list (136 groups) me group tak scroll karne me 30+ screens
                    // lag sakte hain — har successful scroll progress hai, stage
                    // timeout refresh karo warna 45s me "skip" ho jata tha.
                    e.putInt(CampaignStore.KEY_SCAN_COUNT, scans + 1)
                            .putInt(CampaignStore.KEY_NO_NEW_SCANS, 0)
                            .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                            .apply();
                    scheduleProcess(1100);
                } else {
                    int idle = p.getInt(CampaignStore.KEY_NO_NEW_SCANS, 0) + 1;
                    if (scans >= MAX_GROUP_SCANS || idle >= IDLE_SCANS_TO_STOP) {
                        e.remove(CampaignStore.KEY_SCAN_COUNT)
                                .remove(CampaignStore.KEY_NO_NEW_SCANS)
                                .apply();
                        toast("Group not in your list: " + name + " — skipping");
                        advanceToNextGroup();
                    } else {
                        e.putInt(CampaignStore.KEY_NO_NEW_SCANS, idle).apply();
                        scheduleProcess(3000L);
                    }
                }
            }
            return;
        }

        if (CampaignStore.STAGE_FILL_COMPOSER.equals(stage)) {
            AccessibilityNodeInfo editable = findEditable(root);
            if (editable != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        CampaignStore.postText(this));
                if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    setStage(p, CampaignStore.STAGE_PRESS_POST);
                    toast(p.getBoolean(CampaignStore.KEY_AUTO_POST, false)
                            ? "Message ready — posting…"
                            : "Message ready — press Post manually");
                    if (p.getBoolean(CampaignStore.KEY_AUTO_POST, false)) scheduleProcess(1200);
                } else {
                    scheduleProcess(1800L);
                }
            } else {
                scheduleProcess(1800L);
            }
            return;
        }

        if (CampaignStore.STAGE_PRESS_POST.equals(stage)
                && p.getBoolean(CampaignStore.KEY_AUTO_POST, false)) {
            AccessibilityNodeInfo post = findByLabels(root, POST_LABELS, true);
            if (post != null && clickNodeOrParent(post)) {
                setStage(p, CampaignStore.STAGE_WAIT_NEXT);
                int delaySeconds = p.getInt(CampaignStore.KEY_DELAY, 90);
                toast("Posted. Next group in " + delaySeconds + " seconds");
                handler.postDelayed(this::advanceToNextGroup, delaySeconds * 1000L);
            } else {
                scheduleProcess(1800L);
            }
        }
    }

    // Owner workflow v0.6.2/v0.6.3 — STRICT, no shortcuts (owner: "kitni baar workflow batau"):
    //   1) FB khulte hi check: feed pe hain ya nahi (composer hint = feed)
    //   2) Drawer/Menu kholo
    //   3) Drawer me PEHLE "See more" click (Groups collapsed/hidden hota hai)
    //   4) Phir "Groups" entry click
    //   5) Groups screen: "Your groups" tab click
    //   6) Scroll + scan
    // v0.6.3 (owner: "drawer khol raha hai groups nahi mil raha"): the
    // menuOpen branch is now DEADLOCK-FREE. Groups VISIBLE = "See more" has
    // already done its reveal job, so Groups is clicked in the same tick;
    // "See more" itself is capped at 2 clicks per drawer session, so a no-op
    // click on a wrong "See all" node can no longer starve the Groups step
    // forever (the old code returned after EVERY See-more click).
    // The old STEP 0 (click the first exact "Groups" node anywhere) is GONE:
    // the feed's top chips contain a "Groups" chip that opens the Groups
    // FEED (not the list), and BFS also matches off-screen drawer nodes —
    // tapping those either misses or taps the container center (a random
    // row). The drawer path is now the ONLY path.
    private void navigateTowardGroups(SharedPreferences p, AccessibilityNodeInfo root) {
        // Just-clicked-Groups debounce: give the Groups screen time to render
        // before deciding we are lost and starting navigation over.
        long clickedAt = p.getLong(CampaignStore.KEY_GROUPS_CLICKED_AT, 0L);
        if (clickedAt > 0 && System.currentTimeMillis() - clickedAt < 5000L) return;

        long now = System.currentTimeMillis();
        int coordStep = p.getInt(CampaignStore.KEY_COORD_NAV_STEP, 0);
        long coordTapAt = p.getLong(CampaignStore.KEY_COORD_TAP_AT, 0L);
        // On the owner's Facebook build the drawer contents become readable,
        // but the drawer page itself has no reliable accessibility heading.
        // After our hamburger tap, treat the next 15 seconds as drawer context.
        boolean coordinateDrawerExpected = coordStep == 2 && coordTapAt > 0L
                && now - coordTapAt >= 700L && now - coordTapAt < 15000L;
        boolean menuOpen = onMenuScreen(root) || coordinateDrawerExpected;
        boolean onFeed = !menuOpen && onFeedScreen(root);
        boolean feedTapped = p.getBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, false);

        // Facebook can reopen on the drawer it remembered from the previous
        // session. The requested sequence starts from Feed, so close that old
        // drawer before doing anything inside it.
        if (menuOpen && !feedTapped) {
            setNavTrace("menu=1 feedTap=0 act=close-stale-menu");
            if (stageTimedOutSince(p, 1800L)) {
                p.edit().putLong(CampaignStore.KEY_STAGE_SINCE,
                        System.currentTimeMillis()).apply();
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
            return;
        }

        // v0.6.3 anti-loop: drawer band hote hi See-more session counter reset
        // (naya drawer session = phir se 2 See-more clicks ki allowance).
        if (!menuOpen && p.getInt(CampaignStore.KEY_SEEMORE_CLICKS, 0) != 0) {
            p.edit().putInt(CampaignStore.KEY_SEEMORE_CLICKS, 0).apply();
        }

        // STEP 1 (owner step 1): FB khulte hi feed verify. v0.6.4: agar FB
        // kisi aur tab pe khula hai (Video/Reels yaad-se-khula state) to har
        // rate-limited tick pe Feeds tab actively click hota hai. Purana
        // FEEDS_DONE latch recovery ROK deta tha — stale flag ke saath app
        // unknown screen se Menu tap karta rehta tha, kabhi feed wapas nahi
        // aata tha (owner: "fb open karte hi videos me maa chudwane ja raha").
        if (!menuOpen) {
            AccessibilityNodeInfo feeds = findByLabels(root, FEEDS_LABELS, true);
            boolean feedsVisible = feeds != null && isVisibleOnScreen(root, feeds);

            // Always perform the requested first step once per navigation:
            // select Feed/Feeds, even if Facebook happened to launch there.
            if (!feedTapped) {
                if (feedsVisible && stageTimedOutSince(p, 1800L) && clickNodeOrParent(feeds)) {
                    p.edit()
                            .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, true)
                            .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                            .apply();
                    setNavTrace("feedTap=1 act=feeds-click");
                    scheduleProcess(1800L);
                    return;
                }
                setNavTrace("feedTap=0 feeds=" + (feedsVisible ? "visible" : "none")
                        + " act=feeds-wait");
                // Some Facebook builds expose no Feed node once it is already
                // selected. After a patient wait, accept the verified feed.
                if (onFeed && stageTimedOutSince(p, 8000L)) {
                    p.edit()
                            .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, true)
                            .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                            .apply();
                    setNavTrace("feedTap=fallback act=feed-verified");
                    return;
                }
                // Owner screenshot: this FB build renders Feed as the second
                // bottom icon but exposes no Feed node. Tap that real icon.
                // v0.7.4 blind-tap governor: coordinate fallbacks are the
                // #1 source of "har jagah click kar raha hai" — they used to
                // fire on ANY unrecognized screen every ~1.8s with no cap.
                // Now: main-chrome gate + max 2 per group + >=5s spacing.
                if (stageTimedOutSince(p, 1800L)
                        && now - p.getLong(CampaignStore.KEY_COORD_TAP_AT, 0L) >= 5000L
                        && p.getInt(CampaignStore.KEY_FEEDTAB_TAP_ATTEMPTS, 0) < 2
                        && mainChromeVisible(root)
                        && tapRelative(root, 0.25f, 0.955f, "Feed tab")) {
                    p.edit()
                            .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, true)
                            .putInt(CampaignStore.KEY_COORD_NAV_STEP, 1)
                            .putInt(CampaignStore.KEY_FEEDTAB_TAP_ATTEMPTS,
                                    p.getInt(CampaignStore.KEY_FEEDTAB_TAP_ATTEMPTS, 0) + 1)
                            .putLong(CampaignStore.KEY_COORD_TAP_AT, now)
                            .putLong(CampaignStore.KEY_STAGE_SINCE, now)
                            .apply();
                    setNavTrace("feedTap=coord act=feed-bottom");
                    scheduleProcess(1600L);
                }
                return;
            }

            if (onFeed) {
                setNavTrace("feed=1 act=feed-verified");
            } else {
                if (feedsVisible && stageTimedOutSince(p, 3500L) && clickNodeOrParent(feeds)) {
                    p.edit().putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis()).apply();
                    setNavTrace("feed=0 act=feeds-retry");
                    scheduleProcess(1800L);
                    return;
                }
                // v0.6.6: 12s BACK + feedTapped-reset HATA — feed/Feeds page
                // pe BACK FB se bahar nikal jata tha (automation dead = owner
                // ka "app exe nahi chal rahi"). Ab wait-only + fall-through:
                // Menu (STEP 2, ab unconditional) isi tick drawer khol sakta
                // hai — drawer Feeds page se bhi khulta hai.
                setNavTrace("feed=0 feeds=" + (feedsVisible ? "visible" : (feeds == null ? "none" : "off/gone"))
                        + " act=feed-wait+menu-next");
            }
        }

        if (menuOpen) {
            // STEP 4 (owner: "phir groups ko click karna hai") — FIRST, and
            // only a Groups entry that is actually RENDERED on screen right
            // now. v0.6.3: Groups pehle check hota hai kyunki Groups visible
            // hone ka matlab hai "See more" reveal already done — agar is ke
            // baad bhi har tick pehle See-more click + return karte to wo
            // click no-op ya galat node pe hote to Groups KABHI try nahi hota
            // (owner ka exact reported loop).
            AccessibilityNodeInfo groups = findByLabels(root, GROUPS_LABELS, true);
            boolean groupsVisible = groups != null && isVisibleOnScreen(root, groups);
            int smClicks = p.getInt(CampaignStore.KEY_SEEMORE_CLICKS, 0);
            AccessibilityNodeInfo seeMore = findByLabels(root, SEE_MORE_LABELS, true);
            boolean smVisible = seeMore != null && isVisibleOnScreen(root, seeMore);

            // Required drawer order: expand See more once before Groups. If
            // this Facebook build has no See more control, Groups remains a
            // valid direct fallback.
            if (smClicks == 0 && smVisible && stageTimedOutSince(p, 1800L)
                    && clickNodeOrParent(seeMore)) {
                p.edit()
                        .putInt(CampaignStore.KEY_SEEMORE_CLICKS, 1)
                        .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                        .apply();
                setNavTrace("menu=1 act=see-more#1");
                scheduleProcess(1800L);
                return;
            }

            if (groupsVisible && (smClicks > 0 || !smVisible)
                    && stageTimedOutSince(p, 1500L) && clickNodeOrParent(groups)) {
                p.edit()
                        .putLong(CampaignStore.KEY_GROUPS_CLICKED_AT, System.currentTimeMillis())
                        .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, true)
                        .putInt(CampaignStore.KEY_SEEMORE_CLICKS, 0)
                        .putInt(CampaignStore.KEY_COORD_NAV_STEP, 0)
                        .apply();
                setNavTrace("menu=1 grp=1 act=groups-click");
                arriveAtGroupsScreen(p);
                return;
            }
            // STEP 3 (owner: "see more ko pehle click karna hai"): Groups abhi
            // hidden hai — drawer expand karo. Visibility guard: BFS also
            // returns off-screen nodes from the collapsed drawer — never tap
            // those. CAP = 2 clicks per drawer session: do no-op clicks ke
            // baad hum scroll + Groups-search karte rahenge, See-more pe
            // hamesha ke liye stuck nahi honge.
            if (smClicks > 0 && smClicks < 2 && smVisible
                    && stageTimedOutSince(p, 2500L) && clickNodeOrParent(seeMore)) {
                p.edit()
                        .putInt(CampaignStore.KEY_SEEMORE_CLICKS, smClicks + 1)
                        .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                        .apply();
                setNavTrace("menu=1 grp=" + (groupsVisible ? 1 : 0)
                        + " act=see-more#" + (smClicks + 1));
                return;
            }
            // STEP 4b: expanded drawer me Groups abhi bhi fold ke neeche hai —
            // drawer scroll karo.
            boolean scrolled = false;
            if (stageTimedOutSince(p, 2500L)) {
                scrolled = scrollForward(root);
                if (scrolled) {
                    p.edit().putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis()).apply();
                }
            }
            setNavTrace("menu=1 grp=" + (groupsVisible ? 1 : 0)
                    + " sMore=" + (smVisible ? 1 : 0) + " smN=" + smClicks
                    + (scrolled ? " act=drawer-scroll" : " act=drawer-wait"));
            return;
        }

        // STEP 2 (owner step 2): side drawer kholo (Menu tab). v0.6.6: onFeed
        // gate HATA — composer/selected-tab hints FB build pe unreliable hain
        // aur ye gate load-bearing tha: hint miss = drawer KABHI nahi khulta
        // = "app exe nahi chal rahi" (v0.6.4/v0.6.5 dono me yahi deadlock
        // tha). Asli protection wahi guards hain: VISIBLE (isVisibleToUser +
        // bounds) + bottom 25% (drawer ke andar ka "Menu" text / GONE node
        // kabhi pass nahi hota). Rate-limited (double-tap drawer band deta).
        if (!menuOpen && stageTimedOutSince(p, 2500L)) {
            AccessibilityNodeInfo menu = findByLabels(root, MENU_LABELS, true);
            if (menu == null) menu = findByLabels(root, MENU_LABELS, false);
            if (menu != null && isVisibleOnScreen(root, menu)
                    && isBottomNavItem(root, menu) && clickNodeOrParent(menu)) {
                p.edit()
                        .putInt(CampaignStore.KEY_COORD_NAV_STEP, 2)
                        .putLong(CampaignStore.KEY_COORD_TAP_AT, now)
                        .putLong(CampaignStore.KEY_STAGE_SINCE, now)
                        .apply();
                setNavTrace("act=menu-click");
                scheduleProcess(1700L);
                return;
            }
            // Owner screenshot: hamburger is visibly top-left (not a hidden
            // bottom Menu tab), while accessibility exposes neither label.
            // Use the visible control's relative location, never a deep link.
            // v0.7.4 governor: same main-chrome gate + >=5s spacing as the
            // feed-tab tap — no blind taps on unknown screens, ever.
            int attempts = p.getInt(CampaignStore.KEY_DRAWER_TAP_ATTEMPTS, 0);
            if (attempts < 2
                    && now - p.getLong(CampaignStore.KEY_COORD_TAP_AT, 0L) >= 5000L
                    && mainChromeVisible(root)
                    && tapRelative(root, 0.065f, 0.065f, "Facebook drawer")) {
                p.edit()
                        .putInt(CampaignStore.KEY_COORD_NAV_STEP, 2)
                        .putLong(CampaignStore.KEY_COORD_TAP_AT, now)
                        .putInt(CampaignStore.KEY_DRAWER_TAP_ATTEMPTS, attempts + 1)
                        .putLong(CampaignStore.KEY_STAGE_SINCE, now)
                        .apply();
                setNavTrace("act=drawer-coordinate#" + (attempts + 1));
                scheduleProcess(1800L);
                return;
            }
            setNavTrace("act=drawer-miss");
        }

        // Never press Back or open a URL here: Back exits Facebook on this
        // layout and direct Groups links can land in Reels. Stay on-screen and
        // let the next bounded accessibility event retry the visible controls.
    }

    /** Owner step 1: feed pe hain? Composer is strongest; selected Feed tab is fallback. */
    private boolean onFeedScreen(AccessibilityNodeInfo root) {
        if (findByLabels(root, COMPOSER_LABELS, false) != null) return true;
        AccessibilityNodeInfo feed = findByLabels(root, FEEDS_LABELS, true);
        return feed != null && feed.isVisibleToUser() && feed.isSelected();
    }

    /** True only if the node is actually RENDERED on screen right now with a
     * tappable size. BFS returns off-screen nodes inside scrollable
     * containers too; tapping those either lands nowhere or hits the
     * container's visible center — a random row. v0.6.4: isVisibleToUser()
     * check bhi — GONE/invisible nodes (band drawer ka content, removed rows)
     * tree me STALE bounds ke saath rehte hain; bounds-only check unhe
     * "visible" keh deta tha aur tap screen ke beech kisi covered coordinate
     * pe land karta tha. */
    private boolean isVisibleOnScreen(AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        if (root == null) return false;
        try {
            if (!node.isVisibleToUser()) return false;
        } catch (Exception ignored) {
        }
        android.graphics.Rect win = new android.graphics.Rect();
        root.getBoundsInScreen(win);
        if (win.isEmpty()) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);
        if (!r.intersect(win)) return false;
        return r.height() > 40 && r.width() > 8;
    }

    /** Groups entry click ho gayi — import me "Your groups" tab pe jana hai (owner:
     *  "your groups main jao"), campaign me bhi pehle STAGE_NAV_GROUPS ka
     *  onGroupsScreen branch chalega jo "Your groups" tab click kar ke
     *  STAGE_FIND_GROUP deta hai (owner step 5 — one-shot, har tick repeat nahi). */
    private void arriveAtGroupsScreen(SharedPreferences p) {
        setStage(p, p.getBoolean(CampaignStore.KEY_IMPORT_MODE, false)
                ? CampaignStore.STAGE_PICK_TAB
                : CampaignStore.STAGE_NAV_GROUPS);
        p.edit()
                .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                .putInt(CampaignStore.KEY_NO_NEW_SCANS, 0)
                .putInt(CampaignStore.KEY_SCROLL_INDEX, 0)
                .apply();
    }

    private boolean onMenuScreen(AccessibilityNodeInfo root) {
        return findByLabels(root, MENU_SCREEN_HINTS, false) != null;
    }

    /** v0.7.4: is the FEED bottom-nav tab the currently selected destination?
     * If yes, every composer hint on screen belongs to the TIMELINE composer
     * ("What's on your mind?") — clicking it would type the campaign post
     * onto the personal profile instead of a group. */
    private boolean isFeedTabSelected(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo feed = findByLabels(root, FEEDS_LABELS, true);
        return feed != null && feed.isVisibleToUser() && feed.isSelected();
    }

    /** v0.7.4 BLIND-TAP GOVERNOR: coordinate fallbacks (fixed-fraction taps)
     * may only fire while the screen still looks like Facebook's main chrome
     * — a feed signal, the menu/drawer, or at least a bottom-nav structure.
     * On truly unknown screens (photo viewer, profile, search, video, another
     * app layer) a fraction-of-window tap lands on an arbitrary control, which
     * is exactly the owner's "chutiyon ki tarah har jagah click" report.
     * When this returns false the engine waits silently; the stage timeout
     * then skips the group cleanly instead of spraying taps. */
    private boolean mainChromeVisible(AccessibilityNodeInfo root) {
        if (onFeedScreen(root)) return true;
        if (onMenuScreen(root)) return true;
        if (findByLabels(root, FEEDS_LABELS, true) != null) return true;
        if (findByLabels(root, MENU_LABELS, true) != null) return true;
        return bottomNavPresent(root);
    }

    /** Bounded scan: are there at least 2 selectable/clickable controls in the
     * bottom 12% band of the window? That is Facebook's bottom navigation —
     * present on every main screen, absent on overlay screens (composer,
     * photo viewer, video). Cheap guard that a fraction-tap at the bottom
     * can only hit a real nav item. */
    private boolean bottomNavPresent(AccessibilityNodeInfo root) {
        android.graphics.Rect win = new android.graphics.Rect();
        root.getBoundsInScreen(win);
        if (win.isEmpty()) return false;
        int bandTop = win.top + Math.round(win.height() * 0.86f);
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        int found = 0;
        long deadline = System.nanoTime() + LOOKUP_BUDGET_NS;
        while (!queue.isEmpty() && visited++ < MAX_LOOKUP_NODES
                && System.nanoTime() < deadline) {
            AccessibilityNodeInfo node = queue.removeFirst();
            android.graphics.Rect r = new android.graphics.Rect();
            node.getBoundsInScreen(r);
            if (!r.isEmpty() && r.centerY() >= bandTop && r.centerY() <= win.bottom
                    && r.width() > 8 && r.height() > 8
                    && (node.isClickable() || node.isSelected())) {
                found++;
                if (found >= 2) return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    /** v0.6.3 bottom-nav guard: the Menu TAB lives in the bottom 25% of the
     * screen. Any other "Menu" node (drawer header text, off-screen node whose
     * stale bounds sit elsewhere) must NOT be tapped — tapping it closed the
     * freshly opened drawer and the navigator looped open/close forever.
     * Fail-open when window bounds are unavailable (same behavior as before). */
    private boolean isBottomNavItem(AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        android.graphics.Rect win = new android.graphics.Rect();
        root.getBoundsInScreen(win);
        if (win.isEmpty()) return true;
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);
        int cy = r.centerY();
        return cy >= win.top + (int) (win.height() * 0.75) && cy <= win.bottom;
    }

    /** v0.6.3 live navigation trace (see lastNavTrace). */
    private static void setNavTrace(String s) {
        lastNavTrace = s;
    }

    private AccessibilityNodeInfo findGroupRow(AccessibilityNodeInfo root, String name) {
        AccessibilityNodeInfo exact = findByLabels(root, Arrays.asList(name), true);
        if (exact != null) return exact;
        if (name.length() >= 5) {
            return findByLabels(root, Arrays.asList(name), false);
        }
        return null;
    }

    // Device-audit: Groups screen pe tab-pager ("Your groups | For you") bhi scrollable
    // hota hai — BFS ka pehla scrollable pager nikal kar TAB SWITCH kar deta tha, is
    // liye list scroll nahi hoti thi. Ab candidates ko score karte hain: vertical
    // RecyclerView/ListView sab se pehle, ViewPager sab se baad.
    private boolean scrollForward(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates = scrollCandidates(root);
        for (AccessibilityNodeInfo node : candidates) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        return false;
    }

    /** Import scan: same ranked order, magar idle ticks pe candidate rotate hota hai. */
    private boolean scrollForward(SharedPreferences p, AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates = scrollCandidates(root);
        if (candidates.isEmpty()) return false;
        int start = p.getInt(CampaignStore.KEY_SCROLL_INDEX, 0) % candidates.size();
        for (int i = 0; i < candidates.size(); i++) {
            AccessibilityNodeInfo node = candidates.get((start + i) % candidates.size());
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        return false;
    }

    private List<AccessibilityNodeInfo> scrollCandidates(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        long deadline = System.nanoTime() + LOOKUP_BUDGET_NS;
        while (!queue.isEmpty() && visited++ < MAX_LOOKUP_NODES
                && System.nanoTime() < deadline) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isScrollable()) found.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        android.graphics.Rect rootRect = new android.graphics.Rect();
        root.getBoundsInScreen(rootRect);
        final int rootW = Math.max(1, rootRect.width());
        final int rootH = Math.max(1, rootRect.height());
        java.util.Collections.sort(found, (a, b) ->
                scrollScore(b, rootW, rootH) - scrollScore(a, rootW, rootH));
        return found;
    }

    private int scrollScore(AccessibilityNodeInfo node, int rootW, int rootH) {
        String cls = node.getClassName() == null ? "" : node.getClassName().toString();
        int score = 0;
        if (cls.contains("ViewPager")) score -= 600;                    // horizontal tab pager
        if (cls.contains("RecyclerView") || cls.contains("ListView")) score += 250;
        else if (cls.contains("ScrollView")) score += 150;
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);
        if (r.height() >= rootH * 0.4) score += 120;                    // full-height vertical list
        if (r.width() >= rootW * 0.5) score += 60;
        else score -= 200;                                              // narrow strip
        return score;
    }

    /** Idle (naya naam nahi) ticks pe lamba wait — slow network pe content load hone ka time.
     *  v0.6.1: 2s was too fast for FB on mobile data; 3s + 8 idle ticks ≈ 24s patience. */
    private long idleBackoff(SharedPreferences p) {
        return p.getInt(CampaignStore.KEY_NO_NEW_SCANS, 0) > 0 ? 3000L : 1100L;
    }

    private void advanceToNextGroup() {
        SharedPreferences p = CampaignStore.prefs(this);
        if (!p.getBoolean(CampaignStore.KEY_RUNNING, false)) return;
        int next = p.getInt(CampaignStore.KEY_INDEX, 0) + 1;
        if (CampaignStore.selectedTargetAt(this, next) == null) {
            CampaignStore.stop(this);
            toast("All selected groups completed");
            return;
        }
        p.edit()
                .putInt(CampaignStore.KEY_INDEX, next)
                .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_GROUP)
                .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                .apply();
        openCurrentGroup(this);
    }

    static void openCurrentGroup(Context context) {
        SharedPreferences p = CampaignStore.prefs(context);
        JSONObject target = CampaignStore.selectedTargetAt(context, p.getInt(CampaignStore.KEY_INDEX, 0));
        if (target == null) {
            CampaignStore.stop(context);
            return;
        }
        String selectedPackage = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
        if (selectedPackage.isEmpty()) {
            CampaignStore.stop(context);
            Toast.makeText(context, "Facebook app was not selected. Start the campaign again.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent;
        if (CampaignStore.TYPE_URL.equals(target.optString("t"))) {
            // Deep link straight to the group inside the selected Facebook app.
            p.edit()
                    .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_COMPOSER)
                    .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                    .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                    .apply();
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target.optString("v", "")));
        } else {
            // v0.7.1 DIRECT JUMP for imported (name) targets too: applink lands
            // straight on the Groups screen, then only "Your groups" tab + row
            // click remain. The drawer/coordinate walk is now ONLY the fallback
            // when this jump fails.
            boolean jumped = openGroupsScreen(context, selectedPackage);
            if (jumped) {
                p.edit()
                        .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_FIND_GROUP)
                        .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                        .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                        .putLong(CampaignStore.KEY_WEBJUMP_AT, System.currentTimeMillis())
                        .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                        .putInt(CampaignStore.KEY_NO_NEW_SCANS, 0)
                        .putLong(CampaignStore.KEY_YGTAB_AT, 0L)
                        .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, false)
                        .putInt(CampaignStore.KEY_COORD_NAV_STEP, 0)
                        .putLong(CampaignStore.KEY_COORD_TAP_AT, 0L)
                        .putInt(CampaignStore.KEY_DRAWER_TAP_ATTEMPTS, 0)
                        .putInt(CampaignStore.KEY_FEEDTAB_TAP_ATTEMPTS, 0)
                        .apply();
                return;
            }
            // Imported group: open the app, navigate to Groups, then find the group by name.
            p.edit()
                    .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_NAV_GROUPS)
                    .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                    .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                    .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                    .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, false)
                    .putInt(CampaignStore.KEY_COORD_NAV_STEP, 0)
                    .putLong(CampaignStore.KEY_COORD_TAP_AT, 0L)
                    .putInt(CampaignStore.KEY_DRAWER_TAP_ATTEMPTS, 0)
                    .putInt(CampaignStore.KEY_FEEDTAB_TAP_ATTEMPTS, 0)
                    .apply();
            intent = context.getPackageManager().getLaunchIntentForPackage(selectedPackage);
            if (intent == null) {
                CampaignStore.stop(context);
                Toast.makeText(context, "Could not launch the selected Facebook app. Campaign stopped.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(selectedPackage);
        try {
            context.startActivity(intent);
        } catch (Exception missing) {
            CampaignStore.stop(context);
            Toast.makeText(context, "The selected Facebook app is not available. Campaign stopped.", Toast.LENGTH_LONG).show();
        }
    }

    /** v0.7.1 DIRECT JUMP: applink straight into the Facebook Groups screen.
     *  Replaces the fragile drawer walk (Feeds→Menu→See more→Groups) — that
     *  walk is what broke in every release so far, while deep links (already
     *  used for URL targets) never failed. Tries the https applink first (FB
     *  app resolves its own domain to the Groups screen), then the classic
     *  fb:// scheme. Returns false only if the FB app cannot open either. */
    static boolean openGroupsScreen(Context context, String fbPackage) {
        Uri[] candidates = {
                Uri.parse("https://www.facebook.com/groups/"),
                Uri.parse("fb://groups/"),
                Uri.parse("fb://groups")
        };
        for (Uri uri : candidates) {
            try {
                Intent jump = new Intent(Intent.ACTION_VIEW, uri);
                jump.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                jump.setPackage(fbPackage);
                context.startActivity(jump);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String expected = new ComponentName(context, PosterAccessibilityService.class).flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findByLabels(AccessibilityNodeInfo root, List<String> labels, boolean exact) {
        // v0.7.3 CCleaner-port: normalize each label ONCE per lookup, then
        // compare letter-cores (see ccNormalize). exact = letter-core
        // equality ("Menu." still matches "Menu"), fuzzy = letter-core
        // containment ("Write something…" hits "Write something").
        String[] nLabels = new String[labels.size()];
        for (int i = 0; i < labels.size(); i++) nLabels[i] = ccNormalize(labels.get(i));
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        long deadline = System.nanoTime() + LOOKUP_BUDGET_NS;
        while (!queue.isEmpty() && visited++ < MAX_LOOKUP_NODES
                && System.nanoTime() < deadline) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = node.getText() == null ? "" : node.getText().toString();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            String nText = ccNormalize(text);
            String nDesc = ccNormalize(desc);
            for (int li = 0; li < nLabels.length; li++) {
                String nLabel = nLabels[li];
                if (nLabel.isEmpty()) continue;
                if ((exact && (nText.equals(nLabel) || nDesc.equals(nLabel))) ||
                        (!exact && (nText.contains(nLabel) || nDesc.contains(nLabel)))) {
                    return node;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        long deadline = System.nanoTime() + LOOKUP_BUDGET_NS;
        while (!queue.isEmpty() && visited++ < MAX_LOOKUP_NODES
                && System.nanoTime() < deadline) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isEditable() || "android.widget.EditText".contentEquals(node.getClassName())) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    /** Tap a control visible at a stable relative location in Facebook's UI. */
    private boolean tapRelative(AccessibilityNodeInfo root, float xFraction,
                                float yFraction, String label) {
        android.graphics.Rect win = new android.graphics.Rect();
        root.getBoundsInScreen(win);
        if (win.isEmpty()) return false;
        int x = win.left + Math.round(win.width() * xFraction);
        int y = win.top + Math.round(win.height() * yFraction);
        boolean ok = dispatchTap(x, y, 110);
        noteClick("coordinate", label, ok);
        return ok;
    }

    // v0.6.0 click engine — tap-FIRST, and it STAYS tap-first.
    // v0.5.0 rotated the mechanism on every click; that quietly reintroduced
    // the v0.4.6 failure: after one working touch-tap the NEXT click went to
    // the a11y path, and FB's Litho rows accept ACTION_CLICK with no visible
    // effect (silent no-op that still reports success). Now the real-finger
    // tap is always tried first; the a11y click is only a fallback for the
    // current click, and if the system REJECTS gestures three times in a row
    // (rare ROMs) the engine latches a11y-first instead of flip-flopping
    // between a working and a dead mechanism on alternating clicks.
    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        String label = safeLabel(node);
        // v0.7.3 CCleaner-port (event-freshness guard): same label tapped
        // successfully within one tick window = stale tree mid-transition —
        // suppress the duplicate tap, report the action as already done.
        long since = System.currentTimeMillis() - lastSuccessfulClickAt;
        if (since >= 0 && since < 750
                && ccNormalize(label).equals(ccNormalize(lastSuccessfulClickLabel))) {
            noteClick("dup-guard", label, true);
            return true;
        }
        // v0.7.3 CCleaner-port (click-target resolution): Litho rows expose
        // the click handler on the clickable row, not on the text label.
        node = resolveClickTarget(node);
        boolean ok;
        String how;
        int tapMs = tapStrokeMs();
        if (gestureBroken) {
            ok = clickA11y(node) || tapCenter(node, 3, tapMs);
            how = "a11y-first";
        } else if (tapCenter(node, 3, tapMs)) {
            ok = true;
            how = "touch-tap";
        } else {
            // gesture not accepted this tick — land the click via a11y first,
            // then still try a slightly longer tap as the last resort.
            ok = clickA11y(node) || tapCenter(node, 3, 220);
            how = "tap-rejected-fallback";
        }
        noteClick(how, label, ok);
        return ok;
    }

    /** v0.7.3 CCleaner-port (isFirstChildOfClickableParent +
     * getTopParentNode): a non-clickable text node that is the first
     * text-bearing child of a clickable parent should be clicked AT THE
     * PARENT — the row owns the handler and its center is the safe tap
     * target. If the pattern does not match, the original node is returned
     * unchanged (tapCenter's off-screen hard-fail stays authoritative). */
    private AccessibilityNodeInfo resolveClickTarget(AccessibilityNodeInfo node) {
        try {
            if (node == null || node.isClickable()) return node;
            AccessibilityNodeInfo parent = node.getParent();
            for (int i = 0; parent != null && i < 4; i++) {
                if (parent.isClickable() && isFirstTextChild(node, parent)) return parent;
                parent = parent.getParent();
            }
        } catch (Exception ignored) {
        }
        return node;
    }

    /** CCleaner validator: is `node` the first non-empty-text child of
     * `parent`? (Fallback text comparison because AccessibilityNodeInfo
     * equality can be unreliable across separately-fetched instances.) */
    private boolean isFirstTextChild(AccessibilityNodeInfo node, AccessibilityNodeInfo parent) {
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null) continue;
            CharSequence t = child.getText();
            CharSequence d = child.getContentDescription();
            boolean hasText = (t != null && t.length() > 0) || (d != null && d.length() > 0);
            if (hasText) {
                return child.equals(node) || (t != null && t.equals(node.getText()));
            }
        }
        return false;
    }

    private boolean clickA11y(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 5; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        // v0.7.3 CCleaner-port (processNodeClick tier 2): some nodes —
        // WebView composer fields, Litho buttons — reject a bare ACTION_CLICK
        // until they hold focus. Focus first, then click, before giving up.
        current = node;
        for (int i = 0; current != null && i < 5; i++) {
            if (current.isClickable() && focusAndClick(current)) return true;
            current = current.getParent();
        }
        return false;
    }

    /** v0.7.3 CCleaner-port (processNodeClick tier 2):
     * ACCESSIBILITY_FOCUS (or FOCUS) followed by ACTION_CLICK. */
    private boolean focusAndClick(AccessibilityNodeInfo node) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private String safeLabel(AccessibilityNodeInfo node) {
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String s = t != null && t.length() > 0 ? t.toString() : (d != null ? d.toString() : "");
        s = s.trim().replace('\n', ' ');
        if (s.length() > 20) s = s.substring(0, 20);
        return s;
    }

    private void noteClick(String how, String label, boolean ok) {
        try {
            // v0.7.3 CCleaner-port: record the freshness-guard timestamp on
            // every successful click (drives the dup-guard in clickNodeOrParent).
            if (ok) {
                lastSuccessfulClickAt = System.currentTimeMillis();
                lastSuccessfulClickLabel = label == null ? "" : label;
            }
            CampaignStore.prefs(this).edit()
                    .putString(CampaignStore.KEY_LAST_CLICK,
                            how + (ok ? " OK: " : " FAILED: ") + label)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    // ---- v0.6.0 diagnostics helpers (read by MainActivity, same process) ----

    static boolean isLive() {
        return connected;
    }

    static long lastEventMillis() {
        return lastEventAt;
    }

    static String lastGesture() {
        return lastGestureResult.isEmpty() ? "not tested yet" : lastGestureResult;
    }

    /** v0.6.3: last navigation-tick decision, shown on the app status line. */
    static String navTrace() {
        return lastNavTrace;
    }

    /** v0.6.4: last dispatched tap coordinates, e.g. "tap@(540,2100)". */
    static String lastTap() {
        return lastTapPoint;
    }

    /** v0.6.0 self-test: MainActivity asks the service to tap a point inside
     * the app's own window (the probe row). If the gesture engine works,
     * Android delivers a REAL click to that row and the row reports back via
     * noteSelfTestHit() — end-to-end proof that service + gestures + touch
     * delivery all work on this device. */
    static boolean requestSelfTest(int x, int y) {
        if (instance == null) return false;
        lastGestureResult = "self-test dispatching...";
        return instance.dispatchTap(x, y, 110);
    }

    /** Called by MainActivity when the probe row receives the synthetic tap. */
    static void noteSelfTestHit() {
        lastGestureAt = System.currentTimeMillis();
        lastGestureResult = "self-test CLICK RECEIVED - engine OK";
        tapFailStreak = 0;
        gestureBroken = false;
    }

    /**
     * Dispatch a real touch tap at the visible center of the node. If the
     * node itself is degenerate (tiny), walk up a few parents. v0.6.4: a
     * node/parent FULLY outside the window is a HARD FAIL — climbing to its
     * parent lands on some on-screen container (drawer overlay, window root)
     * and the container's center-tap is a random tap in the middle of the
     * screen (Video/Reels used to open that way). Returns true only if the
     * system accepted the gesture.
     */
    private boolean tapCenter(AccessibilityNodeInfo node, int hops, int durationMs) {
        android.graphics.Rect clip = new android.graphics.Rect();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) root.getBoundsInScreen(clip);
        AccessibilityNodeInfo cur = node;
        for (int i = 0; cur != null && i <= hops; i++) {
            android.graphics.Rect r = new android.graphics.Rect();
            cur.getBoundsInScreen(r);
            if (!clip.isEmpty() && !android.graphics.Rect.intersects(clip, r)) {
                return false; // off-screen: NEVER climb to a random container
            }
            if (!clip.isEmpty()) r.intersect(clip); // visible-part center for partial nodes
            if (r.width() > 8 && r.height() > 8) {
                return dispatchTap(r.centerX(), r.centerY(), durationMs);
            }
            cur = cur.getParent(); // degenerate size only — parent of a tiny node is its row
        }
        return false;
    }

    /**
     * Finger tap of the given duration. If the system CANCELS the gesture
     * (overlapping gesture, window transition), retry once after 300ms.
     */
    private boolean dispatchTap(int x, int y, int durationMs) {
        lastTapPoint = "tap@(" + x + "," + y + ")";
        android.graphics.Path pt = new android.graphics.Path();
        pt.moveTo(x, y);
        pt.lineTo(x, y);
        final GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(pt, 0, durationMs))
                .build();
        final Handler h = new Handler(Looper.getMainLooper());
        // v0.6.0: the boolean return of dispatchGesture only means the system
        // QUEUED the gesture — completion/cancellation arrives via callback
        // and is recorded so the in-app card can show whether real taps
        // actually land on this ROM (accepted-but-cancelled was invisible).
        GestureResultCallback cb = new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                lastGestureAt = System.currentTimeMillis();
                lastGestureResult = "tap completed";
                tapFailStreak = 0;
            }

            @Override
            public void onCancelled(GestureDescription g) {
                // one retry — transient cancels (window transition) happen
                h.postDelayed(() -> dispatchGesture(g, new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g2) {
                        lastGestureAt = System.currentTimeMillis();
                        lastGestureResult = "tap completed";
                        tapFailStreak = 0;
                    }

                    @Override
                    public void onCancelled(GestureDescription g2) {
                        lastGestureResult = "tap CANCELLED twice";
                        tapFailStreak++;
                        if (tapFailStreak >= 3) gestureBroken = true;
                    }
                }, h), 300);
            }
        };
        boolean accepted = dispatchGesture(gesture, cb, h);
        if (!accepted) {
            lastGestureResult = "tap REJECTED by system";
            tapFailStreak++;
            if (tapFailStreak >= 3) gestureBroken = true;
        }
        return accepted;
    }

    private boolean matchesPostLabel(AccessibilityEvent event) {
        // v0.7.3 CCleaner-port: letter-core equality ("Post!", "post ",
        // localized punctuation variants all match).
        for (CharSequence value : event.getText()) {
            if (value == null) continue;
            String nv = ccNormalize(value.toString());
            for (String label : POST_LABELS) {
                if (nv.equals(ccNormalize(label))) return true;
            }
        }
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return false;
        String text = source.getText() == null ? "" : source.getText().toString();
        String description = source.getContentDescription() == null ? "" : source.getContentDescription().toString();
        String nText = ccNormalize(text);
        String nDesc = ccNormalize(description);
        for (String label : POST_LABELS) {
            String nLabel = ccNormalize(label);
            if (nText.equals(nLabel) || nDesc.equals(nLabel)) return true;
        }
        return false;
    }

    private boolean stageTimedOut(SharedPreferences p, long timeoutMs) {
        long since = p.getLong(CampaignStore.KEY_STAGE_SINCE, 0L);
        return since > 0 && System.currentTimeMillis() - since > timeoutMs;
    }

    private boolean stageTimedOutSince(SharedPreferences p, long minAgeMs) {
        long since = p.getLong(CampaignStore.KEY_STAGE_SINCE, 0L);
        return since <= 0 || System.currentTimeMillis() - since > minAgeMs;
    }

    private void setStage(SharedPreferences p, String stage) {
        p.edit()
                .putString(CampaignStore.KEY_STAGE, stage)
                .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                .apply();
    }

    private void toast(String message) {
        handler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
