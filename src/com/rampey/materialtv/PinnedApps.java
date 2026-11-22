package com.rampey.materialtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/** SharedPreferences-backed ordered set of pinned packages. */
final class PinnedApps {
    private static final String PREFS = "pinned";
    private static final String KEY = "packages";
    private static List<String> cachedList = null;

    private PinnedApps() {}

    static synchronized List<String> load(Context ctx) {
        if (cachedList != null) return new ArrayList<>(cachedList);
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (raw == null || raw.isEmpty()) {
            cachedList = new ArrayList<>();
            return new ArrayList<>();
        }
        String[] parts = raw.split(",");
        List<String> list = new ArrayList<>();
        for (String s : parts) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                list.add(trimmed);
            }
        }
        cachedList = new ArrayList<>(list);
        return list;
    }

    static synchronized void save(Context ctx, List<String> packages) {
        cachedList = new ArrayList<>(packages);
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString(KEY, TextUtils.join(",", packages));
        ed.apply();
    }

    static synchronized void pin(Context ctx, String pkg) {
        List<String> list = load(ctx);
        if (!list.contains(pkg)) {
            list.add(pkg);
            save(ctx, list);
        }
    }

    static synchronized void unpin(Context ctx, String pkg) {
        List<String> list = load(ctx);
        if (list.remove(pkg)) {
            save(ctx, list);
        }
    }

    static synchronized boolean isPinned(Context ctx, String pkg) {
        return load(ctx).contains(pkg);
    }
}
