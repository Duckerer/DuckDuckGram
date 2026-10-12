package com.duckduckgram.duckerer.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.Paint.ColorPickerBottomSheet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.duckduckgram.duckerer.config.DdgConfig;
import tw.nekomimi.nekogram.helpers.MonetHelper;
import tw.nekomimi.nekogram.helpers.TypefaceHelper;

public class DdgAppearanceActivity extends DdgCategoryActivity {

    private static final int REQUEST_PICK_FONT = 101;
    private static final String FONTS_DIR = "fonts";

    @Override
    protected int titleRes() {
        return R.string.DDGCategoryAppearance;
    }

    @Override
    protected int searchGuid() {
        return 27005;
    }

    @Override
    protected int searchIcon() {
        return R.drawable.msg_text_outlined;
    }

    @Override
    protected int searchPrefixRes() {
        return R.string.DDGCategoryAppearance;
    }

    @Override
    public boolean onFragmentCreate() {
        DdgConfig.loadConfig(false);
        return super.onFragmentCreate();
    }

    private static boolean monetAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.header(R.string.DDGCategoryTypography));
        rows.add(Row.radioValues(R.string.DDGTextSize, DdgConfig.textSizeScale,
                new int[]{R.string.DDGScale80, R.string.DDGScale90, R.string.DDGScale100,
                        R.string.DDGScale110, R.string.DDGScale125},
                new int[]{80, 90, 100, 110, 125}));

        rows.add(Row.radioValues(R.string.DDGLineSpacing, DdgConfig.lineSpacing,
                new int[]{R.string.DDGSpacingTight, R.string.DDGSpacingNormal,
                        R.string.DDGSpacingRoomy, R.string.DDGSpacingLoose},
                new int[]{0, 4, 8, 16}));

        rows.add(Row.value(R.string.DDGCustomFont, () -> {
            String name = DdgConfig.customFontFile.String();
            return name == null || name.isEmpty() ? "" : name;
        }));
        rows.add(Row.action(R.string.DDGCustomFontHint, R.drawable.baseline_file_download_24, this::pickFontFile));
        rows.add(Row.action(R.string.DDGCustomFontReset, R.drawable.baseline_delete_24, this::resetFontFile));
        if (DdgConfig.customFontFile.String() != null && !DdgConfig.customFontFile.String().isEmpty()) {
            rows.add(Row.info(R.string.DDGCustomFontRestartInfo));
        }

        rows.add(Row.header(R.string.DDGCategoryMonet));
        if (!monetAvailable()) {
            rows.add(Row.info(R.string.DDGMonetNeedsAndroid12));
        } else {
            rows.add(Row.value(R.string.DDGMonetAccentColor, () -> {
                int color = DdgConfig.monetAccentColor.Int();
                return color == 0 || color == -1 ? "" : String.format(Locale.ROOT, "#%06X", 0xFFFFFF & color);
            }));
            rows.add(Row.action(R.string.DDGMonetAccentPick, R.drawable.baseline_palette_24, this::pickAccentColor));
            rows.add(Row.action(R.string.DDGMonetAccentReset, R.drawable.baseline_delete_24, this::resetAccentColor));
        }

        return rows;
    }

    private void pickFontFile() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_PICK_FONT);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_FONT || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        Uri uri = data.getData();
        String name = queryDisplayName(activity, uri);
        if (name == null || name.isEmpty()) {
            return;
        }
        name = new File(name).getName();
        if (name.isEmpty()) {
            return;
        }
        try {
            File dir = new File(activity.getFilesDir(), FONTS_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File target = new File(dir, name);
            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    return;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            DdgConfig.customFontFile.setConfigString(FONTS_DIR + "/" + name);
            applyCustomFont();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private String queryDisplayName(Activity activity, Uri uri) {
        try (Cursor cursor = activity.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return uri.getLastPathSegment();
    }

    private void resetFontFile() {
        DdgConfig.customFontFile.setConfigString("");
        applyCustomFont();
    }

    private void applyCustomFont() {
        TypefaceHelper.clearCustomTypefaceCache();
        AndroidUtilities.clearTypefaceCache();
        SharedConfig.applyDdgTextSizeScale();
        Theme.createCommonMessageResources();
        rebuildRows();
        // DuckDuckGram: смена шрифта не перекрашивает уже созданные TextView/SimpleTextView
        // (они запоминают Typeface в конструкторе), поэтому честно сообщаем, что для
        // применения нужен перезапуск приложения.
        if (getParentActivity() != null) {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.DDGCustomFontRestartInfo))
                    .show();
        }
    }

    private void pickAccentColor() {
        Activity activity = getParentActivity();
        if (activity == null || !monetAvailable()) {
            return;
        }
        int current = DdgConfig.monetAccentColor.Int();
        int fallback = MonetHelper.getColor("primary_dark");
        new ColorPickerBottomSheet(activity, getResourceProvider())
                .setColor(current == 0 || current == -1 ? (fallback != 0 ? fallback : 0xFF6750A4) : current)
                .setColorListener(this::setAccentColor)
                .show();
    }

    private void setAccentColor(int color) {
        DdgConfig.monetAccentColor.setConfigInt(color | 0xFF000000);
        MonetHelper.invalidateCache();
        applyAccentColor();
        rebuildRows();
    }

    private void resetAccentColor() {
        DdgConfig.monetAccentColor.setConfigInt(-1);
        MonetHelper.invalidateCache();
        applyAccentColor();
        rebuildRows();
    }

    private void applyAccentColor() {
        Theme.ThemeInfo activeTheme = Theme.getActiveTheme();
        if (activeTheme == null || !activeTheme.isMonet()) {
            return;
        }
        boolean isNight = Theme.isCurrentThemeNight();
        Theme.applyTheme(activeTheme, isNight);
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.needSetDayNightTheme, activeTheme, isNight, null, -1);
    }

    @Override
    protected void onRadioChanged(Row row, int value) {
        if (row.config == DdgConfig.textSizeScale) {
            SharedConfig.applyDdgTextSizeScale();
            Theme.createCommonMessageResources();
        } else if (row.config == DdgConfig.lineSpacing) {
            SharedConfig.applyDdgLineSpacing();
        }
        super.onRadioChanged(row, value);
    }
}
