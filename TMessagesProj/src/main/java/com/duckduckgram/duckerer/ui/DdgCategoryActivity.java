package com.duckduckgram.duckerer.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

/**
 * Базовый экран категории DuckDuckGram Preferences.
 *
 * Категория описывается списком {@link Row}: заголовок секции, переключатель,
 * ссылка на вложенный экран или действие. Всё остальное — раскладка, разделители,
 * фон карточек и клики — делает базовый класс, поэтому конкретная категория
 * сводится к описанию её пунктов.
 *
 * Строки и переключатели живут в {@link com.duckduckgram.duckerer.config.DdgConfig} либо
 * берутся из готовых ConfigItem форка — дублировать настройки форка нельзя.
 */
public abstract class DdgCategoryActivity extends BaseNekoSettingsActivity {

    /** Что умеет строка. */
    public static final class Row {
        public static final int HEADER = 0;
        public static final int TOGGLE = 1;
        public static final int LINK = 2;
        public static final int ACTION = 3;
        public static final int VALUE = 4;
        public static final int INFO = 5;
        /** Переключатель над состоянием, которое живёт не в ConfigItem. */
        public static final int BRIDGE = 6;
        public static final int RADIO = 7;

        public final int type;
        public final int titleRes;
        public final int infoRes;
        public final int iconRes;

        /**
         * Готовый текст заголовка, если он не из ресурсов.
         *
         * <p>Нужен там, где заголовок известен только во время работы: имена
         * плагинов, путь к папке. Если задан {@code titleText}, он важнее
         * {@code titleRes}, поэтому ресурс под такую строку заводить не нужно.
         */
        public final String titleText;
        /** Для TOGGLE. */
        public final ConfigItem config;
        /** Для VALUE: берёт текст значения из сюда. */
        public final ValueProvider valueProvider;
        /** Для LINK и ACTION. */
        public final Runnable action;
        /** Для BRIDGE: чтение и запись состояния. */
        public final BoolGetter boolGetter;
        public final BoolSetter boolSetter;
        /**
         * Для RADIO: строки вариантов по порядку, индекс соответствует значению в
         * ConfigItem типа int. Вариант показывается и в попапе, и в значение ячейки.
         */
        public final int[] optionsRes;
        /**
         * Для RADIO: значения настройки по порядку вариантов, если они не совпадают
         * с индексом. Пусто — вариант i пишет число i, как раньше.
         */
        public int[] optionValues;

        private Row(int type, int titleRes, String titleText, int infoRes, int iconRes,
                    ConfigItem config, ValueProvider valueProvider, Runnable action,
                    BoolGetter boolGetter, BoolSetter boolSetter, int[] optionsRes) {
            this.type = type;
            this.titleRes = titleRes;
            this.titleText = titleText;
            this.infoRes = infoRes;
            this.iconRes = iconRes;
            this.config = config;
            this.valueProvider = valueProvider;
            this.action = action;
            this.boolGetter = boolGetter;
            this.boolSetter = boolSetter;
            this.optionsRes = optionsRes;
        }

        /** Заголовок строки: готовый текст либо строка из ресурсов. */
        public String title(Context context) {
            if (titleText != null) {
                return titleText;
            }
            return titleRes == 0 ? "" : getString(titleRes);
        }

        /**
         * Название выбранного варианта для RADIO.
         *
         * <p>Индекс из настройки не доверяем вслепую: значение могло остаться от
         * прошлой версии, где вариантов было больше, — тогда показываем первый.
         */
        public String optionName(Context context) {
            if (optionsRes == null || optionsRes.length == 0 || config == null) {
                return "";
            }
            int index = 0;
            if (optionValues != null) {
                // Значение ищем в списке: если настройка оказалась не из него,
                // показываем первый вариант, а не случайный по индексу.
                for (int i = 0; i < optionValues.length; i++) {
                    if (optionValues[i] == config.Int()) {
                        index = i;
                        break;
                    }
                }
            } else {
                index = config.Int();
                if (index < 0 || index >= optionsRes.length) {
                    index = 0;
                }
            }
            return getString(optionsRes[index]);
        }

        public static Row header(int titleRes) {
            return new Row(HEADER, titleRes, null, 0, 0, null, null, null, null, null, null);
        }

        public static Row toggle(int titleRes, ConfigItem config) {
            return new Row(TOGGLE, titleRes, null, 0, 0, config, null, null, null, null, null);
        }

        public static Row toggle(int titleRes, int infoRes, ConfigItem config) {
            return new Row(TOGGLE, titleRes, null, infoRes, 0, config, null, null, null, null, null);
        }

        public static Row link(int titleRes, int iconRes, Runnable action) {
            return new Row(LINK, titleRes, null, 0, iconRes, null, null, action, null, null, null);
        }

        /** Ссылка с готовым текстом: имена плагинов приходят из движка. */
        public static Row link(String title, int iconRes, Runnable action) {
            return new Row(LINK, 0, title, 0, iconRes, null, null, action, null, null, null);
        }

        public static Row action(int titleRes, int iconRes, Runnable action) {
            return new Row(ACTION, titleRes, null, 0, iconRes, null, null, action, null, null, null);
        }

