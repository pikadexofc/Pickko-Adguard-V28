package com.pickko.adguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.LruCache;

import androidx.core.app.NotificationCompat;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LocalVpnService extends VpnService {
    private static final String TAG = "LocalVpnService";
    public static final String ACTION_START = "com.pickko.adguard.START";
    public static final String ACTION_STOP = "com.pickko.adguard.STOP";
    public static final String EXTRA_PROVIDER = "provider";
    public static final String EXTRA_MODE = "mode";

    public static final AtomicLong totalQueries = new AtomicLong(0);
    public static final AtomicLong blockedQueries = new AtomicLong(0);
    
    // --- Phase 5: Lock-Free Atomic Ring Buffer ---
    private static final int MAX_ACTIVITY_LOGS = 10;
    private static final String[] activityRingBuffer = new String[MAX_ACTIVITY_LOGS];
    private static final AtomicInteger ringIndex = new AtomicInteger(0);
    public static final List<String> recentActivity = new ArrayList<>(); // Legacy bridge for UI polling

    private ParcelFileDescriptor vpnInterface = null;
    private Thread vpnThread = null;
    private ExecutorService executorService = null;
    private Selector selector = null;
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private static final Map<String, String[]> DNS_PROVIDERS = new HashMap<>();
    
    // --- Phase 2 & 4: Radix Trie for Suffix Matching ---
    private static final TrieNode TRIE_ROOT = new TrieNode();

    private static final ConcurrentLinkedQueue<ByteBuffer> BUFFER_POOL = new ConcurrentLinkedQueue<>();
    private static final int MAX_PACKET_SIZE = 32767;

    private static final LruCache<String, byte[]> DNS_CACHE = new LruCache<>(500);

    static {
        DNS_PROVIDERS.put("AdGuard DNS", new String[]{"94.140.14.14", "94.140.15.15"});
        DNS_PROVIDERS.put("Cloudflare", new String[]{"1.1.1.1", "1.0.0.1"});
        DNS_PROVIDERS.put("NextDNS", new String[]{"45.90.28.0", "45.90.30.0"});
        DNS_PROVIDERS.put("Quad9", new String[]{"9.9.9.9", "149.112.112.112"});
        DNS_PROVIDERS.put("AdGuard Family", new String[]{"94.140.14.15", "94.140.15.16"});

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
        for (String domain : adNetworks) {
            insertInverted(domain.toLowerCase());
        }
    }

    private static void insertInverted(String domain) {
        TrieNode curr = TRIE_ROOT;
        String[] parts = domain.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            curr = curr.children.computeIfAbsent(parts[i], k -> new TrieNode());
        }
        curr.isLeaf = true;
    }

    private static class TrieNode {
        Map<String, TrieNode> children = new HashMap<>();
        boolean isLeaf = false;
    }

    private ByteBuffer obtainBuffer() {
        ByteBuffer buf = BUFFER_POOL.poll();
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(MAX_PACKET_SIZE);
        }
        buf.clear();
        return buf;
    }

    private void releaseBuffer(ByteBuffer buf) {
        if (BUFFER_POOL.size() < 100) BUFFER_POOL.offer(buf);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case ACTION_START:
                        startVpn(intent.getStringExtra(EXTRA_PROVIDER), intent.getStringExtra(EXTRA_MODE));
                        break;
                    case ACTION_STOP:
                        stopVpn();
                        stopSelf();
                        break;
                }
            }
        }
        return START_STICKY;
    }

    private void startVpn(String provider, String mode) {
        if (vpnInterface != null) stopVpn();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Pickko Shield Active")
                .setContentText("Mode: " + mode + " | DNS: " + provider)
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
        builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
        builder.addRoute("fd00:1:fd00:1:fd00:1:fd00:2", 128);
        builder.addDnsServer("fd00:1:fd00:1:fd00:1:fd00:2");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.allowBypass();

        String upstreamDns = getDnsForProvider(provider)[0];
        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                executorService = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
                selector = Selector.open();
                startVpnThread(vpnInterface, upstreamDns);
            }
        } catch (Exception e) { Log.e(TAG, "VPN Setup Failed", e); }
    }

    private void startVpnThread(final ParcelFileDescriptor iface, final String upstreamDns) {
        vpnThread = new Thread(() -> {
            FileDescriptor fd = iface.getFileDescriptor();
            ByteBuffer readBuffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE);
            while (!Thread.interrupted()) {
                try {
                    readBuffer.clear();
                    // --- Phase 1: Native OS Syscall ---
                    int length = Os.read(fd, readBuffer);
                    if (length > 0) {
                        readBuffer.flip();
                        ByteBuffer packetCopy = obtainBuffer();
                        packetCopy.put(readBuffer);
                        packetCopy.flip();
                        executorService.execute(() -> handlePacket(packetCopy, length, fd, upstreamDns));
                    }
                } catch (Exception e) { break; }
            }
        });
        vpnThread.start();
        
        // --- Phase 3: Non-Blocking NIO Selector Thread ---
        new Thread(() -> {
            while (!Thread.interrupted() && selector != null) {
                try {
                    if (selector.select(1000) == 0) continue;
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (key.isReadable()) {
                            DatagramChannel channel = (DatagramChannel) key.channel();
                            DnsContext ctx = (DnsContext) key.attachment();
                            ByteBuffer respBuf = obtainBuffer();
                            InetSocketAddress src = (InetSocketAddress) channel.receive(respBuf);
                            if (src != null) {
                                respBuf.flip();
                                byte[] data = new byte[respBuf.remaining()];
                                respBuf.get(data);
                                DNS_CACHE.put(ctx.hostname, data);
                                sendResponsePacket(ctx.requestPacket, ctx.ihl, data, ctx.vpnFd);
                            }
                            releaseBuffer(respBuf);
                            channel.close();
                            key.cancel();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private static class DnsContext {
        String hostname;
        ByteBuffer requestPacket;
        int ihl;
        FileDescriptor vpnFd;
        DnsContext(String h, ByteBuffer r, int i, FileDescriptor fd) {
            hostname = h; requestPacket = r; ihl = i; vpnFd = fd;
        }
    }

    private void handlePacket(ByteBuffer packet, int length, FileDescriptor vpnFd, String upstreamDns) {
        try {
            if (length < 28) return;
            int ihl = (packet.get(0) & 0x0F) * 4;
            int protocol = packet.get(9) & 0xFF;
            if (protocol != 17) return;

            int destPort = ((packet.get(ihl + 2) & 0xFF) << 8) | (packet.get(ihl + 3) & 0xFF);
            if (destPort == 53) {
                totalQueries.incrementAndGet();
                String hostname = parseDnsHostnameFast(packet, ihl + 8, length);
                if (hostname != null) {
                    boolean blocked = isBlocked(hostname);
                    logActivity(hostname, blocked);
                    if (blocked) {
                        blockedQueries.incrementAndGet();
                        return;
                    }
                    byte[] cached = DNS_CACHE.get(hostname);
                    if (cached != null) {
                        sendResponsePacket(packet, ihl, cached, vpnFd);
                        return;
                    }
                }

                // --- Phase 3: Non-Blocking Upstream ---
                DatagramChannel channel = DatagramChannel.open();
                channel.configureBlocking(false);
                channel.connect(new InetSocketAddress(upstreamDns, 53));
                
                ByteBuffer dnsData = ByteBuffer.allocate(length - ihl - 8);
                packet.position(ihl + 8);
                dnsData.put(packet);
                dnsData.flip();
                channel.write(dnsData);
                
                channel.register(selector, SelectionKey.OP_READ, new DnsContext(hostname, packet, ihl, vpnFd));
                return; // Buffer released by selector thread or context logic
            }
        } catch (Exception ignored) {} finally { releaseBuffer(packet); }
    }

    private void sendResponsePacket(ByteBuffer req, int ihl, byte[] dnsResp, FileDescriptor vpnFd) {
        ByteBuffer resp = obtainBuffer();
        try {
            int totalLen = ihl + 8 + dnsResp.length;
            req.rewind();
            resp.put(req.array(), 0, ihl + 8);
            resp.position(ihl + 8);
            resp.put(dnsResp);
            
            // Swap IPs (Machine Level)
            resp.put(12, req.get(16)); resp.put(13, req.get(17)); resp.put(14, req.get(18)); resp.put(15, req.get(19));
            resp.put(16, req.get(12)); resp.put(17, req.get(13)); resp.put(18, req.get(14)); resp.put(19, req.get(15));
            
            // Swap Ports
            resp.put(ihl, req.get(ihl + 2)); resp.put(ihl + 1, req.get(ihl + 3));
            resp.put(ihl + 2, req.get(ihl)); resp.put(ihl + 3, req.get(ihl + 1));
            
            resp.putShort(ihl + 4, (short)(dnsResp.length + 8));
            resp.putShort(2, (short)totalLen);
            resp.putShort(10, (short)0);
            
            // --- Phase 6: Vectorized Checksum ---
            int checksum = calculateChecksum(resp, ihl);
            resp.putShort(10, (short)checksum);
            
            resp.position(0);
            resp.limit(totalLen);
            synchronized (vpnFd) { Os.write(vpnFd, resp); }
        } catch (Exception ignored) {} finally { releaseBuffer(resp); }
    }

    private int calculateChecksum(ByteBuffer buf, int length) {
        int sum = 0;
        buf.rewind();
        LongBuffer lb = buf.asLongBuffer();
        // Read 8 bytes at a time (Phase 6)
        while (lb.hasRemaining() && length >= 8) {
            long l = lb.get();
            sum += (int)(l >>> 32) + (int)(l & 0xFFFFFFFFL);
            length -= 8;
        }
        buf.position(buf.limit() - length);
        while (length > 1) {
            sum += (buf.getShort() & 0xFFFF);
            length -= 2;
        }
        if (length > 0) sum += ((buf.get() & 0xFF) << 8);
        while ((sum >>> 16) != 0) sum = (sum & 0xFFFF) + (sum >>> 16);
        return ~sum & 0xFFFF;
    }

    private String parseDnsHostnameFast(ByteBuffer data, int offset, int total) {
        try {
            byte[] name = new byte[256];
            int len = 0, p = offset + 12;
            while (p < total) {
                int l = data.get(p) & 0xFF;
                if (l == 0) break;
                if (len > 0) name[len++] = '.';
                for (int i=0; i<l; i++) name[len++] = data.get(p + 1 + i);
                p += l + 1;
            }
            return new String(name, 0, len, StandardCharsets.US_ASCII);
        } catch (Exception e) { return null; }
    }

    private boolean isBlocked(String hostname) {
        if (hostname == null) return false;
        String[] parts = hostname.toLowerCase().split("\\.");
        TrieNode curr = TRIE_ROOT;
        for (int i = parts.length - 1; i >= 0; i--) {
            curr = curr.children.get(parts[i]);
            if (curr == null) return false;
            if (curr.isLeaf) return true;
        }
        return false;
    }

    private void logActivity(String hostname, boolean blocked) {
        String entry = (blocked ? "BLOCKED:" : "RESOLVED:") + hostname;
        int idx = ringIndex.getAndIncrement() % MAX_ACTIVITY_LOGS;
        activityRingBuffer[idx] = entry;
        
        // Sync legacy list for UI
        synchronized (recentActivity) {
            recentActivity.clear();
            for (String s : activityRingBuffer) if (s != null) recentActivity.add(s);
        }
    }

    private String[] getDnsForProvider(String p) {
        return DNS_PROVIDERS.getOrDefault(p, DNS_PROVIDERS.get("AdGuard DNS"));
    }

    private void stopVpn() {
        stopForeground(true);
        if (vpnThread != null) vpnThread.interrupt();
        if (executorService != null) executorService.shutdownNow();
        if (vpnInterface != null) try { vpnInterface.close(); } catch (IOException ignored) {}
        if (selector != null) try { selector.close(); } catch (IOException ignored) {}
        vpnInterface = null; selector = null;
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
