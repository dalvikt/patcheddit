/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.boostforreddit.inlineimages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.morphe.extension.boostforreddit.settings.MorpheSettings
import app.morphe.extension.boostforreddit.settings.SettingDescriptor
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Inline comment-images for Boost. Boost drops `media_metadata` when it builds a CommentModel, so
 * it never renders the image-comments Reddit added; this restores them.
 *
 * Images are loaded through Boost's own Glide: Boost hands the comment binder a Glide
 * `RequestManager` (`com.bumptech.glide.k`) as the 5th parameter of `o`, and in Boost 1.12.12
 * `RequestManager.load(...)` is `t` and `RequestBuilder.into(ImageView)` is `C0` (the decompiled
 * chain is `xb.a.e(ctx).t(url).C0(view)`).
 *
 * To download each image only once, inline loads use the same cache the matching viewer reads:
 * static images load by URL through Glide (Boost's image viewer is also Glide, so it reuses the
 * Glide cache), and GIFs are fetched through Boost's OkHttp (`tb.a.b()`, backed by `ok_cache`) and
 * animated from those bytes — Boost's gif viewer downloads through that same OkHttp/`ok_cache`, so
 * tapping a gif does not fetch it again.
 *
 * The patch injects calls to the static entry points below; Boost-internal classes are reached by
 * reflection (their names are R8-obfuscated and only valid at runtime).
 */
object InlineImages {
    private const val TAG = "MorpheInlineImg"
    const val PREF_KEY = "inline_comment_images"
    private const val INJECTED = "boost_inline_img"

    // Boost's obfuscated Glide method names (1.12.12). RequestManager.load(String) / into(ImageView).
    private const val GLIDE_LOAD = "t"
    private const val GLIDE_INTO = "C0"

    private val mediaByComment = Collections.synchronizedMap(WeakHashMap<Any, Pair<String?, String?>>())
    private val linkListenerByTable = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    // url -> [w,h], learned the first time an image renders, so revisiting a comment (e.g. scrolling
    // back up) can reserve the exact row height up front instead of growing it when the image loads
    private val dimsCache = ConcurrentHashMap<String, IntArray>()

    @Volatile private var registered = false

    // CommentModel.v1 is a straight-line static factory: the patch hands us the comment at entry
    // and the freshly-built model at return, on the same thread, with no reentrancy in between.
    private val pendingComment = ThreadLocal<Any?>()
    // CommentViewHolder.o reuses the model's parameter register for a boolean before it returns,
    // so the registers are not safe to read at exit; capture (viewHolder, model, requestManager)
    // at entry and render at exit from here instead.
    private val pendingRender = ThreadLocal<Triple<Any, Any?, Any?>?>()

    private val GIPHY = Regex("""!\[gif]\(giphy\|([A-Za-z0-9_-]+)\)""")
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    private val io by lazy { Executors.newFixedThreadPool(3) }

    private fun log(m: String) = Log.i(TAG, m)
    private fun logErr(m: String, t: Throwable) = Log.e(TAG, "$m: $t")

    // ---- entry points (called from injected smali) --------------------------------------------

    /** MyApplication.onCreate: advertise the toggle. */
    @JvmStatic
    fun init() {
        ensureRegistered()
    }

    /** CommentModel.v1 entry: remember the raw comment for the model built at return. */
    @JvmStatic
    fun stashComment(comment: Any?) {
        pendingComment.set(comment)
    }

    /** CommentModel.v1 return: pair the just-built model with the stashed comment. */
    @JvmStatic
    fun stashModel(model: Any?) {
        val comment = pendingComment.get()
        pendingComment.remove()
        if (model == null || comment == null) return
        runCatching {
            val node = callMethod(comment, "getDataNode") ?: return
            val mm = if (callMethod(node, "has", "media_metadata") == true)
                callMethod(node, "get", "media_metadata")?.toString() else null
            val body = callMethod(comment, "getBody") as? String
            if (mm != null || body?.contains("giphy|") == true) {
                mediaByComment[model] = Pair(mm, body)
            }
        }.onFailure { logErr("stash failed", it) }
    }

