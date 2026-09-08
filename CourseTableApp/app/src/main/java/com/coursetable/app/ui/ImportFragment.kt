package com.coursetable.app.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.coursetable.app.MainActivity
import com.coursetable.app.R
import com.coursetable.app.data.Store
import com.coursetable.app.model.Schedule
import com.coursetable.app.parser.ImportEngine
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayOutputStream

/** 导入：选择 .xls/.xlsx/.json → 后台解析 → 预览确认 → 替换当前课表。 */
class ImportFragment : Fragment() {

    companion object {
        const val TAG = "import"
        private const val MAX_BYTES = 48L * 1024 * 1024
    }

    private var root: View? = null
    private var pending: Schedule? = null
    private var pendingName: String? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleUri(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_import, container, false)
        root = v
        v.findViewById<MaterialButton>(R.id.btnPick).setOnClickListener {
            picker.launch(
                arrayOf(
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/x-ole-storage",
                    "application/octet-stream"
                )
            )
        }
        v.findViewById<View>(R.id.btnImportCancel).setOnClickListener {
            (activity as? MainActivity)?.goBackToMain()
        }
        v.findViewById<View>(R.id.btnImportOk).setOnClickListener {
            val s = pending
            val nm = pendingName
            if (s != null && nm != null) (activity as? MainActivity)?.onScheduleImported(s, nm)
        }
        return v
    }

    private fun handleUri(uri: Uri) {
        val v = root ?: return
        val act = activity as? MainActivity ?: return
        val ctx = requireContext()
        val displayName = queryName(ctx, uri) ?: "课表文件"
        val progress = v.findViewById<ProgressBar>(R.id.progress)
        val status = v.findViewById<TextView>(R.id.importStatus)
        val preview = v.findViewById<TextView>(R.id.previewBox)
        val confirmRow = v.findViewById<View>(R.id.confirmRow)
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.import_analyzing)
        preview.visibility = View.GONE
        confirmRow.visibility = View.GONE
        pending = null

        Thread {
            val result = runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        total += r
                        if (total > MAX_BYTES) throw ImportEngine.ImportException("文件过大（>48MB）")
                    }
                    out.toByteArray()
                } ?: throw ImportEngine.ImportException("无法读取所选文件")
                ImportEngine.parse(bytes, displayName, Store(ctx).termStart)
            }
            act.runOnUiThread {
                progress.visibility = View.GONE
                result.onSuccess { outcome ->
                    pending = outcome.schedule
                    pendingName = displayName
                    val s = outcome.schedule
                    val unparsed = s.entries.count {
                        it.unparsedWeekTokens || (it.hasWeekAnnotation && it.weekSet.isEmpty())
                    }
                    val sb = StringBuilder()
                    sb.append("文件：").append(displayName).append('\n')
                    sb.append("工作表：").append(outcome.usedSheet.ifEmpty { "-" }).append('\n')
                    s.titleLines.forEach { sb.append(it).append('\n') }
                    sb.append("解析：").append(s.entries.size).append(" 条排课（已合并竖向重复），")
                        .append(s.groupByCourse().size).append(" 门课程，")
                        .append(s.weekCount).append(" 周\n")
                    if (s.hasWeekendEntries()) sb.append("包含周末课程\n")
                    if (unparsed > 0) sb.append("⚠ ").append(unparsed).append(" 条周次含无法解析片段（已按全集处理，可查看原文）\n")
                    if (outcome.notes.isNotEmpty()) outcome.notes.forEach { sb.append("· ").append(it).append('\n') }
                    sb.append('\n').append(getString(R.string.import_overwrite_hint, s.entries.size))
                    preview.text = sb.toString()
                    preview.visibility = View.VISIBLE
                    confirmRow.visibility = View.VISIBLE
                }
                result.onFailure { t ->
                    val msg = if (t is ImportEngine.ImportException) t.message else "${t.message ?: t.javaClass.simpleName}"
                    status.text = getString(R.string.import_failed, msg)
                    preview.visibility = View.GONE
                    confirmRow.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun queryName(ctx: android.content.Context, uri: Uri): String? {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i)
                }
            }
        }
        return name
    }
}
