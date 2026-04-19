package com.tarmac.ui

import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import com.tarmac.R

class MainFragment : BrowseSupportFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        headersState = HEADERS_DISABLED
        isHeadersTransitionOnBackEnabled = false
        adapter = buildAdapter()
    }

    private fun buildAdapter(): ArrayObjectAdapter {
        val rows = ArrayObjectAdapter(ListRowPresenter())
        val header = HeaderItem(0, getString(R.string.status_header))
        rows.add(ListRow(header, ArrayObjectAdapter(StatusItemPresenter())))
        return rows
    }
}