    /**
     * CommentViewHolder.o entry: capture the view holder, model, and Boost's Glide RequestManager.
     * Signature mirrors o(CommentModel, boolean, boolean, boolean, k) — the three booleans are
     * passed only because the smali invoke-static/range must cover a contiguous register span.
     */
    @JvmStatic
    fun renderEnter(viewHolder: Any?, model: Any?, z0: Boolean, z1: Boolean, z2: Boolean, requestManager: Any?) {
        if (viewHolder != null) pendingRender.set(Triple(viewHolder, model, requestManager))
    }

    /** CommentViewHolder.o return: render (or clear) the inline images using the captured values. */
    @JvmStatic
    fun renderExit() {
        val p = pendingRender.get() ?: return
        pendingRender.remove()
        runCatching { renderInto(p.first, p.second, p.third) }.onFailure { logErr("render error", it) }
    }

    /** In TableTextView.setLinkClickedListener(listener): remember Boost's handler for tap-to-open. */
    @JvmStatic
    fun captureLink(table: Any?, listener: Any?) {
        if (table != null && listener != null) linkListenerByTable[table] = listener
    }

    // ---- settings ------------------------------------------------------------------------------

    private fun ensureRegistered() {
        if (registered) return
        registered = true
        MorpheSettings.register(
            SettingDescriptor(
                PREF_KEY,
                "Inline comment images",
                "Render images & GIFs from comments inline",
                "Morphe",
                true,
            ),
        )
    }

    private fun isEnabled(): Boolean {
        ensureRegistered()
        return MorpheSettings.getBoolean(PREF_KEY, true)
    }

    // ---- rendering -----------------------------------------------------------------------------

    /** display url + dims (0,0 = unknown) + the url to open in the viewer on tap + animated flag */
    private data class Img(
        val url: String,
        val w: Int,
        val h: Int,
        val openUrl: String = url,
        val isGif: Boolean = false,
    )

    /** the images we resolved + the media IDs whose redundant link-text should be stripped */
    private data class Resolved(val imgs: List<Img>, val ids: Set<String>)

