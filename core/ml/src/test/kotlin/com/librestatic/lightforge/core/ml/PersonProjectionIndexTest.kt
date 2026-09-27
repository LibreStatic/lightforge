package com.librestatic.lightforge.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonProjectionIndexTest {
    @Test fun projectionsAreDeterministicBoundedAndUseTheWholeVector() {
        val first = ByteArray(128) { index -> (index - 64).toByte() }
        val same = PersonProjectionIndex.projections(first)
        assertEquals(same, PersonProjectionIndex.projections(first.copyOf()))
        assertEquals(4, same.size)
        same.forEach { projection ->
            listOf(projection.q0, projection.q1, projection.q2, projection.q3, projection.q4, projection.q5)
                .forEach { assertTrue(it in 0..15) }
        }
        val changed = ByteArray(128) { index -> (-first[index]).toByte() }
        assertNotEquals(same, PersonProjectionIndex.projections(changed))
    }
}
