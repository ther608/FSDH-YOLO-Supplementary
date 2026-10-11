package com.lychee.fsdh.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Whole-device power sampling around a measurement window.
 *
 * WHAT THIS CAN AND CANNOT SAY
 * An unprivileged app cannot attribute power to itself. The only readings
 * available are the battery fuel gauge's: instantaneous current, voltage, and
 * (where the device exposes it) remaining energy. Those describe the WHOLE
 * phone -- screen, radio, CPU, GPU, everything -- not this app.
 *
 * So the metric reported here is a DIFFERENCE: mean power while the benchmark
 * runs, minus mean power while the device is idle just before it. A single
 * absolute number would be dominated by the display and would say nothing
 * about inference cost.
 *
 * Two further caveats that belong with any number this produces:
 *  - Fuel gauges quantise coarsely. Steps of 50-100 mA are common, and the
 *    value updates on the order of seconds, so short windows are unreliable.
 *    [Reading.medianCurrentDeltaMa] is the robust statistic to quote.
 *  - The device must be on battery. While charging (or plugged and full) the
 *    current reading is meaningless; [Reading.charging] records this so a run
 *    can be discarded rather than silently misread.
 */
class PowerMonitor(private val context: Context) {

    data class Reading(
        val samples: Int,
        val durationMs: Long,
        val meanCurrentMa: Double,
        val medianCurrentMa: Double,
        val meanVoltageV: Double,
        val meanPowerMw: Double,
        val energyDeltaJoules: Double?,
        val charging: Boolean,
        val batteryPercent: Int,
        /** Per-inference energy, when both an energy delta and a count exist. */
        val energyPerInferenceMj: Double?,
        val supported: Boolean,
        val note: String,
    )

    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private var running = false
    private var startNs = 0L
    private var startEnergyNh: Long? = null
    private val currents = ArrayList<Double>(512)
    private val voltages = ArrayList<Double>(512)

    private fun prop(id: Int): Int? {
        val v = bm?.getIntProperty(id) ?: return null
        // the API returns Integer.MIN_VALUE (or a large negative sentinel) when
        // a property is not implemented; treat those as absent rather than
        // reporting a nonsense reading
        return if (v == Int.MIN_VALUE || v == -Int.MAX_VALUE) null else v
    }

    /**
     * Voltage is NOT a BatteryManager property -- there is no
     * BATTERY_PROPERTY_VOLTAGE. It only arrives on the ACTION_BATTERY_CHANGED
     * sticky broadcast, which registerReceiver(null, ...) returns without
     * actually registering anything.
     */
    private fun stickyBattery(): Intent? =
        runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

    private fun voltageV(): Double? {
        val mv = stickyBattery()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return if (mv > 0) mv / 1000.0 else null
    }

    fun start() {
        currents.clear()
        voltages.clear()
        startNs = System.nanoTime()
        startEnergyNh = prop(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.toLong()
        running = true
    }

    /** Call periodically (e.g. every 200 ms) for the duration of the window. */
    fun sample() {
        if (!running) return
        prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { currents.add(it / 1000.0) } // uA -> mA
        voltageV()?.let { voltages.add(it) }
    }

    fun stop(inferences: Int = 0): Reading {
        running = false
        val durationMs = (System.nanoTime() - startNs) / 1_000_000

        val supported = currents.isNotEmpty() && voltages.isNotEmpty()
        if (!supported) {
            return Reading(
                samples = 0, durationMs = durationMs,
                meanCurrentMa = Double.NaN, medianCurrentMa = Double.NaN,
                meanVoltageV = Double.NaN, meanPowerMw = Double.NaN,
                energyDeltaJoules = null,
                charging = isCharging(), batteryPercent = batteryPercent(),
                energyPerInferenceMj = null, supported = false,
                note = "battery current/voltage properties not exposed by this device",
            )
        }

        val meanI = currents.average()
        val meanV = voltages.average()
        val sorted = currents.sorted()
        val medianI = sorted[sorted.size / 2]
        val meanPower = meanI * meanV

        val endEnergyNh = prop(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.toLong()
        // ENERGY_COUNTER counts down (remaining energy), so the consumed amount
        // is start - end. nWh -> J is x 3600 / 1e9.
        // captured into locals: startEnergyNh is a mutable field, so Kotlin
        // will not smart-cast it
        val startNh = startEnergyNh
        val energyJ = if (startNh != null && endEnergyNh != null) {
            (startNh - endEnergyNh).toDouble() * 3600.0 / 1e9
        } else null

        val perInf = if (energyJ != null && inferences > 0 && energyJ > 0) {
            energyJ * 1000.0 / inferences
        } else null

        return Reading(
            samples = currents.size,
            durationMs = durationMs,
            meanCurrentMa = meanI,
            medianCurrentMa = medianI,
            meanVoltageV = meanV,
            meanPowerMw = meanPower,
            energyDeltaJoules = energyJ,
            charging = isCharging(),
            batteryPercent = batteryPercent(),
            energyPerInferenceMj = perInf,
            supported = true,
            note = "whole-device fuel-gauge reading; subtract the idle baseline " +
                "to attribute load. Sign convention of CURRENT_NOW varies by " +
                "vendor, so the magnitude is used.",
        )
    }

    /** isCharging() is API 23+; the sticky broadcast covers anything older. */
    private fun isCharging(): Boolean {
        bm?.let { if (it.isCharging) return true }
        val status = stickyBattery()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun batteryPercent(): Int =
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    companion object {
        private const val TAG = "PowerMonitor"

        /**
         * Mean-power difference between the load window and the idle window.
         * Positive means the device drew more under load, which is the expected
         * sign; the report says so explicitly so a sign flip is not silently
         * read as an improvement.
         */
        fun deltaMw(idle: Reading, load: Reading): Double? {
            if (!idle.supported || !load.supported) return null
            return load.meanPowerMw - idle.meanPowerMw
        }

        fun log(r: Reading) {
            // The parentheses around the concatenation are load-bearing: `+`
            // binds looser than a method call, so without them .format() applies
            // to the SECOND literal only, and the arguments shift by one --
            // which surfaced as "IllegalFormatConversionException: d != Double".
            val msg = (
                "power: supported=${r.supported} mean=%.1f mA median=%.1f mA %.2f V " +
                    "=> %.1f mW  energyDelta=%s J  charging=%s battery=%d%% samples=%d"
                ).format(
                r.meanCurrentMa, r.medianCurrentMa, r.meanVoltageV, r.meanPowerMw,
                r.energyDeltaJoules?.let { "%.2f".format(it) } ?: "n/a",
                r.charging, r.batteryPercent, r.samples,
            )
            Log.i(TAG, msg)
        }

        /** True when the reading is usable as a load measurement. */
        fun usable(r: Reading): Boolean = r.supported && !r.charging && r.samples >= 5
    }
}
