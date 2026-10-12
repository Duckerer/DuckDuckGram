# Java-плагины DuckDuckGram — SDK

Документация по Java-SDK плагинов DuckDuckGram. Сухая справка: контракты,
формат архива, схема `plugin.json`, полный справочник API.

Исходный код SDK: `exteraless/TMessagesProj/src/main/java/app/exteraless/plugins/javasdk/`.

Версия Java-SDK: `1.0.0` (`PluginsConstants.JAVA_SDK_VERSION`).

---

## 1. Обзор

Java-плагин — это код на Java, который приложение исполняет в своём процессе и
вызывает по хукам. Плагин оформляется как zip-архив (`.jar` / `.jplugin`), в
корне которого лежат:

| Вход архива | Обязателен | Назначение |
|---|---|---|
| `classes.dex` | да | dex-байткод классов плагина (сборка `d8`) |
| `plugin.json` | да | метаданные: id, имя, главный класс, версия SDK, разрешения |

Класс из `main_class` должен наследовать `app.exteraless.plugins.javasdk.BasePlugin`
и иметь публичный конструктор без аргументов.

Отличия от Python-SDK: нет интерпретатора, нет AST-сканирования объявленных
возможностей (источник возможностей — только `permissions` в `plugin.json`),
вызовы обёрнуты в тот же watchdog и помечаются владельцем через `PluginRuntime`.

---

## 2. Формат и установка

### 2.1. Расширения файлов

| Расширение | Назначение |
|---|---|
| `.jplugin` | переносимое расширение для передачи файла (Telegram, файловый менеджер) |
| `.jar` | расширение, в которое файл переименовывается при установке/хранении |
| `.py`, `.plugin`, `.elyx`, `.eaf` | при распознавании файла относятся к Python-плагинам; Java-плагина с такими расширениями не бывает |

При установке архив копируется в `filesDir/java_plugins/<id>.jar`. Расширение
нормализуется в `.jar` независимо от исходного (`.jplugin` или `.jar`).

### 2.2. Загрузка кода

`classes.dex` читается из архива в память и грузится:

- API 26+ — `InMemoryDexClassLoader` (dex в памяти, без dexopt; ART не позволяет
  грузить dex напрямую из перезаписываемого каталога приложения);
- API 24–25 — `DexClassLoader` с oat-кэшем в `code_cache`.

Родительский загрузчик — загрузчик классов приложения, поэтому плагин видит все
классы SDK (`app.exteraless...`) и любые системные/прикладные классы.

### 2.3. Сборка плагина

Шаги вручную:

```
# classpath: android.jar (SDK) + скомпилированные классы приложения
# (build/intermediates/javac/debug/compileDebugJavaWithJavac/classes)
javac --release 11 -classpath "<android.jar>:<app_classes>" -d out/classes @sources.txt

# dex только классов плагина
d8 --min-api 21 --lib <android.jar> --output out/ out/classes/**/*.class

# упаковка: classes.dex + plugin.json
( cd out && zip -j plugin.jplugin classes.dex plugin.json )
```

Примечания:

- Классы SDK **не** включаются в dex (`--lib` для d8); они есть в рантайме.
- d8 может вывести предупреждения вида «Type BasePlugin was not found» при
  desugaring лямбд — они не влияют на результат: типы разрешаются
  родительским загрузчиком в рантайме.
- `plugin.json` должен лежать в корне архива (не в подпапке).

---

## 3. plugin.json

Полный перечень полей. Все поля, кроме `id`, необязательные.

