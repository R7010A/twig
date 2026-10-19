package com.twig.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.twig.app.AndroidDataAccess.AppFolder
import com.twig.app.R
import com.twig.app.databinding.DialogAppDataFoldersBinding
import com.twig.app.databinding.ItemAppDataFolderBinding
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Package paths are authorization candidates, not a filesystem listing. */
object AppDataFolderDialog {
    fun show(ctx: Context, scope: CoroutineScope, folders: List<AppFolder>, select: (XFile) -> Unit): AlertDialog {
        val inflater = LayoutInflater.from(ctx)
        val b = DialogAppDataFoldersBinding.inflate(inflater)
        b.listBox.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            minOf((360 * ctx.resources.displayMetrics.density).toInt(), (ctx.resources.displayMetrics.heightPixels * .4f).toInt()))
        val adapter = object : ArrayAdapter<AppFolder>(ctx, 0) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView?.let(ItemAppDataFolderBinding::bind)
                    ?: ItemAppDataFolderBinding.inflate(inflater, parent, false)
                val item = getItem(position)!!
                row.label.text = item.label
                row.packageName.text = item.file.name
                row.icon.setImageResource(R.drawable.ic_file_apk)
                FileIcons.bindApp(row.icon, item.file.name)
                return row.root
            }
        }
        b.list.adapter = adapter
        b.list.emptyView = b.empty
        val dialog = AlertDialog.Builder(ctx).setTitle(R.string.android_data_app)
            .setView(b.root).setNegativeButton(R.string.dialog_cancel, null).create()
        var detected: List<AppFolder>? = null
        var scan: Job? = null
        fun filter() {
            val query = b.filter.text.toString().trim()
            val source = if (b.existing.isChecked) detected.orEmpty() else folders
            adapter.setNotifyOnChange(false)
            adapter.clear()
            adapter.addAll(source.filter {
                (!b.hideSystem.isChecked || !it.system) &&
                    (it.label.contains(query, ignoreCase = true) || it.file.name.contains(query, ignoreCase = true))
            })
            adapter.notifyDataSetChanged()
        }
        b.filter.doAfterTextChanged { filter() }
        b.hideSystem.setOnCheckedChangeListener { _, _ -> filter() }
        b.existing.setOnCheckedChangeListener { _, checked ->
            scan?.cancel()
            filter()
            if (checked && detected == null) scan = scope.launch {
                detected = withContext(Dispatchers.IO) { folders.filter { File(it.file.path).isDirectory } }
                filter()
            }
        }
        b.list.setOnItemClickListener { _, _, position, _ ->
            val file = adapter.getItem(position)!!.file
            dialog.dismiss()
            select(file)
        }
        dialog.setOnDismissListener { scan?.cancel() }
        filter()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
        return dialog
    }
}
