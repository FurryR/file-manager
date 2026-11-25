package io.furryr.file.render

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import io.furryr.file.core.Path
import io.furryr.file.core.VFile
import io.furryr.file.forward.ForwardFacts
import io.furryr.file.forward.ForwardFileProvider
import io.furryr.file.model.PageIcon
import io.furryr.file.ui.components.ApkAction
import io.furryr.file.ui.components.ApkDetails
import io.furryr.file.ui.components.ApkDialog
import io.furryr.file.ui.icons.Icons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ApkPageState : PageState()
private data class ApkPick(val action: ApkAction? = null, val path: String? = null)

/** APK inspection is a popup; only a subsequent browse/navigation commits a page. */
internal class ApkRenderer(private val file: VFile) : RendererBase<ApkPageState>() {
    override val pagePath: String = file.path.toString()
    override val pageIcon: PageIcon get() = PageIcon.Vector(Icons.Default.Description)
    override fun createSession(state: ApkPageState?): PageSession<ApkPageState> =
        object : PageSession<ApkPageState>(state) {
            override val title: String get() = file.name
            override val suppressBookmark: Boolean get() = true
            override fun close() = Unit
        }

    override suspend fun enter(host: RendererContext) {
        val context = requireNotNull(host.context)
        try {
            val details = withContext(Dispatchers.IO) { inspect(context, host) }
            val pick = host.popup<ApkPick?> { resolve ->
                ApkDialog(details,
                    onAction = { resolve(ApkPick(action = it)) },
                    onNavigate = { resolve(ApkPick(path = it)) },
                    onDismiss = { resolve(null) })
            }
            val path = pick?.path
            if (path != null) {
                val node = withContext(Dispatchers.IO) { host.vfs.node(Path.parse(path)).getOrThrow() }
                host.navigate(node)
                return
            }
            when (pick?.action) {
                ApkAction.OpenWith -> host.replace(host.opener.chooser(file))
                ApkAction.Browse -> {
                    val archive = host.opener.options.first { it.option.id == "archive" }
                    host.replace(archive.create(file).getOrThrow())
                }
                ApkAction.Install -> {
                    // The provider opens a lazy handle when PackageInstaller
                    // reads; the APK is never copied into a cache file.
                    val uri = ForwardFileProvider.buildUri(
                        file = file,
                        facts = ForwardFacts(
                            name = file.name,
                            size = details.size,
                            mime = "application/vnd.android.package-archive",
                        ),
                        worldId = host.worldId,
                    )
                    @Suppress("DEPRECATION")
                    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri(file.name, uri)
                    }
                    context.startActivity(intent)
                    host.dismissPending()
                }
                else -> host.dismissPending()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            host.replace(ErrorRenderer(pagePath, failure))
        }
    }

    /** APK facts come from the registered archive view, never a full copy. */
    @Suppress("DEPRECATION")
    private suspend fun inspect(context: Context, host: RendererContext): ApkDetails {
        val stat = file.stat().getOrThrow()
        val archive = host.opener.archive(file).getOrThrow()
        val manifest = ApkManifest.read(archive)
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(manifest.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val info = PackageInfo().apply {
            packageName = manifest.packageName
            versionName = manifest.versionName
            versionCode = manifest.versionCode.toInt()
            applicationInfo = ApplicationInfo().apply {
                packageName = manifest.packageName
                minSdkVersion = manifest.minSdk
                targetSdkVersion = manifest.targetSdk
            }
        }
        return ApkDetails(
            info = info,
            installed = installed,
            label = manifest.label?.takeIf { it.isNotBlank() } ?: manifest.packageName,
            // An installed package already has a platform-resolved icon
            // (adaptive/vector included); only an uninstalled APK is read
            // through its resource table.
            icon = installed?.applicationInfo?.let { application ->
                runCatching { application.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            } ?: manifest.icon,
            size = stat.size,
        )
    }
}
