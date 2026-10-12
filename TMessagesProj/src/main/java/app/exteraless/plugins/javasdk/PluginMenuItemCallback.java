package app.exteraless.plugins.javasdk;

import java.util.Map;

/**
 * Колбэк пункта меню Java-плагина. Аналог Python-колбэка {@code on_click(context)}:
 * движок зовёт его при выборе пункта, передавая контекст меню (выделенное сообщение,
 * диалог, аккаунт и т.п. — набор ключей зависит от типа меню).
 */
@FunctionalInterface
public interface PluginMenuItemCallback {
    void onClick(Map<String, Object> context);
}
