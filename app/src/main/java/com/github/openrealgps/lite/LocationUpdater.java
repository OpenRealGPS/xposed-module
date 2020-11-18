package com.github.openrealgps.lite;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import lombok.AllArgsConstructor;
import lombok.Setter;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

public class LocationUpdater {
    private static volatile LocationUpdater instance;

    public static LocationUpdater getInstance() {
        if (instance == null) {
            synchronized (LocationUpdater.class) {
                if (instance == null) {
                    instance = new LocationUpdater();
                }
            }
        }
        return instance;
    }

    @Setter
    private int satelliteCount;
    private double latitude, longitude, altitude;
    private float speed, bearing, accuracy;
    private long timestamp;

    private final List<LocationListenerWrapper> locationListenerWrappers = new LinkedList<>();
    private final List<PendingIntentWrapper> pendingIntentWrappers = new LinkedList<>();

    private LocationUpdater() {
    }

    public void update(JSONObject data) throws Exception {
        if (data == null) {
            return;
        }

        if (this.locationListenerWrappers.size() > 0 || this.pendingIntentWrappers.size() > 0) {
            this.latitude = data.getDouble("latitude");
            this.longitude = data.getDouble("longitude");
            this.altitude = data.getDouble("altitude");
            this.speed = (float) data.getDouble("speed");
            this.bearing = (float) data.getDouble("bearing");
            this.accuracy = (float) data.getDouble("accuracy");
            this.timestamp = data.getLong("timestamp");

            if (this.locationListenerWrappers.size() > 0) {
                synchronized (this.locationListenerWrappers) {
                    for (LocationListenerWrapper wrapper : this.locationListenerWrappers) {
                        Hooks.executeCallback(() -> wrapper.listener.onLocationChanged(getAsLocation(wrapper.provider)), wrapper.handler, wrapper.executor);
                    }
                }
            }
            if (this.pendingIntentWrappers.size() > 0) {
                synchronized (this.pendingIntentWrappers) {
                    for (PendingIntentWrapper wrapper : this.pendingIntentWrappers) {
                        try {
                            wrapper.pendingIntent.send(wrapper.context, 0, new Intent().putExtra(LocationManager.KEY_LOCATION_CHANGED, getAsLocation(wrapper.provider)));
                        } catch (Exception e) {
                            removePendingIntent(wrapper.pendingIntent);
                        }
                    }
                }
            }
        }
    }

    public Location getAsLocation(String provider) {
        if (this.timestamp <= 0L) {
            return null;
        }
        Location location = new Location(provider);
        location.setLatitude(this.latitude);
        location.setLongitude(this.longitude);
        location.setAltitude(this.altitude);
        location.setSpeed(this.speed);
        location.setBearing(this.bearing);
        location.setAccuracy(this.accuracy);
        location.setTime(this.timestamp);
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (this.satelliteCount > 0) {
            Bundle extras = new Bundle();
            extras.putInt("satellites", this.satelliteCount);
            location.setExtras(extras);
        }
        return location;
    }

    public void addLocationListener(String provider, LocationListener listener, Handler handler, Executor executor) {
        Hooks.executeCallback(() -> listener.onProviderEnabled(provider), handler, executor);
        synchronized (this.locationListenerWrappers) {
            this.locationListenerWrappers.add(new LocationListenerWrapper(provider, listener, handler, executor));
        }
    }

    public void removeLocationListener(LocationListener listener) {
        synchronized (this.locationListenerWrappers) {
            for (Iterator<LocationListenerWrapper> itr = this.locationListenerWrappers.iterator(); itr.hasNext(); ) {
                LocationListenerWrapper wrapper = itr.next();
                if (Objects.equals(wrapper.listener, listener)) {
                    itr.remove();
                    break;
                }
            }
        }
    }

    public void addPendingIntent(String provider, PendingIntent pendingIntent, Context context) {
        try {
            pendingIntent.send(context, 0, new Intent().putExtra(LocationManager.KEY_PROVIDER_ENABLED, true));
        } catch (Exception e) {
            return;
        }

        synchronized (this.pendingIntentWrappers) {
            this.pendingIntentWrappers.add(new PendingIntentWrapper(provider, pendingIntent, context));
        }
    }

    public void removePendingIntent(PendingIntent pendingIntent) {
        synchronized (this.pendingIntentWrappers) {
            for (Iterator<PendingIntentWrapper> itr = this.pendingIntentWrappers.iterator(); itr.hasNext(); ) {
                PendingIntentWrapper wrapper = itr.next();
                if (Objects.equals(wrapper.pendingIntent, pendingIntent)) {
                    itr.remove();
                    break;
                }
            }
        }
    }

    @AllArgsConstructor
    private static class LocationListenerWrapper {
        private final String provider;

        private final LocationListener listener;

        private final Handler handler;

        private final Executor executor;
    }

    @AllArgsConstructor
    private static class PendingIntentWrapper {
        private final String provider;

        private final PendingIntent pendingIntent;

        private final Context context;
    }
}
