package dev.suyash.dot.core.domain.sync

import dev.suyash.dot.core.domain.model.Task
import dev.suyash.dot.core.domain.model.TaskList

/**
 * Independently-mergeable groups of fields ("registers"). Editing a task's title on one phone and
 * completing it on another must not lose either change, so each register carries its own [Hlc].
 */
// Only ever append: names are stored and synced (older app versions ignore names they don't know).
enum class TaskField { TITLE, NOTES, STATUS, STARRED, DUE, REMINDER, PLACEMENT, DELETED, REPEAT }

enum class ListField { TITLE, POSITION, DELETED }

/** A task plus the sync metadata needed for conflict-free merging. */
data class TaskRecord(
    val task: Task,
    val deleted: Boolean,
    val clocks: Map<TaskField, Hlc>,
) {
    fun clock(field: TaskField): Hlc = clocks[field] ?: Hlc.ZERO
    val latest: Hlc get() = clocks.values.maxOrNull() ?: Hlc.ZERO
}

data class ListRecord(
    val list: TaskList,
    val deleted: Boolean,
    val clocks: Map<ListField, Hlc>,
) {
    fun clock(field: ListField): Hlc = clocks[field] ?: Hlc.ZERO
}

/** Encodes register clocks as `FIELD=hlc;FIELD=hlc` for storage. */
object ClockCodec {
    fun <F : Enum<F>> encode(clocks: Map<F, Hlc>): String =
        clocks.entries
            .sortedBy { it.key.ordinal }
            .joinToString(";") { (field, hlc) -> "${field.name}=${hlc.encode()}" }

    inline fun <reified F : Enum<F>> decode(encoded: String): Map<F, Hlc> {
        if (encoded.isBlank()) return emptyMap()
        val byName = enumValues<F>().associateBy { it.name }
        return encoded.split(';').mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val field = byName[entry.substring(0, eq)] ?: return@mapNotNull null
            field to Hlc.decode(entry.substring(eq + 1))
        }.toMap()
    }
}
