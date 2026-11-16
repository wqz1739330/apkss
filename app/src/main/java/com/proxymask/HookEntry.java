package com.proxymask;

import android.net.Uri;

import java.net.ProxySelector;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 让目标 App 读取「代理是否开启」时，永远得到「没有开启」。
 *
 * <p>两种工作模式见 {@link #MODE}：</p>
 * <ul>
 *   <li>MODE_HIDE_ONLY（默认）：只欺骗检测接口，App 真实连接仍然走系统代理，
 *       所以 Burp / Charles 还能继续抓包。</li>
 *   <li>MODE_FORCE_DIRECT：连真实连接也强制直连（App 一定“直连”，但抓不到包）。</li>
 * </ul>
 */
public final class HookEntry implements IXposedHookLoadPackage {

    static final String TAG = "ProxyMask: ";
    static final String SELF_PACKAGE = "com.proxymask";

    /** 0 = 只骗检测，真实连接仍走系统代理（推荐，Burp 可继续抓包） */
    static final int MODE_HIDE_ONLY = 0;
    /** 1 = 真实连接也强制直连 */
    static final int MODE_FORCE_DIRECT = 1;

    /** 想改成「强制直连」就把这里改成 MODE_FORCE_DIRECT。 */
    static final int MODE = MODE_HIDE_ONLY;

    /** 是否连 system_server / SystemUI 一起伪装。默认 false，避免影响系统整体网络。 */
    private static final boolean HOOK_SYSTEM = false;

    /**
     * 递归保护：当平台代理选择器自己去读系统属性时（例如 ProxySelectorImpl.select），
     * 我们不能把这些读取也伪装掉，否则 App 的真实流量会变成直连、Burp 就抓不到了。
     */
    private static final ThreadLocal<int[]> BYPASS = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    private static ProxySelector maskedSelector;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        final ClassLoader cl = lpparam.classLoader;

        if (pkg == null || SELF_PACKAGE.equals(pkg)) {
            return;
        }
        if (!HOOK_SYSTEM && ("android".equals(pkg) || "com.android.systemui".equals(pkg))) {
            return;
        }

        try {
            installProxySelector();
        } catch (Throwable t) {
            log("ProxySelector.getDefault", t);
        }
        try {
            hookJavaProperties();
        } catch (Throwable t) {
            log("System.getProperty/getProperties", t);
        }
        try {
            hookEnv();
        } catch (Throwable t) {
            log("System.getenv", t);
        }
        try {
            hookSystemProperties(cl);
        } catch (Throwable t) {
            log("android.os.SystemProperties", t);
        }
        try {
            hookSettings(cl);
        } catch (Throwable t) {
            log("Settings.Global/Secure/System", t);
        }
        try {
            hookAndroidProxy(cl);
        } catch (Throwable t) {
            log("android.net.Proxy", t);
        }
        try {
            hookProxyInfo(cl);
        } catch (Throwable t) {
            log("android.net.ProxyInfo", t);
        }
        try {
            hookLinkProperties(cl);
        } catch (Throwable t) {
            log("android.net.LinkProperties", t);
        }
        try {
            hookConnectivityManager(cl);
        } catch (Throwable t) {
            log("android.net.ConnectivityManager", t);
        }

        XposedBridge.log(TAG + "代理伪装已生效 -> " + pkg + " (mode=" + MODE + ")");
    }

    // ------------------------------------------------------------------
    // ProxySelector：决定「真实连接是否还走代理」
    // ------------------------------------------------------------------

    private static void installProxySelector() {
        if (MODE == MODE_FORCE_DIRECT) {
            hook(ProxySelector.class, "getDefault", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(DirectProxySelector.INSTANCE);
                }
            });
            return;
        }

        // MODE_HIDE_ONLY：保留平台真实选择器，只在“检测代码”读属性时伪装。
        if (maskedSelector == null) {
            ProxySelector original = ProxySelector.getDefault();
            maskedSelector = new MaskedProxySelector(
                    original != null ? original : DirectProxySelector.INSTANCE);
        }
        final ProxySelector target = maskedSelector;
        hook(ProxySelector.class, "getDefault", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(target);
            }
        });
    }

    static boolean isBypassed() {
        int[] counter = BYPASS.get();
        return counter != null && counter[0] > 0;
    }

    static void enterBypass() {
        BYPASS.get()[0]++;
    }

    static void exitBypass() {
        int[] counter = BYPASS.get();
        if (counter[0] > 0) {
            counter[0]--;
        }
    }

    // ------------------------------------------------------------------
    // Java 系统属性：System.getProperty("http.proxyHost") 等
    // ------------------------------------------------------------------

    private static void hookJavaProperties() {
        hook(System.class, "getProperty", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object key = param.args[0];
                if (key instanceof String && isProxyKey((String) key)) {
                    param.setResult(null);
                }
            }
        }, String.class);

        hook(System.class, "getProperty", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object key = param.args[0];
                if (key instanceof String && isProxyKey((String) key)) {
                    param.setResult(param.args[1]);
                }
            }
        }, String.class, String.class);

        hook(System.class, "getProperties", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object result = param.getResult();
                if (!(result instanceof Properties)) {
                    return;
                }
                Properties source = (Properties) result;
                Properties cleaned = cleanProperties(source);
                if (cleaned != null) {
                    param.setResult(cleaned);
                }
            }
        });
    }

    /** 没有任何代理键时返回 null（不用替换原对象，减少开销）。 */
    private static Properties cleanProperties(Properties source) {
        boolean dirty = false;
        for (String name : source.stringPropertyNames()) {
            if (isProxyKey(name)) {
                dirty = true;
                break;
            }
        }
        if (!dirty) {
            return null;
        }
        Properties out = new Properties();
        for (String name : source.stringPropertyNames()) {
            if (isProxyKey(name)) {
                continue;
            }
            String value = source.getProperty(name);
            if (value != null) {
                out.setProperty(name, value);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 环境变量：HTTP_PROXY / HTTPS_PROXY ...
    // ------------------------------------------------------------------

    private static void hookEnv() {
        hook(System.class, "getenv", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object key = param.args[0];
                if (key instanceof String && isProxyEnv((String) key)) {
                    param.setResult(null);
                }
            }
        }, String.class);

        hook(System.class, "getenv", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object result = param.getResult();
                if (!(result instanceof Map)) {
                    return;
                }
                Map<?, ?> source = (Map<?, ?>) result;
                boolean dirty = false;
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (entry.getKey() instanceof String && isProxyEnv((String) entry.getKey())) {
                        dirty = true;
                        break;
                    }
                }
                if (!dirty) {
                    return;
                }
                HashMap<String, String> out = new HashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (entry.getKey() instanceof String && entry.getValue() instanceof String
                            && !isProxyEnv((String) entry.getKey())) {
                        out.put((String) entry.getKey(), (String) entry.getValue());
                    }
                }
                param.setResult(out);
            }
        });
    }

    // ------------------------------------------------------------------
    // android.os.SystemProperties（getprop 的 Java 入口，隐藏 API）
    // ------------------------------------------------------------------

    private static void hookSystemProperties(ClassLoader cl) {
        Class<?> systemProperties = XposedHelpers.findClassIfExists("android.os.SystemProperties", cl);
        if (systemProperties == null) {
            return;
        }

        hookAll(systemProperties, "get", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object[] args = param.args;
                if (args == null || args.length == 0 || !(args[0] instanceof String)
                        || !isProxyKey((String) args[0])) {
                    return;
                }
                param.setResult(args.length >= 2 ? args[1] : "");
            }
        });

        hookAll(systemProperties, "getInt", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object[] args = param.args;
                if (args == null || args.length == 0 || !(args[0] instanceof String)
                        || !isProxyKey((String) args[0])) {
                    return;
                }
                param.setResult(args.length >= 2 ? args[1] : Integer.valueOf(0));
            }
        });

        hookAll(systemProperties, "getLong", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object[] args = param.args;
                if (args == null || args.length == 0 || !(args[0] instanceof String)
                        || !isProxyKey((String) args[0])) {
                    return;
                }
                param.setResult(args.length >= 2 ? args[1] : Long.valueOf(0L));
            }
        });

        hookAll(systemProperties, "getBoolean", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isBypassed()) {
                    return;
                }
                Object[] args = param.args;
                if (args == null || args.length == 0 || !(args[0] instanceof String)
                        || !isProxyKey((String) args[0])) {
                    return;
                }
                param.setResult(args.length >= 2 ? args[1] : Boolean.FALSE);
            }
        });
    }

    // ------------------------------------------------------------------
    // Settings.Global / Secure / System："http_proxy" 等
    // ------------------------------------------------------------------

    private static void hookSettings(ClassLoader cl) {
        String[] classNames = {
                "android.provider.Settings$Global",
                "android.provider.Settings$Secure",
                "android.provider.Settings$System"
        };

        for (String className : classNames) {
            Class<?> settings = XposedHelpers.findClassIfExists(className, cl);
            if (settings == null) {
                continue;
            }

            hookAll(settings, "getString", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isBypassed()) {
                        return;
                    }
                    Object[] args = param.args;
                    if (args != null && args.length >= 2 && args[1] instanceof String
                            && isProxyKey((String) args[1])) {
                        param.setResult(null);
                    }
                }
            });

            hookAll(settings, "getInt", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isBypassed()) {
                        return;
                    }
                    Object[] args = param.args;
                    if (args != null && args.length >= 2 && args[1] instanceof String
                            && isProxyKey((String) args[1])) {
                        param.setResult(args.length >= 3 && args[2] != null
                                ? args[2] : Integer.valueOf(0));
                    }
                }
            });

            hookAll(settings, "getLong", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isBypassed()) {
                        return;
                    }
                    Object[] args = param.args;
                    if (args != null && args.length >= 2 && args[1] instanceof String
                            && isProxyKey((String) args[1])) {
                        param.setResult(args.length >= 3 && args[2] != null
                                ? args[2] : Long.valueOf(0L));
                    }
                }
            });

            hookAll(settings, "getFloat", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isBypassed()) {
                        return;
                    }
                    Object[] args = param.args;
                    if (args != null && args.length >= 2 && args[1] instanceof String
                            && isProxyKey((String) args[1])) {
                        param.setResult(args.length >= 3 && args[2] != null
                                ? args[2] : Float.valueOf(0f));
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // android.net.Proxy / ProxyInfo / ProxyProperties
    // ------------------------------------------------------------------

    private static void hookAndroidProxy(ClassLoader cl) {
        Class<?> proxy = XposedHelpers.findClassIfExists("android.net.Proxy", cl);
        if (proxy == null) {
            return;
        }
        hookConst(proxy, "getDefaultHost", null);
        hookConst(proxy, "getDefaultPort", Integer.valueOf(-1));
        hookConst(proxy, "getHost", null);
        hookConst(proxy, "getPort", Integer.valueOf(-1));
        hookConst(proxy, "getDefaultProxy", null);

        // 很老的系统（API < 19）用的是 ProxyProperties
        Class<?> proxyProperties = XposedHelpers.findClassIfExists("android.net.ProxyProperties", cl);
        if (proxyProperties != null) {
            hookConst(proxyProperties, "getHost", null);
            hookConst(proxyProperties, "getPort", Integer.valueOf(-1));
            hookConst(proxyProperties, "getPacFileUrl", Uri.EMPTY);
        }
    }

    private static void hookProxyInfo(ClassLoader cl) {
        Class<?> proxyInfo = XposedHelpers.findClassIfExists("android.net.ProxyInfo", cl);
        if (proxyInfo == null) {
            return;
        }
        hookConst(proxyInfo, "getHost", null);
        hookConst(proxyInfo, "getPort", Integer.valueOf(0));
        hookConst(proxyInfo, "getPacFileUrl", Uri.EMPTY);
        hookConst(proxyInfo, "getExclusionList", new String[0]);
    }

    private static void hookLinkProperties(ClassLoader cl) {
        Class<?> linkProperties = XposedHelpers.findClassIfExists("android.net.LinkProperties", cl);
        hookConst(linkProperties, "getHttpProxy", null);
    }

    private static void hookConnectivityManager(ClassLoader cl) {
        Class<?> connectivityManager = XposedHelpers.findClassIfExists("android.net.ConnectivityManager", cl);
        if (connectivityManager == null) {
            return;
        }
        hookConst(connectivityManager, "getDefaultProxy", null);
        hookConst(connectivityManager, "getGlobalProxy", null);
        hookConst(connectivityManager, "getProxyForNetwork", null);
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** "http.proxyHost"、"https.proxyPort"、"socksProxyHost"、"http_proxy" ... */
    static boolean isProxyKey(String key) {
        if (key == null || key.length() < 5) {
            return false;
        }
        return containsIgnoreCase(key, "proxy") || containsIgnoreCase(key, "socks");
    }

    static boolean isProxyEnv(String key) {
        if (key == null) {
            return false;
        }
        return key.equalsIgnoreCase("http_proxy")
                || key.equalsIgnoreCase("https_proxy")
                || key.equalsIgnoreCase("all_proxy")
                || key.equalsIgnoreCase("no_proxy")
                || key.equalsIgnoreCase("ftp_proxy")
                || key.equalsIgnoreCase("socks_proxy");
    }

    /** 不分配新字符串的大小写无关包含判断，避免频繁调用时的开销。 */
    private static boolean containsIgnoreCase(String text, String needleLower) {
        int max = text.length() - needleLower.length();
        outer:
        for (int i = 0; i <= max; i++) {
            for (int j = 0; j < needleLower.length(); j++) {
                char c = text.charAt(i + j);
                if (c != needleLower.charAt(j) && Character.toLowerCase(c) != needleLower.charAt(j)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void hook(Class<?> clazz, String methodName, XC_MethodHook callback,
                             Class<?>... parameterTypes) {
        Object[] args = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
        args[parameterTypes.length] = callback;
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, args);
        } catch (Throwable ignored) {
            // 该方法在当前系统版本上不存在，跳过
        }
    }

    private static void hookAll(Class<?> clazz, String methodName, XC_MethodHook callback) {
        if (clazz == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, methodName, callback);
        } catch (Throwable ignored) {
            // 当前系统版本没有这个方法，跳过
        }
    }

    private static void hookConst(Class<?> clazz, String methodName, final Object value) {
        if (clazz == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 平台代理选择器自己在建连时读取这些值，必须放行，
                    // 否则 App 的真实流量会被变成直连、抓包工具就看不到流量了。
                    if (isBypassed()) {
                        return;
                    }
                    param.setResult(value);
                }
            });
        } catch (Throwable ignored) {
            // 当前系统版本没有这个方法，跳过
        }
    }

    private static void log(String where, Throwable t) {
        XposedBridge.log(TAG + where + " 挂载失败: " + t);
    }
}
