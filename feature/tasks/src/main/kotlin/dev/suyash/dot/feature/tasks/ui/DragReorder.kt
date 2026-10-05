package dev.suyash.dot.feature.tasks.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Long-press-and-drag reordering for a run of keyed items in a LazyColumn. While dragging, the
 * screen shows [order] (a local copy); the database is only written once, on drop.
 */
@Stable
internal class DragReorderState(private val listState: LazyListState) {
    var draggingKey: Any? by mutableStateOf(null)
        private set
    var offset: Float by mutableFloatStateOf(0f)
        private set

    /** Item keys in on-screen order while dragging, else null. */
    var order: List<Any>? by mutableStateOf(null)
        private set

    fun start(key: Any, keys: List<Any>) {
        order = keys
        draggingKey = key
        offset = 0f
    }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        val keys = order ?: return
        offset += dy
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == key } ?: return
        val center = current.offset + offset + current.size / 2f
        val target = visible.firstOrNull { it.key != key && it.key in keys && center.toInt() in it.offset..(it.offset + it.size) } ?: return
        val from = keys.indexOf(key)
        val to = keys.indexOf(target.key)
        if (from < 0 || to < 0) return
        order = keys.toMutableList().apply { add(to, removeAt(from)) }
        offset += (current.offset - target.offset).toFloat() // stay under the finger after the swap
    }

    /** Ends the drag; returns the dragged key and its new index, or null if nothing moved. */
    fun end(): Pair<Any, Int>? {
        val key = draggingKey
        val keys = order
        draggingKey = null
        order = null
        offset = 0f
        return if (key != null && keys != null) key to keys.indexOf(key) else null
    }

    /** Pixels per frame to scroll while the dragged item is near the top or bottom edge. */
    fun edgeScrollSpeed(): Float {
        val key = draggingKey ?: return 0f
        val info = listState.layoutInfo
        val item: LazyListItemInfo = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        val top = item.offset + offset
        val bottom = top + item.size
        val edge = EDGE_PX
        return when {
            top < info.viewportStartOffset + edge -> -SPEED
            bottom > info.viewportEndOffset - edge -> SPEED
            else -> 0f
        }
    }

    fun onScrolled(dy: Float) {
        if (draggingKey == null) return
        offset += dy
        drag(0f)
    }

    private companion object {
        const val EDGE_PX = 120
        const val SPEED = 18f
    }
}

@Composable
internal fun rememberDragReorderState(listState: LazyListState): DragReorderState {
    val state = remember(listState) { DragReorderState(listState) }
    LaunchedEffect(state.draggingKey) {
        while (state.draggingKey != null) {
            val speed = state.edgeScrollSpeed()
            if (speed != 0f) state.onScrolled(listState.scrollBy(speed))
            withFrameNanos { }
        }
    }
    return state
}

/** The gesture and the lifted look for one draggable item. */
internal fun Modifier.dragReorderItem(
    state: DragReorderState,
    key: Any,
    keys: () -> List<Any>,
    onDrop: (key: Any, toIndex: Int) -> Unit,
): Modifier = this
    .zIndex(if (state.draggingKey == key) 1f else 0f)
    .graphicsLayer {
        val dragging = state.draggingKey == key
        translationY = if (dragging) state.offset else 0f
        shadowElevation = if (dragging) 12f else 0f
        scaleX = if (dragging) 1.02f else 1f
        scaleY = if (dragging) 1.02f else 1f
    }
    .pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key, keys()) },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
            onDragEnd = { state.end()?.let { (k, index) -> onDrop(k, index) } },
            onDragCancel = { state.end() },
        )
    }
