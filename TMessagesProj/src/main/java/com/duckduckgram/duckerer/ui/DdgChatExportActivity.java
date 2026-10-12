package com.duckduckgram.duckerer.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.duckduckgram.duckerer.export.DdgChatExporter;

/**
 * Экспорт переписки в Markdown.
 *
 * Экран выбора чата сделан на {@link DdgCategoryActivity}, а не отдельной вкладкой с
 * собственным списком: список чатов — это ровно строки со ссылками, базовый класс
 * уже умеет их рисовать и обрабатывать, а отдельный фрагмент означал бы вторую
 * копию списка, поиска и разметки строк.
 *
 * Список намеренно ограничен {@link DdgChatExporter#MAX_DIALOGS_IN_LIST} недавними
 * чатами. Полный список у аккаунта с тысячей диалогов — это список, по которому
 * невозможно выбрать что-то одним касанием, а поиск по нему в этом экране не
 * предусмотрен; ограничение указано в пояснении под заголовком, чтобы оно не
 * выглядело как «остальные чаты потерялись».
 *
 * Само чтение истории и запись файла делает {@link DdgChatExporter} в отдельном
 * потоке. Здесь только запуск, спиннер и результат.
 */
public class DdgChatExportActivity extends DdgCategoryActivity {

    private final List<DdgChatExporter.DialogEntry> dialogs = new ArrayList<>();
    private AlertDialog progressDialog;
    /**
     * Ручка идущей выгрузки. Нужна, чтобы остановить её вместе с экраном: сам
     * экспортёр в своём потоке не знает, виден ли ещё фрагмент, и иначе дочитал бы
     * историю чата до конца впустую.
     */
    private DdgChatExporter.Job exportJob;

    @Override
    protected int titleRes() {
        return R.string.DDGChatExportTitle;
    }

    @Override
    protected int searchGuid() {
        // 31000 занят экраном плагинов. Идентификатор общий для всех экранов
        // приложения, поэтому брать «свободный на глаз» нельзя: при совпадении
        // история запросов из двух разных экранов смешивается в одну.
        return 31400;
    }

    @Override
    protected int searchIcon() {
        return R.drawable.msg_download;
    }

    @Override
    protected int searchPrefixRes() {
        return R.string.DDGChatExportTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        boolean result = super.onFragmentCreate();
        // Список снимается на UI-потоке: это обход уже готовых ссылок, он ничего
        // не стоит, зато обходить его из фонового потока нельзя — список диалогов
        // меняется на UI-потоке, и обход гоняется с ним.
        loadDialogs(DdgChatExporter.snapshotDialogs(getMessagesController()));
        return result;
    }

    @Override
    public void onFragmentDestroy() {
        // Сначала отмена, и только потом разборка UI. Обратный порядок успел бы
        // закрыть окно, после чего выгрузка продолжила бы читать историю чата,
        // не зная, что её итог никто уже не заберёт.
        if (exportJob != null) {
            exportJob.cancel();
            exportJob = null;
        }
        dismissProgress();
        super.onFragmentDestroy();
    }

