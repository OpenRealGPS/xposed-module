package com.github.openrealgps.lite;

import android.location.GnssStatus;
import android.location.GpsStatus;
import android.os.Build;
import android.os.Handler;
import android.util.ArraySet;
import de.robv.android.xposed.XposedHelpers;
import lombok.AllArgsConstructor;
import org.json.JSONArray;

import java.util.*;
import java.util.concurrent.Executor;

public class SatelliteUpdater {
    public static final int MAX_SVS = 64;
    private static volatile SatelliteUpdater instance;

    public static SatelliteUpdater getInstance() {
        if (instance == null) {
            synchronized (SatelliteUpdater.class) {
                if (instance == null) {
                    instance = new SatelliteUpdater();
                }
            }
        }
        return instance;
    }

    private final Set<GpsStatus.Listener> gpsStatusListeners = new ArraySet<>();
    private final List<GnssStatusCallbackWrapper> gnssStatusCallbacks = new LinkedList<>();
    private int svCount;
    private final int[] prn = new int[MAX_SVS];
    private final float[] snr = new float[MAX_SVS];
    private final float[] elv = new float[MAX_SVS];
    private final float[] azm = new float[MAX_SVS];
    private final float[] freq = new float[MAX_SVS];

    public void update(JSONArray data, Handler handler) throws Exception {
        if (data == null || data.length() <= 0) {
            return;
        }

        this.svCount = Math.min(data.length(), MAX_SVS);
        LocationUpdater.getInstance().setSatelliteCount(this.svCount);
        if (this.gpsStatusListeners.size() > 0 || this.gnssStatusCallbacks.size() > 0) {
            for (int i = 0; i < this.svCount; i++) {
                this.prn[i] = data.getJSONObject(i).getInt("prn");
                this.snr[i] = (float) data.getJSONObject(i).getDouble("snr");
                this.elv[i] = (float) data.getJSONObject(i).getDouble("elv");
                this.azm[i] = (float) data.getJSONObject(i).getDouble("azm");
            }

            if (this.gpsStatusListeners.size() > 0) {
                synchronized (this.gpsStatusListeners) {
                    for (GpsStatus.Listener listener : this.gpsStatusListeners) {
                        handler.post(() -> listener.onGpsStatusChanged(GpsStatus.GPS_EVENT_SATELLITE_STATUS));
                    }
                }
            }
            if (this.gnssStatusCallbacks.size() > 0) {
                synchronized (this.gnssStatusCallbacks) {
                    for (GnssStatusCallbackWrapper wrapper : this.gnssStatusCallbacks) {
                        Hooks.executeCallback(() -> {
                            try {
                                wrapper.callback.onSatelliteStatusChanged(getAsGnssStatus());
                            } catch (Exception ignored) {
                            }
                        }, wrapper.handler, wrapper.executor);
                    }
                }
            }
        }
    }

    public void addGpsStatusListener(GpsStatus.Listener listener, Handler handler) {
        handler.post(() -> {
            listener.onGpsStatusChanged(GpsStatus.GPS_EVENT_STARTED);
            listener.onGpsStatusChanged(GpsStatus.GPS_EVENT_FIRST_FIX);
        });
        synchronized (this.gpsStatusListeners) {
            this.gpsStatusListeners.add(listener);
        }
    }

    public void removeGpsStatusListener(GpsStatus.Listener listener) {
        synchronized (this.gpsStatusListeners) {
            this.gpsStatusListeners.remove(listener);
        }
    }

    public void addGnssStatusCallback(GnssStatus.Callback callback, Handler handler, Executor executor) {
        Hooks.executeCallback(() -> {
            callback.onStarted();
            callback.onFirstFix(5000);
        }, handler, executor);
        synchronized (this.gnssStatusCallbacks) {
            this.gnssStatusCallbacks.add(new GnssStatusCallbackWrapper(callback, handler, executor));
        }
    }

