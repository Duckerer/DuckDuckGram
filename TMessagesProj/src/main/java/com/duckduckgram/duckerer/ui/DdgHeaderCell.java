package com.duckduckgram.duckerer.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Path;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import app.exteraless.icons.IconPacksConfig;
import app.exteraless.icons.IconShapeHelper;

/**
 * Шапка корневого экрана DuckDuckGram: логотип, название, версия.
 *
 * Повторяет {@link app.exteraless.settings.AboutHeaderCell}, но с логотипом DuckDuckGram.
 * Форма логотипа — маска лаунчера, а не жёсткий круг: на прошивке со сквирклом
 * иконка в настройках должна выглядеть так же, как на рабочем столе.
 * Переключается долгим нажатием по самому логотипу.
 */
public class DdgHeaderCell extends LinearLayout {

    /** Цвет подложки логотипа, как R.color.ic_background в exteraGram. */
    private static final int LOGO_BACKGROUND = 0xFF0048FF;

    private ImageView logo;
    private Path shapePath;

    private Path shapePath() {
        if (shapePath == null) {
            shapePath = IconShapeHelper.getFinalIconShapePath(72, 72, 18);
        }
        return shapePath;
    }

    public DdgHeaderCell(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);

        logo = new ImageView(context) {
            @Override
            public void draw(Canvas canvas) {
                canvas.save();
                canvas.clipPath(shapePath());
                super.draw(canvas);
                canvas.restore();
            }
        };
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackgroundColor(LOGO_BACKGROUND);
        logo.setImageResource(R.drawable.duckduckgram_icon);
        logo.setOnLongClickListener(v -> {
            IconPacksConfig.toggleSystemIconShape();
            IconShapeHelper.invalidate();
            shapePath = null;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            v.invalidate();
            return true;
        });
        addView(logo, LayoutHelper.createLinear(72, 72,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 40, 0, 0));

        TextView title = new TextView(context);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setText(LocaleController.getString(R.string.DuckDuckGramPreferences));
        addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 50, 16, 50, 0));

        TextView version = new TextView(context);
        version.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        version.setTypeface(AndroidUtilities.bold());
        version.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        version.setGravity(Gravity.CENTER);
        version.setLineSpacing(AndroidUtilities.dp(2), 1f);
        version.setText(buildVersionString());
        addView(version, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 60, 2, 60, 28));
    }

    public void setOnLogoClickListener(android.view.View.OnClickListener listener) {
        if (logo != null) {
            logo.setOnClickListener(listener);
        }
    }

    /** "12.9.2 (1258)" — версия плюс versionCode из PackageInfo, как в оригинале. */
    private static String buildVersionString() {
        StringBuilder sb = new StringBuilder(BuildVars.BUILD_VERSION_STRING);
        try {
            PackageInfo info = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            if (info != null) {
                sb.append(" (").append(info.versionCode).append(")");
            }
        } catch (Exception ignore) {
        }
        if (!TextUtils.isEmpty(BuildVars.BUILD_COMMIT_ID)) {
            sb.append(" · ").append(BuildVars.BUILD_COMMIT_ID);
        }
        return sb.toString();
    }
}
