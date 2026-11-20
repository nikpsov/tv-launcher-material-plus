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

    private PinnedApps() {}

    static List<String> load(Context ctx) {
        String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        String[] parts = raw.split(",");
        List<String> list = new ArrayList<>();
        for (String s : parts) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                list.add(trimmed);
            }
        }
        return list;
    }

    static void save(Context ctx, List<String> packages) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString(KEY, TextUtils.join(",", packages));
        ed.apply();
    }

    static void pin(Context ctx, String pkg) {
        List<String> list = load(ctx);
        if (!list.contains(pkg)) {
            list.add(pkg);
            save(ctx, list);
        }
    }

    static void unpin(Context ctx, String pkg) {
        List<String> list = load(ctx);
        if (list.remove(pkg)) {
            save(ctx, list);
        }
    }

    static boolean isPinned(Context ctx, String pkg) {
        return load(ctx).contains(pkg);
    }
}
