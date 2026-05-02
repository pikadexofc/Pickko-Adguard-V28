package com.pickko.adguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class LocalVpnService extends VpnService {
    private static final String TAG = "LocalVpnService";
    public static final String ACTION_START = "com.pickko.adguard.START";
    public static final String ACTION_STOP = "com.pickko.adguard.STOP";
    public static final String EXTRA_PROVIDER = "provider";
    public static final String EXTRA_MODE = "mode";

    public static final AtomicLong totalQueries = new AtomicLong(0);
    public static final AtomicLong blockedQueries = new AtomicLong(0);
    private static final List<String> activityLogs = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_ACTIVITY_LOGS = 10;

    private ParcelFileDescriptor vpnInterface = null;
    private Thread vpnThread = null;
    private ExecutorService executorService = null;
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private static final Map<String, String[]> DNS_PROVIDERS = new HashMap<>();
    private static final java.util.Set<String> AD_DOMAINS = new java.util.HashSet<>();

    static {
        DNS_PROVIDERS.put("AdGuard DNS", new String[]{"94.140.14.14"});
        DNS_PROVIDERS.put("Cloudflare", new String[]{"1.1.1.1"});
        DNS_PROVIDERS.put("NextDNS", new String[]{"45.90.28.0"});
        DNS_PROVIDERS.put("Quad9", new String[]{"9.9.9.9"});
        DNS_PROVIDERS.put("AdGuard Family", new String[]{"94.140.14.15"});

        String[] adNetworks = {
            "doubleclick.net", "google-analytics.com", "applovin.com", "applvn.com",
            "unity3d.com", "vungle.com", "ironsrc.com", "adcolony.com", "chartboost.com",
            "adjust.com", "appsflyer.com", "crashlytics.com", "mopub.com", "supersonicads.com",
            "tapjoy.com", "admob.com", "googleanalytics.com", "bugsnag.com", "media.net",
            "sentry-cdn.com", "getsentry.com", "yahooinc.com", "yandex.ru", "yandex.net",
            "unityads.unity3d.com", "tiktokv.com", "googletagmanager.com", "mixpanel.com",
            "optimizely.com", "pangleglobal.com", "bnc.lt", "cookielaw.org", "cookiebot.com",
            "onetrust.com", "trustarc.com", "jwpsrv.com", "jwpcdn.com", "fwmrm.net",
            "connatix.com", "scorecardresearch.com", "skimresources.com", "viglink.com",
            "linksynergy.com", "impact.com", "awin1.com", "shareasale.com", "ironSource.mobi",
            "thetradedesk.com", "crypto-loot.org", "greatis.com", "ct.pinterest.com",
            "mads-eu.amazon.com", "cdn.privacy-mgmt.com", "app.usercentrics.eu",
            "anrdoezrs.net", "zenaps.com", "trackcmp.net", "dai.google.com", "indexexchange.com",
            "tracking.miui.com", "data.mistat.xiaomi.com", "samsungqbe.com", "samsungacr.com",
            "metrics.apple.com", "graph.facebook.com", "app-measurement.com", "branch.io",
            "kochava.com", "singular.net", "segment.com", "flurry.com", "inmobi.com", "criteo.com",
            "appboy.com", "braze.com", "raygun.io", "urbanairship.com", "clevertap.com",
            "moengage.com", "localytics.com", "leanplum.com", "onesignal.com"
        };
        for (String domain : adNetworks) AD_DOMAINS.add(domain);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startVpn(intent.getStringExtra(EXTRA_PROVIDER), intent.getStringExtra(EXTRA_MODE));
            } else if (ACTION_STOP.equals(action)) {
                stopVpn();
                stopSelf();
            }
        }
        return START_STICKY;
    }

    private void startVpn(String provider, String mode) {
        if (vpnInterface != null) stopVpn();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pickko Shield Active")
                .setContentText("DNS: " + provider)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        Builder builder = new Builder();
        builder.setSession("Pickko Adguard");
        try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
        builder.addAddress("10.1.1.1", 24);
        builder.addDnsServer("10.1.1.2");
        builder.addRoute("10.1.1.2", 32);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.allowBypass();

        final String upstreamDns = getDnsForProvider(provider)[0];
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                executorService = Executors.newCachedThreadPool();
                startVpnThread(vpnInterface, upstreamDns);
            }
        } catch (Exception e) { Log.e(TAG, "Failed", e); }
    }

    private void startVpnThread(final ParcelFileDescriptor iface, final String upstreamDns) {
        vpnThread = new Thread(() -> {
            try (FileInputStream in = new FileInputStream(iface.getFileDescriptor());
                 FileOutputStream out = new FileOutputStream(iface.getFileDescriptor())) {
                byte[] packet = new byte[32767];
                while (!Thread.interrupted()) {
                    int length = in.read(packet);
                    if (length > 0) {
                        byte[] data = new byte[length];
                        System.arraycopy(packet, 0, data, 0, length);
                        executorService.execute(() -> handlePacket(data, length, out, upstreamDns));
                    }
                }
            } catch (Exception e) { Log.e(TAG, "Stopped"); }
        });
        vpnThread.start();
    }

    private void handlePacket(byte[] packet, int length, FileOutputStream out, String upstreamDns) {
        try {
            if (length < 28) return;
            int ihl = (packet[0] & 0x0F) * 4;
            if ((packet[9] & 0xFF) != 17) return; // UDP only
            int destPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
            if (destPort != 53) return;

            totalQueries.incrementAndGet();
            String host = parseHost(packet, ihl + 8, length);
            boolean blocked = isBlocked(host);
            log(host, blocked);

            if (blocked) {
                blockedQueries.incrementAndGet();
                return;
            }

            byte[] dnsData = new byte[length - ihl - 8];
            System.arraycopy(packet, ihl + 8, dnsData, 0, dnsData.length);

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(3000);
                DatagramPacket q = new DatagramPacket(dnsData, dnsData.length, InetAddress.getByName(upstreamDns), 53);
                socket.send(q);

                byte[] buf = new byte[4096];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                socket.receive(r);

                byte[] resp = new byte[ihl + 8 + r.getLength()];
                System.arraycopy(packet, 0, resp, 0, ihl + 8);
                System.arraycopy(r.getData(), 0, resp, ihl + 8, r.getLength());

                // Swap IPs/Ports
                System.arraycopy(packet, 12, resp, 16, 4);
                System.arraycopy(packet, 16, resp, 12, 4);
                resp[ihl] = packet[ihl + 2]; resp[ihl + 1] = packet[ihl + 3];
                resp[ihl + 2] = packet[ihl]; resp[ihl + 3] = packet[ihl + 1];

                resp[ihl + 4] = (byte)((r.getLength() + 8) >> 8);
                resp[ihl + 5] = (byte)((r.getLength() + 8) & 0xFF);
                resp[ihl + 6] = 0; resp[ihl + 7] = 0;
                resp[2] = (byte)(resp.length >> 8); resp[3] = (byte)(resp.length & 0xFF);
                resp[10] = 0; resp[11] = 0;
                int sum = calcSum(resp, ihl);
                resp[10] = (byte)(sum >> 8); resp[11] = (byte)(sum & 0xFF);

                synchronized (out) { out.write(resp); }
            }
        } catch (Exception ignored) {}
    }

    private int calcSum(byte[] b, int len) {
        int sum = 0;
        for (int i = 0; i < len; i += 2) sum += (((b[i] << 8) & 0xFF00) | (b[i+1] & 0xFF));
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private String parseHost(byte[] d, int off, int total) {
        try {
            StringBuilder sb = new StringBuilder();
            int p = off + 12;
            while (p < total) {
                int len = d[p] & 0xFF;
                if (len == 0) break;
                if (sb.length() > 0) sb.append(".");
                for (int i=0; i<len; i++) sb.append((char)d[p+1+i]);
                p += len + 1;
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private boolean isBlocked(String h) {
        if (h == null) return false;
        String l = h.toLowerCase();
        for (String d : AD_DOMAINS) if (l.equals(d) || l.endsWith("." + d)) return true;
        return false;
    }

    private void log(String h, boolean b) {
        if (h == null) return;
        String entry = (b ? "BLOCKED:" : "RESOLVED:") + h;
        synchronized (activityLogs) {
            activityLogs.add(0, entry);
            if (activityLogs.size() > MAX_ACTIVITY_LOGS) activityLogs.remove(MAX_ACTIVITY_LOGS);
        }
    }

    public static List<String> getRecentActivity() {
        return new ArrayList<>(activityLogs);
    }

    private String[] getDnsForProvider(String p) {
        return DNS_PROVIDERS.getOrDefault(p, DNS_PROVIDERS.get("AdGuard DNS"));
    }

    private void stopVpn() {
        stopForeground(true);
        if (vpnThread != null) vpnThread.interrupt();
        if (executorService != null) executorService.shutdownNow();
        if (vpnInterface != null) try { vpnInterface.close(); } catch (IOException ignored) {}
        vpnInterface = null;
    }

    @Override public void onDestroy() { stopVpn(); super.onDestroy(); }
    @Override public void onRevoke() { stopVpn(); super.onRevoke(); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Shield", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
