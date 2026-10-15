package com.duckduckgram.duckerer.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.BasePermissionsActivity;

import tw.nekomimi.nekogram.utils.AlertUtil;
import org.telegram.ui.DocumentSelectActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.duckduckgram.duckerer.config.DdgConfig;
import com.duckduckgram.duckerer.support.DdgSupporters;

import tw.nekomimi.nekogram.helpers.SettingsBackupHelper;

/**
 * Категория «Other»: экспорт/импорт настроек, бейдж поддержки и копирование логов.
 *
 * <p>Сюда же раньше жили Text Replacer и пункт Java-плагинов: Text Replacer удалён
 * целиком (добавление замен не работало), а плагины переехали в отдельный раздел
 * {@link DdgPluginsActivity}.
 */
public class DdgOtherActivity extends DdgCategoryActivity {

    @Override
    protected int titleRes() {
        return R.string.DDGCategoryOther;
    }

    @Override
    protected int searchGuid() {
        return 31300;
    }

    @Override
    protected int searchIcon() {
        return R.drawable.msg_list;
    }

    @Override
    protected int searchPrefixRes() {
        return R.string.DDGCategoryOther;
    }

    @Override
    public boolean onFragmentCreate() {
        DdgConfig.loadConfig(false);
        // Бейдж мог быть куплен только что, поэтому список поддержавших обновляем
        // при открытии категории и перерисовываем строку магазина, если пришло
        // что-то новое (иначе «Уже куплен» появился бы лишь после перезапуска).
        DdgSupporters.refresh(changed -> {
            if (changed) {
                rebuildRows();
            }
        });
        return super.onFragmentCreate();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        // Экспорт и импорт используют SettingsBackupHelper из форка: он уже знает
        // про DdgConfig (getConfigTypes()), поэтому DDG-настройки попадают в тот же
        // JSON, что и форковые, и восстанавливаются тем же импортом.
        rows.add(Row.header(R.string.DDGCategoryOther));
        rows.add(Row.action(R.string.DDGSettingsExport, R.drawable.export_solar, this::exportSettings));
        rows.add(Row.action(R.string.DDGSettingsImport, R.drawable.import_solar, this::importSettings));

        rows.add(Row.header(R.string.DDGCategorySupporters));
        rows.add(Row.toggle(R.string.DDGSupporterBadge, R.string.DDGSupporterBadgeInfo, DdgConfig.supporterBadge));
        rows.add(Row.valueAction(R.string.DDGBadgeShop, this::badgeShopValue, this::openBadgeShop));

        rows.add(Row.header(R.string.DDGCopyLogsHeader));
        rows.add(Row.action(R.string.DDGCopyLogs, R.drawable.msg_copy_filled, this::copyLogs));
        rows.add(Row.info(R.string.DDGCopyLogsInfo));

        return rows;
    }

    /** Экспорт: диалог SettingsBackupHelper сам пишет JSON и открывает шару. */
    private void exportSettings() {
        SettingsBackupHelper.backupSettings(getParentActivity(), resourcesProvider);
    }

