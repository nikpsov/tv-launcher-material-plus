package com.rampey.materialtv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Material 3 Expressive home screen for TV: the background and accents take their color from the app in focus,
 * one dock of your most-used apps, everything else on an Apps page.
 */
public class HomeActivity extends Activity {


    private static final int SIDE = 48, DOCK_PAD = 8, SCROLL_PAD = 18, GAP = 14, RING = 6, APP_MARGIN = 9;

    /** Material's "emphasized decelerate" curve for things arriving on screen. */
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> seedCache = new ConcurrentHashMap<>();
    private final Map<Integer, Palette> paletteCache = new ConcurrentHashMap<>();
    private final Map<Long, Typeface> fontCache = new HashMap<>();
    private final java.util.concurrent.ExecutorService paletteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "palette-warmup");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private PackageManager pm;
    private Palette palette = Palette.of(Palette.DEFAULT_SEED);

    private GradientDrawable backdrop;
    private CookieDrawable cookie;
    private ImageView shape;
    /**
     * The background shape drifts at 3 degrees a second, on every display frame (60 fps on a 60 Hz TV). It only runs
     * while Home is on screen; the shape is a hardware layer, so each frame just rotates a texture.
     */
    private long driftLast;
    private boolean drifting;
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (!drifting) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (driftLast > 0) {
                shape.setRotation((shape.getRotation() + 0.003f * (now - driftLast)) % 360f);
            }
            driftLast = now;
            shape.postOnAnimation(this);
        }
    };
    private final Runnable prebuildApps = new Runnable() {
        @Override public void run() {
            if (!HomeActivity.this.showingApps) {
                loadInstalledAppsAsync(HomeActivity.this::fillApps);
            }
        }
    };
    private GradientDrawable dockShape;
    private View homePage;
    private ScrollView appsPage;
    private GridLayout appsGrid;
    private HorizontalScrollView scroller;
    private LinearLayout dock;
    private TextView clock;
    private TextView date;
    private TextView focusTitle;
    private ButtonGroup nav;
    private String reorderingPkg;
    private View leftScrim, rightScrim;
    private GradientDrawable leftScrimShape, rightScrimShape;
    private ValueAnimator dockScrollAnim;
    private int dockScrollTarget = -1;
    private String lastFocusedDockPkg;
    private ButtonGroup wifiPill;
    private boolean wifiConnected;
    private ConnectivityManager.NetworkCallback wifiCallback;

    // Focus settles before the expensive part runs, so holding a direction stays smooth.
    private String pendingPkg;
    private CharSequence pendingLabel;
    private Drawable pendingArt;
    private final Runnable settle = new Runnable() {
        @Override public void run() {
            applyFocus(pendingPkg, pendingLabel, pendingArt);
        }
    };

    // Now Playing.
    private MediaSessionManager sessions;
    private ComponentName mediaListener;
    private MediaController playing;
    private View nowCard;
    private ImageView nowArt;
    private TextView nowTitle;
    private TextView nowArtist;
    private WavyProgress nowProgress;
    private final Map<String, EqualizerBadge> dockBadges = new HashMap<>();
    private final Map<String, View> deviceDots = new HashMap<>();   // input id -> "on" dot
    private final Map<String, Drawable> inputBannerCache = new HashMap<>();
    private final android.media.tv.TvInputManager.TvInputCallback inputCallback = new android.media.tv.TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) {
            showDeviceState(inputId, state);
        }

        // An HDMI device names itself over CEC a little after it appears ("Playback 1" -> "PlayStation 5").
        @Override public void onInputAdded(String inputId) {
            refreshDockSoon();
        }

        @Override public void onInputRemoved(String inputId) {
            refreshDockSoon();
        }

        @Override public void onInputUpdated(String inputId) {
            refreshDockSoon();
        }

        @Override public void onTvInputInfoUpdated(android.media.tv.TvInputInfo info) {
            refreshDockSoon();
        }
    };
    private final Runnable refreshDock = () -> {
        if (drifting) fillDock();
    };

    private void refreshDockSoon() {
        handler.removeCallbacks(refreshDock);
        handler.postDelayed(refreshDock, 500);
    }
    private ImageView nowButton;
    private GradientDrawable nowShape;
    private GradientDrawable nowButtonShape;
    // The callbacks hand over the session's metadata and state; they are kept rather than fetched again from the
    // controller, which would re-parcel the whole bundle (album art included) several times a second.
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            liveMeta = metadata;
            showNowPlaying();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            liveState = state;
            showNowPlaying();
        }

        @Override public void onSessionDestroyed() {
            pickSession();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickSession();
    private boolean showingApps;
    private String appsKey = "";
    private String dockKey = "";
    private ValueAnimator colorAnimator;

    /** Clock weight through the day: 250 at night, rising to 430 by mid-morning, easing back after sunset. */
    private static int clockWeight() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        float h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f;
        float[][] keys = {{0, 250}, {5, 250}, {9, 430}, {17, 430}, {21, 300}, {24, 250}};
        for (int i = 0; i < keys.length - 1; i++) {
            if (h >= keys[i][0] && h <= keys[i + 1][0]) {
                float t = (h - keys[i][0]) / (keys[i + 1][0] - keys[i][0]);
                return Math.round(keys[i][1] + (keys[i + 1][1] - keys[i][1]) * t);
            }
        }
        return 300;
    }

    private Typeface clockFont(int weight) {
        int w = Math.round(weight / 10f) * 10;
        return getCachedFont(w, 48, false);
    }

    /** A new minute: the old time lifts away, the new one settles in with a short weight swell. */
    private void changeClock(String time) {
        int base = clockWeight();
        clock.animate().alpha(0f).translationY(-dp(6)).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                .withEndAction(() -> {
                    clock.setText(time);
                    clock.setTranslationY(dp(8));
                    clock.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL)
                            .setDuration(Motion.DEFAULT_SPATIAL_MS).start();
                    ValueAnimator swell = ValueAnimator.ofInt(base + 320, base).setDuration(700);
                    swell.setInterpolator(Motion.DEFAULT_SPATIAL);
                    swell.addUpdateListener(a -> clock.setTypeface(clockFont((int) a.getAnimatedValue())));
                    swell.start();
                }).start();
    }

    private final SimpleDateFormat timeFormat12 = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final SimpleDateFormat timeFormat24 = new SimpleDateFormat("H:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            boolean is24 = android.text.format.DateFormat.is24HourFormat(HomeActivity.this);
            String time = (is24 ? timeFormat24 : timeFormat12).format(now);
            if (clock.getText().length() == 0) {
                clock.setText(time);
                clock.setTypeface(clockFont(clockWeight()));
            } else if (!time.contentEquals(clock.getText())) {
                changeClock(time);
            }
            String dateStr = dateFormat.format(now);
            date.setTypeface(font(500, dateStr));
            date.setText(dateStr);
            long msUntilNextMinute = 60_000 - (System.currentTimeMillis() % 60_000) + 200;
            handler.postDelayed(this, Math.min(15_000, msUntilNextMinute));
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pm = getPackageManager();
        titleFont(820);
        titleFont(820, "А");
        setContentView(buildRoot());
        registerWifiCallback();
        loadInstalledAppsAsync(null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingResume != null) { pendingResume.cancel(); pendingResume = null; }
        fillDock();
        if (showingApps) fillApps();
        updateWifiState();
        handler.removeCallbacks(tick);
        handler.post(tick);
        drifting = true;
        driftLast = 0;
        shape.removeCallbacks(drift);
        shape.postOnAnimation(drift);
        // Build the Apps page quietly once Home is showing, so opening it later is instant.
        handler.removeCallbacks(prebuildApps);
        handler.postDelayed(prebuildApps, 1500);
        foreground = true;
        startNowPlaying();
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) {
            inputs.registerCallback(inputCallback, handler);
            for (String id : deviceDots.keySet()) showDeviceState(id, inputs.getInputState(id));
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        // Nothing animates while another app is on screen: no CPU or GPU use in the background.
        drifting = false;
        shape.removeCallbacks(drift);
        handler.removeCallbacks(prebuildApps);
        if (colorAnimator != null) colorAnimator.end();
        if (dockScrollAnim != null) dockScrollAnim.cancel();
        nav.stopAnimations();
        if (wifiPill != null) wifiPill.stopAnimations();
        handler.removeCallbacks(settle);
        // The media session stays watched (it only updates fields, never views) so the card remembers what played
        // while another app had the screen; it is let go in onDestroy.
        foreground = false;
        handler.removeCallbacks(checkResumed);
        android.media.tv.TvInputManager inputs = getSystemService(android.media.tv.TvInputManager.class);
        if (inputs != null) inputs.unregisterCallback(inputCallback);
        hideLoader();
    }

    @Override protected void onDestroy() {
        if (pendingResume != null) pendingResume.cancel();
        paletteExecutor.shutdownNow();
        stopNowPlaying();
        if (wifiCallback != null) {
            try {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                if (cm != null) cm.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
            }
            wifiCallback = null;
        }
        handler.removeCallbacks(expire);
        handler.removeCallbacks(checkResumed);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showHome();
    }


    @Override public void onBackPressed() {
        if (showingApps) showHome();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (reorderingPkg != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveReorderingApp(-1);
                return true;
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveReorderingApp(1);
                return true;
            } else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                finishReordering(true);
                return true;
            } else if (code == KeyEvent.KEYCODE_BACK) {
                finishReordering(false);
                return true;
            }
        }
        if (!showingApps && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
            View current = getCurrentFocus();
            if (current != null && (isChildOf(current, nav) || isChildOf(current, wifiPill))) {
                if (lastFocusedDockPkg != null && dock != null && dock.findViewWithTag(lastFocusedDockPkg) != null) {
                    focusTileForPkg(lastFocusedDockPkg);
                    return true;
                } else if (dock != null && dock.getChildCount() > 0) {
                    View firstCard = cardFromHolder(dock.getChildAt(0));
                    if (firstCard != null) {
                        firstCard.requestFocus();
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (showingApps) showHome(); else showApps();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- layout ----

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{palette.surfaceBright, palette.surface});
        root.setBackground(backdrop);

        // One Expressive "cookie" shape behind everything, drifting slowly and turning a little more when focus
        // moves. It sits on its own GPU layer, so each step only moves the layer; nothing is redrawn.
        cookie = new CookieDrawable(palette.primaryContainer, 9, 0.08f);
        ImageView big = new ImageView(this);
        big.setImageDrawable(cookie);
        big.setAlpha(0.55f);
        big.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        FrameLayout.LayoutParams bigLp = new FrameLayout.LayoutParams(dp(560), dp(560), Gravity.END | Gravity.TOP);
        bigLp.setMargins(0, -dp(150), -dp(120), 0);
        root.addView(big, bigLp);
        shape = big;

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(SIDE), dp(32), dp(SIDE), dp(28));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, match());

        nowCard = buildNowPlaying();
        FrameLayout.LayoutParams nowLp = new FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        nowLp.setMargins(0, dp(140), dp(SIDE), 0);
        root.addView(nowCard, nowLp);

        column.addView(buildTopBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout pages = new FrameLayout(this);
        column.addView(pages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        homePage = buildHomePage();
        pages.addView(homePage, match());

        appsPage = new ScrollView(this);
        appsPage.setVerticalScrollBarEnabled(false);
        appsPage.setClipToPadding(false);
        appsPage.setClipChildren(false);
        LinearLayout appsColumn = new LinearLayout(this);
        appsColumn.setOrientation(LinearLayout.VERTICAL);
        appsColumn.setClipChildren(false);
        TextView appsTitle = text("Все приложения", 34, palette.onSurface, 760);
        appsTitle.setPadding(dp(4), dp(28), 0, dp(8));
        appsColumn.addView(appsTitle);
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(5);
        appsGrid.setClipChildren(false);
        appsGrid.setPadding(0, dp(12), 0, dp(32));
        appsColumn.addView(appsGrid);
        appsPage.addView(appsColumn, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        appsPage.setVisibility(View.GONE);
        pages.addView(appsPage, match());
        return root;
    }

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        // Room for a focused button to grow without its edge being cut off.
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(0, dp(4), dp(6), dp(4));

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = text("", 46, palette.onSurface, 300);
        clock.setIncludeFontPadding(false);
        when.addView(clock);
        date = text("", 15, palette.onSurfaceVariant, 500);
        date.setPadding(dp(2), dp(2), 0, 0);
        when.addView(date);
        bar.addView(when, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP));

        nav = new ButtonGroup(this, font(600, "Главная"), palette,
                new String[]{"Главная", "Приложения", "Настройки"},
                new int[]{R.drawable.ic_home, R.drawable.ic_apps, R.drawable.ic_settings},
                new boolean[]{true, true, false},
                index -> {
                    if (index == 0) showHome();
                    else if (index == 1) showApps();
                    else launch(new Intent(Settings.ACTION_SETTINGS));
                });
        nav.select(0);

        LinearLayout topEndRow = new LinearLayout(this);
        topEndRow.setOrientation(LinearLayout.HORIZONTAL);
        topEndRow.setGravity(Gravity.CENTER_VERTICAL);
        topEndRow.setClipChildren(false);
        topEndRow.setClipToPadding(false);

        topEndRow.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Space tabGap = new Space(this);
        topEndRow.addView(tabGap, new LinearLayout.LayoutParams(dp(12), 1));

        wifiPill = new ButtonGroup(this, font(600, "Не подключено"), palette,
                new String[]{"Wi-Fi"},
                new int[]{R.drawable.ic_wifi},
                new boolean[]{false},
                index -> launchWifiSettings());
        topEndRow.addView(wifiPill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar.addView(topEndRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));

        TextView settingsBtn = nav.getButton(2);
        TextView wifiBtn = wifiPill.getButton(0);
        if (settingsBtn != null && wifiBtn != null) {
            settingsBtn.setNextFocusRightId(wifiBtn.getId());
            wifiBtn.setNextFocusLeftId(settingsBtn.getId());
            wifiBtn.setNextFocusRightId(wifiBtn.getId());
            wifiBtn.setNextFocusUpId(wifiBtn.getId());
        }
        return bar;
    }

    private void launchWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException e1) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (ActivityNotFoundException e2) {
                launch(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }

    private void setWifiPillState(boolean connected, String text) {
        wifiConnected = connected;
        if (wifiPill != null) {
            wifiPill.setLabel(0, text);
        }
    }

    private void updateWifiState() {
        boolean connected = false;
        String ssid = null;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        connected = true;
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            android.net.TransportInfo ti = caps.getTransportInfo();
                            if (ti instanceof WifiInfo) {
                                ssid = ((WifiInfo) ti).getSSID();
                            }
                        }
                    }
                }
            }
            if (connected && (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid))) {
                WifiManager wm = getSystemService(WifiManager.class);
                if (wm != null) {
                    WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null) {
                        String s = wi.getSSID();
                        if (s != null && !s.isEmpty() && !"<unknown ssid>".equals(s)) {
                            ssid = s;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }

        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }

        if (!connected) {
            setWifiPillState(false, "Не подключено");
        } else if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equals(ssid)) {
            setWifiPillState(true, ssid);
        } else {
            setWifiPillState(true, "Wi-Fi");
        }
    }

    private void registerWifiCallback() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                handler.post(() -> updateWifiState());
            }
            @Override public void onLost(Network network) {
                handler.post(() -> updateWifiState());
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                handler.post(() -> updateWifiState());
            }
        };
        try {
            cm.registerNetworkCallback(req, wifiCallback);
        } catch (RuntimeException ignored) {
        }
    }

    private View buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.BOTTOM);
        page.setClipChildren(false);

        focusTitle = text("", 64, palette.onSurface, 820);
        focusTitle.setTypeface(titleFont(820));
        focusTitle.setIncludeFontPadding(false);
        // Normal spacing and no ligatures: at display sizes "tf" or "fl" would otherwise touch.
        focusTitle.setLetterSpacing(0.005f);
        focusTitle.setFontFeatureSettings("'liga' 0, 'clig' 0, 'dlig' 0");
        page.addView(focusTitle);
        Space gap = new Space(this);
        page.addView(gap, new LinearLayout.LayoutParams(1, dp(28)));

        // The dock: a rounded tonal container holding the favorites, like Material's floating toolbar.
        FrameLayout dockFrame = new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                scroller.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int h = scroller.getMeasuredHeight() + getPaddingTop() + getPaddingBottom();
                int exactHeightSpec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
                super.onMeasure(widthMeasureSpec, exactHeightSpec);
            }
        };
        dockFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(40));
            }
        });
        dockFrame.setClipToOutline(true);
        dockShape = new GradientDrawable();
        dockShape.setCornerRadius(dp(40));
        dockShape.setColor(withAlpha(palette.container, 0xB3));
        dockFrame.setBackground(dockShape);
        dockFrame.setPadding(0, dp(6), 0, dp(6));

        scroller = new HorizontalScrollView(this) {
            @Override
            public boolean arrowScroll(int direction) {
                return false;
            }

            @Override
            public void requestChildFocus(View child, View focused) {
                super.requestChildFocus(child, focused);
                View holder = findTileHolder(focused);
                if (holder != null) scrollDockToTile(holder);
            }
        };
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setSmoothScrollingEnabled(true);
        scroller.setClipToPadding(false);
        scroller.setClipChildren(false);
        scroller.setPadding(dp(SCROLL_PAD + DOCK_PAD), dp(20), dp(SCROLL_PAD + DOCK_PAD), dp(16));
        dock = new LinearLayout(this);
        dock.setClipChildren(false);
        scroller.addView(dock);
        dockFrame.addView(scroller, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        leftScrimShape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(palette.container, 0xF0), Color.TRANSPARENT});
        leftScrim = new View(this);
        leftScrim.setBackground(leftScrimShape);
        leftScrim.setFocusable(false);
        leftScrim.setClickable(false);
        leftScrim.setAlpha(0f);
        leftScrim.setTranslationZ(dp(2));
        dockFrame.addView(leftScrim, new FrameLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        rightScrimShape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, withAlpha(palette.container, 0xF0)});
        rightScrim = new View(this);
        rightScrim.setBackground(rightScrimShape);
        rightScrim.setFocusable(false);
        rightScrim.setClickable(false);
        rightScrim.setAlpha(0f);
        rightScrim.setTranslationZ(dp(2));
        dockFrame.addView(rightScrim, new FrameLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        scroller.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> updateScrollScrims());

        page.addView(dockFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return page;
    }

    // ---- content ----

    /** The dock: pinned apps followed by HDMI devices (CEC). */
    private List<String> dockApps() {
        List<String> out = new ArrayList<>();
        for (String p : PinnedApps.load(this)) {
            if (launchIntent(p) != null && !p.equals(getPackageName()) && !out.contains(p)) {
                out.add(p);
            }
        }
        // HDMI devices that name themselves (CEC), e.g. "PlayStation 5", take the TV input app's place.
        String inputApp = inputApp();
        List<String> devices = cecDevices();
        int at = inputApp == null ? -1 : out.indexOf(inputApp);
        if (at >= 0 && !devices.isEmpty()) {
            out.remove(at);
            out.addAll(at, devices);
        } else if (!devices.isEmpty()) {
            for (String d : devices) if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    /** CEC-named HDMI devices: dock key "input:<id>" -> {label, parent input id}. */
    private final Map<String, String[]> inputDevices = new HashMap<>();

    private List<String> cecDevices() {
        List<String> keys = new ArrayList<>();
        inputDevices.clear();
        try {
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim == null) return keys;
            for (android.media.tv.TvInputInfo in : tim.getTvInputList()) {
                if (in.getType() != android.media.tv.TvInputInfo.TYPE_HDMI || in.getParentId() == null) continue;
                CharSequence label = in.loadLabel(this);
                if (label == null || label.length() == 0) continue;
                String key = "input:" + in.getId();
                inputDevices.put(key, new String[]{label.toString(), in.getParentId()});
                // Consoles first.
                if (isConsole(label.toString())) keys.add(0, key); else keys.add(key);
            }
        } catch (RuntimeException ignored) {
        }
        return keys;
    }

    /** The app that plays HDMI inputs (the one a passthrough link opens), e.g. "Live TV". */
    private String inputApp() {
        ResolveInfo r = pm.resolveActivity(new Intent(Intent.ACTION_VIEW,
                android.media.tv.TvContract.buildChannelUriForPassthroughInput("x")), 0);
        return r != null && r.activityInfo != null ? r.activityInfo.packageName : null;
    }

    private static boolean isConsole(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        return l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*") || l.contains("xbox")
                || l.contains("nintendo") || l.contains("switch");
    }

    /**
     * A 16:9 banner for an HDMI device, Material You themed-icon style: a deep tone of the console's brand hue with a
     * large Material icon in a light tone of the same hue. No text (the name sits under the tile) and no logos.
     */
    private Drawable inputBanner(String label) {
        Drawable cached = inputBannerCache.get(label);
        if (cached != null) return cached;
        String l = label.toLowerCase(Locale.ROOT);
        int brand = 0xFF5F6368;
        if (l.contains("playstation") || l.matches(".*\\bps[2-5]\\b.*")) brand = 0xFF0047AB;
        else if (l.contains("xbox")) brand = 0xFF107C10;
        else if (l.contains("nintendo") || l.contains("switch")) brand = 0xFFE60012;
        palettes.TonalPalette tones = palettes.TonalPalette.fromInt(brand);
        int icon = isConsole(label) ? R.drawable.ic_gamepad : R.drawable.ic_input_tv;
        Bitmap b = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(tones.tone(30));
        Drawable d = getDrawable(icon).mutate();
        d.setTint(tones.tone(90));
        int size = 250;
        d.setBounds((640 - size) / 2, (360 - size) / 2, (640 + size) / 2, (360 + size) / 2);
        d.draw(c);
        Drawable result = new android.graphics.drawable.BitmapDrawable(getResources(), b);
        inputBannerCache.put(label, result);
        return result;
    }

    private Intent dockIntent(String key) {
        String[] dev = inputDevices.get(key);
        if (dev != null) return new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(dev[1]));
        return launchIntent(key);
    }

    private void restoreDockFocus() {
        if (showingApps || dock == null || dock.getChildCount() == 0 || reorderingPkg != null) return;
        if (lastFocusedDockPkg != null && dock.findViewWithTag(lastFocusedDockPkg) != null) {
            focusTileForPkg(lastFocusedDockPkg);
        } else {
            View firstCard = cardFromHolder(dock.getChildAt(0));
            if (firstCard != null) firstCard.requestFocus();
            else dock.getChildAt(0).requestFocus();
        }
    }

    private void pinAndRefresh(String pkg, CharSequence label) {
        PinnedApps.pin(this, pkg);
        dockKey = "";
        fillDock(true);
        Toast.makeText(this, label + " закреплено", Toast.LENGTH_SHORT).show();
    }

    private void fillDock() {
        fillDock(true);
    }

    private void fillDock(boolean animate) {
        List<String> present = dockApps();
        // Kept between visits; rebuild only when the dock's order or an HDMI device's name changes.
        StringBuilder names = new StringBuilder();
        for (String p : present) {
            String[] dev = inputDevices.get(p);
            if (dev != null) names.append('|').append(dev[0]);
        }
        String key = (present.isEmpty() ? ":empty:" : TextUtils.join(",", present)) + names;
        if (key.equals(dockKey) && dock.getChildCount() > 0) {
            if (!showingApps && reorderingPkg == null) {
                dock.post(this::restoreDockFocus);
            }
            return;
        }
        dockKey = key;
        dock.removeAllViews();
        dockBadges.clear();
        deviceDots.clear();
        warmPalettes(present);
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float countForSize = present.size() > 6 ? 6.4f : 6f;
        float inner = screenDp - 2 * (SIDE + DOCK_PAD + SCROLL_PAD) - 5 * GAP - 12 * RING;
        int tileDp = (int) Math.max(92, Math.min(220, inner / countForSize));

        if (present.isEmpty()) {
            View plusTile = buildPlusTile(tileDp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dock.addView(plusTile, lp);
            if (animate) arrive(plusTile, 0);
            View c = cardFromHolder(plusTile);
            if (c != null) {
                c.setNextFocusLeftId(c.getId());
                c.setNextFocusRightId(c.getId());
                c.setNextFocusDownId(c.getId());
            }
            if (!showingApps && reorderingPkg == null) {
                dock.post(this::restoreDockFocus);
            }
            dock.post(this::updateScrollScrims);
            return;
        }

        int i = 0;
        for (String pkg : present) {
            Intent intent = dockIntent(pkg);
            View tile = tile(pkg, intent, tileDp, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(GAP));
            dock.addView(tile, lp);
            if (animate) arrive(tile, i);
            i++;
        }
        if (reorderingPkg == null) {
            View plus = buildCompactPlusTile(tileDp);
            LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plusLp.setMarginStart(dp(GAP));
            dock.addView(plus, plusLp);
            if (animate) arrive(plus, i);
        }
        int count = dock.getChildCount();
        for (int j = 0; j < count; j++) {
            View c = cardFromHolder(dock.getChildAt(j));
            if (c == null) continue;
            if (j > 0) {
                View prev = cardFromHolder(dock.getChildAt(j - 1));
                if (prev != null) c.setNextFocusLeftId(prev.getId());
            } else {
                c.setNextFocusLeftId(c.getId());
            }
            if (j < count - 1) {
                View next = cardFromHolder(dock.getChildAt(j + 1));
                if (next != null) c.setNextFocusRightId(next.getId());
            } else {
                c.setNextFocusRightId(c.getId());
            }
            c.setNextFocusDownId(c.getId());
        }
        if (!showingApps && dock.getChildCount() > 0 && reorderingPkg == null) {
            dock.post(this::restoreDockFocus);
        }
        updateBadges();
        dock.post(this::updateScrollScrims);
    }

    private volatile List<ResolveInfo> cachedInstalledApps;
    private boolean loadingInstalledApps;

    private List<ResolveInfo> queryInstalledApps() {
        Set<String> seen = new LinkedHashSet<>();
        List<ResolveInfo> found = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                found.add(info);
            }
        }
        Map<ResolveInfo, String> labels = new HashMap<>();
        for (ResolveInfo info : found) labels.put(info, String.valueOf(info.loadLabel(pm)));
        Collections.sort(found, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));
        return found;
    }

    private void loadInstalledAppsAsync(Runnable onLoaded) {
        if (cachedInstalledApps != null) {
            if (onLoaded != null) onLoaded.run();
            return;
        }
        if (loadingInstalledApps) return;
        loadingInstalledApps = true;
        new Thread(() -> {
            List<ResolveInfo> apps = queryInstalledApps();
            cachedInstalledApps = apps;
            loadingInstalledApps = false;
            handler.post(() -> {
                if (onLoaded != null) onLoaded.run();
                if (showingApps) fillApps();
            });
        }, "load-installed-apps").start();
    }

    private List<ResolveInfo> getInstalledApps() {
        if (cachedInstalledApps != null) return cachedInstalledApps;
        return queryInstalledApps();
    }

    private void fillApps() {
        List<ResolveInfo> found = cachedInstalledApps;
        if (found == null) {
            loadInstalledAppsAsync(this::fillApps);
            found = getInstalledApps();
        }
        List<String> pkgs = new ArrayList<>();
        for (ResolveInfo info : found) pkgs.add(info.activityInfo.packageName);
        String key = TextUtils.join(",", pkgs);
        if (key.equals(appsKey) && appsGrid.getChildCount() > 0) return;
        appsKey = key;
        appsGrid.removeAllViews();
        // Five columns that exactly fill the width between the side margins (tile + focus-ring room + gaps).
        int cols = appsGrid.getColumnCount();
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float inner = screenDp - 2 * SIDE - cols * (2 * RING + 2 * APP_MARGIN);
        int tileDp = (int) Math.min(170, inner / cols);
        for (ResolveInfo info : found) {
            String pkg = info.activityInfo.packageName;
            Intent intent = launchIntent(pkg);
            if (intent == null) continue;
            View tile = tile(pkg, intent, tileDp, false);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(APP_MARGIN), dp(10), dp(APP_MARGIN), dp(16));
            appsGrid.addView(tile, lp);
        }
    }

    private static class AppEntry {
        final CharSequence label;
        final Drawable banner;
        final Drawable icon;
        AppEntry(CharSequence label, Drawable banner, Drawable icon) {
            this.label = label;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private AppEntry loadAppEntry(String pkg) {
        CharSequence name = pkg;
        Drawable banner = null;
        Drawable icon = null;
        String[] device = inputDevices.get(pkg);
        if (device != null) {
            name = device[0];
            banner = inputBanner(device[0]);
        } else {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                name = pm.getApplicationLabel(ai);
                banner = pm.getApplicationBanner(ai);
                if (banner == null) icon = pm.getApplicationIcon(ai);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return new AppEntry(name, banner, icon);
    }

    private static class CardComponents {
        final LinearLayout holder;
        final FrameLayout ringFrame;
        final GradientDrawable ring;
        final FrameLayout card;
        final GradientDrawable cardShape;
        final ImageView image;
        CardComponents(LinearLayout holder, FrameLayout ringFrame, GradientDrawable ring,
                       FrameLayout card, GradientDrawable cardShape, ImageView image) {
            this.holder = holder;
            this.ringFrame = ringFrame;
            this.ring = ring;
            this.card = card;
            this.cardShape = cardShape;
            this.image = image;
        }
    }

    private CardComponents buildCardBase(String pkg, CharSequence label, Drawable banner, Drawable icon, int widthDp) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(pkg);
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(banner != null ? Color.TRANSPARENT : palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);

        ImageView image = new ImageView(this);
        if (banner != null) {
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageDrawable(banner);
            card.addView(image, match());
        } else {
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(icon);
            card.addView(image, new FrameLayout.LayoutParams(h / 2, h / 2, Gravity.CENTER));
        }
        card.setContentDescription(label);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));

        return new CardComponents(holder, ringFrame, ring, card, cardShape, image);
    }

    private void applyFocusStyle(View card, View ringFrame, GradientDrawable cardShape,
                                 GradientDrawable ring, ValueAnimator[] ringAnim,
                                 ObjectAnimator[] cornerAnim, float radius, boolean focused) {
        applyFocusStyle(card, ringFrame, cardShape, ring, ringAnim, cornerAnim, radius, focused, false, dp(14));
    }

    private void applyFocusStyle(View card, View ringFrame, GradientDrawable cardShape,
                                 GradientDrawable ring, ValueAnimator[] ringAnim,
                                 ObjectAnimator[] cornerAnim, float radius, boolean focused, boolean activeMove) {
        applyFocusStyle(card, ringFrame, cardShape, ring, ringAnim, cornerAnim, radius, focused, activeMove, dp(14));
    }

    private void applyFocusStyle(View card, View ringFrame, GradientDrawable cardShape,
                                 GradientDrawable ring, ValueAnimator[] ringAnim,
                                 ObjectAnimator[] cornerAnim, float radius, boolean focused, boolean activeMove, int elevation) {
        float scale = activeMove ? 1.14f : (focused ? 1.10f : 1f);
        ringFrame.animate().scaleX(scale).scaleY(scale)
                .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
        if (android.os.Build.VERSION.SDK_INT >= 29 && elevation > 0) {
            card.setOutlineSpotShadowColor(palette.primary);
            card.setOutlineAmbientShadowColor(palette.primary);
        }
        card.animate().translationZ(focused || activeMove ? elevation : 0)
                .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.DEFAULT_EFFECTS_MS).start();

        if (cornerAnim != null) {
            if (cornerAnim[0] != null) cornerAnim[0].cancel();
            float targetRadius = (focused || activeMove) ? radius * 0.62f : radius;
            cornerAnim[0] = ObjectAnimator.ofFloat(cardShape, "cornerRadius", cardShape.getCornerRadius(), targetRadius);
            cornerAnim[0].setDuration(Motion.FAST_SPATIAL_MS);
            cornerAnim[0].setInterpolator(Motion.FAST_SPATIAL);
            cornerAnim[0].addUpdateListener(anim -> card.invalidateOutline());
            cornerAnim[0].start();
            ring.setCornerRadius(targetRadius + dp(RING));
        }

        if (activeMove) {
            if (ringAnim != null && ringAnim[0] != null) ringAnim[0].cancel();
            ring.setColor(palette.primary);
            ring.setStroke(0, Color.TRANSPARENT);
            ring.setAlpha(255);
        } else if (ringAnim != null) {
            fadeRing(ringAnim, ring, focused);
        }
    }

    /** A 16:9 card: the app's TV banner, or its icon on a tonal card, with the name underneath. */
    private View tile(String pkg, Intent intent, int widthDp, boolean inDock) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        AppEntry entry = loadAppEntry(pkg);
        final CharSequence label = entry.label;
        final Drawable art = entry.banner != null ? entry.banner : entry.icon;

        CardComponents comp = buildCardBase(pkg, label, entry.banner, entry.icon, widthDp);
        LinearLayout holder = comp.holder;
        FrameLayout ringFrame = comp.ringFrame;
        GradientDrawable ring = comp.ring;
        FrameLayout card = comp.card;
        GradientDrawable cardShape = comp.cardShape;

        final ValueAnimator[] ringAnim = new ValueAnimator[1];
        final ObjectAnimator[] cornerAnim = new ObjectAnimator[1];

        boolean isReordering = inDock && pkg.equals(reorderingPkg);
        if (isReordering) {
            holder.setTranslationY(-dp(10));
            ringFrame.setScaleX(1.12f);
            ringFrame.setScaleY(1.12f);
            ring.setColor(palette.primary);
        }

        // Expressive loading indicator: a small shape that morphs while the app takes its time to open.
        CookieDrawable loaderShape = new CookieDrawable(palette.primary, 4, 0.18f);
        ImageView loader = new ImageView(this);
        loader.setImageDrawable(loaderShape);
        loader.setVisibility(View.GONE);
        ringFrame.addView(loader, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        card.setOnClickListener(v -> pressAndLaunch(ringFrame, loader, loaderShape, intent));
        card.setOnLongClickListener(v -> {
            if (inDock) showDockContextMenu(pkg, label);
            else showAppsContextMenu(pkg);
            return true;
        });

        TextView caption = text(label, inDock ? 15 : 14, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(10), 0, 0);
        caption.setAlpha(inDock ? 0f : 0.85f);

        card.setOnFocusChangeListener((v, focused) -> {
            boolean activeMove = inDock && pkg.equals(reorderingPkg);
            applyFocusStyle(card, ringFrame, cardShape, ring, ringAnim, cornerAnim, radius, focused, activeMove);
            if (activeMove) {
                holder.setTranslationY(-dp(10));
            }
            caption.animate().cancel();
            if (inDock && focused) {
                caption.setVisibility(View.VISIBLE);
                caption.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            } else if (inDock) {
                caption.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> caption.setVisibility(View.GONE)).start();
            } else {
                caption.animate().alpha(focused ? 1f : 0.85f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            }
            caption.setTextColor(focused ? palette.onSurface : palette.onSurfaceVariant);
            if (focused) onTileFocused(pkg, label, art);
            if (inDock && focused) {
                lastFocusedDockPkg = pkg;
                scrollDockToTile(holder);
            }
        });

        // Status lives under the tile, never on the artwork: an equalizer for the app that's playing, a green dot for
        // an HDMI device that's on. Focused, the name appears beside it.
        LinearLayout captionRow = new LinearLayout(this);
        captionRow.setOrientation(LinearLayout.HORIZONTAL);
        captionRow.setGravity(Gravity.CENTER);
        captionRow.setClipChildren(false);
        caption.setPadding(0, 0, 0, 0);
        caption.setMaxWidth(w + dp(48) - dp(30));
        if (inDock) caption.setVisibility(View.GONE);
        if (inDock && inputDevices.containsKey(pkg)) {
            View dot = new View(this);
            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(0xFF6DD58C);                 // Material green, tone 80: "on"
            dot.setBackground(dotShape);
            dot.setVisibility(View.GONE);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
            dl.setMarginEnd(dp(8));
            captionRow.addView(dot, dl);
            String inputId = pkg.substring("input:".length());
            deviceDots.put(inputId, dot);
            android.media.tv.TvInputManager tim = getSystemService(android.media.tv.TvInputManager.class);
            if (tim != null) showDeviceState(inputId, tim.getInputState(inputId));
        }
        if (inDock) {
            EqualizerBadge badge = new EqualizerBadge(this);
            badge.setVisibility(View.GONE);
            badge.setColors(0x00000000, palette.onSurfaceVariant);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(18), dp(22));
            bl.setMarginEnd(dp(6));
            captionRow.addView(badge, bl);
            dockBadges.put(pkg, badge);
        }
        captionRow.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        // Fixed height so the dock never jumps; a little wider than the tile (negative margins keep the spacing) so a
        // name with a status mark beside it still fits.
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w + dp(48), dp(32));
        rl.topMargin = dp(8);
        rl.leftMargin = -dp(24);
        rl.rightMargin = -dp(24);
        holder.addView(captionRow, rl);
        return holder;
    }

    /** An invitation tile when dock has no pinned apps: 16:9 card with an add icon. */
    private View buildPlusTile(int widthDp) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        final CharSequence label = "Добавить приложение";

        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(":plus:");
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(radius);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageResource(R.drawable.ic_add);
        image.setImageTintList(ColorStateList.valueOf(palette.onSurfaceVariant));
        card.addView(image, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
        card.setContentDescription(label);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);
        ringFrame.addView(card, new FrameLayout.LayoutParams(w, h));

        final ValueAnimator[] ringAnim = new ValueAnimator[1];
        final ObjectAnimator[] cornerAnim = new ObjectAnimator[1];

        card.setOnClickListener(v -> showPinPickerDialog());

        TextView caption = text(label, 15, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setAlpha(0f);

        card.setOnFocusChangeListener((v, focused) -> {
            applyFocusStyle(card, ringFrame, cardShape, ring, ringAnim, cornerAnim, radius, focused);

            caption.animate().cancel();
            if (focused) {
                caption.setVisibility(View.VISIBLE);
                caption.animate().alpha(1f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            } else {
                caption.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> caption.setVisibility(View.GONE)).start();
            }
            caption.setTextColor(focused ? palette.onSurface : palette.onSurfaceVariant);
            image.setImageTintList(ColorStateList.valueOf(focused ? palette.inverseOnSurface : palette.onSurfaceVariant));
            cardShape.setColor(focused ? palette.inverseSurface : palette.containerHigh);

            if (focused) {
                lastFocusedDockPkg = ":plus:";
                scrollDockToTile(holder);
                onTileFocused(null, label, null);
            }
        });

        LinearLayout captionRow = new LinearLayout(this);
        captionRow.setOrientation(LinearLayout.HORIZONTAL);
        captionRow.setGravity(Gravity.CENTER);
        captionRow.setClipChildren(false);
        caption.setPadding(0, 0, 0, 0);
        caption.setMaxWidth(w + dp(48) - dp(30));
        caption.setVisibility(View.GONE);
        captionRow.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w + dp(48), dp(32));
        rl.topMargin = dp(8);
        rl.leftMargin = -dp(24);
        rl.rightMargin = -dp(24);
        holder.addView(captionRow, rl);
        return holder;
    }

    /** A compact plus button at the end of the dock row. */
    private View buildCompactPlusTile(int tileDp) {
        int w = dp(tileDp);
        int h = w * 9 / 16;
        int btnSize = Math.min(dp(56), h);

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setClipChildren(false);
        holder.setGravity(Gravity.CENTER_HORIZONTAL);

        final CharSequence label = "Добавить приложение";

        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(":compact_plus:");
        GradientDrawable cardShape = new GradientDrawable();
        cardShape.setCornerRadius(btnSize / 2f);
        cardShape.setColor(palette.containerHigh);
        card.setBackground(cardShape);
        card.setClipToOutline(true);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageResource(R.drawable.ic_add);
        image.setImageTintList(ColorStateList.valueOf(palette.onSurfaceVariant));
        card.addView(image, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        card.setContentDescription(label);

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(btnSize / 2f + dp(RING));
        ring.setColor(Color.TRANSPARENT);
        FrameLayout ringFrame = new FrameLayout(this);
        ringFrame.setClipChildren(false);
        ringFrame.setPadding(dp(RING), dp(RING), dp(RING), dp(RING));
        ringFrame.setBackground(ring);
        ringFrame.addView(card, new FrameLayout.LayoutParams(btnSize, btnSize));

        final ValueAnimator[] ringAnim = new ValueAnimator[1];
        final ObjectAnimator[] cornerAnim = new ObjectAnimator[1];

        card.setOnClickListener(v -> showPinPickerDialog());

        card.setOnFocusChangeListener((v, focused) -> {
            applyFocusStyle(card, ringFrame, cardShape, ring, ringAnim, cornerAnim, btnSize / 2f, focused, false, 0);

            cardShape.setColor(focused ? palette.inverseSurface : palette.containerHigh);
            image.setImageTintList(ColorStateList.valueOf(focused ? palette.inverseOnSurface : palette.onSurfaceVariant));

            if (focused) {
                lastFocusedDockPkg = ":compact_plus:";
                scrollDockToTile(holder);
                onTileFocused(null, label, null);
            }
        });

        int topOffset = Math.max(0, (h - btnSize) / 2);
        LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(btnSize + dp(2 * RING), btnSize + dp(2 * RING));
        ringLp.topMargin = topOffset;
        holder.addView(ringFrame, ringLp);

        View spacer = new View(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(btnSize, dp(32) + dp(8) + (h - btnSize - topOffset));
        holder.addView(spacer, slp);
        return holder;
    }

    /** The focus ring fades in (fast effects) in the palette's focus color instead of snapping on. */
    private void fadeRing(ValueAnimator[] ringAnim, GradientDrawable ring, boolean focused) {
        int startAlpha = focused ? 0 : 255;
        if (ringAnim[0] != null) {
            if (ringAnim[0].isRunning()) {
                Object current = ringAnim[0].getAnimatedValue();
                if (current instanceof Integer) startAlpha = (Integer) current;
            }
            ringAnim[0].cancel();
        }
        int targetAlpha = focused ? 255 : 0;
        if (startAlpha == targetAlpha) return;
        int color = palette.inverseSurface;
        ValueAnimator a = ValueAnimator.ofInt(startAlpha, targetAlpha).setDuration(Motion.FAST_EFFECTS_MS);
        a.setInterpolator(Motion.FAST_EFFECTS);
        a.addUpdateListener(an -> {
            int alpha = (int) an.getAnimatedValue();
            ring.setStroke(dp(3), (color & 0x00FFFFFF) | (alpha << 24));
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!focused) ring.setStroke(0, Color.TRANSPARENT);
            }
        });
        ringAnim[0] = a;
        a.start();
    }

    private View activeLoader;
    private ValueAnimator loaderAnim;
    private final Runnable[] pendingLoader = new Runnable[1];

    /** Press feedback (a quick squash), then launch; if the app hasn't appeared after 300 ms, a shape loader morphs. */
    private void pressAndLaunch(View tile, ImageView loader, CookieDrawable shape, Intent intent) {
        tile.animate().scaleX(1.0f).scaleY(1.0f).setInterpolator(Motion.FAST_SPATIAL).setDuration(120)
                .withEndAction(() -> tile.animate().scaleX(1.10f).scaleY(1.10f)
                        .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start()).start();
        launch(intent);
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        pendingLoader[0] = () -> {
            hideLoader();
            activeLoader = loader;
            shape.setColor(palette.primary);
            loader.setAlpha(0f);
            loader.setVisibility(View.VISIBLE);
            loader.animate().alpha(1f).setDuration(Motion.FAST_EFFECTS_MS).start();
            final int[] seq = {4, 6, 7, 9, 12, 7, 6};
            final int[] step = {0};
            loaderAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(420);
            loaderAnim.setRepeatCount(ValueAnimator.INFINITE);
            loaderAnim.setInterpolator(Motion.DEFAULT_SPATIAL);
            loaderAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationRepeat(android.animation.Animator a) {
                    step[0] = (step[0] + 1) % seq.length;
                    shape.morphTo(seq[step[0]], seq[step[0]] == 4 ? 0.18f : 0.10f);
                }
            });
            shape.morphTo(seq[0], 0.18f);
            loaderAnim.addUpdateListener(an -> {
                shape.setProgress((float) an.getAnimatedValue());
                loader.setRotation(loader.getRotation() + 2.5f);
            });
            loaderAnim.start();
        };
        handler.postDelayed(pendingLoader[0], 300);
    }

    private void hideLoader() {
        if (pendingLoader[0] != null) handler.removeCallbacks(pendingLoader[0]);
        if (loaderAnim != null) loaderAnim.cancel();
        if (activeLoader != null) activeLoader.setVisibility(View.GONE);
        activeLoader = null;
    }

    private void onTileFocused(String pkg, CharSequence label, Drawable art) {
        pendingPkg = pkg;
        pendingLabel = label;
        pendingArt = art;
        handler.removeCallbacks(settle);
        handler.postDelayed(settle, 120);
    }

    /** Runs once focus has rested for a moment: the title, the background shape and the colors follow the app. */
    private void applyFocus(String pkg, CharSequence label, Drawable art) {
        if (!showingApps && label != null && !TextUtils.equals(focusTitle.getText(), label)) {
            focusTitle.setTypeface(titleFont(820, label));
            focusTitle.setText(label);
            focusTitle.setTextColor(palette.onSurface);
            focusTitle.setAlpha(0f);
            focusTitle.setTranslationY(dp(12));
            focusTitle.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
        }
        if (pkg != null) {
            morphShape(pkg);
            retint(paletteFor(pkg, art));
        } else {
            cookie.morphTo(4, 0.18f, 1f);
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(Motion.DEFAULT_SPATIAL_MS);
            a.setInterpolator(Motion.DEFAULT_SPATIAL);
            a.addUpdateListener(an -> cookie.setProgress((float) an.getAnimatedValue()));
            a.start();
            retint(Palette.of(Palette.DEFAULT_SEED));
        }
    }

    private Palette paletteFor(String pkg, Drawable art) {
        if (pkg == null || art == null) return Palette.of(Palette.DEFAULT_SEED);
        Integer seed = seedCache.get(pkg);
        if (seed == null) {
            seed = Palette.seedFrom(art);
            seedCache.put(pkg, seed);
        }
        Palette p = paletteCache.get(seed);
        if (p == null) {
            p = Palette.of(seed);
            paletteCache.put(seed, p);
        }
        return p;
    }

    private View pickerTile(String pkg, int widthDp, Set<String> selected) {
        int w = dp(widthDp);
        int h = w * 9 / 16;
        int radius = dp(Focus.MEDIA);
        AppEntry entry = loadAppEntry(pkg);
        final CharSequence label = entry.label;

        CardComponents comp = buildCardBase(pkg, label, entry.banner, entry.icon, widthDp);
        LinearLayout holder = comp.holder;
        FrameLayout ringFrame = comp.ringFrame;
        GradientDrawable ring = comp.ring;
        FrameLayout card = comp.card;
        GradientDrawable cardShape = comp.cardShape;

        // Checkmark badge in the top-right corner
        FrameLayout checkBadge = new FrameLayout(this);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(palette.primary);
        checkBadge.setBackground(badgeBg);

        ImageView checkIcon = new ImageView(this);
        checkIcon.setImageResource(R.drawable.ic_check);
        checkIcon.setImageTintList(ColorStateList.valueOf(palette.onPrimary));
        checkIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
        checkBadge.addView(checkIcon, match());

        int badgeSize = dp(26);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP | Gravity.END);
        badgeLp.setMargins(0, dp(6), dp(6), 0);
        card.addView(checkBadge, badgeLp);

        boolean isSelected = selected.contains(pkg);
        checkBadge.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        checkBadge.setAlpha(isSelected ? 1f : 0f);
        checkBadge.setScaleX(isSelected ? 1f : 0f);
        checkBadge.setScaleY(isSelected ? 1f : 0f);

        card.setOnClickListener(v -> {
            boolean checked = selected.contains(pkg);
            if (checked) {
                selected.remove(pkg);
                checkBadge.animate().alpha(0f).scaleX(0f).scaleY(0f)
                        .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> checkBadge.setVisibility(View.GONE)).start();
            } else {
                selected.add(pkg);
                checkBadge.setVisibility(View.VISIBLE);
                checkBadge.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            }
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction(() ->
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()).start();
        });

        final ValueAnimator[] ringAnim = new ValueAnimator[1];
        final ObjectAnimator[] cornerAnim = new ObjectAnimator[1];

        TextView caption = text(label, 14, palette.onSurfaceVariant, 560);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(10), 0, 0);
        caption.setAlpha(0.85f);

        card.setOnFocusChangeListener((v, focused) -> {
            applyFocusStyle(card, ringFrame, cardShape, ring, ringAnim, cornerAnim, radius, focused);
            caption.animate().cancel();
            caption.animate().alpha(focused ? 1f : 0.85f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
            caption.setTextColor(focused ? palette.onSurface : palette.onSurfaceVariant);
        });

        holder.addView(ringFrame, new LinearLayout.LayoutParams(w + dp(2 * RING), h + dp(2 * RING)));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(w + dp(48), dp(32));
        rl.topMargin = dp(8);
        rl.leftMargin = -dp(24);
        rl.rightMargin = -dp(24);
        holder.addView(caption, rl);
        return holder;
    }

    private void showPinPickerDialog() {
        List<ResolveInfo> apps = getInstalledApps();
        List<String> currentPinned = PinnedApps.load(this);
        Set<String> selected = new LinkedHashSet<>(currentPinned);

        Dialog dialog = new Dialog(this, R.style.HomeTheme);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0xEE0B1026));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(SIDE), dp(32), dp(SIDE), dp(28));
        root.setClipChildren(false);

        // Header: Title and Done button
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipChildren(false);

        TextView title = new TextView(this);
        title.setText("Закрепить приложения");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(titleFont(820, "Закрепить приложения"));
        title.setTextColor(palette.onSurface);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView doneBtn = new TextView(this);
        doneBtn.setId(View.generateViewId());
        doneBtn.setText("Готово");
        doneBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        doneBtn.setTypeface(font(600, "Готово"));
        doneBtn.setGravity(Gravity.CENTER);
        doneBtn.setFocusable(true);
        doneBtn.setClickable(true);
        doneBtn.setPadding(dp(20), dp(11), dp(20), dp(11));
        GradientDrawable doneShape = new GradientDrawable();
        doneShape.setCornerRadius(dp(24));
        doneShape.setColor(palette.containerHigh);
        doneBtn.setBackground(doneShape);
        doneBtn.setTextColor(palette.onSurface);

        final ValueAnimator[] doneAnim = new ValueAnimator[1];
        final int[] curDoneColor = new int[]{palette.containerHigh};
        doneBtn.setOnFocusChangeListener((v, focused) -> {
            if (doneAnim[0] != null) doneAnim[0].cancel();
            int target = focused ? palette.inverseSurface : palette.containerHigh;
            doneAnim[0] = ValueAnimator.ofArgb(curDoneColor[0], target).setDuration(Motion.DEFAULT_EFFECTS_MS);
            doneAnim[0].setInterpolator(Motion.DEFAULT_EFFECTS);
            doneAnim[0].addUpdateListener(a -> {
                curDoneColor[0] = (int) a.getAnimatedValue();
                doneShape.setColor(curDoneColor[0]);
            });
            doneAnim[0].start();
            doneBtn.setTextColor(focused ? palette.inverseOnSurface : palette.onSurface);
        });

        Runnable applyAndDismiss = () -> {
            List<String> updated = new ArrayList<>();
            for (String p : currentPinned) {
                if (selected.contains(p)) updated.add(p);
            }
            for (String s : selected) {
                if (!updated.contains(s)) updated.add(s);
            }
            PinnedApps.save(this, updated);
            dockKey = "";
            fillDock(true);
            dialog.dismiss();
        };

        doneBtn.setOnClickListener(v -> applyAndDismiss.run());
        header.addView(doneBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);

        GridLayout grid = new GridLayout(this);
        int cols = 5;
        grid.setColumnCount(cols);
        grid.setClipChildren(false);

        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        float inner = screenDp - 2 * SIDE - cols * (2 * RING + 2 * APP_MARGIN);
        int tileDp = (int) Math.min(170, inner / cols);

        List<View> cards = new ArrayList<>();
        for (ResolveInfo info : apps) {
            String pkg = info.activityInfo.packageName;
            View tileView = pickerTile(pkg, tileDp, selected);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(dp(APP_MARGIN), dp(10), dp(APP_MARGIN), dp(16));
            grid.addView(tileView, lp);
            View c = cardFromHolder(tileView);
            if (c != null) cards.add(c);
        }

        // Setup TV D-pad focus navigation inside modal
        int total = cards.size();
        for (int idx = 0; idx < total; idx++) {
            View card = cards.get(idx);
            int row = idx / cols;
            int col = idx % cols;

            // Row 0 pressing UP goes to Done button
            if (row == 0) {
                card.setNextFocusUpId(doneBtn.getId());
            }

            // Last row pressing DOWN wraps to first row (col)
            int nextDownIdx = idx + cols;
            if (nextDownIdx >= total) {
                int wrapIdx = Math.min(col, total - 1);
                card.setNextFocusDownId(cards.get(wrapIdx).getId());
            }
        }

        doneBtn.setNextFocusUpId(doneBtn.getId());
        if (!cards.isEmpty()) {
            doneBtn.setNextFocusDownId(cards.get(0).getId());
        }

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(16);
        scroll.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, scrollLp);

        dialog.setContentView(root);

        // Handle BACK key: apply selection and close if any selected, otherwise close
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                applyAndDismiss.run();
                return true;
            }
            return false;
        });

        // Focus first app tile on show
        dialog.setOnShowListener(d -> {
            if (!cards.isEmpty()) {
                cards.get(0).requestFocus();
            } else {
                doneBtn.requestFocus();
            }
        });

        dialog.show();
    }

    /** Work out every favorite's colors on a background thread, so the first focus on each app costs nothing. */
    private void warmPalettes(List<String> packages) {
        paletteExecutor.submit(() -> {
            for (String pkg : packages) {
                if (seedCache.containsKey(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    Drawable art = pm.getApplicationBanner(ai);
                    if (art == null) art = pm.getApplicationIcon(ai);
                    int seed = Palette.seedFrom(art);
                    seedCache.put(pkg, seed);
                    if (!paletteCache.containsKey(seed)) paletteCache.put(seed, Palette.of(seed));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static boolean hasCyrillic(CharSequence cs) {
        if (cs == null) return false;
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if ((c >= 0x0400 && c <= 0x04FF) || (c >= 0x0500 && c <= 0x052F)) return true;
        }
        return false;
    }

    private Typeface getCachedFont(int weight, int opsz, boolean cyrillic) {
        long key = (cyrillic ? (1L << 32) : 0L) | (((long) opsz) << 16) | (weight & 0xFFFFL);
        Typeface t = fontCache.get(key);
        if (t != null) return t;

        if (cyrillic) {
            try {
                t = Typeface.create(Typeface.SANS_SERIF, weight, false);
            } catch (Exception ignored) {
                t = null;
            }
            if (t == null || (weight >= 700 && t.getWeight() < 600)) {
                try {
                    t = Typeface.create("sans-serif-black", Typeface.NORMAL);
                } catch (Exception ignored) {
                    t = null;
                }
            }
            if (t == null) {
                t = Typeface.create(Typeface.SANS_SERIF, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            }
        } else {
            try {
                t = new Typeface.Builder(getAssets(), "fonts/GoogleSansFlex.ttf")
                        .setWeight(weight)
                        .setFallback("sans-serif")
                        .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + opsz + ", 'ROND' 0")
                        .build();
            } catch (RuntimeException ignored) {
                t = null;
            }
            if (t == null) {
                try {
                    t = Typeface.create(Typeface.SANS_SERIF, weight, false);
                } catch (Exception ignored) {
                    t = Typeface.create(weight >= 700 ? "sans-serif-black" : "sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
                }
            }
        }
        fontCache.put(key, t);
        return t;
    }

    private Typeface titleFont(int weight) {
        return titleFont(weight, null);
    }

    private Typeface titleFont(int weight, CharSequence text) {
        return getCachedFont(weight, 48, hasCyrillic(text));
    }

    /** Each app gets its own Expressive shape; moving between apps morphs one outline into the next. */
    private void morphShape(String pkg) {
        // Material 3 Expressive shapes (MaterialShapes): Cookie 9, Cookie 12, Clover 4-leaf, Clover 8-leaf, Sunny,
        // Soft burst, Cookie 6, Cookie 7, Cookie 4.
        int[] lobes = {9, 12, 4, 8, 14, 10, 6, 7, 4};
        float[] depths = {0.08f, 0.06f, 0.26f, 0.16f, 0.05f, 0.11f, 0.12f, 0.10f, 0.16f};
        float[] sharp = {1f, 1f, 0.55f, 0.65f, 1f, 2.4f, 1f, 1f, 1f};
        int k = Math.abs(pkg.hashCode()) % lobes.length;
        cookie.morphTo(lobes[k], depths[k], sharp[k]);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(Motion.DEFAULT_SPATIAL_MS);
        a.setInterpolator(Motion.DEFAULT_SPATIAL);
        a.addUpdateListener(an -> cookie.setProgress((float) an.getAnimatedValue()));
        a.start();
    }

    /** Cross-fade every tinted surface from the current palette to the next. */
    private void retint(Palette next) {
        if (next.seed == palette.seed) return;
        final Palette from = palette;
        palette = next;
        if (colorAnimator != null) colorAnimator.cancel();
        ArgbEvaluator argb = new ArgbEvaluator();
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.setDuration(Motion.DEFAULT_EFFECTS_MS);
        colorAnimator.setInterpolator(Motion.DEFAULT_EFFECTS);
        colorAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            int top = (int) argb.evaluate(t, from.surfaceBright, next.surfaceBright);
            int bottom = (int) argb.evaluate(t, from.surface, next.surface);
            backdrop.setColors(new int[]{top, bottom});
            cookie.setColor((int) argb.evaluate(t, from.primaryContainer, next.primaryContainer));
            int containerColor = (int) argb.evaluate(t, from.container, next.container);
            dockShape.setColor(withAlpha(containerColor, 0xB3));
            if (leftScrimShape != null && rightScrimShape != null) {
                leftScrimShape.setColors(new int[]{withAlpha(containerColor, 0xF0), Color.TRANSPARENT});
                rightScrimShape.setColors(new int[]{Color.TRANSPARENT, withAlpha(containerColor, 0xF0)});
            }
            if (focusTitle != null) {
                focusTitle.setTextColor((int) argb.evaluate(t, from.onSurface, next.onSurface));
            }
        });
        colorAnimator.start();
        nav.setPalette(next);
        if (wifiPill != null) {
            wifiPill.setPalette(next);
        }
        for (EqualizerBadge badge : dockBadges.values()) {
            badge.setColors(0x00000000, next.onSurfaceVariant);
        }
        styleNowPlaying();
    }

    private Intent launchIntent(String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    // ---- pages ----

    private void showHome() {
        showingApps = false;
        appsPage.setVisibility(View.GONE);
        homePage.setVisibility(View.VISIBLE);
        nav.select(0);
        if (nowCard != null) showNowPlaying();
        restoreDockFocus();
    }

    private void showApps() {
        showingApps = true;
        fillApps();
        homePage.setVisibility(View.GONE);
        appsPage.setVisibility(View.VISIBLE);
        appsPage.scrollTo(0, 0);
        nav.select(1);
        if (nowCard != null) nowCard.setVisibility(View.GONE);
        if (appsGrid.getChildCount() > 0) {
            View first = appsGrid.getChildAt(0);
            View firstCard = cardFromHolder(first);
            if (firstCard != null) firstCard.requestFocus();
            else if (first != null) first.requestFocus();
        }
    }

    // ---- Now Playing ----

    private View buildNowPlaying() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(14), dp(12));
        nowShape = new GradientDrawable();
        // Use the measured height so the card stays a pill when text or font scaling changes its height.
        card.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                nowShape.setCornerRadius((bottom - top) / 2f));
        card.setBackground(nowShape);
        card.setVisibility(View.GONE);

        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artShape = new GradientDrawable();
        artShape.setCornerRadius(dp(Focus.MEDIA));
        artShape.setColor(0x33FFFFFF);
        nowArt.setBackground(artShape);
        nowArt.setClipToOutline(true);
        card.addView(nowArt, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, dp(10), 0);
        TextView label = text("СЕЙЧАС ИГРАЕТ", 12, palette.onSurfaceVariant, 760);
        label.setLetterSpacing(0.2f);
        words.addView(label);
        nowTitle = text("", 16, palette.onSurface, 760);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowTitle.setPadding(0, dp(3), 0, 0);
        words.addView(nowTitle);
        nowArtist = text("", 14, palette.onSurfaceVariant, 500);
        nowArtist.setSingleLine(true);
        nowArtist.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(nowArtist);
        nowProgress = new WavyProgress(this);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        pl.topMargin = dp(6);
        words.addView(nowProgress, pl);
        card.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nowButton = new ImageView(this);
        nowButton.setScaleType(ImageView.ScaleType.CENTER);
        nowButtonShape = new GradientDrawable();
        nowButtonShape.setShape(GradientDrawable.OVAL);
        nowButton.setBackground(nowButtonShape);
        nowButton.setFocusable(true);
        nowButton.setClickable(true);
        nowButton.setContentDescription("Play or pause");
        nowButton.setOnClickListener(v -> togglePlayback());
        nowButton.setOnFocusChangeListener((v, focused) -> styleNowPlaying());
        card.addView(nowButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        styleNowPlaying();
        return card;
    }

    private void styleNowPlaying() {
        if (nowShape == null) return;
        nowShape.setColor(withAlpha(palette.container, 0xD9));
        boolean focused = nowButton.hasFocus();
        nowButtonShape.setColor(focused ? palette.inverseSurface : palette.primary);
        nowButton.setImageTintList(ColorStateList.valueOf(focused ? palette.inverseOnSurface : palette.onPrimary));
        nowTitle.setTextColor(palette.onSurface);
        nowArtist.setTextColor(palette.onSurfaceVariant);
        nowProgress.setColors(palette.primary, withAlpha(palette.onSurfaceVariant, 0x55));
    }

    private void startNowPlaying() {
        try {
            if (sessions == null) {
                sessions = getSystemService(MediaSessionManager.class);
                mediaListener = new ComponentName(this, MediaListener.class);
            }
            if (!listening) {
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, mediaListener);
                listening = true;
            }
            pickSession();
        } catch (SecurityException notAllowed) {
            // Notification access not granted: the card simply stays hidden.
            nowCard.setVisibility(View.GONE);
        }
    }

    private void stopNowPlaying() {
        if (listening) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignored) {
            }
            listening = false;
        }
        if (playing != null) playing.unregisterCallback(mediaCallback);
        playing = null;
        liveMeta = null;
        liveState = null;
    }

    private void pickSession() {
        if (!listening) return;   // a change posted before onDestroy let the listener go: nothing to pick for
        MediaController best = null;
        MediaMetadata bestMeta = null;
        PlaybackState bestState = null;
        try {
            for (MediaController c : sessions.getActiveSessions(mediaListener)) {
                MediaMetadata m = c.getMetadata();
                if (m == null) continue;
                PlaybackState st = c.getPlaybackState();
                if (best == null || (st != null && st.getState() == PlaybackState.STATE_PLAYING)) {
                    best = c;
                    bestMeta = m;
                    bestState = st;
                }
            }
        } catch (SecurityException e) {
            best = null;
        }
        // getActiveSessions hands out a new controller object for the same session each time: the callback is
        // registered on exactly one object per token, so keep that object when the token is unchanged (a register or
        // unregister on a fresh twin would leave the old registration writing liveMeta/liveState for a stale session).
        boolean same = playing != null && best != null && best.getSessionToken().equals(playing.getSessionToken());
        if (same) {
            best = playing;
        } else {
            if (playing != null) playing.unregisterCallback(mediaCallback);
            if (best != null) {
                best.registerCallback(mediaCallback, handler);
                sessionPlayFailed = false;
            }
        }
        playing = best;
        liveMeta = bestMeta;
        liveState = bestState;
        showNowPlaying();
    }

    /** The device's "on" dot: visible while the HDMI device reports connected (on), hidden in standby or unplugged. */
    private void showDeviceState(String inputId, int state) {
        View dot = deviceDots.get(inputId);
        if (dot == null) return;
        boolean on = state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
        if (on == (dot.getVisibility() == View.VISIBLE)) return;
        if (on) {
            dot.setScaleX(0f);
            dot.setScaleY(0f);
            dot.setVisibility(View.VISIBLE);
            dot.animate().scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
        } else {
            dot.animate().scaleX(0f).scaleY(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                    .withEndAction(() -> dot.setVisibility(View.GONE)).start();
        }
    }

    /** Show the equalizer badge on the dock tile of the app that's playing (if it's in the dock). */
    private void updateBadges() {
        PlaybackState st = playing != null ? playing.getPlaybackState() : null;
        String pkg = st != null && st.getState() == PlaybackState.STATE_PLAYING ? playing.getPackageName() : null;
        for (Map.Entry<String, EqualizerBadge> e : dockBadges.entrySet()) {
            boolean on = e.getKey().equals(pkg);
            EqualizerBadge b = e.getValue();
            if (on && b.getVisibility() != View.VISIBLE) {
                b.setAlpha(0f);
                b.setScaleX(0.6f);
                b.setScaleY(0.6f);
                b.setVisibility(View.VISIBLE);
                b.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Motion.FAST_SPATIAL).setDuration(Motion.FAST_SPATIAL_MS).start();
            } else if (!on && b.getVisibility() == View.VISIBLE) {
                b.animate().alpha(0f).setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                        .withEndAction(() -> b.setVisibility(View.GONE)).start();
            }
        }
    }

    // The last thing that played, kept after its app lets the session go (YouTube stops as soon as you leave it),
    // with whatever the app gave us to get back to it: an item id/uri or the session's own activity when it posts
    // one. When neither is exposed, restore the app's task and resume only its matching item and saved position.
    // The fields follow every session callback, also while another app has the screen; views are touched on Home only.
    private static final long REMEMBER_MS = 6 * 3_600_000L;
    private static final long RESUME_CHECK_MS = 1500;
    private boolean listening;               // the active-sessions listener is registered (until onDestroy)
    private boolean foreground;              // between onResume and onPause: the card may be drawn
    private MediaMetadata liveMeta;          // what the picked session last reported (one fetch per pick, then callbacks)
    private PlaybackState liveState;
    private CharSequence lastTitle, lastArtist;
    private Bitmap lastArt, shownArt;
    private String shownPkgArt;
    private String lastPkg, lastItem, lastMediaId, lastMediaUri;
    private PendingIntent lastSessionActivity;
    private int lastState;
    private float lastSpeed;
    private long lastPosition, lastUpdateTime, lastDuration, lastSeenAt;
    private boolean wasActive, showingRemembered, sessionPlayFailed;
    private MediaResume pendingResume;
    private final Runnable expire = () -> {
        forget();
        if (foreground && nowCard != null) showNowPlaying();
    };
    // play() went to a session that only said it could play: if nothing is playing after a moment, go on to the app.
    private final Runnable checkResumed = () -> {
        if (!foreground) return;
        PlaybackState state = playing != null ? playing.getPlaybackState() : null;
        if (state != null && (state.getState() == PlaybackState.STATE_PLAYING || state.getState() == PlaybackState.STATE_BUFFERING)) return;
        sessionPlayFailed = true;
        resumeRemembered();
    };

    private void showNowPlaying() {
        MediaMetadata m = playing != null ? liveMeta : null;
        PlaybackState st = playing != null ? liveState : null;
        int state = st != null ? st.getState() : PlaybackState.STATE_NONE;
        boolean running = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING;
        boolean active = m != null && (running || state == PlaybackState.STATE_PAUSED);
        if (active) remember(m, st);
        else if (wasActive) freeze();
        wasActive = active;
        if (!foreground || nowCard == null) return;
        updateBadges();
        if (!active && !showingApps && lastPkg != null
                && System.currentTimeMillis() - lastSeenAt < REMEMBER_MS && launchIntent(lastPkg) != null) {
            showRemembered();
            return;
        }
        showingRemembered = false;
        if (!active || showingApps) {
            if (nowCard.getVisibility() == View.VISIBLE) {
                nowCard.animate().alpha(0f).setDuration(200).withEndAction(() -> nowCard.setVisibility(View.GONE)).start();
            }
            return;
        }
        if (running) handler.removeCallbacks(checkResumed);
        nowTitle.setText(lastTitle);
        nowArtist.setText(lastArtist);
        showArt();
        nowProgress.setVisibility(lastDuration > 0 ? View.VISIBLE : View.GONE);
        nowProgress.setState(st.getPosition(), st.getLastPositionUpdateTime(), st.getPlaybackSpeed(), lastDuration, running);
        nowButton.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        styleNowPlaying();
        // Cancel a pending fade-out first: its end action would otherwise hide the card again.
        nowCard.animate().cancel();
        if (nowCard.getVisibility() != View.VISIBLE || nowCard.getAlpha() < 1f) {
            nowCard.setAlpha(0f);
            nowCard.setTranslationY(dp(10));
            nowCard.setVisibility(View.VISIBLE);
            nowCard.animate().alpha(1f).translationY(0).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
        }
    }

    /**
     * Note what the live session shows, on every callback, so the card can bring it back once the session is gone.
     * A session reports several times a second, so anything that costs a binder or PackageManager call (the session
     * activity, the app label standing in for a missing artist) or copies the art is done once per item only.
     */
    private void remember(MediaMetadata m, PlaybackState st) {
        CharSequence title = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String pkg = playing.getPackageName();
        String item = pkg + "|" + m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) + "|" + title + "|" + artist + "|"
                + m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        if (!item.equals(lastItem)) {
            lastItem = item;
            lastTitle = title != null ? title : "";
            lastArtist = artist != null ? artist : appLabel(pkg);
            lastPkg = pkg;
            lastSessionActivity = playing.getSessionActivity();
            lastArt = null;
        }
        // The art copies made the card flicker: fetch it for a new item only, or until it arrives for the current one.
        if (lastArt == null) {
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art == null && m.getDescription() != null) {
                art = m.getDescription().getIconBitmap();
            }
            if (art == null) {
                String uriStr = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                if (uriStr == null) uriStr = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
                if (uriStr == null) uriStr = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                if (uriStr == null && m.getDescription() != null && m.getDescription().getIconUri() != null) {
                    uriStr = m.getDescription().getIconUri().toString();
                }
                if (uriStr != null && uriStr.startsWith("content://")) {
                    try (java.io.InputStream in = getContentResolver().openInputStream(Uri.parse(uriStr))) {
                        art = BitmapFactory.decodeStream(in);
                    } catch (Throwable ignored) {}
                }
            }
            lastArt = art;
        }
        lastMediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        lastMediaUri = m.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
        lastDuration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        lastState = st.getState();
        lastPosition = st.getPosition();
        lastUpdateTime = st.getLastPositionUpdateTime();
        lastSpeed = st.getPlaybackSpeed();
        lastSeenAt = System.currentTimeMillis();
        if (lastState == PlaybackState.STATE_PLAYING || lastState == PlaybackState.STATE_BUFFERING) sessionPlayFailed = false;
        handler.removeCallbacks(expire);
    }

    /** The session stopped or went away: pin the position it had at this moment and start the 6 h clock. */
    private void freeze() {
        lastPosition = rememberedPosition();
        lastState = PlaybackState.STATE_PAUSED;
        lastSpeed = 0f;
        lastUpdateTime = android.os.SystemClock.elapsedRealtime();
        lastSeenAt = System.currentTimeMillis();
        sessionPlayFailed = false;
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, REMEMBER_MS);
    }

    /** Where the remembered item is now: the last reported position, run forward while it was playing, within its length. */
    private long rememberedPosition() {
        long p = lastPosition;
        if (lastState == PlaybackState.STATE_PLAYING) {
            p += (long) ((android.os.SystemClock.elapsedRealtime() - lastUpdateTime) * lastSpeed);
        }
        if (lastDuration > 0) p = Math.min(p, lastDuration);
        return Math.max(0, p);
    }

    /** Six hours on: nothing to come back to, and no artwork or app intent kept around (the view lets go of it too). */
    private void forget() {
        lastPkg = lastItem = lastMediaId = lastMediaUri = null;
        lastTitle = lastArtist = null;
        lastArt = shownArt = null;
        shownPkgArt = null;
        if (nowArt != null) {
            nowArt.setImageDrawable(null);
            nowArt.setPadding(0, 0, 0, 0);
        }
        lastSessionActivity = null;
        lastPosition = lastDuration = 0;
        handler.removeCallbacks(expire);
    }

    private void showArt() {
        if (shownArt == lastArt && ((lastArt != null) || (lastPkg != null && lastPkg.equals(shownPkgArt)))) return;
        if (lastArt != null) {
            nowArt.setImageBitmap(lastArt);
            nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            nowArt.setPadding(0, 0, 0, 0);
            shownArt = lastArt;
            shownPkgArt = null;
        } else if (lastPkg != null) {
            try {
                Drawable icon = pm.getApplicationIcon(lastPkg);
                nowArt.setImageDrawable(icon);
                nowArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int p = dp(8);
                nowArt.setPadding(p, p, p, p);
            } catch (Exception e) {
                nowArt.setImageDrawable(null);
                nowArt.setPadding(0, 0, 0, 0);
            }
            shownArt = null;
            shownPkgArt = lastPkg;
        } else {
            nowArt.setImageDrawable(null);
            nowArt.setPadding(0, 0, 0, 0);
            shownArt = null;
            shownPkgArt = null;
        }
    }

    /** The remembered card: paused at where you left off; play brings that item back (see resumeRemembered). */
    private void showRemembered() {
        showingRemembered = true;
        nowTitle.setText(lastTitle != null ? lastTitle : "");
        nowArtist.setText(lastArtist != null ? lastArtist : appLabel(lastPkg));
        showArt();
        nowProgress.setVisibility(lastDuration > 0 ? View.VISIBLE : View.GONE);
        nowProgress.setState(rememberedPosition(), android.os.SystemClock.elapsedRealtime(), 0f, lastDuration, false);
        // Never fake it: the pause icon appears only once a session reports playing again (through showNowPlaying).
        nowButton.setImageResource(R.drawable.ic_play);
        styleNowPlaying();
        nowCard.animate().cancel();
        if (nowCard.getVisibility() != View.VISIBLE || nowCard.getAlpha() < 1f) {
            nowCard.setAlpha(1f);
            nowCard.setTranslationY(0);
            nowCard.setVisibility(View.VISIBLE);
        }
    }

    private void togglePlayback() {
        if (showingRemembered) {
            resumeRemembered();
            return;
        }
        if (playing == null) return;
        PlaybackState st = playing.getPlaybackState();
        boolean running = st != null
                && (st.getState() == PlaybackState.STATE_PLAYING || st.getState() == PlaybackState.STATE_BUFFERING);
        if (running) playing.getTransportControls().pause();
        else resumeRemembered();
    }

    /**
     * Play the remembered item the most direct way its app still allows, in this order: the session itself if it
     * quietly came back (keeps playing in the background), a deep link to the exact item where we can read its id,
     * the session's own activity for apps whose ids we cannot read, and last the existing app task with a bounded
     * request to resume the matching title and position. Never substitute a search result for the remembered video.
     */
    private void resumeRemembered() {
        if (lastPkg == null) return;
        handler.removeCallbacks(checkResumed);
        PlaybackState st = playing != null && lastPkg.equals(playing.getPackageName()) ? playing.getPlaybackState() : null;
        if (!sessionPlayFailed && st != null && st.getState() != PlaybackState.STATE_PLAYING
                && (st.getActions() & (PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)) != 0) {
            playing.getTransportControls().play();
            handler.postDelayed(checkResumed, RESUME_CHECK_MS);
            return;
        }
        Intent deep = resumeIntent(lastPkg, lastMediaId, lastMediaUri, rememberedPosition());
        if (deep != null && pm.resolveActivity(deep, 0) != null) {
            launch(deep);
            return;
        }
        if (lastSessionActivity != null) {
            try {
                prepareResume();
                lastSessionActivity.send();
                pendingResume.start();
                return;
            } catch (PendingIntent.CanceledException gone) {
                if (pendingResume != null) pendingResume.cancel();
                lastSessionActivity = null;
            }
        }
        Intent back = launchIntent(lastPkg);
        if (back != null) {
            prepareResume();
            // Reuse the existing task without asking the app to reset its playback screen.
            back.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            launch(back);
            pendingResume.start();
        }
    }

    private void prepareResume() {
        if (pendingResume != null) pendingResume.cancel();
        pendingResume = new MediaResume(this, handler, lastPkg, lastTitle, rememberedPosition());
    }

    private static final String YOUTUBE = "com.google.android.youtube.tv";
    // An 11-character YouTube video id: after a watch?v= / youtu.be/ / embed/ marker in a URL, or standing alone in
    // anything that is not a URL. A bare token must carry a digit, capital, '-' or '_' so that an ordinary word is not
    // taken for an id ("recommended" and "googlevideo" are 11 letters too). Checked on the JVM with the same patterns:
    //   dQw4w9WgXcQ, yt:video:dQw4w9WgXcQ, watch?v=.., watch?feature=share&v=..&t=10, youtu.be/.., /embed/.. -> the id
    //   recommended, PL<34-char playlist id>, spotify:track:.., https://r3---sn-abc.googlevideo.com/.. -> nothing
    private static final Pattern YOUTUBE_URL_ID = Pattern.compile("(?:[?&]v=|youtu\\.be/|/embed/|/v/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");
    private static final Pattern YOUTUBE_BARE_ID = Pattern.compile("(?<![A-Za-z0-9_-])(?=[a-z]*[A-Z0-9_-])([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])");

    /**
     * A deep link to the remembered item for the apps whose ids we know how to read, or null to leave it to the
     * session's own activity or task restoration. Measured on the TV on 2026-10-18, neither app we care most about
     * gives us an item id, so for both this returns null:
     *   Spotify  posts neither METADATA_KEY_MEDIA_ID nor MEDIA_URI (only display icon, duration, album-art uri,
     *            advertisement, album, title, artist, album art) and has no session activity.
     *   YouTube  (com.google.android.youtube.tv) posts only DURATION, an empty ALBUM and TITLE (ARTIST/ART possibly
     *            filtered); no MEDIA_ID, no MEDIA_URI, no session activity, empty extras, null queue.
     * The branches below are kept for apps or builds that do post such ids; their formats are UNVERIFIED:
     *   "spotify:track:<22 chars>" style URIs are opened as is; an 11-character YouTube video id, bare or inside a
     *   watch / youtu.be URL, is opened as https://www.youtube.com/watch?v=<id> with &t=<seconds> when a position is
     *   known; any other https URL is opened in the same app.
     */
    static Intent resumeIntent(String pkg, String mediaId, String mediaUri, long positionMs) {
        String[] keys = {mediaUri, mediaId};
        for (String k : keys) {
            if (k != null && k.startsWith("spotify:")) return view(pkg, k);
        }
        if (YOUTUBE.equals(pkg)) {
            String id = youtubeId(mediaUri);
            if (id == null) id = youtubeId(mediaId);
            if (id != null) {
                String url = "https://www.youtube.com/watch?v=" + id;
                if (positionMs > 0) url += "&t=" + positionMs / 1000;
                return view(pkg, url);
            }
        }
        for (String k : keys) {
            if (k != null && k.startsWith("https://")) return view(pkg, k);
        }
        return null;
    }

    private static Intent view(String pkg, String uri) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
    }

    private static String youtubeId(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = (s.contains("://") ? YOUTUBE_URL_ID : YOUTUBE_BARE_ID).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private CharSequence appLabel(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void showAppsContextMenu(String pkg) {
        boolean pinned = PinnedApps.isPinned(this, pkg);
        CharSequence label = appLabel(pkg);
        String actionText = pinned ? "Открепить от главного экрана" : "Закрепить на главном экране";
        String[] items = new String[]{actionText, "Настройки приложения"};
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (pinned) {
                            unpinApp(pkg, label);
                        } else {
                            pinAndRefresh(pkg, label);
                        }
                    } else if (which == 1) {
                        openAppSettings(pkg);
                    }
                })
                .show();
    }

    private void showDockContextMenu(String pkg, CharSequence label) {
        if (pkg != null && pkg.startsWith("input:")) return;
        List<String> allPinned = PinnedApps.load(this);
        boolean pinned = allPinned.contains(pkg);
        if (pinned) {
            boolean canReorder = allPinned.size() > 1;
            String[] items = canReorder
                    ? new String[]{"Открепить", "Переместить", "Настройки приложения"}
                    : new String[]{"Открепить", "Настройки приложения"};
            new AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(items, (d, which) -> {
                        if (which == 0) unpinApp(pkg, label);
                        else if (which == 1 && canReorder) startReordering(pkg);
                        else openAppSettings(pkg);
                    }).show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(new String[]{"Закрепить на главном экране", "Настройки приложения"}, (d, which) -> {
                        if (which == 0) {
                            pinAndRefresh(pkg, label);
                        } else if (which == 1) {
                            openAppSettings(pkg);
                        }
                    }).show();
        }
    }

    private void openAppSettings(String pkg) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", pkg, null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            launch(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void unpinApp(String pkg, CharSequence label) {
        View card = dock != null ? dock.findViewWithTag(pkg) : null;
        View holder = card != null ? findTileHolder(card) : null;
        List<String> pinned = PinnedApps.load(this);
        boolean isLast = pinned.size() <= 1 && cecDevices().isEmpty();

        if (holder != null && !showingApps) {
            if (isLast) {
                View plusCard = dock.findViewWithTag(":compact_plus:");
                View plusHolder = plusCard != null ? findTileHolder(plusCard) : null;
                if (plusHolder != null) {
                    plusHolder.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
                            .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS).start();
                }
            }
            holder.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
                    .setInterpolator(Motion.FAST_EFFECTS).setDuration(Motion.FAST_EFFECTS_MS)
                    .withEndAction(() -> {
                        PinnedApps.unpin(this, pkg);
                        dockKey = "";
                        fillDock(true);
                        Toast.makeText(this, label + " откреплено", Toast.LENGTH_SHORT).show();
                    }).start();
        } else {
            PinnedApps.unpin(this, pkg);
            dockKey = "";
            fillDock(true);
            Toast.makeText(this, label + " откреплено", Toast.LENGTH_SHORT).show();
        }
    }

    private void startReordering(String pkg) {
        reorderingPkg = pkg;
        dockKey = "";
        fillDock(false);
        focusTileForPkg(pkg);
        Toast.makeText(this, "Перемещайте стрелками ◀ ▶, затем нажмите ОК", Toast.LENGTH_LONG).show();
    }

    private void moveReorderingApp(int direction) {
        if (reorderingPkg == null) return;
        List<String> pinned = PinnedApps.load(this);
        int index = pinned.indexOf(reorderingPkg);
        if (index < 0) return;
        int target = index + direction;
        if (target < 0 || target >= pinned.size()) return;
        Collections.swap(pinned, index, target);
        PinnedApps.save(this, pinned);
        dockKey = "";
        fillDock(false);
        focusTileForPkg(reorderingPkg);
    }

    private void finishReordering(boolean confirmed) {
        String pkg = reorderingPkg;
        reorderingPkg = null;
        dockKey = "";
        fillDock(false);
        if (pkg != null) focusTileForPkg(pkg);
        if (confirmed) {
            Toast.makeText(this, "Порядок сохранён", Toast.LENGTH_SHORT).show();
        }
    }

    private void focusTileForPkg(String targetPkg) {
        if (targetPkg == null) return;
        View card = dock.findViewWithTag(targetPkg);
        if (card != null) {
            card.requestFocus();
            View holder = findTileHolder(card);
            if (holder != null) scrollDockToTile(holder);
        }
    }

    private View findTileHolder(View view) {
        View v = view;
        while (v != null && v != dock) {
            if (v.getParent() == dock) return v;
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return null;
    }

    private View cardFromHolder(View holder) {
        if (!(holder instanceof ViewGroup)) return null;
        ViewGroup vg = (ViewGroup) holder;
        if (vg.getChildCount() > 0 && vg.getChildAt(0) instanceof ViewGroup) {
            ViewGroup ring = (ViewGroup) vg.getChildAt(0);
            if (ring.getChildCount() > 0) return ring.getChildAt(0);
        }
        return null;
    }

    private boolean isChildOf(View view, View parent) {
        View v = view;
        while (v != null) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return false;
    }

    private void updateScrollScrims() {
        if (scroller == null || leftScrim == null || rightScrim == null) return;
        boolean canLeft = scroller.canScrollHorizontally(-1);
        boolean canRight = scroller.canScrollHorizontally(1);
        leftScrim.animate().alpha(canLeft ? 1f : 0f).setDuration(Motion.FAST_EFFECTS_MS).start();
        rightScrim.animate().alpha(canRight ? 1f : 0f).setDuration(Motion.FAST_EFFECTS_MS).start();
    }

    private void scrollDockToTile(View holder) {
        if (scroller == null || dock == null || holder == null) return;
        View actualHolder = findTileHolder(holder);
        if (actualHolder == null || !actualHolder.isAttachedToWindow() || actualHolder.getParent() != dock) return;
        if (actualHolder.getWidth() <= 0 || scroller.getWidth() <= 0) {
            actualHolder.post(() -> {
                if (actualHolder.isAttachedToWindow() && actualHolder.getParent() == dock) {
                    scrollDockToTile(actualHolder);
                }
            });
            return;
        }

        int index = dock.indexOfChild(actualHolder);
        int total = dock.getChildCount();
        if (index < 0 || total == 0) return;

        int holderLeft = actualHolder.getLeft();
        int holderRight = actualHolder.getRight();
        int scrollerWidth = scroller.getWidth();
        int padLeft = scroller.getPaddingLeft();
        int padRight = scroller.getPaddingRight();
        int viewWidth = scrollerWidth - padLeft - padRight;
        if (viewWidth <= 0) return;

        int scrollX = scroller.getScrollX();
        int viewLeft = scrollX;
        int viewRight = scrollX + viewWidth;

        int margin = dp(28);
        int peek = dp(76);

        int targetScroll = scrollX;
        boolean hasNext = index < total - 1;
        int neededRight = holderRight + margin + (hasNext ? peek : 0);
        if (neededRight > viewRight) {
            targetScroll += (neededRight - viewRight);
        }

        boolean hasPrev = index > 0;
        int neededLeft = holderLeft - margin - (hasPrev ? peek : 0);
        if (neededLeft < viewLeft) {
            targetScroll += (neededLeft - viewLeft);
        }

        int maxScroll = Math.max(0, dock.getWidth() - viewWidth);
        targetScroll = Math.max(0, Math.min(targetScroll, maxScroll));

        if (targetScroll == scrollX && (dockScrollAnim == null || !dockScrollAnim.isRunning())) {
            dockScrollTarget = targetScroll;
            return;
        }

        if (dockScrollAnim != null && dockScrollAnim.isRunning() && dockScrollTarget == targetScroll) {
            return;
        }

        dockScrollTarget = targetScroll;
        if (dockScrollAnim != null) dockScrollAnim.cancel();
        dockScrollAnim = ValueAnimator.ofInt(scroller.getScrollX(), targetScroll);
        dockScrollAnim.setDuration(Motion.FAST_SPATIAL_MS);
        dockScrollAnim.setInterpolator(Motion.FAST_SPATIAL);
        dockScrollAnim.addUpdateListener(a -> {
            if (scroller != null) scroller.scrollTo((int) a.getAnimatedValue(), 0);
        });
        dockScrollAnim.start();
    }

    // ---- motion ----

    private void arrive(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(dp(18));
        v.animate().alpha(1f).translationY(0).setStartDelay(40L * index).setInterpolator(Motion.DEFAULT_SPATIAL).setDuration(Motion.DEFAULT_SPATIAL_MS).start();
    }

    // ---- helpers ----

    /** Google Sans Flex at a given weight, standard terminals (axes 'wght', 'opsz'; 'ROND' 0); falls back to sans-serif. */
    private Typeface font(int requested) {
        return font(requested, null);
    }

    private Typeface font(int requested, CharSequence text) {
        int weight = requested <= 400 ? 300 : requested <= 650 ? 560 : requested <= 790 ? 760 : 820;
        return getCachedFont(weight, 24, hasCyrillic(text));
    }

    private TextView text(CharSequence value, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(weight, value));
        return t;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float value) {
        return Focus.dp(this, value);
    }
}
