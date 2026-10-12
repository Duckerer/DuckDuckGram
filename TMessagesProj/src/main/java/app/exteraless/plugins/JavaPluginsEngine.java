package app.exteraless.plugins;

import android.content.Context;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dalvik.system.DexClassLoader;
import dalvik.system.InMemoryDexClassLoader;

import app.exteraless.plugins.javasdk.BasePlugin;
import app.exteraless.plugins.javasdk.PluginMenuItemCallback;
import app.exteraless.plugins.models.CustomSetting;
import app.exteraless.plugins.ui.PluginSettingsActivity;

/**
 * Движок Java-плагинов: грузит {@code classes.dex} из {@code .jar}/{@code .jplugin}
 * (в память через {@link InMemoryDexClassLoader}), держит экземпляры
 * {@link BasePlugin} и зовёт их хуки.
 *
 * Полностью повторяет контракты {@link PythonPluginsEngine}: метаданные,
 * загрузка, хуки и JSON настроек имеют тот же формат, поэтому UI
 * ({@code PluginsActivity}, {@code PluginSettingsActivity}, лист установки)
 * работает с обоими движками без ветвлений по типу.
 *
 * Формат Java-плагина: zip-архив (jar), в корне которого лежат
 * {@code classes.dex} (собранный d8/dx байткод) и {@code plugin.json}:
 * <pre>
 * {
 *   "id": "com.example.hello",
 *   "name": "Hello",
 *   "description": "...",
 *   "author": "...",
 *   "version": "1.0",
 *   "main_class": "com.example.HelloPlugin",
 *   "sdk_version": "&gt;=1.0.0",
 *   "permissions": ["network"]
 * }
 * </pre>
 *
 * Код плагина исполняется в процессе приложения; владелец вызова помечается в
 * {@link PluginRuntime}, чтобы {@link PluginSinkGate} пропускал его обращения к
 * внутренним стокам, а все вызовы обёрнуты {@link PluginsWatchdog}.
 */
public class JavaPluginsEngine implements PluginEngine {

    private static volatile JavaPluginsEngine instance;

    private static final String META_ENTRY = "plugin.json";

    public static JavaPluginsEngine getInstance() {
        if (instance == null) {
            synchronized (JavaPluginsEngine.class) {
                if (instance == null) {
                    instance = new JavaPluginsEngine();
                }
            }
        }
        return instance;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "java-plugins-engine");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final ConcurrentHashMap<String, BasePlugin> instances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClassLoader> loaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Plugin> unloadingPlugins = new ConcurrentHashMap<>();

    private volatile boolean started;

    private JavaPluginsEngine() {
    }

    // ---------- PluginEngine ----------

    @Override
    public String getEngineName() {
        return PluginsConstants.JAVA;
    }

    @Override
    public boolean isReady() {
        return started;
    }

    /** Java-движок всегда готов и не требует прогрева интерпретатора. */
    public boolean isStarted() {
        return started;
    }

    /** Идемпотентный «запуск»: помечает движок готовым. */
    public void ensureStarted(Context appContext) {
        if (appContext == null) {
            appContext = ApplicationLoader.applicationContext;
        }
        this.appContext = appContext;
        started = true;
    }

    private Context appContext;

    private Context context() {
        return appContext != null ? appContext : ApplicationLoader.applicationContext;
    }

    @Override
    public void shutdown() {
        instances.clear();
        loaders.clear();
    }

    @Override
    public void runOnEngine(Runnable runnable) {
        executor.execute(runnable);
    }

    /** Забыть наблюдения плагина (удаление плагина, сброс профиля, чистка журнала). */
    public void forgetAudit(String pluginId) {
        PluginAuditJournal.forget(pluginId);
    }

    // ---------- метаданные ----------

