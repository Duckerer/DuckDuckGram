package com.duckduckgram.duckerer.support;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BulletinFactory;

import com.duckduckgram.duckerer.config.DdgConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Список поддержавших DuckDuckGram и значок рядом с ником.
 *
 * <p>Список берётся из публичного репозитория
 * {@code github.com/Duckerer/ddg-donors} (файл {@code donors.json}). Telegram id и
 * ники — персональные данные, поэтому в публичном файле они лежат не в открытом
 * виде, а как {@code SHA-256(salt + "|id:<id>")} / {@code SHA-256(salt + "|u:<ник>")}.
 * <pre>
 * {
 *   "version": 2,
 *   "hashes": [ "9f2c...", "ab71..." ]
 * }
 * </pre>
 * Клиент считает те же хэши для id/ника пользователя и ищет совпадение. Соль вшита
 * и в бота, и в клиент — она не секрет, но мешает тривиальному парсингу файла.
 * Старый формат ({@code donors}/{@code users} в открытом виде) ещё поддерживается
 * при чтении, чтобы обновление было плавным.
 *
 * <p>Список кэшируется в SharedPreferences: он нужен при отрисовке значка в
 * списке чатов, и ходить в сеть на каждый показ нельзя. Если сети нет, отдаётся кэш.
 */
public final class DdgSupporters {

    public static final String BADGES_BOT = "DDG_Badgesbot";

    /** Значок у имени поддержавшего — кастомный эмодзи DuckDuckGram. */
    private static final long BADGE_DOCUMENT_ID = 5386399931378440814L;

    /**
     * Размер значка относительно высоты строки. Дефолтные 1.2 у
     * {@link AnimatedEmojiSpan} слишком широкие для имени: ник «отъезжал» вправо
     * сильнее, чем нужно, поэтому масштаб уменьшен до высоты строки.
     */
    private static final float BADGE_SCALE = 1.0f;

    /** Соль хэшей — должна совпадать с ботом (bot_simple.py, константа SALT). */
    private static final String SALT = "9753828f38cb84d0ce9c8645b6b48e8abbe2a1fc1c621c17";

    private static final String DONORS_URL =
            "https://raw.githubusercontent.com/Duckerer/ddg-donors/main/donors.json";

    private static final String PREFS = "ddg_supporters";
    private static final String KEY_HASHES = "hashes";
    private static final String KEY_IDS = "ids";
    private static final String KEY_NAMES = "names";
    private static final String KEY_DATE = "date";
    private static final String KEY_ETAG = "etag";
    private static final String KEY_COUNT = "count";
    /** SHA-256 скачанного JSON: замена ETag для серверов, которые его не отдают. */
    private static final String KEY_DIGEST = "digest";

    /** ETag ответа donors.json, полученный в последнем успешном запросе. */
    private static volatile String lastEtag;

    private static final Object sync = new Object();
    private static volatile boolean loaded = false;
    /** Хэши id и ников поддержавших. */
    private static final Set<String> hashes = new HashSet<>();

    private DdgSupporters() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Сколько поддержавших известно (список в настройках рисует каждого как Anonymous). */
    public static List<String> getSupporters() {
        ensureLoaded();
        synchronized (sync) {
            List<String> result = new ArrayList<>(hashes.size());
            for (int i = 0; i < hashes.size(); i++) {
                result.add("");
            }
            return result;
        }
    }

    /** Есть ли значок у пользователя по Telegram id. */
    public static boolean hasBadge(long id) {
        if (id == 0) {
            return false;
        }
        ensureLoaded();
        synchronized (sync) {
            return hashes.contains(hashId(id));
        }
    }

    /** Есть ли значок у пользователя по @username (можно с @). */
    public static boolean hasBadge(CharSequence username) {
        if (username == null || username.length() == 0) {
            return false;
        }
        ensureLoaded();
        synchronized (sync) {
            return hashes.contains(hashUsername(username));
        }
    }