| Поле | Тип | Обязательное | Описание |
|---|---|---|---|
| `id` | string | да | Идентификатор плагина |
| `name` | string | нет | Отображаемое имя; по умолчанию равно `id` |
| `description` | string | нет | Описание (показывается в списке/карточке) |
| `author` | string | нет | Автор |
| `version` | string | нет | Версия плагина; по умолчанию `"1.0"` |
| `icon` | string | нет | Иконка (StickerPackShortName/index, например `exteraPlugins/1`) |
| `main_class` | string | нет* | FQCN класса-наследника `BasePlugin`; без него плагин не загрузится |
| `sdk_version` | string | нет | Ограничение на версию Java-SDK (см. §3.2) |
| `app_version` | string | нет | Ограничение на версию приложения (см. §3.2) |
| `beta` | boolean | нет | Признак беты; по умолчанию `false` |
| `requirements` | array[string] | нет | Требования; **информационные**, движок их не проверяет |
| `requires` | object | нет | Зависимости: `id` → массив `[min_version, url]`, оба элемента могут отсутствовать. **Проверяются перед загрузкой** (см. §3.4) |
| `permissions` | array[string] | нет | Запрашиваемые разрешения (ключи из §5.6) |

\* поле `main_class` обязательно для загрузки, но не для чтения метаданных и установки.

### 3.1. id: правила валидации

`id` проверяется регулярным выражением:

```
^[A-Za-z][A-Za-z0-9_-]{1,31}$
```

- первый символ — латинская буква;
- допустимы латинские буквы, цифры, `_`, `-`;
- точки, слэши, кириллица и пробелы **запрещены**;
- длина — от 2 до 32 символов.

Несоответствие отклоняет установку с ошибкой «invalid plugin id».
Рекомендация: символическое имя без доменных суффиксов, например `hello-java`.

### 3.2. Версии и ограничения

Формат ограничения: `[оператор]версия`. Операторы: `>=`, `<=`, `==`, `>`, `<`.
Без оператора подразумевается `>=`. Сравнение — по числовым компонентам,
разделитель `.` или `-` (например, `1.0` и `1.0.0` эквивалентны).

- `sdk_version` сверяется с версией Java-SDK приложения (`1.0.0`);
- `app_version` сверяется с версией приложения (`BuildVars.BUILD_VERSION_STRING`,
  на текущей сборке `12.10.6`).

Для Java-плагинов сверка `sdk_version` идёт против линейки Java (`1.0.0`), а не
против Python-SDK (`1.4.5.3`).

Ограничение без единой цифры (пустое, `"null"` и т.п.) считается не заданным и
не проверяется.

### 3.3. permissions

Источник возможностей плагина. Сканирование исходников (как для Python) не
проводится: возможности = ровно те ключи, что объявлены в `permissions`.
Полный список ключей и их значение — в §5.6.

### 3.4. Зависимости (`requires`)

Перед загрузкой плагина движок проверяет каждую запись `requires`
(`PluginsController.checkRequiredPlugins`):

- зависимый плагин **установлен**;
- он **включён**;
- его версия не старше `min_version` (если задан).

При несоблюдении загрузка отклоняется с сообщением вида
`requires plugin <id> (not installed)` / `(disabled)` / `>= <version>`.
Поле `requirements` (массив строк) только отображается, на загрузку не влияет.

---

## 4. main_class

```
public class MyPlugin extends app.exteraless.plugins.javasdk.BasePlugin {
    public MyPlugin() {           // обязателен public no-arg конструктор
    }
}
```

- Класс загружается по `main_class`; если класс не наследует `BasePlugin` —
  загрузка отклоняется.
- Метод `attach(...)` вызывается движком и **не переопределяется**.
- Код плагина исполняется с пометкой владельца в `PluginRuntime`; вызовы
  `JavaPluginBridge` извне кода плагина отвергаются. Все вызовы обёрнуты
  watchdog-таймером; исключение в любом колбэке не роняет приложение —
  фиксируется в журнал плагина.
- Пока поток помечен как «исполняется плагин», включён `PluginSinkGate`: он
  перехватывает низкоуровневые Java-стоки (сеть, процессы, нативные
  библиотеки, разрешение классов, отправку сообщений, оверлеи, доступ к чужим
  prefs) и требует соответствующее разрешение. Это не полная изоляция — см.
  §8.

---

## 5. BasePlugin — справочник API

