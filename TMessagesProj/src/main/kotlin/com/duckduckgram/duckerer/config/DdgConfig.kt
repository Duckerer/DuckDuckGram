package com.duckduckgram.duckerer.config

import android.content.SharedPreferences
import app.exteraless.notifications.NotificationsHelper
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem

/**
 * Настройки, которых нет ни в NekoConfig, ни в NaConfig, ни в OpenExteraConfig.
 *
 * Схема повторяет [app.exteraless.general.GeneralConfig]: те же SharedPreferences
 * (`nkmrcfg`), тот же [ConfigItem], та же ленивая загрузка. Ключи с префиксом `DDG`.
 *
 * ВАЖНО: сюда попадает только то, чего в форке действительно нет. Экраны DuckDuckGram
 * для остальных пунктов берут готовые ConfigItem форка — дублировать их нельзя,
 * иначе одна и та же настройка жила бы в двух местах и расходилась.
 */
object DdgConfig {

    /** Стиль меню: штатные 48dp и 16dp. */
    const val MENU_STYLE_DEFAULT = 0

    /** Стиль меню: те же иконки, но строка ниже и шрифт мельче. */
    const val MENU_STYLE_COMPACT = 1

    /** Стиль меню: узкие строки без иконок, как элементы списка. */
    const val MENU_STYLE_LIST = 2

    private val sync = Any()
    private val configs = ArrayList<ConfigItem>()

    @Volatile
    private var configLoaded = false

    @JvmStatic
    fun getPreferences(): SharedPreferences = NekoConfig.getPreferences()

    @JvmStatic
    fun getConfigTypes(): Map<String, Int> = configs.associate { it.key to it.type }

    // ---- Chats ----

    /** Подтверждение отправки: диалог перед уходом сообщения из поля ввода. */
    @JvmField
    val confirmSend = addConfig("DDGConfirmSend", ConfigItem.configTypeBool, false)

    /**
     * Отправка только при онлайне: личное сообщение собеседнику, который сейчас
     * офлайн, не уходит, а встаёт в очередь и отправляется, как только он появится
     * в сети. Работает для одиноких приватных сообщений (текст или медиа).
     */
    @JvmField
    val sendOnlyOnline = addConfig("DDGSendOnlyOnline", ConfigItem.configTypeBool, false)

    /**
     * Дизайн пузырей: индекс выбора (0=обычный, 1=компактный, 2=крупный).
     * Значение пишется в SharedConfig.bubbleRadius — рендер пузырей (ChatMessageCell)
     * читает его прямо при отрисовке, поэтому трогать саму отрисовку не нужно.
     */
    @JvmField
    val bubbleDesign = addConfig("DDGBubbleDesign", ConfigItem.configTypeInt, 0)

    /** Отключить крупные эмодзи в поле ввода. */
    @JvmField
    val disableBigEmoji = addConfig("DDGDisableBigEmoji", ConfigItem.configTypeBool, false)

   /** Точный таймстамп с миллисекундами в подписи времени. */
    @JvmField
    val millisecondTimestamp = addConfig("DDGMillisecondTimestamp", ConfigItem.configTypeBool, false)

    /**
     * Скрывать время в пузырях сообщений.
     *
     * Галочки прочтения не трогаются. Реализация использует штатный путь
     * «нет времени»: в ChatMessageCell ветка построения подписи времени
     * проверяет флаг, пустая строка убирает и текст, и зарезервированную под
     * него ширину пузыря.
     */
    @JvmField
    val hideMessageTime = addConfig("DDGHideMessageTime", ConfigItem.configTypeBool, false)

   /** Подсветка всех непрочитанных чатов. */
    @JvmField
    val highlightAllChats = addConfig("DDGHighlightAllChats", ConfigItem.configTypeBool, false)

    /**
     * Сворачивание повторов: идущие подряд одинаковые сообщения одного
     * отправителя показываются одним пузырём (новейшим), а в подписи времени
     * добавляется «×N». Свои (исходящие) не сворачиваются. Одинаковыми считаются
     * повторяющийся текст без медиа, а также стикеры/эмодзи/dice с тем же
     * содержимым. Спрятанные копии остаются настоящими сообщениями — скрытие
     * только в списке (те же 1px-строки, что у скрытых сообщений AyuGram).
     */
    @JvmField
    val collapseDuplicateMessages = addConfig("DDGCollapseDuplicateMessages", ConfigItem.configTypeBool, false)

