package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.SystemFonts;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.TypefaceSpan;

import java.io.File;
import java.util.List;
import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

public class TypefaceHelper {

    private static final String TEST_TEXT;
    private static final int CANVAS_SIZE = 40;
    private static final Paint PAINT = new Paint() {{
        setTextSize(20);
        setAntiAlias(false);
        setSubpixelText(false);
        setFakeBoldText(false);
    }};

    private static Boolean mediumWeightSupported = null;
    private static Boolean italicSupported = null;
    private static volatile Boolean usePixelGoogleSans = null;
    private static Typeface systemGoogleSans;
    private static boolean systemGoogleSansLoaded;
    private static Typeface systemGoogleSansMedium;
    private static boolean systemGoogleSansMediumLoaded;

    static {
        var lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
        if (List.of("zh", "ja", "ko").contains(lang)) {
            TEST_TEXT = "你好";
        } else if (List.of("ar", "fa").contains(lang)) {
            TEST_TEXT = "مرحبا";
        } else if ("iw".equals(lang)) {
            TEST_TEXT = "שלום";
        } else if ("th".equals(lang)) {
            TEST_TEXT = "สวัสดี";
        } else if ("hi".equals(lang)) {
            TEST_TEXT = "नमस्ते";
        } else if (List.of("ru", "uk", "ky", "be", "sr").contains(lang)) {
            TEST_TEXT = "Привет";
        } else {
            TEST_TEXT = "R";
        }
    }

    public static Typeface createTypeface(String assetPath) {
        // DuckDuckGram: свой шрифт клиента. Проверка стоит до switch, потому что
        // ни одна из веток ниже (кроме моноширинной) не строит Typeface из файла —
        // там только имена системных шрифтов, — и свой файл иначе некуда было бы
        // подставить.
        //
        // Моноширинный ассет исключён намеренно: fonts/rmono.ttf это не «ещё один
        // шрифт интерфейса», а гарантия, что символы в колонку. Его используют
        // TableView (таблицы в Instant View) и PaintTypeface.ROBOTO_MONO (инструмент
        // Mono в фоторедакторе), и подмена своего файла там разъезжает колонки.
        if (!AndroidUtilities.TYPEFACE_ROBOTO_MONO.equals(assetPath)) {
            Typeface custom = ddgCustomTypeface(assetPath);
            if (custom != null) {
                return custom;
            }
        }
        return switch (assetPath) {
            case AndroidUtilities.TYPEFACE_ROBOTO_REGULAR -> createTypeface(400, false);
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM -> {
                if (NekoConfig.forceFontWeightFallback.Bool()) {
                    yield createTypeface(700, false);
                }
                if (shouldUsePixelGoogleSans()) {
                    yield pixelMediumTypeface(false);
                }
                yield isMediumWeightSupported() ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.create("sans-serif", Typeface.BOLD);
            }
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC -> {
                if (NekoConfig.forceFontWeightFallback.Bool()) {
                    yield createTypeface(700, true);
                }
                if (shouldUsePixelGoogleSans()) {
                    yield pixelMediumTypeface(true);
                }
                yield isMediumWeightSupported() ? Typeface.create("sans-serif-medium", Typeface.ITALIC) : Typeface.create("sans-serif", Typeface.BOLD_ITALIC);
            }
            case AndroidUtilities.TYPEFACE_RCONDENSED_BOLD ->
                    Typeface.create("sans-serif-condensed", Typeface.BOLD);
            case AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD ->
                    createTypeface(800, false);
            case AndroidUtilities.TYPEFACE_RITALIC ->
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? Typeface.create(baseTypeface(), 400, true) : Typeface.create("sans-serif", Typeface.ITALIC);
            case AndroidUtilities.TYPEFACE_ROBOTO_MONO ->
                    AndroidUtilities.mono();
            default -> createTypefaceFromAsset(assetPath);
        };
    }

    // ---------- DuckDuckGram: свой файл шрифта ----------

    /** Кэш загруженного файла: шрифт спрашивают на каждый экран, грузить заново дорого. */
    private static Typeface ddgTypeface;
    private static String ddgTypefaceName;