    public void removeGnssStatusCallback(GnssStatus.Callback callback) {
        synchronized (this.gnssStatusCallbacks) {
            for (Iterator<GnssStatusCallbackWrapper> itr = this.gnssStatusCallbacks.iterator(); itr.hasNext(); ) {
                GnssStatusCallbackWrapper wrapper = itr.next();
                if (Objects.equals(wrapper.callback, callback)) {
                    itr.remove();
                    break;
                }
            }
        }
    }

    public GpsStatus getAsGpsStatus(GpsStatus status) throws Exception {
        if (status == null) {
            status = (GpsStatus) XposedHelpers.findConstructorExact(GpsStatus.class).newInstance();
        }
        XposedHelpers.callMethod(status, "setStatus", svCount, getSvidWithFlags(), snr, elv, azm);
        XposedHelpers.callMethod(status, "setTimeToFirstFix", 5000);
        return status;
    }

    private int[] getSvidWithFlags() {
        int SVID_SHIFT = XposedHelpers.getStaticIntField(GnssStatus.class, "SVID_SHIFT_WIDTH");
        int CONSTELLATION_SHIFT = XposedHelpers.getStaticIntField(GnssStatus.class, "CONSTELLATION_TYPE_SHIFT_WIDTH");
        int[] svidWithFlags = new int[prn.length];
        for (int i = 0; i < prn.length; i++) {
            if (prn[i] >= 1 && prn[i] <= 32) { // GPS
                svidWithFlags[i] = prn[i];
                svidWithFlags[i] = (svidWithFlags[i] << SVID_SHIFT) + (GnssStatus.CONSTELLATION_GPS << CONSTELLATION_SHIFT) + 7;
            } else if (prn[i] >= 65 && prn[i] <= 96) { // GLONASS
                svidWithFlags[i] = prn[i] - 64;
                svidWithFlags[i] = (svidWithFlags[i] << SVID_SHIFT) + (GnssStatus.CONSTELLATION_GLONASS << CONSTELLATION_SHIFT) + 7;
            } else if (prn[i] >= 193 && prn[i] <= 200) { // QZSS
                svidWithFlags[i] = prn[i];
                svidWithFlags[i] = (svidWithFlags[i] << SVID_SHIFT) + (GnssStatus.CONSTELLATION_QZSS << CONSTELLATION_SHIFT) + 7;
            } else if (prn[i] >= 201 && prn[i] <= 235) { // BeiDou
                svidWithFlags[i] = prn[i] - 200;
                svidWithFlags[i] = (svidWithFlags[i] << SVID_SHIFT) + (GnssStatus.CONSTELLATION_BEIDOU << CONSTELLATION_SHIFT) + 7;
            } else if (prn[i] >= 301 && prn[i] <= 336) { // Galileo
                svidWithFlags[i] = prn[i] - 300;
                svidWithFlags[i] = (svidWithFlags[i] << SVID_SHIFT) + (GnssStatus.CONSTELLATION_GALILEO << CONSTELLATION_SHIFT) + 7;
            } else {
                svidWithFlags[i] = prn[i];
                svidWithFlags[i] = (svidWithFlags[i] << SVID_SHIFT) + (GnssStatus.CONSTELLATION_UNKNOWN << CONSTELLATION_SHIFT) + 7;
            }
        }
        return svidWithFlags;
    }

    private GnssStatus getAsGnssStatus() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return (GnssStatus) XposedHelpers.findConstructorExact(GnssStatus.class, int.class, int[].class, float[].class, float[].class, float[].class, float[].class, float[].class)
                    .newInstance(svCount, getSvidWithFlags(), snr, elv, azm, freq, snr);
        } else {
            return (GnssStatus) XposedHelpers.findConstructorExact(GnssStatus.class, int.class, int[].class, float[].class, float[].class, float[].class, float[].class)
                    .newInstance(svCount, getSvidWithFlags(), snr, elv, azm, freq);
        }
    }

    @AllArgsConstructor
    private static class GnssStatusCallbackWrapper {
        private final GnssStatus.Callback callback;

        private final Handler handler;

        private final Executor executor;
    }
}