### 5.1. Метаданные

| Метод | Возвращает |
|---|---|
| `String getPluginId()` | id плагина |
| `String getPluginName()` | имя |
| `String getPluginDescription()` | описание |
| `String getPluginAuthor()` | автор |
| `String getPluginVersion()` | версия (по умолчанию `"1.0"`) |
| `String getSdkVersion()` | версия Java-SDK (`"1.0.0"`) |
| `Plugin getPluginModel()` | объект `app.exteraless.plugins.Plugin` |
| `Context getContext()` | application context |

### 5.2. Жизненный цикл

| Метод | Когда вызывается |
|---|---|
| `void onPluginLoad()` | сразу после создания экземпляра; здесь регистрируются хуки/меню |
| `void onPluginUnload()` | при выключении, удалении или выгрузке плагина |
| `void onAppEvent(String event)` | событие приложения; `app_start`, `app_stop`, `app_pause`, `app_resume` |

`onAppEvent` вызывается для всех загруженных плагинов, **переопределивших** метод
(определяется рефлексией). Отдельная регистрация не нужна.

### 5.3. Хуки

#### HookResult

Возвращаемое значение всех хуков.

| Значение | Семантика |
|---|---|
| `HookResult.DEFAULT` | «не вмешиваюсь», цепочка продолжается |
| `new HookResult(HookResult.Strategy.CANCEL)` | отменить действие (например, не отправлять сообщение) |
| `new HookResult(HookResult.Strategy.MODIFY, value)` | подменить объект |
| `new HookResult(HookResult.Strategy.MODIFY_FINAL, value)` | подменить и прекратить цепочку |

Вспомогательные методы: `isCancel()`, `isFinal()`, `replacement(Class<T>)`
(возвращает подставленное значение, если оно экземпляр заданного класса,
иначе `null`).

#### Методы-хуки (переопределяются)

| Метод | Аргументы | Вызывается |
|---|---|---|
| `HookResult onSendMessageHook(int account, Object params)` | `account`; `params` — `SendMessagesHelper.SendMessageParams` | при отправке исходящего сообщения; `CANCEL` — не отправлять |
| `HookResult preRequestHook(int account, String requestName, Object request)` | имя TL-метода; объект запроса | перед отправкой TL-запроса |
| `HookResult postRequestHook(int account, String requestName, Object response, Object error)` | ответ и ошибка | после ответа на TL-запрос |
| `HookResult onUpdateHook(int account, String updateName, Object update)` | имя `TL_update*`; сам апдейт | одиночный апдейт |
| `HookResult onUpdatesHook(int account, String containerName, Object updates)` | имя `TL_updates*`; контейнер | контейнер апдейтов |

Правила доставки:

- `onSendMessageHook` — только после регистрации `registerSendMessageHook(...)`;
- `preRequestHook`/`postRequestHook` — только после регистрации
  `registerRequestHook(...)` / `registerRequestHookMatch(...)`;
- `onUpdateHook`/`onUpdatesHook` — для всех загруженных плагинов,
  переопределивших метод (рефлексия).

#### Регистрация хуков

| Метод | Эффект |
|---|---|
| `registerSendMessageHook()` | регистрация исходящих, приоритет 0 |
| `registerSendMessageHook(int priority)` | то же с приоритетом; выше приоритет — раньше вызов |
| `registerRequestHook(String requestName, int priority)` | хукать TL-метод по точному имени |
| `registerRequestHookMatch(String substring, int priority)` | хукать TL-методы по совпадению подстроки в имени |
| `unregisterSendMessageHook()` | снять регистрацию |
| `unregisterRequestHook(String requestName)` | снять регистрацию по имени |

Вызывать в `onPluginLoad()`. Типы и объекты аргументов — внутренние типы
приложения; работайте с ними как с чёрным ящиком.

Порядок вызовов: по приоритету, затем по времени регистрации. `CANCEL`
прерывает цепочку немедленно; `MODIFY_FINAL` — после применения подмены.

