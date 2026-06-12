/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients.boostforreddit.settingsui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.customclients.boostforreddit.BoostCompatible
import app.morphe.patches.reddit.customclients.boostforreddit.misc.extension.sharedExtensionPatch
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/boostforreddit/settings/SettingsBehavior;"

/**
 * Small two-pane / search fixes to Boost's settings, independent of the Morphe menu: the back-arrow
 * closes the open pane instead of leaving settings, the toolbar shows the opened page's name (and
 * resets to "Settings" on close), and the search bar gets a symmetric right margin. Logic lives in
 * the extension; this patch only injects the calls.
 */
@Suppress("unused")
val settingsUiFixesPatch = bytecodePatch(
    name = "Settings UI fixes",
    default = false,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*BoostCompatible)

    execute {
        // back-arrow → close the detail pane (like swipe-back) instead of finishing the activity
        onSupportNavigateUpFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->navigateUp(Ljava/lang/Object;)Z
                move-result v0
                return v0
            """,
        )

        // give the search bar a right margin symmetric with its top inset
        searchActivityOnCreateFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(
                returnIndex,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->fixSearchBar(Ljava/lang/Object;)V",
            )
        }

        // two-pane header tap: capture the opened page's name (p2 = the tapped preference)
        headerOnPreferenceStartFragmentFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS_DESCRIPTOR->onDetailOpened(Ljava/lang/Object;)V",
        )

        // two-pane: reset the toolbar to "Settings" when the detail pane closes
        closePaneFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN)
            addInstructions(
                returnIndex,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->onListReturned(Ljava/lang/Object;)V",
            )
        }

        // show each page's name in the toolbar when it appears (covers the search/intent path too)
        preferenceFragmentOnViewCreatedFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(
                returnIndex,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->onPageShown(Ljava/lang/Object;)V",
            )
        }

        // capture the tapped search result's name so the page it opens can show it in the toolbar
        searchTapFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->capturePendingTitle(Ljava/lang/Object;)V",
        )
    }
}
