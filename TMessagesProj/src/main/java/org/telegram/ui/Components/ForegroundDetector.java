/*
 * This is the source code of Telegram for Android v. 5.x.x
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

import com.duckduckgram.duckerer.config.DdgConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.pip.activity.IPipActivity;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ForegroundDetector implements Application.ActivityLifecycleCallbacks {

    public interface Listener {
        void onBecameForeground();
        void onBecameBackground();
    }

    private int refs;
    private boolean wasInBackground = true;
    private long enterBackgroundTime = 0;
    private CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static ForegroundDetector Instance = null;

    /**
     * DuckDuckGram: privacyScreen — окна, на которых FLAG_SECURE поставил именно он.
     *
     * Слабая карта, чтобы не держать активити после их смерти: колбэки приходят в
     * главном потоке, но синхронизация всё равно не мешает.
     */
    private final Set<Activity> securedByPrivacyScreen =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static ForegroundDetector getInstance() {
        return Instance;
    }

    public ForegroundDetector(Application application) {
        Instance = this;
        application.registerActivityLifecycleCallbacks(this);
    }

    public boolean isForeground() {
        return refs > 0;
    }

    public boolean isBackground() {
        return refs == 0;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        if (++refs == 1) {
            if (SystemClock.elapsedRealtime() - enterBackgroundTime < 200) {
                wasInBackground = false;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("switch to foreground");
            }
            for (Listener listener : listeners) {
                try {
                    listener.onBecameForeground();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    public boolean isWasInBackground(boolean reset) {
        if (reset && Build.VERSION.SDK_INT >= 21 && (SystemClock.elapsedRealtime() - enterBackgroundTime < 200)) {
            wasInBackground = false;
        }
        return wasInBackground;
    }

    public void resetBackgroundVar() {
        wasInBackground = false;
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (--refs == 0) {
            enterBackgroundTime = SystemClock.elapsedRealtime();
            wasInBackground = true;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("switch to background");
            }
            for (Listener listener : listeners) {
                try {
                    listener.onBecameBackground();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // DuckDuckGram: privacyScreen — флаг снимается при возврате в приложение,
        // иначе скриншоты внутри чата были бы запрещены всё время.
        applyPrivacyScreen(activity, false);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        // DuckDuckGram: privacyScreen — см. applyPrivacyScreen.
        applyPrivacyScreen(activity, true);
    }

    /**
     * DuckDuckGram: privacyScreen («размывать превью приложения в списке задач»).
     *
     * Клиентского способа именно размыть снимок нет: система снимает окно сама, и
     * повлиять на содержимое кадра, не закрыв окно, нельзя. Делается ближайшее —
     * окно помечается FLAG_SECURE, пока оно уходит в фон. Для списка задач это
     * означает пустой кадр вместо переписки и аватаров.
     *
     * Тонкость, без которой фича ломает форк: FLAG_SECURE в проекте ставят и снимают
     * ещё пять мест (StoryViewer во время показа историй, BubbleActivity, PasscodeViewDialog,
     * TranslateAlert2, PaymentFormActivity), и onActivityResumed зовётся уже после
     * Activity.onResume. Поэтому флаг снимается только там, где поставил его сам
     * privacyScreen, — безусловный clearFlags стирал бы чужое решение и, например,
     * разрешал бы снимать экраны историй после возврата из фона.
     *
     * Второе исключение — окна, из которых уходит системный Picture-in-Picture:
     * флаг на таком окне опустошает сам PiP-кадр, а не только превью в списке задач.
     *
     * Побочный эффект один: снимок экрана и запись экрана невозможны, пока активити
     * неактивно. Внутри приложения флаг снимается, поэтому там они работают.
     *
     * Точка выбрана одна на все активити: форк держит список чатов, просмотр фото,
     * звонки и настройки в разных Activity, а галочка должна вести себя одинаково.
     */
    private void applyPrivacyScreen(Activity activity, boolean paused) {
        if (activity == null) {
            return;
        }
        final boolean enabled = DdgConfig.privacyScreen.Bool();
        try {
            // DuckDuckGram: privacyScreen. На Android 13+ есть штатный способ убрать
            // кадр приложения из списка задач — setRecentsScreenshotEnabled(false) —
            // он не запрещает скриншоты внутри приложения.
            if (Build.VERSION.SDK_INT >= 33) {
                activity.setRecentsScreenshotEnabled(!enabled);
            }
            // Дополнительно на всех версиях помечаем окно FLAG_SECURE, пока оно уходит
            // в фон: на части прошивок setRecentsScreenshotEnabled игнорируется, а
            // FLAG_SECURE даёт пустой кадр в списке задач гарантированно. При возврате
            // в приложение флаг снимается, поэтому скриншоты внутри чата работают.
            //
            // Тонкость, без которой фича ломает форк: FLAG_SECURE в проекте ставят и
            // снимают ещё пять мест (StoryViewer, BubbleActivity, PasscodeViewDialog,
            // TranslateAlert2, PaymentFormActivity), и onActivityResumed зовётся уже после
            // Activity.onResume. Поэтому флаг снимается только там, где поставил его сам
            // privacyScreen, — безусловный clearFlags стирал бы чужое решение.
            //
            // Второе исключение — окна, из которых уходит системный Picture-in-Picture:
            // флаг на таком окне опустошает сам PiP-кадр, а не только превью в списке задач.
            if (activity.getWindow() == null) {
                return;
            }
            if (paused) {
                if (!enabled || securedByPrivacyScreen.contains(activity)) {
                    return;
                }
                // Чужой FLAG_SECURE (StoryViewer, BubbleActivity, PasscodeViewDialog,
                // TranslateAlert2, PaymentFormActivity ставят его сами): окно не берём
                // в собственность, иначе на возврате clearFlags() сотрёт их защиту —
                // получалось, что после фона пузырь чата или форма оплаты теряли
                // FLAG_SECURE и их можно было снять на скриншот.
                if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                    return;
                }
                // Исключение — окно, ДЕЙСТВИТЕЛЬНО уходящее в системный PiP: флаг на нём
                // опустошил бы сам PiP-кадр. Раньше проверка была «instanceof IPip и SDK
                // >= S» и срабатывала на любом Android 12/12L (это обещает auto-enter-PiP,
                // а не факт входа в него): обычное сворачивание чата на API 31-32 уходило
                // вообще без FLAG_SECURE и превью в списке задач показывало переписку.
                // Проверяем фактический вход в PiP — той же сигнал, на который опирается
                // и сам PipActivityHandler.onPause.
                if (activity instanceof IPipActivity && AndroidUtilities.isInPictureInPictureMode(activity)) {
                    return;
                }
                securedByPrivacyScreen.add(activity);
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else if (securedByPrivacyScreen.remove(activity)) {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
