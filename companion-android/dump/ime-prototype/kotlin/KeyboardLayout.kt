package com.aplusconnect.companion.ui.ime

/** What a key does when it is released. */
enum class KeyKind {
    CHAR,
    SHIFT,
    BACKSPACE,
    ENTER,
    SPACE,
    TO_SYMBOLS,
    TO_LETTERS,
    TO_NUMBERS,
    EMOJI,
    SETTINGS,
}

/** Off, one-shot (the next letter is capitalised), or locked. */
enum class ShiftState { OFF, ON, LOCK }

data class Key(
    val label: String,
    val kind: KeyKind = KeyKind.CHAR,
    val output: String = label,
    val weight: Float = 1f,
) {
    val isFunction: Boolean get() = kind != KeyKind.CHAR
}

data class KeyRow(
    val keys: List<Key>,
    /** Keys' worth of empty space on each side, so the row can be narrower. */
    val sideInset: Float = 0f,
)

/**
 * The keyboard's layouts.
 *
 * All of them describe rows of weighted keys, and the view does the geometry.
 * A layout is rebuilt whenever the shift state, the number row or the page
 * changes, so what is drawn and what is touched can never disagree.
 */
object KeyboardLayout {

    private const val LETTERS = "qwertyuiop"
    private const val LETTERS_HOME = "asdfghjkl"
    private const val LETTERS_BOTTOM = "zxcvbnm"

    fun letters(shift: ShiftState, numberRow: Boolean): List<KeyRow> {
        val rows = mutableListOf<KeyRow>()

        if (numberRow) {
            rows += KeyRow("1234567890".map { charKey(it.toString()) })
        }

        rows += KeyRow(LETTERS.map { charKey(cased(it.toString(), shift)) })
        rows += KeyRow(LETTERS_HOME.map { charKey(cased(it.toString(), shift)) }, sideInset = 0.5f)
        rows += KeyRow(
            listOf(
                Key(
                    label = if (shift == ShiftState.LOCK) "⇪" else "⇧",
                    kind = KeyKind.SHIFT,
                    weight = 1.4f,
                ),
            ) + LETTERS_BOTTOM.map { charKey(cased(it.toString(), shift)) } +
                listOf(Key("⌫", KeyKind.BACKSPACE, weight = 1.4f)),
        )
        rows += bottomRow(toSymbols = "?123")
        return rows
    }

    fun symbols(page: Int): List<KeyRow> {
        if (page == 1) {
            return listOf(
                KeyRow("~`|•√π÷×".map { charKey(it.toString()) }),
                KeyRow("£¥€©®™°".map { charKey(it.toString()) }, sideInset = 0.5f),
                KeyRow(
                    listOf(
                        Key("=\\<", KeyKind.TO_SYMBOLS, weight = 1.4f),
                        charKey("∆"), charKey("¶"), charKey("§"), charKey("∞"),
                        charKey("≈"), charKey("≠"),
                        Key("⌫", KeyKind.BACKSPACE, weight = 1.4f),
                    ),
                ),
                bottomRow(toSymbols = "ABC"),
            )
        }

        return listOf(
            KeyRow("@#$%&-+()/".map { charKey(it.toString()) }),
            KeyRow("*\"':;!?".map { charKey(it.toString()) }, sideInset = 0.5f),
            KeyRow(
                listOf(
                    Key("?123", KeyKind.TO_SYMBOLS, weight = 1.4f),
                    charKey("\""), charKey("_"), charKey("="), charKey("["),
                    charKey("]"), charKey("\\"),
                    Key("⌫", KeyKind.BACKSPACE, weight = 1.4f),
                ),
            ),
            bottomRow(toSymbols = "ABC"),
        )
    }

    fun numbers(): List<KeyRow> = listOf(
        KeyRow(listOf(charKey("1"), charKey("2"), charKey("3"))),
        KeyRow(listOf(charKey("4"), charKey("5"), charKey("6"))),
        KeyRow(listOf(charKey("7"), charKey("8"), charKey("9"))),
        KeyRow(listOf(charKey(","), charKey("0"), Key("⌫", KeyKind.BACKSPACE))),
        KeyRow(
            listOf(
                Key("ABC", KeyKind.TO_LETTERS, weight = 1f),
                Key("space", KeyKind.SPACE, weight = 1f),
                Key("↵", KeyKind.ENTER, weight = 1f),
            ),
        ),
    )