### 5.4. Настройки

#### Построение экрана

| Метод | Назначение |
|---|---|
| `SettingsBuilder createSettings()` | вернуть описание экрана настроек; `null`/пусто — экрана нет |
| `String createSettingsJson()` | готовый JSON-список строк (дефолт: вызывает `createSettings()` и сериализует) |

Движок вызывает `createSettingsJson()` при старте и при обновлении. Текущие
сохранённые значения подмешиваются в поле `value` строк, у которых задан `key`
(override с подписью). Экран рисует `PluginSettingsActivity`, общий с Python-плагинами.

#### Колбэки

| Метод | Когда вызывается |
|---|---|
| `void onSettingChanged(String key, Object value)` | пользователь изменил значение строки с `key` (switch/selector/input/edittext) |
| `void onSettingClick(String callbackId, View view)` | нажатие строки с `callback_id` |
| `void onSettingLongClick(String callbackId, View view)` | долгое нажатие строки с `long_callback_id` |
| `Object createCustomSettingView(String viewId, Context ctx)` | отрисовка строки типа `custom` с `view_id` |

#### Чтение/запись значений

| Метод | Описание |
|---|---|
| `String getString(String key, String defaultValue)` | строковое значение (JSON-строка снимает кавычки) |
| `boolean getBoolean(String key, boolean defaultValue)` | `"true"`/`"false"` |
| `int getInt(String key, int defaultValue)` | целое (допустимо дробное с приведением) |
| `double getDouble(String key, double defaultValue)` | дробное |
| `void setSetting(String key, Object value)` | сохранить значение; без уведомления |
| `void setSettingAndNotify(String key, Object value)` | сохранить и уведомить `onSettingChanged` |
| `String exportSettings()` | все настройки JSON-объектом |
| `void importSettings(String json)` | импортировать все настройки (с уведомлением) |

Доступны только из кода плагина (владелец определяется по `PluginRuntime`).

#### SettingsBuilder

Построение списка строк: `new SettingsBuilder().header("...").add(row).build()`.

Методы `SettingsBuilder`: `add(SettingRow)`, `header(String)`, `divider()`,
`divider(String text)`, `build()`.

##### Общие свойства строк (`SettingRow`)

Любая строка наследует: `subtext(String)`, `icon(String)`, `accent(boolean)`,
`red(boolean)`, `onClick(String callbackId)`, `onLongClick(String callbackId)`,
`linkAlias(String)`, `subPage(SettingsBuilder)` (доступна только для `text`/`custom`).
Поля в JSON: `type`, `text`, `subtext`, `icon`, `accent`, `red`, `callback_id`,
`long_callback_id`, `link_alias`, `sub_page`.

Итоговый JSON — массив объектов; по нему напрямую рисует UI.

##### Типы строк

| Класс | `type` | Дополнительные поля | Поведение |
|---|---|---|---|
| `Header.of(text)` | `header` | `text` | заголовок секции |
| `Divider.of([text])` | `divider` | `text` | разделитель |
| `Switch.of(key, text, def)` | `switch` | `key`, `value`, `subtext`, `icon` | тумблер; изменение сохраняется и уведомляет `onSettingChanged` |
| `Input.of(key, text, def)` | `input` | `key`, `value`, `hint`, `subtext`, `max_length`, `multiline` | однострочное поле; диалог ввода |
| `Input.multiline(key, hint, def)` | `edittext` | то же | многострочное поле (type `edittext`) |
| `Selector.of(key, text, def, items...)` | `selector` | `key`, `value` (индекс), `items` | выбор радиокнопками из списка |
| `Slider.of(key, text, def, min, max, step)` | `slider` | `key`, `value`, `min`, `max`, `step`, `integral` | слайдер |
| `Text.of(text)` | `text` | `text`, `callback_id`, `sub_page`, `accent`, `red` | строка-кнопка; callback или подстраница |
| `Custom.of(viewId)` | `custom` | `view_id`, `callback_id`, `sub_page` | произвольный контент |

