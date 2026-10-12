package app.exteraless.plugins;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.File;

/**
 * Контроллер Java-плагинов — отдельный реестр и движок, а не фильтр поверх
 * Python-контроллера.
 *
 * Наследует {@link PluginsController}, поэтому переиспользует скачивание/скан
 * метаданных, установку, хуки, меню, настройки и watchdog, но владеет
 * собственным состоянием:
 * <ul>
 *   <li>каталог {@code filesDir/java_plugins} ({@link #getPluginsDir()});</li>
 *   <li>собственный SharedPreferences ({@link #init}).</li>
 *   <li>собственный переключатель движка ({@link #isEngineEnabled()});</li>
 *   <li>собственное пространство ключей разрешений/доверия/разбора
 *       ({@link #permId(String)});</li>
 * </ul>
 * Данные общие только те, что естественно общие: модель {@link Plugin},
 * AST-разбор метаданных и хранилища, но ключи в них разведены префиксом.
 */
public class JavaPluginsController extends PluginsController {

    /** Отдельный файл настроек движка Java-плагинов. */
    private static final String PREFS_NAME = "ddg_java_plugins";

    /** Префикс ключей в общих хранилищах разрешений/доверия/разбора. */
    private static final String PERM_NAMESPACE = "java:";

    private static volatile JavaPluginsController instance;

    public static JavaPluginsController getInstance() {
        if (instance == null) {
            synchronized (JavaPluginsController.class) {
                if (instance == null) {
                    instance = new JavaPluginsController();
                }
            }
        }
        return instance;
    }

    private JavaPluginsController() {
    }

    @Override
    public File getPluginsDir() {
        return new File(appContext.getFilesDir(), "java_plugins");
    }

    @Override
    protected String permId(String pluginId) {
        return pluginId == null ? null : PERM_NAMESPACE + pluginId;
    }

    @Override
    protected String settingsPrefsName(String pluginId) {
        return PluginsConstants.SETTINGS_PREFS_PREFIX + "java_" + pluginId;
    }

    /** Java-движок включён по умолчанию: старый {@code DDGJavaPlugins} был {@code true}. */
    @Override
    public boolean isEngineEnabled() {
        return preferences != null
                && preferences.getBoolean(PluginsConstants.KEY_ENGINE_ENABLED, true);
    }

    @Override
    public void init(Context context) {
        if (initialized) {
            return;
        }
        initialized = true;
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        watchdog = new PluginsWatchdog(preferences, this);
        getPluginsDir().mkdirs();

        if (!isEngineEnabled()) {
            return;
        }
        String crashed = watchdog.recoverAfterCrash();
        if (crashed != null) {
            FileLog.e("JavaPluginsController: disabled crashed plugin " + crashed);
        }
        watchdog.start();
        JavaPluginsEngine.getInstance().ensureStarted(appContext);
        rescanAndLoadEnabled();
        executeOnAppEventLocal(PluginsConstants.EVENT_APP_START);
    }

    @Override
    public void setEngineEnabled(boolean enabled) {
        preferences.edit()
                .putBoolean(PluginsConstants.KEY_ENGINE_ENABLED, enabled)
                .apply();
        if (enabled && initialized) {
            JavaPluginsEngine.getInstance().ensureStarted(appContext);
            rescanAndLoadEnabled();
        } else if (!enabled) {
            unloadAll();
        }
    }

    @Override
    public void rescanPlugins(Runnable onDone) {
        JavaPluginsEngine.getInstance().runOnEngine(() -> {
            rescanPlugins();
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    @Override
    public void restart(boolean startWithSafeMode) {
        setSafeMode(startWithSafeMode);
        unloadAll();
        if (!isEngineEnabled() || startWithSafeMode) {
            return;
        }
        JavaPluginsEngine.getInstance().ensureStarted(appContext);
        rescanAndLoadEnabled();
    }

    @Override
    public boolean isPluginEngineAvailable() {
        return JavaPluginsEngine.getInstance().isReady();
    }
}
