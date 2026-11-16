# ProxyMask —— 让 App 认为「没有开启代理」

一个 LSPosed 模块：App 无论用哪种方式读取「代理是否开启」，拿到的都是「没有开启」。

> **为什么不是纯 Magisk（面具）模块？**
> App 判断代理，是在**它自己的进程里**调用 Java/Android API（`System.getProperty("http.proxyHost")`、`Settings.Global.getString(cr, "http_proxy")`、`ConnectivityManager.getDefaultProxy()`、`ProxyInfo`、`LinkProperties` ……）。
> Magisk 模块只能改系统文件、`resetprop` 改系统属性、跑开机脚本，**没有能力拦截 App 进程内的函数调用**。
> 要在 App 进程里改这些返回值，必须用 Xposed 系框架 —— 也就是 **LSPosed**（它本身就跑在面具 / KernelSU 上）。
> 所以本工程交付的是 **LSPosed 模块 APK**，不是刷入式 zip。

---

## 1. 效果

在 LSPosed 里勾选目标 App 后：

| App 的检测方式 | 伪装后拿到的结果 |
| --- | --- |
| `System.getProperty("http.proxyHost")` / `"https.proxyHost"` | `null` |
| `System.getProperty("http.proxyPort")` | `null`（带默认值重载返回默认值） |
| `System.getProperties().getProperty("http.proxyHost")` | `null`（`getProperties()` 返回的副本里已删除代理键） |
| `System.getenv("HTTP_PROXY")` / `"HTTPS_PROXY"` / `"ALL_PROXY"` | `null` |
| `android.os.SystemProperties.get("http.proxyHost")` | `""`（`getInt` → 0，`getBoolean` → false） |
| `Settings.Global.getString(cr, "http_proxy")`、`"global_http_proxy_host"` 等 | `null` |
| `android.net.Proxy.getDefaultHost()` / `getDefaultPort()` | `null` / `-1` |
| `android.net.Proxy.getDefaultProxy()` | `null` |
| `ProxyInfo.getHost()` / `getPort()` / `getPacFileUrl()` | `null` / `0` / `Uri.EMPTY` |
| `ConnectivityManager.getDefaultProxy()` / `getGlobalProxy()` | `null` |
| `LinkProperties.getHttpProxy()` | `null` |

判定规则：属性/设置名里含 `proxy`（大小写无关）或 `socks`，`http_proxy` 之类的环境变量单独列出。

### 关键设计：默认不破坏你的抓包

绝大多数「检测」读的是 `System.getProperty` / `Settings`，而 **真实联网**（OkHttp、`HttpURLConnection`）走的是 `ProxySelector`。所以默认模式 `MODE_HIDE_ONLY` 做了区分：

- 检测代码读属性 → 被伪装成「没有代理」；
- 平台默认 `ProxySelector` 真正去建连时读属性 → 放行，**真实流量依然走你的 Wi‑Fi 代理，Burp 继续能抓包**。

实现方式：把 `ProxySelector.getDefault()` 换成一个包装器，`select()` 期间用 `ThreadLocal` 放行属性读取（见 `MaskedProxySelector.java`）。

如果你希望 App **实际也走直连**（彻底不管代理），把 `HookEntry.java` 里的

```java
static final int MODE = MODE_HIDE_ONLY;
```

改成 `MODE_FORCE_DIRECT` 重新编译即可。此时 `ProxySelector.getDefault()` 直接返回只会给出 `DIRECT` 的选择器。

---

## 2. 工程结构

```
proxymask/
├─ settings.gradle / build.gradle / gradle.properties
├─ app/
│  ├─ build.gradle
│  └─ src/main/
│     ├─ AndroidManifest.xml        # xposedmodule / xposeddescription / xposedminversion
│     ├─ assets/xposed_init         # 入口类 com.proxymask.HookEntry
│     └─ java/com/proxymask/
│        ├─ HookEntry.java          # 所有 hook 逻辑
│        ├─ MaskedProxySelector.java# 保留真实代理连接的包装选择器
│        ├─ DirectProxySelector.java# 强制直连模式用
│        └─ MainActivity.java       # 模块说明页
├─ xposed-stub/                     # 编译期 Xposed API 存根（不会打进 APK）
└─ .github/workflows/build.yml      # 一键出 APK
```

