package com.librestatic.lightforge.core.search

/** Stable search concepts used by localized suggestions and recognition routing. */
enum class SearchConcept(internal val canonicalTerm: String? = null) {
    Me("me"),
    People("person"),
    Image,
    Video,
    Dog("dog"),
    Cat("cat"),
    Beach("beach"),
    Mountain("mountain"),
    City("city"),
    Rain("rain"),
    Document("document"),
    Screenshot("screenshot"),
    Camera("camera"),
    Landscape("landscape"),
    Food("food"),
}

/**
 * Maps user-facing search vocabulary to stable English concepts.
 *
 * Stored ML/AppSearch labels intentionally remain locale-independent. Aliases cover the
 * application's supported locales (English, Spanish, French, Portuguese, Italian, German)
 * and are normalized before lookup, so accents and case do not affect matching.
 */
object SearchVocabulary {
    private val aliases: Map<String, SearchConcept> = buildMap {
        register(SearchConcept.Me, "me", "yo", "moi", "eu", "io", "ich")
        register(
            SearchConcept.People,
            "person", "people", "face", "faces",
            "persona", "personas", "cara", "caras", "rostro", "rostros",
            "personne", "personnes", "visage", "visages",
            "pessoa", "pessoas", "rosto", "rostos",
            "persone", "volto", "volti",
            "Person", "Personen", "Gesicht", "Gesichter",
        )
        register(SearchConcept.Image, "image", "images", "photo", "photos", "foto", "fotos", "imagen", "imágenes")
        register(SearchConcept.Video, "video", "videos", "vídeo", "vídeos")
        register(
            SearchConcept.Dog,
            "dog", "dogs", "puppy", "puppies",
            "perro", "perros", "chien", "chiens", "cão", "cães",
            "cane", "cani", "Hund", "Hunde",
        )
        register(
            SearchConcept.Cat,
            "cat", "cats", "kitten", "kittens",
            "gato", "gatos", "chat", "chats", "gatto", "gatti", "Katze", "Katzen",
        )
        register(SearchConcept.Beach, "beach", "beaches", "coast", "coasts", "playa", "playas", "costa", "costas", "côte", "côtes", "Küste", "Küsten")
        register(SearchConcept.Mountain, "mountain", "mountains", "montaña", "montañas", "montagne", "montagnes", "montanha", "montanhas", "montagna", "montagne", "Berg", "Berge")
        register(SearchConcept.City, "city", "cities", "ciudad", "ciudades", "ville", "villes", "cidade", "cidades", "città", "Stadt", "Städte")
        register(SearchConcept.Rain, "rain", "rains", "lluvia", "lluvias", "pluie", "pluies", "chuva", "chuvas", "pioggia", "piogge", "Regen")
        register(
            SearchConcept.Document,
            "document", "documents", "documento", "documentos", "documenti", "Dokument", "Dokumente",
        )
        register(
            SearchConcept.Screenshot,
            "screenshot", "screenshots", "screen shot", "screen shots",
            "captura", "capturas", "captura de pantalla", "capturas de pantalla", "pantallazo", "pantallazos",
            "capture d’écran", "captures d’écran", "captura de tela", "capturas de tela",
            "schermata", "schermate", "Bildschirmfoto", "Bildschirmfotos",
        )
        register(SearchConcept.Camera, "camera", "cámara", "câmera", "appareil photo", "fotocamera", "Kamera")
        register(
            SearchConcept.Landscape,
            "landscape", "landscapes", "paisaje", "paisajes", "paysage", "paysages",
            "paisagem", "paisagens", "paesaggio", "paesaggi", "Landschaft", "Landschaften",
        )
        register(SearchConcept.Food, "food", "foods", "comida", "comidas", "cuisine", "cibo", "Essen")
    }

    fun resolve(raw: String): SearchConcept? = aliases[SearchTextNormalizer.normalize(raw)]

    private fun MutableMap<String, SearchConcept>.register(concept: SearchConcept, vararg values: String) {
        values.forEach { value ->
            val normalized = SearchTextNormalizer.normalize(value)
            val previous = put(normalized, concept)
            check(previous == null || previous == concept) {
                "Search alias '$normalized' maps to both $previous and $concept"
            }
        }
    }
}
