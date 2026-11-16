package com.proxymask;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * MODE_FORCE_DIRECT 下使用：永远返回 DIRECT。
 */
final class DirectProxySelector extends ProxySelector {

    static final DirectProxySelector INSTANCE = new DirectProxySelector();

    private DirectProxySelector() {
    }

    static List<Proxy> directList() {
        List<Proxy> list = new ArrayList<>(1);
        list.add(Proxy.NO_PROXY);
        return list;
    }

    @Override
    public List<Proxy> select(URI uri) {
        return directList();
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // 直连没有代理连接失败的概念
    }
}
