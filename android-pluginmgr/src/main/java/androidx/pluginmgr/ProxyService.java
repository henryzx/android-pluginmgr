/*
 * Copyright (C) 2015 Baidu, Inc. All Rights Reserved.
 */
package androidx.pluginmgr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Pair;
import androidx.pluginmgr.reflect.Reflect;

/**
 * Created by gerald on 4/2/15.
 */
@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class ProxyService extends Service {

    private LinkedHashMap<String, Service> runningServices = new LinkedHashMap<String, Service>();

    @Override
    public IBinder onBind(Intent intent) {
        Pair<Service, Intent> serviceIntentPair = getServiceInstanceFromIntent(intent);
        if (serviceIntentPair != null) {
            Service service = serviceIntentPair.first;
            Intent oldIntent = serviceIntentPair.second;

            ensureCreated(service);
            return service.onBind(oldIntent);
        } else {
            return null;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Pair<Service, Intent> serviceIntentPair = getServiceInstanceFromIntent(intent);
        if (serviceIntentPair != null) {
            Service service = serviceIntentPair.first;
            boolean unbindResult = service.onUnbind(intent);
            ensureDestroy(service);
            return unbindResult;
        } else {
            return super.onUnbind(intent);
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        Pair<Service, Intent> serviceIntentPair = getServiceInstanceFromIntent(intent);
        if (serviceIntentPair != null) {
            Service service = serviceIntentPair.first;
            Intent oldIntent = serviceIntentPair.second;

            ensureCreated(service);
            service.onStart(oldIntent, startId);
        }
    }

    private Pair<Service, Intent> getServiceInstanceFromIntent(Intent intent) {
        if (intent.hasExtra("oldIntent")) {
            Intent oldIntent = intent.getParcelableExtra("oldIntent");
            ComponentName componentName = oldIntent.getComponent();

            Service delegateService = runningServices.get(componentName.flattenToString());
            if (delegateService == null) {
                // 实例化插件 service，并进入创建流程
                PlugInfo plugin = PluginManager.getInstance().getPluginByPackageName(componentName.getPackageName());
                ServiceInfo serviceInfo = plugin.findServiceByClassName(componentName.getClassName());
                if (serviceInfo != null) {

                    try {
                        delegateService =
                                (Service) plugin.getClassLoader().loadClass(serviceInfo.name).newInstance();

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    // TODO ZX inject contexts
                    Reflect serviceRef = Reflect.on(delegateService);
                    Reflect thisRef = Reflect.on(this);
                    serviceRef.call("attach", this, thisRef.get("mThread"), thisRef.get("mClassName"),
                                           thisRef.get("mToken"),
                                           plugin.getApplication() == null ? getApplication() : plugin.getApplication(),
                                           thisRef.get("mActivityManager"));

                    runningServices.put(componentName.flattenToString(), delegateService);
                }
            }

            return new Pair(delegateService, oldIntent);
        }
        return null;
    }

    private Set<Service> createdServices = Collections.newSetFromMap(new WeakHashMap<Service, Boolean>());

    private void ensureCreated(Service delegateService) {
        if (delegateService == null) {
            return;
        }
        if (!createdServices.contains(delegateService)) {
            // 还未调用create
            delegateService.onCreate();
            createdServices.add(delegateService);
        }
    }

    private void ensureDestroy(Service delegateService) {
        if (delegateService == null) {
            return;
        }
        if (createdServices.remove(delegateService)) {
            // 服务成功被去除，调用onDestroy
            delegateService.onDestroy();

            // 从 runningServices 中删除Service
            String pendingDeleteKey = null;
            for (Map.Entry<String, Service> stringServiceEntry : runningServices.entrySet()) {
                if (stringServiceEntry.getValue() == delegateService) {
                    pendingDeleteKey = stringServiceEntry.getKey();
                }
            }

            runningServices.remove(pendingDeleteKey);
        }
    }
}
