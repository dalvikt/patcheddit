/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.boostforreddit.settings

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup

/**
 * Small fixes to Boost's settings UX, applied by the "Settings UI fixes" patch:
 *  - the toolbar back-arrow closes the open detail pane instead of leaving settings (two-pane);
 *  - the opened page's name is shown in the toolbar, and reset to "Settings" when it closes
 *    (Boost otherwise leaves the title as "Settings" for every page in two-pane mode);
 *  - the settings search bar gets a right margin symmetric with its top inset.
 *
 * Boost-internal members are reached by reflection (they are R8-obfuscated at runtime).
 */
object SettingsBehavior {

    @JvmStatic
    fun navigateUp(activity: Any?): Boolean {
        (activity as? Activity)?.let {
            runCatching { it.javaClass.getMethod("onBackPressed").invoke(it) }
        }
        return true   // we handled it
    }

    // The name to show for the next settings page that opens. Captured when it is navigated to —
    // from the tapped header preference, or the tapped search result — and applied by [onPageShown]
    // when the page appears, because Boost's settings pages carry no screen title of their own.
    @Volatile
    private var pendingTitle: CharSequence? = null

    /** Header tap (two-pane): the opened page's name is the tapped header preference's title. */
    @JvmStatic
    fun onDetailOpened(preference: Any?) {
        pendingTitle = runCatching {
            preference?.let { it.javaClass.getMethod("getTitle").invoke(it) } as? CharSequence
        }.getOrNull()
    }

    /**
     * Search result tap: a result's name (id.a.g()) is the individual setting; the page it lives on
     * is the leaf of its breadcrumb (id.a.c() = "Parent > Page").
     */
    @JvmStatic
    fun capturePendingTitle(searchItem: Any?) {
        pendingTitle = runCatching {
            val breadcrumb = searchItem?.let { it.javaClass.getMethod("c").invoke(it) } as? String
            breadcrumb?.substringAfterLast(" > ")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * Show a settings page's name in the toolbar when it appears — its own preference-screen title
     * if it has one, otherwise the name captured when it was navigated to. This is the single place
     * the title is set, and it covers every path (header tap, and search, which opens the fragment
     * directly and so bypasses the header-tap hook).
     */
    @JvmStatic
    fun onPageShown(fragment: Any?) {
        runCatching {
            val activity = fragment
                ?.let { it.javaClass.getMethod("getActivity").invoke(it) } as? Activity ?: return
            if (!activity.javaClass.name.endsWith("SettingsActivityCompat")) return
            val screenTitle = fragment.javaClass.getMethod("getPreferenceScreen").invoke(fragment)
                ?.let { it.javaClass.getMethod("getTitle").invoke(it) } as? CharSequence
            val title = if (!screenTitle.isNullOrEmpty()) screenTitle else pendingTitle
            pendingTitle = null   // consume
            if (!title.isNullOrEmpty()) activity.title = title
        }
    }

    @JvmStatic
    fun onListReturned(slidingPaneLayout: Any?) {
        runCatching {
            val view = slidingPaneLayout as? View ?: return
            var ctx = view.context
            while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
            (ctx as? Activity)?.title = "Settings"
        }
    }

    @JvmStatic
    fun fixSearchBar(activity: Any?) {
        val act = activity as? Activity ?: return
        runCatching {
            val field = act.javaClass.getDeclaredField("searchEditText").apply { isAccessible = true }
            val editText = field.get(act) as? View ?: return
            val bar = editText.parent as? View ?: return
            val lp = bar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val target = if (lp.topMargin > 0) lp.topMargin else (8 * act.resources.displayMetrics.density).toInt()
            lp.rightMargin = target
            lp.marginEnd = target
            bar.layoutParams = lp
        }
    }
}
