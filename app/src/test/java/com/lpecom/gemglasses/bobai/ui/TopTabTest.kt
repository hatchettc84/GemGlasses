package com.lpecom.gemglasses.bobai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TopTabTest {
    @Test fun officeSitsAfterJobs() {
        assertEquals(
            listOf("Home", "Jobs", "Office", "Notes", "Library", "Settings"),
            TopTab.entries.map { it.label },
        )
        assertEquals(Routes.OFFICE, TopTab.OFFICE.route)
    }

    @Test fun routesAreUnique() {
        val routes = TopTab.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
