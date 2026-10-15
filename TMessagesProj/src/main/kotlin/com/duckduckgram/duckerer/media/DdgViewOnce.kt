package com.duckduckgram.duckerer.media

import com.duckduckgram.duckerer.config.DdgConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessageObject
import org.telegram.messenger.secretmedia.EncryptedFileInputStream
import org.telegram.tgnet.TLRPC
import java.io.File
import java.io.FileOutputStream

/**
 * View-once без ограничений — по образцу того, как форк умеет сохранять удалённые
 * сообщения (`AyuMessageUtils.decryptAndSaveMedia` + `MessageHelper.getPathToMessage`),
 * но со своей папкой и без изменения чужих настроек.
 *
 * Механика та же, что у AyuGram с удалёнными сообщениями, и она единственно честная:
 * сгоревшее на сервере медиа клиент не воскресит — после просмотра Telegram присылает
 * на место содержимого `photo_empty`/`document_empty`, и запросить файл уже нечем. Зато
 * пока файл ещё на диске (то есть в момент, когда пользователь медиа открыл), его можно
 * расшифровать и положить себе. Дальше копия живёт в приватном каталоге приложения и
 * открывается повторно сколько угодно раз — ровно то же, что даёт «сохранение удалённых».
 *
 * Отличия от AyuGram, и почему они:
 *  - копия кладётся в `filesDir/ddg_view_once`, а не в публичный `Downloads/<AyuGram>`.
 *    Расшифрованное view-once по определению то, что пользователь не хочет показывать
 *    другим приложениям, поэтому приватный каталог тут единственно разумный выбор;
 *  - никакая настройка AyuGram (`enableSaveDeletedMessages`, `messageSavingSaveMedia`)
 *    не читается и не переопределяется: у DDG своя галочка, которую чужие настройки
 *    не задевают;
 *  - имя файла собирается как `<dialogId>_<messageId>_<оригинальное имя>`, поэтому
 *    копия находится обычным префиксным поиском и не зависит от того, что сгорело
 *    (`getPathToMessage` форка опирается на имя файла, выведенное из уже пустого
 *    `document`, и после сгорания оно перестаёт совпадать).
 *
 * Плюс к этому отключается штатный таймер самоуничтожения SecretMediaViewer
 * ([enabledFor]) — пока окно открыто, закрытия по таймеру не происходит.
 *
 * Чего тут по-прежнему нет: показать то, что пользователь ни разу не открывал при жизни
 * медиа. Копия появляется только после первого открытия — раньше расшифровывать нечего,
 * файла с ключом ещё нет.
 */
object DdgViewOnce {

    /** TTL бессрочного медиа: такие не горят, галочка на них не нужна. */
    private const val TTL_NEVER = 0x7FFFFFFF

    /** Имя приватного каталога с копиями внутри `filesDir`. */
    private const val DIR_NAME = "ddg_view_once"

    private fun isSelfDestructing(message: MessageObject): Boolean {
        val owner = message.messageOwner ?: return false
        val ttl = owner.ttl
        // TTL_NEVER — это не «бессрочное», а маркер view-once этого форка: облачное
        // view-once хранится в базе с ttl == 0x7FFFFFFF (см. messages_v2.ttl и
        // SecretMediaViewer.ignoreDelete). Раньше такое медиа пропускалось и фича
        // не работала ровно для настоящих view-once.
        if (ttl == 0) {
            return false
        }
        return owner.media is TLRPC.TL_messageMediaPhoto || owner.media is TLRPC.TL_messageMediaDocument
    }

    /**
     * Медиа ещё настоящее, а не `photo_empty`/`document_empty`. После сгорания обёртка
     * `TL_messageMediaPhoto`/`TL_messageMediaDocument` остаётся той же, но внутри лежит
     * пустой объект — именно его сжигает сервер, и именно его проверяет форк в
     * `SecretMediaViewer.onDeleteReady`.
     */
    private fun hasLiveMedia(message: MessageObject): Boolean {
        val media = message.messageOwner?.media ?: return false
        return when (media) {
            is TLRPC.TL_messageMediaPhoto -> media.photo !is TLRPC.TL_photoEmpty
            is TLRPC.TL_messageMediaDocument -> media.document !is TLRPC.TL_documentEmpty
            else -> false
        }
    }

