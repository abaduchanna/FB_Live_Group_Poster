package com.threesverse.fbliveposter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final Pattern FACEBOOK_URL = Pattern.compile("https?://(?:www\\.|m\\.)?facebook\\.com/\\S+", Pattern.CASE_INSENSITIVE);
    private static final String KEY_DARK_THEME = "ui_dark_theme";
    private static final int FB_BLUE = Color.rgb(24, 119, 242);
    private static final int FB_LIGHT_BG = Color.rgb(240, 242, 245);
    private static final int FB_DARK_BG = Color.rgb(24, 25, 26);
    private static final int FB_LIGHT_TEXT = Color.rgb(5, 5, 5);
    private static final int FB_DARK_TEXT = Color.rgb(228, 230, 235);
    /** Facebook apps that are not for posting — excluded from the chooser. */
    private static final List<String> EXCLUDED_PACKAGES = java.util.Arrays.asList(
            "com.facebook.orca", "com.facebook.mlite", "com.facebook.services",
            "com.facebook.appmanager", "com.facebook.system", "com.facebook.katana.proxy"
    );

    private EditText linkInput;
    private EditText messageInput;
    private EditText delayInput;
    private CheckBox autoPostInput;
    private CheckBox selectAllBox;
    private TextView status;
    private TextView facebookAppStatus;
    private TextView selectionCount;
    private LinearLayout groupListBox;
    private Button startButton;
    private boolean darkTheme;
    private boolean syncingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        darkTheme = resolveDarkTheme();
        setTheme(darkTheme ? R.style.AppThemeDark : R.style.AppThemeLight);
        super.onCreate(savedInstanceState);
        buildUi();
        loadSavedValues();
        acceptSharedLink(getIntent());
        requestNotificationsIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptSharedLink(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mergeImportResult();
        rebuildGroupList();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        root.setBackgroundColor(darkTheme ? FB_DARK_BG : FB_LIGHT_BG);
        scroll.addView(root);

        TextView title = text("FB Live Group Poster", 26, true);
        title.setTextColor(FB_BLUE);
        root.addView(title);
        root.addView(text("Facebook se apni groups list import karein, groups select karein, aur selected groups par live link auto-post karein. Facebook password kabhi nahi mangta.", 15, false));

        Button themeToggle = button(darkTheme ? "Light theme" : "Dark theme");
        themeToggle.setContentDescription(darkTheme ? "Switch to Facebook light theme" : "Switch to Facebook dark theme");
        themeToggle.setOnClickListener(v -> {
            CampaignStore.prefs(this).edit().putBoolean(KEY_DARK_THEME, !darkTheme).apply();
            recreate();
        });
        root.addView(themeToggle);

        facebookAppStatus = text("Facebook app: Start par choose hogi", 14, true);
        facebookAppStatus.setPadding(0, dp(12), 0, 0);
        root.addView(facebookAppStatus);

        root.addView(label("1. Live link"));
        linkInput = input("Facebook live link", false);
        root.addView(linkInput);

        root.addView(label("2. Message (khaali chhoda to sirf live link post hoga)"));
        messageInput = input("Message — optional", true);
        root.addView(messageInput);

        root.addView(label("3. Groups — Facebook se import karein, phir select karein"));
        Button importButton = button("Import groups from Facebook");
        importButton.setOnClickListener(v -> startImport());
        root.addView(importButton);

        LinearLayout selectionRow = new LinearLayout(this);
        selectionRow.setOrientation(LinearLayout.HORIZONTAL);
        selectionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        selectionRow.setPadding(0, dp(8), 0, dp(4));
        selectAllBox = new CheckBox(this);
        selectAllBox.setText("Select all");
        selectAllBox.setTextSize(15);
        selectAllBox.setTextColor(darkTheme ? FB_DARK_TEXT : FB_LIGHT_TEXT);
        selectAllBox.setOnCheckedChangeListener(this::onSelectAllChanged);
        selectionRow.addView(selectAllBox);
        selectionCount = text("", 13, false);
        selectionCount.setPadding(dp(12), 0, 0, 0);
        selectionRow.addView(selectionCount);
        root.addView(selectionRow);

        // NO inner ScrollView here: a scrollable box nested inside the page's
        // own ScrollView never scrolls on real devices (the outer view eats the
        // drag — with 136 imported groups the user could only ever see the
        // first ~10). Rows render straight into the page scroll instead, so the
        // whole list is reachable by scrolling the screen itself.
        groupListBox = new LinearLayout(this);
        groupListBox.setOrientation(LinearLayout.VERTICAL);
        groupListBox.setBackgroundColor(darkTheme ? Color.rgb(30, 31, 34) : Color.WHITE);
        groupListBox.setPadding(dp(10), dp(6), dp(10), dp(6));
        root.addView(groupListBox);

        LinearLayout listButtons = new LinearLayout(this);
        listButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button saveSelectionButton = button("Save selection");
        saveSelectionButton.setOnClickListener(v -> saveSelection());
        LinearLayout.LayoutParams thirdA = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        thirdA.setMargins(0, dp(8), dp(4), 0);
        saveSelectionButton.setLayoutParams(thirdA);
        listButtons.addView(saveSelectionButton);
        Button manualButton = button("Manual URLs");
        manualButton.setOnClickListener(v -> showManualUrlDialog());
        LinearLayout.LayoutParams thirdB = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        thirdB.setMargins(0, dp(8), dp(4), 0);
        manualButton.setLayoutParams(thirdB);
        listButtons.addView(manualButton);
        Button clearButton = button("Clear list");
        clearButton.setOnClickListener(v -> confirmClearList());
        LinearLayout.LayoutParams thirdC = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        thirdC.setMargins(dp(4), dp(8), 0, 0);
        clearButton.setLayoutParams(thirdC);
        listButtons.addView(clearButton);
        root.addView(listButtons);

        root.addView(label("4. Safety delay"));
        delayInput = input("Delay between groups (seconds)", false);
        delayInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(delayInput);

        autoPostInput = new CheckBox(this);
        autoPostInput.setText("Post button automatically press kare (experimental)");
        autoPostInput.setTextColor(darkTheme ? FB_DARK_TEXT : FB_LIGHT_TEXT);
        autoPostInput.setPadding(0, dp(10), 0, dp(10));
        root.addView(autoPostInput);

        Button accessibility = button("Enable Accessibility service");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        startButton = button("Auto post to selected groups (0)");
        startButton.setOnClickListener(v -> validateAndConfirm());
        root.addView(startButton);

        Button stop = button("Stop campaign");
        stop.setOnClickListener(v -> {
            CampaignStore.stop(this);
            refreshStatus();
            Toast.makeText(this, "Campaign stopped", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);

        status = text("", 14, true);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status);

        TextView warning = text("Important: import aap ke Facebook account ki visible groups dikhata hai — jo group aap me shamil nahi usay uncheck karein. Sirf un groups mein post karein jahan promotion/live links allowed hon. Same phone par Facebook Live background mein jane se stream ruk sakti hai; doosra phone zyada reliable hai.", 13, false);
        warning.setTextColor(darkTheme ? Color.rgb(176, 179, 184) : Color.rgb(101, 103, 107));
        warning.setPadding(0, dp(18), 0, 0);
        root.addView(warning);

        setContentView(scroll);
    }

    private void loadSavedValues() {
        SharedPreferences p = CampaignStore.prefs(this);
        linkInput.setText(p.getString(CampaignStore.KEY_LINK, ""));
        messageInput.setText(""); // message is always empty by default
        delayInput.setText(String.valueOf(p.getInt(CampaignStore.KEY_DELAY, 90)));
        autoPostInput.setChecked(p.getBoolean(CampaignStore.KEY_AUTO_POST, false));
        String savedPackage = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
        if (!savedPackage.isEmpty()) {
            facebookAppStatus.setText("Last used: " + facebookLabel(savedPackage) + " — Start par dobara choose hogi");
        }
    }

    // ================= GROUP LIST UI =================

    private void rebuildGroupList() {
        if (groupListBox == null) return;
        syncingUi = true;
        JSONArray targets = CampaignStore.loadTargets(this);
        groupListBox.removeAllViews();

        if (targets.length() == 0) {
            TextView empty = text("Abhi koi group nahi hai. Pehle \"Import groups from Facebook\" dabayein ya manual URLs add karein.", 13, false);
            empty.setPadding(0, dp(8), 0, dp(8));
            groupListBox.addView(empty);
            selectAllBox.setEnabled(false);
            selectAllBox.setChecked(false);
            updateSelectionUi();
            syncingUi = false;
            return;
        }

        int checkedCount = 0;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject t = targets.optJSONObject(i);
            if (t == null) continue;
            boolean selected = t.optBoolean("s", false);
            if (selected) checkedCount++;
            CheckBox row = new CheckBox(this);
            boolean isUrl = CampaignStore.TYPE_URL.equals(t.optString("t", CampaignStore.TYPE_NAME));
            String value = t.optString("v", "");
            row.setText(isUrl ? compactUrl(value) : value);
            row.setTextSize(14);
            row.setTextColor(darkTheme ? FB_DARK_TEXT : FB_LIGHT_TEXT);
            row.setChecked(selected);
            final JSONObject target = t;
            row.setOnCheckedChangeListener((buttonView, isChecked) -> {
                JSONArray all = CampaignStore.loadTargets(this);
                for (int j = 0; j < all.length(); j++) {
                    JSONObject item = all.optJSONObject(j);
                    if (item != null && item.optString("t", "").equals(target.optString("t", ""))
                            && item.optString("v", "").equalsIgnoreCase(target.optString("v", ""))) {
                        try {
                            item.put("s", isChecked);
                        } catch (Exception ignored) {
                        }
                    }
                }
                CampaignStore.saveTargets(this, all);
                updateSelectionUi();
            });
            groupListBox.addView(row);
        }

        selectAllBox.setEnabled(true);
        selectAllBox.setChecked(checkedCount == targets.length() && targets.length() > 0);
        updateSelectionUi();
        syncingUi = false;
    }

    private void onSelectAllChanged(CompoundButton buttonView, boolean isChecked) {
        if (syncingUi) return;
        JSONArray all = CampaignStore.loadTargets(this);
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t != null) {
                try {
                    t.put("s", isChecked);
                } catch (Exception ignored) {
                }
            }
        }
        CampaignStore.saveTargets(this, all);
        rebuildGroupList();
    }

    private void updateSelectionUi() {
        JSONArray all = CampaignStore.loadTargets(this);
        int total = all.length();
        int selected = 0;
        for (int i = 0; i < total; i++) {
            JSONObject t = all.optJSONObject(i);
            if (t != null && t.optBoolean("s", false)) selected++;
        }
        selectionCount.setText(selected + " of " + total + " selected");
        startButton.setText("Auto post to selected groups (" + selected + ")");
    }

    private void mergeImportResult() {
        List<String> names = CampaignStore.takeImportResult(this);
        if (names.isEmpty()) return;
        List<String[]> pairs = new ArrayList<>();
        for (String name : names) pairs.add(new String[]{CampaignStore.TYPE_NAME, name});
        int added = CampaignStore.mergeTargets(this, pairs);
        Toast.makeText(this, added + " nayi groups import ho gayin — ab select karein", Toast.LENGTH_LONG).show();
    }

    /** Explicit save point (owner request: "selection ka save button ho — next
        time select na karna pare"). Every checkbox toggle already writes
        through to storage, so the selection DOES survive app restarts; this
        button re-writes it and confirms the exact count in words, so dealers
        can trust it without guessing. */
    private void saveSelection() {
        JSONArray all = CampaignStore.loadTargets(this);
        CampaignStore.saveTargets(this, all);
        int selected = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.optJSONObject(i);
            if (t != null && t.optBoolean("s", false)) selected++;
        }
        Toast.makeText(this, selected == 0
                ? "Koi group select nahi hai — pehle groups check karein, phir Save dabayein"
                : selected + " groups ka selection save ho gaya — app yaad rakhegi, dobara select nahi karna parega",
                Toast.LENGTH_LONG).show();
    }

    private void showManualUrlDialog() {
        final EditText input = new EditText(this);
        input.setHint("https://www.facebook.com/groups/...\n(har line par aik URL)");
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(4);

        new AlertDialog.Builder(this)
                .setTitle("Manual group URLs")
                .setMessage("Facebook URL wale groups bhi list mein add ho jayenge (import ki groups ke sath).")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    List<String[]> pairs = new ArrayList<>();
                    for (String url : CampaignStore.parseGroups(input.getText().toString())) {
                        pairs.add(new String[]{CampaignStore.TYPE_URL, url});
                    }
                    int added = CampaignStore.mergeTargets(this, pairs);
                    Toast.makeText(this, added + " URLs add huin", Toast.LENGTH_SHORT).show();
                    rebuildGroupList();
                })
                .show();
    }

    private void confirmClearList() {
        new AlertDialog.Builder(this)
                .setTitle("Clear group list?")
                .setMessage("Imported aur manual dono lists hat jayengi.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    CampaignStore.saveTargets(this, new JSONArray());
                    rebuildGroupList();
                })
                .show();
    }

    // ================= FACEBOOK APP CHOOSER =================

    private void startImport() {
        if (!PosterAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "Pehle Accessibility service enable karein", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        chooseFacebookApp(true, 0);
    }

    private void chooseFacebookApp(final boolean forImport, final int delay) {
        List<String> packages = installedFacebookPackages();
        if (packages.isEmpty()) {
            Toast.makeText(this, "Facebook app install aur login karein — Facebook, Facebook Lite ya clone, sab chalenge", Toast.LENGTH_LONG).show();
            return;
        }

        // Framework setSingleChoiceItems() collapses/hides the list when setMessage()
        // is also set on several OEM skins — so the app rows are built by hand and
        // passed as a custom view. Renders identically on every device.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        panel.setPadding(pad, dp(4), pad, dp(4));

        TextView hint = text("Facebook, Facebook Lite ya clone — har campaign se pehle aap choose karte hain. App kabhi khud select nahi karti.", 14, false);
        hint.setPadding(0, 0, 0, dp(8));
        panel.addView(hint);

        java.util.Map<Integer, Integer> idToIndex = new java.util.HashMap<>();
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < packages.size(); i++) {
            RadioButton row = new RadioButton(this);
            int rowId = View.generateViewId();
            row.setId(rowId);
            idToIndex.put(rowId, i);
            row.setText(facebookLabel(packages.get(i)));
            row.setTextSize(16);
            row.setTextColor(darkTheme ? FB_DARK_TEXT : FB_LIGHT_TEXT);
            row.setPadding(dp(6), dp(10), dp(6), dp(10));
            group.addView(row);
        }
        panel.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Kaunsi Facebook app use karni hai?")
                .setView(panel)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(forImport ? "Import in selected" : "Use selected", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            final Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(false);
            confirm.setOnClickListener(v -> {
                Integer chosen = idToIndex.get(group.getCheckedRadioButtonId());
                if (chosen == null) return;
                dialog.dismiss();
                if (forImport) {
                    beginImport(packages.get(chosen));
                } else {
                    startCampaign(delay, packages.get(chosen));
                }
            });
        });
        dialog.show();
        group.setOnCheckedChangeListener((g, checkedId) ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true));
    }

    /** Finds every installed Facebook-family app: official, Lite and clones/mods. */
    private List<String> installedFacebookPackages() {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchables = getPackageManager().queryIntentActivities(main, 0);
        for (ResolveInfo info : launchables) {
            if (info == null || info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            if (EXCLUDED_PACKAGES.contains(pkg)) continue;
            String label;
            try {
                label = String.valueOf(info.loadLabel(getPackageManager()));
            } catch (Exception broken) {
                label = "";
            }
            String pkgLow = pkg.toLowerCase();
            String labelLow = label.toLowerCase();
            boolean isFacebookFamily = pkgLow.contains("facebook")
                    || pkgLow.startsWith("com.fb")
                    || labelLow.contains("facebook");
            if (isFacebookFamily) found.add(pkg);
        }
        // Make sure the official apps are always considered, in a stable order.
        List<String> result = new ArrayList<>();
        for (String known : new String[]{"com.facebook.katana", "com.facebook.lite"}) {
            try {
                getPackageManager().getApplicationInfo(known, 0);
                result.add(known);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not installed.
            }
        }
        for (String pkg : found) {
            if (!result.contains(pkg)) result.add(pkg);
        }
        return result;
    }

    private String facebookLabel(String packageName) {
        if ("com.facebook.katana".equals(packageName)) return "Facebook";
        if ("com.facebook.lite".equals(packageName)) return "Facebook Lite";
        try {
            CharSequence label = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) return label.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // Fall through to raw package name.
        }
        return packageName;
    }

    // ================= CAMPAIGN =================

    private void validateAndConfirm() {
        String link = linkInput.getText().toString().trim();
        List<JSONObject> selected = CampaignStore.selectedTargets(this);
        int delay;
        try {
            delay = Integer.parseInt(delayInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            delay = 90;
        }

        if (!FACEBOOK_URL.matcher(link).find()) {
            linkInput.setError("Valid Facebook link paste karein");
            return;
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Pehle groups import karein aur kam az kam aik select karein", Toast.LENGTH_LONG).show();
            return;
        }
        if (delay < 45) {
            delayInput.setError("Minimum 45 seconds rakhein");
            return;
        }

        final int safeDelay = delay;
        String mode = autoPostInput.isChecked()
                ? "App composer fill karke Post bhi press karegi."
                : "App composer fill karegi; Post aap khud press karenge.";
        new AlertDialog.Builder(this)
                .setTitle("Start for " + selected.size() + " groups?")
                .setMessage(mode + "\n\nGroup rules aur Facebook restrictions ki zimmedari user ki hai.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> chooseFacebookApp(false, safeDelay))
                .show();
    }

    private void beginImport(String packageName) {
        SharedPreferences p = CampaignStore.prefs(this);
        p.edit()
                .putBoolean(CampaignStore.KEY_IMPORT_MODE, true)
                .putBoolean(CampaignStore.KEY_RUNNING, false)
                .putString(CampaignStore.KEY_FACEBOOK_PACKAGE, packageName)
                .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_NAV_GROUPS)
                .putLong(CampaignStore.KEY_STAGE_SINCE, System.currentTimeMillis())
                .putLong(CampaignStore.KEY_LAST_ACTION, System.currentTimeMillis())
                .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                .putBoolean(CampaignStore.KEY_NAV_FEEDS_DONE, false)
                .apply();

        facebookAppStatus.setText("Import app: " + facebookLabel(packageName));
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            CampaignStore.stop(this);
            Toast.makeText(this, "Selected Facebook app launch nahi hui", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
            Toast.makeText(this, "Facebook khulne par app khud navigate karegi: Feeds → Menu drawer → Groups → Your groups. Facebook ko khula chhore dein — list ban jaye to is app par wapas aayen.", Toast.LENGTH_LONG).show();
        } catch (Exception failed) {
            CampaignStore.stop(this);
            Toast.makeText(this, "Facebook app launch fail — dobara koshish karein", Toast.LENGTH_LONG).show();
        }
    }

    private void startCampaign(int delay, String facebookPackage) {
        long now = System.currentTimeMillis();
        CampaignStore.prefs(this).edit()
                .putString(CampaignStore.KEY_LINK, linkInput.getText().toString().trim())
                .putString(CampaignStore.KEY_MESSAGE, messageInput.getText().toString().trim())
                .putString(CampaignStore.KEY_GROUPS, "") // legacy field no longer used
                .putInt(CampaignStore.KEY_DELAY, delay)
                .putBoolean(CampaignStore.KEY_AUTO_POST, autoPostInput.isChecked())
                .putBoolean(CampaignStore.KEY_IMPORT_MODE, false)
                .putString(CampaignStore.KEY_FACEBOOK_PACKAGE, facebookPackage)
                .putBoolean(CampaignStore.KEY_RUNNING, true)
                .putInt(CampaignStore.KEY_INDEX, 0)
                .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_GROUP)
                .putLong(CampaignStore.KEY_LAST_ACTION, now)
                .putLong(CampaignStore.KEY_STAGE_SINCE, now)
                .putInt(CampaignStore.KEY_SCAN_COUNT, 0)
                .apply();

        facebookAppStatus.setText("Facebook app: " + facebookLabel(facebookPackage));

        if (!PosterAccessibilityService.isEnabled(this)) {
            CampaignStore.stop(this);
            Toast.makeText(this, "Pehle Accessibility service enable karein", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        PosterAccessibilityService.openCurrentGroup(this);
        refreshStatus();
    }

    private void acceptSharedLink(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null) return;
        Matcher matcher = FACEBOOK_URL.matcher(shared);
        if (matcher.find()) {
            linkInput.setText(matcher.group());
            Toast.makeText(this, "Facebook link received", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        SharedPreferences p = CampaignStore.prefs(this);
        boolean running = p.getBoolean(CampaignStore.KEY_RUNNING, false);
        boolean importing = p.getBoolean(CampaignStore.KEY_IMPORT_MODE, false);
        if (importing) {
            status.setText("Status: Facebook se groups scan ho rahi hain…");
            return;
        }
        int index = p.getInt(CampaignStore.KEY_INDEX, 0);
        int total = CampaignStore.selectedTargets(this).size();
        status.setText(running
                ? "Running: group " + Math.min(index + 1, total) + " of " + total
                : "Status: stopped / ready");
    }

    private String compactUrl(String url) {
        return url.replaceFirst("^https?://(www\\.|m\\.)?", "");
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private boolean resolveDarkTheme() {
        SharedPreferences p = CampaignStore.prefs(this);
        if (p.contains(KEY_DARK_THEME)) return p.getBoolean(KEY_DARK_THEME, false);
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private TextView label(String value) {
        TextView view = text(value, 14, true);
        view.setPadding(0, dp(18), 0, dp(6));
        return view;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(darkTheme ? FB_DARK_TEXT : FB_LIGHT_TEXT);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private EditText input(String hint, boolean multiline) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextSize(16);
        view.setTextColor(darkTheme ? FB_DARK_TEXT : FB_LIGHT_TEXT);
        view.setHintTextColor(darkTheme ? Color.rgb(176, 179, 184) : Color.rgb(101, 103, 107));
        view.setBackgroundTintList(ColorStateList.valueOf(darkTheme ? Color.rgb(176, 179, 184) : FB_BLUE));
        view.setSingleLine(!multiline);
        if (multiline) view.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(FB_BLUE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