Особенности:

- `switch`/`selector`/`input`/`edittext` требуют `key` — именно по нему значения
  хранятся и подмешиваются;
- `text` и `custom` без `callback_id` и без `sub_page` — «мёртвая» строка;
- `sub_page` открывает вложенный экран из массива строк того же формата
  (для `text` и `custom`);
- при нажатии на `custom` движок сначала зовёт `dispatchSettingsCustomClick`
  (для `CustomSetting`-фабрики), иначе `onSettingClick`.

##### Произвольный контент (`custom`)

`createCustomSettingView(viewId, ctx)` может вернуть:

- `android.view.View`;
- `org.telegram.ui.Components.UItem`;
- `app.exteraless.plugins.models.CustomSetting` (обёртка над `View`/`UItem`
  либо фабрика `CustomSetting.Factory`).

Для фабрики доступны `onClick`/`onLongClick` (`dispatchSettingsCustomClick`).
Есть Java-конструкторы без Python-колбэков: `CustomSetting(View)`,
`CustomSetting(UItem)`, `CustomSetting(Factory<?>)` — клики по вью обрабатывает
его собственный слушатель, а поведение фабрики — её `onClick`/`onLongClick`.
Полные конструкторы с `PyObject`-колбэками остаются для Python-плагинов.

### 5.5. Меню

#### Регистрация

`String addMenuItem(MenuItemBuilder)` — регистрирует пункт и возвращает `item_id`;
`void removeMenuItem(String itemId)` — снимает его.

#### Типы меню (import из `MenuItemBuilder`)

| Фабрика | `menu_type` | Где появляется |
|---|---|---|
| `MenuItemBuilder.messageContext()` | `message_context_menu` | контекстное меню сообщения (долгое нажатие на сообщение) |
| `MenuItemBuilder.drawer()` | `drawer_menu` | боковая шторка |
| `MenuItemBuilder.main()` | `main_menu` | меню «⋮» главного экрана |
| `MenuItemBuilder.chatAction()` | `chat_action_menu` | подменю «⋮» чата (swipe-back слой) |
| `MenuItemBuilder.profileAction()` | `profile_action_menu` | подменю «⋮» профиля |

#### Поля пункта

| Метод | Поле JSON | Значение |
|---|---|---|
| `id(String)` | `item_id` | уникальный id пункта |
| `text(String)` | `text` | подпись |
| `subtext(String)` | `subtext` | подпись второго уровня |
| `icon(String)` | `icon` | иконка |
| `condition(String)` | `condition` | условие видимости (MVEL) |
| `priority(int)` | `priority` | порядок (выше — раньше) |
| `onClick(PluginMenuItemCallback)` | — | колбэк нажатия |

Если `text` не задан, в меню подставляется `item_id`.

#### Колбэк нажатия

```
interface PluginMenuItemCallback { void onClick(Map<String, Object> context); }
```

Контекст нажатия зависит от типа меню:

| Тип меню | Ключи контекста |
|---|---|
| `message_context_menu` | `message` (сообщение), `chat`/`chatId`, `user`/`userId`, `encryptedChat`, `dialog_id`, `account`, `fragment`, `context` |
| `drawer_menu` | `account` |
| `main_menu` | `account`, `fragment`, `context` |
| `chat_action_menu` | `account`, `dialog_id`, `fragment`, `context`, `chat`/`chatId`, `user`/`userId`, `encryptedChat` |
| `profile_action_menu` | `account`, `dialog_id`, `fragment`, `context`, `user`/`userId`, `chat`/`chatId` |

#### Условие видимости (`condition`)

Выражение MVEL, вычисляемое против контекста меню (см. выше). Пример:
`"message != null"`. Ошибка вычисления или небезопасное выражение (обращение
не только к контексту) — пункт скрывается, ошибка пишется в лог.

### 5.6. Разрешения

