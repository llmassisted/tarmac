package com.tarmac.ui

import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.leanback.preference.LeanbackSettingsFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.tarmac.R

/**
 * Two-pane Leanback settings host. The actual preference XML lives in
 * [TarmacPreferenceFragment]; this class is just the navigation shell that
 * Leanback expects (it manages the right-pane stack for nested screens).
 */
class SettingsRootFragment : LeanbackSettingsFragmentCompat() {

    override fun onPreferenceStartInitialScreen() {
        startPreferenceFragment(TarmacPreferenceFragment())
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference,
    ): Boolean = false  // no nested fragments yet

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        pref: PreferenceScreen,
    ): Boolean {
        val frag = TarmacPreferenceFragment().apply {
            arguments = Bundle().apply { putString(ARG_PREFERENCE_ROOT, pref.key) }
        }
        startPreferenceFragment(frag)
        return true
    }

    class TarmacPreferenceFragment : LeanbackPreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }

    companion object {
        private const val ARG_PREFERENCE_ROOT = "androidx.preference.PreferenceFragmentCompat.PREFERENCE_ROOT"
    }
}
