package com.vonage.clientlibrary

/**
 * Represents the cellular connectivity status of the device.
 *
 * Use [VGCellularRequestClient.getCellularStatus] to retrieve the current status
 * before initiating a Silent Authentication flow, so your app can select the
 * appropriate Vonage Verify channel without waiting for a Silent Auth attempt to fail.
 *
 * Typical usage:
 * ```kotlin
 * when (VGCellularRequestClient.getInstance().getCellularStatus()) {
 *     CellularStatus.Available -> {
 *         // Cellular is the active network — proceed with Silent Auth
 *     }
 *     CellularStatus.AvailableNotActive -> {
 *         // Cellular exists but device is on Wi-Fi — Silent Auth may still work
 *         // but will take longer. Consider falling back to SMS/WhatsApp if latency matters.
 *     }
 *     CellularStatus.Unavailable -> {
 *         // No cellular interface — skip Silent Auth and use another Verify channel
 *     }
 * }
 * ```
 */
sealed class CellularStatus {

    /**
     * Cellular is the device's active network. Silent Authentication is very likely
     * to succeed and should be attempted first.
     */
    object Available : CellularStatus()

    /**
     * A cellular interface exists on the device but is not the current active network
     * (the device is likely on Wi-Fi). Silent Authentication may still succeed but
     * will incur additional latency while the SDK forces a cellular connection.
     * Consider your UX tolerance before proceeding.
     */
    object AvailableNotActive : CellularStatus()

    /**
     * No cellular interface was detected. Silent Authentication will not succeed.
     * Fall back to another Vonage Verify channel (e.g. SMS or WhatsApp).
     */
    object Unavailable : CellularStatus()
}
