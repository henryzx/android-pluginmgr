/*
 *    Copyright 2015 henryzx <henryzx@hotmail.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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
 * Created by henryzx on 4/2/15.
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
//            ensureDestroy(service);
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

            if (checkStopService(intent, service)) {
                return;
            }

            ensureCreated(service);
//            service.onStart(oldIntent, startId);
            service.onStartCommand(oldIntent, 0, startId);
        }
    }

    /**
     * 看看是不是stopService
     *
     * @param intent
     * @param service
     * @return
     */
    boolean checkStopService(Intent intent, Service service) {
        Intent oldIntent = intent.getParcelableExtra("oldIntent");
        if (oldIntent != null && oldIntent.hasExtra("stopService")
                && oldIntent.getBooleanExtra("stopService", false)) {
            if (service != null) {
                ensureDestroy(service);
                return true;
            }
        }

        return false;
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

    /**
     * 正在运行的服务记录
     */
    public static class RunningServiceRecord{
        public Service service;
        public ServiceInfo serviceInfo;
        public ComponentName name;
        public Intent startIntent;

        public int startCount = 0; //记录被启动的次数
    }
}
