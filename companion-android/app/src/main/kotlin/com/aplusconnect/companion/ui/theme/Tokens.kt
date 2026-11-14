package com.aplusconnect.companion.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Primitive tokens: the raw scales from docs/DESIGN.md §2.1, §4 and §5.1.
 *
 * Nothing outside `ui/theme` may read these directly. Screens and components
 * read the semantic roles in [AplusColors] through `LocalAplusColors`, or the
 * Material roles built from them in [AplusTheme].
 */
internal object Primitives {
    val Neutral0 = Color(0xFFFFFFFF)
    val Neutral025 = Color(0xFFF7F9FC)
    val Neutral050 = Color(0xFFF1F4F9)
    val Neutral100 = Color(0xFFE6EBF2)
    val Neutral200 = Color(0xFFD3DAE5)
    val Neutral400 = Color(0xFF7D8AA0)
    val Neutral500 = Color(0xFF5E6B80)
    val Neutral600 = Color(0xFF4A5568)
    val Neutral700 = Color(0xFF66738A)
    val Neutral750 = Color(0xFF8D99AB)
    val Neutral800 = Color(0xFFB4BECD)
    val Neutral850 = Color(0xFF273143)
    val Neutral875 = Color(0xFF1A2332)
    val Neutral900 = Color(0xFF131A26)
    val Neutral950 = Color(0xFF0B1018)
    val Ink = Color(0xFF0F1623)
    val Snow = Color(0xFFEEF2F8)
    val Black = Color(0xFF000000)

    val Blue050 = Color(0xFFEAF0FF)
    val Blue100 = Color(0xFFD9E4FF)
    val Blue300 = Color(0xFF9DBBFF)
    val Blue400 = Color(0xFF7AA2FF)
    val Blue600 = Color(0xFF2457E0)
    val Blue700 = Color(0xFF1D47BF)
    val Blue900 = Color(0xFF17264A)
    val Blue950Strong = Color(0xFF1E3363)

    val Green050 = Color(0xFFE6F6EE)
    val Green300 = Color(0xFF43D696)
    val Green700 = Color(0xFF0B7A47)
    val Green950 = Color(0xFF0F2A20)

    val Amber050 = Color(0xFFFFF3DF)
    val Amber300 = Color(0xFFFFB74D)
    val Amber700 = Color(0xFF8F5300)
    val Amber950 = Color(0xFF2E2210)

    val Red050 = Color(0xFFFDECEC)
    val Red300 = Color(0xFFFF8079)
    val Red700 = Color(0xFFC8282A)
    val Red950 = Color(0xFF331718)
}

/** Spacing, the 4 dp rhythm (§4). */
internal object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s7 = 28.dp
    val s8 = 32.dp
    val s10 = 40.dp

    /** Screen margin; 16 dp under 360 dp wide (see [screenMargin]). */
    val screen = 20.dp
    val screenNarrow = 16.dp
}

/** Radius, one step rounder than the desktop (§5.1). */
internal object Radius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 28.dp
}

/** Sizes that recur: touch targets, icons, controls (§7, §8.1). */
internal object Size {
    val target = 48.dp
    val control = 40.dp
    val primaryButton = 52.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val iconXl = 40.dp
    val tile = 40.dp
    val rowSingle = 56.dp
    val rowDouble = 72.dp
    val statusDot = 8.dp
}
