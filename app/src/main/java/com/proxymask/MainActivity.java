package com.proxymask;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 模块只是一个“说明页”，真正的逻辑在 HookEntry。
 */
public class MainActivity extends Activity {

    private static final String TEXT =
            "ProxyMask —— 让 App 认为「没有开启代理」\n"
            + "\n"
            + "【怎么用】\n"
            + "1. 手机已安装 Magisk(面具) + Zygisk + LSPosed；\n"
            + "2. 安装本 APK；\n"
            + "3. 打开 LSPosed → 模块 → 启用「ProxyMask 代理伪装」；\n"
            + "4. 在「作用域」里勾选要伪装的目标 App（只勾它，别勾系统框架）；\n"
            + "5. 强制停止目标 App，再重新打开。\n"
            + "\n"
            + "【已拦截的检测点】\n"
            + "• System.getProperty(\"http.proxyHost\") / getProperties()\n"
            + "• System.getenv(\"HTTP_PROXY\") 等环境变量\n"
            + "• android.os.SystemProperties.get(\"http.proxyHost\")\n"
            + "• Settings.Global/Secure.getString(cr, \"http_proxy\")\n"
            + "• android.net.Proxy.getDefaultHost() / getDefaultProxy()\n"
            + "• android.net.ProxyInfo.getHost() / getPacFileUrl()\n"
            + "• ConnectivityManager.getDefaultProxy()\n"
            + "• LinkProperties.getHttpProxy()\n"
            + "\n"
            + "【两种模式】\n"
            + "默认 MODE_HIDE_ONLY：只欺骗检测，App 真实流量仍然走系统代理，\n"
            + "所以 Burp / Charles 还能继续抓到包。\n"
            + "如果想要 App 实际也直连，把 HookEntry.java 里的 MODE 改成\n"
            + "MODE_FORCE_DIRECT 后重新编译。\n"
            + "\n"
            + "【注意】\n"
            + "• 只影响 LSPosed 里勾选的作用域，不会改系统设置；\n"
            + "• 原生 so / WebView / 直接调用 ProxySelector.select() 的检测不在覆盖范围；\n"
            + "• VPN 类代理（TRANSPORT_VPN）检测也不在覆盖范围。\n";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("ProxyMask 代理伪装");

        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());

        TextView textView = new TextView(this);
        textView.setPadding(padding, padding, padding, padding);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textView.setLineSpacing(0f, 1.15f);
        textView.setTextIsSelectable(true);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setText(TEXT);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);
        setContentView(scrollView);
    }
}
