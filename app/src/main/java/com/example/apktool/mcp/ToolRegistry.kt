package com.example.apktool.mcp

import org.json.JSONArray
import org.json.JSONObject

/** 一个工具的返回值：给模型的文本 + 是否出错。 */
class ToolResult(val text: String, val isError: Boolean = false) {
    fun toJson(): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        return JSONObject().put("content", content).put("isError", isError)
    }

    companion object {
        fun ok(text: String) = ToolResult(text)
        fun fail(text: String) = ToolResult(text, true)
        fun json(o: Any) = ToolResult(o.toString())
    }
}

/**
 * 工具定义：名字、描述、JSON Schema 入参、实现。
 *
 * @param destructive 是否会**不可逆地改动用户数据**（删除/覆盖/改名/安装等）。
 *   Agent 在执行这类工具前必须向用户确认；MCP 侧不受影响。
 */
class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val destructive: Boolean = false,
    val handler: (JSONObject) -> ToolResult,
)

/** 极简 JSON Schema 构造器，避免手写又长又容易错的 schema 字面量。 */
object S {
    fun obj(
        props: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList(),
        additional: Boolean = false,
    ): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject()
            .put("type", "object")
            .put("properties", p)
            .put("required", JSONArray(required))
            .put("additionalProperties", additional)
    }

    fun str(desc: String, enum: List<String>? = null): JSONObject {
        val o = JSONObject().put("type", "string").put("description", desc)
        if (enum != null) o.put("enum", JSONArray(enum))
        return o
    }

    fun int(desc: String, default: Int? = null, min: Int? = null, max: Int? = null): JSONObject {
        val o = JSONObject().put("type", "integer").put("description", desc)
        if (default != null) o.put("default", default)
        if (min != null) o.put("minimum", min)
        if (max != null) o.put("maximum", max)
        return o
    }

    fun bool(desc: String, default: Boolean? = null): JSONObject {
        val o = JSONObject().put("type", "boolean").put("description", desc)
        if (default != null) o.put("default", default)
        return o
    }
}

class ToolRegistry {
    private val tools = LinkedHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
    }

    fun all(): List<McpTool> = tools.values.toList()

    fun names(): List<String> = tools.keys.toList()

    fun find(name: String): McpTool? = tools[name]

    /** MCP 的 tools/list 格式。 */
    fun toJson(): JSONArray {
        val arr = JSONArray()
        tools.values.forEach { t ->
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("inputSchema", t.inputSchema)
            )
        }
        return arr
    }

    /** OpenAI 兼容 chat/completions 的 tools 参数格式。 */
    fun toOpenAiTools(): JSONArray {
        val arr = JSONArray()
        tools.values.forEach { t ->
            if (t.name.startsWith("__")) return@forEach
            val fn = JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("parameters", t.inputSchema)
            arr.put(JSONObject().put("type", "function").put("function", fn))
        }
        return arr
    }

    fun call(name: String, args: JSONObject): ToolResult {
        val tool = tools[name] ?: return ToolResult.fail("未知工具：$name（可用：${names().joinToString(", ")}）")
        return try {
            tool.handler(args)
        } catch (e: Throwable) {
            ToolResult.fail("工具 $name 执行失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
