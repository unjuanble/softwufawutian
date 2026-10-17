package com.example.apktool

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.apktool.agent.AgentState
import com.example.apktool.fs.Workspaces
import com.example.apktool.mcp.McpState
import com.example.apktool.ui.ApkToolApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 竖屏使用（设计上只有竖向布局，横屏只会把工具栏挤成一条缝）
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        McpState.load(this)
        AgentState.load(this)
        Workspaces.load(this)
        setContent { ApkToolApp() }
    }
}
