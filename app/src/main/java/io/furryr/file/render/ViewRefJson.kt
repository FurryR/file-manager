package io.furryr.file.render

import io.furryr.file.core.NodeRef
import io.furryr.file.core.ViewRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for [ViewRef]. Used by tab/history persistence and by agent tool
 * arguments; it deliberately does not produce an address string.
 */
object ViewRefJson {
    fun encode(ref: ViewRef): JSONObject = when (ref) {
        is ViewRef.Node -> JSONObject()
            .put("kind", "node")
            .put("root", ref.root.let { (it as NodeRef.Vfs).uri })
            .put("view", ref.viewId)
            .put("path", JSONArray(ref.path))
        is ViewRef.Url -> JSONObject()
            .put("kind", "url")
            .put("url", ref.url)
        is ViewRef.Session -> JSONObject()
            .put("kind", "session")
            .put("id", ref.id)
            .put("sessionKind", ref.kind)
    }

    fun decode(json: JSONObject): ViewRef = when (json.getString("kind")) {
        "node" -> ViewRef.Node(
            root = NodeRef.Vfs(json.getString("root")),
            viewId = json.getString("view"),
            path = json.getJSONArray("path").let { array ->
                buildList { for (i in 0 until array.length()) add(array.getString(i)) }
            },
        )
        "url" -> ViewRef.Url(json.getString("url"))
        "session" -> ViewRef.Session(
            id = json.getString("id"),
            kind = json.getString("sessionKind"),
        )
        else -> error("unknown ViewRef kind: ${json.getString("kind")}")
    }

    fun encodeString(ref: ViewRef): String = encode(ref).toString()
    fun decodeString(raw: String): ViewRef = decode(JSONObject(raw))
}
