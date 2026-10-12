package app.exteraless.plugins.javasdk;

import app.exteraless.plugins.PluginPermissions;
import app.exteraless.plugins.PluginRuntime;
import app.exteraless.plugins.PluginsConstants;
import app.exteraless.plugins.JavaPluginsController;
import app.exteraless.plugins.PluginLog;

import com.exteragram.messenger.utils.AppUtils;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

/**
 * Фасад SDK, которым пользуются Java-плагины (аналог {@code PythonBridge} для
 * Python-стороны). Владелец вызова определяется по метке потока
 * ({@link PluginRuntime}); любой вызов вне кода плагина отвергается.
 *
 * Мост намеренно узкий: сложные значения ходят JSON-строками. Вся логика — в
 * {@link JavaPluginsController}.
 */
public final class JavaPluginBridge {

    private JavaPluginBridge() {
    }

    /** id плагина, чей код сейчас исполняется, или null. */
    public static String currentPluginId() {
        return PluginRuntime.current();
    }

    private static String requireId() {
        String id = PluginRuntime.current();
        if (id == null) {
            FileLog.w("JavaPluginBridge: called outside of plugin code");
        }
        return id;
    }

    // ---------- настройки ----------

    /** @return JSON-значение настройки или null. */
    public static String getSettingJson(String key) {
        String id = requireId();
        return id == null ? null : JavaPluginsController.getInstance().getPluginSettingJson(id, key);
    }

    public static void setSetting(String key, Object value) {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().setPluginSetting(id, key, value);
        }
    }

    public static void setSetting(String key, Object value, boolean reloadSettings) {
        String id = requireId();
        if (id == null) {
            return;
        }
        JavaPluginsController controller = JavaPluginsController.getInstance();
        String json = toJson(value);
        controller.setPluginSettingJson(id, key, json, reloadSettings);
        controller.notifySettingChanged(id, key, json);
    }

    public static String exportSettings() {
        String id = requireId();
        return id == null ? "{}" : JavaPluginsController.getInstance().exportPluginSettings(id);
    }

    public static void importSettings(String json, boolean reloadSettings) {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().importPluginSettings(id, json, reloadSettings);
        }
    }

    public static void replaceSettings(String json) {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().replacePluginSettings(id, json);
        }
    }

    // ---------- логирование ----------

    public static void log(String message) {
        log("I", message);
    }

    public static void log(String level, String message) {
        String id = requireId();
        String text = message == null ? "" : message;
        FileLog.d("[plugin:" + id + "] " + text);
        PluginLog.append(id, level == null ? "I" : level, text);
        AppUtils.logPlugin(text);
    }

    // ---------- хуки ----------

    public static void addSendMessageHook(int priority) {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().registerSendMessageHook(id, priority);
        }
    }

    public static void addRequestHook(String requestName, boolean matchSubstring, int priority) {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().registerRequestHook(id, requestName, matchSubstring, priority);
        }
    }

    public static void removeSendMessageHook() {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().unregisterSendMessageHook(id);
        }
    }

    public static void removeRequestHook(String requestName) {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().unregisterRequestHook(id, requestName);
        }
    }

    // ---------- меню ----------

    /** @return item_id зарегистрированного пункта меню. */
    public static String addMenuItem(String jsonMenuItem, PluginMenuItemCallback onClick) {
        String id = requireId();
        return id == null ? null : JavaPluginsController.getInstance()
                .registerMenuItem(id, jsonMenuItem, onClick);
    }

    public static void removeMenuItem(String itemId) {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().removeMenuItem(id, itemId);
        }
    }

    // ---------- разрешения и прочее ----------

    public static boolean hasPermission(String permission) {
        String id = requireId();
        return id != null && PluginPermissions.has(
                JavaPluginsController.getInstance().permissionId(id), permission);
    }

    public static boolean checkPermission(String permission, String what) {
        String id = requireId();
        return id != null && PluginPermissions.check(
                JavaPluginsController.getInstance().permissionId(id), permission, what);
    }

    public static boolean isUnsafeMode() {
        return PluginPermissions.isUnsafeMode();
    }

    public static boolean isEngineEnabled() {
        return JavaPluginsController.getInstance().isEngineEnabled();
    }

    public static String sdkVersion() {
        return PluginsConstants.JAVA_SDK_VERSION;
    }

    public static String appVersion() {
        return BuildVars.BUILD_VERSION_STRING;
    }

    public static String pluginsDir() {
        return JavaPluginsController.getInstance().getPluginsDir().getAbsolutePath();
    }

    public static void reloadSettingsScreen() {
        String id = requireId();
        if (id != null) {
            JavaPluginsController.getInstance().reloadSettingsScreen(id);
        }
    }

    // ---------- внутреннее ----------

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return org.json.JSONObject.quote(String.valueOf(value));
    }
}
