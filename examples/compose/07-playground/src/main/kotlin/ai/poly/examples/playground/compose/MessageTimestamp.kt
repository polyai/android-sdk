// Copyright PolyAI Limited

//  MessageTimestamp.kt — Examples/compose/07-playground
//  All inputs are epoch millis (the SDK's `ChatMessage.timestamp` representation).

package ai.poly.examples.playground.compose

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object MessageTimestamp {

    /** ~5 min grouping threshold. */
    const val GROUP_GAP_MILLIS: Long = 5 * 60 * 1_000L

    // Cached — date formatters are expensive and chat views recompute on every scroll.
    // Built from localized templates ("jmm" honors the device's 12/24h setting).
    private val compactFormatter: SimpleDateFormat by lazy {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "jmm"), Locale.getDefault())
    }
    private val weekdayShort: SimpleDateFormat by lazy {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEE"), Locale.getDefault())
    }
    private val monthDay: SimpleDateFormat by lazy {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), Locale.getDefault())
    }
    private val monthDayYear: SimpleDateFormat by lazy {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMdyyyy"), Locale.getDefault())
    }

    fun compactTime(epochMillis: Long): String = compactFormatter.format(Date(epochMillis))

    fun groupHeader(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val time = compactTime(epochMillis)
        val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }

        if (isSameDay(cal, now)) return time

        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (isSameDay(cal, yesterday)) return "Yesterday $time"

        // Same week (last 7 days, not yesterday).
        val weekStart = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }
        if (epochMillis >= weekStart.timeInMillis) {
            return "${weekdayShort.format(Date(epochMillis))} $time"
        }

        // Same year.
        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            return "${monthDay.format(Date(epochMillis))}, $time"
        }

        // Different year.
        return "${monthDayYear.format(Date(epochMillis))}, $time"
    }

    fun shouldInsertSeparator(previousMillis: Long?, currentMillis: Long): Boolean {
        if (previousMillis == null) return true
        return currentMillis - previousMillis > GROUP_GAP_MILLIS
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
