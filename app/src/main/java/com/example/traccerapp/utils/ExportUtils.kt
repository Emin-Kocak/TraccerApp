package com.example.traccerapp.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.traccerapp.data.UsageLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ExportUtils {
    fun exportToCsv(context: Context, logs: List<UsageLog>) {
        val fileName = "usage_export_${System.currentTimeMillis()}.csv"
        val file = File(context.cacheDir, fileName)
        
        file.printWriter().use { out ->
            out.println("Package Name,Date,Duration (ms)")
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            logs.forEach { log ->
                val dateStr = sdf.format(Date(log.date))
                out.println("${log.packageName},$dateStr,${log.durationMs}")
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export Usage Logs"))
    }
}
