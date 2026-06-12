/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.boostforreddit.settings;

/**
 * Describes one runtime toggle that a patch wants surfaced in the Morphe settings menu.
 *
 * <p>A patch registers a descriptor via {@link MorpheSettings#register(SettingDescriptor)} and
 * reads its value via {@link MorpheSettings#getBoolean(String, boolean)}. The descriptor is the
 * <em>only</em> coupling between a feature and the menu: the feature does not reference any menu
 * class, so it keeps working (at {@link #defaultValue}) when the menu patch is not applied.
 */
public final class SettingDescriptor {
    /** Preference key, also the storage key in the {@code morphe_prefs} file. */
    public final String key;
    /** Title shown on the switch row. */
    public final String title;
    /** Optional one-line summary; may be empty. */
    public final String summary;
    /** Group / category label, for future grouping in the menu (e.g. "Morphe"). */
    public final String group;
    /** Value used when the user has never flipped the switch — the feature's built-in default. */
    public final boolean defaultValue;

    public SettingDescriptor(String key, String title, String summary, String group, boolean defaultValue) {
        this.key = key;
        this.title = title;
        this.summary = summary == null ? "" : summary;
        this.group = group == null ? "" : group;
        this.defaultValue = defaultValue;
    }
}