    /** Есть ли значок у пользователя/чата (по id или username). */
    public static boolean hasBadge(TLObject object) {
        if (object instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) object;
            return hasBadge(user.id) || (user.username != null && hasBadge(user.username));
        }
        if (object instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) object;
            return hasBadge(chat.id) || (chat.username != null && hasBadge(chat.username));
        }
        return false;
    }

    /**
     * Есть ли значок у пользователя по id: сначала точный id, затем его @username
     * (список может содержать только ники). Ник берётся из уже загруженных
     * пользователей во всех аккаунтах.
     */
    public static boolean hasBadgeForUser(long id) {
        if (id == 0) {
            return false;
        }
        if (hasBadge(id)) {
            return true;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.isValidAccount(a)) {
                continue;
            }
            TLRPC.User user = MessagesController.getInstance(a).getUser(id);
            if (user != null && user.username != null && hasBadge(user.username)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Является ли id текущим аккаунтом («Сохранённые сообщения» / свой профиль).
     * Возле «Сохранённых сообщений» значок не показываем, а в своём профиле — показываем,
     * поэтому решение принимает не {@link #prependBadge}, а вызывающая ячейка.
     */
    public static boolean isSelf(long id) {
        if (id == 0) {
            return false;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.isValidAccount(a)) {
                continue;
            }
            if (id == UserConfig.getInstance(a).getClientUserId()) {
                return true;
            }
        }
        return false;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (sync) {
            if (loaded) {
                return;
            }
            applyFromPrefs();
            loaded = true;
        }
    }

    private static void applyFromPrefs() {
        synchronized (sync) {
            hashes.clear();
            String rawHashes = prefs().getString(KEY_HASHES, null);
            if (rawHashes != null && !rawHashes.isEmpty()) {
                for (String part : rawHashes.split(",")) {
                    String value = part.trim();
                    if (!value.isEmpty()) {
                        hashes.add(value);
                    }
                }
                return;
            }
            // Обратная совместимость: кэш старого формата (id/ники в открытом виде).
            String rawIds = prefs().getString(KEY_IDS, null);
            if (rawIds != null && !rawIds.isEmpty()) {
                for (String part : rawIds.split(",")) {
                    try {
                        hashes.add(hashId(Long.parseLong(part.trim())));
                    } catch (Exception ignore) {
                    }
                }
            }
            String rawNames = prefs().getString(KEY_NAMES, null);
            if (rawNames != null && !rawNames.isEmpty()) {
                for (String line : rawNames.split("\n")) {
                    int separator = line.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    try {
                        long id = Long.parseLong(line.substring(0, separator).trim());
                        String name = line.substring(separator + 1).trim();
                        if (!name.isEmpty()) {
                            hashes.add(hashId(id));
                            hashes.add(hashUsername(name));
                        }
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    private static void saveToPrefs(int count, String etag, String digest) {
        StringBuilder hashBuilder = new StringBuilder();
        synchronized (sync) {
            for (String value : hashes) {
                if (hashBuilder.length() > 0) {
                    hashBuilder.append(',');
                }
                hashBuilder.append(value);
            }
        }
        SharedPreferences.Editor editor = prefs().edit()
                .putString(KEY_HASHES, hashBuilder.toString())
                .putInt(KEY_COUNT, count)
                .putInt(KEY_DATE, (int) (System.currentTimeMillis() / 1000L));
        if (etag != null && !etag.isEmpty()) {
            editor.putString(KEY_ETAG, etag);
        }
        if (digest != null) {
            editor.putString(KEY_DIGEST, digest);
        }
        editor.apply();
    }

    /**
     * Обновить список из {@code donors.json}. Колбэк всегда вызывается в UI-потоке.
     * Если что-то не сработало, отдаётся кэш.
     */
    public static void load(org.telegram.messenger.Utilities.Callback<List<String>> callback) {
        ensureLoaded();
        Utilities.globalQueue.postRunnable(() -> {
            String json = download(null);
            if (json != null) {
                parseFromJson(json, lastEtag);
            }
            AndroidUtilities.runOnUIThread(() -> callback.run(getSupporters()));
        });
    }

    /**
     * Проверка обновлений при входе в приложение. Шлём условный GET с сохранённым
     * ETag: если сервер отвечает 304 — список не менялся, кэш остаётся как есть.
     * Если 200 — применяем список, только если он реально изменился: по ETag (когда
     * сервер его отдаёт) либо по SHA-256 тела (когда нет). Колбэк получает true,
     * только если кэш обновился, — по этому флагу {@link ApplicationLoader} просит
     * список чатов перерисоваться.
     */
    public static void refresh(org.telegram.messenger.Utilities.Callback<Boolean> callback) {
        ensureLoaded();
        Utilities.globalQueue.postRunnable(() -> {
            boolean changed = false;
            final String cachedEtag = prefs().getString(KEY_ETAG, null);
            String json = download(cachedEtag);
            if (json != null) {
                int newCount = countEntries(json);
                int oldCount = prefs().getInt(KEY_COUNT, -1);
                String newDigest = jsonDigest(json);
                String oldDigest = prefs().getString(KEY_DIGEST, null);
                // Раньше отсутствие ETag у сервера трактовалось как «изменилось»,
                // и неизменённый файл разбирался при каждом запуске. Теперь тело
                // сравнивается по SHA-256, а ETag — дополнительный сигнал.
                boolean etagChanged = lastEtag != null && !lastEtag.equals(cachedEtag);
                boolean digestChanged = oldDigest == null || newDigest == null
                        || !newDigest.equals(oldDigest);
                if (newCount != oldCount || etagChanged || digestChanged) {
                    parseFromJson(json, lastEtag);
                    changed = true;
                }
            }
            final boolean result = changed;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        });
    }

    /** Скачивает donors.json. При передаче etag сервер может ответить 304 (тогда null). */
    private static String download(String etag) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(DONORS_URL).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            // Android-кэш HttpURLConnection и посредники могли держать старый
            // donors.json, из-за чего бейдж появлялся только со второго запуска.
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DuckDuckGram");
            if (etag != null && !etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return null;
            }
            if (code < 200 || code >= 300) {
                return null;
            }
            String newEtag = connection.getHeaderField("ETag");
            lastEtag = (newEtag != null && !newEtag.isEmpty()) ? newEtag : null;
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
            return builder.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Число записей в документе: хэши в новом формате либо donors + users в старом. */
    private static int countEntries(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray hashArray = root.optJSONArray("hashes");
            if (hashArray != null) {
                return hashArray.length();
            }
            int count = 0;
            JSONArray donors = root.optJSONArray("donors");
            if (donors != null) {
                count += donors.length();
            }
            JSONObject users = root.optJSONObject("users");
            if (users != null) {
                count += users.length();
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    /** SHA-256 сырого JSON: отличается при любом изменении файла, без соли. */
    private static String jsonDigest(String json) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void parseFromJson(String json, String etag) {
        try {
            JSONObject root = new JSONObject(json);
            Set<String> parsed = new HashSet<>();
            JSONArray hashArray = root.optJSONArray("hashes");
            if (hashArray != null) {
                for (int i = 0; i < hashArray.length(); i++) {
                    Object item = hashArray.opt(i);
                    if (item instanceof String) {
                        String value = ((String) item).trim();
                        if (!value.isEmpty()) {
                            parsed.add(value);
                        }
                    }
                }
            } else {
                // Старый формат: id/ники в открытом виде — считаем хэши на лету.
                JSONArray donors = root.optJSONArray("donors");
                if (donors != null) {
                    for (int i = 0; i < donors.length(); i++) {
                        Object item = donors.opt(i);
                        if (item instanceof Number) {
                            parsed.add(hashId(((Number) item).longValue()));
                        } else if (item instanceof String) {
                            String name = ((String) item).trim();
                            if (!name.isEmpty()) {
                                parsed.add(hashUsername(name));
                            }
                        }
                    }
                }
                JSONObject users = root.optJSONObject("users");
                if (users != null) {
                    java.util.Iterator<String> keys = users.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = users.opt(key);
                        String display = null;
                        if (value instanceof String) {
                            display = (String) value;
                        } else if (value instanceof JSONObject) {
                            JSONObject object = (JSONObject) value;
                            display = object.optString("username", object.optString("name", null));
                        }
                        try {
                            parsed.add(hashId(Long.parseLong(key)));
                        } catch (Exception ignore) {
                        }
                        if (display != null && !display.isEmpty()) {
                            parsed.add(hashUsername(display));
                        }
                    }
                }
            }
            synchronized (sync) {
                hashes.clear();
                hashes.addAll(parsed);
            }
            saveToPrefs(parsed.size(), etag, jsonDigest(json));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Когда список обновлялся последний раз, для отладки и настроек. */
    public static int getLastUpdateDate() {
        return prefs().getInt(KEY_DATE, 0);
    }

    private static String hashId(long id) {
        return sha256("id:" + id);
    }

    private static String hashUsername(CharSequence username) {
        String name = username.toString();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        return sha256("u:" + name.toLowerCase(Locale.ROOT));
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((SALT + "|" + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Значок поддержавшего, добавленный слева от имени.
     *
     * <p>Возвращает имя без изменений, если настройка выключена или человека нет
     * в списке. Вставляет {@link SupporterSpan}, по которому ячейки ловят нажатие.
     */
    public static CharSequence prependBadge(CharSequence name, long id, Paint.FontMetricsInt fontMetrics) {
        if (name == null || name.length() == 0 || !DdgConfig.supporterBadge.Bool()) {
            return name;
        }
        // Идемпотентность: ячейки переиспользуются, а текст берётся из SimpleTextView,
        // поэтому старый значок снимаем перед вставкой нового.
        CharSequence clean = removeBadge(name);
        if (!hasBadgeForUser(id)) {
            return clean;
        }
        SpannableStringBuilder result = new SpannableStringBuilder();
        result.append(" ");
        result.setSpan(new SupporterSpan(BADGE_DOCUMENT_ID, BADGE_SCALE, fontMetrics), 0, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        result.append(clean);
        return result;
    }

    /**
     * Ширина в пикселях, которую займёт значок вместе с ведущим пробелом у имени.
     *
     * <p>Нужна ячейкам, чтобы зарезервировать место и обрезать ник чуть раньше, иначе
     * значок, добавленный после обрезки, вылезает за границу и наезжает на сообщение.
     * Возвращает 0, если значок выключен или у пользователя его нет.
     */
    public static int badgeWidth(long id, Paint paint) {
        if (paint == null || !DdgConfig.supporterBadge.Bool() || !hasBadgeForUser(id)) {
            return 0;
        }
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int size = Math.abs(fm.descent) + Math.abs(fm.ascent);
        if (size == 0) {
            size = AndroidUtilities.dp(20);
        }
        int width = (int) Math.ceil(size * BADGE_SCALE);
        width += (int) Math.ceil(paint.measureText(" "));
        return width;
    }

    /** Убирает ранее вставленный значок из текста (для переиспользуемых ячеек). */
    public static CharSequence removeBadge(CharSequence name) {
        if (!(name instanceof Spanned)) {
            return name;
        }
        Spanned spanned = (Spanned) name;
        SupporterSpan[] spans = spanned.getSpans(0, name.length(), SupporterSpan.class);
        if (spans == null || spans.length == 0) {
            return name;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(name);
        for (SupporterSpan span : spans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            builder.removeSpan(span);
            if (start >= 0 && end > start && end <= builder.length()) {
                builder.delete(start, end);
            }
        }
        return builder;
    }

    /**
     * Показать нижнее уведомление о поддержке с кнопкой перехода к боту.
     * Можно передать контейнер (если известен) или фрагмент.
     */
    public static void showBadgeBulletin(BaseFragment fragment, FrameLayout container,
                                         Theme.ResourcesProvider resourcesProvider, int account) {
        int resolvedAccount = account < 0 ? UserConfig.selectedAccount : account;
        BulletinFactory factory;
        if (container != null) {
            factory = BulletinFactory.of(container, resourcesProvider);
        } else if (fragment != null) {
            factory = BulletinFactory.of(fragment);
        } else {
            factory = BulletinFactory.global();
        }
        if (factory == null) {
            return;
        }
        final BaseFragment target = fragment != null ? fragment : org.telegram.ui.LaunchActivity.getSafeLastFragment();
        CharSequence text = LocaleController.getString(R.string.DDGSupporterBadgeBulletin);
        CharSequence button = LocaleController.getString(R.string.DDGSupporterBadgeOpenBot);
        Runnable action = () -> {
            if (target != null) {
                target.getMessagesController().openByUserName(BADGES_BOT, target, 1);
            }
        };
        TLRPC.Document document = AnimatedEmojiDrawable.findDocument(resolvedAccount, BADGE_DOCUMENT_ID);
        if (document != null) {
            factory.createEmojiBulletin(document, text, button, action).wrapContent().show();
        } else {
            factory.createSimpleBulletin(R.drawable.msg_premium_badge, text, button, action).show();
        }
    }

    /** Значок, при нажатии на который показывается уведомление о поддержке. */
    public static class SupporterSpan extends AnimatedEmojiSpan {
        public SupporterSpan(long documentId, Paint.FontMetricsInt fontMetrics) {
            super(documentId, fontMetrics);
        }

        public SupporterSpan(long documentId, float scale, Paint.FontMetricsInt fontMetrics) {
            super(documentId, scale, fontMetrics);
        }
    }
}
