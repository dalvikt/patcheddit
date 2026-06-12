/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients.boostforreddit.inlineimages

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.customclients.boostforreddit.BoostCompatible
import app.morphe.patches.reddit.customclients.boostforreddit.misc.extension.sharedExtensionPatch
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/boostforreddit/inlineimages/InlineImages;"

/**
 * Render Reddit image-comments (the media_metadata feature) inline in Boost, which silently drops
 * them. All logic lives in the extension; this patch only injects calls to it.
 *
 * invoke-static/range is used where a value comes from a parameter register, because the comment
 * binder is large enough that its parameter registers can exceed v15 (which the 35c invoke form
 * cannot encode). The v1 capture is split into two single-register calls paired by a ThreadLocal,
 * so it never needs the (non-contiguous) model + comment registers in one instruction.
 */
@Suppress("unused")
val inlineCommentImagesPatch = bytecodePatch(
    name = "Inline comment images",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*BoostCompatible)

    execute {
        // 1. capture media_metadata at CommentModel.v1: comment at entry, model at return; the
        //    extension pairs them (v1 is a straight-line static factory, so a ThreadLocal is safe).
        commentModelV1Fingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_OBJECT)
            val modelRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructions(
                returnIndex,
                "invoke-static/range { v$modelRegister .. v$modelRegister }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->stashModel(Ljava/lang/Object;)V",
            )
            addInstructions(
                0,
                "invoke-static/range { p0 .. p0 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->stashComment(Ljava/lang/Object;)V",
            )
        }

        // 2. render inline images after the comment view binds. Capture (view holder, model, and
        //    Boost's Glide RequestManager = the 5th param) at entry — the binder reuses the model's
        //    parameter register for a boolean before it returns, so the registers are not safe to
        //    read at the exit point — then render at exit. The invoke-static/range spans the whole
        //    contiguous p0..p5 parameter range, so renderEnter also receives the three booleans.
        commentViewHolderBindFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(returnIndex, "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->renderExit()V")
            addInstructions(
                0,
                "invoke-static/range { p0 .. p5 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->renderEnter(Ljava/lang/Object;Ljava/lang/Object;ZZZLjava/lang/Object;)V",
            )
        }

        // 3. capture Boost's link handler so a tap on an inline image opens the same viewer.
        setLinkClickedListenerFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p1 }, " +
                "$EXTENSION_CLASS_DESCRIPTOR->captureLink(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // 4. register the runtime toggle + warm Glide once the app context exists.
        myApplicationOnCreateFingerprint.method.apply {
            val returnIndex = indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)
            addInstructions(returnIndex, "invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->init()V")
        }
    }
}
