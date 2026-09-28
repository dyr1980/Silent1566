package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.proxy.MultiThreadProxy;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncer;
import com.fongmi.android.tv.player.PlaybackMemoryMonitor;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.player.mpv.PlaybackRecoveryMonitor;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.setting.AppBranding;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.DanmakuSearchListFocusFixer;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PreviousProcessExitLogger;
import com.fongmi.android.tv.utils.WebViewDataDirectoryGuard;
import com.fongmi.hook.Hook;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private final Runnable backgroundServicesStarter = this::startBackgroundServicesNow;

    private volatile Activity activity;
    private Hook hook;

    private Resources resources;
    private int resourcesLanguage = Integer.MIN_VALUE;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (PlaybackRecoveryMonitor.isRecoveryProcess(base)) return;
        WebViewDataDirectoryGuard.clearStaleLock(base);
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (PlaybackRecoveryMonitor.isRecoveryProcess(this)) return;
        PlaybackMemoryMonitor.process().initialize(this);
        PlaybackSystemConditionMonitor.process().initialize(this);
        Setting.applyLanguage();
        AppBranding.applyLauncherIcon(this);
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) {
            PlaybackRecoveryMonitor.logPreviousResult(this);
            Setting.logDebugEnvironment("restore");
            PreviousProcessExitLogger.log(this);
        }
        Notify.createChannel();
        ProxySetting.apply();
        registerActivityLifecycleCallbacks(this);
        registerContentHandlers();
        resumeBackgroundServices();
    }

    private void registerContentHandlers() {
        com.fongmi.android.tv.content.ContentDispatcher.registerHandler(new com.fongmi.android.tv.content.CatActionContentHandler());
        com.fongmi.android.tv.content.ContentDispatcher.registerHandler(new com.fongmi.android.tv.content.GameContentHandler());
        com.fongmi.android.tv.content.ContentDispatcher.registerHandler(new com.fongmi.android.tv.content.AudioContentHandler());
        com.fongmi.android.tv.content.ContentDispatcher.registerHandler(new com.fongmi.android.tv.content.ReaderContentHandler());
        registerReaderFallback();
    }

    private void registerReaderFallback() {
        Product.registerReaderFallback();
    }

    @Override
    public void onTrimMemory(int level) {
        if (!PlaybackRecoveryMonitor.isRecoveryProcess(this)) PlaybackMemoryMonitor.process().onTrimMemory(level);
        super.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        if (!PlaybackRecoveryMonitor.isRecoveryProcess(this)) PlaybackMemoryMonitor.process().onLowMemory();
        super.onLowMemory();
    }

    private void startBackgroundServicesNow() {
        SpiderDebug.log("startup", "background services start cost=%sms", System.currentTimeMillis() - time);
        Server.get().start();
        startMultiThreadProxy();
        PlaybackRemoteSyncer.start();
        RemoteAgent.get().start();
        NsdDeviceDiscovery.register();
        com.fongmi.android.tv.lab.LabAutoStart.start(this);
        SpiderDebug.log("startup", "background services ready cost=%sms", System.currentTimeMillis() - time);
    }

    private void startMultiThreadProxy() {
        try {
            var snapshot = MultiThreadProxy.applyStored();
            SpiderDebug.log("proxy",
                    "multi-thread proxy enabled=%s ready=%s port=%s revision=%s",
                    snapshot.config().enabled(),
                    snapshot.ready(),
                    snapshot.actualPort(),
                    snapshot.configRevision());
        } catch (Exception e) {
            SpiderDebug.log("proxy", "multi-thread proxy start failed error=%s", e.getMessage());
        }
    }

    public static void resumeBackgroundServices() {
        removeCallbacks(get().backgroundServicesStarter);
        DanmakuSearchListFocusFixer.start();
        post(get().backgroundServicesStarter, 1200);
    }

    public static void stopBackgroundServices() {
        removeCallbacks(get().backgroundServicesStarter);
        DanmakuSearchListFocusFixer.stop();
        MultiThreadProxy.stop();
        PlaybackRemoteSyncer.stop();
        RemoteAgent.get().stop();
        NsdDeviceDiscovery.unregister();
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Resources getResources() {
        int language = Setting.getLanguage();
        if (resources == null || resourcesLanguage != language) {
            Resources resources = super.getResources();
            Configuration configuration = Setting.wrapLanguage(getBaseContext()).getResources().getConfiguration();
            resources.updateConfiguration(configuration, resources.getDisplayMetrics());
            this.resources = resources;
            resourcesLanguage = language;
        }
        return resources;
    }

    public void invalidateResources() {
        resources = null;
        resourcesLanguage = Integer.MIN_VALUE;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    // ===================== 爬虫 Context 包装类 =====================
    public static class SpiderContextWrapper extends ContextWrapper {

        private static final String ORIGIN_APP_NAME = "默影视";

        public SpiderContextWrapper(Context base) {
            super(base);
        }

        @Override
        public PackageManager getPackageManager() {
            return new ProxyPackageManager(super.getPackageManager());
        }

        private static class ProxyPackageManager extends PackageManager {

            private final PackageManager origin;

            public ProxyPackageManager(PackageManager origin) {
                this.origin = origin;
            }

            @Override
            public ApplicationInfo getApplicationInfo(String packageName, int flags) throws NameNotFoundException {
                ApplicationInfo info = origin.getApplicationInfo(packageName, flags);
                info.nonLocalizedLabel = ORIGIN_APP_NAME;
                info.labelRes = 0;
                return info;
            }

            @Override
            public CharSequence getApplicationLabel(ApplicationInfo info) {
                return ORIGIN_APP_NAME;
            }

            @Override
            public CharSequence getApplicationLabel(String packageName) throws NameNotFoundException {
                return ORIGIN_APP_NAME;
            }

            @Override
            public String[] getPackagesForUid(int uid) { return origin.getPackagesForUid(uid); }

            @Override
            public int getPackageUid(String packageName, int flags) throws NameNotFoundException { return origin.getPackageUid(packageName, flags); }

            @Override
            public android.content.pm.PackageInfo getPackageInfo(String packageName, int flags) throws NameNotFoundException { return origin.getPackageInfo(packageName, flags); }

            @Override
            public android.content.pm.PackageInfo getPackageInfo(android.net.Uri packageUri, int flags) throws NameNotFoundException { return origin.getPackageInfo(packageUri, flags); }

            @Override
            public android.content.pm.PackageInfo getPackageInfo(int uid, int flags) throws NameNotFoundException { return origin.getPackageInfo(uid, flags); }

            @Override
            public android.content.pm.PackageInfo[] getInstalledPackages(int flags) { return origin.getInstalledPackages(flags); }

            @Override
            public android.content.pm.PackageInfo[] getInstalledPackages(int flags, int userId) { return origin.getInstalledPackages(flags, userId); }

            @Override
            public ApplicationInfo[] getInstalledApplications(int flags) { return origin.getInstalledApplications(flags); }

            @Override
            public ApplicationInfo[] getInstalledApplications(int flags, int userId) { return origin.getInstalledApplications(flags, userId); }

            @Override
            public android.content.pm.ResolveInfo resolveActivity(android.content.Intent intent, int flags) { return origin.resolveActivity(intent, flags); }

            @Override
            public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent intent, int flags) { return origin.queryIntentActivities(intent, flags); }

            @Override
            public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName caller, android.content.Intent[] specifics, android.content.Intent intent, int flags) { return origin.queryIntentActivityOptions(caller, specifics, intent, flags); }

            @Override
            public java.util.List<android.content.pm.ResolveInfo> queryIntentReceivers(android.content.Intent intent, int flags) { return origin.queryIntentReceivers(intent, flags); }

            @Override
            public android.content.pm.ResolveInfo resolveService(android.content.Intent intent, int flags) { return origin.resolveService(intent, flags); }

            @Override
            public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent intent, int flags) { return origin.queryIntentServices(intent, flags); }

            @Override
            public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent intent, int flags) { return origin.queryIntentContentProviders(intent, flags); }

            @Override
            public android.content.ComponentName getHomeActivities(java.util.List<android.content.pm.ResolveInfo> outActivities) { return origin.getHomeActivities(outActivities); }

            @Override
            public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName component, int flags) throws NameNotFoundException { return origin.getProviderInfo(component, flags); }

            @Override
            public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName component, int flags) throws NameNotFoundException { return origin.getServiceInfo(component, flags); }

            @Override
            public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName component, int flags) throws NameNotFoundException { return origin.getActivityInfo(component, flags); }

            @Override
            public android.content.pm.PackageInfo getPackageArchiveInfo(String archiveFilePath, int flags) { return origin.getPackageArchiveInfo(archiveFilePath, flags); }

            @Override
            public android.graphics.drawable.Drawable getApplicationIcon(String packageName) throws NameNotFoundException { return origin.getApplicationIcon(packageName); }

            @Override
            public android.graphics.drawable.Drawable getApplicationIcon(ApplicationInfo info) { return origin.getApplicationIcon(info); }

            @Override
            public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName component) throws NameNotFoundException { return origin.getActivityIcon(component); }

            @Override
            public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent intent) throws NameNotFoundException { return origin.getActivityIcon(intent); }

            @Override
            public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName component) throws NameNotFoundException { return origin.getActivityBanner(component); }

            @Override
            public android.graphics.drawable.Drawable getApplicationBanner(String packageName) throws NameNotFoundException { return origin.getApplicationBanner(packageName); }

            @Override
            public android.graphics.drawable.Drawable getApplicationBanner(ApplicationInfo info) { return origin.getApplicationBanner(info); }

            @Override
            public void setComponentEnabledSetting(android.content.ComponentName componentName, int newState, int flags) { origin.setComponentEnabledSetting(componentName, newState, flags); }

            @Override
            public int getComponentEnabledSetting(android.content.ComponentName componentName) { return origin.getComponentEnabledSetting(componentName); }

            @Override
            public void setApplicationEnabledSetting(String packageName, int newState, int flags) { origin.setApplicationEnabledSetting(packageName, newState, flags); }

            @Override
            public int getApplicationEnabledSetting(String packageName) { return origin.getApplicationEnabledSetting(packageName); }

            @Override
            public boolean isSafeMode() { return origin.isSafeMode(); }

            @Override
            public void addPackageToPreferred(String packageName) { origin.addPackageToPreferred(packageName); }

            @Override
            public void removePackageFromPreferred(String packageName) { origin.removePackageFromPreferred(packageName); }

            @Override
            public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int flags) { return origin.getPreferredPackages(flags); }

            @Override
            public void addPreferredActivity(android.content.IntentFilter filter, int match, android.content.ComponentName[] set, android.content.ComponentName activity) { origin.addPreferredActivity(filter, match, set, activity); }

            @Override
            public void replacePreferredActivity(android.content.IntentFilter filter, int match, android.content.ComponentName[] set, android.content.ComponentName activity) { origin.replacePreferredActivity(filter, match, set, activity); }

            @Override
            public void clearPackagePreferredActivities(String packageName) { origin.clearPackagePreferredActivities(packageName); }

            @Override
            public int getPreferredActivities(java.util.List<android.content.IntentFilter> outFilters, java.util.List<android.content.ComponentName> outActivities, String packageName) { return origin.getPreferredActivities(outFilters, outActivities, packageName); }

            @Override
            public android.content.pm.PackageInfo getInstalledPackageInfo(String packageName) throws NameNotFoundException { return origin.getInstalledPackageInfo(packageName); }

            @Override
            public void verifyPackageInstaller(String packageName) throws NameNotFoundException { origin.verifyPackageInstaller(packageName); }

            @Override
            public boolean isPackageAvailable(String packageName) { return origin.isPackageAvailable(packageName); }

            @Override
            public java.util.List<android.content.pm.PackageInfo> getUninstalledPackages(int flags) { return origin.getUninstalledPackages(flags); }

            @Override
            public android.content.pm.PackageInfo getPackageInfoAsUser(String packageName, int flags, int userId) throws NameNotFoundException { return origin.getPackageInfoAsUser(packageName, flags, userId); }

            @Override
            public ApplicationInfo getApplicationInfoAsUser(String packageName, int flags, int userId) throws NameNotFoundException { return origin.getApplicationInfoAsUser(packageName, flags, userId); }

            @Override
            public android.content.pm.PackageInfo getPackageInfo(int uid, int flags, int userId) throws NameNotFoundException { return origin.getPackageInfo(uid, flags, userId); }

            @Override
            public ApplicationInfo getApplicationInfo(int uid, int flags, int userId) throws NameNotFoundException { return origin.getApplicationInfo(uid, flags, userId); }
        }
    }
}
