package com.github.openrealgps.lite;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.*;
import android.net.LocalServerSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
public class Hooks implements IXposedHookLoadPackage {
    private static LocalServerSocket serverLock;
    private static final AtomicBoolean hookApplied = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            serverLock = new LocalServerSocket("OpenRealGPS_Lite_ServerLock");
            try {
                int port = LocalServer.HTTP_PORT;
                new LocalServer(port).start();
                XposedBridge.log("Started local server on port " + port + " in process " + lpparam.processName);
            } catch (Exception e) {
                XposedBridge.log("Local server failed to start in process " + lpparam.processName);
                XposedBridge.log(e);
            }
        } catch (Exception ignored) {
        }

        XposedBridge.hookAllConstructors(LocationManager.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (hookApplied.compareAndSet(false, true)) {
                    XposedBridge.log("Applying hooks for " + lpparam.packageName);
                    try {
                        Handler handler = new Handler(Looper.getMainLooper());
                        new Thread(new LocalBroadcastReceiver(handler)).start();
                        hookMethods(handler);
                    } catch (Exception e) {
                        XposedBridge.log(String.valueOf(e));
                    }
                }
            }
        });
    }

    public static void executeCallback(Runnable callback, Handler handler, Executor executor) {
        if (handler != null) {
            handler.post(callback);
        } else if (executor != null) {
            executor.execute(callback);
        } else {
            callback.run();
        }
    }

    private static void hookMethods(Handler defaultHandler) {
        Map<String, XC_MethodHook> hooks = new ArrayMap<>(39);
        XC_MethodHook returnNull = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
            }
        };
        XC_MethodHook returnTrue = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(true);
            }
        };
        XC_MethodHook returnFalse = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(false);
            }
        };
        XC_MethodHook returnZero = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(0);
            }
        };
        hooks.put("addGeofence", returnNull);
        hooks.put("addGpsStatusListener", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                SatelliteUpdater.getInstance().addGpsStatusListener((GpsStatus.Listener) param.args[0], defaultHandler);
                param.setResult(true);
            }
        });
        hooks.put("addNmeaListener", returnTrue); // TODO NMEA may be supported in future versions
        hooks.put("addProximityAlert", returnNull);
        hooks.put("flushGnssBatch", returnNull);
        XC_MethodHook addGpsProvider = new XC_MethodHook() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                List results = (List) param.getResult();
                if (!results.contains(LocationManager.GPS_PROVIDER)) {
                    results.add(LocationManager.GPS_PROVIDER);
                    param.setResult(results);
                }
            }
        };
        hooks.put("getAllProviders", addGpsProvider);
        hooks.put("getBestProvider", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(LocationManager.GPS_PROVIDER);
            }
        });
        hooks.put("getCurrentLocation", new XC_MethodHook() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String provider;
                if (param.args[0] instanceof String) {
                    provider = (String) param.args[0];
                } else {
                    provider = (String) XposedHelpers.getObjectField(param.args[0], "mProvider");
                }
                ((Consumer) param.args[3]).accept(LocationUpdater.getInstance().getAsLocation(provider));
                param.setResult(null);
            }
        });
        hooks.put("getGnssBatchSize", returnZero);
        hooks.put("getGnssCapabilities", new XC_MethodHook() {
            @TargetApi(Build.VERSION_CODES.R)
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                param.setResult(XposedHelpers.findConstructorExact(GnssCapabilities.class, long.class).newInstance(0L));
            }
        });
        hooks.put("getGnssHardwareModelName", returnNull);
        hooks.put("getGnssYearOfHardware", returnZero);
        hooks.put("getGpsStatus", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Exception {
                param.setResult(SatelliteUpdater.getInstance().getAsGpsStatus((GpsStatus) param.args[0]));
            }
        });
        hooks.put("getLastKnownLocation", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(LocationUpdater.getInstance().getAsLocation((String) param.args[0]));
            }
        });
        hooks.put("getLastLocation", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(LocationUpdater.getInstance().getAsLocation(LocationManager.GPS_PROVIDER));
            }
        });
        hooks.put("getProvider", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Exception {
                if (!param.hasThrowable() && param.getResult() == null && LocationManager.GPS_PROVIDER.equals(param.args[0])) {
                    Class<?> propClass = XposedHelpers.findClass("com.android.internal.location.ProviderProperties", null);
                    Object props = XposedHelpers.findConstructorExact(propClass, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, int.class, int.class)
                            .newInstance(false, true, false, false, true, true, true, Criteria.POWER_MEDIUM, Criteria.ACCURACY_FINE);
                    param.setResult(XposedHelpers.findConstructorExact(LocationProvider.class, String.class, propClass)
                            .newInstance(LocationManager.GPS_PROVIDER, props));
                }
            }
        });
        hooks.put("getProviderPackages", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (LocationManager.GPS_PROVIDER.equals(param.args[0])) {
                    param.setResult(Collections.emptyList());
                }
            }
        });
        hooks.put("getProviders", addGpsProvider);
        hooks.put("isProviderEnabled", returnTrue);
        hooks.put("isProviderEnabledForUser", returnTrue);
        hooks.put("registerAntennaInfoListener", returnFalse);
        hooks.put("registerGnssBatchedLocationCallback", returnFalse);
        hooks.put("registerGnssMeasurementsCallback", returnFalse);
        hooks.put("registerGnssNavigationMessageCallback", returnFalse);
        hooks.put("registerGnssStatusCallback", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                GnssStatus.Callback callback;
                Handler handler = null;
                Executor executor = null;
                if (param.args[0] instanceof GnssStatus.Callback) {
                    callback = (GnssStatus.Callback) param.args[0];
                    if (param.args.length >= 2) {
                        handler = (Handler) param.args[1];
                    }
                } else {
                    executor = (Executor) param.args[0];
                    callback = (GnssStatus.Callback) param.args[1];
                }
                if (handler == null && executor == null) {
                    handler = new Handler();
                }
                SatelliteUpdater.getInstance().addGnssStatusCallback(callback, handler, executor);
                param.setResult(true);
            }
        });
        hooks.put("removeAllGeofences", returnNull);
        hooks.put("removeGeofence", returnNull);
        hooks.put("removeGpsStatusListener", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                SatelliteUpdater.getInstance().removeGpsStatusListener((GpsStatus.Listener) param.args[0]);
                param.setResult(null);
            }
        });
        hooks.put("removeNmeaListener", returnNull);
        hooks.put("removeProximityAlert", returnNull);
        hooks.put("removeUpdates", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] instanceof LocationListener) {
                    LocationUpdater.getInstance().removeLocationListener((LocationListener) param.args[0]);
                } else if (param.args[0] instanceof PendingIntent) {
                    LocationUpdater.getInstance().removePendingIntent((PendingIntent) param.args[0]);
                }
                param.setResult(null);
            }
        });
        hooks.put("requestLocationUpdates", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String provider = LocationManager.GPS_PROVIDER;
                if (param.args[0] instanceof String) {
                    provider = (String) param.args[0];
                }
                LocationListener locationListener = null;
                PendingIntent pendingIntent = null;
                Executor executor = null;
                Looper looper = null;
                for (int i = 1; i < param.args.length; i++) {
                    if (param.args[i] instanceof LocationListener) {
                        locationListener = (LocationListener) param.args[i];
                    } else if (param.args[i] instanceof Looper) {
                        looper = (Looper) param.args[i];
                    } else if (param.args[i] instanceof Executor) {
                        executor = (Executor) param.args[i];
                    } else if (param.args[i] instanceof PendingIntent) {
                        pendingIntent = (PendingIntent) param.args[i];
                    }
                }
                if (locationListener != null) {
                    Handler handler = null;
                    if (executor == null) {
                        handler = (looper == null) ? new Handler() : new Handler(looper);
                    }
                    LocationUpdater.getInstance().addLocationListener(provider, locationListener, handler, executor);
                } else if (pendingIntent != null) {
                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    LocationUpdater.getInstance().addPendingIntent(provider, pendingIntent, context);
                }
                param.setResult(null);
            }
        });
        hooks.put("requestSingleUpdate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String provider = LocationManager.GPS_PROVIDER;
                if (param.args[0] instanceof String) {
                    provider = (String) param.args[0];
                }
                if (param.args[1] instanceof LocationListener) {
                    Looper looper = null;
                    if (param.args.length >= 3) {
                        looper = (Looper) param.args[2];
                    }
                    Handler handler = looper == null ? new Handler() : new Handler(looper);
                    String finalProvider = provider;
                    executeCallback(() -> ((LocationListener) param.args[1]).onLocationChanged(LocationUpdater.getInstance().getAsLocation(finalProvider)), handler, null);
                } else if (param.args[1] instanceof PendingIntent) {
                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    try {
                        ((PendingIntent) param.args[1]).send(context, 0, new Intent().putExtra(LocationManager.KEY_LOCATION_CHANGED, LocationUpdater.getInstance().getAsLocation(provider)));
                    } catch (Exception ignored) {
                    }
                }
                param.setResult(null);
            }
        });
        hooks.put("unregisterAntennaInfoListener", returnNull);
        hooks.put("unregisterGnssBatchedLocationCallback", returnNull);
        hooks.put("unregisterGnssMeasurementsCallback", returnNull);
        hooks.put("unregisterGnssNavigationMessageCallback", returnNull);
        hooks.put("unregisterGnssStatusCallback", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                SatelliteUpdater.getInstance().removeGnssStatusCallback((GnssStatus.Callback) param.args[0]);
                param.setResult(null);
            }
        });

        for (Method method : LocationManager.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                XC_MethodHook hook = hooks.get(method.getName());
                if (hook != null) {
                    XposedBridge.hookMethod(method, hook);
                }
            }
        }
    }
}