    @Override
    public String readMetadataJson(String path) {
        JSONObject root = new JSONObject();
        JSONObject meta = new JSONObject();
        try {
            String raw;
            java.util.zip.ZipFile zip = null;
            try {
                zip = new java.util.zip.ZipFile(path);
                java.util.zip.ZipEntry entry = zip.getEntry(META_ENTRY);
                if (entry == null) {
                    root.put("ok", false);
                    root.put("error", "plugin.json not found");
                    return root.toString();
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    raw = out.toString("UTF-8");
                }
            } finally {
                if (zip != null) {
                    zip.close();
                }
            }
            JSONObject json = new JSONObject(raw);
            String id = json.optString("id", null);
            if (id == null || id.isEmpty()) {
                root.put("ok", false);
                root.put("error", "plugin.json has no id");
                return root.toString();
            }
            meta.put("id", id);
            meta.put("name", json.optString("name", id));
            putIfNotNull(meta, "description", json.optString("description", null));
            putIfNotNull(meta, "author", json.optString("author", null));
            meta.put("version", json.optString("version", "1.0"));
            putIfNotNull(meta, "icon", json.optString("icon", null));
            putIfNotNull(meta, "main_class", json.optString("main_class", null));
            putIfNotNull(meta, "app_version", json.optString("app_version", null));
            putIfNotNull(meta, "sdk_version", json.optString("sdk_version", null));
            meta.put("beta", json.optBoolean("beta", false));

            JSONArray requirements = json.optJSONArray("requirements");
            meta.put("requirements", requirements == null ? new JSONArray() : requirements);

            boolean declared = json.has("permissions");
            meta.put("permissions_declared", declared);
            JSONArray permissions = json.optJSONArray("permissions");
            meta.put("permissions", permissions == null ? new JSONArray() : permissions);

            JSONObject requires = json.optJSONObject("requires");
            if (requires != null) {
                meta.put("requires", requires);
            }

            root.put("ok", true);
            root.put("meta", meta);
            return root.toString();
        } catch (Throwable t) {
            FileLog.e("JavaPluginsEngine: readMetadata failed for " + path, t);
            try {
                root.put("ok", false);
                root.put("error", t.getMessage() == null ? t.toString() : t.getMessage());
            } catch (Exception ignored) {
            }
            return root.toString();
        }
    }

    private static void putIfNotNull(JSONObject obj, String key, String value) throws Exception {
        if (value != null && !value.isEmpty()) {
            obj.put(key, value);
        }
    }

