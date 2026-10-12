package app.exteraless.plugins;

import android.content.Context;
import android.view.View;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;

/**
 * Общий контракт движка плагинов. Реализуется {@link PythonPluginsEngine} и
 * {@link JavaPluginsEngine}; {@link PluginsController} маршрутизирует вызовы
 * по полю {@link Plugin#engine} конкретного плагина, а не по типу движка.
 *
 * Контракты входа/выхода намеренно совпадают с Python-движком:
 *  - {@link #readMetadataJson(String)} → {@code {"ok":true,"meta":{...}}} или
 *    {@code {"ok":false,"error":...}};
 *  - {@link #loadPlugin(Plugin)} → {@code {"ok":true,"has_settings":bool,"handles":{...}}};
 *  - хуки возвращают {@link HookResult}.
 * Так экран списка плагинов, лист установки и экран настроек работают с обоими
 * движками одинаково.
 */
public interface PluginEngine {

    /** Имя движка в карте {@link PluginsController#getEngines()}: "python" или "java". */
    String getEngineName();

    /** Готов ли движок принимать плагины (для Python — поднят ли интерпретатор). */
    boolean isReady();

    /** Прочитать метаданные файла, не исполняя плагин. */
    String readMetadataJson(String path);

    /** Статический разбор возможностей файла (может быть null). */
    String scanCapabilitiesJson(String path);

    /** Загрузить плагин. Возвращает JSON-отчёт движка. */
    String loadPlugin(Plugin plugin);

    /** Выгрузить плагин (onUnload + снятие хуков). */
    void unload(Plugin plugin);

    /** Выгрузить по id. */
    void unloadPlugin(String pluginId);

    /** Полная деинсталляция: очистка ресурсов, специфичных движку. */
    void uninstallPlugin(String pluginId);

    /** Событие приложения: app_start/app_stop/app_pause/app_resume. */
    void callAppEvent(String pluginId, String event);

    HookResult callSendMessageHook(String pluginId, int account, Object params);

    HookResult callPreRequestHook(String pluginId, int account, String requestName, Object request);

    HookResult callPostRequestHook(String pluginId, int account, String requestName,
                                   Object response, Object error);

    HookResult callUpdateHook(String pluginId, int account, String updateName, Object update);

    HookResult callUpdatesHook(String pluginId, int account, String containerName, Object updates);

    /** JSON-список элементов настроек с текущими значениями или null/[] при их отсутствии. */
    String getSettingsJson(String pluginId);

    /** Сбросить кэш JSON-списка настроек (перерисовка экрана настроек). */
    void invalidateSettingsJson(String pluginId);

    /** Произвольная строка настроек: View/UItem/CustomSetting. */
    Object getSettingsCustomContent(String pluginId, String viewId, Context context);

    /** Клик/долгий клик по произвольной строке настроек. */
    boolean dispatchSettingsCustomClick(String pluginId, UItem item, View view, boolean longClick);

    /** Значение настройки изменилось в UI: jsonValue — JSON-представление значения. */
    void notifySettingChanged(String pluginId, String key, String jsonValue);

    /** Нажатие элемента с callback_id. */
    void dispatchSettingClick(String pluginId, String callbackId, View view);

    /** Зеркало настроек устарело (запись извне UI). */
    void invalidateSettingsMirror(String pluginId);

    /** Выполнить работу на потоке движка (у Python — единственный поток интерпретатора). */
    void runOnEngine(Runnable runnable);

    /**
     * Открыть экран настроек плагина. Тип параметра — базовый
     * {@code com.exteragram.messenger.plugins.Plugin}: так сигнатуру разделяет и
     * Python-движок (метод объявлен в шиме и переопределяется dex-модулями), и
     * Java-движок.
     */
    void openPluginSettings(com.exteragram.messenger.plugins.Plugin plugin,
                            BaseFragment fragment, String targetSetting);

    /** Остановить движок (для Python — no-op, интерпретатор не гасится). */
    void shutdown();
}