| Метод | Назначение |
|---|---|
| `boolean hasPermission(String permission)` | тихая проверка |
| `boolean checkPermission(String permission, String what)` | проверка с записью отказа в лог и журнал (что именно запрошено) |
| `boolean isUnsafeMode()` | включён ли unsafe-режим (обходит проверки) |

Разрешения выдаются пользователем на экране установки на основе `permissions`
из `plugin.json`. Внутреннее хранение — в prefs, namespaced как `java:<id>`.

Ключи (`app.exteraless.plugins.PluginPermissions`):

| Ключ | Что даёт |
|---|---|
| `ui` | меню, диалоги, бюллетени, экран настроек; выдаётся всегда, не спрашивается |
| `messages.read` | чтение апдейтов, диалогов и сообщений; post-request хуки |
| `messages.send` | отправка, редактирование, удаление сообщений; отмена исходящих |
| `network` | сетевые запросы из кода плагина |
| `files` | чтение/запись вне своего каталога |
| `intents` | перехват ссылок и интентов |
| `settings` | чтение/запись настроек приложения вне своих |
| `hooks` | Xposed-хуки и критические операции (полный контроль) |
| `native` | загрузка нативных библиотек (полный контроль) |

`hooks`/`native` — опасные: при уровне доступа плагина ниже «доверенного»
(`PluginTrustLevel`) они отзываются из эффективного набора; в `isolated`-режиме
остаётся только `ui`.

Гейт исполнения (`PluginSinkGate`) применяет эти разрешения не только к
методам `BasePlugin`, но и к части прямых Java-вызовов на помеченных потоках.
Гейт ставится при готовом хуке (Aliuhook/Xposed); без него остаётся только
Python-гейт.

| Сток | Требует | Иначе |
|---|---|---|
| `URL.openConnection`, `Socket.connect` | `network` | отказ |
| `Class.forName` сетевого класса (`java.net.*`, `javax.net.ssl.*`, `okhttp3.*`, `org.apache.http.*`, каналы) | `network` | отказ |
| `Runtime.load0`, `Runtime.loadLibrary0` | `native` | отказ |
| `Class.forName` загрузчика кода (`dalvik.system.*DexClassLoader`, `PathClassLoader`, `de.robv.android.xposed.*`, `java.security.KeyStore`, `android.security.keystore.*`) и конструкторы этих загрузчиков | `hooks` | отказ |
| `Runtime.exec`, `ProcessBuilder.start`/`startPipeline` | — | запрещено всегда |
| `ConnectionsManager.sendRequest`/`sendRequestSync` | `messages.read` (чтение) / `messages.send` (запись) / `hooks` (аккаунт, платежи, авторизация) — по имени TL-запроса | отказ |
| `SendMessagesHelper.sendMessage`/`prepareSendingMedia`/`Document`/`Photo` | `messages.send` | отказ |
| `WebView.loadUrl`/`postUrl`/`loadDataWithBaseURL`, `ImageLoader.loadHttpFile`, `MediaPlayer.setDataSource`, `DownloadManager.enqueue` (только для удалённых URL) | `network` | отказ |
| системные оверлеи (`WindowManagerImpl.addView` с system-window type) | `hooks` | отказ |
| прямой доступ к базе (`getDatabase`) | `messages.read` | пропуск + запись в журнал |
| prefs хранилища разрешений | — | запрещено всегда |
| `SharedPreferences` настроек другого плагина | `hooks` | отказ |
| `Class.forName` на `PluginPermissions`, `PluginGrantStore`, `PluginSinkGate`, `PluginsWatchdog`, `PluginRuntime`, `PluginAuditJournal`, `PluginDenialNotice`, контроллеры файлов/интентов/меню | — | запрещено всегда |

Отказ пишется в журнал плагина (см. `PluginDenialNotice`/`PluginAuditJournal`).
Что гейт **не** держит: `java.io.File`, рефлексию общего назначения, прочие
классы без явного запрета. Плагин уровня `hooks` может снять сами хуки гейта —
защиты от него нет. Поэтому изоляция плагина неполная: см. §8.