    /**
     * Пресет градиента цвета счётчика повторов: индекс в [COLLAPSE_GRADIENTS].
     * При 2 повторах цвет совпадает с началом пары, при 12+ — с концом, между
     * ними — плавный переход (ColorUtils.blendARGB).
     */
    @JvmField
    val collapseGradient = addConfig("DDGCollapseGradient", ConfigItem.configTypeInt, 0)

    /**
     * Пары цветов [start, end] для счётчика повторов. Индексы совпадают со
     * значениями [collapseGradient] и строками в strings_ddg.xml (DDGCollapseGradient*).
     * Начальный цвет всегда дефолтный цвет времени (входящего/исходящего),
     * конечный — выбранный цвет из палитры удалённых сообщений (как в AyuMoments).
     */
@JvmField
    val COLLAPSE_GRADIENT_ENDS: IntArray = intArrayOf(
        0xFFFF0000.toInt(),
        0xFFDC2626.toInt(),
        0xFFDB2777.toInt(),
        0xFFC026D3.toInt(),
        0xFF9333EA.toInt(),
        0xFF4F46E5.toInt(),
        0xFF2563EB.toInt()
    )

    /**
     * Быстрый шеринг медиа: удержание пузыря открывает недавние чаты поверх него.
     *
     * Значение по умолчанию — включено: кнопка и жест в форке работали всегда, и
     * выключатель должен вести себя как выключатель, а не как включение того, чего
     * раньше не было.
     */
    @JvmField
    val quickShareMedia = addConfig("DDGQuickShareMedia", ConfigItem.configTypeBool, true)

   /** Принудительное копирование: поле ввода ведёт себя как копипаст. */
    @JvmField
    val forceCopy = addConfig("DDGForceCopy", ConfigItem.configTypeBool, false)

    /** Пропуск undo-таймера: удалить без подтверждения. */
    @JvmField
    val skipUndoTimer = addConfig("DDGSkipUndoTimer", ConfigItem.configTypeBool, false)

    /** Сохранение оригинальных имён файлов при скачивании. */
    @JvmField
    val keepOriginalFileNames = addConfig("DDGKeepOriginalFileNames", ConfigItem.configTypeBool, false)

    // ---- Media ----

    /** Рестайленный аудиоплеер с акцентным UI. */
    @JvmField
    val restyledPlayer = addConfig("DDGRestyledPlayer", ConfigItem.configTypeBool, false)

    // ---- Interface ----

    /** Компактный сайдбар. */
    @JvmField
    val compactSidebar = addConfig("DDGCompactSidebar", ConfigItem.configTypeBool, false)

    /** Стили меню: 0 — обычный, 1 — компактный, 2 — списком. */
    @JvmField
    val menuStyle = addConfig("DDGMenuStyles", ConfigItem.configTypeInt, 0)

    /** Встроенный браузер вместо системного. */
    @JvmField
    val builtInBrowser = addConfig("DDGBuiltInBrowser", ConfigItem.configTypeBool, false)

   /** Взаимные контакты в карточке пользователя. */
    @JvmField
    val mutualContacts = addConfig("DDGMutualContacts", ConfigItem.configTypeBool, false)

    /** Непрочитанные немьюты над закрепами. */
    @JvmField
    val unreadMutesAbovePins = addConfig("DDGUnreadMutesAbovePins", ConfigItem.configTypeBool, false)

    /**
     * Безлимит аккаунтов: без премиума доступна лишь половина слотов, флаг открывает
     * все свободные. См. {@code DrawerAccountPickerView.freeAccountsForAdd}.
     */
    @JvmField
    val unlimitedAccounts = addConfig("DDGUnlimitedAccounts", ConfigItem.configTypeBool, false)

    // ---- Avatars and folders ----

    /** Аватар всегда виден в шапке чата. */
    @JvmField
    val avatarInHeader = addConfig("DDGAvatarInHeader", ConfigItem.configTypeBool, false)