    /**
     * Имена и виды чатов достраиваются в отдельном потоке.
     *
     * <p>Имя приходится искать в кэше контроллера, а при промахе — в базе, и на
     * аккаунте с сотней диалогов это десятки запросов к SQLite. На UI-потоке такой
     * список заметно подтормаживал бы открытие экрана.
     */
    private void loadDialogs(List<TLRPC.Dialog> snapshot) {
        Thread thread = new Thread(() -> {
            final ArrayList<DdgChatExporter.DialogEntry> loaded;
            try {
                loaded = DdgChatExporter.dialogs(currentAccount, snapshot);
            } catch (Throwable t) {
                FileLog.e("DdgChatExportActivity failed to list dialogs", t);
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentActivity() == null) {
                        return;
                    }
                    // Молча показать «чатов нет» значило бы солгать: список не пуст,
                    // экран просто не смог его показать.
                    BulletinFactory.of(DdgChatExportActivity.this)
                            .createErrorBulletin(getString(R.string.DDGChatExportFailed))
                            .show();
                });
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                // Экран могли закрыть, пока список собирался: пересобирать строки
                // в невидимом фрагменте бессмысленно, а getParentActivity() здесь ещё и
                // спасает от NPE в rebuildRows.
                if (getParentActivity() == null) {
                    return;
                }
                dialogs.clear();
                dialogs.addAll(loaded);
                rebuildRows();
            });
        }, "DdgChatExportDialogs");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.info(R.string.DDGChatExportInfo));
        rows.add(Row.header(R.string.DDGChatExportHeader));

        if (dialogs.isEmpty()) {
            rows.add(Row.info(R.string.DDGChatExportNoChats));
            return rows;
        }

        for (DdgChatExporter.DialogEntry entry : dialogs) {
            final long dialogId = entry.getId();
            final String name = entry.getName();
            rows.add(Row.link(name, iconFor(entry.getKind()), () -> startExport(dialogId, name)));
        }
        return rows;
    }

    /** Иконка по виду чата: иначе сотня диалогов выглядит одним списком одинаковых строк. */
    private static int iconFor(int kind) {
        switch (kind) {
            case DdgChatExporter.KIND_USER:
                return R.drawable.msg_contacts;
            case DdgChatExporter.KIND_GROUP:
                return R.drawable.msg_groups;
            case DdgChatExporter.KIND_FORUM:
                return R.drawable.msg_discussion;
            default:
                return R.drawable.msg_channel;
        }
    }

    private void startExport(long dialogId, String name) {
        if (getParentActivity() == null) {
            return;
        }
        if (progressDialog != null) {
            // Экспорт уже идёт. Второй запуск всё равно упёрся бы в ту же очередь
            // экспортёра и тихо ждал бы её, поэтому честнее сказать об этом сразу.
            BulletinFactory.of(this)
                    .createErrorBulletin(getString(R.string.DDGChatExportBusy))
                    .show();
            return;
        }

        progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();

        exportJob = new DdgChatExporter.Job();
        DdgChatExporter.export(currentAccount, dialogId, exportJob, new DdgChatExporter.Callback() {
            @Override
            public void onResult(DdgChatExporter.Result result) {
                exportJob = null;
                dismissProgress();
                if (result.getCancelled()) {
                    // Пользователь закрыл экран. Сообщать ему об ошибке тут не о чем.
                    return;
                }
                if (getParentActivity() == null) {
                    // Экран закрыли, пока шла выгрузка: bulletin показывать негде,
                    // а шаринг без активности всё равно бросил бы исключение.
                    return;
                }
                if (result.getEmpty()) {
                    // Пустой чат — не поломка: в базе просто нет ни одного сообщения,
                    // и сказать об этом отдельной строкой полезнее, чем об ошибке.
                    BulletinFactory.of(DdgChatExportActivity.this)
                            .createErrorBulletin(getString(R.string.DDGChatExportEmpty))
                            .show();
                    return;
                }
                File file = result.getFile();
                if (result.getError() != null || file == null) {
                    BulletinFactory.of(DdgChatExportActivity.this)
                            .createErrorBulletin(getString(R.string.DDGChatExportFailed))
                            .show();
                    return;
                }
                BulletinFactory.of(DdgChatExportActivity.this)
                        .createSuccessBulletin(formatString(R.string.DDGChatExportDone,
                                name, result.getMessageCount(), result.getMediaCount()))
                        .show();
                share(file, name);
            }
        });
    }

    private void dismissProgress() {
        if (progressDialog == null) {
            return;
        }
        try {
            progressDialog.dismiss();
        } catch (Throwable ignore) {
            // Окно могло уже быть закрыто вместе с экраном.
        }
        progressDialog = null;
    }

    /**
     * Отдать файл наружу.
     *
     * <p>Тип {@code text/plain} выбран вместо {@code text/markdown}: расширение
     * {@code .md} знают единицы приложений, а текстовый файл подхватят и редакторы,
     * и всё, что умеет показывать {@code text/*}.
     */
    private void share(File file, String name) {
        try {
            Uri uri = FileProvider.getUriForFile(getParentActivity(),
                    ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, name);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, getString(R.string.ShareFile)));
        } catch (Throwable t) {
            FileLog.e("DdgChatExportActivity failed to share export", t);
            BulletinFactory.of(this)
                    .createErrorBulletin(getString(R.string.DDGChatExportFailed))
                    .show();
        }
    }
}