`xposed-stub` 的存在是为了**离线可编译**：不需要访问 `api.xposed.info` 拉 `api-82.jar`。这些类只在编译期参与，运行期由 LSPosed 注入的真实 `XposedBridge` 提供（APK 里不包含它们，不会有冲突）。

---

## 3. 编译

需要 **JDK 17** + **Android SDK（platform 34）**。三选一：

### 方式 A：GitHub Actions 一键出包（最省事，推荐）

1. 把 `proxymask` 目录推到一个 GitHub 仓库（私有仓库也行）；
2. 仓库 Actions 会自动跑 `Build ProxyMask APK`；
3. 在 workflow 运行记录里下载 artifact `ProxyMask-debug-apk`，里面就是 `app-debug.apk`；
4. 也可以手动点 `Run workflow`。

### 方式 B：Android Studio

1. Android Studio 打开 `proxymask` 目录；
2. 第一次 Sync 会提示缺少 Gradle wrapper —— 在项目根目录执行一次
   `gradle wrapper --gradle-version 8.9`（本机装了 Gradle 的话），
   或者直接在 AS 的 Gradle 设置里选一个本地的 8.7+ 发行版；
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`。

### 方式 C：命令行

```bash
# 需要本机 gradle 8.7+、JDK 17、ANDROID_HOME 指向 SDK
cd proxymask
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 如果你更想用官方 Xposed API：把 `app/build.gradle` 里的 `compileOnly project(":xposed-stub")`
> 换成 `compileOnly "de.robv.android.xposed:api:82"`，并在 `settings.gradle` 的
> `repositories` 里加上 `maven { url "https://api.xposed.info/" }`。

---

## 4. 安装与启用

1. 手机：**Magisk（面具）+ Zygisk + LSPosed**（KernelSU 用户：ZygiskNext + LSPosed 也可以）；
2. 安装 `app-debug.apk`（debug 签名即可，LSPosed 不校验签名）；
3. 打开 **LSPosed 管理器 → 模块 → 启用「ProxyMask 代理伪装」**；
4. 进入该模块的**作用域**，只勾选你要伪装的那个 App（不要勾「系统框架」，否则可能影响整机网络）；
5. 强制停止目标 App（设置 → 应用 → 强行停止），再重新打开；
6. 看日志确认生效：`adb logcat | findstr ProxyMask`，应看到
   `ProxyMask: 代理伪装已生效 -> com.xxx (mode=0)`。

卸载/停用模块并重启目标 App，即恢复原状；模块本身不修改任何系统设置。

---

## 5. 已知边界（重要）

- **只覆盖 Java/Android 层的读取接口**。原生 `so` 直接 `getprop`、WebView/Chromium 内部的代理解析、直接 `ProxySelector.select()` 拿结果做判断的代码，都不在覆盖范围内。
- **VPN 类代理不在覆盖范围**：如果 App 是靠 `NetworkCapabilities.hasTransport(TRANSPORT_VPN)`、`NetworkInterface` 里出现 `tun0` 来判断「被代理」，那就不是本模块管的事 —— 这类需要额外 hook。需要的话可以加（见下）。
- 若 App 检测的是 `ProxySelector.getDefault().select(...)` 的结果，默认模式下它仍会看到真实代理（因为要保住抓包能力）。遇到这种 App 就切 `MODE_FORCE_DIRECT`，代价是抓不到它的包。
- 本模块不涉及 SSL Pinning / 证书校验，那是另一个模块的事（你工作区里的 Burp CA 面具模块负责证书）。

---

## 6. 可以继续加的东西

- **VPN 伪装**：hook `NetworkCapabilities.hasTransport(4)` 返回 false、`NetworkInterface.getNetworkInterfaces()` 过滤 `tun*`；
- **按包名开关**：目前靠 LSPosed 作用域控制，够用；要更细可以在 `HookEntry` 里读模块 SharedPreferences（LSPosed 的 `xposedsharedprefs`）；
- **native 兜底**：hook `android.os.SystemProperties` 只覆盖 Java；纯 native 的 `__system_property_get` 需要 Zygisk 级 hook（工作量大，一般用不上）。