    /** Безлимитные папки. */
    @JvmField
    val unlimitedFolders = addConfig("DDGUnlimitedFolders", ConfigItem.configTypeBool, false)

    // ---- Profile ----
    //
    // Локальная подмена полей собственного профиля: номер, @юзернейм и ID меняются
    // только на этом устройстве и только для отображения. Серверу ничего не уходит,
    // выход и повторный вход возвращают настоящие значения. Пустая строка — поле
    // не подменяется.

    /** Локальный номер телефона вместо настоящего. */
    @JvmField
    val localPhone = addConfig("DDGLocalPhone", ConfigItem.configTypeString, "")

    /** Локальный @юзернейм вместо настоящего. */
    @JvmField
    val localUsername = addConfig("DDGLocalUsername", ConfigItem.configTypeString, "")

    /** Локальный ID аккаунта (только подпись в профиле). */
    @JvmField
    val localUserId = addConfig("DDGLocalUserId", ConfigItem.configTypeString, "")

    // ---- Privacy ----

    /** Экран приватности в свитчере приложений. */
    @JvmField
    val privacyScreen = addConfig("DDGPrivacyScreen", ConfigItem.configTypeBool, true)

    /**
     * Стример-режим: пока включён, переписка не попадает на демонстрацию экрана.
     *
     * Работает «механически», без всякой магии: приложение не получает событий о чужой
     * записи экрана, поэтому режим просто выключает показ содержимого в тех местах,
     * которые видны со стороны — уведомления (текст и аватар) и превью в списке чатов
     * (текст, подпись к медиа, черновик и миниатюры). Всё, что уже открыто и на экране,
     * режим не трогает: закрывать это — работа privacyScreen.
     */
    @JvmField
    val streamerMode = addConfig("DDGStreamerMode", ConfigItem.configTypeBool, false)

    /** Скрытие аватарок в списке чатов. */
    @JvmField
    val hideAvatarsInList = addConfig("DDGHideAvatarsInList", ConfigItem.configTypeBool, false)

    /** Скрытие информации о стикерах на фото. */
    @JvmField
    val hideStickerInfoOnPhotos = addConfig("DDGHideStickerInfoOnPhotos", ConfigItem.configTypeBool, false)

    // ---- Скрытие лишнего в интерфейсе ----

    /**
     * Не показывать буллетен с быстрыми тегами после пересылки сообщений в «Избранное».
     *
     * Порядок реализации взят из XF (Bulletin.LottieLayoutWithReactions.init()), там просто
     * стоит ранний return. У нас к нему добавлена проверка на null в hideReactionsDialog():
     * если init() ничего не создал, поле остаётся null, а XF в этом месте обращается к нему
     * без проверки — вылетает с NPE, как только буллетен пытаются скрыть.
     */
    @JvmField
    val disableShortcutTagActions = addConfig("DDGDisableShortcutTagActions", ConfigItem.configTypeBool, false)

    /**
     * Скрыть счётчик фотографий в просмотрщике (PhotoViewer).
     *
     * Гейт стоит в CaptionOverlay.updateShow(), поэтому счётчик не появляется ни при каком
     * сценарии открытия — ни по свайпу по альбому, ни при переходе из профиля/сообщения.
     */
    @JvmField
    val hidePhotoCounter = addConfig("DDGHidePhotoCounter", ConfigItem.configTypeBool, false)

    /**
     * Скрыть раздел подарков в профиле пользователя и чата.
     *
     * XF гасит три точки: строку подарков в шапке профиля, отдельную вкладку в медиа-разделе
     * и сам контейнер подарков, когда у пользователя их уже нет. Последнее важно: без него
     * после скрытия раздела остаётся пустой таб с надписью «пока нет подарков».
     */
    @JvmField
    val disableGifts = addConfig("DDGDisableGifts", ConfigItem.configTypeBool, false)

