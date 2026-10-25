package app.anihome.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import app.anihome.launcher.R
import app.anihome.launcher.databinding.DialogAddSiteBinding
import app.anihome.launcher.databinding.DialogInputBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Dialogs {
    internal fun addSites(context: Context, onAdd: (List<BatchSites.Site>) -> Unit): AlertDialog {
        val density = context.resources.displayMetrics.density
        val input = android.widget.EditText(context).apply {
            hint = "youtube.com\nAniKoto | https://anikototv.to/"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4; maxLines = 8; gravity = android.view.Gravity.TOP
            setTextColor(-1); setHintTextColor(0xffaab4c5.toInt())
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        }
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(android.widget.TextView(context).apply {
                text = "One website per line · Optional: Name | URL"
                setTextColor(0xffb9ccdc.toInt()); setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
            }); addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle("Add websites").setView(content)
            .setNegativeButton(R.string.action_cancel, null).setPositiveButton("Add websites", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val draft = BatchSites.parse(input.text.toString(), app.anihome.launcher.data.SiteIcons::normalizeUrl)
                when {
                    draft.invalidLines.isNotEmpty() -> input.error = "Check line(s): ${draft.invalidLines.joinToString()}"
                    draft.sites.isEmpty() -> input.error = "Enter at least one website"
                    else -> { onAdd(draft.sites); dialog.dismiss() }
                }
            }
        }
        return dialog
    }

    fun input(context: Context, title: Int, hint: Int?, initial: String?, onDone: (String) -> Unit): AlertDialog {
        val binding = DialogInputBinding.inflate(LayoutInflater.from(context))
        if (hint != null) binding.input.setHint(hint)
        binding.input.setText(initial.orEmpty())
        binding.input.setSelection(binding.input.text.length)
        binding.input.filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok, null)
            .create()
        dialog.setOnShowListener {
            val submit = {
                val value = binding.input.text.toString().trim()
                if (value.isBlank()) binding.input.error = "Enter a name"
                else { onDone(value); dialog.dismiss() }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            binding.input.setOnEditorActionListener { _, _, _ -> submit(); true }
            binding.input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        return dialog
    }
}
