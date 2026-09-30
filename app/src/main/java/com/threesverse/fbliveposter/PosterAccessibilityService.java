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

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PosterAccessibilityService extends AccessibilityService {
    private static final Set<String> FACEBOOK_PACKAGES = new HashSet<>(Arrays.asList("com.facebook.katana", "com.facebook.lite"));
    private static final List<String> COMPOSER_LABELS = Arrays.asList(
            "Write something", "What's on your mind", "Create a public post", "Create post", "Say something"
    );
    private static final List<String> POST_LABELS = Arrays.asList("Post", "POST", "Publish");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean processing;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!FACEBOOK_PACKAGES.contains(event.getPackageName().toString())) return;
        SharedPreferences p = CampaignStore.prefs(this);
        if (!p.getBoolean(CampaignStore.KEY_RUNNING, false)) return;

        String stage = p.getString(CampaignStore.KEY_STAGE, "");
        if (CampaignStore.STAGE_PRESS_POST.equals(stage)
                && !p.getBoolean(CampaignStore.KEY_AUTO_POST, false)
                && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
                && matchesPostLabel(event)) {
            setStage(CampaignStore.STAGE_WAIT_NEXT);
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
        if (!p.getBoolean(CampaignStore.KEY_RUNNING, false)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String stage = p.getString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_COMPOSER);
        long lastAction = p.getLong(CampaignStore.KEY_LAST_ACTION, 0L);
        if (System.currentTimeMillis() - lastAction < 800) return;

        if (CampaignStore.STAGE_OPEN_GROUP.equals(stage) || CampaignStore.STAGE_OPEN_COMPOSER.equals(stage)) {
            AccessibilityNodeInfo composer = findByLabels(root, COMPOSER_LABELS, false);
            if (composer != null && clickNodeOrParent(composer)) {
                setStage(CampaignStore.STAGE_FILL_COMPOSER);
                scheduleProcess(1400);
            }
            return;
        }

        if (CampaignStore.STAGE_FILL_COMPOSER.equals(stage)) {
            AccessibilityNodeInfo editable = findEditable(root);
            if (editable != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, CampaignStore.postText(this));
                if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    setStage(CampaignStore.STAGE_PRESS_POST);
                    toast(p.getBoolean(CampaignStore.KEY_AUTO_POST, false) ? "Message ready — posting…" : "Message ready — Post manually press karein");
                    if (p.getBoolean(CampaignStore.KEY_AUTO_POST, false)) scheduleProcess(1200);
                }
            }
            return;
        }

        if (CampaignStore.STAGE_PRESS_POST.equals(stage) && p.getBoolean(CampaignStore.KEY_AUTO_POST, false)) {
            AccessibilityNodeInfo post = findByLabels(root, POST_LABELS, true);
            if (post != null && clickNodeOrParent(post)) {
                setStage(CampaignStore.STAGE_WAIT_NEXT);
                int delaySeconds = p.getInt(CampaignStore.KEY_DELAY, 90);
                toast("Posted. Next group in " + delaySeconds + " seconds");
                handler.postDelayed(this::advanceToNextGroup, delaySeconds * 1000L);
            }
        }
    }

    private void advanceToNextGroup() {
        SharedPreferences p = CampaignStore.prefs(this);
        if (!p.getBoolean(CampaignStore.KEY_RUNNING, false)) return;
        int next = p.getInt(CampaignStore.KEY_INDEX, 0) + 1;
        if (CampaignStore.groupAt(this, next) == null) {
            CampaignStore.stop(this);
            toast("All groups completed");
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
        String group = CampaignStore.groupAt(context, p.getInt(CampaignStore.KEY_INDEX, 0));
        if (group == null) {
            CampaignStore.stop(context);
            return;
        }
        p.edit()
                .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_COMPOSER)
                .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                .apply();
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(group));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String selectedPackage = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
        if (!FACEBOOK_PACKAGES.contains(selectedPackage)) {
            CampaignStore.stop(context);
            Toast.makeText(context, "Facebook app select nahi hui. Campaign dobara Start karein.", Toast.LENGTH_LONG).show();
            return;
        }
        intent.setPackage(selectedPackage);
        try {
            context.startActivity(intent);
        } catch (Exception missing) {
            CampaignStore.stop(context);
            Toast.makeText(context, "Selected Facebook app available nahi. Campaign stopped.", Toast.LENGTH_LONG).show();
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
                        (!exact && (text.toLowerCase().contains(label.toLowerCase()) || desc.toLowerCase().contains(label.toLowerCase())))) {
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

    private void setStage(String stage) {
        CampaignStore.prefs(this).edit()
                .putString(CampaignStore.KEY_STAGE, stage)
                .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                .apply();
    }

    private void toast(String message) {
        handler.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
