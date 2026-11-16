package io.furryr.file.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.furryr.file.AppLocale
import io.furryr.file.MainActivity
import io.furryr.file.ProviderAuthority
import io.furryr.file.R
import io.furryr.file.ui.theme.FileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Crash report viewer. It is started by [CrashReporter] from the crashing
 * process, or by [MainActivity] on the next launch when the immediate start
 * did not happen. The report is plain text; the screen only offers sharing
 * paths (copy, share, SAF export) and a clean restart.
 */
class CrashActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_REPORT_PATH = "io.furryr.file.crash.REPORT_PATH"
        private const val EXTRA_REPORT_TEXT = "io.furryr.file.crash.REPORT_TEXT"
        private const val MAX_INLINE_CHARS = 100_000

        /**
         * [report] is the on-disk report; [reportText] is only used when the
         * report file could not be written. The activity falls back to the
         * pending marker when no extras are present (normal launch case).
         */
        fun intent(context: Context, report: File?, reportText: String? = null): Intent =
            Intent(context, CrashActivity::class.java).apply {
                if (report != null) putExtra(EXTRA_REPORT_PATH, report.absolutePath)
                if (reportText != null) {
                    putExtra(EXTRA_REPORT_TEXT, reportText.take(MAX_INLINE_CHARS))
                }
            }
    }

    private var reportFile: File? = null
    private var reportText: String = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(EXTRA_REPORT_PATH)
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?: CrashReporter.pendingReport(this)
        reportFile = report
        reportText = report?.let { runCatching { it.readText() }.getOrNull() }
            ?: intent.getStringExtra(EXTRA_REPORT_TEXT)
            ?: getString(R.string.crash_report_missing)
        if (report != null) CrashReporter.markShown(this, report)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            val darkTheme = remember { prefs.getBoolean("dark_theme", true) }
            FileManagerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CrashScreen(
                        reportText = reportText,
                        reportFile = reportFile,
                        onClose = { finish() },
                        onRestart = { restartApp() },
                    )
                }
            }
        }
    }

    private fun restartApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashScreen(
    reportText: String,
    reportFile: File?,
    onClose: () -> Unit,
    onRestart: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val summary = remember(reportText) {
        reportText.lineSequence()
            .firstOrNull { it.startsWith("Exception  : ") }
            ?.substringAfter("Exception  : ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
    val exportName = remember(reportFile) {
        reportFile?.name ?: CrashReporter.suggestedFileName()
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(reportText.toByteArray(Charsets.UTF_8))
                    } ?: error("openOutputStream returned null")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (saved) R.string.crash_exported else R.string.crash_export_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crash_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.crash_close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { shareReport(context, reportFile, reportText) }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.crash_share),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.crash_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                SelectionContainer {
                    Text(
                        text = reportText,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { exportLauncher.launch(exportName) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.crash_export))
                }
                OutlinedButton(
                    onClick = { copyReport(context, reportText) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.crash_copy))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.crash_restart))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun copyReport(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.crash_title), text),
    )
    Toast.makeText(context, R.string.crash_copied, Toast.LENGTH_SHORT).show()
}

private fun shareReport(context: Context, reportFile: File?, text: String) {
    val shareFile = reportFile?.takeIf { it.isFile } ?: runCatching {
        val dir = File(context.cacheDir, CrashReporter.ReportDirName)
        dir.mkdirs()
        File(dir, CrashReporter.suggestedFileName()).apply {
            writeText(text, Charsets.UTF_8)
        }
    }.getOrNull()
    val uri = shareFile?.let { file ->
        runCatching { FileProvider.getUriForFile(context, ProviderAuthority, file) }.getOrNull()
    }
    if (shareFile == null || uri == null) {
        Toast.makeText(context, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_title))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, shareFile.name, uri)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.crash_share)),
        )
    }.onFailure {
        Toast.makeText(context, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
    }
}
