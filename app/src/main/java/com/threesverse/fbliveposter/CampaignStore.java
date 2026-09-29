package com.threesverse.fbliveposter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class CampaignStore {
    static final String PREFS = "poster_prefs";
    static final String KEY_LINK = "live_link";
    static final String KEY_MESSAGE = "message";
    static final String KEY_GROUPS = "groups";
    static final String KEY_DELAY = "delay_seconds";
    static final String KEY_AUTO_POST = "auto_post";
    static final String KEY_RUNNING = "running";
    static final String KEY_INDEX = "group_index";
    static final String KEY_STAGE = "stage";
    static final String KEY_LAST_ACTION = "last_action";

    static final String STAGE_OPEN_GROUP = "open_group";
    static final String STAGE_OPEN_COMPOSER = "open_composer";
    static final String STAGE_FILL_COMPOSER = "fill_composer";
    static final String STAGE_PRESS_POST = "press_post";
    static final String STAGE_WAIT_NEXT = "wait_next";

    private CampaignStore() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> parseGroups(String raw) {
        List<String> result = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String value = line.trim();
            if (value.matches("https?://(www\\.|m\\.)?facebook\\.com/groups/[^\\s]+/?")) {
                result.add(value);
            }
        }
        return result;
    }

    static String groupAt(Context context, int index) {
        List<String> groups = parseGroups(prefs(context).getString(KEY_GROUPS, ""));
        return index >= 0 && index < groups.size() ? groups.get(index) : null;
    }

    static String postText(Context context) {
        SharedPreferences p = prefs(context);
        String message = p.getString(KEY_MESSAGE, "I am live now — join me:").trim();
        String link = p.getString(KEY_LINK, "").trim();
        return message.isEmpty() ? link : message + "\n\n" + link;
    }

    static void stop(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString(KEY_STAGE, STAGE_OPEN_GROUP)
                .apply();
    }
}

