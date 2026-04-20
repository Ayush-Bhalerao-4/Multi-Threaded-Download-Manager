package com.downloadmanager;

import javafx.application.Platform;
import javafx.beans.property.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Monitors network connectivity by periodically pinging a reliable endpoint.
 * Fires callbacks when connection state changes.
 */
public class NetworkMonitor {

    public interface Listener {
        void onOnline();
        void onOffline();
    }

    private final BooleanProperty online = new SimpleBooleanProperty(true);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Timer timer;

    public BooleanProperty onlineProperty() { return online; }
    public boolean isOnline() { return online.get(); }

    public void addListener(Listener l) { listeners.add(l); }

    public void start() {
        // Check immediately
        checkOnce();
        timer = new Timer("NetworkMonitor", true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkOnce();
            }
        }, 3000, 3000);
    }

    public void stop() {
        if (timer != null) timer.cancel();
    }

    private void checkOnce() {
        boolean reachable = probe();
        Platform.runLater(() -> {
            boolean was = online.get();
            online.set(reachable);
            if (was && !reachable) listeners.forEach(Listener::onOffline);
            if (!was && reachable) listeners.forEach(Listener::onOnline);
        });
    }

    private boolean probe() {
        String[] hosts = {
            "https://www.google.com",
            "https://www.cloudflare.com",
            "https://dns.google"
        };
        for (String host : hosts) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(host).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setRequestMethod("HEAD");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code >= 200 && code < 400) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }
}
