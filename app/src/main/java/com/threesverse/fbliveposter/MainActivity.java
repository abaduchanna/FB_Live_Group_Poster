package com.threesverse.fbliveposter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.ArrayList;
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

    private EditText linkInput;
    private EditText messageInput;
    private EditText groupsInput;
    private EditText delayInput;
    private CheckBox autoPostInput;
    private TextView status;
    private TextView facebookAppStatus;
    private boolean darkTheme;

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
        root.addView(text("Aap ke approved groups mein aap ka live link post karta hai. Facebook password kabhi nahi mangta.", 15, false));

        Button themeToggle = button(darkTheme ? "☀️  Light theme" : "🌙  Dark theme");
        themeToggle.setContentDescription(darkTheme ? "Switch to Facebook light theme" : "Switch to Facebook dark theme");
        themeToggle.setOnClickListener(v -> {
            CampaignStore.prefs(this).edit().putBoolean(KEY_DARK_THEME, !darkTheme).apply();
            recreate();
        });
        root.addView(themeToggle);

        facebookAppStatus = text("Facebook app: Start par choose hogi", 14, true);
        facebookAppStatus.setPadding(0, dp(12), 0, 0);
        root.addView(facebookAppStatus);

        linkInput = input("Facebook live link", false);
        messageInput = input("Message, e.g. Main live hoon — join karein", true);
        groupsInput = input("Group URLs — har line par aik", true);
        groupsInput.setMinLines(5);
        delayInput = input("Delay between groups (seconds)", false);
        delayInput.setInputType(InputType.TYPE_CLASS_NUMBER);

        root.addView(label("1. Live link"));
        root.addView(linkInput);
        root.addView(label("2. Message"));
        root.addView(messageInput);
        root.addView(label("3. Facebook group links (manual approved list)"));
        root.addView(groupsInput);
        root.addView(label("4. Safety delay"));
        root.addView(delayInput);

        autoPostInput = new CheckBox(this);
        autoPostInput.setText("Post button automatically press kare (experimental)");
        autoPostInput.setPadding(0, dp(10), 0, dp(10));
        root.addView(autoPostInput);

        Button accessibility = button("Enable Accessibility service");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button start = button("Start posting");
        start.setOnClickListener(v -> validateAndConfirm());
        root.addView(start);

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

        TextView warning = text("Important: sirf un groups mein post karein jahan promotion/live links allowed hon. Same phone par Facebook Live background mein jane se stream ruk sakti hai; doosra phone zyada reliable hai.", 13, false);
        warning.setTextColor(darkTheme ? Color.rgb(176, 179, 184) : Color.rgb(101, 103, 107));
        warning.setPadding(0, dp(18), 0, 0);
        root.addView(warning);

        setContentView(scroll);
    }

    private void loadSavedValues() {
        SharedPreferences p = CampaignStore.prefs(this);
        linkInput.setText(p.getString(CampaignStore.KEY_LINK, ""));
        messageInput.setText(p.getString(CampaignStore.KEY_MESSAGE, "Main Facebook par live hoon — join karein:"));
        groupsInput.setText(p.getString(CampaignStore.KEY_GROUPS, ""));
        delayInput.setText(String.valueOf(p.getInt(CampaignStore.KEY_DELAY, 90)));
        autoPostInput.setChecked(p.getBoolean(CampaignStore.KEY_AUTO_POST, false));
        String savedPackage = p.getString(CampaignStore.KEY_FACEBOOK_PACKAGE, "");
        if (!savedPackage.isEmpty()) {
            facebookAppStatus.setText("Last used: " + facebookLabel(savedPackage) + " — Start par dobara confirm hogi");
        }
    }

    private void validateAndConfirm() {
        String link = linkInput.getText().toString().trim();
        List<String> groups = CampaignStore.parseGroups(groupsInput.getText().toString());
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
        if (groups.isEmpty()) {
            groupsInput.setError("Kam az kam aik valid facebook.com/groups/... link dein");
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
                .setTitle("Start for " + groups.size() + " groups?")
                .setMessage(mode + "\n\nGroup rules aur Facebook restrictions ki zimmedari user ki hai.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> chooseFacebookAppThenStart(safeDelay))
                .show();
    }

    private void chooseFacebookAppThenStart(int delay) {
        List<String> packages = installedFacebookPackages();
        if (packages.isEmpty()) {
            Toast.makeText(this, "Facebook ya Facebook Lite install aur login karein", Toast.LENGTH_LONG).show();
            return;
        }
        if (packages.size() == 1) {
            startCampaign(delay, packages.get(0));
            return;
        }

        String[] labels = new String[packages.size()];
        for (int i = 0; i < packages.size(); i++) labels[i] = facebookLabel(packages.get(i));
        final int[] selected = {-1};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Kaunsi Facebook app use karni hai?")
                .setMessage("App auto-select nahi karegi. Har campaign ke liye aap choose karein.")
                .setSingleChoiceItems(labels, -1, (d, which) -> {
                    selected[0] = which;
                    ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use selected", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(false);
            confirm.setOnClickListener(v -> {
                if (selected[0] < 0) return;
                String packageName = packages.get(selected[0]);
                dialog.dismiss();
                startCampaign(delay, packageName);
            });
        });
        dialog.show();
    }

    private List<String> installedFacebookPackages() {
        List<String> result = new ArrayList<>();
        for (String packageName : new String[]{"com.facebook.katana", "com.facebook.lite"}) {
            try {
                getPackageManager().getApplicationInfo(packageName, 0);
                result.add(packageName);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not installed.
            }
        }
        return result;
    }

    private String facebookLabel(String packageName) {
        return "com.facebook.lite".equals(packageName) ? "Facebook Lite" : "Facebook";
    }

    private void startCampaign(int delay, String facebookPackage) {
        long now = System.currentTimeMillis();
        CampaignStore.prefs(this).edit()
                .putString(CampaignStore.KEY_LINK, linkInput.getText().toString().trim())
                .putString(CampaignStore.KEY_MESSAGE, messageInput.getText().toString().trim())
                .putString(CampaignStore.KEY_GROUPS, groupsInput.getText().toString().trim())
                .putInt(CampaignStore.KEY_DELAY, delay)
                .putBoolean(CampaignStore.KEY_AUTO_POST, autoPostInput.isChecked())
                .putString(CampaignStore.KEY_FACEBOOK_PACKAGE, facebookPackage)
                .putBoolean(CampaignStore.KEY_RUNNING, true)
                .putInt(CampaignStore.KEY_INDEX, 0)
                .putString(CampaignStore.KEY_STAGE, CampaignStore.STAGE_OPEN_GROUP)
                .putLong(CampaignStore.KEY_LAST_ACTION, now)
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
        int index = p.getInt(CampaignStore.KEY_INDEX, 0);
        int total = CampaignStore.parseGroups(p.getString(CampaignStore.KEY_GROUPS, "")).size();
        status.setText(running ? "Running: group " + Math.min(index + 1, total) + " of " + total : "Status: stopped / ready");
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
