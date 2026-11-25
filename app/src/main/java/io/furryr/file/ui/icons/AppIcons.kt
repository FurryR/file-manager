package io.furryr.file.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Only the icons this app uses, inlined so the APK does not carry
 * `material-icons-extended` (22k generated classes, ~5 MB of dex).
 * Path data is the Material Icons set the extended artifact ships.
 */
object Icons {
    val Default: Filled get() = Filled

    object Filled {
        val Add: ImageVector by lazy {
            icon("Add", autoMirror = false) {
                path {
                    moveTo(19f, 13f)
                    horizontalLineToRelative(-6f)
                    verticalLineToRelative(6f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-6f)
                    horizontalLineTo(5f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(6f)
                    verticalLineTo(5f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(6f)
                    horizontalLineToRelative(6f)
                    verticalLineToRelative(2f)
                    close()
                }
            }
        }
        val Android: ImageVector by lazy {
            icon("Android", autoMirror = false) {
                path {
                    moveTo(17.6f, 9.48f)
                    lineToRelative(1.84f, -3.18f)
                    curveToRelative(0.16f, -0.31f, 0.04f, -0.69f, -0.26f, -0.85f)
                    curveToRelative(-0.29f, -0.15f, -0.65f, -0.06f, -0.83f, 0.22f)
                    lineToRelative(-1.88f, 3.24f)
                    curveToRelative(-2.86f, -1.21f, -6.08f, -1.21f, -8.94f, 0f)
                    lineTo(5.65f, 5.67f)
                    curveToRelative(-0.19f, -0.29f, -0.58f, -0.38f, -0.87f, -0.2f)
                    curveTo(4.5f, 5.65f, 4.41f, 6.01f, 4.56f, 6.3f)
                    lineTo(6.4f, 9.48f)
                    curveTo(3.3f, 11.25f, 1.28f, 14.44f, 1f, 18f)
                    horizontalLineToRelative(22f)
                    curveTo(22.72f, 14.44f, 20.7f, 11.25f, 17.6f, 9.48f)
                    close()
                    moveTo(7f, 15.25f)
                    curveToRelative(-0.69f, 0f, -1.25f, -0.56f, -1.25f, -1.25f)
                    curveToRelative(0f, -0.69f, 0.56f, -1.25f, 1.25f, -1.25f)
                    reflectiveCurveTo(8.25f, 13.31f, 8.25f, 14f)
                    curveTo(8.25f, 14.69f, 7.69f, 15.25f, 7f, 15.25f)
                    close()
                    moveTo(17f, 15.25f)
                    curveToRelative(-0.69f, 0f, -1.25f, -0.56f, -1.25f, -1.25f)
                    curveToRelative(0f, -0.69f, 0.56f, -1.25f, 1.25f, -1.25f)
                    reflectiveCurveToRelative(1.25f, 0.56f, 1.25f, 1.25f)
                    curveTo(18.25f, 14.69f, 17.69f, 15.25f, 17f, 15.25f)
                    close()
                }
            }
        }
        val Archive: ImageVector by lazy {
            icon("Archive", autoMirror = false) {
                path {
                    moveTo(20.54f, 5.23f)
                    lineToRelative(-1.39f, -1.68f)
                    curveTo(18.88f, 3.21f, 18.47f, 3f, 18f, 3f)
                    horizontalLineTo(6f)
                    curveToRelative(-0.47f, 0f, -0.88f, 0.21f, -1.16f, 0.55f)
                    lineTo(3.46f, 5.23f)
                    curveTo(3.17f, 5.57f, 3f, 6.02f, 3f, 6.5f)
                    verticalLineTo(19f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(14f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(6.5f)
                    curveToRelative(0f, -0.48f, -0.17f, -0.93f, -0.46f, -1.27f)
                    close()
                    moveTo(12f, 17.5f)
                    lineTo(6.5f, 12f)
                    horizontalLineTo(10f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(4f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(3.5f)
                    lineTo(12f, 17.5f)
                    close()
                    moveTo(5.12f, 5f)
                    lineToRelative(0.81f, -1f)
                    horizontalLineToRelative(12f)
                    lineToRelative(0.94f, 1f)
                    horizontalLineTo(5.12f)
                    close()
                }
            }
        }
        val AudioFile: ImageVector by lazy {
            icon("AudioFile", autoMirror = false) {
                path {
                    moveTo(14f, 2f)
                    horizontalLineTo(6f)
                    curveTo(4.9f, 2f, 4.01f, 2.9f, 4.01f, 4f)
                    lineTo(4f, 20f)
                    curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
                    horizontalLineTo(18f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(8f)
                    lineTo(14f, 2f)
                    close()
                    moveTo(16f, 13f)
                    horizontalLineToRelative(-3f)
                    verticalLineToRelative(3.75f)
                    curveToRelative(0f, 1.24f, -1.01f, 2.25f, -2.25f, 2.25f)
                    reflectiveCurveTo(8.5f, 17.99f, 8.5f, 16.75f)
                    curveToRelative(0f, -1.24f, 1.01f, -2.25f, 2.25f, -2.25f)
                    curveToRelative(0.46f, 0f, 0.89f, 0.14f, 1.25f, 0.38f)
                    verticalLineTo(11f)
                    horizontalLineToRelative(4f)
                    verticalLineTo(13f)
                    close()
                    moveTo(13f, 9f)
                    verticalLineTo(3.5f)
                    lineTo(18.5f, 9f)
                    horizontalLineTo(13f)
                    close()
                }
            }
        }
        val Check: ImageVector by lazy {
            icon("Check", autoMirror = false) {
                path {
                    moveTo(9f, 16.17f)
                    lineTo(4.83f, 12f)
                    lineToRelative(-1.42f, 1.41f)
                    lineTo(9f, 19f)
                    lineTo(21f, 7f)
                    lineToRelative(-1.41f, -1.41f)
                    close()
                }
            }
        }
        val ChevronRight: ImageVector by lazy {
            icon("ChevronRight", autoMirror = false) {
                path {
                    moveTo(10f, 6f)
                    lineTo(8.59f, 7.41f)
                    lineTo(13.17f, 12f)
                    lineToRelative(-4.58f, 4.59f)
                    lineTo(10f, 18f)
                    lineToRelative(6f, -6f)
                    close()
                }
            }
        }
        val ClearAll: ImageVector by lazy {
            icon("ClearAll", autoMirror = false) {
                path {
                    moveTo(5f, 13f)
                    horizontalLineToRelative(14f)
                    verticalLineToRelative(-2f)
                    lineTo(5f, 11f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(3f, 17f)
                    horizontalLineToRelative(14f)
                    verticalLineToRelative(-2f)
                    lineTo(3f, 15f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(7f, 7f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(14f)
                    lineTo(21f, 7f)
                    lineTo(7f, 7f)
                    close()
                }
            }
        }
        val Close: ImageVector by lazy {
            icon("Close", autoMirror = false) {
                path {
                    moveTo(19f, 6.41f)
                    lineTo(17.59f, 5f)
                    lineTo(12f, 10.59f)
                    lineTo(6.41f, 5f)
                    lineTo(5f, 6.41f)
                    lineTo(10.59f, 12f)
                    lineTo(5f, 17.59f)
                    lineTo(6.41f, 19f)
                    lineTo(12f, 13.41f)
                    lineTo(17.59f, 19f)
                    lineTo(19f, 17.59f)
                    lineTo(13.41f, 12f)
                    close()
                }
            }
        }
        val Cloud: ImageVector by lazy {
            icon("Cloud", autoMirror = false) {
                path {
                    moveTo(19.35f, 10.04f)
                    curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
                    curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                    curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
                    curveToRelative(0f, 3.31f, 2.69f, 6f, 6f, 6f)
                    horizontalLineToRelative(13f)
                    curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
                    curveToRelative(0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                    close()
                }
            }
        }
        val Code: ImageVector by lazy {
            icon("Code", autoMirror = false) {
                path {
                    moveTo(9.4f, 16.6f)
                    lineTo(4.8f, 12f)
                    lineToRelative(4.6f, -4.6f)
                    lineTo(8f, 6f)
                    lineToRelative(-6f, 6f)
                    lineToRelative(6f, 6f)
                    lineToRelative(1.4f, -1.4f)
                    close()
                    moveTo(14.6f, 16.6f)
                    lineToRelative(4.6f, -4.6f)
                    lineToRelative(-4.6f, -4.6f)
                    lineTo(16f, 6f)
                    lineToRelative(6f, 6f)
                    lineToRelative(-6f, 6f)
                    lineToRelative(-1.4f, -1.4f)
                    close()
                }
            }
        }
        val ContentCopy: ImageVector by lazy {
            icon("ContentCopy", autoMirror = false) {
                path {
                    moveTo(16f, 1f)
                    lineTo(4f, 1f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    verticalLineToRelative(14f)
                    horizontalLineToRelative(2f)
                    lineTo(4f, 3f)
                    horizontalLineToRelative(12f)
                    lineTo(16f, 1f)
                    close()
                    moveTo(19f, 5f)
                    lineTo(8f, 5f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    verticalLineToRelative(14f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(11f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    lineTo(21f, 7f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(19f, 21f)
                    lineTo(8f, 21f)
                    lineTo(8f, 7f)
                    horizontalLineToRelative(11f)
                    verticalLineToRelative(14f)
                    close()
                }
            }
        }
        val ContentCut: ImageVector by lazy {
            icon("ContentCut", autoMirror = false) {
                path {
                    moveTo(9.64f, 7.64f)
                    curveToRelative(0.23f, -0.5f, 0.36f, -1.05f, 0.36f, -1.64f)
                    curveToRelative(0f, -2.21f, -1.79f, -4f, -4f, -4f)
                    reflectiveCurveTo(2f, 3.79f, 2f, 6f)
                    reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
                    curveToRelative(0.59f, 0f, 1.14f, -0.13f, 1.64f, -0.36f)
                    lineTo(10f, 12f)
                    lineToRelative(-2.36f, 2.36f)
                    curveTo(7.14f, 14.13f, 6.59f, 14f, 6f, 14f)
                    curveToRelative(-2.21f, 0f, -4f, 1.79f, -4f, 4f)
                    reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
                    reflectiveCurveToRelative(4f, -1.79f, 4f, -4f)
                    curveToRelative(0f, -0.59f, -0.13f, -1.14f, -0.36f, -1.64f)
                    lineTo(12f, 14f)
                    lineToRelative(7f, 7f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(-1f)
                    lineTo(9.64f, 7.64f)
                    close()
                    moveTo(6f, 8f)
                    curveToRelative(-1.1f, 0f, -2f, -0.89f, -2f, -2f)
                    reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                    reflectiveCurveToRelative(2f, 0.89f, 2f, 2f)
                    reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                    close()
                    moveTo(6f, 20f)
                    curveToRelative(-1.1f, 0f, -2f, -0.89f, -2f, -2f)
                    reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                    reflectiveCurveToRelative(2f, 0.89f, 2f, 2f)
                    reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                    close()
                    moveTo(12f, 12.5f)
                    curveToRelative(-0.28f, 0f, -0.5f, -0.22f, -0.5f, -0.5f)
                    reflectiveCurveToRelative(0.22f, -0.5f, 0.5f, -0.5f)
                    reflectiveCurveToRelative(0.5f, 0.22f, 0.5f, 0.5f)
                    reflectiveCurveToRelative(-0.22f, 0.5f, -0.5f, 0.5f)
                    close()
                    moveTo(19f, 3f)
                    lineToRelative(-6f, 6f)
                    lineToRelative(2f, 2f)
                    lineToRelative(7f, -7f)
                    lineTo(22f, 3f)
                    close()
                }
            }
        }
        val DarkMode: ImageVector by lazy {
            icon("DarkMode", autoMirror = false) {
                path {
                    moveTo(12f, 3f)
                    curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
                    reflectiveCurveToRelative(4.03f, 9f, 9f, 9f)
                    reflectiveCurveToRelative(9f, -4.03f, 9f, -9f)
                    curveToRelative(0f, -0.46f, -0.04f, -0.92f, -0.1f, -1.36f)
                    curveToRelative(-0.98f, 1.37f, -2.58f, 2.26f, -4.4f, 2.26f)
                    curveToRelative(-2.98f, 0f, -5.4f, -2.42f, -5.4f, -5.4f)
                    curveToRelative(0f, -1.81f, 0.89f, -3.42f, 2.26f, -4.4f)
                    curveTo(12.92f, 3.04f, 12.46f, 3f, 12f, 3f)
                    lineTo(12f, 3f)
                    close()
                }
            }
        }
        val Delete: ImageVector by lazy {
            icon("Delete", autoMirror = false) {
                path {
                    moveTo(6f, 19f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(8f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(7f)
                    horizontalLineTo(6f)
                    verticalLineToRelative(12f)
                    close()
                    moveTo(19f, 4f)
                    horizontalLineToRelative(-3.5f)
                    lineToRelative(-1f, -1f)
                    horizontalLineToRelative(-5f)
                    lineToRelative(-1f, 1f)
                    horizontalLineTo(5f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(14f)
                    verticalLineTo(4f)
                    close()
                }
            }
        }
        val Description: ImageVector by lazy {
            icon("Description", autoMirror = false) {
                path {
                    moveTo(14f, 2f)
                    lineTo(6f, 2f)
                    curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                    lineTo(4f, 20f)
                    curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
                    lineTo(18f, 22f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    lineTo(20f, 8f)
                    lineToRelative(-6f, -6f)
                    close()
                    moveTo(16f, 18f)
                    lineTo(8f, 18f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(8f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(16f, 14f)
                    lineTo(8f, 14f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(8f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(13f, 9f)
                    lineTo(13f, 3.5f)
                    lineTo(18.5f, 9f)
                    lineTo(13f, 9f)
                    close()
                }
            }
        }
        val DesktopWindows: ImageVector by lazy {
            icon("DesktopWindows", autoMirror = false) {
                path {
                    moveTo(20f, 3f)
                    horizontalLineTo(4f)
                    curveTo(2.9f, 3f, 2f, 3.9f, 2f, 5f)
                    verticalLineToRelative(10f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(6f)
                    verticalLineToRelative(2f)
                    horizontalLineTo(8f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(8f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(6f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(5f)
                    curveTo(22f, 3.9f, 21.1f, 3f, 20f, 3f)
                }
            }
        }
        val Done: ImageVector by lazy {
            icon("Done", autoMirror = false) {
                path {
                    moveTo(9f, 16.2f)
                    lineTo(4.8f, 12f)
                    lineToRelative(-1.4f, 1.4f)
                    lineTo(9f, 19f)
                    lineTo(21f, 7f)
                    lineToRelative(-1.4f, -1.4f)
                    lineTo(9f, 16.2f)
                    close()
                }
            }
        }
        val DragHandle: ImageVector by lazy {
            icon("DragHandle", autoMirror = false) {
                path {
                    moveTo(20f, 9f)
                    horizontalLineTo(4f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(16f)
                    verticalLineTo(9f)
                    close()
                    moveTo(4f, 15f)
                    horizontalLineToRelative(16f)
                    verticalLineToRelative(-2f)
                    horizontalLineTo(4f)
                    verticalLineTo(15f)
                    close()
                }
            }
        }
        val DriveFileRenameOutline: ImageVector by lazy {
            icon("DriveFileRenameOutline", autoMirror = false) {
                path {
                    moveTo(18.41f, 5.8f)
                    lineTo(17.2f, 4.59f)
                    curveToRelative(-0.78f, -0.78f, -2.05f, -0.78f, -2.83f, 0f)
                    lineToRelative(-2.68f, 2.68f)
                    lineTo(3f, 15.96f)
                    verticalLineTo(20f)
                    horizontalLineToRelative(4.04f)
                    lineToRelative(8.74f, -8.74f)
                    lineToRelative(2.63f, -2.63f)
                    curveToRelative(0.79f, -0.78f, 0.79f, -2.05f, 0f, -2.83f)
                    close()
                    moveTo(6.21f, 18f)
                    horizontalLineTo(5f)
                    verticalLineToRelative(-1.21f)
                    lineToRelative(8.66f, -8.66f)
                    lineToRelative(1.21f, 1.21f)
                    lineTo(6.21f, 18f)
                    close()
                    moveTo(11f, 20f)
                    lineToRelative(4f, -4f)
                    horizontalLineToRelative(6f)
                    verticalLineToRelative(4f)
                    horizontalLineTo(11f)
                    close()
                }
            }
        }
        val Edit: ImageVector by lazy {
            icon("Edit", autoMirror = false) {
                path {
                    moveTo(3f, 17.25f)
                    verticalLineTo(21f)
                    horizontalLineToRelative(3.75f)
                    lineTo(17.81f, 9.94f)
                    lineToRelative(-3.75f, -3.75f)
                    lineTo(3f, 17.25f)
                    close()
                    moveTo(20.71f, 7.04f)
                    curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
                    lineToRelative(-2.34f, -2.34f)
                    curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
                    lineToRelative(-1.83f, 1.83f)
                    lineToRelative(3.75f, 3.75f)
                    lineToRelative(1.83f, -1.83f)
                    close()
                }
            }
        }
        val ExpandLess: ImageVector by lazy {
            icon("ExpandLess", autoMirror = false) {
                path {
                    moveTo(12f, 8f)
                    lineToRelative(-6f, 6f)
                    lineToRelative(1.41f, 1.41f)
                    lineTo(12f, 10.83f)
                    lineToRelative(4.59f, 4.58f)
                    lineTo(18f, 14f)
                    close()
                }
            }
        }
        val ExpandMore: ImageVector by lazy {
            icon("ExpandMore", autoMirror = false) {
                path {
                    moveTo(16.59f, 8.59f)
                    lineTo(12f, 13.17f)
                    lineTo(7.41f, 8.59f)
                    lineTo(6f, 10f)
                    lineToRelative(6f, 6f)
                    lineToRelative(6f, -6f)
                    close()
                }
            }
        }
        val Favorite: ImageVector by lazy {
            icon("Favorite", autoMirror = false) {
                path {
                    moveTo(12f, 21.35f)
                    lineToRelative(-1.45f, -1.32f)
                    curveTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
                    curveTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
                    curveToRelative(1.74f, 0f, 3.41f, 0.81f, 4.5f, 2.09f)
                    curveTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
                    curveTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
                    curveToRelative(0f, 3.78f, -3.4f, 6.86f, -8.55f, 11.54f)
                    lineTo(12f, 21.35f)
                    close()
                }
            }
        }
        val FilterAlt: ImageVector by lazy {
            icon("FilterAlt", autoMirror = false) {
                path {
                    moveTo(4.25f, 5.61f)
                    curveTo(6.27f, 8.2f, 10f, 13f, 10f, 13f)
                    verticalLineToRelative(6f)
                    curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                    horizontalLineToRelative(2f)
                    curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                    verticalLineToRelative(-6f)
                    curveToRelative(0f, 0f, 3.72f, -4.8f, 5.74f, -7.39f)
                    curveTo(20.25f, 4.95f, 19.78f, 4f, 18.95f, 4f)
                    horizontalLineTo(5.04f)
                    curveTo(4.21f, 4f, 3.74f, 4.95f, 4.25f, 5.61f)
                    close()
                }
            }
        }
        val Flip: ImageVector by lazy {
            icon("Flip", autoMirror = false) {
                path {
                    moveTo(15f, 21f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 9f)
                    horizontalLineToRelative(2f)
                    lineTo(21f, 7f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(3f, 5f)
                    verticalLineToRelative(14f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(4f)
                    verticalLineToRelative(-2f)
                    lineTo(5f, 19f)
                    lineTo(5f, 5f)
                    horizontalLineToRelative(4f)
                    lineTo(9f, 3f)
                    lineTo(5f, 3f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    close()
                    moveTo(19f, 3f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(11f, 23f)
                    horizontalLineToRelative(2f)
                    lineTo(13f, 1f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(22f)
                    close()
                    moveTo(19f, 17f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(15f, 5f)
                    horizontalLineToRelative(2f)
                    lineTo(17f, 3f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 13f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 21f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                }
            }
        }
        val FlipToBack: ImageVector by lazy {
            icon("FlipToBack", autoMirror = false) {
                path {
                    moveTo(9f, 7f)
                    lineTo(7f, 7f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    lineTo(9f, 7f)
                    close()
                    moveTo(9f, 11f)
                    lineTo(7f, 11f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    close()
                    moveTo(9f, 3f)
                    curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                    horizontalLineToRelative(2f)
                    lineTo(9f, 3f)
                    close()
                    moveTo(13f, 15f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    close()
                    moveTo(19f, 3f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(13f, 3f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    lineTo(13f, 3f)
                    close()
                    moveTo(9f, 17f)
                    verticalLineToRelative(-2f)
                    lineTo(7f, 15f)
                    curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                    close()
                    moveTo(19f, 13f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 9f)
                    horizontalLineToRelative(2f)
                    lineTo(21f, 7f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 17f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(5f, 7f)
                    lineTo(3f, 7f)
                    verticalLineToRelative(12f)
                    curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                    horizontalLineToRelative(12f)
                    verticalLineToRelative(-2f)
                    lineTo(5f, 19f)
                    lineTo(5f, 7f)
                    close()
                    moveTo(15f, 5f)
                    horizontalLineToRelative(2f)
                    lineTo(17f, 3f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(15f, 17f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                }
            }
        }
        val Folder: ImageVector by lazy {
            icon("Folder", autoMirror = false) {
                path {
                    moveTo(10f, 4f)
                    horizontalLineTo(4f)
                    curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                    lineTo(2f, 18f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(16f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(8f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    horizontalLineToRelative(-8f)
                    lineToRelative(-2f, -2f)
                    close()
                }
            }
        }
        val FolderOpen: ImageVector by lazy {
            icon("FolderOpen", autoMirror = false) {
                path {
                    moveTo(20f, 6f)
                    horizontalLineToRelative(-8f)
                    lineToRelative(-2f, -2f)
                    lineTo(4f, 4f)
                    curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                    lineTo(2f, 18f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(16f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    lineTo(22f, 8f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(20f, 18f)
                    lineTo(4f, 18f)
                    lineTo(4f, 8f)
                    horizontalLineToRelative(16f)
                    verticalLineToRelative(10f)
                    close()
                }
            }
        }
        val Group: ImageVector by lazy {
            icon("Group", autoMirror = false) {
                path {
                    moveTo(16f, 11f)
                    curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
                    reflectiveCurveTo(17.66f, 5f, 16f, 5f)
                    curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                    reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                    close()
                    moveTo(8f, 11f)
                    curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
                    reflectiveCurveTo(9.66f, 5f, 8f, 5f)
                    curveTo(6.34f, 5f, 5f, 6.34f, 5f, 8f)
                    reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                    close()
                    moveTo(8f, 13f)
                    curveToRelative(-2.33f, 0f, -7f, 1.17f, -7f, 3.5f)
                    verticalLineTo(19f)
                    horizontalLineToRelative(14f)
                    verticalLineToRelative(-2.5f)
                    curveToRelative(0f, -2.33f, -4.67f, -3.5f, -7f, -3.5f)
                    close()
                    moveTo(16f, 13f)
                    curveToRelative(-0.29f, 0f, -0.62f, 0.02f, -0.97f, 0.05f)
                    curveToRelative(1.16f, 0.84f, 1.97f, 1.97f, 1.97f, 3.45f)
                    verticalLineTo(19f)
                    horizontalLineToRelative(6f)
                    verticalLineToRelative(-2.5f)
                    curveToRelative(0f, -2.33f, -4.67f, -3.5f, -7f, -3.5f)
                    close()
                }
            }
        }
        val FormatListNumbered: ImageVector by lazy {
            icon("FormatListNumbered", autoMirror = false) {
                path {
                    moveTo(2f, 17f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(0.5f)
                    lineTo(3f, 17.5f)
                    verticalLineToRelative(1f)
                    horizontalLineToRelative(1f)
                    verticalLineToRelative(0.5f)
                    lineTo(2f, 19f)
                    verticalLineToRelative(1f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(-4f)
                    lineTo(2f, 16f)
                    verticalLineToRelative(1f)
                    close()
                    moveTo(3f, 8f)
                    horizontalLineToRelative(1f)
                    lineTo(4f, 4f)
                    lineTo(2f, 4f)
                    verticalLineToRelative(1f)
                    horizontalLineToRelative(1f)
                    verticalLineToRelative(3f)
                    close()
                    moveTo(2f, 11f)
                    horizontalLineToRelative(1.8f)
                    lineTo(2f, 13.1f)
                    verticalLineToRelative(0.9f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(-1f)
                    lineTo(3.2f, 13f)
                    lineTo(5f, 10.9f)
                    lineTo(5f, 10f)
                    lineTo(2f, 10f)
                    verticalLineToRelative(1f)
                    close()
                    moveTo(7f, 5f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(14f)
                    lineTo(21f, 5f)
                    lineTo(7f, 5f)
                    close()
                    moveTo(7f, 19f)
                    horizontalLineToRelative(14f)
                    verticalLineToRelative(-2f)
                    lineTo(7f, 17f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(7f, 13f)
                    horizontalLineToRelative(14f)
                    verticalLineToRelative(-2f)
                    lineTo(7f, 11f)
                    verticalLineToRelative(2f)
                    close()
                }
            }
        }
        val History: ImageVector by lazy {
            icon("History", autoMirror = false) {
                path {
                    moveTo(13f, 3f)
                    curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
                    lineTo(1f, 12f)
                    lineToRelative(3.89f, 3.89f)
                    lineToRelative(0.07f, 0.14f)
                    lineTo(9f, 12f)
                    lineTo(6f, 12f)
                    curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
                    reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
                    reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
                    curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
                    lineToRelative(-1.42f, 1.42f)
                    curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
                    curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
                    reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f)
                    close()
                    moveTo(12f, 8f)
                    verticalLineToRelative(5f)
                    lineToRelative(4.28f, 2.54f)
                    lineToRelative(0.72f, -1.21f)
                    lineToRelative(-3.5f, -2.08f)
                    lineTo(13.5f, 8f)
                    lineTo(12f, 8f)
                    close()
                }
            }
        }
        val Image: ImageVector by lazy {
            icon("Image", autoMirror = false) {
                path {
                    moveTo(21f, 19f)
                    verticalLineTo(5f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    horizontalLineTo(5f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    verticalLineToRelative(14f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(14f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    close()
                    moveTo(8.5f, 13.5f)
                    lineToRelative(2.5f, 3.01f)
                    lineTo(14.5f, 12f)
                    lineToRelative(4.5f, 6f)
                    horizontalLineTo(5f)
                    lineToRelative(3.5f, -4.5f)
                    close()
                }
            }
        }
        val Info: ImageVector by lazy {
            icon("Info", autoMirror = false) {
                path {
                    moveTo(12f, 2f)
                    curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                    reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
                    reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
                    reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                    close()
                    moveTo(13f, 17f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-6f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(6f)
                    close()
                    moveTo(13f, 9f)
                    horizontalLineToRelative(-2f)
                    lineTo(11f, 7f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                }
            }
        }
        val Keyboard: ImageVector by lazy {
            icon("Keyboard", autoMirror = false) {
                path {
                    moveTo(20f, 5f)
                    lineTo(4f, 5f)
                    curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                    lineTo(2f, 17f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(16f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    lineTo(22f, 7f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(11f, 8f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(-2f)
                    lineTo(11f, 8f)
                    close()
                    moveTo(11f, 11f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-2f)
                    close()
                    moveTo(8f, 8f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    lineTo(8f, 10f)
                    lineTo(8f, 8f)
                    close()
                    moveTo(8f, 11f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    lineTo(8f, 13f)
                    verticalLineToRelative(-2f)
                    close()
                    moveTo(7f, 13f)
                    lineTo(5f, 13f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(7f, 10f)
                    lineTo(5f, 10f)
                    lineTo(5f, 8f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(16f, 17f)
                    lineTo(8f, 17f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(8f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(16f, 13f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(16f, 10f)
                    horizontalLineToRelative(-2f)
                    lineTo(14f, 8f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 13f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 10f)
                    horizontalLineToRelative(-2f)
                    lineTo(17f, 8f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                }
            }
        }
        val KeyboardArrowDown: ImageVector by lazy {
            icon("KeyboardArrowDown", autoMirror = false) {
                path {
                    moveTo(7.41f, 8.59f)
                    lineTo(12f, 13.17f)
                    lineToRelative(4.59f, -4.58f)
                    lineTo(18f, 10f)
                    lineToRelative(-6f, 6f)
                    lineToRelative(-6f, -6f)
                    lineToRelative(1.41f, -1.41f)
                    close()
                }
            }
        }
        val KeyboardArrowUp: ImageVector by lazy {
            icon("KeyboardArrowUp", autoMirror = false) {
                path {
                    moveTo(7.41f, 15.41f)
                    lineTo(12f, 10.83f)
                    lineToRelative(4.59f, 4.58f)
                    lineTo(18f, 14f)
                    lineToRelative(-6f, -6f)
                    lineToRelative(-6f, 6f)
                    close()
                }
            }
        }
        val Language: ImageVector by lazy {
            icon("Language", autoMirror = false) {
                path {
                    moveTo(11.99f, 2f)
                    curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
                    reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
                    curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                    reflectiveCurveTo(17.52f, 2f, 11.99f, 2f)
                    close()
                    moveTo(18.92f, 8f)
                    horizontalLineToRelative(-2.95f)
                    curveToRelative(-0.32f, -1.25f, -0.78f, -2.45f, -1.38f, -3.56f)
                    curveToRelative(1.84f, 0.63f, 3.37f, 1.91f, 4.33f, 3.56f)
                    close()
                    moveTo(12f, 4.04f)
                    curveToRelative(0.83f, 1.2f, 1.48f, 2.53f, 1.91f, 3.96f)
                    horizontalLineToRelative(-3.82f)
                    curveToRelative(0.43f, -1.43f, 1.08f, -2.76f, 1.91f, -3.96f)
                    close()
                    moveTo(4.26f, 14f)
                    curveTo(4.1f, 13.36f, 4f, 12.69f, 4f, 12f)
                    reflectiveCurveToRelative(0.1f, -1.36f, 0.26f, -2f)
                    horizontalLineToRelative(3.38f)
                    curveToRelative(-0.08f, 0.66f, -0.14f, 1.32f, -0.14f, 2f)
                    curveToRelative(0f, 0.68f, 0.06f, 1.34f, 0.14f, 2f)
                    lineTo(4.26f, 14f)
                    close()
                    moveTo(5.08f, 16f)
                    horizontalLineToRelative(2.95f)
                    curveToRelative(0.32f, 1.25f, 0.78f, 2.45f, 1.38f, 3.56f)
                    curveToRelative(-1.84f, -0.63f, -3.37f, -1.9f, -4.33f, -3.56f)
                    close()
                    moveTo(8.03f, 8f)
                    lineTo(5.08f, 8f)
                    curveToRelative(0.96f, -1.66f, 2.49f, -2.93f, 4.33f, -3.56f)
                    curveTo(8.81f, 5.55f, 8.35f, 6.75f, 8.03f, 8f)
                    close()
                    moveTo(12f, 19.96f)
                    curveToRelative(-0.83f, -1.2f, -1.48f, -2.53f, -1.91f, -3.96f)
                    horizontalLineToRelative(3.82f)
                    curveToRelative(-0.43f, 1.43f, -1.08f, 2.76f, -1.91f, 3.96f)
                    close()
                    moveTo(14.34f, 14f)
                    lineTo(9.66f, 14f)
                    curveToRelative(-0.09f, -0.66f, -0.16f, -1.32f, -0.16f, -2f)
                    curveToRelative(0f, -0.68f, 0.07f, -1.35f, 0.16f, -2f)
                    horizontalLineToRelative(4.68f)
                    curveToRelative(0.09f, 0.65f, 0.16f, 1.32f, 0.16f, 2f)
                    curveToRelative(0f, 0.68f, -0.07f, 1.34f, -0.16f, 2f)
                    close()
                    moveTo(14.59f, 19.56f)
                    curveToRelative(0.6f, -1.11f, 1.06f, -2.31f, 1.38f, -3.56f)
                    horizontalLineToRelative(2.95f)
                    curveToRelative(-0.96f, 1.65f, -2.49f, 2.93f, -4.33f, 3.56f)
                    close()
                    moveTo(16.36f, 14f)
                    curveToRelative(0.08f, -0.66f, 0.14f, -1.32f, 0.14f, -2f)
                    curveToRelative(0f, -0.68f, -0.06f, -1.34f, -0.14f, -2f)
                    horizontalLineToRelative(3.38f)
                    curveToRelative(0.16f, 0.64f, 0.26f, 1.31f, 0.26f, 2f)
                    reflectiveCurveToRelative(-0.1f, 1.36f, -0.26f, 2f)
                    horizontalLineToRelative(-3.38f)
                    close()
                }
            }
        }
        val Lightbulb: ImageVector by lazy {
            icon("Lightbulb", autoMirror = false) {
                path {
                    moveTo(9f, 21f)
                    curveToRelative(0f, 0.5f, 0.4f, 1f, 1f, 1f)
                    horizontalLineToRelative(4f)
                    curveToRelative(0.6f, 0f, 1f, -0.5f, 1f, -1f)
                    verticalLineToRelative(-1f)
                    lineTo(9f, 20f)
                    verticalLineToRelative(1f)
                    close()
                    moveTo(12f, 2f)
                    curveTo(8.1f, 2f, 5f, 5.1f, 5f, 9f)
                    curveToRelative(0f, 2.4f, 1.2f, 4.5f, 3f, 5.7f)
                    lineTo(8f, 17f)
                    curveToRelative(0f, 0.5f, 0.4f, 1f, 1f, 1f)
                    horizontalLineToRelative(6f)
                    curveToRelative(0.6f, 0f, 1f, -0.5f, 1f, -1f)
                    verticalLineToRelative(-2.3f)
                    curveToRelative(1.8f, -1.3f, 3f, -3.4f, 3f, -5.7f)
                    curveToRelative(0f, -3.9f, -3.1f, -7f, -7f, -7f)
                    close()
                }
            }
        }
        val LightMode: ImageVector by lazy {
            icon("LightMode", autoMirror = false) {
                path {
                    moveTo(12f, 7f)
                    curveToRelative(-2.76f, 0f, -5f, 2.24f, -5f, 5f)
                    reflectiveCurveToRelative(2.24f, 5f, 5f, 5f)
                    reflectiveCurveToRelative(5f, -2.24f, 5f, -5f)
                    reflectiveCurveTo(14.76f, 7f, 12f, 7f)
                    lineTo(12f, 7f)
                    close()
                    moveTo(2f, 13f)
                    lineToRelative(2f, 0f)
                    curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                    reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
                    lineToRelative(-2f, 0f)
                    curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                    reflectiveCurveTo(1.45f, 13f, 2f, 13f)
                    close()
                    moveTo(20f, 13f)
                    lineToRelative(2f, 0f)
                    curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                    reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
                    lineToRelative(-2f, 0f)
                    curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                    reflectiveCurveTo(19.45f, 13f, 20f, 13f)
                    close()
                    moveTo(11f, 2f)
                    verticalLineToRelative(2f)
                    curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                    reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                    verticalLineTo(2f)
                    curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                    reflectiveCurveTo(11f, 1.45f, 11f, 2f)
                    close()
                    moveTo(11f, 20f)
                    verticalLineToRelative(2f)
                    curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                    reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                    verticalLineToRelative(-2f)
                    curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                    curveTo(11.45f, 19f, 11f, 19.45f, 11f, 20f)
                    close()
                    moveTo(5.99f, 4.58f)
                    curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                    curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                    lineToRelative(1.06f, 1.06f)
                    curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0f)
                    reflectiveCurveToRelative(0.39f, -1.03f, 0f, -1.41f)
                    lineTo(5.99f, 4.58f)
                    close()
                    moveTo(18.36f, 16.95f)
                    curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                    curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                    lineToRelative(1.06f, 1.06f)
                    curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0f)
                    curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0f, -1.41f)
                    lineTo(18.36f, 16.95f)
                    close()
                    moveTo(19.42f, 5.99f)
                    curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0f, -1.41f)
                    curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                    lineToRelative(-1.06f, 1.06f)
                    curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                    reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0f)
                    lineTo(19.42f, 5.99f)
                    close()
                    moveTo(7.05f, 18.36f)
                    curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0f, -1.41f)
                    curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                    lineToRelative(-1.06f, 1.06f)
                    curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                    reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0f)
                    lineTo(7.05f, 18.36f)
                    close()
                }
            }
        }
        val Link: ImageVector by lazy {
            icon("Link", autoMirror = false) {
                path {
                    moveTo(3.9f, 12f)
                    curveToRelative(0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                    horizontalLineToRelative(4f)
                    lineTo(11f, 7f)
                    lineTo(7f, 7f)
                    curveToRelative(-2.76f, 0f, -5f, 2.24f, -5f, 5f)
                    reflectiveCurveToRelative(2.24f, 5f, 5f, 5f)
                    horizontalLineToRelative(4f)
                    verticalLineToRelative(-1.9f)
                    lineTo(7f, 15.1f)
                    curveToRelative(-1.71f, 0f, -3.1f, -1.39f, -3.1f, -3.1f)
                    close()
                    moveTo(8f, 13f)
                    horizontalLineToRelative(8f)
                    verticalLineToRelative(-2f)
                    lineTo(8f, 11f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(17f, 7f)
                    horizontalLineToRelative(-4f)
                    verticalLineToRelative(1.9f)
                    horizontalLineToRelative(4f)
                    curveToRelative(1.71f, 0f, 3.1f, 1.39f, 3.1f, 3.1f)
                    reflectiveCurveToRelative(-1.39f, 3.1f, -3.1f, 3.1f)
                    horizontalLineToRelative(-4f)
                    lineTo(13f, 17f)
                    horizontalLineToRelative(4f)
                    curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
                    reflectiveCurveToRelative(-2.24f, -5f, -5f, -5f)
                    close()
                }
            }
        }
        val Menu: ImageVector by lazy {
            icon("Menu", autoMirror = false) {
                path {
                    moveTo(3f, 18f)
                    horizontalLineToRelative(18f)
                    verticalLineToRelative(-2f)
                    lineTo(3f, 16f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(3f, 13f)
                    horizontalLineToRelative(18f)
                    verticalLineToRelative(-2f)
                    lineTo(3f, 11f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(3f, 6f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(18f)
                    lineTo(21f, 6f)
                    lineTo(3f, 6f)
                    close()
                }
            }
        }
        val MoreVert: ImageVector by lazy {
            icon("MoreVert", autoMirror = false) {
                path {
                    moveTo(12f, 8f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
                    reflectiveCurveToRelative(-2f, 0.9f, -2f, 2f)
                    reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
                    close()
                    moveTo(12f, 10f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
                    reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
                    reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(12f, 16f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
                    reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
                    reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
                    close()
                }
            }
        }
        val PictureAsPdf: ImageVector by lazy {
            icon("PictureAsPdf", autoMirror = false) {
                path {
                    moveTo(20f, 2f)
                    lineTo(8f, 2f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    verticalLineToRelative(12f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(12f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    lineTo(22f, 4f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(11.5f, 9.5f)
                    curveToRelative(0f, 0.83f, -0.67f, 1.5f, -1.5f, 1.5f)
                    lineTo(9f, 11f)
                    verticalLineToRelative(2f)
                    lineTo(7.5f, 13f)
                    lineTo(7.5f, 7f)
                    lineTo(10f, 7f)
                    curveToRelative(0.83f, 0f, 1.5f, 0.67f, 1.5f, 1.5f)
                    verticalLineToRelative(1f)
                    close()
                    moveTo(16.5f, 11.5f)
                    curveToRelative(0f, 0.83f, -0.67f, 1.5f, -1.5f, 1.5f)
                    horizontalLineToRelative(-2.5f)
                    lineTo(12.5f, 7f)
                    lineTo(15f, 7f)
                    curveToRelative(0.83f, 0f, 1.5f, 0.67f, 1.5f, 1.5f)
                    verticalLineToRelative(3f)
                    close()
                    moveTo(20.5f, 8.5f)
                    lineTo(19f, 8.5f)
                    verticalLineToRelative(1f)
                    horizontalLineToRelative(1.5f)
                    lineTo(20.5f, 11f)
                    lineTo(19f, 11f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(-1.5f)
                    lineTo(17.5f, 7f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(1.5f)
                    close()
                    moveTo(9f, 9.5f)
                    horizontalLineToRelative(1f)
                    verticalLineToRelative(-1f)
                    lineTo(9f, 8.5f)
                    verticalLineToRelative(1f)
                    close()
                    moveTo(4f, 6f)
                    lineTo(2f, 6f)
                    verticalLineToRelative(14f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(14f)
                    verticalLineToRelative(-2f)
                    lineTo(4f, 20f)
                    lineTo(4f, 6f)
                    close()
                    moveTo(14f, 11.5f)
                    horizontalLineToRelative(1f)
                    verticalLineToRelative(-3f)
                    horizontalLineToRelative(-1f)
                    verticalLineToRelative(3f)
                    close()
                }
            }
        }
        val Person: ImageVector by lazy {
            icon("Person", autoMirror = false) {
                path {
                    moveTo(12f, 12f)
                    curveToRelative(2.21f, 0f, 4f, -1.79f, 4f, -4f)
                    reflectiveCurveToRelative(-1.79f, -4f, -4f, -4f)
                    reflectiveCurveToRelative(-4f, 1.79f, -4f, 4f)
                    reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
                    close()
                    moveTo(12f, 14f)
                    curveToRelative(-2.67f, 0f, -8f, 1.34f, -8f, 4f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(16f)
                    verticalLineToRelative(-2f)
                    curveToRelative(0f, -2.66f, -5.33f, -4f, -8f, -4f)
                    close()
                }
            }
        }
        val PowerSettingsNew: ImageVector by lazy {
            icon("PowerSettingsNew", autoMirror = false) {
                path {
                    moveTo(13f, 3f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(10f)
                    horizontalLineToRelative(2f)
                    lineTo(13f, 3f)
                    close()
                    moveTo(17.83f, 5.17f)
                    lineToRelative(-1.42f, 1.42f)
                    curveTo(17.99f, 7.86f, 19f, 9.81f, 19f, 12f)
                    curveToRelative(0f, 3.87f, -3.13f, 7f, -7f, 7f)
                    reflectiveCurveToRelative(-7f, -3.13f, -7f, -7f)
                    curveToRelative(0f, -2.19f, 1.01f, -4.14f, 2.58f, -5.42f)
                    lineTo(6.17f, 5.17f)
                    curveTo(4.23f, 6.82f, 3f, 9.26f, 3f, 12f)
                    curveToRelative(0f, 4.97f, 4.03f, 9f, 9f, 9f)
                    reflectiveCurveToRelative(9f, -4.03f, 9f, -9f)
                    curveToRelative(0f, -2.74f, -1.23f, -5.18f, -3.17f, -6.83f)
                    close()
                }
            }
        }
        val Refresh: ImageVector by lazy {
            icon("Refresh", autoMirror = false) {
                path {
                    moveTo(17.65f, 6.35f)
                    curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                    curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
                    reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
                    curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
                    horizontalLineToRelative(-2.08f)
                    curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
                    curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
                    reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
                    curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
                    lineTo(13f, 11f)
                    horizontalLineToRelative(7f)
                    verticalLineTo(4f)
                    lineToRelative(-2.35f, 2.35f)
                    close()
                }
            }
        }
        val Save: ImageVector by lazy {
            icon("Save", autoMirror = false) {
                path {
                    moveTo(17f, 3f)
                    lineTo(5f, 3f)
                    curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                    verticalLineToRelative(14f)
                    curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                    horizontalLineToRelative(14f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    lineTo(21f, 7f)
                    lineToRelative(-4f, -4f)
                    close()
                    moveTo(12f, 19f)
                    curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f)
                    reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
                    reflectiveCurveToRelative(3f, 1.34f, 3f, 3f)
                    reflectiveCurveToRelative(-1.34f, 3f, -3f, 3f)
                    close()
                    moveTo(15f, 9f)
                    lineTo(5f, 9f)
                    lineTo(5f, 5f)
                    horizontalLineToRelative(10f)
                    verticalLineToRelative(4f)
                    close()
                }
            }
        }
        val SaveAs: ImageVector by lazy {
            icon("SaveAs", autoMirror = false) {
                path {
                    moveTo(21f, 12.4f)
                    verticalLineTo(7f)
                    lineToRelative(-4f, -4f)
                    horizontalLineTo(5f)
                    curveTo(3.89f, 3f, 3f, 3.9f, 3f, 5f)
                    verticalLineToRelative(14f)
                    curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                    horizontalLineToRelative(7.4f)
                    lineTo(21f, 12.4f)
                    close()
                    moveTo(15f, 15f)
                    curveToRelative(0f, 1.66f, -1.34f, 3f, -3f, 3f)
                    reflectiveCurveToRelative(-3f, -1.34f, -3f, -3f)
                    reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
                    reflectiveCurveTo(15f, 13.34f, 15f, 15f)
                    close()
                    moveTo(6f, 6f)
                    horizontalLineToRelative(9f)
                    verticalLineToRelative(4f)
                    horizontalLineTo(6f)
                    verticalLineTo(6f)
                    close()
                    moveTo(19.99f, 16.25f)
                    lineToRelative(1.77f, 1.77f)
                    lineTo(16.77f, 23f)
                    horizontalLineTo(15f)
                    verticalLineToRelative(-1.77f)
                    lineTo(19.99f, 16.25f)
                    close()
                    moveTo(23.25f, 16.51f)
                    lineToRelative(-0.85f, 0.85f)
                    lineToRelative(-1.77f, -1.77f)
                    lineToRelative(0.85f, -0.85f)
                    curveToRelative(0.2f, -0.2f, 0.51f, -0.2f, 0.71f, 0f)
                    lineToRelative(1.06f, 1.06f)
                    curveTo(23.45f, 16f, 23.45f, 16.32f, 23.25f, 16.51f)
                    close()
                }
            }
        }
        val SaveAlt: ImageVector by lazy {
            icon("SaveAlt", autoMirror = false) {
                path {
                    moveTo(19f, 12f)
                    verticalLineToRelative(7f)
                    lineTo(5f, 19f)
                    verticalLineToRelative(-7f)
                    lineTo(3f, 12f)
                    verticalLineToRelative(7f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(14f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineToRelative(-7f)
                    horizontalLineToRelative(-2f)
                    close()
                    moveTo(13f, 12.67f)
                    lineToRelative(2.59f, -2.58f)
                    lineTo(17f, 11.5f)
                    lineToRelative(-5f, 5f)
                    lineToRelative(-5f, -5f)
                    lineToRelative(1.41f, -1.41f)
                    lineTo(11f, 12.67f)
                    lineTo(11f, 3f)
                    horizontalLineToRelative(2f)
                    close()
                }
            }
        }
        val SdCard: ImageVector by lazy {
            icon("SdCard", autoMirror = false) {
                path {
                    moveTo(18f, 2f)
                    horizontalLineToRelative(-8f)
                    lineTo(4.02f, 8f)
                    lineTo(4f, 20f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(12f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    lineTo(20f, 4f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(12f, 8f)
                    horizontalLineToRelative(-2f)
                    lineTo(10f, 4f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(4f)
                    close()
                    moveTo(15f, 8f)
                    horizontalLineToRelative(-2f)
                    lineTo(13f, 4f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(4f)
                    close()
                    moveTo(18f, 8f)
                    horizontalLineToRelative(-2f)
                    lineTo(16f, 4f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(4f)
                    close()
                }
            }
        }
        val Search: ImageVector by lazy {
            icon("Search", autoMirror = false) {
                path {
                    moveTo(15.5f, 14f)
                    horizontalLineToRelative(-0.79f)
                    lineToRelative(-0.28f, -0.27f)
                    curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                    curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                    reflectiveCurveTo(3f, 5.91f, 3f, 9.5f)
                    reflectiveCurveTo(5.91f, 16f, 9.5f, 16f)
                    curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
                    lineToRelative(0.27f, 0.28f)
                    verticalLineToRelative(0.79f)
                    lineToRelative(5f, 4.99f)
                    lineTo(20.49f, 19f)
                    lineToRelative(-4.99f, -5f)
                    close()
                    moveTo(9.5f, 14f)
                    curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                    reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
                    reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
                    reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
                    close()
                }
            }
        }
        val SelectAll: ImageVector by lazy {
            icon("SelectAll", autoMirror = false) {
                path {
                    moveTo(3f, 5f)
                    horizontalLineToRelative(2f)
                    lineTo(5f, 3f)
                    curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                    close()
                    moveTo(3f, 13f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    lineTo(3f, 11f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(7f, 21f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    lineTo(7f, 19f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(3f, 9f)
                    horizontalLineToRelative(2f)
                    lineTo(5f, 7f)
                    lineTo(3f, 7f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(13f, 3f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    lineTo(13f, 3f)
                    close()
                    moveTo(19f, 3f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(5f, 21f)
                    verticalLineToRelative(-2f)
                    lineTo(3f, 19f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    close()
                    moveTo(3f, 17f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    lineTo(3f, 15f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(9f, 3f)
                    lineTo(7f, 3f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(2f)
                    lineTo(9f, 3f)
                    close()
                    moveTo(11f, 21f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 13f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 21f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 9f)
                    horizontalLineToRelative(2f)
                    lineTo(21f, 7f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(19f, 17f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(15f, 21f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(15f, 5f)
                    horizontalLineToRelative(2f)
                    lineTo(17f, 3f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(7f, 17f)
                    horizontalLineToRelative(10f)
                    lineTo(17f, 7f)
                    lineTo(7f, 7f)
                    verticalLineToRelative(10f)
                    close()
                    moveTo(9f, 9f)
                    horizontalLineToRelative(6f)
                    verticalLineToRelative(6f)
                    lineTo(9f, 15f)
                    lineTo(9f, 9f)
                    close()
                }
            }
        }
        val Settings: ImageVector by lazy {
            icon("Settings", autoMirror = false) {
                path {
                    moveTo(19.14f, 12.94f)
                    curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
                    curveToRelative(0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
                    lineToRelative(2.03f, -1.58f)
                    curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
                    lineToRelative(-1.92f, -3.32f)
                    curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
                    lineToRelative(-2.39f, 0.96f)
                    curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
                    lineTo(14.4f, 2.81f)
                    curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
                    horizontalLineToRelative(-3.84f)
                    curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f)
                    lineTo(9.25f, 5.35f)
                    curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                    lineTo(5.24f, 5.33f)
                    curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f)
                    lineTo(2.74f, 8.87f)
                    curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
                    lineToRelative(2.03f, 1.58f)
                    curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
                    reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
                    lineToRelative(-2.03f, 1.58f)
                    curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                    lineToRelative(1.92f, 3.32f)
                    curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                    lineToRelative(2.39f, -0.96f)
                    curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                    lineToRelative(0.36f, 2.54f)
                    curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                    horizontalLineToRelative(3.84f)
                    curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
                    lineToRelative(0.36f, -2.54f)
                    curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                    lineToRelative(2.39f, 0.96f)
                    curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
                    lineToRelative(1.92f, -3.32f)
                    curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                    lineTo(19.14f, 12.94f)
                    close()
                    moveTo(12f, 15.6f)
                    curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
                    reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
                    reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                    reflectiveCurveTo(13.98f, 15.6f, 12f, 15.6f)
                    close()
                }
            }
        }
        val Share: ImageVector by lazy {
            icon("Share", autoMirror = false) {
                path {
                    moveTo(18f, 16.08f)
                    curveToRelative(-0.76f, 0f, -1.44f, 0.3f, -1.96f, 0.77f)
                    lineTo(8.91f, 12.7f)
                    curveToRelative(0.05f, -0.23f, 0.09f, -0.46f, 0.09f, -0.7f)
                    reflectiveCurveToRelative(-0.04f, -0.47f, -0.09f, -0.7f)
                    lineToRelative(7.05f, -4.11f)
                    curveToRelative(0.54f, 0.5f, 1.25f, 0.81f, 2.04f, 0.81f)
                    curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                    reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
                    reflectiveCurveToRelative(-3f, 1.34f, -3f, 3f)
                    curveToRelative(0f, 0.24f, 0.04f, 0.47f, 0.09f, 0.7f)
                    lineTo(8.04f, 9.81f)
                    curveTo(7.5f, 9.31f, 6.79f, 9f, 6f, 9f)
                    curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                    reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                    curveToRelative(0.79f, 0f, 1.5f, -0.31f, 2.04f, -0.81f)
                    lineToRelative(7.12f, 4.16f)
                    curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                    curveToRelative(0f, 1.61f, 1.31f, 2.92f, 2.92f, 2.92f)
                    curveToRelative(1.61f, 0f, 2.92f, -1.31f, 2.92f, -2.92f)
                    reflectiveCurveToRelative(-1.31f, -2.92f, -2.92f, -2.92f)
                    close()
                }
            }
        }
        val Star: ImageVector by lazy {
            icon("Star", autoMirror = false) {
                path {
                    moveTo(12f, 17.27f)
                    lineTo(18.18f, 21f)
                    lineToRelative(-1.64f, -7.03f)
                    lineTo(22f, 9.24f)
                    lineToRelative(-7.19f, -0.61f)
                    lineTo(12f, 2f)
                    lineTo(9.19f, 8.63f)
                    lineTo(2f, 9.24f)
                    lineToRelative(5.46f, 4.73f)
                    lineTo(5.82f, 21f)
                    close()
                }
            }
        }
        val StarBorder: ImageVector by lazy {
            icon("StarBorder", autoMirror = false) {
                path {
                    moveTo(22f, 9.24f)
                    lineToRelative(-7.19f, -0.62f)
                    lineTo(12f, 2f)
                    lineTo(9.19f, 8.63f)
                    lineTo(2f, 9.24f)
                    lineToRelative(5.46f, 4.73f)
                    lineTo(5.82f, 21f)
                    lineTo(12f, 17.27f)
                    lineTo(18.18f, 21f)
                    lineToRelative(-1.63f, -7.03f)
                    lineTo(22f, 9.24f)
                    close()
                    moveTo(12f, 15.4f)
                    lineToRelative(-3.76f, 2.27f)
                    lineToRelative(1f, -4.28f)
                    lineToRelative(-3.32f, -2.88f)
                    lineToRelative(4.38f, -0.38f)
                    lineTo(12f, 6.1f)
                    lineToRelative(1.71f, 4.04f)
                    lineToRelative(4.38f, 0.38f)
                    lineToRelative(-3.32f, 2.88f)
                    lineToRelative(1f, 4.28f)
                    lineTo(12f, 15.4f)
                    close()
                }
            }
        }
        val Terminal: ImageVector by lazy {
            icon("Terminal", autoMirror = false) {
                path {
                    moveTo(20f, 4f)
                    horizontalLineTo(4f)
                    curveTo(2.89f, 4f, 2f, 4.9f, 2f, 6f)
                    verticalLineToRelative(12f)
                    curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                    horizontalLineToRelative(16f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(6f)
                    curveTo(22f, 4.9f, 21.11f, 4f, 20f, 4f)
                    close()
                    moveTo(20f, 18f)
                    horizontalLineTo(4f)
                    verticalLineTo(8f)
                    horizontalLineToRelative(16f)
                    verticalLineTo(18f)
                    close()
                    moveTo(18f, 17f)
                    horizontalLineToRelative(-6f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(6f)
                    verticalLineTo(17f)
                    close()
                    moveTo(7.5f, 17f)
                    lineToRelative(-1.41f, -1.41f)
                    lineTo(8.67f, 13f)
                    lineToRelative(-2.59f, -2.59f)
                    lineTo(7.5f, 9f)
                    lineToRelative(4f, 4f)
                    lineTo(7.5f, 17f)
                    close()
                }
            }
        }
        val TextFields: ImageVector by lazy {
            icon("TextFields", autoMirror = false) {
                path {
                    moveTo(2.5f, 4f)
                    verticalLineToRelative(3f)
                    horizontalLineToRelative(5f)
                    verticalLineToRelative(12f)
                    horizontalLineToRelative(3f)
                    verticalLineTo(7f)
                    horizontalLineToRelative(5f)
                    verticalLineTo(4f)
                    horizontalLineTo(2.5f)
                    close()
                    moveTo(21.5f, 9f)
                    horizontalLineToRelative(-9f)
                    verticalLineToRelative(3f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(7f)
                    horizontalLineToRelative(3f)
                    verticalLineToRelative(-7f)
                    horizontalLineToRelative(3f)
                    verticalLineTo(9f)
                    close()
                }
            }
        }
        val VideoFile: ImageVector by lazy {
            icon("VideoFile", autoMirror = false) {
                path {
                    moveTo(14f, 2f)
                    horizontalLineTo(6.01f)
                    curveToRelative(-1.1f, 0f, -2f, 0.89f, -2f, 2f)
                    lineTo(4f, 20f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(12f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(8f)
                    lineTo(14f, 2f)
                    close()
                    moveTo(13f, 9f)
                    verticalLineTo(3.5f)
                    lineTo(18.5f, 9f)
                    horizontalLineTo(13f)
                    close()
                    moveTo(14f, 14f)
                    lineToRelative(2f, -1.06f)
                    verticalLineToRelative(4.12f)
                    lineTo(14f, 16f)
                    verticalLineToRelative(1f)
                    curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
                    horizontalLineTo(9f)
                    curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
                    verticalLineToRelative(-4f)
                    curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                    horizontalLineToRelative(4f)
                    curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
                    verticalLineTo(14f)
                    close()
                }
            }
        }
        val Warning: ImageVector by lazy {
            icon("Warning", autoMirror = false) {
                path {
                    moveTo(1f, 21f)
                    horizontalLineToRelative(22f)
                    lineTo(12f, 2f)
                    lineTo(1f, 21f)
                    close()
                    moveTo(13f, 18f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                    moveTo(13f, 14f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-4f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(4f)
                    close()
                }
            }
        }
    }

    object AutoMirrored {
        object Filled {
            val ArrowBack: ImageVector by lazy {
                icon("ArrowBack", autoMirror = true) {
                    path {
                        moveTo(20f, 11f)
                        horizontalLineTo(7.83f)
                        lineToRelative(5.59f, -5.59f)
                        lineTo(12f, 4f)
                        lineToRelative(-8f, 8f)
                        lineToRelative(8f, 8f)
                        lineToRelative(1.41f, -1.41f)
                        lineTo(7.83f, 13f)
                        horizontalLineTo(20f)
                        verticalLineToRelative(-2f)
                        close()
                    }
                }
            }
            val ExitToApp: ImageVector by lazy {
                icon("ExitToApp", autoMirror = true) {
                    path {
                        moveTo(10.09f, 15.59f)
                        lineTo(11.5f, 17f)
                        lineToRelative(5f, -5f)
                        lineToRelative(-5f, -5f)
                        lineToRelative(-1.41f, 1.41f)
                        lineTo(12.67f, 11f)
                        horizontalLineTo(3f)
                        verticalLineToRelative(2f)
                        horizontalLineToRelative(9.67f)
                        lineToRelative(-2.58f, 2.59f)
                        close()
                        moveTo(19f, 3f)
                        horizontalLineTo(5f)
                        curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                        verticalLineToRelative(4f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(5f)
                        horizontalLineToRelative(14f)
                        verticalLineToRelative(14f)
                        horizontalLineTo(5f)
                        verticalLineToRelative(-4f)
                        horizontalLineTo(3f)
                        verticalLineToRelative(4f)
                        curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                        horizontalLineToRelative(14f)
                        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                        verticalLineTo(5f)
                        curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                        close()
                    }
                }
            }
            val InsertDriveFile: ImageVector by lazy {
                icon("InsertDriveFile", autoMirror = true) {
                    path {
                        moveTo(6f, 2f)
                        curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                        lineTo(4f, 20f)
                        curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
                        lineTo(18f, 22f)
                        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                        lineTo(20f, 8f)
                        lineToRelative(-6f, -6f)
                        lineTo(6f, 2f)
                        close()
                        moveTo(13f, 9f)
                        lineTo(13f, 3.5f)
                        lineTo(18.5f, 9f)
                        lineTo(13f, 9f)
                        close()
                    }
                }
            }
            val KeyboardArrowLeft: ImageVector by lazy {
                icon("KeyboardArrowLeft", autoMirror = true) {
                    path {
                        moveTo(15.41f, 16.59f)
                        lineTo(10.83f, 12f)
                        lineToRelative(4.58f, -4.59f)
                        lineTo(14f, 6f)
                        lineToRelative(-6f, 6f)
                        lineToRelative(6f, 6f)
                        lineToRelative(1.41f, -1.41f)
                        close()
                    }
                }
            }
            val KeyboardArrowRight: ImageVector by lazy {
                icon("KeyboardArrowRight", autoMirror = true) {
                    path {
                        moveTo(8.59f, 16.59f)
                        lineTo(13.17f, 12f)
                        lineTo(8.59f, 7.41f)
                        lineTo(10f, 6f)
                        lineToRelative(6f, 6f)
                        lineToRelative(-6f, 6f)
                        lineToRelative(-1.41f, -1.41f)
                        close()
                    }
                }
            }
            val KeyboardReturn: ImageVector by lazy {
                icon("KeyboardReturn", autoMirror = true) {
                    path {
                        moveTo(19f, 7f)
                        verticalLineToRelative(4f)
                        horizontalLineTo(5.83f)
                        lineToRelative(3.58f, -3.59f)
                        lineTo(8f, 6f)
                        lineToRelative(-6f, 6f)
                        lineToRelative(6f, 6f)
                        lineToRelative(1.41f, -1.41f)
                        lineTo(5.83f, 13f)
                        horizontalLineTo(21f)
                        verticalLineTo(7f)
                        close()
                    }
                }
            }
            val OpenInNew: ImageVector by lazy {
                icon("OpenInNew", autoMirror = true) {
                    path {
                        moveTo(19f, 19f)
                        horizontalLineTo(5f)
                        verticalLineTo(5f)
                        horizontalLineToRelative(7f)
                        verticalLineTo(3f)
                        horizontalLineTo(5f)
                        curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                        verticalLineToRelative(14f)
                        curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                        horizontalLineToRelative(14f)
                        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                        verticalLineToRelative(-7f)
                        horizontalLineToRelative(-2f)
                        verticalLineToRelative(7f)
                        close()
                        moveTo(14f, 3f)
                        verticalLineToRelative(2f)
                        horizontalLineToRelative(3.59f)
                        lineToRelative(-9.83f, 9.83f)
                        lineToRelative(1.41f, 1.41f)
                        lineTo(19f, 6.41f)
                        verticalLineTo(10f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(3f)
                        horizontalLineToRelative(-7f)
                        close()
                    }
                }
            }
            val Redo: ImageVector by lazy {
                icon("Redo", autoMirror = true) {
                    path {
                        moveTo(18.4f, 10.6f)
                        curveTo(16.55f, 8.99f, 14.15f, 8f, 11.5f, 8f)
                        curveToRelative(-4.65f, 0f, -8.58f, 3.03f, -9.96f, 7.22f)
                        lineTo(3.9f, 16f)
                        curveToRelative(1.05f, -3.19f, 4.05f, -5.5f, 7.6f, -5.5f)
                        curveToRelative(1.95f, 0f, 3.73f, 0.72f, 5.12f, 1.88f)
                        lineTo(13f, 16f)
                        horizontalLineToRelative(9f)
                        verticalLineTo(7f)
                        lineToRelative(-3.6f, 3.6f)
                        close()
                    }
                }
            }
            val Send: ImageVector by lazy {
                icon("Send", autoMirror = true) {
                    path {
                        moveTo(2.01f, 21f)
                        lineTo(23f, 12f)
                        lineTo(2.01f, 3f)
                        lineTo(2f, 10f)
                        lineToRelative(15f, 2f)
                        lineToRelative(-15f, 2f)
                        close()
                    }
                }
            }
            val Sort: ImageVector by lazy {
                icon("Sort", autoMirror = true) {
                    path {
                        moveTo(3f, 18f)
                        horizontalLineToRelative(6f)
                        verticalLineToRelative(-2f)
                        lineTo(3f, 16f)
                        verticalLineToRelative(2f)
                        close()
                        moveTo(3f, 6f)
                        verticalLineToRelative(2f)
                        horizontalLineToRelative(18f)
                        lineTo(21f, 6f)
                        lineTo(3f, 6f)
                        close()
                        moveTo(3f, 13f)
                        horizontalLineToRelative(12f)
                        verticalLineToRelative(-2f)
                        lineTo(3f, 11f)
                        verticalLineToRelative(2f)
                        close()
                    }
                }
            }
            val TextSnippet: ImageVector by lazy {
                icon("TextSnippet", autoMirror = true) {
                    path {
                        moveTo(20.41f, 8.41f)
                        lineToRelative(-4.83f, -4.83f)
                        curveTo(15.21f, 3.21f, 14.7f, 3f, 14.17f, 3f)
                        horizontalLineTo(5f)
                        curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
                        verticalLineToRelative(14f)
                        curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                        horizontalLineToRelative(14f)
                        curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                        verticalLineTo(9.83f)
                        curveTo(21f, 9.3f, 20.79f, 8.79f, 20.41f, 8.41f)
                        close()
                        moveTo(7f, 7f)
                        horizontalLineToRelative(7f)
                        verticalLineToRelative(2f)
                        horizontalLineTo(7f)
                        verticalLineTo(7f)
                        close()
                        moveTo(17f, 17f)
                        horizontalLineTo(7f)
                        verticalLineToRelative(-2f)
                        horizontalLineToRelative(10f)
                        verticalLineTo(17f)
                        close()
                        moveTo(17f, 13f)
                        horizontalLineTo(7f)
                        verticalLineToRelative(-2f)
                        horizontalLineToRelative(10f)
                        verticalLineTo(13f)
                        close()
                    }
                }
            }
            val Undo: ImageVector by lazy {
                icon("Undo", autoMirror = true) {
                    path {
                        moveTo(12.5f, 8f)
                        curveToRelative(-2.65f, 0f, -5.05f, 0.99f, -6.9f, 2.6f)
                        lineTo(2f, 7f)
                        verticalLineToRelative(9f)
                        horizontalLineToRelative(9f)
                        lineToRelative(-3.62f, -3.62f)
                        curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
                        curveToRelative(3.54f, 0f, 6.55f, 2.31f, 7.6f, 5.5f)
                        lineToRelative(2.37f, -0.78f)
                        curveTo(21.08f, 11.03f, 17.15f, 8f, 12.5f, 8f)
                        close()
                    }
                }
            }
            val WrapText: ImageVector by lazy {
                icon("WrapText", autoMirror = true) {
                    path {
                        moveTo(4f, 19f)
                        horizontalLineToRelative(6f)
                        verticalLineToRelative(-2f)
                        lineTo(4f, 17f)
                        verticalLineToRelative(2f)
                        close()
                        moveTo(20f, 5f)
                        lineTo(4f, 5f)
                        verticalLineToRelative(2f)
                        horizontalLineToRelative(16f)
                        lineTo(20f, 5f)
                        close()
                        moveTo(17f, 11f)
                        lineTo(4f, 11f)
                        verticalLineToRelative(2f)
                        horizontalLineToRelative(13.25f)
                        curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                        reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                        lineTo(15f, 17f)
                        verticalLineToRelative(-2f)
                        lineToRelative(-3f, 3f)
                        lineToRelative(3f, 3f)
                        verticalLineToRelative(-2f)
                        horizontalLineToRelative(2f)
                        curveToRelative(2.21f, 0f, 4f, -1.79f, 4f, -4f)
                        reflectiveCurveToRelative(-1.79f, -4f, -4f, -4f)
                        close()
                    }
                }
            }
        }
    }
}

private fun icon(name: String, autoMirror: Boolean, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror
    ).apply(block).build()

private fun ImageVector.Builder.path(block: PathBuilder.() -> Unit) {
    addPath(
        pathData = PathBuilder().apply(block).nodes,
        fill = SolidColor(Color.Black)
    )
}
