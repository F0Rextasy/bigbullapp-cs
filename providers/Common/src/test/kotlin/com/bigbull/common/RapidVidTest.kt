package com.bigbull.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RapidVidTest {
    @Test
    fun shiftUndoesRapidVidSalt() {
        val first = "bKT1fJN9Nz<|NmWv\\4SxcX4pd}m{Q|dxe3kxdF;ugk:KUmPydKr6QHm6WnkcS361ZnWRcFU8b}HxhnN4\\EE8fHyzUYixUkIjdJ35eWGweHkyNqXqfG6}OIkr"
        val expected = "aHR0cHM6Ly9zMjUuY2RuaW1nczkzNzcuc2hvcC9tdi9HSlMwcHp5NFl3UmhaR040WlVOaER6azFwelM1ZDB6eEwyRWhuSjFhcG14bUFtcGhwMnVpcE5zMHhp"
        assertEquals(expected, rapidVidShift(first))
    }
}
