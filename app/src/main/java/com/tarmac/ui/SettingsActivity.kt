package com.tarmac.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.tarmac.R

class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsRootFragment())
                .commit()
        }
    }
}
