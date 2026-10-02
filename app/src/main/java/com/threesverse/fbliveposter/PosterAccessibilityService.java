package com.threesverse.fbliveposter;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
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
            "Write something", "What's on your mind", "Create a public post", "Create post", "Say something"
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
    private static final int IDLE_SCANS_TO_STOP = 4;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean processing;
    private final Set<String> importedNames = new LinkedHashSet<>();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
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

    private boolean isTrackedPackage(String pkg, SharedPreferences p) {
        if (pkg == null) return false;
        String selected = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
        return pkg.toLowerCase().contains("facebook") || pkg.equals(selected);
    }

    private void scheduleProcess(long delayMs) {
        if (processing) return;
        processing = true;
        handler.postDelayed(() -> {
            try {
                processCurrentScreen();
            } finally {
                processing = false;
            }
        }, delayMs);
    }

    private void processCurrentScreen() {
        SharedPreferences p = CampaignStore.prefs(this);
        boolean running = p.getBoolean(CampaignStore.KEY_RUNNING, false);
        boolean importing = p.getBoolean(CampaignStore.KEY_IMPORT_MODE, false);
        if (!running && !importing) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

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
            if (stageTimedOut(p, IMPORT_TIMEOUT_MS)) {
                finishImport(p);
                return;
            }
            navigateTowardGroups(p, root);
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
                    finishImport(p); // 4 ticks me koi naya naam nahi = list ka bottom
                    return;
                }
            }
            if (scans < MAX_IMPORT_SCANS && scrollForward(p, root)) {
                p.edit().putInt(CampaignStore.KEY_SCAN_COUNT, scans + 1).apply();
                scheduleProcess(idleBackoff(p));
            } else {
                finishImport(p);
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
        while (!queue.isEmpty()) {
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

        if (CampaignStore.STAGE_OPEN_GROUP.equals(stage)
                || CampaignStore.STAGE_OPEN_COMPOSER.equals(stage)) {
            AccessibilityNodeInfo composer = findByLabels(root, COMPOSER_LABELS, false);
            if (composer != null && clickNodeOrParent(composer)) {
                setStage(p, CampaignStore.STAGE_FILL_COMPOSER);
                scheduleProcess(1400);
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
                setStage(p, CampaignStore.STAGE_FIND_GROUP);
                return;
            }
            navigateTowardGroups(p, root);
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
            if (row != null && clickNodeOrParent(row)) {
                e.remove(CampaignStore.KEY_SCAN_COUNT).apply();
                setStage(p, CampaignStore.STAGE_OPEN_COMPOSER);
                toast("Found group: " + name);
                scheduleProcess(1500);
            } else {
                int scans = p.getInt(CampaignStore.KEY_SCAN_COUNT, 0);
                if (scrollForward(root) && scans < MAX_GROUP_SCANS) {
                    // Lambi list (136 groups) me group tak scroll karne me 30+ screens
                    // lag sakte hain — har successful scroll progress hai, stage
                    // timeout refresh karo warna 45s me "skip" ho jata tha.
                    e.putInt(CampaignStore.KEY_SCAN_COUNT, scans + 1)
                            .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                            .apply();
                    scheduleProcess(1100);
                } else {
                    e.remove(CampaignStore.KEY_SCAN_COUNT).apply();
                    toast("Group not in your list: " + name + " — skipping");
                    advanceToNextGroup();
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
                }
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
            }
        }
    }

    // Owner flow v0.4.4 (device-audit): FB open hone ke baad pehle Feeds tab
    // click (koi bhi purani screen khuli ho to clean feed pe aa jate hain), phir
    // side drawer kholo, drawer me Groups entry dhoond ke click karo. Purana
    // order (Groups → See more → scroll → Menu) real devices pe stall hota tha
    // kyunki feed ke "See more" post-expanders ghalat click ho jate the.
    private void navigateTowardGroups(SharedPreferences p, AccessibilityNodeInfo root) {
        // STEP 0: Groups pehle se tappable? (drawer shortcut, Menu grid, ya
        // bottom-nav tab) — sab se seedha raasta, baki steps skip.
        AccessibilityNodeInfo groups = findByLabels(root, GROUPS_LABELS, true);
        if (groups != null && clickNodeOrParent(groups)) {
            arriveAtGroupsScreen(p);
            return;
        }

        boolean menuOpen = onMenuScreen(root);

        // STEP 1: Feeds pehle — clean known screen jahan Menu/drawer reliably
        // milta hai. Drawer already khula ho to ye step skip (warna "Feeds"
        // shortcut drawer se wapas feed pe le jata hai).
        if (!p.getBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, false) && !menuOpen) {
            AccessibilityNodeInfo feeds = findByLabels(root, FEEDS_LABELS, true);
            if (feeds != null && clickNodeOrParent(feeds)) {
                p.edit()
                        .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, true)
                        .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                        .apply();
                scheduleProcess(1500);
                return;
            }
            // Feeds tab nahi mila (already feed pe hain, ya is screen pe bottom
            // nav nahi) — thoda intezar, phir aage badh jao.
            if (!stageTimedOutSince(p, 3500L)) return;
            p.edit().putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, true).apply();
        }

        if (menuOpen) {
            // STEP 2b: drawer/Menu screen khula hai — Groups entry dhoondo:
            // pehle "See more" (shortcuts collapsed hote hain), phir drawer scroll.
            AccessibilityNodeInfo seeMore = findByLabels(root, SEE_MORE_LABELS, true);
            if (seeMore != null && stageTimedOutSince(p, 1500L) && clickNodeOrParent(seeMore)) {
                p.edit().putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis()).apply();
                return;
            }
            // Even expanded, the Groups entry can sit below the drawer fold —
            // scroll the drawer to reveal it.
            if (stageTimedOutSince(p, 2200L) && scrollForward(root)) {
                p.edit().putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis()).apply();
            }
            return;
        }

        // STEP 2a: side drawer kholo (Menu tab / hamburger) — rate-limited
        // (double-tap drawer band kar deta).
        if (stageTimedOutSince(p, 2500L)) {
            AccessibilityNodeInfo menu = findByLabels(root, MENU_LABELS, true);
            if (menu == null) menu = findByLabels(root, MENU_LABELS, false);
            if (menu != null && clickNodeOrParent(menu)) {
                p.edit().putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis()).apply();
                return;
            }
        }

        // STEP 3: dead screen (drawer open twice, stray dialog…) — BACK resets
        // the navigation so the next tick can tap Menu again instead of waiting
        // out the whole stage timeout.
        if (stageTimedOutSince(p, 12000L)) {
            p.edit().putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis()).apply();
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    /** Groups entry click ho gayi — import me "Your groups" tab pe jana hai (owner:
     *  "your groups main jao"), campaign me seedha group-name scan. */
    private void arriveAtGroupsScreen(SharedPreferences p) {
        setStage(p, p.getBoolean(CampaignStore.KEY_IMPORT_MODE, false)
                ? CampaignStore.STAGE_PICK_TAB
                : CampaignStore.STAGE_FIND_GROUP);
        p.edit()
                .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                .putInt(CampaignStore.KEY_NO_NEW_SCANS, 0)
                .putInt(CampaignStore.KEY_SCROLL_INDEX, 0)
                .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, false)
                .apply();
    }

    private boolean onMenuScreen(AccessibilityNodeInfo root) {
        return findByLabels(root, MENU_SCREEN_HINTS, false) != null;
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
        while (!queue.isEmpty()) {
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

    /** Idle (naya naam nahi) ticks pe lamba wait — slow network pe content load hone ka time. */
    private long idleBackoff(SharedPreferences p) {
        return p.getInt(CampaignStore.KEY_NO_NEW_SCANS, 0) > 0 ? 2000L : 1100L;
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
            // Imported group: open the app, navigate to Groups, then find the group by name.
            p.edit()
                    .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_NAV_GROUPS)
                    .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                    .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                    .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                    .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, false)
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
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            String text = node.getText() == null ? "" : node.getText().toString().trim();
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
            for (String label : labels) {
                if ((exact && (text.equalsIgnoreCase(label) || desc.equalsIgnoreCase(label))) ||
                        (!exact && (text.toLowerCase().contains(label.toLowerCase())
                                || desc.toLowerCase().contains(label.toLowerCase())))) {
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
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isEditable() || "android.widget.EditText".contentEquals(node.getClassName())) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 5; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean matchesPostLabel(AccessibilityEvent event) {
        for (CharSequence value : event.getText()) {
            if (value == null) continue;
            for (String label : POST_LABELS) {
                if (value.toString().trim().equalsIgnoreCase(label)) return true;
            }
        }
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return false;
        String text = source.getText() == null ? "" : source.getText().toString().trim();
        String description = source.getContentDescription() == null ? "" : source.getContentDescription().toString().trim();
        for (String label : POST_LABELS) {
            if (text.equalsIgnoreCase(label) || description.equalsIgnoreCase(label)) return true;
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
