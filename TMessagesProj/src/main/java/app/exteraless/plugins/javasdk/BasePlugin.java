package app.exteraless.plugins.javasdk;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.exteraless.plugins.HookResult;
import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginsConstants;
import app.exteraless.plugins.JavaPluginsController;

/**
 * Базовый класс Java-плагина — точка входа SDK.
 *
 * Плагин — это {@code .jar} с байткодом и файлом {@code plugin.json} в корне, в
 * котором объявлены {@code id}, {@code name}, {@code main_class} (класс-наследник
 * {@code BasePlugin}), версия и разрешения. Установка, список и экран настроек —
 * те же, что у Python-плагинов: движок отдаёт метаданные и JSON настроек в общий
 * UI.
 *
 * Жизненный цикл:
 * <ol>
 *   <li>{@link #onPluginLoad()} — после создания экземпляра: здесь регистрируются
 *       хуки, пункты меню и формируется {@link #createSettings()};</li>
 *   <li>колбэки хуков и событий (см. ниже);</li>
 *   <li>{@link #onPluginUnload()} — при выключении/удалении/выгрузке.</li>
 * </ol>
 *
 * Хуки возвращают {@link HookResult}: {@code HookResult.DEFAULT} — «не вмешиваюсь»,
 * {@code CANCEL} — отменить, {@code MODIFY(value)} — подменить объект,
 * {@code MODIFY_FINAL(value)} — подменить и прекратить цепочку.
 *
 * SDK максимально широкий: доступ к {@link #getContext() контексту}, файлам,
 * сети (через {@link #httpGet(String)}), настройкам, меню, произвольным вьюхам
 * настроек и планировщику. Разрешения ({@code network}, {@code messages.send},
 * {@code hooks} и т.д.) объявляются в {@code plugin.json}.
 */
public abstract class BasePlugin {

    // ---------- метаданные (заполняет движок) ----------

    private String pluginId;
    private String pluginName;
    private String pluginDescription;
    private String pluginAuthor;
    private String pluginVersion = "1.0";

    private Context context;

    /** Обработчики по умолчанию; движок уточняет их по факту переопределения методов. */
    public boolean handlesAppEvent = true;
    public boolean handlesSendMessage = true;
    public boolean handlesPreRequest = true;
    public boolean handlesPostRequest = true;
    public boolean handlesUpdate = true;
    public boolean handlesUpdates = true;

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
                Thread t = new Thread(r, "java-plugin-worker");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    // ---------- доступ к метаданным ----------

    public final String getPluginId() {
        return pluginId;
    }

    public final String getPluginName() {
        return pluginName;
    }

    public final String getPluginDescription() {
        return pluginDescription;
    }

    public final String getPluginAuthor() {
        return pluginAuthor;
    }

    public final String getPluginVersion() {
        return pluginVersion;
    }

    public final Context getContext() {
        return context != null ? context : org.telegram.messenger.ApplicationLoader.applicationContext;
    }

    /** Вызывается движком после создания экземпляра. Не переопределять. */
    public final void attach(String id, String name, String description, String author,
                             String version, Context appContext) {
        this.pluginId = id;
        this.pluginName = name;
        this.pluginDescription = description;
        this.pluginAuthor = author;
        this.pluginVersion = version == null ? "1.0" : version;
        this.context = appContext;
    }

    // ---------- жизненный цикл ----------

    /** Плагин загружен: регистрируем хуки/меню, готовим настройки. */
    public void onPluginLoad() {
    }

    /** Плагин выгружается: снимаем то, что регистрировали вручную. */
    public void onPluginUnload() {
    }

    /** Событие приложения: {@code app_start}, {@code app_stop}, {@code app_pause}, {@code app_resume}. */
    public void onAppEvent(String event) {
    }

    // ---------- хуки ----------

    /** Исходящее сообщение. {@code params} — {@code SendMessagesHelper.SendMessageParams}. */
    public HookResult onSendMessageHook(int account, Object params) {
        return HookResult.DEFAULT;
    }

    /** Перед отправкой TL-запроса. */
    public HookResult preRequestHook(int account, String requestName, Object request) {
        return HookResult.DEFAULT;
    }

