package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 仅用于编译的 Xposed API 存根（compileOnly）。
 * 运行期由 LSPosed 注入的框架提供同名真实实现，本类不会被打进 APK。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
