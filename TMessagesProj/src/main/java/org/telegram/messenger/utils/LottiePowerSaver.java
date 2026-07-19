package org.telegram.messenger.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * LottiePowerSaver
 *
 * rlottie decodes every frame of every animated sticker/emoji entirely on the CPU (no GPU
 * path), and dozens of them can be on screen at once. That's the dominant CPU/battery cost
 * in this app. This class tracks the system's power-save state (Android's own "Battery
 * Saver") and lets RLottieDrawable / BitmapsCache throttle themselves down while it's on:
 *  - RLottieDrawable: floors the frame-advance interval to a lower effective FPS.
 *  - BitmapsCache: shrinks the shared frame-cache-generation thread pool.
 * Both relax back to normal automatically once power save is turned off.
 *
 * Usage: LottiePowerSaver.init(applicationContext) once at app startup.
 */
public class LottiePowerSaver {

    // ponytail: coarse global flag is enough here, no per-animation/per-account granularity needed
    public static volatile boolean active;

    private static boolean inited;

    public static void init(Context context) {
        if (inited || context == null) {
            return;
        }
        inited = true;
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            active = powerManager != null && powerManager.isPowerSaveMode();
            IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            ApplicationLoader.registerReceiverNotExported(context, new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                        active = pm != null && pm.isPowerSaveMode();
                        BitmapsCache.onPowerSaveChanged(active);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }, filter);
            BitmapsCache.onPowerSaveChanged(active);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