    /** После ответа на TL-запрос. */
    public HookResult postRequestHook(int account, String requestName, Object response, Object error) {
        return HookResult.DEFAULT;
    }

    /** Одиночный апдейт ({@code TL_update*}). */
    public HookResult onUpdateHook(int account, String updateName, Object update) {
        return HookResult.DEFAULT;
    }

    /** Контейнер апдейтов ({@code TL_updates*}). */
    public HookResult onUpdatesHook(int account, String containerName, Object updates) {
        return HookResult.DEFAULT;
    }

    // ---------- регистрация хуков ----------

    public void registerSendMessageHook() {
        registerSendMessageHook(0);
    }

    public void registerSendMessageHook(int priority) {
        JavaPluginBridge.addSendMessageHook(priority);
    }

    public void registerRequestHook(String requestName, int priority) {
        JavaPluginBridge.addRequestHook(requestName, false, priority);
    }

    public void registerRequestHookMatch(String substring, int priority) {
        JavaPluginBridge.addRequestHook(substring, true, priority);
    }

    public void unregisterSendMessageHook() {
        JavaPluginBridge.removeSendMessageHook();
    }

    public void unregisterRequestHook(String requestName) {
        JavaPluginBridge.removeRequestHook(requestName);
    }

    // ---------- настройки ----------

    /** Описание экрана настроек. null/пусто — экрана нет. */
    public SettingsBuilder createSettings() {
        return null;
    }

    /** Готовый JSON-список строк (если нужен ручной контроль над схемой). */
    public String createSettingsJson() {
        SettingsBuilder builder = createSettings();
        return builder == null ? "[]" : builder.build();
    }

    /** Значение настройки изменилось в UI. */
    public void onSettingChanged(String key, Object value) {
    }

    /** Нажатие строки с {@code callback_id}. */
    public void onSettingClick(String callbackId, View view) {
    }

    /** Долгое нажатие строки с {@code long_callback_id}. */
    public void onSettingLongClick(String callbackId, View view) {
    }

    /**
     * Произвольная строка настроек: верните {@link android.view.View},
     * {@link org.telegram.ui.Components.UItem} или
     * {@link app.exteraless.plugins.models.CustomSetting}. Ключ {@code viewId}
     * задаётся в {@code SettingsBuilder.Custom.of(viewId)}.
     */
    public Object createCustomSettingView(String viewId, Context ctx) {
        return null;
    }

    // ---------- доступ к настройкам ----------

