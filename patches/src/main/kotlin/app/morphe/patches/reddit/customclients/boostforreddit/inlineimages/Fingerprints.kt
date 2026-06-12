/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.reddit.customclients.boostforreddit.inlineimages

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// static CommentModel v1(Comment) — where Boost builds a CommentModel from the raw comment, the
// only place media_metadata is still available before it is dropped.
internal val commentModelV1Fingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/models/reddit/CommentModel;",
    name = "v1",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/rubenmayayo/reddit/models/reddit/CommentModel;",
    parameters = listOf("Lnet/dean/jraw/models/Comment;"),
)

// void CommentViewHolder.o(CommentModel, boolean, boolean, boolean, com.bumptech.glide.k) — the
// real comment binder (the obfuscated glide type k is the 5th parameter).
internal val commentViewHolderBindFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/adapters/CommentViewHolder;",
    name = "o",
    returnType = "V",
    parameters = listOf(
        "Lcom/rubenmayayo/reddit/models/reddit/CommentModel;",
        "Z",
        "Z",
        "Z",
        "Lcom/bumptech/glide/k;",
    ),
)

// void TableTextView.setLinkClickedListener(LinkTextView.d) — Boost's per-view link tap handler.
internal val setLinkClickedListenerFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/ui/customviews/TableTextView;",
    name = "setLinkClickedListener",
    parameters = listOf("Lcom/rubenmayayo/reddit/ui/customviews/LinkTextView${'$'}d;"),
)

// MyApplication.onCreate — runs after the shared extension has set the app context.
internal val myApplicationOnCreateFingerprint = Fingerprint(
    definingClass = "Lcom/rubenmayayo/reddit/MyApplication;",
    name = "onCreate",
)
