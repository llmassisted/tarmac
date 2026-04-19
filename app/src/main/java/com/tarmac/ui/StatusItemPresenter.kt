package com.tarmac.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.tarmac.R

class StatusItemPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_status, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = item?.toString().orEmpty()
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
