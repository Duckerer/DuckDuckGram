package com.duckduckgram.duckerer.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.util.ArrayList;
import java.util.List;

import com.duckduckgram.duckerer.config.DdgConfig;
import com.duckduckgram.duckerer.profile.DdgProfileOverride;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.llm.ui.EditTextFactory;
import xyz.nextalone.nagram.NaConfig;

/**
 * Категория «Privacy screen». Здесь важно не сломать уже работающие приватные
 * настройки форка, поэтому часть пунктов — ссылки на существующие экраны.
 *
 * <p>Нижняя половина экрана — секция «Локальный профиль» (бывшая отдельная
 * вкладка, влита сюда по договорённости: ей место рядом с приватностью).
 */
public class DdgPrivacyActivity extends DdgCategoryActivity {

    @Override
    protected int titleRes() {
        return R.string.DDGCategoryPrivacy;
    }

    @Override
    protected int searchGuid() {
        // 30400 уже занят экраном аватаров: идентификаторы глобального поиска
        // общие для всех экранов, и совпадение смешивает историю запросов.
        return 30401;
    }

    @Override
    protected int searchIcon() {
        return R.drawable.msg_stealth_locked;
    }

    @Override
    protected int searchPrefixRes() {
        return R.string.DDGCategoryPrivacy;
    }

    @Override
    public boolean onFragmentCreate() {
        DdgConfig.loadConfig(false);
        return super.onFragmentCreate();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.header(R.string.DDGCategoryPrivacy));
        // DuckDuckGram: privacyScreen — FLAG_SECURE на время ухода в фон, см.
        // ForegroundDetector.applyPrivacyScreen.
        rows.add(Row.toggle(R.string.DDGPrivacyScreen, R.string.DDGPrivacyScreenInfo, DdgConfig.privacyScreen));
        // DuckDuckGram: streamerMode. Переключатель — второе место того же выключателя,
        // первое — пункт «Streamer mode» в боковом меню. Состояние одно (DdgConfig),
        // поэтому они не могут разойтись.
        rows.add(Row.toggle(R.string.DDGStreamerMode, R.string.DDGStreamerModeInfo, DdgConfig.streamerMode));
        rows.add(Row.toggle(R.string.DDGHideStickerInfoOnPhotos, DdgConfig.hideStickerInfoOnPhotos));
        rows.add(Row.toggle(R.string.DDGMutualContacts, R.string.DDGMutualContactsInfo, DdgConfig.mutualContacts));

        rows.add(Row.header(R.string.DDGCategoryProfile));
        rows.add(Row.info(R.string.DDGProfileInfo));
        // Ограничение на цифры убрано: и номер, и ID можно вводить в свободной форме
        // (буквы, «+», пробелы) — значение подставляется в профиль как есть.
        rows.add(Row.valueAction(R.string.DDGProfilePhone, this::phoneValue, () -> promptText(
                R.string.DDGProfilePhone, R.string.DDGProfilePhoneHint,
                DdgConfig.localPhone.String(), this::savePhone)));
        rows.add(Row.valueAction(R.string.DDGProfileUsername, this::usernameValue, () -> promptText(
                R.string.DDGProfileUsername, R.string.DDGProfileUsernameHint,
                DdgConfig.localUsername.String(), this::saveUsername)));
        rows.add(Row.valueAction(R.string.DDGProfileUserId, this::userIdValue, () -> promptText(
                R.string.DDGProfileUserId, R.string.DDGProfileUserIdHint,
                DdgConfig.localUserId.String(), this::saveUserId)));
        rows.add(Row.action(R.string.DDGProfileReset, R.drawable.msg_reset_solar, this::confirmReset));

        return rows;
    }

    @Override
    protected void onToggleChanged(Row row, int position, boolean enabled) {
        // DuckDuckGram: streamerMode. Пункт в боковом меню после переключения
        // перерисовывает список чатов и снимает висящие уведомления. Этот экран —
        // вторая сторона того же выключателя, и без той же команды режим выглядел бы
        // включившимся в настройках, но не в приложении.
        if (row.config == DdgConfig.streamerMode) {
            DdgConfig.setStreamerMode(enabled);
        }
    }

    private String phoneValue() {
        String value = DdgProfileOverride.effectivePhone();
        return TextUtils.isEmpty(value) ? getString(R.string.DDGProfileNotSet) : value;
    }

    private String usernameValue() {
        String value = DdgProfileOverride.effectiveUsername();
        if (TextUtils.isEmpty(value)) {
            return getString(R.string.DDGProfileNotSet);
        }
        return "@" + value;
    }

    private String userIdValue() {
        return DdgProfileOverride.effectiveAccountId();
    }

    /**
     * Диалог ввода значения локального поля в стиле форка (телеграмовский
     * {@link AlertDialog} + themed-поле {@link EditTextFactory}). Пустая строка
     * очищает подмену (настоящее значение вернётся при обновлении пользователя).
     */
    private void promptText(int titleRes, int hintRes, String current,
                            java.util.function.Consumer<String> onSave) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        EditTextBoldCursor input = EditTextFactory.createAndSetupEditText(
                activity, getResourceProvider(), current, getString(hintRes),
                EditorInfo.IME_ACTION_DONE, true);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(24), 0, dp(24), 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setTitle(getString(titleRes))
                .setView(container)
                .setPositiveButton(getString(R.string.Done),
                        (d, w) -> onSave.accept(input.getText().toString().trim()))
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
    }

    private void savePhone(String value) {
        DdgConfig.localPhone.setConfigString(value);
        applyProfileChange();
    }

    private void saveUsername(String value) {
        DdgConfig.localUsername.setConfigString(value.startsWith("@") ? value.substring(1) : value);
        applyProfileChange();
    }

    private void saveUserId(String value) {
        // Без чистки символов: ID сохраняется как введён.
        DdgConfig.localUserId.setConfigString(value);
        applyProfileChange();
    }

    private void confirmReset() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(activity, getResourceProvider())
                .setMessage(getString(R.string.DDGProfileResetInfo))
                .setPositiveButton(getString(R.string.Reset), (d, w) -> {
                    DdgProfileOverride.reset();
                    applyProfileChange();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
    }

    /**
     * После смены профиля: переприменяем подмену к текущему объекту пользователя,
     * перерисовываем строки и шлём форку событие перерисовки шапки настроек —
     * иначе новый номер/юзернейм не появятся, пока их не пересоберут.
     */
    private void applyProfileChange() {
        DdgProfileOverride.applyToCurrentUser();
        rebuildRows();
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(
                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
    }
}
