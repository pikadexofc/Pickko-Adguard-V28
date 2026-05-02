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
    public static final List<String> recentActivity = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_ACTIVITY_LOGS = 10;

    private ParcelFileDescriptor vpnInterface = null;
    private Thread vpnThread = null;
    private ExecutorService executorService = null;
    private static final String CHANNEL_ID = "vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final Map<String, String[]> DNS_PROVIDERS = new HashMap<>();

    static {
        DNS_PROVIDERS.put("AdGuard DNS", new String[]{"94.140.14.14", "94.140.15.15"});
        DNS_PROVIDERS.put("Cloudflare", new String[]{"1.1.1.1", "1.0.0.1"});
        DNS_PROVIDERS.put("NextDNS", new String[]{"45.90.28.0", "45.90.30.0"});
        DNS_PROVIDERS.put("Quad9", new String[]{"9.9.9.9", "149.112.112.112"});
        DNS_PROVIDERS.put("AdGuard Family", new String[]{"94.140.14.15", "94.140.15.16"});
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

        // EXCLUDE THIS APP: Ensure AdMob and internal calls bypass the VPN
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {}
        
        // IPv4 Routing
        builder.addAddress("10.1.1.1", 24);
        builder.addDnsServer("10.1.1.2");
        builder.addRoute("10.1.1.2", 32);
        
        // IPv6 Routing (Prevents IPv6 leaks from freezing connections)
        builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
        builder.addRoute("fd00:1:fd00:1:fd00:1:fd00:2", 128);
        builder.addDnsServer("fd00:1:fd00:1:fd00:1:fd00:2");

        // Allow non-DNS traffic to bypass the VPN seamlessly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.allowBypass();
        }

        String[] dnsServers = getDnsForProvider(provider);
        final String upstreamDns = dnsServers[0];

        try {
            vpnInterface = builder.establish();
            if (vpnInterface != null) {
                // Initialize thread pool for concurrent DNS queries
                executorService = Executors.newCachedThreadPool();
                startVpnThread(vpnInterface, upstreamDns);
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN Setup Failed", e);
        }
    }

    private void startVpnThread(final ParcelFileDescriptor iface, final String upstreamDns) {
        vpnThread = new Thread(() -> {
            try (FileInputStream in = new FileInputStream(iface.getFileDescriptor());
                 FileOutputStream out = new FileOutputStream(iface.getFileDescriptor())) {
                
                byte[] packet = new byte[32767];
                
                while (!Thread.interrupted()) {
                    int length = in.read(packet);
                    if (length > 0) {
                        // Clone packet data immediately so next read doesn't overwrite it
                        byte[] packetCopy = new byte[length];
                        System.arraycopy(packet, 0, packetCopy, 0, length);
                        
                        // Execute off the main loop to prevent queuing delays
                        executorService.execute(() -> handlePacket(packetCopy, length, out, upstreamDns));
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "VPN Thread stopped");
            }
        });
        vpnThread.start();
    }

    private void handlePacket(byte[] packet, int length, FileOutputStream out, String upstreamDns) {
        try {
            if (length < 28) return; 
            int ihl = (packet[0] & 0x0F) * 4;
            int protocol = packet[9] & 0xFF;
            if (protocol != 17) return; // UDP

            int destPort = ((packet[ihl + 2] & 0xFF) << 8) | (packet[ihl + 3] & 0xFF);
            if (destPort == 53) {
                totalQueries.incrementAndGet();
                String hostname = parseDnsHostname(packet, ihl + 8, length);
                
                if (hostname != null) {
                    boolean blocked = isBlocked(hostname);
                    logActivity(hostname, blocked);
                    if (blocked) {
                        blockedQueries.incrementAndGet();
                        return; // Drop the packet safely
                    }
                }

                byte[] dnsData = new byte[length - ihl - 8];
                System.arraycopy(packet, ihl + 8, dnsData, 0, dnsData.length);
                
                // Use a fresh socket per query so concurrent requests don't mix up responses
                try (DatagramSocket reqSocket = new DatagramSocket()) {
                    InetAddress upstreamAddr = InetAddress.getByName(upstreamDns);
                    DatagramPacket query = new DatagramPacket(dnsData, dnsData.length, upstreamAddr, 53);
                    reqSocket.send(query);
                    
                    byte[] responseBuffer = new byte[4096];
                    DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
                    reqSocket.setSoTimeout(4000); // 4 seconds is plenty, doesn't block other threads anymore
                    reqSocket.receive(response);
                    
                    byte[] returnPacket = new byte[ihl + 8 + response.getLength()];
                    System.arraycopy(packet, 0, returnPacket, 0, ihl + 8);
                    System.arraycopy(response.getData(), 0, returnPacket, ihl + 8, response.getLength());
                    
                    // Swap IPs
                    System.arraycopy(packet, 12, returnPacket, 16, 4);
                    System.arraycopy(packet, 16, returnPacket, 12, 4);
                    
                    // Swap Ports
                    returnPacket[ihl] = packet[ihl + 2];
                    returnPacket[ihl + 1] = packet[ihl + 3];
                    returnPacket[ihl + 2] = packet[ihl];
                    returnPacket[ihl + 3] = packet[ihl + 1];
                    
                    int udpLen = response.getLength() + 8;
                    returnPacket[ihl + 4] = (byte)(udpLen >> 8);
                    returnPacket[ihl + 5] = (byte)(udpLen & 0xFF);
                    returnPacket[ihl + 6] = 0; 
                    returnPacket[ihl + 7] = 0;

                    int ipLen = returnPacket.length;
                    returnPacket[2] = (byte)(ipLen >> 8);
                    returnPacket[3] = (byte)(ipLen & 0xFF);
                    
                    returnPacket[10] = 0;
                    returnPacket[11] = 0;
                    int checksum = calculateChecksum(returnPacket, ihl);
                    returnPacket[10] = (byte)(checksum >> 8);
                    returnPacket[11] = (byte)(checksum & 0xFF);
                    
                    // Synchronize writing back to the VPN interface
                    synchronized (out) {
                        out.write(returnPacket);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private int calculateChecksum(byte[] buf, int length) {
        int i = 0, sum = 0, len = length;
        while (len > 1) {
            sum += (((buf[i] << 8) & 0xFF00) | (buf[i + 1] & 0xFF));
            i += 2;
            len -= 2;
        }
        if (len > 0) sum += (buf[i] << 8 & 0xFF00);
        while ((sum >> 16) > 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    private String parseDnsHostname(byte[] data, int offset, int totalLength) {
        try {
            StringBuilder sb = new StringBuilder();
            int p = offset + 12;
            while (p < totalLength) {
                int len = data[p] & 0xFF;
                if (len == 0) break;
                if (sb.length() > 0) sb.append(".");
                for (int i = 0; i < len; i++) {
                    if (p + 1 + i < totalLength) sb.append((char) data[p + 1 + i]);
                }
                p += len + 1;
            }
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // SAFE & OPTIMIZED BLOCKLIST
    private boolean isBlocked(String hostname) {
        if (hostname == null || hostname.isEmpty()) return false;
        String lower = hostname.toLowerCase();
        
        // Exact matches or proper subdomains to prevent breaking random sites
        return lower.equals("doubleclick.net") || lower.endsWith(".doubleclick.net") ||
               lower.equals("google-analytics.com") || lower.endsWith(".google-analytics.com") ||
               lower.startsWith("ads.") || lower.startsWith("ad.") ||
               lower.contains(".ads.") || lower.contains("-ads.");
    }

    private void logActivity(String hostname, boolean blocked) {
        if (hostname == null || hostname.isEmpty()) return;
        String entry = (blocked ? "BLOCKED:" : "RESOLVED:") + hostname;
        
        synchronized (recentActivity) {
            if (!recentActivity.isEmpty() && recentActivity.get(0).equals(entry)) return;
            recentActivity.add(0, entry);
            if (recentActivity.size() > MAX_ACTIVITY_LOGS) recentActivity.remove(MAX_ACTIVITY_LOGS);
        }
    }

    private String[] getDnsForProvider(String provider) {
        String[] dns = DNS_PROVIDERS.get(provider);
        return (dns != null) ? dns : DNS_PROVIDERS.get("AdGuard DNS");
    }

    private void stopVpn() {
        stopForeground(true);
        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (IOException ignored) {}
            vpnInterface = null;
        }
    }

    @Override
    public void onDestroy() { stopVpn(); super.onDestroy(); }

    @Override
    public void onRevoke() { stopVpn(); super.onRevoke(); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "Pickko VPN Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }
}
