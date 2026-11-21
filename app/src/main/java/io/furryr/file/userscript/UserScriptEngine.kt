package io.furryr.file.userscript

import android.content.Context

/**
 * Process-wide compiled user-script snapshot.
 *
 * Script files are read only when the installation changes; document-start
 * matching and the JavaScript bridge always work against this immutable
 * snapshot. The settings screen can open a script in the editor, so a reload or
 * navigation checks the tree's newest mtime before deciding to recompile.
 */
object UserScriptEngine {
    private data class Snapshot(val scripts: List<UserScriptSource>)

    @Volatile
    private var snapshot = Snapshot(emptyList())

    @Volatile
    private var loadedStamp = 0L

    private val lock = Any()

    /** Load the snapshot once per process. */
    fun load(context: Context) {
        refresh(context)
    }

    fun refresh(context: Context) {
        synchronized(lock) {
            val scripts = UserScriptStore.scripts(context)
                .filter { it.enabled }
                .mapNotNull { compile(it, context) }
            snapshot = Snapshot(scripts)
            loadedStamp = UserScriptStore.latestModified(context)
        }
    }

    /** Recompile after an editor save; cheap when nothing changed. */
    fun refreshIfChanged(context: Context) {
        if (UserScriptStore.latestModified(context) == loadedStamp) return
        runCatching { refresh(context) }
    }

    fun scriptsFor(url: String): List<UserScriptSource> =
        snapshot.scripts.filter { it.accepts(url) }

    fun source(id: String): UserScriptSource? =
        snapshot.scripts.firstOrNull { it.meta.id == id }

    fun install(context: Context, draft: UserScriptDraft, replacingId: String? = null): UserScript {
        val script = UserScriptStore.install(context, draft, replacingId)
        refresh(context)
        return script
    }

    fun update(context: Context, id: String): UserScript {
        val script = UserScriptStore.update(context, id)
        refresh(context)
        return script
    }

    fun delete(context: Context, id: String) {
        runCatching { UserScriptStore.delete(context, id) }
        runCatching { refresh(context) }
    }

    fun setScriptEnabled(context: Context, id: String, enabled: Boolean) {
        runCatching { UserScriptStore.setScriptEnabled(context, id, enabled) }
        runCatching { refresh(context) }
    }

    fun value(context: Context, scriptId: String, key: String): String? =
        UserScriptStore.value(context, scriptId, key)

    fun setValue(context: Context, scriptId: String, key: String, value: String) {
        UserScriptStore.setValue(context, scriptId, key, value)
    }

    fun deleteValue(context: Context, scriptId: String, key: String) {
        UserScriptStore.deleteValue(context, scriptId, key)
    }

    fun values(context: Context, scriptId: String): Map<String, String> =
        UserScriptStore.values(context, scriptId)

    fun resource(context: Context, scriptId: String, name: String): UserScriptResource? =
        UserScriptStore.resource(context, scriptId, name)

    private fun compile(script: UserScript, context: Context): UserScriptSource? {
        val matches = script.matches.mapNotNull(UserScriptPattern::match)
        val includes = script.includes.mapNotNull(UserScriptPattern::include)
        if (matches.isEmpty() && includes.isEmpty()) return null
        return UserScriptSource(
            meta = script,
            requires = UserScriptStore.requires(context, script.id),
            resources = UserScriptStore.resources(context, script.id),
            matchMatchers = matches,
            includeMatchers = includes,
            excludeMatchers = script.excludes.mapNotNull(UserScriptPattern::include),
        )
    }
}