### 5.7. Логирование

| Метод | Уровень |
|---|---|
| `void log(String message)` | информация |
| `void logWarn(String message)` | предупреждение |
| `void logError(String message)` | ошибка |
| `void logError(String message, Throwable t)` | ошибка с исключением |

Записи попадают в журнал плагина (UI: список плагинов → плагин → журнал) и в
лог системы. Владелец записи приписывается из `PluginRuntime`.

### 5.8. Потоки

| Метод | Назначение |
|---|---|
| `void runOnUiThread(Runnable)` | выполнить на UI-потоке |
| `void postDelayed(Runnable, long delayMs)` | отложенно на UI-поток |
| `void runAsync(Runnable)` | выполнить в общем пуле (`max(2, CPU/2)` потоков) |

Движок вызывает колбэки на своих потоках; не блокируйте их — уводите тяжёлую
работу в `runAsync`.

### 5.9. Файлы

| Метод | Возвращает |
|---|---|
| `File getPluginDir()` | `filesDir/java_plugins/<id>` (создаётся при первом обращении) |
| `File getPluginsDir()` | общий `filesDir/java_plugins` |

По контракту доступ — только в свой каталог; за его пределами подразумевается
разрешение `files`. Принудительно на уровне движка это **не проверяется**: гейт
`PluginSinkGate` не перехватывает `java.io.File` и потоки (слишком горячий
путь). Методы `getPluginDir`/`getPluginsDir` работают в своём каталоге, но
произвольный файловый ввод-вывод в Java-плагине движок не ограничивает — это
осознанный компромисс (см. §8).

### 5.10. Сеть

| Метод | Описание |
|---|---|
| `String httpGet(String url)` | GET, тело ответа строкой (UTF-8); ошибки — null |
| `String httpGet(String url, Map<String, String> headers)` | GET с заголовками |
| `String httpPost(String url, String body)` | POST, тело как есть; ошибки — null |
| `String httpPost(String url, String body, Map<String, String> headers)` | POST с заголовками |
| `void httpGetAsync(String url, HttpCallback callback)` | GET без блокировки |
| `void httpGetAsync(String url, Map<String, String> headers, HttpCallback callback)` | GET с заголовками |
| `void httpPostAsync(String url, String body, HttpCallback callback)` | POST без блокировки |
| `void httpPostAsync(String url, String body, Map<String, String> headers, HttpCallback callback)` | POST с заголовками |

- Требуют разрешение `network`; без него синхронные возвращают `null`, а
  асинхронные отдают в колбэк `(null, "network permission missing")`.
- Синхронные **блокирующие**: таймауты connect 15 с, read 20 с. Зовите из
  `runAsync`.
- Асинхронные: запрос уходит в общий пул, колбэк вызывается в UI-потоке.
  Ошибка запроса — `(null, "request failed")`, успех — `(body, null)`.
- `body` для POST отправляется как есть; `Content-Type` по умолчанию
  `text/plain; charset=utf-8`, переопределяется заголовком `Content-Type`.
- User-Agent: `DuckDuckGram-JavaPlugin/<version плагина>`.

```
interface HttpCallback { void onResponse(String body, String error); }
```

---

## 6. Порядок исполнения и внутренние механизмы

- Владелец вызова (`PluginRuntime`) помечает, какой плагин сейчас исполняется;
  `JavaPluginBridge` отклоняет вызовы вне кода плагина.