    private fun bottomRow(toSymbols: String): KeyRow = KeyRow(
        listOf(
            Key("😀", KeyKind.EMOJI, weight = 1.1f),
            if (toSymbols == "ABC") {
                Key("ABC", KeyKind.TO_LETTERS, weight = 1.3f)
            } else {
                Key(toSymbols, KeyKind.TO_SYMBOLS, weight = 1.3f)
            },
            charKey(","),
            Key("space", KeyKind.SPACE, weight = 3.8f),
            charKey("."),
            Key("↵", KeyKind.ENTER, weight = 1.3f),
        ),
    )

    private fun charKey(text: String): Key =
        Key(label = text, kind = KeyKind.CHAR, output = text)

    fun cased(text: String, shift: ShiftState): String =
        if (shift == ShiftState.OFF) text else text.uppercase()
}

/**
 * The emoji the keyboard offers.
 *
 * A fixed, curated set per category rather than the whole Unicode table: every
 * phone renders these, and a searchable index of 3,600 code points would cost
 * more than it gives on a keyboard that sits above a text field.
 */
object EmojiData {

    data class Category(val label: String, val icon: String, val emoji: List<String>)

    val categories: List<Category> = listOf(
        Category(
            "Smileys", "😀",
            listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
                "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
                "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳",
                "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖", "😫",
                "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳",
                "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭",
                "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯", "😦", "😧",
                "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐", "🥴", "🤢",
                "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👻",
            ),
        ),
        Category(
            "People", "👍",
            listOf(
                "👍", "👎", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘", "🤙",
                "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋",
                "🤝", "🙏", "✍️", "💅", "🤳", "💪", "🦾", "🦵", "🦶", "👂",
                "👀", "👁️", "🧠", "🫀", "🫁", "🦷", "👶", "🧒", "👦", "👧",
                "🧑", "👨", "👩", "🧓", "👴", "👵", "🙆", "🙅", "💁", "🙋",
                "🧏", "🤦", "🤷", "👮", "🕵️", "💂", "👷", "🤴", "👸", "🧙",
                "🧚", "🧛", "🧜", "🧝", "🧞", "🧟", "💆", "💇", "🚶", "🧍",
                "🏃", "💃", "🕺", "👯", "🧘", "🛀", "🛌", "👭", "👫", "👬",
            ),
        ),
        Category(
            "Animals", "🐻",
            listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
                "🦁", "🐮", "🐷", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒", "🐔",
                "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴",
                "🦄", "🐝", "🪱", "🐛", "🦋", "🐌", "🐞", "🐜", "🪰", "🪲",
                "🐢", "🐍", "🦎", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠",
                "🐟", "🐬", "🐳", "🐋", "🦈", "🐊", "🐅", "🐆", "🦓", "🦍",
                "🦧", "🐘", "🦛", "🦏", "🐪", "🐫", "🦒", "🦘", "🐃", "🐄",
                "🐎", "🐖", "🐏", "🐑", "🦙", "🐐", "🦌", "🐕", "🐩", "🦮",
            ),
        ),
        Category(
            "Food", "🍔",
            listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐",
                "🍈", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑",
                "🥦", "🥬", "🥒", "🌶️", "🫑", "🌽", "🥕", "🫒", "🧄", "🧅",
                "🥔", "🍠", "🥐", "🥯", "🍞", "🥖", "🥨", "🧀", "🥚", "🍳",
                "🧈", "🥞", "🧇", "🥓", "🥩", "🍗", "🍖", "🌭", "🍔", "🍟",
                "🍕", "🥪", "🥙", "🧆", "🌮", "🌯", "🫔", "🥗", "🍝", "🍜",
                "🍲", "🍛", "🍣", "🍱", "🥟", "🍤", "🍙", "🍚", "🍘", "🍥",
                "🥠", "🥮", "🍢", "🍡", "🍧", "🍨", "🍦", "🥧", "🧁", "🍰",
                "🎂", "🍮", "🍭", "🍬", "🍫", "🍿", "🍩", "🍪", "☕", "🍵",
            ),
        ),
        Category(
            "Activity", "⚽",
            listOf(
                "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱",
                "🪀", "🏓", "🏸", "🏒", "🏑", "🥍", "🏏", "🪃", "🥅", "⛳",
                "🪁", "🏹", "🎣", "🤿", "🥊", "🥋", "🎽", "🛹", "🛼", "🛷",
                "⛸️", "🥌", "🎿", "⛷️", "🏂", "🪂", "🏋️", "🤼", "🤸", "⛹️",
                "🤺", "🤾", "🏌️", "🏇", "🧘", "🏄", "🏊", "🤽", "🚣", "🧗",
                "🚵", "🚴", "🏆", "🥇", "🥈", "🥉", "🏅", "🎖️", "🏵️", "🎗️",
                "🎫", "🎟️", "🎪", "🤹", "🎭", "🩰", "🎨", "🎬", "🎤", "🎧",
                "🎼", "🎹", "🥁", "🪘", "🎷", "🎺", "🪗", "🎸", "🪕", "🎻",
            ),
        ),
        Category(
            "Travel", "🚗",
            listOf(
                "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
                "🛻", "🚚", "🚛", "🚜", "🦯", "🦽", "🦼", "🛴", "🚲", "🛵",
                "🏍️", "🛺", "🚨", "🚔", "🚍", "🚘", "🚖", "🚡", "🚠", "🚟",
                "🚃", "🚋", "🚞", "🚝", "🚄", "🚅", "🚈", "🚂", "🚆", "🚇",
                "🚊", "🚉", "✈️", "🛫", "🛬", "🛩️", "💺", "🛰️", "🚀", "🛸",
                "🚁", "🛶", "⛵", "🚤", "🛥️", "🛳️", "⛴️", "🚢", "⚓", "🪝",
                "🗺️", "🧭", "🏔️", "⛰️", "🌋", "🗻", "🏕️", "🏖️", "🏜️", "🏝️",
                "🏞️", "🏟️", "🏛️", "🏗️", "🏘️", "🏚️", "🏠", "🏡", "🏢", "🏬",
            ),
        ),
        Category(
            "Objects", "💡",
            listOf(
                "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🖲️", "🕹️", "🗜️",
                "💽", "💾", "💿", "📀", "📼", "📷", "📸", "📹", "🎥", "📽️",
                "🎞️", "📞", "☎️", "📟", "📠", "📺", "📻", "🎙️", "🎚️", "🎛️",
                "🧭", "⏱️", "⏲️", "⏰", "🕰️", "⌛", "⏳", "📡", "🔋", "🪫",
                "🔌", "💡", "🔦", "🕯️", "🪔", "🧯", "🛢️", "💸", "💵", "💴",
                "💶", "💷", "🪙", "💰", "💳", "🧾", "💎", "⚖️", "🪜", "🧰",
                "🪛", "🔧", "🔨", "⚒️", "🛠️", "⛏️", "🪚", "🔩", "⚙️", "🪤",
                "🧱", "⛓️", "🧲", "🔫", "💣", "🧨", "🪓", "🔪", "🗡️", "⚔️",
            ),
        ),
        Category(
            "Symbols", "❤️",
            listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
                "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️",
                "✝️", "☪️", "🕉️", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐",
                "⛎", "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐",
                "♑", "♒", "♓", "🆔", "⚛️", "🉑", "☢️", "☣️", "📴", "📳",
                "🈶", "🈚", "🈸", "🈺", "🈷️", "✴️", "🆚", "💮", "🉐", "㊙️",
                "㊗️", "🈴", "🈵", "🈹", "🈲", "🅰️", "🅱️", "🆎", "🆑", "🅾️",
                "🆘", "❌", "⭕", "🛑", "⛔", "📛", "🚫", "💯", "💢", "♨️",
            ),
        ),
    )

    /** A short, useful set of flags: the ones people actually send. */
    val flags: List<String> = listOf(
        "🇮🇳", "🇺🇸", "🇬🇧", "🇨🇦", "🇦🇺", "🇩🇪", "🇫🇷", "🇯🇵", "🇨🇳", "🇰🇷",
        "🇸🇬", "🇦🇪", "🇸🇦", "🇧🇷", "🇷🇺", "🇮🇹", "🇪🇸", "🇳🇱", "🇸🇪", "🇨🇭",
    )
}