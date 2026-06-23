// Copyright PolyAI Limited

//  ChatModels.kt — Examples/views/07-playground

package ai.poly.examples.playground.views

import java.util.UUID

/** One row in the playground's debug event log (the logs sheet). */
data class LogEntry(
    val id: UUID = UUID.randomUUID(),
    val summary: String,
    val detail: String?,
)