    /** Загружает файл своего шрифта (с кэшем) или null, если он не задан/не читается. */
    private static Typeface ddgTypefaceFile() {
        String name = com.duckduckgram.duckerer.config.DdgConfig.customFontFile.String();
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (!name.equals(ddgTypefaceName) || ddgTypeface == null) {
            ddgTypefaceName = name;
            ddgTypeface = null;
            try {
                File file = new File(ApplicationLoader.applicationContext.getFilesDir(), name);
                if (file.isFile() && file.canRead()) {
                    ddgTypeface = Typeface.createFromFile(file);
                }
            } catch (Throwable e) {
                // Шрифт не читается — это не повод падать, берём системный.
                FileLog.e(e);
            }
        }
        return ddgTypeface;
    }

    /**
     * Шрифт клиента из filesDir, если файл задан и читается.
     *
     * <p>Имя файла берётся из настройки — там хранится только имя, а не путь: иначе
     * в настройке остался бы абсолютный путь, который после переноса приложения
     * перестаёт существовать. Возвращаем null, если файла нет: тогда вызывающий
     * идёт обычным путём и получает системный шрифт, то есть клиент не остаётся
     * без текста из-за испорченной настройки.
     *
     * <p>Жирное и курсивное начертания Android строит сам из того же файла:
     * отдельных файлов пользователь не приносит, а синтетические начертания
     * для кастомного шрифта работают на всех версиях.
     */
    private static Typeface ddgCustomTypeface(String assetPath) {
        if (ddgTypefaceFile() == null) {
            return null;
        }
        // Начертание выводим из имени ассета тем же разбором, что и форк для
        // ассетов: у всех, кроме обычного, оно выводится из названия файла.
        int style = Typeface.NORMAL;
        if (assetPath.contains("italic")) {
            style |= Typeface.ITALIC;
        }
        if (assetPath.contains("medium") || assetPath.contains("rbold") || assetPath.contains("rextrabold")) {
            style |= Typeface.BOLD;
        }
        return style == Typeface.NORMAL ? ddgTypeface : Typeface.create(ddgTypeface, style);
    }

    /**
     * Свой шрифт, если он задан, с начертанием из веса и курсива.
     *
     * <p>Отдельный вход по начертанию нужен потому, что через него идёт весь жирный
     * и средний текст: {@link AndroidUtilities#bold()} и ветки TYPEFACE_ROBOTO_MEDIUM
     * зовут {@link #createTypeface(int, boolean)}, а не версию по имени ассета. Пока
     * свой файл подставлялся только в именованную версию, регулярный текст менялся,
     * а заголовки, кнопки и подписи оставались системными — тот самый «шрифт не везде».
     */
    private static Typeface ddgCustomTypeface(int weight, boolean italic) {
        if (ddgTypefaceFile() == null) {
            return null;
        }
        int style = Typeface.NORMAL;
        if (italic) {
            style |= Typeface.ITALIC;
        }
        if (weight >= 500) {
            style |= Typeface.BOLD;
        }
        return style == Typeface.NORMAL ? ddgTypeface : Typeface.create(ddgTypeface, style);
    }

    /** Задан ли свой файл шрифта и читается ли он. */
    public static boolean hasCustomTypeface() {
        return ddgTypefaceFile() != null;
    }

    /** Сбросить кэш своего шрифта: зовётся после смены файла в настройках клиента. */
    public static void clearCustomTypefaceCache() {
        ddgTypeface = null;
        ddgTypefaceName = null;
    }

    public static Typeface createTypefaceFromAsset(String assetPath) {
        Typeface.Builder builder = new Typeface.Builder(ApplicationLoader.applicationContext.getAssets(), assetPath);
        if (assetPath.contains("rextrabold")) {
            builder.setWeight(800);
        }
        if (assetPath.contains("medium") || assetPath.contains("rbold")) {
            builder.setWeight(700);
        }
        if (assetPath.contains("italic")) {
            builder.setItalic(true);
        }
        return builder.build();
    }

    public static boolean isMediumWeightSupported() {
        if (mediumWeightSupported == null) {
            mediumWeightSupported = testTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            FileLog.d("mediumWeightSupported = " + mediumWeightSupported);
        }
        return mediumWeightSupported;
    }

    public static boolean isItalicSupported() {
        if (italicSupported == null) {
            italicSupported = testTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
            FileLog.d("italicSupported = " + italicSupported);
        }
        return italicSupported;
    }

