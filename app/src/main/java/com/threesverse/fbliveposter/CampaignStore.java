package com.threesverse.fbliveposter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class CampaignStore {
    static final String PREFS = "poster_prefs";
    static final String KEY_LINK = "live_link";
    static final String KEY_MESSAGE = "message";
    static final String KEY_GROUPS = "groups";              // legacy manual URL text (kept for migration)
    static final String KEY_TARGETS = "targets_json";       // [{"t":"url"|"name","v":"...","s":true|false}]
    static final String KEY_DELAY = "delay_seconds";
    static final String KEY_AUTO_POST = "auto_post";
    static final String KEY_FACEBOOK_PACKAGE = "facebook_package";
    static final String KEY_RUNNING = "running";
    static final String KEY_INDEX = "group_index";
    static final String KEY_STAGE = "stage";
    static final String KEY_LAST_ACTION = "last_action";
    static final String KEY_STAGE_SINCE = "stage_since";
    static final String KEY_SCAN_COUNT = "scan_count";
    static final String KEY_IMPORT_MODE = "import_mode";
    static final String KEY_IMPORT_RESULT = "import_result_json";

    static final String STAGE_OPEN_GROUP = "open_group";
    static final String STAGE_NAV_GROUPS = "nav_groups";    // find + tap the Groups entry inside Facebook
    static final String STAGE_FIND_GROUP = "find_group";    // find + tap the selected group by name
    static final String STAGE_OPEN_COMPOSER = "open_composer";
    static final String STAGE_FILL_COMPOSER = "fill_composer";
    static final String STAGE_PRESS_POST = "press_post";
    static final String STAGE_WAIT_NEXT = "wait_next";
    static final String STAGE_IMPORT_SCAN = "import_scan";  // read visible group rows and scroll

    static final String TYPE_URL = "url";
    static final String TYPE_NAME = "name";

    private CampaignStore() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> parseGroups(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        for (String line : raw.split("\\r?\\n")) {
            String value = line.trim();
            if (value.matches("https?://(www\\.|m\\.)?facebook\\.com/groups/[^\\s]+/?")) {
                result.add(value);
            }
        }
        return result;
    }

    // ----- target list (checkbox model) -----

    static JSONArray loadTargets(Context context) {
        SharedPreferences p = prefs(context);
        String raw = p.getString(KEY_TARGETS, "");
        if (raw.isEmpty()) {
            // one-time migration from the legacy manual URL list
            List<String> legacy = parseGroups(p.getString(KEY_GROUPS, ""));
            if (legacy.isEmpty()) return new JSONArray();
            JSONArray migrated = new JSONArray();
            for (String url : legacy) {
                try {
                    JSONObject t = new JSONObject();
                    t.put("t", TYPE_URL);
                    t.put("v", url);
                    t.put("s", true);
                    migrated.put(t);
                } catch (Exception ignored) {
                }
            }
            p.edit().putString(KEY_TARGETS, migrated.toString()).apply();
            return migrated;
        }
        try {
            return new JSONArray(raw);
        } catch (Exception broken) {
            return new JSONArray();
        }
    }

    static void saveTargets(Context context, JSONArray targets) {
        prefs(context).edit().putString(KEY_TARGETS, targets.toString()).apply();
    }

    static List<JSONObject> selectedTargets(Context context) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray all = loadTargets(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t != null && t.optBoolean("s", false)) result.add(t);
        }
        return result;
    }

    static JSONObject selectedTargetAt(Context context, int index) {
        List<JSONObject> selected = selectedTargets(context);
        return index >= 0 && index < selected.size() ? selected.get(index) : null;
    }

    /** Adds url/name targets, skipping duplicates (type + value, case-insensitive). Returns count added. */
    static int mergeTargets(Context context, List<String[]> pairs) {
        JSONArray all = loadTargets(context);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t != null) seen.add(key(t.optString("t"), t.optString("v")));
        }
        int added = 0;
        for (String[] pair : pairs) {
            String k = key(pair[0], pair[1]);
            if (seen.contains(k)) continue;
            try {
                JSONObject t = new JSONObject();
                t.put("t", pair[0]);
                t.put("v", pair[1]);
                t.put("s", false);
                all.put(t);
                seen.add(k);
                added++;
            } catch (Exception ignored) {
            }
        }
        if (added > 0) saveTargets(context, all);
        return added;
    }

    private static String key(String type, String value) {
        return type + "|" + value.toLowerCase().trim();
    }

    // ----- import results -----

    static void putImportResult(Context context, List<String> names) {
        JSONArray arr = new JSONArray();
        for (String name : names) arr.put(name);
        prefs(context).edit().putString(KEY_IMPORT_RESULT, arr.toString()).apply();
    }

    static List<String> takeImportResult(Context context) {
        SharedPreferences p = prefs(context);
        List<String> names = new ArrayList<>();
        String raw = p.getString(KEY_IMPORT_RESULT, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i, "").trim();
                    if (!v.isEmpty()) names.add(v);
                }
            } catch (Exception ignored) {
            }
            p.edit().remove(KEY_IMPORT_RESULT).apply();
        }
        return names;
    }

    static String postText(Context context) {
        SharedPreferences p = prefs(context);
        String message = p.getString(KEY_MESSAGE, "").trim();
        String link = p.getString(KEY_LINK, "").trim();
        if (message.isEmpty()) return link;
        return link.isEmpty() ? message : message + "\n\n" + link;
    }

    static void stop(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putBoolean(KEY_IMPORT_MODE, false)
                .putString(KEY_STAGE, STAGE_OPEN_GROUP)
                .putInt(KEY_SCAN_COUNT, 0)
                .apply();
    }
}