    /**
     * Возможности Java-плагина по объявленным разрешениям. У Java нет AST-разбора
     * исходника, поэтому источником служит {@code plugin.json}: категория — само
     * разрешение, причина — объявление.
     */
    @Override
    public String scanCapabilitiesJson(String path) {
        try {
            String metaJson = readMetadataJson(path);
            JSONObject root = new JSONObject(metaJson);
            if (!root.optBoolean("ok")) {
                return null;
            }
            JSONObject meta = root.optJSONObject("meta");
            JSONArray permissions = meta == null ? null : meta.optJSONArray("permissions");
            JSONObject result = new JSONObject();
            if (permissions != null) {
                for (int i = 0; i < permissions.length(); i++) {
                    String perm = permissions.optString(i);
                    if (perm != null && !perm.isEmpty()) {
                        JSONArray reasons = new JSONArray();
                        reasons.put("plugin.json");
                        result.put(perm, reasons);
                    }
                }
            }
            return result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- жизненный цикл ----------

    @Override
    public String loadPlugin(Plugin plugin) {
        if (PluginSinkGate.refuseFromPlugin("loadPlugin", true)) {
            return "{\"ok\":false,\"error\":\"not allowed\"}";
        }
        if (plugin == null) {
            return "{\"ok\":false,\"error\":\"no plugin\"}";
        }
        dropSettingsJson(plugin.id);
        if (!started) {
            return "{\"ok\":false,\"error\":\"engine not started\"}";
        }
        try {
            PluginSinkGate.install();
        } catch (Throwable t) {
            FileLog.e("JavaPluginsEngine: sink gate install failed", t);
        }

        PluginsWatchdog watchdog = JavaPluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(plugin.id, true);
        String previousRuntime = PluginRuntime.enter(plugin.id);
        try {
            String metaJson = readMetadataJson(plugin.path);
            JSONObject root = new JSONObject(metaJson);
            if (!root.optBoolean("ok")) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(root.optString("error", "bad metadata")) + "}";
            }
            JSONObject meta = root.getJSONObject("meta");
            String mainClass = meta.optString("main_class", null);
            if (mainClass == null || mainClass.isEmpty()) {
                return "{\"ok\":false,\"error\":\"plugin.json has no main_class\"}";
            }

            ClassLoader loader = createPluginLoader(new File(plugin.path));
            Class<?> clazz = loader.loadClass(mainClass);
            if (!BasePlugin.class.isAssignableFrom(clazz)) {
                return "{\"ok\":false,\"error\":\"main_class is not a BasePlugin\"}";
            }
            BasePlugin instance = (BasePlugin) clazz.getDeclaredConstructor().newInstance();
            instance.attach(plugin.id, meta.optString("name", plugin.id),
                    meta.optString("description", null), meta.optString("author", null),
                    meta.optString("version", "1.0"), context());

            loaders.put(plugin.id, loader);
            instances.put(plugin.id, instance);

            instance.onPluginLoad();

            boolean hasSettings;
            try {
                String settings = instance.createSettingsJson();
                hasSettings = settings != null && !settings.trim().isEmpty()
                        && !settings.trim().equals("[]") && !settings.trim().equals("null");
            } catch (Throwable t) {
                hasSettings = false;
                FileLog.e("JavaPluginsEngine: createSettings failed for " + plugin.id, t);
            }

            JSONObject handles = new JSONObject();
            handles.put("app_event", overrides(clazz, "onAppEvent", String.class));
            handles.put("pre_request", overrides(clazz, "preRequestHook", int.class, String.class, Object.class));
            handles.put("post_request", overrides(clazz, "postRequestHook", int.class, String.class, Object.class, Object.class));
            handles.put("update", overrides(clazz, "onUpdateHook", int.class, String.class, Object.class));
            handles.put("updates", overrides(clazz, "onUpdatesHook", int.class, String.class, Object.class));
            handles.put("send_message", overrides(clazz, "onSendMessageHook", int.class, Object.class));

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("has_settings", hasSettings);
            result.put("handles", handles);
            return result.toString();
        } catch (Throwable t) {
            watchdog.handlePluginError(plugin.id, t);
            return "{\"ok\":false,\"error\":" + JSONObject.quote(t.getMessage() == null ? t.toString() : t.getMessage()) + "}";
        } finally {
            PluginRuntime.exit(previousRuntime);
            watchdog.notePluginExit(plugin.id);
        }
    }

    private static boolean overrides(Class<?> clazz, String name, Class<?>... params) {
        try {
            Method method = clazz.getMethod(name, params);
            return method.getDeclaringClass() != BasePlugin.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Загрузчик для кода плагина. ART не позволяет грузить dex-файл напрямую из
     * перезаписываемого каталога приложения ({@code filesDir}): «Writable dex file
     * ... is not allowed». Поэтому {@code classes.dex} читается из архива и грузится
     * в память через {@link InMemoryDexClassLoader} (API 26+); для API 24/25 — старый
     * {@link DexClassLoader} с oat-кэшем в {@code code_cache}.
     */
    private ClassLoader createPluginLoader(File pluginFile) throws java.io.IOException {
        byte[] dex = readDexBytes(pluginFile);
        if (dex == null) {
            throw new java.io.IOException("classes.dex not found in " + pluginFile.getName());
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            return new InMemoryDexClassLoader(java.nio.ByteBuffer.wrap(dex),
                    JavaPluginsEngine.class.getClassLoader());
        }
        File codeCache = context().getCodeCacheDir();
        return new DexClassLoader(pluginFile.getAbsolutePath(),
                codeCache == null ? context().getCacheDir().getAbsolutePath() : codeCache.getAbsolutePath(),
                null, JavaPluginsEngine.class.getClassLoader());
    }

    private static byte[] readDexBytes(File file) throws java.io.IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            java.util.zip.ZipEntry entry = zip.getEntry("classes.dex");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
    }

    @Override
    public void unload(Plugin plugin) {
        if (PluginSinkGate.refuseFromPlugin("unload", true)) {
            return;
        }
        if (plugin == null) {
            return;
        }
        unloadingPlugins.put(plugin.id, plugin);
        try {
            unloadPlugin(plugin.id);
        } finally {
            unloadingPlugins.remove(plugin.id);
        }
    }

    @Override
    public void unloadPlugin(String pluginId) {
        if (PluginSinkGate.refuseForeign(pluginId, "unloadPlugin")) {
            return;
        }
        if (pluginId == null) {
            return;
        }
        dropSettingsJson(pluginId);
        BasePlugin instance = instances.remove(pluginId);
        loaders.remove(pluginId);
        if (instance == null) {
            return;
        }
        PluginsWatchdog watchdog = JavaPluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        String previousRuntime = PluginRuntime.enter(pluginId);
        try {
            instance.onPluginUnload();
        } catch (Throwable t) {
            FileLog.e("JavaPluginsEngine: unload failed for " + pluginId, t);
        } finally {
            PluginRuntime.exit(previousRuntime);
            watchdog.notePluginExit(pluginId);
        }
        Plugin plugin = unloadingPlugins.get(pluginId);
        if (plugin == null) {
            plugin = JavaPluginsController.getInstance().getPlugin(pluginId);
        }
        if (plugin != null) {
            plugin.loaded = false;
        }
        JavaPluginsController.getInstance().notifyMenuItemsUpdated();
    }

    @Override
    public void uninstallPlugin(String pluginId) {
        if (PluginSinkGate.refuseFromPlugin("uninstallPlugin", true)) {
            return;
        }
        instances.remove(pluginId);
        loaders.remove(pluginId);
        dropSettingsJson(pluginId);
    }

    // ---------- события и хуки ----------

    @Override
    public void callAppEvent(String pluginId, String event) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return;
        }
        invoke(pluginId, () -> instance.onAppEvent(event));
    }

