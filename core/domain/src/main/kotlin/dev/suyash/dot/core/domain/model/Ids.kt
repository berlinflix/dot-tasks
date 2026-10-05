package dev.suyash.dot.core.domain.model

import java.util.UUID

@JvmInline
value class TaskId(val value: String) {
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
