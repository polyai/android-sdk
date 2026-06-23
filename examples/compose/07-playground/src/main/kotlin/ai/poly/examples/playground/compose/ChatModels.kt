// Copyright PolyAI Limited

//  ChatModels.kt — Examples/compose/07-playground

package ai.poly.examples.playground.compose

import java.util.UUID

/** One row in the playground's debug event log ([LogsSheet]). */
data class LogEntry(
    val id: UUID = UUID.randomUUID(),
    val summary: String,
    val detail: String?,
)
