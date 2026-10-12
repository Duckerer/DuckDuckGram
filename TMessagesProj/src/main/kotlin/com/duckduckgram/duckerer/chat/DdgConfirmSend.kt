package com.duckduckgram.duckerer.chat

import android.app.Activity
import android.view.Gravity
import android.widget.FrameLayout
import com.duckduckgram.duckerer.config.DdgConfig
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BackupImageView
import org.telegram.ui.Components.LayoutHelper

/**
 * Подтверждение отправки: диалог перед тем, как сообщение уйдёт из поля ввода.
 *
 * Взято из NullcoreGram (`tw.nekomimi.nekogram.helpers.MediaConfirmHelper`), где та же
 * идея разнесена на три отдельных переключателя — отдельно для стикеров, гифок и
 * медиа. У нас настройка одна (`DdgConfig.confirmSend`), потому что заводить три
 * переключателя ради одной и той же проверки незачем: пользователю нужно одно
 * «спрашивать перед отправкой», а не три похожих пункта в списке.
 *
 * Модель вызова везде одна: если настройка выключена или показать нечего, метод
 * возвращает false и вызывающий выполняет отправку сам. Если диалог показан —
 * возвращает true, и отправка произойдёт только из кнопки «Отправить».
 */
object DdgConfirmSend {

    /** Настройка выключена — вызывающий отправляет немедленно, диалога не будет. */
    @JvmStatic
    fun disabled(): Boolean = !DdgConfig.confirmSend.Bool()

    /**
     * Подтверждение отправки текстового сообщения, стикера или гифки.
     *
     * Превью для них не показываем: у стикера и гифки свой путь отправки, а обычный
     * текст в этом методе не различается — показывать нечего, только лишний диалог с
     * одним словом.
     */
    @JvmStatic
    fun checkSimple(fragment: BaseFragment?, titleRes: Int, messageRes: Int, onConfirm: Runnable?): Boolean {
        if (disabled() || fragment == null || onConfirm == null) {
            return false
        }
        val activity = fragment.parentActivity ?: return false

        val builder = AlertDialog.Builder(activity)
        builder.setTitle(LocaleController.getString(titleRes))
        builder.setMessage(LocaleController.getString(messageRes))
        builder.setPositiveButton(LocaleController.getString(R.string.Send)) { _, _ -> onConfirm.run() }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        fragment.showDialog(builder.create())
        return true
    }

    /**
     * Подтверждение отправки стикера — с его превью.
     *
     * Смысл подтверждения именно тут максимальный: стикер уходит сразу и без
     * возможности забрать его обратно, а в переписке это часто не то, что человек
     * хотел отправить. Превью берём из самого документа тем же способом, что и
     * форк при показе стикеров (ImageLocation.getForDocument).
     */
    @JvmStatic
    fun checkSticker(fragment: BaseFragment?, sticker: TLRPC.Document?, onConfirm: Runnable?): Boolean {
        if (disabled() || fragment == null || onConfirm == null) {
            return false
        }
        val activity = fragment.parentActivity ?: return false

        val builder = AlertDialog.Builder(activity)
        builder.setTitle(LocaleController.getString(R.string.DDGConfirmSendTitle))
        if (sticker != null) {
            val container = FrameLayout(activity)
            val imageView = BackupImageView(activity)
            imageView.setAspectFit(true)
            imageView.setImage(ImageLocation.getForDocument(sticker), "130_130", "webp", null, sticker)
            // Отступы передаём float: у LayoutHelper есть перегрузки createFrame и с int,
            // и с float по ширине, и целочисленные литералы в Kotlin подходят под обе —
            // вызов становится неоднозначным. dp-семантика у createFrame правильная.
            container.addView(imageView, LayoutHelper.createFrame(130, 130f, Gravity.CENTER, 0f, 12f, 0f, 8f))
            builder.setView(container)
        } else {
            builder.setMessage(LocaleController.getString(R.string.DDGConfirmSendStickerMessage))
        }
        builder.setPositiveButton(LocaleController.getString(R.string.Send)) { _, _ -> onConfirm.run() }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        fragment.showDialog(builder.create())
        return true
    }

    /**
     * Подтверждение отправки фото или видео.
     *
     * Одиночное медиа показываем превью, набор — только заголовок с числом файлов:
     * превью десяти картинок в диалоге подтверждения ничего не объясняет, только
     * занимает место. Считать самим файлом нельзя — у SendingMediaInfo путь может быть
     * пустым, пока файл пишется, поэтому при пустом пути показываем текст.
     */
    @JvmStatic
    fun checkMedia(
        fragment: BaseFragment?,
        photos: ArrayList<SendMessagesHelper.SendingMediaInfo>?,
        onConfirm: Runnable?
    ): Boolean {
        if (disabled() || fragment == null || onConfirm == null || photos.isNullOrEmpty()) {
            return false
        }
        val activity = fragment.parentActivity ?: return false

        val builder = AlertDialog.Builder(activity)
        if (photos.size == 1) {
            val info = photos[0]
            builder.setTitle(
                LocaleController.getString(
                    if (info.isVideo) R.string.DDGConfirmSendVideoTitle else R.string.DDGConfirmSendPhotoTitle
                )
            )
            // Проверка именно на null, а не isNullOrEmpty: иначе Kotlin не сузит тип до
            // String и setImage(path, ...) не проходит компиляцию.
            val path = info.path ?: info.imagePath
            if (path == null || path.isEmpty()) {
                builder.setMessage(LocaleController.getString(R.string.DDGConfirmSendMediaMessage))
            } else {
                val container = FrameLayout(activity)
                val imageView = BackupImageView(activity)
                imageView.setRoundRadius(AndroidUtilities.dp(8f))
                imageView.setImage(path, null as String?, null)
                container.addView(imageView, LayoutHelper.createFrame(140, 140f, Gravity.CENTER, 0f, 12f, 0f, 8f))
                builder.setView(container)
            }
        } else {
            builder.setTitle(
                LocaleController.formatString(R.string.DDGConfirmSendMediaTitleMultiple, photos.size)
            )
            builder.setMessage(LocaleController.getString(R.string.DDGConfirmSendMediaMessage))
        }
        builder.setPositiveButton(LocaleController.getString(R.string.Send)) { _, _ -> onConfirm.run() }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
        fragment.showDialog(builder.create())
        return true
    }
}
