package com.mas.tgblocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter untuk daftar package aplikasi klon custom (ditambahkan manual
 * atau lewat share dari Play Store). Hanya bisa dihapus, tidak ada edit —
 * nama package memang harus persis, kalau salah cukup hapus & tambah ulang.
 */
class PackageAdapter(
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<PackageAdapter.PackageViewHolder>() {

    private val items = mutableListOf<String>()

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_package, parent, false)
        return PackageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PackageViewHolder, position: Int) {
        val item = items[position]
        holder.tvPackageName.text = item
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    class PackageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPackageName: TextView = view.findViewById(R.id.tvPackageName)
        val btnDelete: TextView = view.findViewById(R.id.btnDeletePackage)
    }
}
