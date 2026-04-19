package com.tarmac

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.tarmac.ui.MainFragment

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_container, MainFragment())
                .commit()
        }
    }
}
