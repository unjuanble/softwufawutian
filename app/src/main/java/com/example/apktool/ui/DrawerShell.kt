package com.example.apktool.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.apktool.agent.AgentState
import com.example.apktool.agent.SessionInfo
import com.example.apktool.agent.SessionStore
import com.example.apktool.fs.Workspace
import com.example.apktool.fs.Workspaces
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抽屉外壳：Material3 的 [ModalNavigationDrawer] 自带左边缘右滑手势、
 * 拖拽跟手、右侧遮罩与点击关闭，宽度取 80%。
 */
@Composable
fun DrawerShell(
    drawerState: DrawerState,
    drawer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                drawer()
            }
        },
        content = content,
    )
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun sizeText(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    b >= 1024L -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/** 分组标题。 */
@Composable
private fun SectionTitle(text: String, count: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count != null) {
            Spacer(Modifier.weight(1f))
            Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 分组标题行（可点，带箭头）。 */
@Composable
private fun GroupRow(
    title: String,
    subtitle: String?,
    count: Int?,
    expanded: Boolean?,
    leading: @Composable () -> Unit,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded == null) {
            Spacer(Modifier.width(26.dp))
        } else {
            Box(Modifier.size(26.dp).clickable { onToggle() }, contentAlignment = Alignment.Center) {
                Caret(down = expanded, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.width(6.dp))
        leading()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text("$count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 分组里的普通项目行。 */
@Composable
private fun ItemRow(
    text: String,
    detail: String? = null,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, end = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onTrailing?.invoke() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 抽屉内容：工作区（目录，默认折叠）+ 会话 + 底部固定「设置」。
 *
 * @param onReference 把某个路径填进主界面输入框（例如「@/sdcard/Download/a.txt」）。
 */
@Composable
fun AppDrawer(
    ctx: Context,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onReference: (String) -> Unit,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var tick by remember { mutableStateOf(0) }
    val sessionsToday = remember(tick, AgentState.messages.size) { SessionStore.today(ctx) }
    val sessionsEarlier = remember(tick, AgentState.messages.size) { SessionStore.earlier(ctx) }

    Column(
        Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 6.dp)) {
            Text("1Z-Agent", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(
                "手机端 Agent · ${AgentState.mode.label}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        // ---------------------------------------------------------- 工作区
        SectionTitle("工作区（目录）", Workspaces.items.size)
        Workspaces.items.forEach { w ->
            val key = "w:${w.id}"
            val open = expanded[key] == true
            GroupRow(
                title = w.name,
                subtitle = w.path,
                count = null,
                expanded = open,
                highlighted = w.id == Workspaces.currentId,
                leading = {
                    FolderIcon(
                        color = if (w.id == Workspaces.currentId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {
                    Workspaces.setCurrent(ctx, w.id)
                    onClose()
                },
                onToggle = { expanded[key] = !open },
            )
            if (open) {
                ItemRow("引用这个目录", detail = "在输入框里插入 ${w.path}") {
                    onReference(w.path); onClose()
                }
                val recent = remember(tick, w.id) { Workspaces.recentFiles(w, 5) }
                if (recent.isEmpty()) {
                    ItemRow("（这个目录暂时读不到文件）", detail = "可能是没授权或目录不存在") { }
                } else {
                    recent.forEach { f: File ->
                        ItemRow(
                            text = f.name,
                            detail = "${sizeText(f.length())} · ${fmtTime(f.lastModified())}",
                            onClick = { onReference(f.absolutePath); onClose() },
                        )
                    }
                }
                if (Workspaces.items.size > 1) {
                    ItemRow("移除这个工作区", detail = "只从列表里去掉，不删文件") {
                        Workspaces.remove(ctx, w.id); expanded[key] = false; tick++
                    }
                }
            }
        }
        ItemRow("＋  添加工作区（选目录）", detail = "在设置页里选目录并授权") {
            onOpenSettings(); onClose()
        }

        // ---------------------------------------------------------- 会话
        SectionTitle("会话", sessionsToday.size + sessionsEarlier.size)
        GroupRow(
            title = "新对话",
            subtitle = "把当前对话归档，开一段新的",
            count = null,
            expanded = null,
            leading = { Box(Modifier.size(22.dp)) },
            onClick = {
                SessionStore.newSession(ctx)
                AgentState.status = "就绪"
                tick++
                onClose()
            },
            onToggle = { },
        )
        GroupRow(
            title = "今天的对话",
            subtitle = "点开切回某一段继续",
            count = sessionsToday.size,
            expanded = expanded["s:today"] == true,
            leading = { Box(Modifier.size(22.dp)) },
            onClick = { expanded["s:today"] = !(expanded["s:today"] == true) },
            onToggle = { expanded["s:today"] = !(expanded["s:today"] == true) },
        )
        if (expanded["s:today"] == true) {
            sessionRows(ctx, sessionsToday) { tick++ }
        }
        GroupRow(
            title = "历史对话",
            subtitle = "更早的会话",
            count = sessionsEarlier.size,
            expanded = expanded["s:earlier"] == true,
            leading = { Box(Modifier.size(22.dp)) },
            onClick = { expanded["s:earlier"] = !(expanded["s:earlier"] == true) },
            onToggle = { expanded["s:earlier"] = !(expanded["s:earlier"] == true) },
        )
        if (expanded["s:earlier"] == true) {
            sessionRows(ctx, sessionsEarlier) { tick++ }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpenSettings(); onClose() }
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 齿轮用文字标签代替图标，避免字体缺字形
            Text("设置", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Caret(down = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // 抽屉打开时刷新一次列表（会话可能在别处变化）
    LaunchedEffect(Unit) { tick++ }
}

@Composable
private fun sessionRows(
    ctx: Context,
    list: List<SessionInfo>,
    onChange: () -> Unit,
) {
    if (list.isEmpty()) {
        ItemRow("（还没有记录）", detail = "对话一旦开始就会自动存下来") { }
        return
    }
    list.take(12).forEach { s ->
        ItemRow(
            text = s.title,
            detail = "${fmtTime(s.updatedAt)} · ${s.count} 条",
            trailing = "删除",
            onClick = { SessionStore.load(ctx, s.id); onChange() },
            onTrailing = { SessionStore.delete(ctx, s.id); onChange() },
        )
    }
}
