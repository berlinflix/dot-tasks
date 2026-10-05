package dev.suyash.dot.core.domain.model

import java.util.UUID

@JvmInline
value class TaskId(val value: String) {
    /**
     * The id of the next occurrence of a repeating task. Deterministic, so two devices completing the
     * same occurrence offline create the same next task instead of duplicates.
     */
    fun successor(): TaskId = TaskId(UUID.nameUUIDFromBytes("next:$value".toByteArray(Charsets.UTF_8)).toString())

    companion object {
        fun random(): TaskId = TaskId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ListId(val value: String) {
    companion object {
        fun random(): ListId = ListId(UUID.randomUUID().toString())
    }
}