    /**
     * Не показывать рекламные промо-вставки Telegram.
     *
     * Спонсорные сообщения (запрос messages.getSponsoredMessages) в форке отключены жёстко,
     * условием `if (true) return null;` в MessagesController.getSponsoredMessages() — ещё до
     * DDG-слоя и независимо от настроек. Поэтому здесь остаётся только вторая половина
     * механики: промо-баннеры от help.getPromoData, которые прилетают на старте и
     * периодически показываются как диалог с рекламным каналом.
     *
     * Реализация отличается от XF: там ветка ответа на getPromoData просто не выполняется,
     * и это ломает две вещи. Первая — nextPromoInfoCheckTime не обновляется, и форк
     * дёргает сервер при каждом вызове checkPromoInfoInternal(). Вторая — внутри блока
     * показа лежит синхронизация серверных подсказок аккаунта (pendingSuggestions,
     * dismissedSuggestions, customPendingSuggestion) и lastCheckPromoInfoTime, к рекламе
     * отношения не имеющая: последний отвечает за подсказку «настройте email для входа».
     * Пропустив её, XF молча отключает checkEmailSuggestion() навсегда.
     *
     * Поэтому здесь состояние промо обрабатывается полностью (как при noDialog == false),
     * а скрывается только показ диалога — ранним выходом сразу после синхронизации.
     */
    @JvmField
    val disableAds = addConfig("DDGDisableAds", ConfigItem.configTypeBool, false)

    /**
     * Не предлагать поделиться своим телефоном в чате с пользователем.
     *
     * Промпт живёт в верхней панели чата и берётся из сохранённого флага диалога
     * "dialog_bar_share", то есть включается один раз и висит до ручного выключения.
     *
     * В XF гейтится только ветка `showShare` цепочки в updateTopPanel, и этого мало:
     * у не-контакта она всегда проигрывает следующей ветке `!show`, из-за чего панель
     * возвращается с другим текстом («поделиться контактом») и с меткой 2 — то есть
     * вместо предложения отдать номер предлагает отправить визитку в чат. У нас
     * выключаются обе ветки, но только для чатов, где промпт действительно предлагали
     * (showShare == true), так что поведение остальных диалогов не меняется. Заодно
     * сбрасывается метка addToContactsButton: пункт меню «Добавить в контакты» читает
     * именно её и при скрытой панели, и без сброса предложил бы неожиданное действие.
     *
     * Побочный эффект, который стоит знать: в тех же чатах пункт меню «Добавить в
     * контакты» пропадает совсем — при скрытой панели его единственное содержимое и
     * было предложением отдать номер, а не само добавление. В XF он остаётся, но
     * продолжает предлагать отдать номер, то есть настройка там попросту не работает.
     * Перерисовки на лету нет: панель обновится при ближайшем событии в чате.
     */
    @JvmField
    val disablePhoneSharePrompt = addConfig("DDGDisablePhoneSharePrompt", ConfigItem.configTypeBool, false)

    /**
     * Не показывать подсказку «подарите подарок ко дню рождения» над списком чатов.
     *
     * Подсказка живёт в dialogsHintCell и предлагает открыть GiftSheet. Само условие
     * показа оформлено как длинная цепочка &&, поэтому гейт добавлен последним звеном —
     * так же, как в XF, и состояние dismissedSuggestions при этом не трогается:
     * выключенная настройка не «съедает» саму подсказку, её всё ещё можно закрыть крестиком.
     */
    @JvmField
    val disableBirthdayContact = addConfig("DDGDisableBirthdayContact", ConfigItem.configTypeBool, false)

    // ---- Премиум-навязчивание ----
    //
    // Переключатели из XF, каждый — ровно одна точка правки в форке. Общее у них одно:
    // все гасят предложение, за которым стоит платная подписка, и ни одно из них не
    // трогает саму подписку — Premium-экраны и кнопки оплаты продолжают работать,
    // исчезает только навязка.
    //
    // Чего здесь нет и почему. В XF есть ещё три переключателя над списком чатов
    // (disablePremiumChristmas / disablePremiumRestore / disablePremiumUpgrade) — они
    // гасят ветки dialogsHintCell, но в форке все три метода-предиката
    // (isPremiumChristmasHintVisible, isPremiumRestoreHintVisible, isPremiumHintVisible)
    // заглушены: тела закомментированы, остался `return false`. Ветки цепочки
    // updateDialogsHint, в которые XF вставляет гейт, в форке недостижимы, то есть
    // переносить гейт некуда — он был бы мёртвым кодом в чистом виде. Держать
    // переключатель, который ничего не делает, нельзя, поэтому они не портированы.

