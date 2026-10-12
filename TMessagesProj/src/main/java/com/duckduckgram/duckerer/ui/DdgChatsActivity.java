package com.duckduckgram.duckerer.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

import com.duckduckgram.duckerer.config.DdgConfig;

import tw.nekomimi.nekogram.NekoConfig;

import xyz.nextalone.nagram.NaConfig;

/**
 * Категория «Chats».
 *
 * Здесь смешаны два источника настроек: то, что в форке уже есть
 * ({@link NekoConfig}, NaConfig), берётся оттуда, остальное — из
 * {@link DdgConfig}. Дублировать существующую настройку нельзя: одна и та же
 * галочка в двух местах со временем разъезжается.
 */
public class DdgChatsActivity extends DdgCategoryActivity {

    @Override
    protected int titleRes() {
        return R.string.DDGCategoryChats;
    }

    @Override
    protected int searchGuid() {
        return 30100;
    }

    @Override
    protected int searchIcon() {
        return R.drawable.msg_discussion;
    }

    @Override
    protected int searchPrefixRes() {
        return R.string.DDGCategoryChats;
    }

    @Override
    public boolean onFragmentCreate() {
        DdgConfig.loadConfig(false);
        return super.onFragmentCreate();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.header(R.string.DDGCategoryChats));
        rows.add(Row.toggle(R.string.DDGConfirmSend, R.string.DDGConfirmSendInfo, DdgConfig.confirmSend));
        rows.add(Row.toggle(R.string.DDGSendOnlyOnline, R.string.DDGSendOnlyOnlineInfo, DdgConfig.sendOnlyOnline));
        rows.add(Row.toggle(R.string.DDGQuickShareMedia, R.string.DDGQuickShareMediaInfo, DdgConfig.quickShareMedia));
        rows.add(Row.toggle(R.string.DDGForceCopy, R.string.DDGForceCopyInfo, DdgConfig.forceCopy));
        rows.add(Row.toggle(R.string.DDGSkipUndoTimer, DdgConfig.skipUndoTimer));
        rows.add(Row.toggle(R.string.DDGHighlightAllChats, DdgConfig.highlightAllChats));
        rows.add(Row.action(R.string.DDGMoveAllChatsToFolder, R.drawable.msg_folders, this::openMoveAllChats));
        rows.add(Row.toggle(R.string.DDGMillisecondTimestamp, DdgConfig.millisecondTimestamp));
        rows.add(Row.toggle(R.string.DDGHideMessageTime, R.string.DDGHideMessageTimeInfo, DdgConfig.hideMessageTime));
        rows.add(Row.toggle(R.string.DDGViewOnceUnlimited, R.string.DDGViewOnceUnlimitedInfo,
                DdgConfig.viewOnceUnlimited));

        rows.add(Row.header(R.string.DDGCategoryMedia));
        rows.add(Row.toggle(R.string.DDGDisableBigEmoji, DdgConfig.disableBigEmoji));
        rows.add(Row.toggle(R.string.DDGRestyledPlayer, R.string.DDGRestyledPlayerInfo, DdgConfig.restyledPlayer));

        rows.add(Row.header(R.string.DDGCategoryTranslate));
        rows.add(Row.toggle(R.string.DDGRealtimeTranslation, R.string.DDGRealtimeTranslationInfo,
                DdgConfig.realtimeTranslation));

        rows.add(Row.header(R.string.DDGChatExportTitle));
        rows.add(Row.link(R.string.DDGChatExportTitle, R.drawable.msg_download, this::openChatExport));

        return rows;
    }

    private void openChatExport() {
        presentFragment(new DdgChatExportActivity());
    }

    /**
     * «Move all chats to a folder»: выбор папки, затем каждый чат из списка
     * добавляется в её alwaysShow (и убирается из neverShow). Папка применяется
     * локально и улетает тем же путём, что правки из редактора фильтров
     * (onFilterUpdate + saveDialogFilter).
     */
    private void openMoveAllChats() {
        List<org.telegram.messenger.MessagesController.DialogFilter> filters =
                getMessagesController().getDialogFilters();
        if (filters == null || filters.isEmpty()) {
            org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                    new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
            builder.setMessage(LocaleController.getString(R.string.DDGNoFolders));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(builder.create());
            return;
        }
        org.telegram.ui.Components.ItemOptions options =
                org.telegram.ui.Components.ItemOptions.makeOptions(this, listView);
        for (org.telegram.messenger.MessagesController.DialogFilter filter : filters) {
            options.add(0, filter.name, () -> moveAllChats(filter));
        }
        options.show();
    }

    private void moveAllChats(org.telegram.messenger.MessagesController.DialogFilter filter) {
        org.telegram.messenger.MessagesController mc = getMessagesController();
        ArrayList<org.telegram.tgnet.TLRPC.Dialog> dialogs = mc.getAllDialogs();
        boolean changed = false;
        if (dialogs != null) {
            for (org.telegram.tgnet.TLRPC.Dialog dialog : dialogs) {
                if (dialog == null) {
                    continue;
                }
                if (!filter.alwaysShow.contains(dialog.id)) {
                    filter.alwaysShow.add(dialog.id);
                    changed = true;
                }
                filter.neverShow.remove(dialog.id);
            }
        }
        if (changed) {
            mc.onFilterUpdate(filter);
            getMessagesStorage().saveDialogFilter(filter, false, true);
            getNotificationCenter().postNotificationName(
                    org.telegram.messenger.NotificationCenter.dialogFiltersUpdated);
        }
    }

    /**
     * Часть переключателей меняет то, что уже закэшировано в LocaleController.
     * Форматтеры времени создаются один раз и переиспользуются, поэтому без явного
     * сброса переключатель выглядел бы как «ничего не произошло» до перезапуска.
     */
    @Override
    protected void onToggleChanged(Row row, int position, boolean enabled) {
        super.onToggleChanged(row, position, enabled);
        if (row.config == DdgConfig.millisecondTimestamp) {
            LocaleController.getInstance().recreateFormatters();
        }
        // «Только онлайн» выключен — накопленные сообщения уходят обычным путём.
        if (row.config == DdgConfig.sendOnlyOnline && !enabled) {
            com.duckduckgram.duckerer.chat.DdgSendWhenOnline.INSTANCE.onSettingDisabled();
        }
    }
}
