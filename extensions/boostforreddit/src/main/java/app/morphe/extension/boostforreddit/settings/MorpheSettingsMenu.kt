/*
 * Copyright 2026 wchill.
 * https://github.com/wchill/patcheddit
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.boostforreddit.settings

import android.app.Activity
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The "Morphe" settings menu: a top-level entry in Boost's settings that opens a page of runtime
 * toggles registered by other patches (see [MorpheSettings]), plus integration with Boost's
 * settings search so those toggles are findable.
 *
 * The page reuses one of Boost's own preference fragments (its preference screen is already built)
 * and repopulates it — rather than creating a fresh screen — because Boost ships a minified copy of
 * androidx.preference in which only the APIs Boost itself calls survive. A marker (set on the entry,
 * and on the intent used by search) tells [buildMorphePage] which navigations are ours.
 *
 * The patch calls [addMorpheEntry] (settings header), [buildMorphePage] (the reused fragment),
 * [addSearchItems] (search index) and [handleSearchTap] (search result tap).
 */
object MorpheSettingsMenu {
    private const val KEY = "morphe_pref"
    private const val MARKER = "morphe_settings"
    private const val TITLE = "Morphe"
    private const val HOST_FRAGMENT = "com.rubenmayayo.reddit.ui.preferences.v2.PreferenceFragmentMiscCompat"
    private const val SETTINGS_ACTIVITY = "com.rubenmayayo.reddit.ui.preferences.v2.SettingsActivityCompat"
    private const val EXTRA_SHOW_FRAGMENT = "extra_show_fragment"
    private const val SEARCH_ITEM_CLASS = "id.a"
    private const val SEARCH_ICON_CODE = 12   // comment icon

    // The search-index entries we contributed, tracked by identity so a tap can be recognised.
    private val ourSearchItems = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
    )

    @JvmStatic
    fun addMorpheEntry(headerFragment: Any?) {
        val header = headerFragment as? PreferenceFragmentCompat ?: return
        val screen = header.preferenceScreen ?: return
        if (screen.findPreference<Preference>(KEY) != null) return   // already added
        val ctx = header.context ?: return

        val entry = Preference(ctx).apply {
            key = KEY
            order = -1000                              // pin to the top of the list
            title = TITLE
            summary = "Patch settings"
            fragment = HOST_FRAGMENT                    // reused + repopulated by buildMorphePage
            extras.putBoolean(MARKER, true)
            MorpheLogo.icon(ctx)?.let { icon = it }
        }
        screen.addPreference(entry)
    }

    @JvmStatic
    fun buildMorphePage(hostFragment: Any?) {
        val fragment = hostFragment as? PreferenceFragmentCompat ?: return
        if (!isMorpheLaunch(fragment)) return                          // not our page; leave it alone
        val screen = fragment.preferenceScreen ?: return
        val ctx = fragment.context ?: return

        screen.title = TITLE   // the page's own name; the Settings UI fixes patch shows it in the toolbar

        screen.removeAll()
        val descriptors = MorpheSettings.getDescriptors()
        if (descriptors.isEmpty()) {
            screen.addPreference(
                Preference(ctx).apply {
                    isIconSpaceReserved = false
                    summary = "No patch settings"
                },
            )
            return
        }
        for (descriptor in descriptors) {
            val toggle = SwitchPreferenceCompat(ctx).apply {
                key = descriptor.key
                title = descriptor.title
                summary = descriptor.summary
                isPersistent = false                    // we store the value ourselves
                isIconSpaceReserved = false
                isChecked = MorpheSettings.getBoolean(descriptor.key, descriptor.defaultValue)
            }
            bindChangeListener(toggle, descriptor.key)
            screen.addPreference(toggle)
        }
    }

    /** Add one search-index entry per registered toggle, so they turn up in Boost's settings search. */
    @JvmStatic
    fun addSearchItems(searchIndex: Any?) {
        @Suppress("UNCHECKED_CAST")
        val list = searchIndex as? MutableList<Any> ?: return
        runCatching {
            ourSearchItems.clear()
            val itemClass = Class.forName(SEARCH_ITEM_CLASS)
            val constructor = itemClass.getConstructor(
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            val icon = itemClass.getMethod("e", Int::class.javaPrimitiveType).invoke(null, SEARCH_ICON_CODE)
            for (descriptor in MorpheSettings.getDescriptors()) {
                val item = constructor.newInstance(descriptor.title, "", SEARCH_ICON_CODE, TITLE, icon)
                list.add(item)
                ourSearchItems.add(item)
            }
        }
    }

    /** When one of our search entries is tapped, open the Morphe page; returns true if handled. */
    @JvmStatic
    fun handleSearchTap(searchActivity: Any?, item: Any?): Boolean {
        if (item == null || !ourSearchItems.contains(item)) return false
        val activity = searchActivity as? Activity ?: return false
        runCatching {
            val intent = Intent(activity, Class.forName(SETTINGS_ACTIVITY)).apply {
                putExtra(EXTRA_SHOW_FRAGMENT, HOST_FRAGMENT)
                putExtra(MARKER, true)
            }
            activity.startActivity(intent)
            activity.finish()
        }
        return true
    }

    private fun isMorpheLaunch(fragment: PreferenceFragmentCompat): Boolean {
        if (fragment.arguments?.getBoolean(MARKER) == true) return true
        val activity = activityOf(fragment) ?: return false
        return activity.intent?.getBooleanExtra(MARKER, false) == true
    }

    private fun activityOf(fragment: Any): Activity? = runCatching {
        fragment.javaClass.getMethod("getActivity").invoke(fragment)
    }.getOrNull() as? Activity

    /**
     * Wire a switch's change callback reflectively. The OnPreferenceChangeListener interface is
     * obfuscated to `Preference$c` in Boost's minified androidx.preference, so a typed reference
     * would fail to load the whole class.
     */
    private fun bindChangeListener(toggle: Any, key: String) {
        runCatching {
            val listenerClass = Class.forName("androidx.preference.Preference\$c")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "MorpheToggle"
                    else -> {   // onPreferenceChange(preference, newValue): boolean
                        (args?.getOrNull(1) as? Boolean)?.let { MorpheSettings.setBoolean(key, it) }
                        true
                    }
                }
            }
            Class.forName("androidx.preference.Preference")
                .getMethod("setOnPreferenceChangeListener", listenerClass)
                .invoke(toggle, listener)
        }
    }
}
