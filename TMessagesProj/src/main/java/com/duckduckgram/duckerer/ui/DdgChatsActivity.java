package com.duckduckgram.duckerer.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.duckduckgram.duckerer.config.DdgConfig;

import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;

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
        rows.add(Row.toggle(R.string.DDGCollapseDuplicateMessages, R.string.DDGCollapseDuplicateMessagesInfo, DdgConfig.collapseDuplicateMessages));
        rows.add(Row.custom(ConfigCellCustom.CUSTOM_ITEM_DdgCollapseAppearanceCard));
        rows.add(Row.header(R.string.DDGCollapseGradient));
        rows.add(Row.custom(ConfigCellCustom.CUSTOM_ITEM_DdgCollapseColorPicker));
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
     * локально (saveDialogFilter) и отправляется на сервер запросом
     * TL_messages_updateDialogFilter — тем же, что и правки из редактора фильтров.
     * Без серверной части изменение откатывалось бы при следующей синхронизации
     * фильтров (например, с другого устройства).
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
                // «Диалог Архива» (TL_dialogFolder) живёт в общем getDialogs-списке с
                // собственным id-фолдером: раньше он уезжал в alwaysShow мусорной
                // записью, которая нигде не резолвится в чат.
                if (org.telegram.messenger.DialogObject.isFolderDialogId(dialog.id)) {
                    continue;
                }
                long dialogId = dialog.id;
                // Фильтр хранит секретные чаты по id собеседника, а не по id диалога:
                // includesDialog сравнивает с encryptedChat.user_id
                // (MessagesController.updateFilterDialogs). Без этой замены секретный
                // чат попадал бы во alwaysShow, но в папке никогда не появлялся.
                if (org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) {
                    org.telegram.tgnet.TLRPC.EncryptedChat encryptedChat = mc.getEncryptedChat(
                            org.telegram.messenger.DialogObject.getEncryptedChatId(dialogId));
                    if (encryptedChat != null) {
                        dialogId = encryptedChat.user_id;
                    }
                }
                if (!filter.alwaysShow.contains(dialogId)) {
                    filter.alwaysShow.add(dialogId);
                    changed = true;
                }
                filter.neverShow.remove(dialogId);
            }
        }
        if (changed) {
            mc.onFilterUpdate(filter);
            getMessagesStorage().saveDialogFilter(filter, false, true);
            getNotificationCenter().postNotificationName(
                    org.telegram.messenger.NotificationCenter.dialogFiltersUpdated);
            sendFilterToServer(filter);
        }
    }

    /** Отправляет папку на сервер TL_messages_updateDialogFilter, как делает редактор фильтров. */
    private void sendFilterToServer(org.telegram.messenger.MessagesController.DialogFilter filter) {
        org.telegram.tgnet.TLRPC.TL_messages_updateDialogFilter req =
                new org.telegram.tgnet.TLRPC.TL_messages_updateDialogFilter();
        req.id = filter.id;
        org.telegram.tgnet.TLRPC.TL_dialogFilter tlFilter = new org.telegram.tgnet.TLRPC.TL_dialogFilter();
        tlFilter.flags = 0;
        tlFilter.id = filter.id;
        // Битовая раскладка локального DialogFilter (типы = биты 0-4, исключения = биты
        // 5-7) отличается от TL-флагов serializer'а (исключения живут на битах 11-13).
        // Копировать flags напрямую нельзя: serializeToStream пересчитает биты из полей
        // booleans и сотрёт тип папки и исключения (сам плеер потом обработает биты 5-7
        // как мусор). Переводим так же, как редактор FilterCreateActivity.
        int flags = filter.flags;
        tlFilter.contacts = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_CONTACTS) != 0;
        tlFilter.non_contacts = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) != 0;
        tlFilter.groups = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_GROUPS) != 0;
        tlFilter.broadcasts = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_CHANNELS) != 0;
        tlFilter.bots = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_BOTS) != 0;
        tlFilter.exclude_muted = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) != 0;
        tlFilter.exclude_read = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0;
        tlFilter.exclude_archived = (flags & org.telegram.messenger.MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) != 0;
        tlFilter.title = new org.telegram.tgnet.TLRPC.TL_textWithEntities();
        tlFilter.title.text = filter.name == null ? "" : filter.name;
        tlFilter.title.entities = filter.entities == null
                ? new ArrayList<>() : new ArrayList<>(filter.entities);
        if (filter.emoticon != null) {
            tlFilter.emoticon = filter.emoticon;
            tlFilter.flags |= org.telegram.tgnet.TLObject.FLAG_25;
        }
        if (filter.color >= 0) {
            tlFilter.color = filter.color;
            tlFilter.flags |= org.telegram.tgnet.TLObject.FLAG_27;
        } else {
            tlFilter.color = 0;
            tlFilter.flags &= ~org.telegram.tgnet.TLObject.FLAG_27;
        }
        // FLAG_28 (noanimate) serializeToStream выставит сам из поля title_noanimate.
        tlFilter.title_noanimate = filter.title_noanimate;
        org.telegram.messenger.MessagesController mc = getMessagesController();
        // Пины, как в редакторе фильтров (FilterCreateActivity): сортируем по индексу и
        // не дублируем их в include_peers, иначе сервер видит пины и список дважды.
        ArrayList<Long> pinArray = new ArrayList<>();
        org.telegram.messenger.support.LongSparseIntArray newPinned = filter.pinnedDialogs;
        for (int a = 0, N = newPinned.size(); a < N; a++) {
            long key = newPinned.keyAt(a);
            if (org.telegram.messenger.DialogObject.isEncryptedDialog(key)) {
                continue;
            }
            pinArray.add(key);
        }
        Collections.sort(pinArray, (o1, o2) -> {
            int idx1 = newPinned.get(o1);
            int idx2 = newPinned.get(o2);
            if (idx1 > idx2) {
                return 1;
            } else if (idx1 < idx2) {
                return -1;
            }
            return 0;
        });
        for (int b = 0; b < 3; b++) {
            ArrayList<Long> fromArray;
            ArrayList<org.telegram.tgnet.TLRPC.InputPeer> toArray;
            if (b == 0) {
                fromArray = filter.alwaysShow;
                toArray = tlFilter.include_peers;
            } else if (b == 1) {
                fromArray = filter.neverShow;
                toArray = tlFilter.exclude_peers;
            } else {
                fromArray = pinArray;
                toArray = tlFilter.pinned_peers;
            }
            for (long did : fromArray) {
                if (b == 0 && newPinned.indexOfKey(did) >= 0) {
                    continue;
                }
                addDialogPeer(mc, did, toArray);
            }
        }
        req.filter = tlFilter;
        req.flags |= 1;
        getConnectionsManager().sendRequest(req, null);
    }

    private void addDialogPeer(org.telegram.messenger.MessagesController mc, long did,
                               ArrayList<org.telegram.tgnet.TLRPC.InputPeer> out) {
        if (org.telegram.messenger.DialogObject.isEncryptedDialog(did)) {
            return;
        }
        if (did > 0) {
            org.telegram.tgnet.TLRPC.User user = mc.getUser(did);
            if (user != null) {
                org.telegram.tgnet.TLRPC.TL_inputPeerUser inputPeer =
                        new org.telegram.tgnet.TLRPC.TL_inputPeerUser();
                inputPeer.user_id = did;
                inputPeer.access_hash = user.access_hash;
                out.add(inputPeer);
            }
        } else {
            org.telegram.tgnet.TLRPC.Chat chat = mc.getChat(-did);
            if (chat == null) {
                return;
            }
            if (org.telegram.messenger.ChatObject.isChannel(chat)) {
                org.telegram.tgnet.TLRPC.TL_inputPeerChannel inputPeer =
                        new org.telegram.tgnet.TLRPC.TL_inputPeerChannel();
                inputPeer.channel_id = -did;
                inputPeer.access_hash = chat.access_hash;
                out.add(inputPeer);
            } else {
                org.telegram.tgnet.TLRPC.TL_inputPeerChat inputPeer =
                        new org.telegram.tgnet.TLRPC.TL_inputPeerChat();
                inputPeer.chat_id = -did;
                out.add(inputPeer);
            }
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
        // Сворачивание повторов включили/выключили — открытый чат должен
        // пересчитать бейджи и скрытые копии без возврата в чат заново.
        if (row.config == DdgConfig.collapseDuplicateMessages) {
            NotificationCenter.getGlobalInstance()
                    .postNotificationName(NotificationCenter.ddgMessageListReload);
        }
    }

    private DdgCollapsePreviewCell collapsePreviewCell;
    private DdgCollapseColorPickerCell collapseColorPickerCell;

    @Override
    protected android.view.View onCreateCustomViewHolder(android.view.ViewGroup parent, int viewType) {
        if (viewType == ConfigCellCustom.CUSTOM_ITEM_DdgCollapseAppearanceCard) {
            return collapsePreviewCell = new DdgCollapsePreviewCell(parent.getContext(), getParentLayout());
        } else if (viewType == ConfigCellCustom.CUSTOM_ITEM_DdgCollapseColorPicker) {
            collapseColorPickerCell = new DdgCollapseColorPickerCell(parent.getContext(), getResourceProvider());
            collapseColorPickerCell.setOnColorSelected(colorId -> {
                DdgConfig.collapseGradient.setConfigInt(colorId);
                collapseColorPickerCell.setSelectedColorId(colorId, true);
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.ddgMessageListReload);
                if (collapsePreviewCell != null) {
                    collapsePreviewCell.refresh();
                }
            });
            return collapseColorPickerCell;
        }
        return super.onCreateCustomViewHolder(parent, viewType);
    }

    @Override
    protected void onBindCustomViewHolder(androidx.recyclerview.widget.RecyclerView.ViewHolder holder, int position) {
        if (holder.itemView == collapsePreviewCell) {
            collapsePreviewCell.refresh();
        } else if (holder.itemView == collapseColorPickerCell) {
            collapseColorPickerCell.setSelectedColorId(DdgConfig.collapseGradient.Int(), false);
        }
    }
}
