/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.boostforreddit.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.shared.Utils;

/**
 * A small, always-present settings registry for Morphe patches on Boost.
 *
 * <p>This is deliberately split from the menu UI: any patch can {@link #register} a
 * {@link SettingDescriptor} and gate its behaviour on {@link #getBoolean}, and that works whether
 * or not the separate "settings menu" patch is applied. With no menu patch, nothing ever writes
 * the backing store, so {@link #getBoolean} always returns the caller's default — i.e. every
 * feature runs in its default-enabled state. With the menu patch applied, the menu renders one
 * switch per registered descriptor and persists flips here.
 *
 * <p>Values live in the patcher's own {@code morphe_prefs} file, never in Boost's preferences, so
 * an unpatched (or differently-patched) Boost install has nothing dangling.
 */
public final class MorpheSettings {
    /** Our own prefs file — intentionally separate from Boost's settings. */
    public static final String PREFS_NAME = "morphe_prefs";

    private static final List<SettingDescriptor> DESCRIPTORS = new CopyOnWriteArrayList<>();

    private MorpheSettings() {
    }

    /**
     * Advertise a toggle to the (optional) Morphe menu. Idempotent per key — safe to call from a
     * patch's init each launch. Has no effect on behaviour by itself; the feature still decides
     * what to do by calling {@link #getBoolean}.
     */
    public static void register(SettingDescriptor descriptor) {
        if (descriptor == null || descriptor.key == null) {
            return;
        }
        for (SettingDescriptor existing : DESCRIPTORS) {
            if (existing.key.equals(descriptor.key)) {
                return;
            }
        }
        DESCRIPTORS.add(descriptor);
    }

    /** The registered descriptors, in registration order. Consumed by the menu patch. */
    public static List<SettingDescriptor> getDescriptors() {
        return DESCRIPTORS;
    }

    private static SharedPreferences prefs() {
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The stored value for {@code key}, or {@code defaultValue} when nothing has been stored (the
     * common case when the menu patch is absent, or the context is not ready yet).
     */
    public static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return defaultValue;
        }
        return prefs.getBoolean(key, defaultValue);
    }

    /** Persist a flip. Called by the menu's switch listener. No-op if the context is not ready. */
    public static void setBoolean(String key, boolean value) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        prefs.edit().putBoolean(key, value).apply();
    }
}