    /**
     * Не показывать в списке «отправлять от имени» каналов, доступных только премиуму.
     *
     * XF вырезает их из sendAsPeersObj.peers сразу после загрузки списка, и в меню
     * их нет вообще — не затемнены и не помечены премиум-замочком.
     *
     * Отличие от XF: у него removeIf() mutates список на месте, а getSendAsPeers()
     * возвращает объект из общего кэша MessagesController (LongSparseArray, TTL 5 минут).
     * Такой removeIf бьёт по всем, кто читает этот же объект, — в частности
     * processEditMessage() определяет по нему message.out, и отредактированное
     * сообщение, отправленное как premium-only-канал, нарисовалось бы входящим.
     * Поэтому здесь собирается отфильтрованная копия, а кэш остаётся нетронутым.
     */
    @JvmField
    val disableNonPremiumChannelChatShow =
        addConfig("DDGDisableNonPremiumChannelChatShow", ConfigItem.configTypeBool, false)

    /**
     * Не показывать строку избранных реакций в поиске по «Избранному».
     *
     * Строка — это SearchTagsList, который ChatActivity создаёт в createView, когда
     * открыт чат с самим собой и аккаунт премиумный. Гейт снимает вторую половину
     * условия, поэтому бесплатный аккаунт её и так не получает, а премиумный теряет.
     *
     * Гейтов два, а не один: у SharedMediaLayout есть собственный SearchTagsList для
     * поиска по «Избранному» из профиля, и его setFilter() для вкладки сообщений
     * уходит в ChatActivity.setTagFilter() → actionBarSearchTags. Если убрать только
     * первый список, в профиле строка осталась бы, тап по тегу ничего бы не делал
     * (поле null, проверка в setTagFilter() молча выходит), и настройка выглядела бы
     * как сломанная. Второй гейт стоит на создании того списка.
     *
     * Известный долг: поле создаётся в createView, поэтому переключатель не
     * перерисовывает уже открытый чат — эффект появится при его переоткрытии.
     */
    @JvmField
    val disableFavoriteSearchEmojiTags =
        addConfig("DDGDisableFavoriteSearchEmojiTags", ConfigItem.configTypeBool, false)

    /**
     * Не показывать избранный набор реакций в меню реакций у сообщения.
     *
     * Условие показа полосы реакций в bottom sheet: реакции доступны всем, а избранный
     * набор — только премиуму. Гейт стоит внутри премиумной скобки, поэтому бесплатный
     * пользователь по-прежнему видит обычный список реакций, а не пустоту.
     *
     * Отличие от XF: у него гейт просто не даёт наполнить полосу, но сам
     * ReactionsContainerLayout к этому моменту уже создан, и пустой объект остаётся в
     * контейнере: scrimPopupWindow теряет PopupContextAnimation и всё равно зовёт
     * startEnterAnimation() на осиротевшем layout. Здесь полоса не создаётся вовсе.
     * Отступы LayoutParams оставлены на isReactionsAvailable, как в XF и в форке до
     * правки, — иначе изменилась бы вёрстка у бесплатных аккаунтов, где полосы и так
     * нет. В остальном поведение при выключенной настройке совпадает с форкой
     * построчно.
     */
    @JvmField
    val disablePremiumFavoriteEmojiTags =
        addConfig("DDGDisablePremiumFavoriteEmojiTags", ConfigItem.configTypeBool, false)

    /**
     * Не добавлять пункт «создать задачу» в меню вложений для аккаунта без премиума.
     *
     * XF гасит todoEnabled после того, как он посчитан для обеих веток (чат и ЛС).
     * Ставим гейт там же — после if/else, иначе пришлось бы дублировать его в двух
     * местах.
     *
     * Что исчезает вместе с кнопкой: при нажатии на неё следовал либо сам todoLayout,
     * либо запасной ChatAttachRestrictedLayout с предложением снять лимит сообщений
     * бустами (проверка checkCanRemoveRestrictionsByBoosts()). Так как кнопка не
     * создаётся, вторая ветка недостижима — а она единственная, где ещё раз читался
     * todoEnabled, и к ней больше негде обращаться.
     *
     * Известный долг: меню вложений пересобирается само (ChatAttachAlert создаётся на
     * каждое открытие), поэтому здесь переключатель действует сразу.
     */
    @JvmField
    val disablePremiumSendTodo = addConfig("DDGDisablePremiumSendTodo", ConfigItem.configTypeBool, false)

