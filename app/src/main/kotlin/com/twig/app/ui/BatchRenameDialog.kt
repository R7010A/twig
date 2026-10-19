package com.twig.app.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.core.isMutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.Locale

/** Name generation has no filesystem side effects; preview and execution use the same plan. */
internal object BatchRenameNames {
    enum class Part { NAME, EXTENSION, FULL }

    fun generate(
        files: List<XFile>, part: Part, numbering: Boolean, find: String, replacement: String,
        regex: Boolean, matchCase: Boolean, width: Int, start: String, numberFirst: Boolean,
    ): List<String> {
        val pattern = if (!numbering && regex && find.isNotEmpty()) {
            Regex(find, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } else null
        val first = if (numbering) {
            require(start.isNotEmpty() && start.all { it in '0'..'9' })
            BigInteger(start)
        } else BigInteger.ZERO
        return files.mapIndexed { index, file ->
            val name = file.name
            val dot = if (file.isDir) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val stem = if (dot < 0) name else name.substring(0, dot)
            val extension = if (dot < 0) "" else name.substring(dot + 1)
            val old = when (part) { Part.NAME -> stem; Part.EXTENSION -> extension; Part.FULL -> name }
            val changed = when {
                numbering -> {
                    val number = (first + BigInteger.valueOf(index.toLong())).toString().padStart(width, '0')
                    if (old.isEmpty()) number else if (numberFirst) "$number - $old" else "$old - $number"
                }
                find.isEmpty() -> old
                pattern != null -> pattern.replace(old, replacement)
                else -> old.replace(find, replacement, ignoreCase = !matchCase)
            }
            // Directories have no extension; extension-only leaves them unchanged.
            when (part) {
                Part.FULL -> changed
                Part.NAME -> changed + if (dot < 0) "" else ".$extension"
                Part.EXTENSION -> if (file.isDir) name else stem + if (changed.isEmpty()) "" else ".$changed"
            }
        }
    }

    fun valid(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }
}

internal object BatchRenameDialog {
    fun show(
        context: Context, owner: LifecycleOwner, files: List<XFile>,
        parentInTree: (XFile) -> XFile?, onChanged: () -> Unit,
    ) {
        val dp = context.resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()
        fun label(id: Int) = TextView(context).apply { setText(id); setPadding(0, px(8), 0, px(4)) }
        fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun spinner(ids: List<Int>) = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ids.map(context::getString))
        }
        val content = column().apply { setPadding(px(16), 0, px(16), px(8)) }
        val part = spinner(listOf(R.string.batch_name_only, R.string.batch_ext_only, R.string.batch_name_ext))
        val method = spinner(listOf(R.string.batch_replace, R.string.batch_numbering))
        content.addView(label(R.string.batch_apply_to)); content.addView(part)
        content.addView(method)
        val replaceBox = column()
        val find = EditText(context).apply { setHint(R.string.batch_find); setSingleLine() }
        val replacement = EditText(context).apply { setHint(R.string.batch_replacement); setSingleLine() }
        val regex = CheckBox(context).apply { setText(R.string.batch_regex) }
        val matchCase = CheckBox(context).apply { setText(R.string.batch_match_case) }
        replaceBox.addView(find); replaceBox.addView(replacement)
        val checks = LinearLayout(context)
        checks.addView(regex, LinearLayout.LayoutParams(0, -2, 1f))
        checks.addView(matchCase, LinearLayout.LayoutParams(0, -2, 1f))
        replaceBox.addView(checks); content.addView(replaceBox)
        val numberBox = column()
        val width = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("1", "01", "001", "0001"))
        }
        val start = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(); setText("1")
        }
        val order = spinner(listOf(R.string.batch_old_number, R.string.batch_number_old))
        numberBox.addView(label(R.string.batch_number_format)); numberBox.addView(width)
        numberBox.addView(label(R.string.batch_start)); numberBox.addView(start); numberBox.addView(order)
        content.addView(numberBox)
        val header = NameColumns(context)
        header.addView(label(R.string.batch_before), LinearLayout.LayoutParams(0, -2))
        header.addView(label(R.string.batch_after), LinearLayout.LayoutParams(0, -2))
        content.addView(header)
        val preview = PreviewAdapter(files, ::px)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context); adapter = preview
        }
        content.addView(list, LinearLayout.LayoutParams(-1, px(180)))
        val status = TextView(context)
        content.addView(status)
        val scroll = ScrollView(context).apply { addView(content) }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.batch_rename).setView(scroll)
            .setNegativeButton(R.string.dialog_cancel, null).setPositiveButton(R.string.action_rename, null).create()
        var names = emptyList<String>()
        var busy = false
        fun update() {
            if (busy) return
            val numbering = method.selectedItemPosition == 1
            replaceBox.visibility = if (numbering) View.GONE else View.VISIBLE
            numberBox.visibility = if (numbering) View.VISIBLE else View.GONE
            val result = runCatching {
                BatchRenameNames.generate(files, BatchRenameNames.Part.entries[part.selectedItemPosition], numbering,
                    find.text.toString(), replacement.text.toString(), regex.isChecked, matchCase.isChecked,
                    width.selectedItemPosition + 1, start.text.toString(), order.selectedItemPosition == 1)
            }
            names = result.getOrDefault(emptyList())
            preview.names = names; preview.notifyDataSetChanged()
            val invalid = names.firstOrNull { !BatchRenameNames.valid(it) }
            status.text = when {
                result.isFailure -> context.getString(R.string.batch_invalid_rule)
                invalid != null -> context.getString(R.string.batch_invalid_name, invalid)
                else -> ""
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = result.isSuccess && invalid == null &&
                names.indices.any { names[it] != files[it].name }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = update()
            override fun afterTextChanged(s: Editable?) {}
        }
        listOf(find, replacement, start).forEach { it.addTextChangedListener(watcher) }
        listOf(part, method, width, order).forEach {
            it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = update()
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        regex.setOnCheckedChangeListener { _, _ -> update() }
        matchCase.setOnCheckedChangeListener { _, _ -> update() }
        dialog.setOnShowListener {
            update()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requestedNames = names.toList()
                // Capture tree parents on the UI thread; opaque document URIs cannot be sliced.
                val parents = HashMap<XFile, XFile?>()
                for (file in files) {
                    var entry: XFile? = file
                    while (entry != null && !parents.containsKey(entry)) {
                        val parent = parentInTree(entry)
                        parents[entry] = parent
                        entry = parent
                    }
                }
                fun enableInputs(group: ViewGroup, enabled: Boolean) {
                    for (i in 0 until group.childCount) {
                        val child = group.getChildAt(i)
                        child.isEnabled = enabled
                        if (child is ViewGroup) enableInputs(child, enabled)
                    }
                }
                busy = true
                dialog.setCancelable(false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                enableInputs(content, false)
                status.setText(R.string.batch_working)
                owner.lifecycleScope.launch {
                    var completed = 0
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            execute(context, files, requestedNames, parents) { completed++ }
                        }
                    }
                    if (completed > 0) onChanged()
                    val message = result.fold(
                        onSuccess = { context.getString(R.string.batch_done, completed) },
                        onFailure = { context.getString(R.string.batch_failed, completed, it.message ?: context.getString(R.string.err_failed)) },
                    )
                    if (result.isFailure && completed == 0) {
                        busy = false
                        enableInputs(content, true)
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        update()
                        status.text = message
                    } else {
                        dialog.dismiss()
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    /** Preflight the entire selection before mutating, and report each completed rename. */
    internal fun execute(
        context: Context, files: List<XFile>, names: List<String>, parents: Map<XFile, XFile?>,
        onRenamed: () -> Unit,
    ) {
        require(files.size == names.size)
        check(names.all(BatchRenameNames::valid)) { context.getString(R.string.batch_invalid_rule) }
        val plan = files.zip(names).filter { (file, name) -> file.name != name }
        check(files.all { it.isMutable() }) { context.getString(R.string.err_failed) }
        val directories = files.associateWith { file ->
            parents[file] ?: FsRegistry.of(file).parentOf(file)
                ?: error(context.getString(R.string.err_failed))
        }
        val grouped = files.zip(names).groupBy { (file, _) ->
            directories.getValue(file).let { it.scheme to it.path }
        }
        // Reject duplicate destinations and existing names before the first mutation.
        for ((_, entries) in grouped) {
            val parent = directories.getValue(entries.first().first)
            val existing = FsRegistry.of(parent).list(parent).groupBy { it.name.lowercase(Locale.ROOT) }
            val destinations = HashSet<String>()
            for ((file, name) in entries) {
                check(destinations.add(name.lowercase(Locale.ROOT))) {
                    context.getString(R.string.batch_conflict, name)
                }
                if (name == file.name) continue
                check(existing[name.lowercase(Locale.ROOT)].orEmpty().none { it.path != file.path }) {
                    context.getString(R.string.batch_conflict, name)
                }
            }
        }
        // Renaming an ancestor would invalidate a selected descendant's handle.
        for (file in files) {
            var parent = parents[file] ?: parentInTreeFallback(file)
            val seen = HashSet<XFile>()
            while (parent != null && seen.add(parent)) {
                val ancestor = parent
                check(plan.none { it.first.scheme == ancestor.scheme && it.first.path == ancestor.path }) {
                    context.getString(R.string.batch_nested)
                }
                parent = parents[parent] ?: parentInTreeFallback(parent)
            }
        }
        for ((file, name) in plan) {
            FsRegistry.of(file).rename(file, name)
            onRenamed()
        }
    }

    private fun parentInTreeFallback(file: XFile): XFile? = FsRegistry.of(file).parentOf(file)

    private class PreviewAdapter(val files: List<XFile>, val px: (Int) -> Int) : RecyclerView.Adapter<PreviewAdapter.Holder>() {
        var names: List<String> = emptyList()
        class Holder(val row: LinearLayout, val before: TextView, val after: TextView) : RecyclerView.ViewHolder(row)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val row = NameColumns(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(0, px(6), 0, px(6))
            }
            fun cell() = TextView(parent.context).apply {
                gravity = Gravity.TOP or Gravity.START
                setPadding(0, 0, px(8), 0)
                setTextIsSelectable(true)
            }
            val before = cell(); val after = cell()
            row.addView(before, LinearLayout.LayoutParams(0, -2))
            row.addView(after, LinearLayout.LayoutParams(0, -2))
            return Holder(row, before, after)
        }
        override fun getItemCount() = files.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.before.text = files[position].name
            holder.after.text = names.getOrNull(position) ?: ""
            holder.after.alpha = if (names.getOrNull(position) == files[position].name) 0.5f else 1f
        }
    }

    /** Equal columns even during the dialog's initial AT_MOST measurement. */
    internal class NameColumns(context: Context) : LinearLayout(context) {
        init {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            isBaselineAligned = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (childCount == 2 && View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
                val width = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                getChildAt(0).layoutParams.width = width / 2
                getChildAt(1).layoutParams.width = width - width / 2
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
