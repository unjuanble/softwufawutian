package com.example.apktool.tools

import android.content.Context
import com.example.apktool.mcp.ToolRegistry

/**
 * 工具集总装：文件管理 + 文本编辑 + 只读 APK 解析（+ 后续的特权/Shizuku 工具）。
 * MCP 服务器、界面「工具清单」与 Agent 循环都从这里取，保证三边看到的是同一套工具。
 */
object McpTools {

    fun build(ctx: Context): ToolRegistry {
        val reg = ToolRegistry()
        FileTools.register(reg, ctx)
        TextTools.register(reg, ctx)
        ApkTools.register(reg, ctx)
        AgentTools.register(reg, ctx)
        return reg
    }
}