    // ---- Typography ----

   /** Имя кастомного файла шрифта в filesDir, пусто — не задан. */
    @JvmField
    val customFontFile = addConfig("DDGCustomFontFile", ConfigItem.configTypeString, "")

    /** Редактируемый стиль текста: множитель размера, в процентах. */
    @JvmField
    val textSizeScale = addConfig("DDGTextSizeScale", ConfigItem.configTypeInt, 100)

    /** Межстрочный интервал, в десятых долях пункта. */
    @JvmField
    val lineSpacing = addConfig("DDGLineSpacing", ConfigItem.configTypeInt, 0)

    // ---- Translation ----

    /** Real-time перевод входящих сообщений. */
    @JvmField
    val realtimeTranslation = addConfig("DDGRealtimeTranslation", ConfigItem.configTypeBool, false)

    // ---- Ghost ----

    /** View-once без ограничений: сколько угодно пересылок и просмотров. */
    @JvmField
    val viewOnceUnlimited = addConfig("DDGViewOnceUnlimited", ConfigItem.configTypeBool, false)

    // ---- Monet ----

    /** Свой акцентный цвет Monet, ARGB. */
    @JvmField
    val monetAccentColor = addConfig("DDGMonetAccentColor", ConfigItem.configTypeInt, -1)

    // ---- Plugins ----

    /** Compatibility Mode: код входа всегда приходит по SMS. */
    @JvmField
    val compatibilityMode = addConfig("DDGCompatibilityMode", ConfigItem.configTypeBool, false)

    // ---- Supporters ----

    /** Бейдж донатера у ника. */
    @JvmField
    val supporterBadge = addConfig("DDGSupporterBadge", ConfigItem.configTypeBool, true)

    // ---- Login ----

    /** Тестовый бэкенд Telegram. */
    @JvmField
    val testBackend = addConfig("DDGTestBackend", ConfigItem.configTypeBool, false)

    // ---- Инфраструктура ----

    private fun addConfig(key: String, type: Int, defaultValue: Any?): ConfigItem {
        val item = ConfigItem(key, type, defaultValue)
        configs.add(item)
        return item
    }

    @JvmStatic
    fun init() {
        loadConfig(false)
    }

    @JvmStatic
    fun loadConfig(force: Boolean) {
        synchronized(sync) {
            if (configLoaded && !force) return
            if (ApplicationLoader.applicationContext == null) return
            val preferences = getPreferences()
            for (item in configs) {
                try {
                    when (item.type) {
                        ConfigItem.configTypeBool ->
                            item.value = preferences.getBoolean(item.key, item.defaultValue as Boolean)

                        ConfigItem.configTypeInt ->
                            item.value = preferences.getInt(item.key, item.defaultValue as Int)

                        ConfigItem.configTypeLong ->
                            item.value = preferences.getLong(item.key, item.defaultValue as Long)

                        ConfigItem.configTypeFloat ->
                            item.value = preferences.getFloat(item.key, item.defaultValue as Float)

                        // Строковые значения не должны приходить null: ConfigItem.String()
                        // вызывает toString() без проверки, и null уронил бы любого,
                        // кто спросит настройку до первого сохранения.
                        ConfigItem.configTypeString ->
                            item.value = preferences.getString(item.key, item.defaultValue as String?)
                                    ?: (item.defaultValue as String? ?: "")
                    }
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
            configLoaded = true
        }
    }

    /**
     * Переключает [streamerMode] и приводит интерфейс в соответствие с новым значением.
     *
     * Выключатель один, а мест два — пункт в боковом меню и экран приватности, — и
     * состояние обязано разойтись ровно ненадолго: пока список чатов не пересобран,
     * в нём остаются старые превью, а уже висящие уведомления показывают текст,
     * который режим уже запретил показывать. Поэтому обе кнопки зовут сюда, а не
     * пишут в [streamerMode] напрямую.
     */
    @JvmStatic
    fun setStreamerMode(enabled: Boolean) {
        streamerMode.setConfigBool(enabled)
        // Настройка одна на всё приложение, а список чатов — свой у каждого
        // аккаунта, и перерисовка одного из них оставила бы переписку второго на
        // экране. Поэтому команда уходит всем активным аккаунтам, а не тому, из
        // которого нажали.
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (!UserConfig.isValidAccount(account)) {
                continue
            }
            // updateInterfaces с полной маской — единственное событие, на которое
            // DialogsActivity пересобирает видимые строки. Своего «перерисовать
            // список чатов» в форке нет, а молчаливый переключатель выглядел бы как
            // «ничего не произошло»: ячейки продолжали бы показывать превью до
            // первого скролла.
            NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL)
        }
        if (enabled) {
            // Уведомления, уже висящие в шторке, были собраны по старым правилам и
            // останутся с текстом навсегда: перерисовать их можно только сняв.
            // Снимаем все, кроме служебных: уведомление о загрузке файла или о
            // входящем звонке к переписке отношения не имеет, и его исчезновение
            // было бы уже не пользой, а лишним действием.
            cancelShownMessageNotifications()
        }
    }

