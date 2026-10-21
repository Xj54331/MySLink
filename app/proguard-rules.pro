# ============================================================
#  SonnyLink 发布版混淆规则
# ============================================================

# ---- 保留行号，崩溃栈才有意义 ----
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 纯 Kotlin 模块的模型类（比较脆，保守保留字段名） ----
-keep class com.sonnyapp.core.dlna.** { *; }
-keep class com.sonnyapp.core.importer.** { *; }
