package com.duckduckgram.duckerer.profile;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.Map;

import com.duckduckgram.duckerer.config.DdgConfig;

/**
 * Локальная подмена полей собственного профиля (номер, @юзернейм), как в
 * плагине Profile_Custom.
 *
 * <p>Работает только в памяти и только на этом устройстве: серверу ничего не
 * уходит, а объект {@link TLRPC.User} текущего пользователя каждый раз после
 * обновления с сервера проходит через {@link #applyToUser(TLRPC.User)} и получает
 * локальные значения обратно. ID аккаунта не подменяется — он используется как
 * ключ кэшей и подмена сломала бы их — его значение показывается в экране
 * локального профиля отдельной строкой.
 *
 * <p>Долг: сброс поля (пустая строка) возвращает настоящее значение не сразу, а
 * при следующем обновлении пользователя с сервера или перезапуске — «настоящее»
 * значение уже перезаписано в памяти, брать его больше неоткуда. Так же вёл себя
 * и плагин, поэтому поведение считаем ожидаемым.
 */
public final class DdgProfileOverride {

    private DdgProfileOverride() {
    }

    /**
     * Настоящие значения, которые были перезаписаны подменой. Нужны, чтобы
     * {@link #reset()} мог вернуть исходный номер и @юзернейм: после подмены
     * взять «настоящее» из объекта пользователя уже неоткуда. Ключ — индекс
     * аккаунта. Запоминается один раз за сессию, при первой подмене.
     */
    private static final Map<Integer, RealValues> realValues = new HashMap<>();

    private static final class RealValues {
        final String phone;
        final String username;
        final boolean hadUsername;

        RealValues(String phone, String username, boolean hadUsername) {
            this.phone = phone;
            this.username = username;
            this.hadUsername = hadUsername;
        }
    }

    /** Стартовая инициализация: применяет подмену ко всем активным аккаунтам. */
    public static void init() {
        try {
            DdgConfig.loadConfig(false);
            applyToCurrentUser();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Применить подмену к объекту текущего пользователя каждого активного аккаунта. */
    public static void applyToCurrentUser() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.isValidAccount(account)) {
                continue;
            }
            TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
            if (self != null) {
                applyToUser(self, account);
            }
        }
    }

    /**
     * Применить подмену к одному пользователю, если это текущий пользователь этого
     * аккаунта. Вызывается из {@code MessagesController.putUser} после каждого
     * обновления с сервера, чтобы серверное значение не перетёрло локальное.
     */
    public static void applyToUser(TLRPC.User user, int currentAccount) {
        if (user == null) {
            return;
        }
        if (user.id != UserConfig.getInstance(currentAccount).getClientUserId()) {
            return;
        }
        applyFields(user, currentAccount);
    }

    private static void applyFields(TLRPC.User user, int currentAccount) {
        String localPhone = DdgConfig.localPhone.String();
        boolean hasPhone = localPhone != null && !localPhone.trim().isEmpty();
        String localUsername = DdgConfig.localUsername.String();
        boolean hasUsername = localUsername != null && !localUsername.trim().isEmpty();
        if (!hasPhone && !hasUsername) {
            return;
        }

        // Запоминаем настоящие значения до первой подмены — иначе сброс вернуть их
        // не сможет (после перезаписи объекта брать настоящее неоткуда).
        RealValues real;
        synchronized (realValues) {
            real = realValues.get(currentAccount);
            if (real == null) {
                real = new RealValues(user.phone, user.username, (user.flags & 8) != 0);
                realValues.put(currentAccount, real);
            }
        }

        if (hasPhone) {
            // Телефон подставляем ровно как ввёл пользователь: без чистки символов
            // и без принудительного «+» (его приложение добавляет само при показе
            // настоящего номера — см. displayPhone).
            user.phone = localPhone.trim();
        } else {
            user.phone = real.phone;
        }

        if (hasUsername) {
            user.username = localUsername.startsWith("@")
                    ? localUsername.substring(1)
                    : localUsername.trim();
            user.flags |= 8;
        } else {
            user.username = real.username;
            if (real.hadUsername) {
                user.flags |= 8;
            } else {
                user.flags &= ~8;
            }
        }
    }

    /** Текущий отображаемый номер: локальный, либо настоящий из объекта. */
    public static String effectivePhone() {
        String local = DdgConfig.localPhone.String();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        TLRPC.User user = currentUser();
        if (user == null || user.phone == null || user.phone.isEmpty()) {
            return "";
        }
        return "+" + user.phone;
    }

    /** Текущий отображаемый @юзернейм (без @): локальный, либо настоящий. */
    public static String effectiveUsername() {
        String local = DdgConfig.localUsername.String();
        if (local != null && !local.isEmpty()) {
            return local.startsWith("@") ? local.substring(1) : local;
        }
        TLRPC.User user = currentUser();
        return user == null || user.username == null ? "" : user.username;
    }

    /** Текущий отображаемый ID: локальный, либо настоящий. */
    public static String effectiveAccountId() {
        String local = DdgConfig.localUserId.String();
        if (local != null && !local.isEmpty()) {
            return local;
        }
        TLRPC.User user = currentUser();
        return user == null ? "" : String.valueOf(user.id);
    }

    /**
     * Телефон для показа в шапках приложения (настройки, сайдбар).
     *
     * <p>Локальная подмена выводится только для текущего пользователя и ровно как её
     * ввели — без «+», который приложение приписывает настоящему номеру. Для чужого
     * профиля подмена не применяется: иначе номер из твоих настроек показывался бы
     * на странице любого пользователя.
     */
    public static String displayPhone(TLRPC.User user) {
        if (user != null && user.self) {
            String local = DdgConfig.localPhone.String();
            if (local != null && !local.trim().isEmpty()) {
                return local.trim();
            }
        }
        String realPhone = user == null ? null : user.phone;
        return realPhone == null || realPhone.isEmpty()
                ? "" : PhoneFormat.getInstance().format("+" + realPhone);
    }

    /** Очистить все поля локального профиля и вернуть настоящие значения. */
    public static void reset() {
        DdgConfig.localPhone.setConfigString("");
        DdgConfig.localUsername.setConfigString("");
        DdgConfig.localUserId.setConfigString("");

        // Возвращаем сохранённые настоящие номер и @юзернейм в объекты пользователей:
        // без этого «сброс» очищал только ID, потому что номер и юзернейм оставались
        // подменёнными прямо в объекте пользователя.
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (!UserConfig.isValidAccount(account)) {
                continue;
            }
            RealValues real;
            TLRPC.User user = currentUser(account);
            synchronized (realValues) {
                real = realValues.get(account);
            }
            if (real == null || user == null) {
                continue;
            }
            user.phone = real.phone;
            user.username = real.username;
            if (real.hadUsername) {
                user.flags |= 8;
            } else {
                user.flags &= ~8;
            }
        }
        synchronized (realValues) {
            realValues.clear();
        }
    }

    private static TLRPC.User currentUser() {
        return currentUser(UserConfig.selectedAccount);
    }

    private static TLRPC.User currentUser(int account) {
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) {
            user = MessagesController.getInstance(account).getUser(
                    UserConfig.getInstance(account).getClientUserId());
        }
        return user;
    }
}