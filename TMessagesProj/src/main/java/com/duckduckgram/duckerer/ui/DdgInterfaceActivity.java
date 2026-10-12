package com.duckduckgram.duckerer.ui;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

import com.duckduckgram.duckerer.config.DdgConfig;
import app.exteraless.chats.ChatsConfig;
import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

/**
 * Категория «Interface». Основная часть пунктов уже есть в форке — они берутся
 * оттуда, чтобы не заводить вторую копию одной и той же настройки.
 */
public class DdgInterfaceActivity extends DdgCategoryActivity {

    @Override
    protected int titleRes() {
        return R.string.DDGCategoryInterface;
    }

    @Override
    protected int searchGuid() {
        return 30300;
    }

    @Override
    protected int searchIcon() {
        return R.drawable.msg_theme;
    }

    @Override
    protected int searchPrefixRes() {
        return R.string.DDGCategoryInterface;
    }

    @Override
    public boolean onFragmentCreate() {
        DdgConfig.loadConfig(false);
        ChatsConfig.ensureLoaded();
        return super.onFragmentCreate();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.header(R.string.DDGCategoryInterface));
        // DuckDuckGram: compactSidebar — уменьшенные метрики сайдбара (пункты, шапка,
        // список аккаунтов), см. DdgSidebarMetrics. Отдельные вкладки чатов и
        // «прокручиваемые превью» из DDG убраны: аналогов нет ни в форке, ни в
        // NagramXF, и обе задачи решаются на стороне сервера или разметки списка.
        rows.add(Row.toggle(R.string.DDGCompactSidebar, R.string.DDGCompactSidebarInfo, DdgConfig.compactSidebar));
        rows.add(Row.radio(R.string.DDGMenuStyles, DdgConfig.menuStyle, new int[]{
                R.string.DDGMenuStyleDefault,
                R.string.DDGMenuStyleCompact,
                R.string.DDGMenuStyleList
        }));
        // Дизайн пузырей пишется в SharedConfig.bubbleRadius: 17 — стандарт форка,
        // компактный — углы можно прижать, крупный — раскрыть. См. onRadioChanged.
        rows.add(Row.radioValues(R.string.DDGBubbleDesign, DdgConfig.bubbleDesign, new int[]{
                R.string.DDGBubbleDesignDefault,
                R.string.DDGBubbleDesignCompact,
                R.string.DDGBubbleDesignLarge
        }, new int[]{0, 1, 2}));
        rows.add(Row.toggle(R.string.DDGUnreadMutesAbovePins, DdgConfig.unreadMutesAbovePins));
        rows.add(Row.toggle(R.string.DDGUnlimitedAccounts, DdgConfig.unlimitedAccounts));

        // Скрытие того, что Telegram (или форк) показывает без явной просьбы. Набор взят из
        // XF; то, что в форке уже есть под другим именем, сюда не попадает — например,
        // квадратная FAB (AppearanceConfig.squareFab), скрытие подборок (NekoConfig.disableTrending),
        // AI-саммари и AI-редактор (AiFeaturesHelper) и «хвост» сообщения (ChatsConfig.removeMessageTail).
        rows.add(Row.header(R.string.DDGHideInterfaceExtra));
        rows.add(Row.toggle(R.string.DDGDisableShortcutTagActions, R.string.DDGDisableShortcutTagActionsInfo, DdgConfig.disableShortcutTagActions));
        rows.add(Row.toggle(R.string.DDGHidePhotoCounter, R.string.DDGHidePhotoCounterInfo, DdgConfig.hidePhotoCounter));
        rows.add(Row.toggle(R.string.DDGDisableGifts, R.string.DDGDisableGiftsInfo, DdgConfig.disableGifts));
        rows.add(Row.toggle(R.string.DDGDisableAds, R.string.DDGDisableAdsInfo, DdgConfig.disableAds));
        rows.add(Row.toggle(R.string.DDGDisablePhoneSharePrompt, R.string.DDGDisablePhoneSharePromptInfo, DdgConfig.disablePhoneSharePrompt));
        rows.add(Row.toggle(R.string.DDGDisableBirthdayContact, R.string.DDGDisableBirthdayContactInfo, DdgConfig.disableBirthdayContact));

        // Премиум-навязчивание. Каждый переключатель гасит одно конкретное предложение
        // и ничего больше: сами экраны покупки, кнопки оплаты и Premium-разделы в
        // настройках продолжают работать.
        //
        // Чего здесь нет: в XF есть ещё три переключателя над списком чатов
        // (новогодняя подсказка, «восстановите премиум», «перейдите на премиум»), но в
        // форке все три метча-предиката заглушены — тела закомментированы, остался
        // `return false`. Гейтить там нечего, ветки недостижимы, поэтому переносить
        // переключатель было бы бессмысленно.
        rows.add(Row.header(R.string.DDGHidePremiumNags));
        rows.add(Row.toggle(R.string.DDGDisableNonPremiumChannelChatShow, R.string.DDGDisableNonPremiumChannelChatShowInfo, DdgConfig.disableNonPremiumChannelChatShow));
        rows.add(Row.toggle(R.string.DDGDisableFavoriteSearchEmojiTags, R.string.DDGDisableFavoriteSearchEmojiTagsInfo, DdgConfig.disableFavoriteSearchEmojiTags));
        rows.add(Row.toggle(R.string.DDGDisablePremiumFavoriteEmojiTags, R.string.DDGDisablePremiumFavoriteEmojiTagsInfo, DdgConfig.disablePremiumFavoriteEmojiTags));
        rows.add(Row.toggle(R.string.DDGDisablePremiumSendTodo, R.string.DDGDisablePremiumSendTodoInfo, DdgConfig.disablePremiumSendTodo));

        rows.add(Row.header(R.string.DDGCategoryAvatars));
        rows.add(Row.toggle(R.string.DDGAvatarInHeader, DdgConfig.avatarInHeader));
        rows.add(Row.toggle(R.string.DDGHideAvatarsInList, DdgConfig.hideAvatarsInList));
        rows.add(Row.toggle(R.string.DDGUnlimitedFolders, DdgConfig.unlimitedFolders));

        rows.add(Row.header(R.string.DDGCategoryStorage));
        rows.add(Row.toggle(R.string.DDGKeepOriginalFileNames, DdgConfig.keepOriginalFileNames));
        rows.add(Row.toggle(R.string.DDGBuiltInBrowser, R.string.DDGBuiltInBrowserInfo, DdgConfig.builtInBrowser));

        return rows;
    }

    @Override
    protected void onRadioChanged(Row row, int value) {
        super.onRadioChanged(row, value);
        if (row.config == DdgConfig.bubbleDesign) {
            // Индекс выбора → радиус пузырей, как в ThemeActivity.setBubbleRadius.
            int radius = value == 1 ? 6 : value == 2 ? 26 : 17;
            org.telegram.messenger.SharedConfig.bubbleRadius = radius;
            org.telegram.messenger.MessagesController.getGlobalMainSettings()
                    .edit().putInt("bubbleRadius", radius).apply();
        }
    }
}
