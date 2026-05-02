package com.pickko.adguard;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import androidx.annotation.NonNull;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import androidx.activity.result.ActivityResult;

@CapacitorPlugin(name = "VpnPlugin")
public class VpnPlugin extends Plugin {

    private AdView adView;

    private boolean isProtected = false;
    private String currentProvider = "AdGuard DNS";
    private String currentMode = "Balanced";
    private boolean isPremium = false;
    private boolean onboardingComplete = false;

    private static final String PREFS_NAME = "PickkoPrefs";
    private static final String KEY_IS_PROTECTED = "isProtected";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_MODE = "mode";
    private static final String KEY_IS_PREMIUM = "isPremium";
    private static final String KEY_ONBOARDING_COMPLETE = "onboardingComplete";

    @Override
    public void load() {
        super.load();
        MobileAds.initialize(getContext(), initializationStatus -> {});

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.isProtected = prefs.getBoolean(KEY_IS_PROTECTED, false);
        this.currentProvider = prefs.getString(KEY_PROVIDER, "AdGuard DNS");
        this.currentMode = prefs.getString(KEY_MODE, "Balanced");
        this.isPremium = prefs.getBoolean(KEY_IS_PREMIUM, false);
        this.onboardingComplete = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false);
    }

    private void saveState() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
             .putBoolean(KEY_IS_PROTECTED, this.isProtected)
             .putString(KEY_PROVIDER, this.currentProvider)
             .putString(KEY_MODE, this.currentMode)
             .putBoolean(KEY_IS_PREMIUM, this.isPremium)
             .putBoolean(KEY_ONBOARDING_COMPLETE, this.onboardingComplete)
             .apply();
    }

    @PluginMethod
    public void enableShield(PluginCall call) {
        this.currentProvider = call.getString("provider", this.currentProvider);
        this.currentMode = call.getString("mode", this.currentMode);
        saveState();
        
        Intent intent = VpnService.prepare(getContext());
        if (intent != null) {
            startActivityForResult(call, intent, "vpnAuthResult");
        } else {
            startVpnService();
            this.isProtected = true;
            saveState();
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void disableShield(PluginCall call) {
        this.isProtected = false;
        saveState();
        Intent intent = new Intent(getContext(), LocalVpnService.class);
        intent.setAction(LocalVpnService.ACTION_STOP);
        getContext().startService(intent);
        if (call != null) {
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void updateConfig(PluginCall call) {
        this.currentProvider = call.getString("provider", this.currentProvider);
        this.currentMode = call.getString("mode", this.currentMode);
        saveState();
        if (isProtected) startVpnService();
        call.resolve();
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("isProtected", this.isProtected);
        ret.put("currentProvider", this.currentProvider);
        ret.put("currentMode", this.currentMode);
        ret.put("isPremium", this.isPremium);
        ret.put("onboardingComplete", this.onboardingComplete);
        
        JSObject stats = new JSObject();
        long queries = LocalVpnService.totalQueries.get();
        long blocked = LocalVpnService.blockedQueries.get();
        stats.put("totalQueries", String.valueOf(queries));
        stats.put("adsBlocked", String.valueOf(blocked));
        stats.put("trackers", String.valueOf(blocked / 2));
        stats.put("dataSaved", String.format(Locale.US, "%.1f MB", blocked * 0.05f));
        ret.put("stats", stats);

        JSArray logs = new JSArray();
        List<String> recentLogs = LocalVpnService.getRecentActivity();
        for (String activity : recentLogs) {
            logs.put(activity);
        }
        ret.put("recentActivity", logs);

        call.resolve(ret);
    }

    @PluginMethod
    public void setOnboardingComplete(PluginCall call) {
        this.onboardingComplete = true;
        saveState();
        call.resolve();
    }

    @PluginMethod
    public void setPremium(PluginCall call) {
        Boolean premium = call.getBoolean("isPremium");
        this.isPremium = (premium != null) ? premium : true;
        saveState();
        call.resolve();
    }

    @PluginMethod
    public void showBanner(PluginCall call) {
        if (isPremium) {
            call.reject("Premium users don't see ads");
            return;
        }

        getActivity().runOnUiThread(() -> {
            if (adView != null) {
                adView.setVisibility(View.VISIBLE);
                call.resolve();
                return;
            }

            adView = new AdView(getContext());
            adView.setAdSize(AdSize.BANNER);
            adView.setAdUnitId("ca-app-pub-6496422443237011/1129625748");

            adView.setAdListener(new AdListener() {
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError adError) {
                    android.util.Log.e("AdMob", "Ad failed to load: " + adError.getMessage());
                }

                @Override
                public void onAdLoaded() {
                    android.util.Log.i("AdMob", "Ad loaded successfully");
                }
            });

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = Gravity.BOTTOM;
            
            // Add margin to not overlap navigation (approx 80dp)
            float density = getContext().getResources().getDisplayMetrics().density;
            params.bottomMargin = (int) (80 * density);

            getActivity().addContentView(adView, params);

            AdRequest adRequest = new AdRequest.Builder().build();
            adView.loadAd(adRequest);
            call.resolve();
        });
    }

    @PluginMethod
    public void hideBanner(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (adView != null) {
                adView.setVisibility(View.GONE);
            }
            if (call != null) call.resolve();
        });
    }

    @PluginMethod
    public void resetConfig(PluginCall call) {
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        load();
        disableShield(null);
        call.resolve();
    }

    @PluginMethod
    public void testLatency(PluginCall call) {
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                boolean r = InetAddress.getByName("8.8.8.8").isReachable(1500);
                JSObject ret = new JSObject();
                ret.put("latency", r ? (System.currentTimeMillis() - start) + "ms" : "timeout");
                call.resolve(ret);
            } catch (Exception e) { call.reject(e.getMessage()); }
        }).start();
    }

    @ActivityCallback
    private void vpnAuthResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK) {
            startVpnService();
            this.isProtected = true;
            saveState();
            call.resolve();
        } else {
            call.reject("Denied");
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(getContext(), LocalVpnService.class);
        intent.setAction(LocalVpnService.ACTION_START);
        intent.putExtra(LocalVpnService.EXTRA_PROVIDER, currentProvider);
        intent.putExtra(LocalVpnService.EXTRA_MODE, currentMode);
        getContext().startService(intent);
    }
}
