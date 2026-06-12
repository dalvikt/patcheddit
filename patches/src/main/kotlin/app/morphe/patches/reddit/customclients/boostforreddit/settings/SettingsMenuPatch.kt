/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients.boostforreddit.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.customclients.boostforreddit.BoostCompatible
import app.morphe.patches.reddit.customclients.boostforreddit.misc.extension.sharedExtensionPatch
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/boostforreddit/settings/MorpheSettingsMenu;"

/**
 * Add a "Morphe" entry to Boost's settings that opens a page of runtime toggles registered by other
 * patches (see MorpheSettings). All UI lives in the extension; this patch only appends the entry to
 * the settings header fragment, which navigates to the page like any other preference fragment.
 */
@Suppress("unused")
val settingsMenuPatch = bytecodePatch(
    name = "Morphe settings menu",
    default = false,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*BoostCompatible)

    execute {
        // append the "Morphe" entry to the root settings list
        settingsHeaderOnCreatePreferencesFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(
                returnIndex,
                "invoke-static/range { p0 .. p0 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->addMorpheEntry(Ljava/lang/Object;)V",
            )
        }

        // repopulate the reused fragment with the registered toggles when it is our page
        miscFragmentOnCreatePreferencesFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(
                returnIndex,
                "invoke-static/range { p0 .. p0 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->buildMorphePage(Ljava/lang/Object;)V",
            )
        }

        // append the Morphe toggles to the settings-search index (the returned List)
        searchIndexFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val listRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructions(
                returnIndex,
                "invoke-static/range { v$listRegister .. v$listRegister }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->addSearchItems(Ljava/lang/Object;)V",
            )
        }

        // route a tap on one of our search entries to the Morphe page, skipping Boost's default
        searchTapFingerprint.method.apply {
            val firstInstruction = getInstruction(0)
            addInstructionsWithLabels(
                0,
                "invoke-static { p0, p1 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->handleSearchTap(Ljava/lang/Object;Ljava/lang/Object;)Z\n" +
                    "move-result v0\n" +
                    "if-eqz v0, :morphe_continue\n" +
                    "return-void",
                ExternalLabel("morphe_continue", firstInstruction),
            )
        }
    }
}
