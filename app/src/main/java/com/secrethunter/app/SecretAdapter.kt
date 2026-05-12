package com.secrethunter.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.secrethunter.app.databinding.ItemSecretBinding

class SecretAdapter : RecyclerView.Adapter<SecretAdapter.SecretViewHolder>() {

    private val items = mutableListOf<Secret>()

    fun submit(list: List<Secret>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SecretViewHolder {
        val binding = ItemSecretBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SecretViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SecretViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class SecretViewHolder(
        private val binding: ItemSecretBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Secret) {
            val ctx = binding.root.context
            binding.textSeverity.text = item.severity
            binding.textRuleName.text = item.ruleName
            binding.textSnippet.text = item.snippet
            binding.textPath.text = item.filePath
            binding.textMeta.text = ctx.getString(
                R.string.secret_meta_format,
                item.lineNumber,
                item.category,
            )

            val bg = ContextCompat.getColor(
                ctx,
                when (item.severity) {
                    "CRITICAL" -> R.color.severity_critical
                    "HIGH" -> R.color.severity_high
                    "MEDIUM" -> R.color.severity_medium
                    else -> R.color.severity_low
                },
            )
            val base = ContextCompat.getDrawable(ctx, R.drawable.bg_severity_chip)?.mutate()
            binding.textSeverity.background = base
            binding.textSeverity.backgroundTintList = ColorStateList.valueOf(bg)
            binding.textSeverity.setTextColor(Color.WHITE)
        }
    }
}