    private static Typeface pixelMediumTypeface(boolean italic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final Typeface weighted = createTypeface(500, italic);
            if (rendersDifferently(weighted, Typeface.create(baseTypeface(), 400, italic))) {
                return weighted;
            }
        }
        final Typeface medium = getSystemGoogleSansMedium();
        if (medium != null) {
            return italic ? Typeface.create(medium, Typeface.ITALIC) : medium;
        }
        return Typeface.create(baseTypeface(), italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
    }

    private static Typeface baseTypeface() {
        if (!shouldUsePixelGoogleSans()) {
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }
        if (!systemGoogleSansLoaded) {
            systemGoogleSansLoaded = true;
            for (String family : new String[]{"google-sans-text", "google-sans"}) {
                final Typeface candidate = Typeface.create(family, Typeface.NORMAL);
                if (rendersDifferently(candidate, Typeface.DEFAULT)) {
                    systemGoogleSans = candidate;
                    FileLog.d("system google sans alias = " + family);
                    break;
                }
            }
            if (systemGoogleSans == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                final File file = findGoogleSansFile();
                if (file != null) {
                    try {
                        systemGoogleSans = Typeface.createFromFile(file);
                        FileLog.d("system google sans file = " + file.getAbsolutePath());
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        }
        return systemGoogleSans != null ? systemGoogleSans : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    private static Typeface getSystemGoogleSansMedium() {
        if (!shouldUsePixelGoogleSans()) {
            return null;
        }
        if (!systemGoogleSansMediumLoaded) {
            systemGoogleSansMediumLoaded = true;
            for (String family : new String[]{"variable-title-medium-emphasized", "variable-title-medium"}) {
                final Typeface candidate = Typeface.create(family, Typeface.NORMAL);
                if (rendersDifferently(candidate, Typeface.DEFAULT)) {
                    systemGoogleSansMedium = candidate;
                    FileLog.d("system google sans medium alias = " + family);
                    break;
                }
            }
        }
        return systemGoogleSansMedium;
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private static File findGoogleSansFile() {
        try {
            for (Font font : SystemFonts.getAvailableFonts()) {
                final File file = font.getFile();
                if (file == null) {
                    continue;
                }
                final String name = file.getName().toLowerCase(Locale.US);
                if ((name.contains("googlesans") || name.contains("google-sans"))
                        && !name.contains("medium") && !name.contains("bold") && !name.contains("italic") && !name.contains("condensed")) {
                    return file;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static boolean isGooglePixelDevice() {
        return "google".equalsIgnoreCase(Build.MANUFACTURER) && Build.MODEL != null && Build.MODEL.toLowerCase(Locale.US).startsWith("pixel");
    }

    public static boolean shouldUsePixelGoogleSans() {
        if (!isGooglePixelDevice()) {
            return false;
        }
        if (usePixelGoogleSans == null) {
            synchronized (TypefaceHelper.class) {
                if (usePixelGoogleSans == null) {
                    boolean use;
                    try {
                        use = hasSimilarMetrics(Typeface.create("sans-serif", Typeface.NORMAL), createTypefaceFromAsset(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
                    } catch (Exception e) {
                        FileLog.e(e);
                        use = false;
                    }
                    usePixelGoogleSans = use;
                    FileLog.d("usePixelGoogleSans = " + use);
                }
            }
        }
        return usePixelGoogleSans;
    }

    private static boolean hasSimilarMetrics(Typeface a, Typeface b) {
        final String sample = "Hamburgefontsiv0123456789";
        final Paint paint = new Paint();
        paint.setTextSize(100);
        final float[] first = new float[sample.length()];
        final float[] second = new float[sample.length()];
        paint.setTypeface(a);
        paint.getTextWidths(sample, first);
        paint.setTypeface(b);
        paint.getTextWidths(sample, second);
        for (int i = 0; i < sample.length(); i++) {
            if (second[i] == 0 || Math.abs(first[i] - second[i]) / second[i] > 0.01f) {
                return false;
            }
        }
        return true;
    }

    private static boolean rendersDifferently(Typeface a, Typeface b) {
        final Canvas canvas = new Canvas();
        final Bitmap first = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        final Bitmap second = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        synchronized (PAINT) {
            canvas.setBitmap(first);
            PAINT.setTypeface(a);
            canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);
            canvas.setBitmap(second);
            PAINT.setTypeface(b);
            canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);
            PAINT.setTypeface(null);
        }
        final boolean different = !first.sameAs(second);
        AndroidUtilities.recycleBitmaps(List.of(first, second));
        return different;
    }

    private static boolean testTypeface(Typeface typeface) {
        Canvas canvas = new Canvas();

        Bitmap bitmap1 = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bitmap1);
        PAINT.setTypeface(null);
        canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);

        Bitmap bitmap2 = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bitmap2);
        PAINT.setTypeface(typeface);
        canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);

        boolean supported = !bitmap1.sameAs(bitmap2);
        AndroidUtilities.recycleBitmaps(List.of(bitmap1, bitmap2));
        return supported;
    }

    public static Typeface createTypeface(int weight, boolean italic) {
        // DuckDuckGram: свой шрифт клиента. Эта ветка обслуживает всё, что строится
        // по весу, а не по имени ассета, — в первую очередь AndroidUtilities.bold() и
        // medium-начертания. Без неё кастомный файл применялся бы только к обычному
        // тексту, и интерфейс остался бы наполовину системным.
        Typeface custom = ddgCustomTypeface(weight, italic);
        if (custom != null) {
            return custom;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (weight >= 500 && weight < 700 && shouldUsePixelGoogleSans()) {
                final Typeface medium = getSystemGoogleSansMedium();
                if (medium != null) {
                    return Typeface.create(medium, weight, italic);
                }
            }
            return Typeface.create(baseTypeface(), weight, italic);
        }
        if (weight == 700) {
            return Typeface.create("sans-serif", italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        }
        var family = switch (weight) {
            case 800 -> "sans-serif-black";
            case 500 -> "sans-serif-medium";
            default -> "sans-serif";
        };
        return Typeface.create(family, italic ? Typeface.ITALIC : Typeface.NORMAL);
    }

    public static SpannableStringBuilder getTitleText(int currentAccount) {
        String title = (String) NaConfig.INSTANCE.getCustomTitle().defaultValue;
        // openExtera: заголовок списка чатов (AppearanceConfig.titleText).
        // Перенесено из exteraGram 12.9.0, LocaleUtils.getActionBarTitle(int).
        // 0 — имя приложения (NagramX customTitle), 1 — username, 2 — имя, 3 — «Чаты».
        final int oeTitleText = app.exteraless.appearance.AppearanceConfig.titleText();
        if (oeTitleText == 3) {
            title = LocaleController.getString(R.string.FilterChats);
        } else if (oeTitleText == app.exteraless.appearance.AppearanceConfig.TITLE_TEXT_CUSTOM) {
            String customTitle = NaConfig.INSTANCE.getCustomTitle().String();
            if (!TextUtils.isEmpty(customTitle)) {
                title = customTitle;
            }
        } else if (oeTitleText != 0) {
            TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
            String username = oeTitleText == 1 ? UserObject.getPublicUsername(self) : null;
            if (!TextUtils.isEmpty(username)) {
                title = username;
            } else {
                String firstName = UserObject.getFirstName(self);
                if (!TextUtils.isEmpty(firstName)) {
                    title = firstName;
                }
            }
        }
        var builder = new SpannableStringBuilder(title);
        builder.setSpan(new LeadingMarginSpan.Standard(dp(2), 0), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Typeface titleTypeface = NekoConfig.typeface.Bool() && NekoConfig.forceFontWeightFallback.Bool() ? createTypeface(700, false) : createTypeface(600, false);
        builder.setSpan(new TypefaceSpan(titleTypeface, 0, Theme.key_telegram_color_dialogsLogo, null), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }


    private static Typeface lyricsTypeface;

    public static Typeface lyricsTypeface() {
        if (lyricsTypeface != null) {
            return lyricsTypeface;
        }
        Typeface base = null;
        for (String family : new String[]{"google-sans-display", "google-sans", "google-sans-text"}) {
            final Typeface candidate = Typeface.create(family, Typeface.NORMAL);
            if (rendersDifferently(candidate, Typeface.DEFAULT)) {
                base = candidate;
                break;
            }
        }
        if (base == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final File file = findGoogleSansFile();
            if (file != null) {
                try {
                    base = Typeface.createFromFile(file);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        if (base == null) {
            lyricsTypeface = AndroidUtilities.bold();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lyricsTypeface = Typeface.create(base, 700, false);
        } else {
            lyricsTypeface = Typeface.create(base, Typeface.BOLD);
        }
        return lyricsTypeface;
    }
}
