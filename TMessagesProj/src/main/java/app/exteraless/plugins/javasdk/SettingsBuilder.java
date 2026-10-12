package app.exteraless.plugins.javasdk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Строитель JSON-описания экрана настроек Java-плагина. Формат строк полностью
 * совпадает с Python-SDK ({@code ui.settings}) и потребляется тем же
 * {@code PluginSettingsActivity}, поэтому экран настроек у обоих движков один.
 *
 * Значения по умолчанию кладутся в поле {@code value}; текущие значения из
 * хранилища подмешивает движок (см. {@code JavaPluginsEngine#mergeStoredValues}).
 *
 * Пример:
 * <pre>
 * String json = new SettingsBuilder()
 *     .header("Основное")
 *     .add(Switch.of("enabled", "Включить", true).subtext("Главный тумблер"))
 *     .add(Selector.of("mode", "Режим", 0, "Быстро", "Качественно"))
 *     .add(Input.of("token", "Токен", "").subtext("Из чата"))
 *     .add(Text.of("О плагине").onClick("about"))
 *     .build();
 * </pre>
 */
public final class SettingsBuilder {

    private final JSONArray rows = new JSONArray();

    public SettingsBuilder add(SettingRow row) {
        if (row != null) {
            rows.put(row.toJson());
        }
        return this;
    }

    public SettingsBuilder header(String text) {
        return add(Header.of(text));
    }

    public SettingsBuilder divider() {
        return add(Divider.of());
    }

    public SettingsBuilder divider(String text) {
        return add(Divider.of(text));
    }

    public int size() {
        return rows.length();
    }

    public boolean isEmpty() {
        return rows.length() == 0;
    }

    /** JSON-массив строк настроек. */
    public String build() {
        return rows.toString();
    }

    @Override
    public String toString() {
        return build();
    }

    /** Общая основа строки настроек: общие поля и настройка подписи/иконки/callback. */
    public abstract static class SettingRow {

        protected final JSONObject json = new JSONObject();

        protected SettingRow(String type) {
            put("type", type);
        }

        protected final void put(String key, Object value) {
            if (value == null) {
                return;
            }
            try {
                json.put(key, value);
            } catch (JSONException ignored) {
            }
        }

        public SettingRow subtext(String subtext) {
            put("subtext", emptyToNull(subtext));
            return this;
        }

        public SettingRow icon(String icon) {
            put("icon", emptyToNull(icon));
            return this;
        }

        public SettingRow accent(boolean accent) {
            put("accent", accent);
            return this;
        }

        public SettingRow red(boolean red) {
            put("red", red);
            return this;
        }

        /** callback_id: по нажатию движок зовёт {@code onSettingClick(id, view)}. */
        public SettingRow onClick(String callbackId) {
            put("callback_id", emptyToNull(callbackId));
            return this;
        }

        /** long_callback_id: долгое нажатие → {@code onSettingLongClick(id, view)}. */
        public SettingRow onLongClick(String callbackId) {
            put("long_callback_id", emptyToNull(callbackId));
            return this;
        }

        public SettingRow linkAlias(String alias) {
            put("link_alias", emptyToNull(alias));
            return this;
        }

        /** Открыть подстраницу из строки (только text/custom). */
        public SettingRow subPage(SettingsBuilder sub) {
            if (sub != null) {
                try {
                    json.put("sub_page", new JSONArray(sub.build()));
                } catch (JSONException ignored) {
                }
            }
            return this;
        }

        public JSONObject toJson() {
            return json;
        }

        protected static String emptyToNull(String value) {
            return value == null || value.isEmpty() ? null : value;
        }
    }

    public static final class Header extends SettingRow {
        private Header(String text) {
            super("header");
            put("text", text);
        }

        public static Header of(String text) {
            return new Header(text);
        }
    }

    public static final class Divider extends SettingRow {
        private Divider(String text) {
            super("divider");
            put("text", emptyToNull(text));
        }

        public static Divider of() {
            return new Divider(null);
        }

        public static Divider of(String text) {
            return new Divider(text);
        }
    }

    public static final class Switch extends SettingRow {
        private Switch(String key, String text, boolean def) {
            super("switch");
            put("key", key);
            put("text", text);
            put("value", def);
        }

        public static Switch of(String key, String text, boolean def) {
            return new Switch(key, text, def);
        }

        @Override
        public Switch subtext(String subtext) {
            super.subtext(subtext);
            return this;
        }

        @Override
        public Switch icon(String icon) {
            super.icon(icon);
            return this;
        }

        @Override
        public Switch onLongClick(String callbackId) {
            super.onLongClick(callbackId);
            return this;
        }

        @Override
        public Switch linkAlias(String alias) {
            super.linkAlias(alias);
            return this;
        }
    }

    public static final class Selector extends SettingRow {
        private Selector(String key, String text, int def, String[] items) {
            super("selector");
            put("key", key);
            put("text", text);
            JSONArray array = new JSONArray();
            if (items != null) {
                for (String item : items) {
                    array.put(item == null ? "" : item);
                }
            }
            put("items", array);
            put("value", def);
            put("default", def);
        }

        public static Selector of(String key, String text, int def, String... items) {
            return new Selector(key, text, def, items);
        }

        @Override
        public Selector subtext(String subtext) {
            super.subtext(subtext);
            return this;
        }

        @Override
        public Selector icon(String icon) {
            super.icon(icon);
            return this;
        }

        @Override
        public Selector onLongClick(String callbackId) {
            super.onLongClick(callbackId);
            return this;
        }

        @Override
        public Selector linkAlias(String alias) {
            super.linkAlias(alias);
            return this;
        }
    }

    public static final class Input extends SettingRow {
        private Input(String key, String text, String def, boolean multiline) {
            super(multiline ? "edittext" : "input");
            put("key", key);
            if (multiline) {
                put("hint", text);
                put("multiline", true);
            } else {
                put("text", text);
            }
            put("value", def == null ? "" : def);
        }

        public static Input of(String key, String text, String def) {
            return new Input(key, text, def, false);
        }

        /** Многострочное поле ввода ({@code ui.settings.EditText}). */
        public static Input multiline(String key, String hint, String def) {
            return new Input(key, hint, def, true);
        }

        public Input maxLength(int maxLength) {
            put("max_length", maxLength);
            return this;
        }

        @Override
        public Input subtext(String subtext) {
            super.subtext(subtext);
            return this;
        }

        @Override
        public Input icon(String icon) {
            super.icon(icon);
            return this;
        }

        @Override
        public Input onLongClick(String callbackId) {
            super.onLongClick(callbackId);
            return this;
        }

        @Override
        public Input linkAlias(String alias) {
            super.linkAlias(alias);
            return this;
        }
    }

    public static final class Text extends SettingRow {
        private Text(String text) {
            super("text");
            put("text", text);
        }

        public static Text of(String text) {
            return new Text(text);
        }

        @Override
        public Text subtext(String subtext) {
            super.subtext(subtext);
            return this;
        }

        @Override
        public Text icon(String icon) {
            super.icon(icon);
            return this;
        }

        @Override
        public Text accent(boolean accent) {
            super.accent(accent);
            return this;
        }

        @Override
        public Text red(boolean red) {
            super.red(red);
            return this;
        }

        @Override
        public Text onClick(String callbackId) {
            super.onClick(callbackId);
            return this;
        }

        @Override
        public Text onLongClick(String callbackId) {
            super.onLongClick(callbackId);
            return this;
        }

        @Override
        public Text linkAlias(String alias) {
            super.linkAlias(alias);
            return this;
        }

        @Override
        public Text subPage(SettingsBuilder sub) {
            super.subPage(sub);
            return this;
        }
    }

    public static final class Custom extends SettingRow {
        private Custom(String viewId) {
            super("custom");
            put("view_id", emptyToNull(viewId));
        }

        public static Custom of(String viewId) {
            return new Custom(viewId);
        }

        @Override
        public Custom onClick(String callbackId) {
            super.onClick(callbackId);
            return this;
        }

        @Override
        public Custom onLongClick(String callbackId) {
            super.onLongClick(callbackId);
            return this;
        }

        @Override
        public Custom linkAlias(String alias) {
            super.linkAlias(alias);
            return this;
        }

        @Override
        public Custom subPage(SettingsBuilder sub) {
            super.subPage(sub);
            return this;
        }
    }

    public static final class Slider extends SettingRow {
        private Slider(String key, String text, double def, double min, double max, double step) {
            super("slider");
            put("key", key);
            put("text", text);
            put("min", min);
            put("max", max);
            put("step", step);
            put("integral", isIntegral(min) && isIntegral(max) && isIntegral(step));
            put("value", def);
        }

        public static Slider of(String key, String text, double def, double min, double max, double step) {
            return new Slider(key, text, def, min, max, step);
        }

        private static boolean isIntegral(double value) {
            return value == Math.rint(value);
        }

        @Override
        public Slider subtext(String subtext) {
            super.subtext(subtext);
            return this;
        }

        @Override
        public Slider icon(String icon) {
            super.icon(icon);
            return this;
        }

        @Override
        public Slider onLongClick(String callbackId) {
            super.onLongClick(callbackId);
            return this;
        }

        @Override
        public Slider linkAlias(String alias) {
            super.linkAlias(alias);
            return this;
        }
    }
}
