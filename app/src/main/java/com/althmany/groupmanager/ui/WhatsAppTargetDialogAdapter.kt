package com.althmany.groupmanager.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.althmany.groupmanager.util.InstalledWhatsAppApp

/** Compact icon + label list used by the installed WhatsApp target picker. */
class WhatsAppTargetDialogAdapter(
    private val context: Context,
    private val items: List<InstalledWhatsAppApp>
) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): InstalledWhatsAppApp = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))

            addView(ImageView(context).apply {
                tag = "icon"
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            })
            addView(LinearLayout(context).apply {
                tag = "texts"
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    tag = "label"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    tag = "package"
                    textSize = 11f
                    alpha = 0.65f
                })
            })
        }

        val item = getItem(position)
        val icon = row.findViewWithTag<ImageView>("icon")
        val label = row.findViewWithTag<TextView>("label")
        val packageText = row.findViewWithTag<TextView>("package")
        icon.setImageDrawable(runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull())
        label.text = item.label
        packageText.text = item.packageName
        return row
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