- Все входы в код плагина обёрнуты watchdog (`PluginsWatchdog`), но он **не
  прерывает** зависший колбэк — прервать чужой поток на Android нечем
  (`Thread.stop` не работает). Поведение такое:
  - исключение в колбэке — только запись в журнал, плагин **не** отключается;
  - поток, не вернувший управление, помечается «не отвечает»
    (`frozenExecutions` + уведомление `pluginIsNotResponding`); пользователю
    показывается диалог «плагин не отвечает» с выбором: заглушить уведомление
    или **отключить плагин и перезапустить приложение** (`forceDisablePlugin`);
  - смерть процесса, когда плагин был активен, пишется маркером; при следующем
    старте после **двух** таких смертей плагин автоматически отключается
    (`recoverAfterCrash`, `CRASH_STRIKES_BEFORE_DISABLE = 2`).
  Практический вывод: оборвавшийся поток останавливается только перезапуском
  приложения, поэтому не блокируйте потоки движка — уводите работу в `runAsync`.
- `requires` проверяются перед загрузкой (см. §3.4).
- Safe mode: движок стартует, плагины не грузятся.
- `onPluginLoad` выполняется при загрузке; после него движок запрашивает
  `createSettingsJson()` (для флага «есть настройки») и определяет
  переопределённые методы рефлексией.
- При выключении/удалении зовётся `onPluginUnload` и снимаются регистрации.

---

## 7. Проверка SDK при установке

Установка отклоняется, если:

- `id` не проходит `^[A-Za-z][A-Za-z0-9_-]{1,31}$`;
- `sdk_version` не удовлетворяет ограничению против `1.0.0`;
- `app_version` не удовлетворяет ограничению против версии приложения;
- метаданные не читаются (нет `plugin.json`, нет `id`).

Загрузка дополнительно отклоняется, если:

- нет `main_class`;
- `main_class` не найден или не является `BasePlugin`;
- нет `classes.dex` в архиве;
- не выполнены `requires` (см. §3.4).

На экране установки показывается SHA-256 архива (для сверки с источником).
Это **не** проверка подписи: приложение не сверяет хэш автоматически и не
требует подписи архива — контроль на стороне пользователя.

---

## 8. Ограничения и примечания

- `id` без точек и слэшей (см. §3.1). Пакет-имя класса произвольное;
  ограничение накладывается только на `plugin.json > id`.
- В архиве ровно один dex-вход — `classes.dex` в корне.
- Типы аргументов хуков — внутренние классы приложения; контракт на их поля
  SDK не даёт (обращаться безопаснее через поддерживаемые методы `BasePlugin`).
  Типизированных обёрток аргументов в SDK нет — параметры хуков приходят как
  `Object`.
- Экран настроек и журнал — общие с Python-плагинами: схема строк совпадает.
- Архив не подписывается и не проверяется по хэшу: SHA-256 лишь показывается на
  экране установки, автоматической сверки нет.
- Изоляция неполная: гейт `PluginSinkGate` покрывает часть прямых Java-стоков
  (см. §5.6), но не `java.io.File`, не общую рефлексию и не снимается защитой от
  плагина с уровнем `hooks`. Разрешения гейтят стоки, а не дают песочницу;
  плагин — это код в доверенном процессе приложения.
- `requirements` не проверяются; `requires` проверяются (см. §3.4).

---

## 9. Пример

Минимальный плагин: логирует загрузку, добавляет пункт в контекстное меню
сообщения и регистрирует хук отправки.

```java
package com.example.demo;

import app.exteraless.plugins.javasdk.BasePlugin;

public class DemoPlugin extends BasePlugin {

    public DemoPlugin() {
    }

    @Override
    public void onPluginLoad() {
        log("demo loaded");

        addMenuItem(MenuItemBuilder.messageContext()
                .id("demo-hello")
                .text("Demo")
                .onClick(context -> log("clicked: " + context.get("message"))));

        registerSendMessageHook();
    }

    @Override
    public HookResult onSendMessageHook(int account, Object params) {
        log("send hook, account=" + account);
        return HookResult.DEFAULT;
    }

    @Override
    public void onPluginUnload() {
        log("demo unloaded");
    }
}
```

Сборка — `build.sh` (см. §2.3):
`javac --release 11` против `android.jar` и классов приложения, затем `d8`
(`--min-api 21 --lib android.jar`), и упаковка `classes.dex` + `plugin.json` в
zip с расширением `.jplugin`.