        public static Row value(int titleRes, ValueProvider provider) {
            return new Row(VALUE, titleRes, null, 0, 0, null, provider, null, null, null, null);
        }

        /**
         * Строка со значением, по которой можно нажать. Нужна для локальных полей
         * профиля: ячейка показывает текущее значение, тап открывает ввод.
         */
        public static Row valueAction(int titleRes, ValueProvider provider, Runnable action) {
            return new Row(VALUE, titleRes, null, 0, 0, null, provider, action, null, null, null);
        }

        public static Row info(int titleRes) {
            return new Row(INFO, titleRes, null, 0, 0, null, null, null, null, null, null);
        }

        public static Row bridge(int titleRes, int infoRes, BoolGetter getter, BoolSetter setter) {
            return new Row(BRIDGE, titleRes, null, infoRes, 0, null, null, null, getter, setter, null);
        }

        /**
         * Выбор одного варианта из нескольких: ячейка показывает текущий, тап
         * открывает попап. Настройка обязана быть типа int, а индекс в optionsRes
         * соответствует её значению.
         */
        public static Row radio(int titleRes, ConfigItem config, int[] optionsRes) {
            return new Row(RADIO, titleRes, null, 0, 0, config, null, null, null, null, optionsRes);
        }

        /**
         * Выбор варианта, у которого значение настройки не совпадает с индексом.
         * Нужно там, где в попапе порядок удобнее, а в настройке хранится другое
         * число: например множители 80/100/120 %, которые не идут подряд с нуля.
         * В optionsRes лежат подписи, в values — то, что реально пишется в настройку.
         */
        public static Row radioValues(int titleRes, ConfigItem config, int[] optionsRes, int[] values) {
            Row row = new Row(RADIO, titleRes, null, 0, 0, config, null, null, null, null, optionsRes);
            row.optionValues = values;
            return row;
        }
    }

    /** Чтение состояния для строки BRIDGE. */
    public interface BoolGetter {
        boolean get();
    }

    /** Запись состояния для строки BRIDGE. */
    public interface BoolSetter {
        void set(boolean value);
    }

    /** Текст значения для строки VALUE. */
    public interface ValueProvider {
        String get();
    }

    private final List<Row> rows = new ArrayList<>();
    /** Позиции строк, по которым нужно перерисовать список после смены настройки. */
    private int toggleRow = -1;

    /** Список пунктов категории. Каждая реализация собирает свой ряд. */
    protected abstract List<Row> buildRows();

