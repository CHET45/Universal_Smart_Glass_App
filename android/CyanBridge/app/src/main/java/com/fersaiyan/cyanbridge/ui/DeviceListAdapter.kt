package com.fersaiyan.cyanbridge.ui

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.devices.DeviceClass
import com.fersaiyan.cyanbridge.devices.ScannedDevice

/**
 * Chapter 3: pairing list with classification + manual override.
 */
class DeviceListAdapter(
    private val ctx: Context,
    data: MutableList<ScannedDevice>,
) : BaseQuickAdapter<ScannedDevice, BaseViewHolder>(R.layout.recycleview_item_device, data) {

    private val selectableClasses = listOf(
        DeviceClass.META_RAYBAN,
        DeviceClass.GENERIC_AUDIO,
        DeviceClass.HEY_CYAN,
    )

    override fun convert(holder: BaseViewHolder, item: ScannedDevice) {
        val rawName = item.advertisedName?.trim().orEmpty()
        val displayName = when {
            rawName.startsWith("O_") || rawName.startsWith("Q_") -> rawName.drop(2)
            rawName.isBlank() -> "(Unnamed device)"
            else -> rawName
        }

        holder.getView<TextView>(R.id.rcv_device_name).text = displayName
        holder.getView<TextView>(R.id.rcv_device_address).text = item.macAddress

        val detected = item.detectedClass
        holder.getView<TextView>(R.id.tv_detected_class).text =
            "Detected: ${detected.displayName()}"

        holder.getView<ImageView>(R.id.iv_device_icon)
            .setImageResource(item.effectiveSelectedClass().iconRes())

        val btnChooseType = holder.getView<MaterialButton>(R.id.btn_choose_device_type)
        btnChooseType.text = "Type: ${item.effectiveSelectedClass().displayName()}"

        btnChooseType.setOnClickListener {
            showTypePicker(holder, item)
        }

        val tvSelected = holder.getView<TextView>(R.id.tv_selected_override)
        if (item.userOverridden()) {
            tvSelected.text = "Selected: ${item.effectiveSelectedClass().displayName()}"
            tvSelected.visibility = View.VISIBLE
        } else {
            tvSelected.visibility = View.GONE
        }
    }

    private fun showTypePicker(holder: BaseViewHolder, item: ScannedDevice) {
        val labels = selectableClasses.map { it.displayName() }.toTypedArray()
        val current = item.userSelectedClass ?: item.detectedClass
        val checked = selectableClasses.indexOf(current)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Select glasses type")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                item.userSelectedClass = selectableClasses.getOrNull(which)
                notifyItemChangedSafe(holder)
                dialog.dismiss()
            }
            .setNeutralButton("Use detected") { dialog, _ ->
                item.userSelectedClass = null
                notifyItemChangedSafe(holder)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun notifyItemChangedSafe(holder: BaseViewHolder) {
        val pos = holder.adapterPosition
        if (pos >= 0) notifyItemChanged(pos) else notifyDataSetChanged()
    }
}
