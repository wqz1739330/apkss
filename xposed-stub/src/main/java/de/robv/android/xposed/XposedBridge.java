package de.robv.android.xposed;

import java.util.Collections;
import java.util.Set;

/**
 * 编译期存根：运行期由 LSPosed 提供真实实现。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                          XC_MethodHook callback) {
        return Collections.emptySet();
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return Collections.emptySet();
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member hookMethod, XC_MethodHook callback) {
        return null;
    }
}
