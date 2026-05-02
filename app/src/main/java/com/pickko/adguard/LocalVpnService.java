package com.pickko.adguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;

import androidx.core.app.NotificationCompat;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final int MAX_ACTIVITY_LOGS = 15;

    private static final Set<String> AD_DOMAINS = new HashSet<>();
    private static final LruCache<String, Boolean> BLOCK_DECISION_CACHE = new LruCache<>(2000);
    private static final LruCache<String, byte[]> DNS_RESULT_CACHE = new LruCache<>(500);

    private ParcelFileDescriptor vpnInterface = null;
    private Thread vpnThread = null;
    private ExecutorService executorService = null;
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int MAX_PACKET_SIZE = 32767;

    static {
        String[] adNetworks = {
            "doubleclick.net", "google-analytics.com", "applovin.com", "applvn.com",
            "unity3d.com", "vungle.com", "ironsrc.com", "adcolony.com", "chartboost.com",
            "adjust.com", "appsflyer.com", "crashlytics.com", "mopub.com", "supersonicads.com",
            "tapjoy.com", "admob.com", "googleanalytics.com", "bugsnag.com", "media.net",
            "sentry-cdn.com", "getsentry.com", "yahooinc.com", "yandex.ru", "yandex.net",
            "googletagmanager.com", "mixpanel.com", "optimizely.com", "pangleglobal.com",
            "cookielaw.org", "cookiebot.com", "onetrust.com", "trustarc.com", "jwpsrv.com",
            "fwmrm.net", "connatix.com", "scorecardresearch.com", "skimresources.com",
            "thetradedesk.com", "crypto-loot.org", "greatis.com", "ct.pinterest.com",
            "mads-eu.amazon.com", "cdn.privacy-mgmt.com", "app.usercentrics.eu",
            "anrdoezrs.net", "zenaps.com", "trackcmp.net", "dai.google.com", "indexexchange.com",
            "tracking.miui.com", "data.mistat.xiaomi.com", "samsungqbe.com", "samsungacr.com",
            "metrics.apple.com", "graph.facebook.com", "app-measurement.com", "branch.io",
            "kochava.com", "singular.net", "segment.com", "flurry.com", "inmobi.com", "criteo.com",
            "appboy.com", "braze.com", "raygun.io", "urbanairship.com", "clevertap.com",
            "moengage.com", "localytics.com", "leanplum.com", "onesignal.com",
            "ads-api.twitter.com", "ads.pinterest.com", "business-api.tiktok.com",
            "ads-api.tiktok.com", "ads.tiktok.com", "ads-sg.tiktok.com", "log.fc.yahoo.com",
            "udcm.yahoo.com", "ads.yahoo.com", "partnerads.ysm.yahoo.com", "gemini.yahoo.com",
            "bingads.microsoft.com", "pixel.quora.com", "linksynergy.com", "impact.com",
            "imasdk.googleapis.com", "google-analytics.com", "doubleclick.net"
        };
        for (String domain : adNetworks) AD_DOMAINS.add(domain.toLowerCase());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            startVpn(intent.getStringExtra(EXTRA_PROVIDER));
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startVpn(String provider) {
        if (vpnInterface != null) stopVpn();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pickko Shield Active")
                .setContentText("DNS: " + provider)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(1, notification);

        Builder builder = new Builder();
        builder.setSession("Pickko Adguard");
        builder.setMtu(1500);
        try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}
        
        // IPv4 Setup
        builder.addAddress("10.1.1.1", 24);
        builder.addDnsServer("10.1.1.2");
        builder.addRoute("10.1.1.2", 32);

        // IPv6 Setup (Critical for modern phones to prevent leaks/heat)
        builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
        builder.addDnsServer("fd00:1:fd00:1:fd00:1:fd00:2");
        builder.addRoute("fd00:1:fd00:1:fd00:1:fd00:2", 128);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.allowBypass();

        final String upstreamDns = getDns(provider);
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                executorService = Executors.newFixedThreadPool(4);
                startVpnThread(vpnInterface, upstreamDns);
            }
        } catch (Exception e) { Log.e(TAG, "Establish failed", e); }
    }

    private void startVpnThread(final ParcelFileDescriptor iface, final String upstreamDns) {
        vpnThread = new Thread(() -> {
            FileDescriptor fd = iface.getFileDescriptor();
            FileInputStream in = new FileInputStream(fd);
            FileOutputStream out = new FileOutputStream(fd);
            byte[] packet = new byte[MAX_PACKET_SIZE];
            
            while (!Thread.interrupted()) {
                try {
                    int length = in.read(packet);
                    if (length > 0) {
                        byte[] data = new byte[length];
                        System.arraycopy(packet, 0, data, 0, length);
                        executorService.execute(() -> handlePacket(data, length, out, upstreamDns));
                    } else if (length == 0) {
                        Thread.sleep(10);
                    }
                } catch (Exception e) { break; }
            }
        });
        vpnThread.start();
    }

    private void handlePacket(byte[] packet, int length, FileOutputStream out, String upstreamDns) {
        try {
            if (length < 28) return;
            
            int version = (packet[0] & 0xF0) >> 4;
            int ihl;
            int protocol;
            
            if (version == 4) {
                ihl = (packet[0] & 0x0F) * 4;
                protocol = packet[9] & 0xFF;
            } else if (version == 6) {
                ihl = 40;
                protocol = packet[6] & 0xFF;
            } else return;

            if (protocol != 17) return; // UDP only
            
            int destPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
            if (destPort != 53) return;

            // Analytics: Count every DNS packet that hits the VPN
            totalQueries.incrementAndGet();

            String host = parseHost(packet, ihl + 8, length);
            if (host == null) {
                Log.w(TAG, "DNS Query detected but host parsing failed");
                return;
            }

            Log.i(TAG, "Query #" + totalQueries.get() + ": " + host);
            boolean blocked = isBlocked(host);
            log(host, blocked);

            if (blocked) {
                blockedQueries.incrementAndGet();
                sendNxDomainResponse(packet, ihl, out); // FASTER: Tell browser it's gone immediately
                return;
            }

            byte[] cached = DNS_RESULT_CACHE.get(host);
            if (cached != null) {
                sendResponse(packet, ihl, cached, out);
                return;
            }

            byte[] dnsData = new byte[length - ihl - 8];
            System.arraycopy(packet, ihl + 8, dnsData, 0, dnsData.length);

            try (DatagramSocket socket = new DatagramSocket()) {
                protect(socket); // Critical: Bypass VPN for upstream call
                socket.setSoTimeout(2500);
                DatagramPacket q = new DatagramPacket(dnsData, dnsData.length, InetAddress.getByName(upstreamDns), 53);
                socket.send(q);

                byte[] buf = new byte[4096];
                DatagramPacket r = new DatagramPacket(buf, buf.length);
                socket.receive(r);

                byte[] resData = new byte[r.getLength()];
                System.arraycopy(r.getData(), 0, resData, 0, r.getLength());
                DNS_RESULT_CACHE.put(host, resData);

                sendResponse(packet, ihl, resData, out);
            }
        } catch (Exception ignored) {}
    }

    private void sendResponse(byte[] req, int ihl, byte[] dnsRes, FileOutputStream out) throws IOException {
        byte[] resp = new byte[ihl + 8 + dnsRes.length];
        System.arraycopy(req, 0, resp, 0, ihl + 8);
        System.arraycopy(dnsRes, 0, resp, ihl + 8, dnsRes.length);

        if (ihl == 20) { // IPv4
            System.arraycopy(req, 12, resp, 16, 4);
            System.arraycopy(req, 16, resp, 12, 4);
            resp[2] = (byte)(resp.length >> 8); resp[3] = (byte)(resp.length & 0xFF);
            resp[10] = 0; resp[11] = 0;
            int sum = calcSum(resp, ihl);
            resp[10] = (byte)(sum >> 8); resp[11] = (byte)(sum & 0xFF);
        } else { // IPv6 (RFC 2460 Alignment)
            System.arraycopy(req, 8, resp, 24, 16);
            System.arraycopy(req, 24, resp, 8, 16);
            int payloadLen = dnsRes.length + 8;
            resp[4] = (byte)(payloadLen >> 8);
            resp[5] = (byte)(payloadLen & 0xFF);
        }
        
        // Swap Ports
        resp[ihl] = req[ihl + 2]; resp[ihl + 1] = req[ihl + 3];
        resp[ihl + 2] = req[ihl]; resp[ihl + 3] = req[ihl + 1];

        resp[ihl + 4] = (byte)((dnsRes.length + 8) >> 8);
        resp[ihl + 5] = (byte)((dnsRes.length + 8) & 0xFF);
        resp[ihl + 6] = 0; resp[ihl + 7] = 0; // Clear UDP Checksum

        synchronized (out) { out.write(resp); }
    }

    private void sendNxDomainResponse(byte[] req, int ihl, FileOutputStream out) throws IOException {
        // DNS Header for NXDOMAIN (Name Error)
        byte[] nxData = new byte[12];
        System.arraycopy(req, ihl + 8, nxData, 0, 12); // Copy original ID
        nxData[2] = (byte) 0x81; // Response, recursive
        nxData[3] = (byte) 0x83; // NXDOMAIN error code
        nxData[4] = 0; nxData[5] = 1; // 1 Question
        nxData[6] = 0; nxData[7] = 0; // 0 Answer
        nxData[8] = 0; nxData[9] = 0; // 0 Authority
        nxData[10] = 0; nxData[11] = 0; // 0 Additional
        
        // Find end of question in original request to mirror it
        int p = ihl + 8 + 12;
        while (p < req.length) {
            int len = req[p] & 0xFF;
            if (len == 0) { p += 5; break; } // Null terminator + Type/Class (4 bytes)
            p += len + 1;
        }
        
        int questionLen = p - (ihl + 8 + 12);
        byte[] fullNx = new byte[12 + questionLen];
        System.arraycopy(nxData, 0, fullNx, 0, 12);
        System.arraycopy(req, ihl + 8 + 12, fullNx, 12, questionLen);

        sendResponse(req, ihl, fullNx, out);
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
            return sb.toString().toLowerCase();
        } catch (Exception e) { return null; }
    }

    private boolean isBlocked(String host) {
        if (host == null) return false;
        Boolean cached = BLOCK_DECISION_CACHE.get(host);
        if (cached != null) return cached;
        boolean result = checkBlocklist(host);
        BLOCK_DECISION_CACHE.put(host, result);
        return result;
    }

    private boolean checkBlocklist(String host) {
        if (AD_DOMAINS.contains(host)) return true;
        int dot = host.indexOf('.');
        while (dot != -1) {
            String suffix = host.substring(dot + 1);
            if (AD_DOMAINS.contains(suffix)) return true;
            dot = host.indexOf('.', dot + 1);
        }
        return false;
    }

    private void log(String h, boolean b) {
        String entry = (b ? "BLOCKED:" : "RESOLVED:") + h;
        synchronized (activityLogs) {
            activityLogs.add(0, entry);
            if (activityLogs.size() > MAX_ACTIVITY_LOGS) activityLogs.remove(MAX_ACTIVITY_LOGS);
        }
    }

    public static List<String> getRecentActivity() { return new ArrayList<>(activityLogs); }

    private String getDns(String p) {
        if ("Cloudflare".equals(p)) return "1.1.1.1";
        if ("NextDNS".equals(p)) return "45.90.28.0";
        if ("Quad9".equals(p)) return "9.9.9.9";
        if ("AdGuard Family".equals(p)) return "94.140.14.15";
        return "94.140.14.14";
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
