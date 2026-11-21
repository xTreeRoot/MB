package com.xtreeroot.mb.plugin

import org.json.JSONArray
import org.json.JSONObject

/** 本地名称、顺序独立于远程清单、插件代码和账号目录。 */
internal data class PluginPresentation(val names: Map<String, String> = emptyMap(), val order: List<String> = emptyList()) {
    fun toJson() = JSONObject().put("names", JSONObject(names)).put("order", JSONArray(order)).toString()
    fun renamed(id: String, value: String): PluginPresentation {
        val name = value.trim()
        require(name.codePointCount(0, name.length) <= 60) { "本地名称最多 60 个字符" }
        return copy(names = if (name.isEmpty()) names - id else names + (id to name))
    }
    companion object {
        fun read(text: String): PluginPresentation {
            val json = JSONObject(text)
            val names = json.optJSONObject("names")
            val order = json.optJSONArray("order")
            return PluginPresentation(names?.keys()?.asSequence()?.associateWith { names.getString(it) }.orEmpty(),
                order?.let { list -> (0 until list.length()).map(list::getString).distinct() }.orEmpty())
        }
    }
}
