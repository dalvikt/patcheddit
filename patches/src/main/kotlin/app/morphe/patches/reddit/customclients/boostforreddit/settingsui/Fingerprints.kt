/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients.boostforreddit.settingsui

import app.morphe.patcher.Fingerprint

// Toolbar back-arrow handler — in two-pane mode this finish()es out instead of closing the pane.
internal val onSupportNavigateUpFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/preferences/v2/SettingsActivityCompat;",
    name = "onSupportNavigateUp",
    parameters = listOf(),
    returnType = "Z",
)

// Settings search activity — its search bar sits flush against the right edge.
internal val searchActivityOnCreateFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/preferences/SettingsSearchActivity;",
    name = "onCreate",
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "V",
)

// Two-pane navigation — where the detail page is opened (and the toolbar title should follow).
internal val headerOnPreferenceStartFragmentFingerprint = Fingerprint(
    definingClass = "Landroidx/preference/PreferenceHeaderFragmentCompat;",
    name = "onPreferenceStartFragment",
    parameters = listOf("Landroidx/preference/PreferenceFragmentCompat;", "Landroidx/preference/Preference;"),
    returnType = "Z",
)

// SlidingPaneLayout.closePane() (obfuscated) — runs when the detail pane closes (back/swipe).
internal val closePaneFingerprint = Fingerprint(
    definingClass = "Landroidx/slidingpanelayout/widget/SlidingPaneLayout;",
    name = "b",
    parameters = listOf(),
    returnType = "Z",
)

// Fires when any preference page's view is created — where we show that page's name in the toolbar.
internal val preferenceFragmentOnViewCreatedFingerprint = Fingerprint(
    definingClass = "Landroidx/preference/PreferenceFragmentCompat;",
    name = "onViewCreated",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
    returnType = "V",
)

// Settings-search result tap — we read the tapped item's name so the opened page can show it.
internal val searchTapFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/preferences/SettingsSearchActivity;",
    name = "J",
    parameters = listOf("Lid/a;"),
    returnType = "V",
)
