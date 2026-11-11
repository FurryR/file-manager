package io.furryr.file.editor

import android.content.Context
import android.os.Bundle
import android.util.Log
import io.furryr.file.R
import io.furryr.file.core.ContentInfo
import io.furryr.file.core.ContentKind
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.runBlocking
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

internal object EditorLanguages {
    private const val DARK_THEME = "file-manager-dark"
    private const val LIGHT_THEME = "file-manager-light"
    private var ready = false

    private val specialNames = setOf(
        "dockerfile", "makefile", "cmakelists.txt", "license", "readme",
        ".gitignore", ".gitattributes", ".editorconfig", ".env", ".bashrc", ".zshrc",
    )

    private val textExtensions = setOf(
        "txt", "md", "markdown", "log", "json", "jsonc", "yaml", "yml", "toml", "ini",
        "cfg", "conf", "properties", "xml", "svg", "html", "htm", "css", "scss", "sass",
        "less", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "py", "pyw",
        "java", "kt", "kts", "c", "h", "cpp", "cc", "hpp", "cxx", "rs", "go", "rb", "php",
        "sh", "bash", "zsh", "fish", "sql", "lua", "dart", "swift", "cs", "gradle", "cmake",
        "bat", "cmd", "ps1", "patch", "diff",
    )

    /** Languages the editor can highlight, exposed by the file-type picker. */
    val languageIds = listOf(
        "plaintext", "kotlin", "java", "python", "javascript", "typescript", "rust",
        "c", "cpp", "csharp", "go", "ruby", "php", "swift", "dart", "shellscript",
        "json", "yaml", "ini", "xml", "html", "css", "markdown", "sql", "lua",
        "dockerfile", "makefile", "cmakelists",
    )

    fun label(context: Context, id: String): String = when (id) {
        "plaintext" -> context.getString(R.string.editor_language_plain_text)
        "dockerfile" -> "Dockerfile"
        "makefile" -> "Makefile"
        "cmakelists" -> "CMake"
        "shellscript" -> "Shell"
        "javascript" -> "JavaScript"
        "typescript" -> "TypeScript"
        "cpp" -> "C++"
        "csharp" -> "C#"
        "json" -> "JSON"
        "yaml" -> "YAML"
        "ini" -> "INI"
        "xml" -> "XML"
        "html" -> "HTML"
        "css" -> "CSS"
        "php" -> "PHP"
        "sql" -> "SQL"
        else -> id.replaceFirstChar { it.uppercase() }
    }

    /** Text/code facts, or a known text extension when a provider could not sniff. */
    fun supports(name: String, content: ContentInfo?): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower in specialNames) return true
        if (lower.substringAfterLast('.', "") in textExtensions) return true
        return content?.kind == ContentKind.TEXT || content?.kind == ContentKind.CODE
    }

    fun id(path: String): String {
        val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
        if (name in setOf("dockerfile", "makefile", "cmakelists.txt")) return name.substringBefore('.')
        return when (name.substringAfterLast('.', "")) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py", "pyw" -> "python"
            "js", "mjs", "cjs", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "rs" -> "rust"
            "c", "h" -> "c"
            "cc", "cpp", "cxx", "hpp", "hxx" -> "cpp"
            "cs" -> "csharp"
            "go" -> "go"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "dart" -> "dart"
            "sh", "bash", "zsh", "fish" -> "shellscript"
            "json", "jsonc" -> "json"
            "yaml", "yml" -> "yaml"
            "toml", "ini", "cfg", "conf", "properties", "env" -> "ini"
            "xml", "svg", "xhtml" -> "xml"
            "html", "htm", "vue" -> "html"
            "css", "scss", "sass", "less" -> "css"
            "md", "markdown" -> "markdown"
            "sql" -> "sql"
            "lua" -> "lua"
            else -> "plaintext"
        }
    }

    @Synchronized
    fun configure(
        context: Context,
        editor: CodeEditor,
        languageId: String,
        dark: Boolean,
        session: () -> LspSession?,
    ) {
        if (!ready) {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            val registry = ThemeRegistry.getInstance()
            registry.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(context.assets.open("editor/theme.json"), "editor/theme.json", null),
                    DARK_THEME,
                ).apply { isDark = true },
            )
            registry.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        context.assets.open("editor/theme-light.json"),
                        "editor/theme-light.json",
                        null,
                    ),
                    LIGHT_THEME,
                ).apply { isDark = false },
            )
            registry.setTheme(if (dark) DARK_THEME else LIGHT_THEME)
            GrammarRegistry.getInstance().loadGrammars("editor/languages.json")
            ready = true
        }
        selectTheme(dark)
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        val grammar = when (languageId) {
            "plaintext" -> null
            "python", "ruby" -> "python"
            "shellscript", "dockerfile", "makefile" -> "shell"
            "json", "yaml", "ini" -> "config"
            "xml", "html" -> "markup"
            "markdown" -> "markdown"
            else -> "code"
        }
        val base: Language = if (grammar == null) EmptyLanguage() else TextMateLanguage.create("source.fm-$grammar", true)
        editor.setEditorLanguage(ServerLanguage(base, session))
    }

    private fun selectTheme(dark: Boolean) {
        val name = if (dark) DARK_THEME else LIGHT_THEME
        val registry = ThemeRegistry.getInstance()
        if (registry.getCurrentThemeModel()?.name != name) registry.setTheme(name)
    }

    /**
     * Material surface colours win over the static theme file, so the editor
     * follows palette and AMOLED changes. Bracket-delimiter colours are set
     * explicitly: Sora's default foreground is opaque black, which makes the
     * matching bracket unreadable on a dark background.
     */
    fun applyColors(
        editor: CodeEditor,
        dark: Boolean,
        background: Int,
        foreground: Int,
        accent: Int,
        bracketBackground: Int,
        lineNumberBackground: Int,
        lineHighlight: Int,
        muted: Int,
    ) {
        selectTheme(dark)
        val scheme = editor.colorScheme ?: return
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, background)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, lineNumberBackground)
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, foreground)
        scheme.setColor(EditorColorScheme.LINE_NUMBER, muted)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, foreground)
        scheme.setColor(EditorColorScheme.CURRENT_LINE, lineHighlight)
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, foreground)
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, bracketBackground)
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, accent)
        scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, bracketBackground)
    }
}

