package com.mas.tgblocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter untuk daftar domain custom yang diblokir (di luar daftar bawaan
 * AdultDomainList). Hanya bisa dihapus, tidak ada edit.
 */
class DomainAdapter(
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<DomainAdapter.DomainViewHolder>() {

    private val items = mutableListOf<String>()

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DomainViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_domain, parent, false)
        return DomainViewHolder(view)
    }

    override fun onBindViewHolder(holder: DomainViewHolder, position: Int) {
        val item = items[position]
        holder.tvDomainName.text = item
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    class DomainViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDomainName: TextView = view.findViewById(R.id.tvDomainName)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteDomain)
    }
}