    /** Приватный каталог с копиями; создаётся лениво, чтобы не жечь диск зря. */
    private fun storageDir(): File? {
        val context = ApplicationLoader.applicationContext ?: return null
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        return dir
    }

    /** Префикс копий конкретного сообщения. */
    private fun prefixOf(message: MessageObject): String = message.getDialogId().toString() + "_" + message.getId() + "_"

    /**
     * Галочка включена и это ровно тот случай, где её нечего делать: медиа с
     * самоуничтожением, не бессрочное. Тогда SecretMediaViewer не запускает таймер
     * закрытия.
     */
    @JvmStatic
    fun enabledFor(message: MessageObject?): Boolean {
        return message != null && DdgConfig.viewOnceUnlimited.Bool() && isSelfDestructing(message)
    }

    /**
     * Стоит ли сейчас попытаться сохранить копию: галочка включена, медиа живое и
     * у клиента нашёлся зашифрованный файл с ключом (то есть он уже скачан).
     *
     * Вызывается из SecretMediaViewer на обеих ветках — фото и видео/GIF.
     */
    @JvmStatic
    fun saveCopy(encryptedFile: File?, baseName: String?, message: MessageObject?): File? {
        if (!DdgConfig.viewOnceUnlimited.Bool() || message == null) {
            return null
        }
        if (encryptedFile == null || !encryptedFile.exists() || encryptedFile.length() <= 0) {
            return null
        }
        if (!isSelfDestructing(message)) {
            return null
        }
        val dir = storageDir() ?: return null
        var name = baseName ?: ""
        if (name.isEmpty() || name == ".enc") {
            name = encryptedFile.name
        }
        if (name.endsWith(".enc")) {
            name = name.substring(0, name.length - 4)
        }
        val output = File(dir, prefixOf(message) + name)
        // копия уже есть — ничего не делаем, лишний проход по файлу не нужен
        if (output.exists() && output.length() > 0) {
            return output
        }
        val keyFile = File(FileLoader.getInternalCacheDir(), encryptedFile.name + ".key")
        if (!keyFile.exists()) {
            FileLog.d("DDG view-once: ключ не найден для " + encryptedFile.getName())
            return null
        }
        return try {
            EncryptedFileInputStream(encryptedFile, keyFile).use { input ->
                FileOutputStream(output).use { out ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) {
                            break
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            FileLog.d("DDG view-once: копия сохранена, " + output.length() + " байт")
            output
        } catch (e: Throwable) {
            FileLog.e("DDG view-once: не удалось сохранить копию", e)
            output.delete()
            null
        }
    }

    /** Найденная ранее копия этого сообщения — берём самую крупную, если их вдруг несколько. */
    @JvmStatic
    fun savedCopy(message: MessageObject?): File? {
        if (!DdgConfig.viewOnceUnlimited.Bool() || message == null) {
            return null
        }
        val dir = storageDir() ?: return null
        val prefix = prefixOf(message)
        val files = dir.listFiles() ?: return null
        var best: File? = null
        for (file in files) {
            if (!file.isFile || !file.name.startsWith(prefix) || file.length() <= 0) {
                continue
            }
            if (best == null || file.length() > best.length()) {
                best = file
            }
        }
        return best
    }

    /**
     * Сгоревшее медиа, для которого есть сохранённая копия. Только для этого случая
     * снимается штатный запрет на открытие (`MessageObject.needDrawBluredPreview`
     * возвращает false у пустого медиа, и SecretMediaViewer по нему просто не открывается).
     * Живое медиа сюда не попадает — для него открытие работает и без нас.
     */
    @JvmStatic
    fun canOpenExpired(message: MessageObject?): Boolean {
        if (!DdgConfig.viewOnceUnlimited.Bool() || message == null || hasLiveMedia(message)) {
            return false
        }
        return savedCopy(message) != null
    }

    /**
     * Фильтр расширения для `PhotoViewer.setImage` по сохранённому файлу. Форк в этой
     * ветке жёстко пишет `"jpg"`, а сгореть может и видео: без правильного расширения
     * просмотрщик не поймёт, что перед ним ролик.
     */
    @JvmStatic
    fun filterFor(file: File?): String {
        if (file == null) {
            return "jpg"
        }
        val name = file.name
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) {
            return "jpg"
        }
        return name.substring(dot + 1)
    }
}