package com.proxymask;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * MODE_HIDE_ONLY 下包装平台默认的 ProxySelector：
 * App 代码调用 select() 时，把「读取系统属性」这一步临时放行，
 * 这样真实连接仍然走系统代理（Burp 能继续抓包），
 * 而 App 自己直接读 System.getProperty / Settings 时拿到的是“没有代理”。
 */
final class MaskedProxySelector extends ProxySelector {

    private final ProxySelector delegate;

    MaskedProxySelector(ProxySelector delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Proxy> select(URI uri) {
        HookEntry.enterBypass();
        try {
            return delegate.select(uri);
        } finally {
            HookEntry.exitBypass();
        }
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        HookEntry.enterBypass();
        try {
            delegate.connectFailed(uri, sa, ioe);
        } finally {
            HookEntry.exitBypass();
        }
    }
}