private class ServerLanguage(private val base: Language, private val session: () -> LspSession?) : Language by base {
    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_STRONG

    override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extraArguments: Bundle) {
        val lsp = session()
        if (lsp == null) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        val version = content.documentVersion
        val snapshot = content.toString()
        val beforeCursor = content.getLine(position.line).take(position.column)
        val prefix = beforeCursor.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }.length
        try {
            val items = runBlocking { lsp.completion(snapshot, version, position.line, position.column) }
            items.filter { it.optInt("insertTextFormat", 1) != 2 }.forEach { item ->
                publisher.addItem(ServerCompletion(item, version, prefix))
            }
        } catch (error: IOException) {
            Log.d("EditorLsp", "Completion unavailable", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private class ServerCompletion(private val item: JSONObject, private val version: Long, prefix: Int) :
    CompletionItem(item.optString("label"), item.optString("detail")) {
    init {
        prefixLength = prefix
        sortText = item.optString("sortText", item.optString("label"))
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        // Results arriving after another edit must never overwrite newer text.
        if (text.documentVersion != version) return
        val edits = mutableListOf<Edit>()
        val primary = item.optJSONObject("textEdit")
        val primaryEdit = if (primary != null) {
            parseEdit(text, primary, primary.optJSONObject("range") ?: primary.optJSONObject("replace") ?: primary.optJSONObject("insert")) ?: return
        } else {
            val offset = text.getCharIndex(line, column)
            Edit(offset - prefixLength, offset, item.optString("insertText", item.optString("label")))
        }
        edits.add(primaryEdit)
        item.optJSONArray("additionalTextEdits")?.let { more ->
            for (index in 0 until more.length()) {
                val edit = more.optJSONObject(index) ?: return
                edits.add(parseEdit(text, edit, edit.optJSONObject("range")) ?: return)
            }
        }
        val sorted = edits.sortedBy { it.start }
        if (sorted.zipWithNext().any { (left, right) -> left.end > right.start }) return
        var cursor = primaryEdit.start + primaryEdit.value.length
        edits.filter { it !== primaryEdit && it.end <= primaryEdit.start }.forEach { cursor += it.value.length - (it.end - it.start) }
        edits.sortedByDescending { it.start }.forEach { text.replace(it.start, it.end, it.value) }
        val position = text.indexer.getCharPosition(cursor.coerceIn(0, text.length))
        editor.setSelection(position.line, position.column)
    }

    private fun parseEdit(text: Content, edit: JSONObject, range: JSONObject?): Edit? {
        if (range == null) return null
        fun index(position: JSONObject?): Int? {
            if (position == null) return null
            val line = position.optInt("line", -1)
            val column = position.optInt("character", -1)
            if (line !in 0 until text.lineCount || column !in 0..text.getColumnCount(line)) return null
            return text.getCharIndex(line, column)
        }
        val start = index(range.optJSONObject("start")) ?: return null
        val end = index(range.optJSONObject("end")) ?: return null
        if (end < start || !edit.has("newText")) return null
        return Edit(start, end, edit.getString("newText").replace("\r\n", "\n"))
    }

    private data class Edit(val start: Int, val end: Int, val value: String)
}
