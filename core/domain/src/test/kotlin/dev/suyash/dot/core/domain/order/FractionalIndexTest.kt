package dev.suyash.dot.core.domain.order

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class FractionalIndexTest {

    @Test
    fun `first key sits in the middle`() {
        assertThat(FractionalIndex.first()).isEqualTo("V")
    }

    @Test
    fun `appending produces increasing keys`() {
        val keys = FractionalIndex.sequence(200)
        assertThat(keys).isInStrictOrder()
    }

    @Test
    fun `prepending produces decreasing keys`() {
        var first = FractionalIndex.first()
        repeat(200) {
            val before = FractionalIndex.between(null, first)
            assertThat(before < first).isTrue()
            first = before
        }
    }

    @Test
    fun `repeated insertion between neighbours always fits`() {
        val random = Random(42)
        val keys = FractionalIndex.sequence(5).toMutableList()
        repeat(2_000) {
            val i = random.nextInt(keys.size + 1)
            val before = keys.getOrNull(i - 1)
            val after = keys.getOrNull(i)
            val key = FractionalIndex.between(before, after)
            if (before != null) assertThat(key > before).isTrue()
            if (after != null) assertThat(key < after).isTrue()
            assertThat(key.last()).isNotEqualTo('0')
            keys.add(i, key)
        }
        assertThat(keys).isInStrictOrder()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects inverted bounds`() {
        FractionalIndex.between("b", "a")
    }
}
