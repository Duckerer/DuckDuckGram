package com.duckduckgram.duckerer.export

import org.telegram.SQLite.SQLiteCursor
import org.telegram.SQLite.SQLiteDatabase
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.DialogObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.MessagesStorage
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Экспорт переписки в Markdown.
 *
 * Источник истории — локальная база (`messages_v2`), а не память контроллера.
 * В памяти лежит только то, что открывали и что ещё не вытеснено, поэтому экспорт
 * из памяти молча отдавал бы последние пару сотен сообщений и называл это всей
 * перепиской. Из базы доступна вся история, какая есть на устройстве.
 *
 * Что файл НЕ содержит — и это написано в его шапке, а не спрятано:
 *  - самих медиафайлов: молча копировать их из кэша Telegram означало бы незаметно
 *    размножить гигабайты, поэтому даётся таблица с именами, размерами и признаком
 *    «файл уже лежит на устройстве»;
 *  - истории, которой в базе нет (удалённой до установки приложения, с другого
 *    аккаунта): взять её неоткуда, и тишина тут была бы враньём.
 *
 * Форматирование не выбрасывается в plain text: сущности Telegram (bold, italic,
 * code, pre, ссылки, спойлеры) раскладываются в разметку Markdown, поэтому
 * `**жирный**` и блок кода остаются ими после экспорта.
 *
 * Всё пишется потоково, во временные файлы, а не в память: чаты на сотни тысяч
 * сообщений существуют, и список из них в виде ArrayList<Message> — это верный
 * способ уронить приложение на экспорте.
 */
object DdgChatExporter {

