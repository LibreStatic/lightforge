package com.librestatic.lightforge.feature.viewer.textselect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartEntitiesTest {
    @Test
    fun detectsLinksEmailsAndPhones() {
        val entities = SmartEntities.detect("Visit www.penguin.co.uk or mail info@penguin.com, call +44 20 7139 3000.")
        assertTrue(SmartEntity.Link("www.penguin.co.uk") in entities)
        assertTrue(SmartEntity.Email("info@penguin.com") in entities)
        assertTrue(entities.any { it is SmartEntity.Phone && it.value.startsWith("+44") })
    }

    @Test
    fun emailDomainIsNotReportedAsSeparateLink() {
        assertEquals(listOf(SmartEntity.Email("hola@ejemplo.com")), SmartEntities.detect("hola@ejemplo.com"))
    }

    @Test
    fun detectsStreetAddresses() {
        assertTrue(SmartEntities.detect("1600 Amphitheatre Parkway Drive").any { it is SmartEntity.Address })
        assertTrue(SmartEntities.detect("Avenida Corrientes 1234").any { it is SmartEntity.Address })
    }

    @Test
    fun plainProseHasNoEntities() {
        assertEquals(emptyList<SmartEntity>(), SmartEntities.detect("The Odyssey is a poem of extraordinary pleasures"))
        assertEquals(emptyList<SmartEntity>(), SmartEntities.detect("Published in 1946"))
    }

    @Test
    fun phoneDoesNotSwallowDigitsFromTheNextLine() {
        val phone = SmartEntities.detect("Call +1 415 555 0132\n221 Baker Street, London")
            .filterIsInstance<SmartEntity.Phone>().single()
        assertEquals("+1 415 555 0132", phone.value)
    }

    @Test
    fun addressKeepsTheCityAndAllFourActionsFit() {
        val entities = SmartEntities.detect(
            "Visit https://example.com/menu\nhello@example.com\n+1 415 555 0132\n221 Baker Street, London",
        )
        assertEquals(
            SmartEntity.Address("221 Baker Street, London"),
            entities.filterIsInstance<SmartEntity.Address>().single(),
        )
        assertEquals(4, entities.size)
    }
}
