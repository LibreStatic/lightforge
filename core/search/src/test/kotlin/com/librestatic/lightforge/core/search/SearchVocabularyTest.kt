package com.librestatic.lightforge.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchVocabularyTest {
    @Test
    fun `people and pet aliases cover every supported language`() {
        assertAliases(SearchConcept.Me, "Me", "Yo", "Moi", "Eu", "Io", "Ich")
        assertAliases(
            SearchConcept.People,
            "person", "personas", "visages", "pessoas", "volti", "Gesichter",
        )
        assertAliases(
            SearchConcept.Dog,
            "dog", "dogs", "perro", "perros", "chien", "chiens",
            "cão", "cães", "cane", "cani", "Hund", "Hunde",
        )
        assertAliases(
            SearchConcept.Cat,
            "cat", "cats", "gato", "gatos", "chat", "chats",
            "gatto", "gatti", "Katze", "Katzen",
        )
    }

    @Test
    fun `all discovery concepts accept localized and multiword aliases`() {
        val expectations = mapOf(
            "Fotos" to SearchConcept.Image,
            "Vidéos" to SearchConcept.Video,
            "Côte" to SearchConcept.Beach,
            "Montanhas" to SearchConcept.Mountain,
            "Städte" to SearchConcept.City,
            "Piogge" to SearchConcept.Rain,
            "Dokumente" to SearchConcept.Document,
            "Capturas de tela" to SearchConcept.Screenshot,
            "Appareil photo" to SearchConcept.Camera,
            "Landschaften" to SearchConcept.Landscape,
            "Cuisine" to SearchConcept.Food,
        )

        expectations.forEach { (alias, concept) ->
            assertEquals(alias, concept, SearchVocabulary.resolve(alias))
        }
    }

    @Test
    fun `aliases are case accent and punctuation insensitive`() {
        assertEquals(SearchConcept.Dog, SearchVocabulary.resolve("CÃES"))
        assertEquals(SearchConcept.City, SearchVocabulary.resolve("stadte"))
        assertEquals(SearchConcept.Screenshot, SearchVocabulary.resolve("captures d'ecran"))
    }

    private fun assertAliases(concept: SearchConcept, vararg aliases: String) {
        aliases.forEach { alias -> assertEquals(alias, concept, SearchVocabulary.resolve(alias)) }
    }
}
