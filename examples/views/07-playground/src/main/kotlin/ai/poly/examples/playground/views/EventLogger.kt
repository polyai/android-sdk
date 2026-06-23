// Copyright PolyAI Limited

//  EventLogger.kt — Examples/views/07-playground

package ai.poly.examples.playground.views

import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.debugDetail
import ai.poly.messaging.debugSummary
import java.text.DateFormat
import java.util.Date

object EventLogger {

    fun makeEntry(msg: String, detail: String? = null): LogEntry {
        // Localized medium time style — "HH:mm:ss".
        val ts = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        return LogEntry(summary = "[$ts] $msg", detail = detail)
    }

    fun makeEntry(event: MessagingEvent): LogEntry =
        makeEntry(event.debugSummary, event.debugDetail)
}
