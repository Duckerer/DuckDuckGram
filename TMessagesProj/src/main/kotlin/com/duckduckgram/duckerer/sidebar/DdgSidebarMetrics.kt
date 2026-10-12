package com.duckduckgram.duckerer.sidebar

import com.duckduckgram.duckerer.config.DdgConfig

/**
 * Компактный сайдбар: уменьшенные метрики меню.
 *
 * Сайдбар в форке свой, exteraGram-овский (`app.exteraless.drawer`), и все размеры в
 * нём зашиты числами прямо в вёрстке: 48dp на пункт, 24dp на иконку, 68dp до текста,
 * 160dp на шапку, 48dp на строку аккаунта. Чтобы не плодить второй сайдбар, размеры
 * вынесены сюда и читаются из вёрстки — при выключенной галочке возвращаются штатные
 * значения, то есть поведение полностью совпадает с исходным.
 *
 * Что уменьшается и на сколько:
 *  - пункт меню 48 → 40dp, иконка 24 → 20dp, отступ слева 20 → 12dp, текст 68 → 52dp
 *    от левого края, кегль 15 → 13.5sp, скругление селектора 12 → 8dp;
 *  - вертикальные отступы списка 8 → 4dp и поля разделителей 8 → 4dp;
 *  - шапка 160 → 144dp (блок с именем поднимается с 100dp на 92dp и сжимается с 50dp
 *    до 48dp — ниже нельзя, там аватар), строка аккаунта 44 → 36dp.
 *
 * Идея в том, чтобы при большом количестве аккаунтов и пунктов меню помещалось
 * больше за один экран: полоса прокрутки появляется заметно реже.
 *
 * Метрики читаются и в конструкторах вьюх, и при каждом открытии шторки — см.
 * `DrawerContainer.applySidebarMetrics()`, поэтому переключение галочки подхватывается
 * без перезапуска приложения.
 */
object DdgSidebarMetrics {

    /** Галочка включена. */
    @JvmStatic
    fun compact(): Boolean = DdgConfig.compactSidebar.Bool()

    private fun pick(normal: Int, compact: Int): Int = if (compact()) compact else normal

    private fun pick(normal: Float, compact: Float): Float = if (compact()) compact else normal

    /** Высота одного пункта меню, dp. */
    @JvmStatic
    fun itemHeight(): Int = pick(48, 40)

    /** Размер иконки пункта, dp. */
    @JvmStatic
    fun iconSize(): Int = pick(24, 20)

    /** Отступ иконки от левого края, dp. */
    @JvmStatic
    fun iconLeftPadding(): Int = pick(20, 12)

    /** Отступ текста от левого края, dp. */
    @JvmStatic
    fun textLeftPadding(): Int = pick(68, 52)

    /** Отступ текста от правого края, dp. */
    @JvmStatic
    fun textRightPadding(): Int = pick(16, 12)

    /** Кегль подписи пункта, sp. */
    @JvmStatic
    fun textSize(): Float = pick(15.0f, 13.5f)

    /** Скругление фона пункта, dp. */
    @JvmStatic
    fun selectorRadius(): Int = pick(12, 8)

    /** Вертикальный отступ списка пунктов сверху и снизу, dp. */
    @JvmStatic
    fun listVerticalPadding(): Int = pick(8, 4)

    /**
     * Поля разделителя между группами пунктов, dp.
     *
     * Штатно разделитель имел свои поля: 12dp по горизонтали и 8dp по вертикали.
     * Хранится двумя методами, а не одним, потому что при выключенной галочке
     * значения обязаны совпадать с исходными: общий `dividerMargin` подставлялся
     * во все четыре слота и менял горизонтальные поля 12 → 8 даже при выключенном
     * компактном режиме.
     */
    @JvmStatic
    fun dividerMarginHorizontal(): Int = pick(12, 8)

    @JvmStatic
    fun dividerMarginVertical(): Int = pick(8, 4)

    /** Высота шапки сайдбара, dp. */
    @JvmStatic
    fun headerHeight(): Int = pick(160, 144)

    /**
     * Отступ блока с именем от верхнего края шапки, dp.
     *
     * Штатно блок идёт с 100dp и занимает 50dp, то есть шапка требует 150dp — опустить
     * её выше 88dp нельзя, там аватар (16dp отступ плюс 72dp). Отсюда и предел 144dp
     * у [headerHeight]: при компактных 92 + 48 блок занимает 140dp и целиком помещается.
     */
    @JvmStatic
    fun headerNameTop(): Float = pick(100.0f, 92.0f)

    /** Высота блока с именем в шапке, dp. */
    @JvmStatic
    fun headerNameHeight(): Float = pick(50.0f, 48.0f)

    /** Высота одной строки в списке аккаунтов с зазором, dp. */
    @JvmStatic
    fun accountRowHeight(): Int = pick(48, 40)

    /**
     * Высота самой строки списка аккаунтов (и кнопки «добавить аккаунт»), dp.
     *
     * Отдельно от [accountRowHeight]: строка занимает 44dp плюс 4dp зазора — вместе
     * это и есть те 48dp, из которых считается высота раскрытого списка.
     */
    @JvmStatic
    fun pickerItemHeight(): Int = pick(44, 36)
}
