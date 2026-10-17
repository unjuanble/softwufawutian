// 根构建脚本：只声明插件，不在此处配置模块。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
