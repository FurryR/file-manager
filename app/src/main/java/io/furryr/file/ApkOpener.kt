package io.furryr.file

import io.furryr.file.core.OpenIcon
import io.furryr.file.core.OpenOption
import io.furryr.file.core.VFile
import io.furryr.file.render.ApkRenderer

internal class ApkOpener(label: String) : Factory {
    override val option = OpenOption(id = "apk", label = label, icon = OpenIcon.APK)
    override suspend fun create(file: VFile): Result<Renderer> = Result.success(ApkRenderer(file))
}
