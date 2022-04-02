package com.statsig.android_sdk_testbed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView


class RecyclerViewAdapter(
    private val data: Array<Pair<(() -> Any)?, String>>,
    private val rowClickListener: (Int) -> Unit) :
    RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder>() {

    abstract class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.textView)
    }

    class HeaderViewHolder(view: View) : ViewHolder(view) {}

    class ItemViewHolder(view: View, private val rowClickListener: (Int) -> Unit) : ViewHolder(view), View.OnClickListener {
        init {
            view.setOnClickListener(this)
        }

        override fun onClick(view: View?) {
            rowClickListener.invoke(adapterPosition)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (data[position].first == null) 0 else 1
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        if (viewType == 0) {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.text_header_item, viewGroup, false)

            return HeaderViewHolder(view)
        }
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.text_row_item, viewGroup, false)

        return ItemViewHolder(view, rowClickListener)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = data[position]
        holder.textView.text = item.second
    }

    override fun getItemCount() = data.size
}
