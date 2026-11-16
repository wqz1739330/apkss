# 模块入口类不能被混淆 / 裁剪
-keep class com.proxymask.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
