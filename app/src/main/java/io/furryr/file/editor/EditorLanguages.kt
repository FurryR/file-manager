package io.furryr.file.editor

import android.content.Context
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.runBlocking
import org.eclipse.tm4e.core.registry.IThemeSource
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

internal object EditorLanguages {
    private var ready = false

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
    fun configure(context: Context, editor: CodeEditor, languageId: String, session: () -> LspSession?) {
        if (!ready) {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            val theme = ThemeModel(IThemeSource.fromInputStream(context.assets.open("editor/theme.json"), "editor/theme.json", null), "file-manager").apply { isDark = true }
            ThemeRegistry.getInstance().loadTheme(theme)
            ThemeRegistry.getInstance().setTheme("file-manager")
            GrammarRegistry.getInstance().loadGrammars("editor/languages.json")
            ready = true
        }
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
