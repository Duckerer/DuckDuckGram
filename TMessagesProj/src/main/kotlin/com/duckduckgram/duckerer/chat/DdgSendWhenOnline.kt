package com.duckduckgram.duckerer.chat

import android.widget.Toast
import com.duckduckgram.duckerer.config.DdgConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ContactsController
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.ConnectionsManager

/**
 * Отправка только при онлайне.
 *
 * Вызывается из самого верха `SendMessagesHelper.sendMessage(params)`. Если настройка
 * включена, собеседник сейчас офлайн, а это приватное (не секретное) сообщение без
 * расписания — сообщение не уходит, а встаёт в очередь и показывается тост. Когда
 * `TL_updateUserStatus` сообщает, что собеседник онлайн, очередь для него отправляется
 * теми же `SendMessageParams`.
 *
 * Очередь живёт в памяти. Это честная модель: у Telegram нет backend-«отправить, когда
 * собеседник онлайн», поэтому сообщение либо откладывается локально и уходит по приходу
 * статуса, либо не было бы отправлено вообще.
 */
object DdgSendWhenOnline {

    private data class HeldMessage(val account: Int, val params: SendMessagesHelper.SendMessageParams)

    private data class HeldKey(val account: Int, val peer: Long)

    private val held = HashMap<HeldKey, ArrayList<HeldMessage>>()

    /** Очередь дергается и с UI, и с фоновых путей отправки — одна блокировка на карту. */
    private val heldLock = Any()

    /** Защита от рекурсии: разгребание очереди заново вызывает sendMessage. */
    private var flushing = false

    /**
     * Перехват перед отправкой. Возвращает true, если отправку нужно не выполнять,
     * а отложить. false — отправляем как обычно.
     */
    @JvmStatic
    fun intercept(currentAccount: Int, params: SendMessagesHelper.SendMessageParams): Boolean {
        if (flushing || !DdgConfig.sendOnlyOnline.Bool() || params == null) {
            return false
        }
        val peer = params.peer
        if (!shouldHold(currentAccount, params)) {
            return false
        }
        synchronized(heldLock) {
            held.getOrPut(HeldKey(currentAccount, peer)) { ArrayList() }.add(HeldMessage(currentAccount, params))
        }

        AndroidUtilities.runOnUIThread {
            toast(LocaleController.formatString(R.string.DDGSendOnlyOnlineHeld, peerName(currentAccount, peer)))
        }
        return true
    }

    /**
     * Сигнал из MessagesController.processUpdate: статус пользователя изменился.
     * Если он теперь онлайн — отправляем всё, что накопилось для него.
     */
    @JvmStatic
    fun onUserStatusChanged(currentAccount: Int, userId: Long) {
        if (flushing || !DdgConfig.sendOnlyOnline.Bool()) {
            return
        }
        val queued = synchronized(heldLock) { held[HeldKey(currentAccount, userId)] } ?: return
        if (queued.isEmpty() || !isOnline(currentAccount, userId)) {
            return
        }
        synchronized(heldLock) { held.remove(HeldKey(currentAccount, userId)) }
        flush(queued)
    }

    /** Настройка выключена — сбрасываем копившиеся сообщения в обычную отправку. */
    @JvmStatic
    fun onSettingDisabled() {
        // Осознанно без `if (flushing) return`: идущий flush уже вынул свою запись из
        // held, а здесь мы забираем остаток — наборы не пересекаются, двойной отправки
        // нет. Ранний выход (как было) заставлял тушить настройку мимо очереди, пока
        // параллельно шёл хоть один flush, и оставшиеся сообщения зависали навсегда.
        val all = synchronized(heldLock) {
            val copy = ArrayList<HeldMessage>()
            for (queued in held.values) {
                copy.addAll(queued)
            }
            held.clear()
            copy
        }
        if (all.isEmpty()) {
            return
        }
        flush(all)
    }

    private fun shouldHold(currentAccount: Int, params: SendMessagesHelper.SendMessageParams): Boolean {
        val peer = params.peer
        if (peer <= 0 || peer == UserConfig.getInstance(currentAccount).getClientUserId()) {
            return false
        }
        if (params.scheduleDate != 0 || params.sendingStory != null || params.richMessage != null) {
            return false
        }
        return !isOnline(currentAccount, peer)
    }

    private fun isOnline(currentAccount: Int, peerId: Long): Boolean {
        val user = MessagesController.getInstance(currentAccount).getUser(peerId) ?: return true
        if (UserObject.isDeleted(user) || user.bot || user.status == null) {
            return true
        }
        // Те же правила, что у LocaleController.formatUserStatus: входящий в белый
        // список приватности всегда считается онлайн, остальное — по expires.
        if (MessagesController.getInstance(currentAccount).onlinePrivacy.containsKey(user.id)) {
            return true
        }
        return user.status.expires > ConnectionsManager.getInstance(currentAccount).getCurrentTime()
    }

    private fun flush(queued: ArrayList<HeldMessage>) {
        flushing = true
        try {
            for (msg in queued) {
                // Изоляция: исключение из одного сообщения не должно ронять всю
                // очередь. Неудачное остаётся в рамках этого вызова.
                runCatching {
                    SendMessagesHelper.getInstance(msg.account).sendMessage(msg.params)
                }
            }
        } finally {
            flushing = false
        }
    }

    private fun peerName(currentAccount: Int, peerId: Long): String {
        val user = MessagesController.getInstance(currentAccount).getUser(peerId)
        return if (user != null) {
            ContactsController.formatName(user.first_name, user.last_name)
        } else {
            peerId.toString()
        }
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}