    private void importSettings() {
        if (Build.VERSION.SDK_INT >= 33) {
            openFilePicker();
        } else {
            DocumentSelectActivity activity = getDocumentSelectActivity(getParentActivity());
            if (activity != null) {
                presentFragment(activity);
            }
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        // getDocumentSelectActivity() при отсутствии разрешения на Android < 13 только
        // запрашивает его и возвращает null — повторяем импорт после выдачи, иначе
        // первый тап после гранта навсегда оставался бы «бездействием».
        if (requestCode == BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE
                && grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            importSettings();
        }
        super.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
    }

    private void openBadgeShop() {
        getMessagesController().openByUserName(DdgSupporters.BADGES_BOT, this, 1);
    }

    /**
     * Правое значение строки магазина: «Уже куплен», если бейдж есть у текущего
     * аккаунта (по id или @username), иначе пусто.
     */
    private String badgeShopValue() {
        return currentAccountHasBadge() ? getString(R.string.DDGBadgeOwned) : "";
    }

    private boolean currentAccountHasBadge() {
        UserConfig config = UserConfig.getInstance(currentAccount);
        if (DdgSupporters.hasBadgeForUser(config.getClientUserId())) {
            return true;
        }
        TLRPC.User me = config.getCurrentUser();
        return me != null && me.username != null && DdgSupporters.hasBadge(me.username);
    }

    private void copyLogs() {
        Utilities.globalQueue.postRunnable(() -> {
            String text;
            try {
                text = buildLogsText();
            } catch (Exception e) {
                text = "";
            }
            final String logs = text;
            AndroidUtilities.runOnUIThread(() -> {
                if (logs.isEmpty() || getParentActivity() == null) {
                    return;
                }
                AndroidUtilities.addToClipboard(logs);
                Toast.makeText(getParentActivity(), getString(R.string.DDGCopyLogsDone), Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Собирает свежие логи из папки FileLog: самое новое до 1 МБ. */
    private static String buildLogsText() throws IOException {
        File dir = AndroidUtilities.getLogsDir();
        if (dir == null || !dir.exists()) {
            return "";
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "";
        }
        List<File> logs = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".txt")) {
                logs.add(file);
            }
        }
        logs.sort((o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
        StringBuilder sb = new StringBuilder();
        int maxSize = 1024 * 1024;
        for (File file : logs) {
            if (sb.length() >= maxSize) {
                break;
            }
            if (sb.length() > 0) {
                sb.append("\n\n===== ").append(file.getName()).append(" =====\n");
            }
            long skip = Math.max(0, file.length() - (maxSize - sb.length()) * 2L);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                reader.skip(skip);
                char[] buf = new char[8192];
                int n;
                while (sb.length() < maxSize && (n = reader.read(buf, 0, Math.min(buf.length, maxSize - sb.length()))) != -1) {
                    sb.append(buf, 0, n);
                }
            }
        }
        return sb.toString();
    }

    /** Старый путь импорта для Android < 13: собственный выбор файла. */
    private DocumentSelectActivity getDocumentSelectActivity(Activity parent) {
        try {
            if (parent.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                parent.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);
                return null;
            }
        } catch (Throwable ignore) {
        }
        DocumentSelectActivity fragment = new DocumentSelectActivity(false);
        fragment.setMaxSelectedFiles(1);
        fragment.setAllowPhoto(false);
        fragment.setDelegate(new DocumentSelectActivity.DocumentSelectActivityDelegate() {
            @Override
            public void didSelectFiles(DocumentSelectActivity activity, ArrayList<String> files, String caption,
                                       boolean notify, int scheduleDate) {
                activity.finishFragment();
                if (!files.isEmpty()) {
                    SettingsBackupHelper.importSettings(getParentActivity(), new File(files.get(0)));
                }
            }

            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify,
                                        int scheduleDate) {
            }

            @Override
            public void startDocumentSelectActivity() {
            }
        });
        return fragment;
    }

    /** Новый путь импорта для Android 13+: системный пикер документов. */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, 21);
        } catch (android.content.ActivityNotFoundException ex) {
            AlertUtil.showSimpleAlert(getParentActivity(), ex);
        }
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == 21 && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                File cacheDir = AndroidUtilities.getCacheDir();
                String tempFile = UUID.randomUUID().toString().replace("-", "") + ".nekox-settings.json";
                File file = new File(cacheDir.getPath(), tempFile);
                try {
                    final InputStream inputStream = ApplicationLoader.applicationContext
                            .getContentResolver().openInputStream(uri);
                    if (inputStream != null) {
                        OutputStream outputStream = new FileOutputStream(file);
                        final byte[] buffer = new byte[4 * 1024];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                        inputStream.close();
                        outputStream.flush();
                        outputStream.close();
                        SettingsBackupHelper.importSettings(getParentActivity(), file);
                    }
                } catch (Exception ignore) {
                }
            }
        }
        // Всегда передаём результат дальше: без super на чужом requestCode дочерние
        // фрагменты/другие экраны теряли бы свои результаты.
        super.onActivityResultFragment(requestCode, resultCode, data);
    }
}