    @Override
    protected void updateRows() {
        super.updateRows();
        rows.clear();
        rows.addAll(buildRows());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.type == Row.TOGGLE) {
                addRow(row.config.getKey());
            } else {
                addRow();
            }
        }
    }

    @Override
    protected String getActionBarTitle() {
        return getString(titleRes());
    }

    protected abstract int titleRes();

    /** Идентификатор категории в поиске настроек. У каждой свой, чтобы не сливались. */
    protected abstract int searchGuid();

    /** Иконка категории: та же, что в корневом списке. */
    protected abstract int searchIcon();

    /** Строка, по которой категория ищется. */
    protected abstract int searchPrefixRes();

    @Override
    public int getSearchGuid() {
        return searchGuid();
    }

    @Override
    public int getSearchIcon() {
        return searchIcon();
    }

    @Override
    public String getSearchPrefix() {
        return getString(searchPrefixRes());
    }

    @Override
    protected String getKey() {
        return "ddg_" + getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    /**
     * Читает настройку как «включено/выключено» независимо от её типа. Раньше здесь
     * вызывался {@link ConfigItem#Bool()} напрямую, из-за чего строки с настройками
     * другого типа (например, string-путь сохранения или int-режим) роняли экран
     * через {@code ClassCastException}.
     */
    private static boolean configBoolValue(ConfigItem config) {
        switch (config.type) {
            case ConfigItem.configTypeBool:
                return config.Bool();
            case ConfigItem.configTypeInt:
            case ConfigItem.configTypeBoolLinkInt:
                return config.Int() != 0;
            case ConfigItem.configTypeLong:
                return config.Long() != 0;
            case ConfigItem.configTypeFloat:
                return config.Float() != 0;
            default:
                return !config.String().isEmpty();
        }
    }

    /** Переключает настройку с учётом её типа и возвращает новое состояние. */
    private static boolean toggleConfigValue(ConfigItem config) {
        if (config.type == ConfigItem.configTypeBool) {
            return config.toggleConfigBool();
        }
        boolean enabled = !configBoolValue(config);
        switch (config.type) {
            case ConfigItem.configTypeInt:
            case ConfigItem.configTypeBoolLinkInt:
                config.setConfigInt(enabled ? 1 : 0);
                break;
            case ConfigItem.configTypeLong:
                config.setConfigLong(enabled ? 1L : 0L);
                break;
            case ConfigItem.configTypeFloat:
                config.setConfigFloat(enabled ? 1f : 0f);
                break;
            default:
                // Строковые настройки (пути и т.п.) переключателем не управляются.
                return enabled;
        }
        return enabled;
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        Row row = rowAt(position);
        if (row == null) {
            return;
        }
        switch (row.type) {
            case Row.TOGGLE: {
                boolean enabled = toggleConfigValue(row.config);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                // Подчинённые строки зависят от мастер-переключателя, поэтому список
                // пересобирается целиком — иначе они остались бы с прежним составом.
                onToggleChanged(row, position, enabled);
                break;
            }
            case Row.BRIDGE: {
                boolean enabled = !row.boolGetter.get();
                row.boolSetter.set(enabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(enabled);
                }
                onToggleChanged(row, position, enabled);
                break;
            }
            case Row.LINK:
            case Row.ACTION:
                if (row.action != null) {
                    row.action.run();
                }
                break;
            case Row.VALUE:
                // Обычная VALUE-строка не нажимается; action появляется только у
                // valueAction (локальные поля профиля).
                if (row.action != null) {
                    row.action.run();
                }
                break;
            case Row.RADIO:
                showRadioOptions(row, view);
                break;
        }
    }

    /**
     * Попап выбора варианта для строки RADIO.
     *
     * <p>Сделан так же, как выбор темы Monet в ThemeActivity: тот же ItemOptions,
     * та же галочка у текущего пункта. Отдельный тип ячейки с радиокнопками не
     * заводим — настройка одна, а готовый попап уже есть.
     */
    private void showRadioOptions(Row row, View anchor) {
        if (row.config == null || row.optionsRes == null) {
            return;
        }
        int current = row.config.Int();
        ItemOptions options = ItemOptions.makeOptions(this, anchor)
                .setGravity(Gravity.RIGHT);
        for (int i = 0; i < row.optionsRes.length; i++) {
            final int value = row.optionValues != null ? row.optionValues[i] : i;
            options.add(value == current ? R.drawable.msg_select : 0, getString(row.optionsRes[i]),
                    () -> {
                        row.config.setConfigInt(value);
                        onRadioChanged(row, value);
                    });
        }
        options.show();
    }

    /** Хук на смену варианта: список по умолчанию не трогаем. */
    protected void onRadioChanged(Row row, int value) {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    /** Хук на смену переключателя: по умолчанию список не трогаем. */
    protected void onToggleChanged(Row row, int position, boolean enabled) {
    }

    /** Пересобрать строки: вызывать, если переключатель меняет состав списка. */
    protected void rebuildRows() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private Row rowAt(int position) {
        return position >= 0 && position < rows.size() ? rows.get(position) : null;
    }

    private static int viewTypeOf(Row row) {
        switch (row.type) {
            case Row.HEADER:
                return TYPE_HEADER;
            case Row.TOGGLE:
            case Row.BRIDGE:
                return TYPE_CHECK;
            case Row.LINK:
            case Row.ACTION:
                return TYPE_TEXT;
            case Row.VALUE:
            case Row.RADIO:
                return TYPE_SETTINGS;
            case Row.INFO:
                return TYPE_INFO_PRIVACY;
            default:
                return TYPE_CHECK;
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public int getItemViewType(int position) {
            Row row = rowAt(position);
            return row == null ? TYPE_SHADOW : viewTypeOf(row);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            Row row = rowAt(position);
            if (row == null) {
                return;
            }
            switch (row.type) {
                case Row.HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(row.title(mContext));
                    break;
                }
                case Row.TOGGLE: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setEnabled(true, null);
                    boolean checked = configBoolValue(row.config);
                    if (row.infoRes != 0) {
                        cell.setTextAndValueAndCheck(row.title(mContext), getString(row.infoRes),
                                checked, true, true);
                    } else {
                        cell.setTextAndCheck(row.title(mContext), checked, true);
                    }
                    cell.setIcon(row.iconRes);
                    break;
                }
                case Row.BRIDGE: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setEnabled(true, null);
                    if (row.infoRes != 0) {
                        cell.setTextAndValueAndCheck(row.title(mContext), getString(row.infoRes),
                                row.boolGetter.get(), true, true);
                    } else {
                        cell.setTextAndCheck(row.title(mContext), row.boolGetter.get(), true);
                    }
                    cell.setIcon(row.iconRes);
                    break;
                }
                case Row.LINK: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                    cell.setTextAndIcon(row.title(mContext), row.iconRes, true);
                    cell.setImageLeft(21);
                    cell.setOffsetFromImage(71);
                    break;
                }
                case Row.ACTION: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                    cell.setTextAndIcon(row.title(mContext), row.iconRes, false);
                    cell.setImageLeft(21);
                    cell.setOffsetFromImage(71);
                    break;
                }
                case Row.VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    String value = row.valueProvider == null ? "" : row.valueProvider.get();
                    cell.setTextAndValue(row.title(mContext), value, true);
                    break;
                }
                case Row.RADIO: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    cell.setTextAndValue(row.title(mContext), row.optionName(mContext), true);
                    break;
                }
                case Row.INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(row.title(mContext));
                    cell.setBackground(Theme.getThemedDrawable(mContext,
                            R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }
    }
}
