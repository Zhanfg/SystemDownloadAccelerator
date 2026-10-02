package dev.axymorrsen.systemdownloadaccelerator;

import android.app.Application;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Owns the single libxposed service listener for the module app process. */
public final class ModuleApp extends Application
        implements XposedServiceHelper.OnServiceListener {
    private static final String TAG = "SDAApp";
    private static final CopyOnWriteArrayList<Runnable> LISTENERS =
            new CopyOnWriteArrayList<>();

    private static volatile XposedService service;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable t) {
            Log.e(TAG, "Unable to register XposedService listener", t);
        }
    }

    @Override
    public void onServiceBind(XposedService boundService) {
        service = boundService;
        notifyListeners();
    }

    @Override
    public void onServiceDied(XposedService deadService) {
        if (service == deadService) {
            service = null;
        }
        notifyListeners();
    }

    static XposedService service() {
        return service;
    }

    static void addListener(Runnable listener) {
        LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
