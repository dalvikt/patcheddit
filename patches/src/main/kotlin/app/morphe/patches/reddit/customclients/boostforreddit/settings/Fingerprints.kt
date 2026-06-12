/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients.boostforreddit.settings

import app.morphe.patcher.Fingerprint

// The root settings list's header fragment — where the "Morphe" entry is appended.
internal val settingsHeaderOnCreatePreferencesFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/preferences/v2/SettingsActivityCompat${'$'}HeaderFragment;",
    name = "onCreatePreferences",
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
    returnType = "V",
)

// The Boost preference fragment the Morphe page reuses — repopulated with the registered toggles.
internal val miscFragmentOnCreatePreferencesFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/preferences/v2/PreferenceFragmentMiscCompat;",
    name = "onCreatePreferences",
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
    returnType = "V",
)

// Builds the settings-search index (a List of search entries) — we append the Morphe toggles.
internal val searchIndexFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/preferences/SettingsSearchActivity;",
    name = "d1",
    parameters = listOf(),
    returnType = "Ljava/util/List;",
)

// Handles a tap on a search result — we intercept taps on the Morphe entries.
internal val searchTapFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/preferences/SettingsSearchActivity;",
    name = "J",
    parameters = listOf("Lid/a;"),
    returnType = "V",
)