    @Override
    public HookResult callSendMessageHook(String pluginId, int account, Object params) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return HookResult.DEFAULT;
        }
        return invokeResult(pluginId, () -> instance.onSendMessageHook(account, params));
    }

    @Override
    public HookResult callPreRequestHook(String pluginId, int account, String requestName, Object request) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return HookResult.DEFAULT;
        }
        return invokeResult(pluginId, () -> instance.preRequestHook(account, requestName, request));
    }

    @Override
    public HookResult callPostRequestHook(String pluginId, int account, String requestName,
                                          Object response, Object error) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return HookResult.DEFAULT;
        }
        return invokeResult(pluginId, () -> instance.postRequestHook(account, requestName, response, error));
    }

    @Override
    public HookResult callUpdateHook(String pluginId, int account, String updateName, Object update) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return HookResult.DEFAULT;
        }
        return invokeResult(pluginId, () -> instance.onUpdateHook(account, updateName, update));
    }

    @Override
    public HookResult callUpdatesHook(String pluginId, int account, String containerName, Object updates) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return HookResult.DEFAULT;
        }
        return invokeResult(pluginId, () -> instance.onUpdatesHook(account, containerName, updates));
    }

    @FunctionalInterface
    private interface HookCall {
        HookResult call();
    }

    private HookResult invokeResult(String pluginId, HookCall call) {
        PluginsWatchdog watchdog = JavaPluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        String previousRuntime = PluginRuntime.enter(pluginId);
        try {
            HookResult result = call.call();
            return result == null ? HookResult.DEFAULT : result;
        } catch (Throwable t) {
            watchdog.handlePluginError(pluginId, t);
            return HookResult.DEFAULT;
        } finally {
            PluginRuntime.exit(previousRuntime);
            watchdog.notePluginExit(pluginId);
        }
    }

    @FunctionalInterface
    private interface SimpleCall {
        void call();
    }

    private void invoke(String pluginId, SimpleCall call) {
        PluginsWatchdog watchdog = JavaPluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        String previousRuntime = PluginRuntime.enter(pluginId);
        try {
            call.call();
        } catch (Throwable t) {
            watchdog.handlePluginError(pluginId, t);
        } finally {
            PluginRuntime.exit(previousRuntime);
            watchdog.notePluginExit(pluginId);
        }
    }

    // ---------- экран настроек ----------

    private static final long SETTINGS_JSON_TTL_MS = 500;
    private final ConcurrentHashMap<String, Object[]> settingsJsonCache = new ConcurrentHashMap<>();

    @Override
    public void invalidateSettingsJson(String pluginId) {
        dropSettingsJson(pluginId);
    }

    private void dropSettingsJson(String pluginId) {
        if (pluginId != null) {
            settingsJsonCache.remove(pluginId);
        }
    }

    @Override
    public String getSettingsJson(String pluginId) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return null;
        }
        Object[] cached = settingsJsonCache.get(pluginId);
        long now = android.os.SystemClock.elapsedRealtime();
        if (cached != null && now - (Long) cached[1] < SETTINGS_JSON_TTL_MS) {
            return (String) cached[0];
        }
        PluginsWatchdog watchdog = JavaPluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        String previousRuntime = PluginRuntime.enter(pluginId);
        try {
            String json = instance.createSettingsJson();
            json = mergeStoredValues(pluginId, json);
            if (json != null) {
                settingsJsonCache.put(pluginId, new Object[]{json, now});
            }
            return json;
        } catch (Throwable t) {
            watchdog.handlePluginError(pluginId, t);
            return null;
        } finally {
            PluginRuntime.exit(previousRuntime);
            watchdog.notePluginExit(pluginId);
        }
    }

    /** Подмешивает сохранённые значения в «value» строк с полем key. */
    private String mergeStoredValues(String pluginId, String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            JSONArray rows = new JSONArray(json);
            boolean changed = false;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String key = row.optString("key", null);
                if (key == null || key.isEmpty()) {
                    continue;
                }
                String stored = JavaPluginsController.getInstance().getPluginSettingJson(pluginId, key);
                if (stored == null) {
                    continue;
                }
                Object value = parseValue(stored);
                if (value != null) {
                    row.put("value", value);
                    changed = true;
                }
            }
            return changed ? rows.toString() : json;
        } catch (Throwable t) {
            FileLog.e("JavaPluginsEngine: merge settings failed for " + pluginId, t);
            return json;
        }
    }

    private static Object parseValue(String raw) {
        try {
            return new JSONArray("[" + raw + "]").get(0);
        } catch (Throwable t) {
            return raw;
        }
    }

    @Override
    public Object getSettingsCustomContent(String pluginId, String viewId, Context context) {
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return null;
        }
        PluginsWatchdog watchdog = JavaPluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        String previousRuntime = PluginRuntime.enter(pluginId);
        try {
            Object content = instance.createCustomSettingView(viewId, context);
            if (content instanceof CustomSetting) {
                CustomSetting setting = (CustomSetting) content;
                CustomSetting.Factory<?> factory = setting.getFactory();
                if (factory == null) {
                    return setting.getItem();
                }
                UItem.UItemFactory.setup(factory);
                UItem item = factory.create(JavaPluginsController.getInstance().getPlugin(pluginId),
                        setting, setting.getFactoryArgs());
                if (item != null) {
                    item.settingItem = setting;
                }
                return item;
            }
            return content;
        } catch (Throwable t) {
            watchdog.handlePluginError(pluginId, t);
            return null;
        } finally {
            PluginRuntime.exit(previousRuntime);
            watchdog.notePluginExit(pluginId);
        }
    }

    @Override
    public boolean dispatchSettingsCustomClick(String pluginId, UItem item, View view, boolean longClick) {
        if (item == null || !(item.settingItem instanceof CustomSetting)) {
            return false;
        }
        CustomSetting.Factory<?> factory = ((CustomSetting) item.settingItem).getFactory();
        Plugin plugin = JavaPluginsController.getInstance().getPlugin(pluginId);
        if (factory == null || plugin == null) {
            return false;
        }
        PluginsWatchdog watchdog = JavaPluginsController.getInstance().getWatchdog();
        watchdog.notePluginEnter(pluginId);
        String previousRuntime = PluginRuntime.enter(pluginId);
        try {
            if (longClick) {
                factory.onLongClick(plugin, item, view);
            } else {
                factory.onClick(plugin, item, view);
            }
        } catch (Throwable t) {
            watchdog.handlePluginError(pluginId, t);
        } finally {
            PluginRuntime.exit(previousRuntime);
            watchdog.notePluginExit(pluginId);
        }
        return true;
    }

    @Override
    public void notifySettingChanged(String pluginId, String key, String jsonValue) {
        dropSettingsJson(pluginId);
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return;
        }
        Object value = parseValue(jsonValue);
        invoke(pluginId, () -> instance.onSettingChanged(key, value));
    }

    @Override
    public void dispatchSettingClick(String pluginId, String callbackId, View view) {
        dropSettingsJson(pluginId);
        BasePlugin instance = instances.get(pluginId);
        if (instance == null) {
            return;
        }
        invoke(pluginId, () -> instance.onSettingClick(callbackId, view));
    }

    @Override
    public void invalidateSettingsMirror(String pluginId) {
        dropSettingsJson(pluginId);
    }

    @Override
    public void openPluginSettings(com.exteragram.messenger.plugins.Plugin plugin,
                                   BaseFragment fragment, String targetSetting) {
        if (plugin == null || fragment == null) {
            return;
        }
        fragment.presentFragment(PluginSettingsActivity.newInstance(plugin.getId(), targetSetting, true));
    }

    // ---------- мост для JavaPluginBridge ----------

    /** Зарегистрировать пункт меню Java-плагина (вызывается из {@link PluginsController}). */
    public String registerMenuItem(String pluginId, String jsonMenuItem, PluginMenuItemCallback onClick) {
        return JavaPluginsController.getInstance().registerMenuItem(pluginId, jsonMenuItem, onClick);
    }
}