    public String getString(String key, String defaultValue) {
        String raw = JavaPluginBridge.getSettingJson(key);
        if (raw == null) {
            return defaultValue;
        }
        return fromJsonString(raw, defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = JavaPluginBridge.getSettingJson(key);
        if (raw == null) {
            return defaultValue;
        }
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String raw = JavaPluginBridge.getSettingJson(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
    }

    public double getDouble(String key, double defaultValue) {
        String raw = JavaPluginBridge.getSettingJson(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Установить значение настройки (и перерисовать экран, если он открыт). */
    public void setSetting(String key, Object value) {
        JavaPluginBridge.setSetting(key, value, false);
    }

    /** Установить значение и попросить движок уведомить {@link #onSettingChanged}. */
    public void setSettingAndNotify(String key, Object value) {
        JavaPluginBridge.setSetting(key, value, true);
    }

    public String exportSettings() {
        return JavaPluginBridge.exportSettings();
    }

    public void importSettings(String json) {
        JavaPluginBridge.importSettings(json, true);
    }

    // ---------- меню ----------

    /** Зарегистрировать пункт меню; возвращает его item_id. */
    public String addMenuItem(MenuItemBuilder builder) {
        if (builder == null) {
            return null;
        }
        return JavaPluginBridge.addMenuItem(builder.build(), builder.callback());
    }

    public void removeMenuItem(String itemId) {
        JavaPluginBridge.removeMenuItem(itemId);
    }

    // ---------- разрешения ----------

    public boolean hasPermission(String permission) {
        return JavaPluginBridge.hasPermission(permission);
    }

    public boolean checkPermission(String permission, String what) {
        return JavaPluginBridge.checkPermission(permission, what);
    }

    public boolean isUnsafeMode() {
        return JavaPluginBridge.isUnsafeMode();
    }

    // ---------- логирование ----------

    public void log(String message) {
        JavaPluginBridge.log("I", message);
    }

    public void logWarn(String message) {
        JavaPluginBridge.log("W", message);
    }

    public void logError(String message) {
        JavaPluginBridge.log("E", message);
    }

    public void logError(String message, Throwable t) {
        JavaPluginBridge.log("E", message + ": " + (t == null ? "" : t.toString()));
    }

    // ---------- потоки ----------

    public void runOnUiThread(Runnable runnable) {
        if (runnable != null) {
            AndroidUtilities.runOnUIThread(runnable);
        }
    }

    public void postDelayed(Runnable runnable, long delayMs) {
        if (runnable != null) {
            AndroidUtilities.runOnUIThread(runnable, delayMs);
        }
    }

    public void runAsync(Runnable runnable) {
        if (runnable != null) {
            WORKERS.execute(runnable);
        }
    }

    // ---------- файлы и сеть ----------

    /** Каталог плагина в filesDir (создаётся при первом обращении). */
    public File getPluginDir() {
        File dir = new File(getContext().getFilesDir(), "java_plugins/" + pluginId);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Общий каталог плагинов (filesDir/plugins). */
    public File getPluginsDir() {
        return JavaPluginsController.getInstance().getPluginsDir();
    }

    /**
     * GET-запрос строкой. Требует разрешения {@code network}; при отсутствии
     * возвращает null и пишет отказ в журнал. Блокирующий — зовите из
     * {@link #runAsync(Runnable)}.
     */
    public String httpGet(String url) {
        return httpGet(url, null);
    }

    public String httpGet(String url, Map<String, String> headers) {
        String result = httpRequest("GET", url, null, headers);
        return DENIED.equals(result) ? null : result;
    }

    /**
     * POST-запрос строкой. Тело отправляется как есть; тип содержимого по
     * умолчанию {@code text/plain; charset=utf-8} — переопределяется заголовком
     * {@code Content-Type} в {@code headers}. Требует разрешения {@code network};
     * при отсутствии возвращает null. Блокирующий — зовите из
     * {@link #runAsync(Runnable)}.
     */
    public String httpPost(String url, String body) {
        return httpPost(url, body, null);
    }

    public String httpPost(String url, String body, Map<String, String> headers) {
        String result = httpRequest("POST", url, body, headers);
        return DENIED.equals(result) ? null : result;
    }

    /**
     * Асинхронный GET. Требует разрешения {@code network}. Колбэк вызывается в
     * UI-потоке: {@code (body, null)} при успехе либо {@code (null, причина)}
     * при отказе или ошибке.
     */
    public void httpGetAsync(String url, HttpCallback callback) {
        httpGetAsync(url, null, callback);
    }

    public void httpGetAsync(String url, Map<String, String> headers, HttpCallback callback) {
        httpRequestAsync("GET", url, null, headers, callback);
    }

    /** Асинхронный POST. См. {@link #httpGetAsync(String, HttpCallback)}. */
    public void httpPostAsync(String url, String body, HttpCallback callback) {
        httpPostAsync(url, body, null, callback);
    }

    public void httpPostAsync(String url, String body, Map<String, String> headers, HttpCallback callback) {
        httpRequestAsync("POST", url, body, headers, callback);
    }

    /** Колбэк асинхронных HTTP-запросов; вызывается в UI-потоке. */
    public interface HttpCallback {
        /**
         * @param body  ответ, если запрос удался, иначе null
         * @param error причина отказа, если запрос не удался, иначе null
         */
        void onResponse(String body, String error);
    }

    private static final String DENIED = "\u0000denied";

    private void httpRequestAsync(String method, String url, String body,
                                  Map<String, String> headers, HttpCallback callback) {
        if (callback == null) {
            return;
        }
        runAsync(() -> {
            String result = httpRequest(method, url, body, headers);
            final String value;
            final String error;
            if (DENIED.equals(result)) {
                value = null;
                error = "network permission missing";
            } else if (result == null) {
                value = null;
                error = "request failed";
            } else {
                value = result;
                error = null;
            }
            runOnUiThread(() -> callback.onResponse(value, error));
        });
    }

    private String httpRequest(String method, String url, String body, Map<String, String> headers) {
        if (!checkPermission(app.exteraless.plugins.PluginPermissions.NETWORK, "http " + method)) {
            return DENIED;
        }
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", "DuckDuckGram-JavaPlugin/" + pluginVersion);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            if (body != null) {
                connection.setDoOutput(true);
                boolean hasType = headers != null && headers.containsKey("Content-Type");
                if (!hasType) {
                    connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                }
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes("UTF-8"));
                }
            }
            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return null;
            }
            try (java.io.InputStream in = stream) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                return out.toString("UTF-8");
            }
        } catch (Throwable t) {
            logError("HTTP " + method + " failed for " + url, t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ---------- внутреннее ----------

    private static String fromJsonString(String raw, String defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.endsWith("\"")) {
            try {
                return new org.json.JSONArray("[" + trimmed + "]").optString(0, defaultValue);
            } catch (org.json.JSONException e) {
                return defaultValue;
            }
        }
        return raw;
    }

    /** Совместимость: SDK-версия Java-движка. */
    public String getSdkVersion() {
        return PluginsConstants.JAVA_SDK_VERSION;
    }

    /**
     * Билдер пункта меню. Значения {@code menu_type} — из
     * {@link PluginsConstants.MenuItemTypes}.
     */
    public static final class MenuItemBuilder {

        private final org.json.JSONObject json = new org.json.JSONObject();
        private PluginMenuItemCallback callback;

        private MenuItemBuilder(String menuType) {
            put("menu_type", menuType);
        }

        public static MenuItemBuilder messageContext() {
            return new MenuItemBuilder(PluginsConstants.MenuItemTypes.MESSAGE_CONTEXT_MENU);
        }

        public static MenuItemBuilder drawer() {
            return new MenuItemBuilder(PluginsConstants.MenuItemTypes.DRAWER_MENU);
        }

        public static MenuItemBuilder main() {
            return new MenuItemBuilder(PluginsConstants.MenuItemTypes.MAIN_MENU);
        }

        public static MenuItemBuilder chatAction() {
            return new MenuItemBuilder(PluginsConstants.MenuItemTypes.CHAT_ACTION_MENU);
        }

        public static MenuItemBuilder profileAction() {
            return new MenuItemBuilder(PluginsConstants.MenuItemTypes.PROFILE_ACTION_MENU);
        }

        public static MenuItemBuilder of(String menuType) {
            return new MenuItemBuilder(menuType);
        }

        public MenuItemBuilder id(String itemId) {
            put("item_id", itemId);
            return this;
        }

        public MenuItemBuilder text(String text) {
            put("text", text);
            return this;
        }

        public MenuItemBuilder subtext(String subtext) {
            put("subtext", subtext);
            return this;
        }

        public MenuItemBuilder icon(String icon) {
            put("icon", icon);
            return this;
        }

        /** MVEL-условие видимости пункта (например {@code "message != null"}). */
        public MenuItemBuilder condition(String condition) {
            put("condition", condition);
            return this;
        }

        public MenuItemBuilder priority(int priority) {
            put("priority", priority);
            return this;
        }

        public MenuItemBuilder onClick(PluginMenuItemCallback callback) {
            this.callback = callback;
            return this;
        }

        private void put(String key, Object value) {
            if (value == null) {
                return;
            }
            try {
                json.put(key, value);
            } catch (org.json.JSONException ignored) {
            }
        }

        public String build() {
            return json.toString();
        }

        PluginMenuItemCallback callback() {
            return callback;
        }
    }

    /** Ссылка на модель плагина (для экранов, которые её ждут). */
    public Plugin getPluginModel() {
        return JavaPluginsController.getInstance().getPlugin(pluginId);
    }
}