    /**
     * Свой поток, а не чужая очередь: чтение всей истории одного чата — это десятки
     * тысяч строк из SQLite, выполнять это в общей очереди нельзя. Одного потока
     * достаточно: экспорт запускают вручную и по одному чату за раз.
     */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DdgChatExporter").apply { isDaemon = true }
    }

    /** Потолок на число сообщений в одном файле: страховка от многолетнего чата. */
    private const val MAX_MESSAGES = 100_000

    /** Сколько чатов показывать на экране выбора: список чатов бывает длинным. */
    const val MAX_DIALOGS_IN_LIST = 300

    /** Итог экспорта. `file == null` означает, что ничего не вышло. */
    class Result(
        val file: File?,
        val messageCount: Int,
        val mediaCount: Int,
        val error: Throwable?,
        /**
         * В базе нет ни одного сообщения этого чата.
         *
         * Отдельный флаг, а не разновидность ошибки: пустой чат — это не поломка,
         * и показывать про него то же сообщение, что про упавший экспорт, значило бы
         * врать пользователю о том, что случилось.
         */
        val empty: Boolean = false,
        /**
         * Экспорт остановлен, потому что его отменили.
         *
         * Тоже отдельный флаг: отмена — не ошибка, и показывать про неё «экспорт
         * не удался» значило бы сообщить пользователю о поломке приложения там,
         * где он сам закрыл экран и ничего не сломал.
         */
        val cancelled: Boolean = false,
    )

    /**
     * Ручка одного запуска экспорта.
     *
     * Нужна для отмены: сам вызов живёт в своём потоке и не знает, виден ли ещё
     * экран. Без ручки единственный способ прекратить работу — ждать, пока
     * выгрузка ста тысяч сообщений дойдёт до конца, и всё это время держать
     * фрагмент в памяти.
     */
    class Job {
        @Volatile
        var cancelled = false

        fun cancel() {
            cancelled = true
        }
    }

    /** Чат для экрана выбора. */
    class DialogEntry(val id: Long, val name: String, val kind: Int)

    /** Виды чатов: нужны только для иконки в списке выбора. */
    const val KIND_USER = 0
    const val KIND_GROUP = 1
    const val KIND_CHANNEL = 2
    const val KIND_FORUM = 3

    /** Вызов всегда приходит в UI-потоке. */
    interface Callback {
        fun onResult(result: Result)
    }

    /**
     * Собирает Markdown по чату и кладёт его в `ddg_export` рядом с файлами приложения.
     * Работа уходит в отдельный поток, результат возвращается через [Callback].
     *
     * [job] позволяет отменить выгрузку: экран держит ручку и отменяет её в
     * `onFragmentDestroy`, иначе закрытый экран ждал бы конца выгрузки впустую.
     */
    @JvmStatic
    fun export(accountNum: Int, dialogId: Long, job: Job, callback: Callback) {
        executor.execute {
            if (job.cancelled) {
                postResult(callback, Result(null, 0, 0, null, false, true))
                return@execute
            }
            val result = try {
                runExport(accountNum, dialogId, job)
            } catch (t: Throwable) {
                FileLog.e("DdgChatExporter failed for dialog " + dialogId, t)
                Result(null, 0, 0, t)
            }
            postResult(callback, result)
        }
    }

    private fun postResult(callback: Callback, result: Result) {
        AndroidUtilities.runOnUIThread { callback.onResult(result) }
    }

    /** Тело экспорта. Вызывается только из [executor], то есть не в UI-потоке. */
    private fun runExport(accountNum: Int, dialogId: Long, job: Job): Result {
        val clientUserId = UserConfig.getInstance(accountNum).clientUserId
        val names = Names(accountNum)
        val title = chatTitle(accountNum, dialogId)
        val root = ApplicationLoader.applicationContext.getExternalFilesDir(null)
            ?: throw IllegalStateException("no external files dir")
        val dir = File(root, EXPORT_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cannot create " + dir.absolutePath)
        }
        // Метка у временных имён — до миллисекунды: секундная совпала бы у двух
        // экспортов, запущенных в одну секунду (например, сразу после отмены
        // первого), и второй молча перезаписал бы временные файлы первого прямо в
        // момент чтения.
        val stampFile = SimpleDateFormat(STAMP_FILE_MS, Locale.US).format(Date())
        val bodyPart = File(dir, ".body_" + stampFile + ".tmp")
        val mediaPart = File(dir, ".media_" + stampFile + ".tmp")
        val stage = File(dir, ".out_" + stampFile + ".tmp")
        // Имя итогового файла, наоборот, читается человеком, поэтому метка в нём
        // секундная — но тогда два экспорта одного чата в одну секунду получили бы
        // одно имя, и второй затёр бы первый. uniqueTarget разрешает это суффиксом.
        val target = uniqueTarget(dir, sanitizeFileName(title) + "_" + stampReadableFile() + ".md")
        dropStaleTemps(dir)

        var messages = 0
        var media = 0
        var truncated = false
        var firstStamp: String? = null
        var lastStamp: String? = null

        try {
            val body = openWriter(bodyPart)
            try {
                val mediaOut = openWriter(mediaPart)
                try {
                    // Отдельное соединение с той же базой, см. openReadConnection.
                    val db = openReadConnection(accountNum)
                    try {
                        val cursor: SQLiteCursor = db.queryFinalized(
                            // dialogId приходит из нашего же кода, а не от пользователя:
                            // подставлять его в SQL без экранирования безопасно.
                            "SELECT data, mid, date FROM messages_v2 WHERE uid = $dialogId ORDER BY date ASC, mid ASC",
                        )
                        try {
                            while (cursor.next()) {
                                if (messages >= MAX_MESSAGES) {
                                    truncated = true
                                    break
                                }
                                // Отмену проверяем на каждом сообщении, а не раз в конце
                                // цикла: иначе закрытый экран ждал бы, пока дочитаются все
                                // сто тысяч сообщений, а это может занять минуты.
                                if (job.cancelled) {
                                    return Result(null, 0, 0, null, false, true)
                                }
                                val message = readMessage(cursor, clientUserId) ?: continue
                                val stamp = appendMessage(body, mediaOut, names, message)
                                firstStamp = firstStamp ?: stamp
                                lastStamp = stamp
                                messages++
                            }
                        } finally {
                            cursor.dispose()
                        }
                    } finally {
                        db.close()
                    }
                } finally {
                    mediaOut.close()
                }
            } finally {
                body.close()
            }

            if (job.cancelled) {
                return Result(null, 0, 0, null, false, true)
            }
            if (messages == 0) {
                // Отдельный исход: пустой чат и упавший экспорт выглядели бы одинаково,
                // и пользователь не смог бы понять, что именно произошло. Итогового
                // файла к этому моменту ещё нет — он собирается ниже, — так что
                // удалять нечего, остаётся вернуться с признаком «пусто».
                return Result(null, 0, 0, null, true)
            }

            media = countLines(mediaPart)
            writeTarget(stage, title, messages, media, truncated, firstStamp!!, lastStamp!!, bodyPart, mediaPart)
            // Итоговое имя появляется одним шагом, а не растёт по мере записи.
            // Иначе прерванная выгрузка оставила бы после себя файл, который
            // выглядит как готовый экспорт, но обрывается на середине.
            if (!stage.renameTo(target)) {
                // renameTo на разных томах может не сработать; тогда копируем.
                stage.copyTo(target, overwrite = true)
                stage.delete()
            }
        } finally {
            bodyPart.delete()
            mediaPart.delete()
            stage.delete()
        }

        FileLog.d("DdgChatExporter wrote " + target.absolutePath)
        return Result(target, messages, media, null)
    }

    /** Метка для имени итогового файла: секунды, потому что её читает человек. */
    private fun stampReadableFile(): String = SimpleDateFormat(STAMP_FILE, Locale.US).format(Date())

    /**
     * Отдельное соединение с базой этого аккаунта только на время экспорта.
     *
     * <p>`MessagesStorage` работает с базой из одного потока — своей очереди
     * `storageQueue`, — а `SQLiteDatabase` в форке не синхронизирован ничем: чтение
     * из чужого потока делило бы нативное соединение с пишущей транзакцией, а это
     * ровно тот случай, когда SQLite не обещает ничего. Своё соединение эту гонку
     * снимает целиком.
     *
     * <p>Очередь хранилища при этом остаётся свободной: входящие сообщения пишутся
     * как обычно, и выгрузка чата не задерживает их сохранение на минуты.
     *
     * <p>Путь повторяет `MessagesStorage.openDatabase`: у нулевого аккаунта это
     * `files/cache4.db`, у остальных — `files/accountN/cache4.db`. Файл базы
     * `MessagesStorage` нигде не переименовывает, так что путь стабилен.
     */
    private fun openReadConnection(accountNum: Int): SQLiteDatabase {
        val dir = if (accountNum == 0) {
            ApplicationLoader.getFilesDirFixed()
        } else {
            File(ApplicationLoader.getFilesDirFixed(), "account" + accountNum)
        }
        return SQLiteDatabase(File(dir, "cache4.db").path)
    }

    /**
     * Имя, которого ещё нет: `base`, `base_2`, `base_3` и так далее.
     *
     * <p>Проверки «занято ли имя» достаточно, потому что экспорт выполняется в
     * однопоточном [executor]: одновременно двух выгрузок быть не может, и между
     * проверкой и переименованием никто не вклинится.
     */
    private fun uniqueTarget(dir: File, base: String): File {
        var name = base
        var n = 1
        while (File(dir, name).exists()) {
            n++
            name = base.substringBeforeLast('.') + "_" + n + base.substringAfterLast('.', base)
        }
        return File(dir, name)
    }

    /**
     * Убирает временные файлы, оставшиеся от выгрузок, которые убили вместе с
     * приложением.
     *
     * <p>Свежие не трогаем: они могут принадлежать выгрузке, идущей прямо сейчас.
     * Старые — это мусор, который копится при каждом жёстком закрытии процесса.
     */
    private fun dropStaleTemps(dir: File) {
        val files = dir.listFiles() ?: return
        val threshold = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        for (file in files) {
            if (file.isFile && file.name.endsWith(".tmp") && file.lastModified() < threshold) {
                file.delete()
            }
        }
    }

    private fun openWriter(file: File): BufferedWriter =
        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).buffered()

    private fun countLines(file: File): Int {
        if (!file.exists() || file.length() == 0L) {
            return 0
        }
        var lines = 0
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { reader ->
            while (reader.readLine() != null) {
                lines++
            }
        }
        return lines
    }

    /** Склеивает шапку, поток сообщений и таблицу вложений в итоговый файл. */
    private fun writeTarget(
        target: File,
        title: String,
        messages: Int,
        media: Int,
        truncated: Boolean,
        firstStamp: String,
        lastStamp: String,
        bodyPart: File,
        mediaPart: File,
    ) {
        openWriter(target).use { out ->
            // Имя чата в заголовок идёт с заменой переносов: иначе чат, названный
            // в две строки, разорвал бы заголовок на два и второй стал бы текстом.
            out.append("# ").append(title.replace('\n', ' ').replace('\r', ' ')).append("\n\n")
            // Строки шапки идут через formatString, а не через склейку с ": ":
            // Android срезает хвостовые пробелы у строковых ресурсов, и «Exported: »
            // без формата превратился бы в «Exported:2026-10-02».
            out.append(LocaleController.formatString(R.string.DDGChatExportStamp, stampReadable())).append('\n')
            out.append(LocaleController.formatString(R.string.DDGChatExportMessages, messages)).append('\n')
            out.append(LocaleController.formatString(R.string.DDGChatExportMediaItems, media)).append('\n')
            out.append(LocaleController.formatString(R.string.DDGChatExportPeriod, firstStamp, lastStamp)).append('\n')
            if (truncated) {
                out.append(LocaleController.formatString(R.string.DDGChatExportTruncated, MAX_MESSAGES)).append('\n')
            }
            // Что именно файл не содержит, написано в самом файле, а не только в
            // описании экрана: через полгода у кого-то останется лишь этот .md, и
            // молчаливая дыра в нём выглядела бы как «приложение потеряло данные».
            out.append(LocaleController.getString(R.string.DDGChatExportScopeNote)).append('\n')
            out.append('\n')
            copyInto(bodyPart, out)

            out.append("\n---\n\n")
            if (media == 0) {
                out.append(LocaleController.getString(R.string.DDGChatExportNoMedia)).append('\n')
            } else {
                out.append(LocaleController.formatString(R.string.DDGChatExportMediaTitle, media)).append("\n\n")
                out.append(LocaleController.getString(R.string.DDGChatExportTableHeader)).append('\n')
                copyInto(mediaPart, out)
                out.append('\n').append(LocaleController.getString(R.string.DDGChatExportMediaNote)).append('\n')
            }
        }
    }

    /** Перекладывает временный файл в итоговый, не читая его целиком в память. */
    private fun copyInto(part: File, out: BufferedWriter) {
        if (!part.exists()) {
            return
        }
        BufferedReader(InputStreamReader(FileInputStream(part), Charsets.UTF_8)).use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
        }
    }

    /** Читает одно сообщение из строки курсора. `null` — строка битая, пропускаем её. */
    private fun readMessage(cursor: SQLiteCursor, clientUserId: Long): TLRPC.Message? {
        val data = cursor.byteBufferValue(0) ?: return null
        val message = try {
            // Порядок как в MessagesStorage: сначала сам объект, потом пути вложений,
            // и только после этого буфер можно переиспользовать. Здесь exception = false,
            // потому что битые строки базы мы не хотим превращать в исключение всей
            // выгрузки, а просто пропускаем.
            TLRPC.Message.TLdeserialize(data, data.readInt32(false), false)
        } catch (ignore: Throwable) {
            null
        }
        if (message == null) {
            data.reuse()
            return null
        }
        try {
            message.readAttachPath(data, clientUserId)
        } catch (ignore: Throwable) {
            // Без путей вложение всё равно попадёт в файл — по имени и размеру.
        }
        data.reuse()
        message.id = cursor.intValue(1)
        message.date = cursor.intValue(2)
        return message
    }

    /**
     * Раскладывает одно сообщение: подпись, текст в разметке, строку вложения.
     * Возвращает метку времени — она нужна вызывающему для границ периода.
     */
    private fun appendMessage(
        out: BufferedWriter,
        mediaOut: BufferedWriter,
        names: Names,
        message: TLRPC.Message,
    ): String {
        val stamp = SimpleDateFormat(STAMP_TEXT, Locale.US).format(Date(message.date * 1000L))
        val from = names.of(message.from_id)

        out.write("**")
        if (from.isNotEmpty()) {
            out.write(escapeInline(from))
            out.write(" - ")
        }
        out.write(stamp)
        out.write(" - #")
        out.write(message.id.toString())
        out.write("**\n")

        // Сервисные события (вход, закрепление, смена названия) в официальном экспорте
        // Telegram помечаются словом service: тот же смысл и то же имя полезны, если
        // файл потом скормят скрипту.
        if (message.action != null) {
            out.write("> _service: ")
            out.write(snakeCase(message.action.javaClass.simpleName))
            out.write("_\n\n")
            return stamp
        }
        val text = message.message
        if (!text.isNullOrEmpty()) {
            val rendered = renderMarkdown(text, message.entities)
            var firstLine = true
            for (line in rendered.split("\n")) {
                if (!firstLine) {
                    out.write(">\n")
                }
                firstLine = false
                out.write("> ")
                out.write(line)
                out.write("\n")
            }
        }

        val attach = describeMedia(mediaOut, names, message, stamp, from)
        if (attach != null) {
            if (!text.isNullOrEmpty()) {
                out.write(">\n")
            }
            out.write("> ")
            out.write(attach)
            out.write("\n")
        }

        out.write("\n")
        return stamp
    }

    /** Отрезок сущности в границах текста. Смещения приводятся сюда же, а не в сущность. */
    private class Span(val start: Int, val end: Int, val entity: TLRPC.MessageEntity)

    /**
     * Переводит сущности Telegram в Markdown.
     *
     * Сущности вкладываются друг в друга (`**жирный _и_ курсивом**`), поэтому идём по
     * тексту слева направо и держим стек открытых сущностей: на одной позиции сначала
     * открываем самые длинные, при закрытии — самые короткие. Без стека вложенный
     * курсив внутри жирного вышел бы наружу и сломал бы разметку.
     */
    private fun renderMarkdown(text: String, entities: ArrayList<TLRPC.MessageEntity>?): String {
        if (text.isEmpty()) {
            return text
        }
        val usable = ArrayList<Span>()
        if (entities != null) {
            for (entity in entities) {
                // Смещения приходят с сервера, а текст мог быть обрезан или дописан
                // локально, поэтому отрезок подрезается по границам текста. Без этого
                // сущность, конец которой за текстом, не закрылась бы никогда и
                // оставила бы в файле незакрытую разметку.
                val end = minOf(entity.offset + entity.length, text.length)
                if (entity.offset < 0 || entity.offset >= text.length || end <= entity.offset) continue
                usable.add(Span(entity.offset, end, entity))
            }
        }
        if (usable.isEmpty()) {
            return escapeInline(text)
        }
        // При одинаковом начале сначала открывается внешняя сущность (у неё конец
        // дальше), чтобы при закрытии она закрылась последней. По возрастанию
        // конца порядок был бы обратным, и вложенность вложенных сущностей с
        // одинаковым смещением перекрещивалась бы: `**жирный` и `код` в одном
        // месте превратились бы в один незакрытый фрагмент кода.
        usable.sortWith(compareBy({ it.start }, { -it.end }))

        val sb = StringBuilder(text.length + usable.size * 8 + 16)
        val opened = ArrayList<Span>()
        var pos = 0
        var index = 0
        while (pos <= text.length) {
            while (opened.isNotEmpty() && opened.last().end <= pos) {
                sb.append(closingTag(opened.removeAt(opened.size - 1).entity))
            }
            while (index < usable.size && usable[index].start == pos) {
                val span = usable[index]
                opened.add(span)
                sb.append(openingTag(span.entity))
                index++
            }
            if (pos == text.length) break
            // Внутри кода текст должен остаться дословным: экранирование превратило
            // бы, например, `a_b_c` в `a\_b\_c`, и такой код уже не запустится. Под
            // «кодом» понимается и блок, и строчная вставка: в обоих случаях Telegram
            // показывает символы как есть.
            val innermost = if (opened.isEmpty()) null else opened[opened.size - 1].entity
            if (innermost is TLRPC.TL_messageEntityPre || innermost is TLRPC.TL_messageEntityCode) {
                sb.append(text[pos])
            } else if (innermost is TLRPC.TL_messageEntityUrl || innermost is TLRPC.TL_messageEntityEmail) {
                // В автоссылке «<адрес>» экранирование не работает: слэш обратной
                // косой черты внутри угловых скобок попадает в адрес как есть, и
                // адрес перестаёт быть адресом. Символы, которые иначе завершили бы
                // ссылку на полуслове, кодируются процентом — так их понимает любой
                // адрес, независимо от того, чем он открывается.
                sb.append(escapeUrlChar(text[pos]))
            } else if (text[pos] == '\n' && opened.any { it.entity is TLRPC.TL_messageEntityBlockquote }) {
                // Продолжение цитаты. Само тело сообщения уже лежит в блок-цитате
                // (appendMessage ставит «> » перед каждой строкой), поэтому вложенная
                // цитата Telegram — это ровно ещё один «>» без пробела на её маркере.
                // Так цитата не разваливается после первого переноса строки.
                sb.append("\n>> ")
            } else {
                sb.append(escapeChar(text[pos]))
            }
            pos++
        }
        return sb.toString()
    }

    private fun openingTag(entity: TLRPC.MessageEntity): String = when (entity) {
        is TLRPC.TL_messageEntityBold -> "**"
        is TLRPC.TL_messageEntityItalic -> "_"
        is TLRPC.TL_messageEntityUnderline -> "<u>"
        is TLRPC.TL_messageEntityStrike -> "~~"
        is TLRPC.TL_messageEntityCode -> "`"
        // Блок кода оформляется ограждением: язык подсказываем, если сервер его прислал.
        is TLRPC.TL_messageEntityPre ->
            if (entity.language.isNullOrEmpty()) "```\n" else "```" + entity.language + "\n"

        is TLRPC.TL_messageEntitySpoiler -> "||"
        // Цитата Telegram — вложенная цитата Markdown. Тело сообщения уже обёрнуто в
        // «> » силами appendMessage, поэтому здесь нужен ровно один дополнительный
        // маркер и без ведущего переноса: перенос дал бы в файле пустую строку цитаты.
        is TLRPC.TL_messageEntityBlockquote -> " >> "
        is TLRPC.TL_messageEntityTextUrl -> "["
        is TLRPC.TL_messageEntityUrl -> "<"
        is TLRPC.TL_messageEntityEmail -> "<"
        // Упоминания, хештеги, кастомные эмодзи и команды в тексте уже выглядят
        // как надо: оборачивать их нечем, а экранирование испортило бы читаемость.
        else -> ""
    }

    private fun closingTag(entity: TLRPC.MessageEntity): String = when (entity) {
        is TLRPC.TL_messageEntityBold -> "**"
        is TLRPC.TL_messageEntityItalic -> "_"
        is TLRPC.TL_messageEntityUnderline -> "</u>"
        is TLRPC.TL_messageEntityStrike -> "~~"
        is TLRPC.TL_messageEntityCode -> "`"
        is TLRPC.TL_messageEntityPre -> "\n```"
        is TLRPC.TL_messageEntitySpoiler -> "||"
        // У цитаты закрывающего маркера нет: он ставится на каждом переносе строки,
        // см. renderMarkdown.
        is TLRPC.TL_messageEntityBlockquote -> ""
        // Адрес вставляется как есть, поэтому он берётся в угловые скобки: в ссылке
        // Markdown закрывающая скобка в адресе иначе завершила бы ссылку на полуслове.
        is TLRPC.TL_messageEntityTextUrl -> "](<" + safeUrl(entity.url ?: "") + ">)"
        is TLRPC.TL_messageEntityUrl -> ">"
        is TLRPC.TL_messageEntityEmail -> ">"
        else -> ""
    }

    /** Экранирует символы, которые иначе стали бы разметкой. */
    private fun escapeInline(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            sb.append(escapeChar(c))
        }
        return sb.toString()
    }

    private fun escapeChar(c: Char): String = if (c in SPECIAL) "\\" + c else c.toString()

    /**
     * Символ адреса, который иначе завершил бы ссылку Markdown на полуслове.
     *
     * <p>Экранировать их нечем: внутри `<...>` обратная косая черта не имеет силы,
     * и адрес со слэшем просто перестал бы открываться. Процентное кодирование
     * понимают все, поэтому оно и выбрано.
     */
    private fun escapeUrlChar(c: Char): String = when (c) {
        '<', '>' -> "%3" + if (c == '<') "C" else "E"
        ' ', '\n', '\r' -> "%20"
        else -> c.toString()
    }

    /** Адрес для `](<...>)`: то же, что [escapeUrlChar], но для всей строки сразу. */
    private fun safeUrl(url: String): String {
        if (url.none { it == '<' || it == '>' || it == ' ' || it == '\n' || it == '\r' }) {
            return url
        }
        val sb = StringBuilder(url.length + 8)
        for (c in url) {
            sb.append(escapeUrlChar(c))
        }
        return sb.toString()
    }

    /**
     * Описание вложения строкой для тела сообщения; попутно строка в таблицу.
     * Возвращает `null`, если это не вложение и показывать нечего.
     */
    private fun describeMedia(
        mediaOut: BufferedWriter,
        names: Names,
        message: TLRPC.Message,
        stamp: String,
        from: String,
    ): String? {
        val value = MessageObject.getMedia(message) ?: return null
        val loader = FileLoader.getInstance(names.account)

        val kind: String
        val file: String
        val size: Long
        var onDevice = false

        val line = when (value) {
            is TLRPC.TL_messageMediaPhoto -> {
                val photoSize = biggestPhotoSize(value)
                if (photoSize == null) {
                    kind = "photo"
                    file = ""
                    size = 0
                    "[photo, no size]"
                } else {
                    kind = "photo"
                    file = FileLoader.getAttachFileName(photoSize)
                    size = photoSize.size.toLong()
                    onDevice = runCatching { loader.getPathToAttach(photoSize).exists() }.getOrDefault(false)
                    "[photo - $file - ${sizeText(size)}]"
                }
            }
            is TLRPC.TL_messageMediaDocument -> {
                val document = value.document
                if (document == null) {
                    kind = "file"
                    file = ""
                    size = 0
                    "[file, expired]"
                } else {
                    kind = when {
                        value.voice -> "voice"
                        value.video -> "video"
                        value.round -> "round video"
                        else -> document.mime_type?.substringAfterLast('/', "file") ?: "file"
                    }
                    file = document.file_name?.takeIf { it.isNotEmpty() } ?: FileLoader.getAttachFileName(document)
                    size = document.size
                    onDevice = runCatching { loader.getPathToAttach(document).exists() }.getOrDefault(false)
                    "[$kind - $file - ${sizeText(size)}]"
                }
            }
            is TLRPC.TL_messageMediaGeo -> {
                kind = "location"
                file = ""
                size = 0
                geoLine(value.geo, "location")
            }
            is TLRPC.TL_messageMediaGeoLive -> {
                kind = "live location"
                file = ""
                size = 0
                geoLine(value.geo, "live location")
            }
            is TLRPC.TL_messageMediaVenue -> {
                // В этом слое TLRPC название и адрес места разложены прямо в медиа,
                // отдельного объекта Venue тут нет.
                val venueTitle = value.title?.takeIf { it.isNotEmpty() } ?: ""
                val address = value.address?.takeIf { it.isNotEmpty() } ?: ""
                if (venueTitle.isEmpty() && address.isEmpty()) {
                    return null
                }
                kind = "venue"
                file = ""
                size = 0
                "[venue - $venueTitle, $address]"
            }
            is TLRPC.TL_messageMediaContact -> {
                val name = ((value.first_name ?: "") + " " + (value.last_name ?: "")).trim()
                kind = "contact"
                file = ""
                size = 0
                "[contact - $name" + (value.phone_number?.let { " - $it" } ?: "") + "]"
            }
            is TLRPC.TL_messageMediaPoll -> {
                val poll = value.poll
                if (poll == null) {
                    return null
                }
                kind = "poll"
                file = ""
                size = 0
                val sb = StringBuilder("[poll - ")
                sb.append(escapeInline(poll.question.text ?: ""))
                for (answer in poll.answers) {
                    sb.append(" / ").append(escapeInline(answer.text.text ?: ""))
                }
                sb.append(']')
                sb.toString()
            }
            is TLRPC.TL_messageMediaDice -> {
                kind = "dice"
                file = ""
                size = 0
                "[dice ${value.emoticon ?: ""} - ${value.value}]"
            }
            is TLRPC.TL_messageMediaWebPage -> {
                val page = value.webpage
                if (page == null) {
                    return null
                }
                kind = "link"
                file = page.url ?: ""
                size = 0
                val pageTitle = page.title?.takeIf { it.isNotEmpty() }
                    ?: page.site_name?.takeIf { it.isNotEmpty() }
                    ?: file
                "[link - $pageTitle - $file]"
            }
            is TLRPC.TL_messageMediaUnsupported -> {
                kind = "unsupported"
                file = ""
                size = 0
                "[unsupported media]"
            }
            // Платное медиа — контейнер: в нём лежит список вложений, а не один файл.
            // Без ветки такое сообщение попало бы в файл вообще без описания, хотя
            // всё содержимое в базе есть. Список раскладываем по именам частей.
            is TLRPC.TL_messageMediaPaidMedia -> {
                val names2 = ArrayList<String>()
                var bytes = 0L
                for (part in value.extended_media) {
                    // Часть бывает двух видов: с настоящим вложением и превью.
                    // Превью файлом не является, поэтому в перечень не идёт.
                    val inner = (part as? TLRPC.TL_messageExtendedMedia)?.media
                    val doc = (inner as? TLRPC.TL_messageMediaDocument)?.document
                    if (doc != null) {
                        names2.add(doc.file_name?.takeIf { it.isNotEmpty() } ?: FileLoader.getAttachFileName(doc))
                        bytes += doc.size
                        onDevice = onDevice || runCatching { loader.getPathToAttach(doc).exists() }.getOrDefault(false)
                    } else {
                        // Вложение может оказаться и фотографией, и у неё размер живёт не в
                        // документе, а в таблице размеров самой фотографии. Без этой
                        // ветки платное фото выпадало бы из перечня.
                        val photo = (inner as? TLRPC.TL_messageMediaPhoto) ?: continue
                        val photoSize = biggestPhotoSize(photo) ?: continue
                        names2.add(FileLoader.getAttachFileName(photoSize))
                        bytes += photoSize.size
                        onDevice = onDevice || runCatching { loader.getPathToAttach(photoSize).exists() }.getOrDefault(false)
                    }
                }
                if (names2.isEmpty()) {
                    return null
                }
                kind = "paid"
                file = names2.joinToString(" / ")
                size = bytes
                "[paid - " + file + " - " + sizeText(size) + "]"
            }
            else -> return null
        }

        mediaOut.write("| ")
        mediaOut.write(stamp)
        mediaOut.write(" | ")
        mediaOut.write(escapeTableCell(from))
        mediaOut.write(" | ")
        mediaOut.write(kind)
        mediaOut.write(" | ")
        // Обратная кавычка внутри имени закрыла бы ограждение на полуслове, и хвост
        // имени уехал бы в обычный текст. На этот случай ограждение просто не
        // ставится: имя останется читаемым и хотя бы не разъедется.
        val fileCell = escapeTableCell(file)
        mediaOut.write(if (fileCell.isEmpty() || fileCell.contains('`')) fileCell else "`" + fileCell + "`")
        mediaOut.write(" | ")
        mediaOut.write(if (size > 0) sizeText(size) else "-")
        mediaOut.write(" | ")
        mediaOut.write(if (onDevice) "yes" else "no")
        mediaOut.write(" |\n")
        return line
    }

    private fun geoLine(geo: TLRPC.GeoPoint?, kind: String): String {
        if (geo == null) {
            return "[$kind]"
        }
        // Шесть знаков — примерно сантиметр. Полная точность в экспорте не нужна,
        // а лишние цифры в файле выглядят как точность, которой нет.
        return String.format(Locale.US, "[%s - %.6f, %.6f]", kind, geo.lat, geo.longitude())
    }

    /** Долгота в TLRPC названа `_long`; короткое имя нужно, чтобы не путать с `Long`. */
    private fun TLRPC.GeoPoint.longitude(): Double = this._long

    private fun sizeText(size: Long): String = if (size > 0) AndroidUtilities.formatFileSize(size) else "-"

    /** Самая «тяжёлая» версия фото: у экспорта интересует оригинал, а не превью. */
    private fun biggestPhotoSize(media: TLRPC.TL_messageMediaPhoto): TLRPC.PhotoSize? {
        val photo = media.photo ?: return null
        var best: TLRPC.PhotoSize? = null
        for (size in photo.sizes) {
            if (size == null || size.size <= 0) continue
            if (best == null || size.size > best!!.size) {
                best = size
            }
        }
        return best
    }

    /**
     * Имена авторов на один прогон экспорта.
     *
     * <p>Сначала смотрим в кэш контроллера, потом в базу: у экспортируемой истории
     * авторов может не быть в памяти, но они почти наверняка лежат в базе — без
     * этого в файле вместо имён были бы голые числовые идентификаторы.
     *
     * <p>Собственный кэш обязателен именно потому, что база тут не панацея: каждый
     * промах по кэшу контроллера — это запрос к SQLite. Без памяти на прогон в чате
     * на сто тысяч сообщений от двух авторов получилось бы двести тысяч одинаковых
     * запросов, и экспорт выглядел бы как зависший.
     */
    private class Names(val account: Int) {
        private val controller = MessagesController.getInstance(account)
        // У неактивного аккаунта базы может не быть, и синхронные запросы к ней
        // ждут ответа на очереди хранилища, которая тогда не обслуживается. Поэтому
        // у выключенного аккаунта спрашиваем только кэш контроллера: он в памяти и
        // ждать нечего, а имя чата на то и имя, что чата уже нет.
        private val storage = if (UserConfig.isValidAccount(account)) {
            MessagesStorage.getInstance(account)
        } else {
            null
        }

        private val users = HashMap<Long, String>()
        private val chats = HashMap<Long, String>()

        private fun userName(userId: Long): String = users.getOrPut(userId) {
            val user = controller.getUser(userId) ?: storage?.getUserSync(userId)
            user?.let { UserObject.getUserName(it) }.orEmpty()
        }

        /**
         * Имя чата по его положительному идентификатору.
         *
         * <p>Отдельный метод, а не ветка в [of], потому что у чатов и пользователей
         * идентификаторы выглядят одинаково — оба положительные. Различить их можно
         * только по знаку в `dialogId`, и там, где знака нет (внутри `peer_id`
         * сообщения), попытка определить вид по величине искала бы пользователя
         * вместо канала, и в файле вместо названия канала печатался бы «id1000123».
         */
        private fun chatName(chatId: Long): String = chats.getOrPut(chatId) {
            val chat = controller.getChat(chatId) ?: storage?.getChatSync(chatId)
            chat?.title.orEmpty()
        }

        /** Имя чата или пользователя по идентификатору диалога (со знаком). */
        fun of(dialogId: Long): String =
            if (DialogObject.isUserDialog(dialogId)) userName(dialogId) else chatName(-dialogId)

        fun of(peer: TLRPC.Peer?): String = when (peer) {
            is TLRPC.TL_peerUser -> userName(peer.user_id).ifEmpty { "id" + peer.user_id }
            is TLRPC.TL_peerChannel -> chatName(peer.channel_id).ifEmpty { "id" + peer.channel_id }
            is TLRPC.TL_peerChat -> chatName(peer.chat_id).ifEmpty { "id" + peer.chat_id }
            else -> ""
        }

        /** Вид чата для иконки в списке выбора. */
        fun kind(dialogId: Long): Int {
            if (DialogObject.isUserDialog(dialogId)) {
                return KIND_USER
            }
            val chat = controller.getChat(-dialogId) ?: storage?.getChatSync(-dialogId)
            return when {
                chat == null -> KIND_CHANNEL
                chat.forum -> KIND_FORUM
                chat.megagroup -> KIND_GROUP
                else -> KIND_CHANNEL
            }
        }
    }

    /** Заголовок файла и подпись строки в списке выбора — одно и то же имя. */
    @JvmStatic
    fun chatTitle(accountNum: Int, dialogId: Long): String {
        val name = Names(accountNum).of(dialogId)
        return if (name.isEmpty()) "dialog_" + dialogId else name
    }

    /**
     * Снимок списка чатов. Снимать обязательно на UI-потоке.
     *
     * <p>`dialogsByFolder` принадлежит контроллеру и меняется на UI-потоке: папки
     * добавляются, диалоги переезжают между ними. Обход из фонового потока — это
     * гонка с `ConcurrentModificationException` на живом аккаунте, где в момент
     * открытия экрана приходит новое сообщение. Здесь берём сами ссылки на
     * диалоги (список обхода — микросекунды) и передаём их дальше, чтобы тяжёлая
     * работа с именами шла уже в своём потоке.
     */
    @JvmStatic
    fun snapshotDialogs(controller: MessagesController): ArrayList<TLRPC.Dialog> {
        // Обходим все папки, а не getDialogs(0): публичный список одной папки в этом
        // форке попутно применяет настройку «скрыть архив» и трогает состояние
        // сообщества, а нам нужен просто перечень чатов. Плоского публичного поля у
        // контроллера нет — allDialogs protected, — поэтому берём папки и убираем дубли.
        val folders = controller.dialogsByFolder
        val seen = HashSet<Long>()
        val all = ArrayList<TLRPC.Dialog>()
        for (i in 0 until folders.size()) {
            val list = folders.valueAt(i) ?: continue
            for (dialog in list) {
                // Папка — не чат: экспортировать её нельзя. Признак isFolder
                // проставлен не у всех папок (у созданных программно он остаётся
                // ложным), поэтому сверяются оба: и класс, и сам идентификатор.
                if (dialog == null || dialog.isFolder || dialog is TLRPC.TL_dialogFolder) continue
                if (seen.add(dialog.id)) {
                    all.add(dialog)
                }
            }
        }
        all.sortByDescending { it.last_message_date }
        return all
    }

    /** Чаты для экрана выбора, свежие сверху. */
    @JvmStatic
    fun dialogs(accountNum: Int, snapshot: List<TLRPC.Dialog>): ArrayList<DialogEntry> {
        val names = Names(accountNum)
        val out = ArrayList<DialogEntry>()
        for (dialog in snapshot) {
            if (out.size >= MAX_DIALOGS_IN_LIST) {
                break
            }
            val name = names.of(dialog.id)
            if (name.isEmpty()) {
                // Чат без собственного имени — записи об удалённых аккаунтах и
                // мусор в базе. Экспортировать их нечего.
                continue
            }
            // Вид чата берётся из того же [Names]: заводить ради него ещё одну
            // пару обращений к базе незачем, а Names уже держит память на прогон.
            out.add(DialogEntry(dialog.id, name, names.kind(dialog.id)))
        }
        return out
    }

    /** Имя файла без символов, которые запрещены в именах на всех системах. */
    @JvmStatic
    fun sanitizeFileName(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name) {
            val allowed = c.isLetterOrDigit() || c == '-' || c == '_' || c == ' '
            sb.append(if (allowed) c else '_')
        }
        // Обрезка по char'ам безопасна именно потому, что всё, что не буква и не
        // цифра, уже заменено: суррогатные пары (emoji, редкие иероглифы) до
        // take() не доживают, а кириллица и латиница состоят из одного char'а.
        val cleaned = sb.toString().trim()
        if (cleaned.isEmpty()) {
            return "chat"
        }
        return cleaned.take(MAX_NAME_LENGTH).trim().ifEmpty { "chat" }
    }

    private fun stampReadable(): String =
        SimpleDateFormat(STAMP_TEXT, Locale.US).format(Date())

    /** `TL_messageActionPinMessage` -> `t_l_message_action_pin_message`: как в экспорте Telegram. */
    private fun snakeCase(name: String): String {
        val sb = StringBuilder(name.length + 8)
        for (c in name) {
            if (c.isUpperCase() && sb.isNotEmpty() && sb[sb.length - 1] != '_') {
                sb.append('_').append(c.lowercaseChar())
            } else {
                sb.append(c.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * Ячейка таблицы.
     *
     * <p>`|` ломает колонки, поэтому экранируется. Обратная кавычка тоже: имя файла
     * оборачивается в кавычки, и кавычка внутри имени закрыла бы их на полуслове.
     * Переносов строк быть не должно, но если попадут — они тоже ломают таблицу.
     */
    private fun escapeTableCell(text: String): String = text
        .replace("|", "\\|")
        .replace("`", "\\`")
        .replace("\r", " ")
        .replace("\n", " ")

    private const val EXPORT_DIR = "ddg_export"
    private const val STAMP_FILE = "yyyyMMdd_HHmmss"
    private const val STAMP_FILE_MS = "yyyyMMdd_HHmmss_SSS"
    private const val STAMP_TEXT = "yyyy-MM-dd HH:mm:ss"
    private const val MAX_NAME_LENGTH = 60

    /** Сколько живут временные файлы: сутки с запасом на длительный экспорт. */
    private const val TEMP_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private val SPECIAL = charArrayOf('\\', '`', '*', '_', '[', ']', '<', '>', '|')
}
