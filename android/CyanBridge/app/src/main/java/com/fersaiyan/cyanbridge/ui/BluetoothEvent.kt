package com.fersaiyan.cyanbridge.ui

/**
 * @author hzy ,
 * @date  2021/1/15
 * <p>
 * "Programs should be written for other people to read,
 * and only incidentally for machines to execute"
 **/
open class BluetoothEvent(val connect:Boolean)

/**
 * Posted when the Oudmon/HeyCyan init flow receives a battery response.
 * Mirrors the vendor app path, where DeviceCmdInit publishes the battery update
 * as soon as syncBattery() completes after service discovery.
 */
class GlassesBatteryUpdateEvent(
    val battery: Int,
    val charging: Boolean,
)
