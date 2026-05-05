package com.pickko.adguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS_NAME = "PickkoPrefs";
    private static final String KEY_IS_PROTECTED = "isProtected";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_MODE = "mode";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean isProtected = prefs.getBoolean(KEY_IS_PROTECTED, false);
            String provider = prefs.getString(KEY_PROVIDER, "AdGuard DNS");
            String mode = prefs.getString(KEY_MODE, "Balanced");

            if (isProtected) {
                Intent vpnIntent = new Intent(context, LocalVpnService.class);
                vpnIntent.setAction(LocalVpnService.ACTION_START);
                vpnIntent.putExtra(LocalVpnService.EXTRA_PROVIDER, provider);
                vpnIntent.putExtra(LocalVpnService.EXTRA_MODE, mode);
                
                // Required fix: Safe boot startup for modern Android devices
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent);
                } else {
                    context.startService(vpnIntent);
                }
            }
        }
    }
}
