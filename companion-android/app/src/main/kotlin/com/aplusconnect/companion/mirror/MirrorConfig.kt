package com.aplusconnect.companion.mirror

/**
 * How big the captured screen should be.
 *
 * A phone screen is huge: 1256x2760 on the test device. Encoding that costs the
 * phone far more than it gains the desktop, which is showing the picture in a
 * card a few hundred pixels wide. The long side is capped and the aspect ratio
 * is kept, so the desktop sees the phone's shape rather than a squashed one.
 *
 * Even numbers only: video encoders work in whole chroma blocks, and an odd
 * width makes some of them round the wrong way or fail outright.
 *
 * Pure, so the arithmetic is testable without a phone.
 */
object MirrorConfig {

    /** Comfortable on a LAN, small enough not to starve anything else. */
    const val DEFAULT_MAX_LONG_SIDE = 1280

    const val DEFAULT_FPS = 30

    const val DEFAULT_BITRATE = 4_000_000

    const val DEFAULT_KEYFRAME_SECONDS = 1

    data class Size(val width: Int, val height: Int)

    /**
     * The capture size for a screen of [screenWidth] x [screenHeight].
     *
     * Never upscales: a screen smaller than the cap is captured at its own size,
     * because inventing pixels costs the encoder and gains the viewer nothing.
     */
    fun fit(screenWidth: Int, screenHeight: Int, maxLongSide: Int = DEFAULT_MAX_LONG_SIDE): Size {
        val width = screenWidth.coerceAtLeast(2)
        val height = screenHeight.coerceAtLeast(2)
        val longSide = maxOf(width, height)
        val cap = maxLongSide.coerceAtLeast(2)

        if (longSide <= cap) return Size(even(width), even(height))

        val ratio = cap.toDouble() / longSide.toDouble()
        return Size(even((width * ratio).toInt()), even((height * ratio).toInt()))
    }

    /** Rounded down to an even number, never below 2. */
    private fun even(value: Int): Int {
        val floored = if (value % 2 == 0) value else value - 1
        return floored.coerceAtLeast(2)
    }
}
