// Copyright PolyAI Limited

//  EventLogger.kt — Examples/compose/07-playground

package ai.poly.examples.playground.compose

import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.debugDetail
import ai.poly.messaging.debugSummary
import java.text.DateFormat
import java.util.Date

object EventLogger {

    fun makeEntry(msg: String, detail: String? = null): LogEntry {
        // A localized medium time style — "HH:mm:ss" localized.
        val ts = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())
        return LogEntry(summary = "[$ts] $msg", detail = detail)
    }

    fun makeEntry(event: MessagingEvent): LogEntry =
        makeEntry(event.debugSummary, event.debugDetail)
}
