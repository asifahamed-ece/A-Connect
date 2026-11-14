package com.aplusconnect.companion.ui

/**
 * What the Connect page says about the link, derived from the service.
 *
 * Kept free of Android and Compose so it can be unit tested: a status that
 * lies (audit B2: "CONNECTING" while not trying) is the bug this prevents.
 */
internal enum class ConnectionPhase(val label: String, val tone: Tone, val breathes: Boolean) {
    Off("Off", Tone.Neutral, false),
    NotPaired("Not paired", Tone.Neutral, false),
    Connecting("Connecting…", Tone.Warning, true),
    Connected("Connected", Tone.Success, false),
    Mirroring("Mirroring", Tone.Success, false),
    ComputerOff("Computer is off", Tone.Neutral, false),
    NotConnected("Not connected", Tone.Neutral, false);

    /** The one main action the hero offers in this phase (§10.1). */
    val primaryAction: PrimaryAction
        get() = when (this) {
            Off -> PrimaryAction.TurnOn
            NotPaired -> PrimaryAction.ScanQr
            Connecting -> PrimaryAction.None
            Connected -> PrimaryAction.None
            Mirroring -> PrimaryAction.StopMirroring
            ComputerOff, NotConnected -> PrimaryAction.Reconnect
        }
}

internal enum class Tone { Neutral, Accent, Success, Warning, Danger }

internal enum class PrimaryAction { None, TurnOn, ScanQr, Reconnect, StopMirroring }

/**
 * Maps the service's state to a phase.
 *
 * @param enabled the kill switch.
 * @param hasHost whether a computer address is stored.
 * @param userAskedToConnect the user tapped Scan/Save/Reconnect and no answer has come back yet.
 * @param state `CompanionService.Status.state`, a human string set by the service.
 */
internal fun connectionPhase(
    enabled: Boolean,
    hasHost: Boolean,
    isConnected: Boolean,
    mirroring: Boolean,
    userAskedToConnect: Boolean,
    state: String,
): ConnectionPhase = when {
    !enabled -> ConnectionPhase.Off
    isConnected && mirroring -> ConnectionPhase.Mirroring
    isConnected -> ConnectionPhase.Connected
    !hasHost -> ConnectionPhase.NotPaired
    state.startsWith("Connecting", ignoreCase = true) -> ConnectionPhase.Connecting
    state.contains("retrying", ignoreCase = true) -> ConnectionPhase.Connecting
    state.contains("computer is off", ignoreCase = true) -> ConnectionPhase.ComputerOff
    userAskedToConnect -> ConnectionPhase.Connecting
    else -> ConnectionPhase.NotConnected
}

/** "482913" → "482 913": pairing codes are read in groups of three (§3). */
internal fun groupCode(code: String): String =
    code.filter { !it.isWhitespace() }.chunked(3).joinToString(" ")

/** Host and port as shown in mono; uses the stored port, never a hardcoded one (audit B3). */
internal fun hostPort(host: String, port: Int): String =
    if (host.isEmpty()) "" else "$host:$port"

/** Plain-language direction for a clip; no arrows as icons (§7). */
internal fun directionLabel(direction: String?): String? = when (direction) {
    "pc_to_phone" -> "From computer"
    "phone_to_pc" -> "To computer"
    else -> null
}

/** 1 536 000 → "1.5 MB". Tabular digits are applied where it is drawn. */
internal fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (value >= 100) "${value.toInt()} ${units[i]}" else "%.1f %s".format(java.util.Locale.ROOT, value, units[i])
}