    /**
     * Каналы уведомлений о сообщениях в форке называются
     * `"<номер аккаунта>channel_<диалог>_<случайное>"`, например "0channel_123_456".
     * Всё остальное живёт в других: служебное — в `Other<случайное>`, входящий звонок
     * — в `incoming_calls4<n>`.
     *
     * <p>Regex.matches() требует совпадения со всей строкой, поэтому без `.*` в конце
     * реальный канал "0channel_123_456" не матчился бы ни разу, и уже показанные
     * уведомления не снимались.
     */
    private val MESSAGE_CHANNEL = Regex("^\\d+channel_.*")

    /**
     * Снимает с экрана уже показанные уведомления о сообщениях.
     *
     * <p>Уведомления у Telegram два. Отдельное на каждый чат висит в своём канале
     * вида `<аккаунт>channel_<диалог>_<случайное>` — такие и отбирает [MESSAGE_CHANNEL].
     * А сводное, с текстом последнего сообщения, висит на номере `аккаунт + 1`
     * (формула из конструктора `NotificationsController`) в общем канале `Other…`,
     * и по каналу его не узнать. Без его снятия в шторке осталась бы ровно та
     * переписка, ради которой режим и включается.
     */
    private fun cancelShownMessageNotifications() {
        val manager = ApplicationLoader.applicationContext
            .getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            if (UserConfig.isValidAccount(account)) {
                manager.cancel(account + 1)
            }
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            // На Android 7 каналов у уведомлений ещё нет, и отличить переписку от
            // служебных уведомлений нечем. Снимать всё подряд нельзя — пропадёт
            // прогресс загрузки файла. На этом API уведомления всё равно короткоживущие,
            // а новые уже собираются с учётом режима.
            return
        }
        val active = try {
            manager.activeNotifications
        } catch (t: Throwable) {
            // На части прошивок список отдаёт SecurityException. Отказ означает, что
            // уведомления останутся, но ломать из-за этого переключение нельзя.
            FileLog.e("DdgConfig cannot list active notifications", t)
            return
        }
        for (status in active) {
            val channel = status.notification.channelId ?: continue
            if (!MESSAGE_CHANNEL.matches(channel)) {
                continue
            }
            // Подпись о показанном уведомлении тоже снимается, как это делает сам
            // форк: иначе Telegram счёл бы, что уведомление с этой подписью уже
            // показано, и больше не показал бы его — сообщение пропало бы совсем.
            NotificationsHelper.removePostedSignature(channel.takeWhile { it.isDigit() }.toInt(), status.id)
            manager.cancel(status.id)
        }
    }

    @JvmStatic
    fun reset() {
        synchronized(sync) {
            val editor = getPreferences().edit()
            for (item in configs) {
                editor.remove(item.key)
                item.value = item.defaultValue
            }
            editor.apply()
            // Форматтеры времени в LocaleController тоже закэшированы. Сбросил бы
            // millisecondTimestamp на 100, а подпись в чате продолжила бы показывать
            // миллисекунды до перезапуска — пересобираем их сразу.
            if (LocaleController.getInstance() != null) {
                LocaleController.getInstance().recreateFormatters()
            }
        }
    }
}