    private fun renderInto(viewHolder: Any, model: Any?, requestManager: Any?) {
        val container = getField(viewHolder, "commentTv") as? ViewGroup ?: return
        if (!isEnabled()) {   // toggled off → strip our injected views, do nothing
            for (i in container.childCount - 1 downTo 0)
                if (container.getChildAt(i).tag == INJECTED) container.removeViewAt(i)
            return
        }
        val pair = model?.let { mediaByComment[it] }
        val resolved = if (pair == null) Resolved(emptyList(), emptySet()) else resolveImages(pair.first, pair.second)
        val imgs = resolved.imgs

        val existing = ArrayList<InlineImageView>()
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is InlineImageView) existing.add(c)
        }
        while (existing.size > imgs.size) container.removeView(existing.removeAt(existing.size - 1))
        if (imgs.isEmpty()) return

        stripImageLinks(container, resolved.ids)

        val ctx = container.context
        val maxH = dp(ctx, 340f)
        val availW = if (container.width > 0) container.width else ctx.resources.displayMetrics.widthPixels
        for (idx in imgs.indices) {
            val img = imgs[idx]
            val iv = existing.getOrNull(idx) ?: InlineImageView(ctx).apply { tag = INJECTED }
                .also { container.addView(it) }
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.maxHeight = maxH
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
                .apply { topMargin = dp(ctx, 6f) }
            // reserve the exact height from real dims — media_metadata, or dims learned on a prior
            // render (dimsCache) — so the row never grows when the image loads; only the very first
            // sighting of a dimensionless gif falls back to sizing on load
            val dims = if (img.w > 0 && img.h > 0) intArrayOf(img.w, img.h) else dimsCache[img.url]
            if (dims != null && dims[0] > 0 && dims[1] > 0) {
                iv.adjustViewBounds = false
                iv.minimumHeight = 0
                lp.height = (availW.toFloat() * dims[1] / dims[0]).toInt().coerceIn(dp(ctx, 48f), maxH)
            } else {
                iv.adjustViewBounds = true
                iv.minimumHeight = dp(ctx, 120f)
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            iv.layoutParams = lp
            // tap → Boost's own URL handler (same viewer the link opened)
            val clickUrl = img.openUrl
            iv.setOnClickListener {
                val l = linkListenerByTable[container]
                if (l != null) runCatching { callMethod(l, "a", clickUrl) }.onFailure { logErr("open fail", it) }
            }
            if (iv.url != img.url) {
                iv.url = img.url
                loadInto(requestManager, iv, img.url, img.isGif)
            }
        }
    }

    /**
     * Load an image into the view through Boost's own Glide. Static images load by URL (the image
     * viewer shares the Glide cache); gifs are fetched through Boost's OkHttp so they land in
     * `ok_cache` — the pool Boost's gif viewer reads — then animated from those bytes, so a tapped
     * gif is not downloaded again. Falls back to a plain static decode if Glide is unavailable.
     */
    private fun loadInto(requestManager: Any?, iv: InlineImageView, url: String, isGif: Boolean) {
        if (requestManager == null) { manualLoad(iv, url); return }
        if (!isGif) {
            if (!glideShow(requestManager, url, iv)) manualLoad(iv, url)
            return
        }
        io.execute {
            val bytes = fetchViaBoostOkHttp(url)
            ui.post {
                if (iv.parent == null || iv.url != url) return@post
                val shown = bytes != null && glideShow(requestManager, bytes, iv)
                if (!shown && !glideShow(requestManager, url, iv)) manualLoad(iv, url)
            }
        }
    }

    /** RequestManager.load(model).into(view) — model is a URL String or raw gif bytes. */
    private fun glideShow(requestManager: Any, model: Any, iv: ImageView): Boolean = runCatching {
        val builder = callMethod(requestManager, GLIDE_LOAD, model) ?: return false
        callMethod(builder, GLIDE_INTO, iv)
        true
    }.onFailure { logErr("glide load failed", it) }.getOrDefault(false)

    /** Download via Boost's OkHttp client (`tb.a.b()`), which is backed by the on-disk `ok_cache`. */
    private fun fetchViaBoostOkHttp(url: String): ByteArray? = runCatching {
        val client = Class.forName("tb.a").getMethod("b").invoke(null) ?: return null
        val builder = Class.forName("okhttp3.Request\$Builder").getDeclaredConstructor().newInstance()
        callMethod(builder, "url", url)
        val request = callMethod(builder, "build") ?: return null
        val call = callMethod(client, "newCall", request) ?: return null
        val response = callMethod(call, "execute") ?: return null
        try {
            val body = callMethod(response, "body") ?: return null
            callMethod(body, "bytes") as? ByteArray
        } finally {
            runCatching { callMethod(response, "close") }
        }
    }.getOrElse { logErr("okhttp gif fetch failed $url", it); null }

    /** Walk commentTv's subtree (find the text view by type) and delete the link-text for the
     *  images we rendered — matched by resolved media ID, so we only strip links we replaced. */
    private fun stripImageLinks(v: View, ids: Set<String>) {
        if (ids.isEmpty() || v.tag == INJECTED) return
        if (v is ViewGroup) { for (i in 0 until v.childCount) stripImageLinks(v.getChildAt(i), ids); return }
        if (v !is TextView) return
        val text = v.text as? Spanned ?: return
        val ranges = text.getSpans(0, text.length, URLSpan::class.java)
            .filter { span -> val u = span.url ?: ""; ids.any { u.contains(it) } }
            .map { text.getSpanStart(it) to text.getSpanEnd(it) }
            .filter { it.first in 0..text.length && it.second in it.first..text.length }
            .sortedByDescending { it.first }
        if (ranges.isEmpty()) return
        val sb = SpannableStringBuilder(text)
        for ((s, e) in ranges) sb.delete(s, e)
        while (sb.isNotEmpty() && sb[0].isWhitespace()) sb.delete(0, 1)
        while (sb.isNotEmpty() && sb[sb.length - 1].isWhitespace()) sb.delete(sb.length - 1, sb.length)
        if (sb.isBlank()) v.visibility = View.GONE else v.text = sb
    }

    private fun manualLoad(iv: ImageView, url: String) {
        io.execute {
            runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) ui.post { if (iv.parent != null) iv.setImageBitmap(bmp) }
            }.onFailure { logErr("img load fail $url", it) }
        }
    }

    private fun resolveImages(mmJson: String?, body: String?): Resolved {
        val out = LinkedHashMap<String, Img>()
        val ids = LinkedHashSet<String>()       // media IDs of images we render → strip their links
        val giphyHandled = HashSet<String>()    // giphy ids covered by media_metadata
        if (mmJson != null) runCatching {
            val obj = JSONObject(mmJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val e = obj.optJSONObject(k) ?: continue
                if (e.optString("status") != "valid") continue
                val type = e.optString("e")
                if (type != "Image" && type != "AnimatedImage") continue
                val s = e.optJSONObject("s")
                // giphy entry → giphy's own CDN gif (animates + the viewer opens it cleanly)
                if (k.startsWith("giphy|")) {
                    val id = k.removePrefix("giphy|"); giphyHandled.add(id); ids.add(id)
                    val url = "https://i.giphy.com/media/$id/giphy.gif"
                    out[url] = Img(url, s?.optInt("x", 0) ?: 0, s?.optInt("y", 0) ?: 0, isGif = true); continue
                }
                // reddit AnimatedImage: animated source (s.gif), not the static p[] preview
                if (type == "AnimatedImage") {
                    val gif = s?.optString("gif")?.takeIf { it.isNotEmpty() }
                    if (gif != null) {
                        val url = Html.fromHtml(gif).toString()
                        ids.add(k)   // reddit media id — appears in the comment's preview.redd.it link
                        out[url] = Img(url, s.optInt("x", 0), s.optInt("y", 0), isGif = true); continue
                    }
                }
                val p = e.optJSONArray("p") ?: continue
                if (p.length() == 0) continue
                val best = p.optJSONObject(p.length() - 1) ?: continue
                val raw = best.optString("u"); if (raw.isNullOrEmpty()) continue
                val url = Html.fromHtml(raw).toString()
                val full = s?.optString("u")?.takeIf { it.isNotEmpty() }?.let { Html.fromHtml(it).toString() } ?: url
                ids.add(k)
                out[url] = Img(url, best.optInt("x", 0), best.optInt("y", 0), full)  // open full-res in viewer
            }
        }
        // body giphy refs — only when not already covered by a media_metadata entry
        if (body != null) for (m in GIPHY.findAll(body)) {
            val id = m.groupValues[1]
            if (id in giphyHandled) continue
            ids.add(id)
            val url = "https://i.giphy.com/media/$id/giphy.gif"
            out.getOrPut(url) { Img(url, 0, 0, isGif = true) }
        }
        return Resolved(out.values.toList(), ids)
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    /** ImageView that records the real drawable dimensions into [dimsCache] the moment an image is
     *  set, so the next time the comment is bound the row height can be reserved up front. */
    private class InlineImageView(context: Context) : ImageView(context) {
        var url: String? = null

        override fun setImageDrawable(drawable: Drawable?) {
            super.setImageDrawable(drawable)
            if (drawable != null) cacheDims(url, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            if (bm != null) cacheDims(url, bm.width, bm.height)
        }
    }

    private fun cacheDims(url: String?, w: Int, h: Int) {
        if (url != null && w > 0 && h > 0) dimsCache[url] = intArrayOf(w, h)
    }

    // ---- minimal reflection helpers (Boost classes are obfuscated; resolve at runtime) ---------

    /** Resolve by name AND assignable parameter types — overloads like JsonNode.get(int) vs
     *  get(String), or Glide load(String) vs load(Object), must not be confused. */
    private fun resolveMethod(cls: Class<*>, name: String, args: Array<out Any?>): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        var fallback: java.lang.reflect.Method? = null
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name || m.parameterTypes.size != args.size) continue
                if (fallback == null) fallback = m
                if (paramsMatch(m.parameterTypes, args)) { m.isAccessible = true; return m }
            }
            c = c.superclass
        }
        return fallback?.also { it.isAccessible = true }
    }

    private fun paramsMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in types.indices) {
            val a = args[i] ?: continue   // null matches any reference type
            if (!boxed(types[i]).isAssignableFrom(a.javaClass)) return false
        }
        return true
    }

    private fun boxed(t: Class<*>): Class<*> = when (t) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> t
    }

    private fun callMethod(target: Any, name: String, vararg args: Any?): Any? {
        val m = resolveMethod(target.javaClass, name, args) ?: return null
        return m.invoke(target, *args)
    }

    private fun getField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            runCatching {
                val f = c!!.getDeclaredField(name); f.isAccessible = true; return f.get(target)
            }
            c = c.superclass
        }
        return null
    }
}
