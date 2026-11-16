package de.robv.android.xposed.callbacks;

/**
 * 编译期存根：运行期由 LSPosed 提供真实实现。
 */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
