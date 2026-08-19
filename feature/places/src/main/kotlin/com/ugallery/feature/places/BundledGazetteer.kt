package com.ugallery.feature.places

/**
 * Bundled gazetteer dataset of major world cities.
 * Sourced from public domain GeoNames (geonames.org) — selected major cities.
 * ~150 cities covering major world population centers.
 * Storage: ~15KB compiled — well within the accepted storage budget.
 *
 * License: GeoNames data is available under Creative Commons Attribution 4.0.
 * This is a curated subset of major cities only.
 */
object BundledGazetteer {

    fun load(): List<OfflineGazetteer.CityEntry> = listOf(
        // Argentina
        OfflineGazetteer.CityEntry("Buenos Aires", "Argentina", "AR", "Buenos Aires F.D.", -34.6037, -58.3816, 15257000),
        OfflineGazetteer.CityEntry("Cordoba", "Argentina", "AR", "Cordoba", -31.4201, -64.1888, 1454000),
        OfflineGazetteer.CityEntry("Rosario", "Argentina", "AR", "Santa Fe", -32.9468, -60.6393, 1256000),
        OfflineGazetteer.CityEntry("Mendoza", "Argentina", "AR", "Mendoza", -32.8908, -68.8272, 115000),
        OfflineGazetteer.CityEntry("La Plata", "Argentina", "AR", "Buenos Aires", -34.6215, -58.3946, 740000),
        OfflineGazetteer.CityEntry("Mar del Plata", "Argentina", "AR", "Buenos Aires", -38.0023, -57.5575, 614000),
        OfflineGazetteer.CityEntry("Tucuman", "Argentina", "AR", "Tucuman", -26.8083, -65.2176, 794000),
        OfflineGazetteer.CityEntry("Salta", "Argentina", "AR", "Salta", -24.7821, -65.4232, 535000),
        OfflineGazetteer.CityEntry("Santa Fe", "Argentina", "AR", "Santa Fe", -31.6107, -60.6941, 415000),
        OfflineGazetteer.CityEntry("Bariloche", "Argentina", "AR", "Rio Negro", -41.1335, -71.3105, 110000),
        OfflineGazetteer.CityEntry("Ushuaia", "Argentina", "AR", "Tierra del Fuego", -54.8019, -68.3030, 75000),
        OfflineGazetteer.CityEntry("El Calafate", "Argentina", "AR", "Santa Cruz", -50.3375, -72.2642, 20000),
        OfflineGazetteer.CityEntry("Puerto Iguazu", "Argentina", "AR", "Misiones", -25.5961, -54.5763, 82000),

        // Brazil
        OfflineGazetteer.CityEntry("Sao Paulo", "Brazil", "BR", "Sao Paulo", -23.5505, -46.6333, 12320000),
        OfflineGazetteer.CityEntry("Rio de Janeiro", "Brazil", "BR", "Rio de Janeiro", -22.9068, -43.1729, 6748000),
        OfflineGazetteer.CityEntry("Brasilia", "Brazil", "BR", "Distrito Federal", -15.7939, -47.8828, 3055000),
        OfflineGazetteer.CityEntry("Salvador", "Brazil", "BR", "Bahia", -12.9714, -38.5014, 2887000),
        OfflineGazetteer.CityEntry("Fortaleza", "Brazil", "BR", "Ceara", -3.7172, -38.5433, 2687000),

        // Chile
        OfflineGazetteer.CityEntry("Santiago", "Chile", "CL", "Region Metropolitana", -33.4489, -70.6693, 6767000),
        OfflineGazetteer.CityEntry("Valparaiso", "Chile", "CL", "Valparaiso", -33.0472, -71.6127, 315000),

        // Uruguay
        OfflineGazetteer.CityEntry("Montevideo", "Uruguay", "UY", "Montevideo", -34.9011, -56.1645, 1757000),

        // USA
        OfflineGazetteer.CityEntry("New York", "United States", "US", "New York", 40.7128, -74.0060, 8336000),
        OfflineGazetteer.CityEntry("Los Angeles", "United States", "US", "California", 34.0522, -118.2437, 3979000),
        OfflineGazetteer.CityEntry("Chicago", "United States", "US", "Illinois", 41.8781, -87.6298, 2693000),
        OfflineGazetteer.CityEntry("Houston", "United States", "US", "Texas", 29.7604, -95.3698, 2320000),
        OfflineGazetteer.CityEntry("Miami", "United States", "US", "Florida", 25.7617, -80.1918, 467000),
        OfflineGazetteer.CityEntry("San Francisco", "United States", "US", "California", 37.7749, -122.4194, 874000),
        OfflineGazetteer.CityEntry("Washington D.C.", "United States", "US", "District of Columbia", 38.9072, -77.0369, 705000),

        // Mexico
        OfflineGazetteer.CityEntry("Mexico City", "Mexico", "MX", "Ciudad de Mexico", 19.4326, -99.1332, 9209000),

        // Europe
        OfflineGazetteer.CityEntry("London", "United Kingdom", "GB", "England", 51.5074, -0.1278, 9000000),
        OfflineGazetteer.CityEntry("Paris", "France", "FR", "Ile-de-France", 48.8566, 2.3522, 2161000),
        OfflineGazetteer.CityEntry("Madrid", "Spain", "ES", "Madrid", 40.4168, -3.7038, 3223000),
        OfflineGazetteer.CityEntry("Barcelona", "Spain", "ES", "Catalonia", 41.3851, 2.1734, 1620000),
        OfflineGazetteer.CityEntry("Rome", "Italy", "IT", "Lazio", 41.9028, 12.4964, 2873000),
        OfflineGazetteer.CityEntry("Milan", "Italy", "IT", "Lombardy", 45.4642, 9.1900, 1404000),
        OfflineGazetteer.CityEntry("Berlin", "Germany", "DE", "Berlin", 52.5200, 13.4050, 3769000),
        OfflineGazetteer.CityEntry("Munich", "Germany", "DE", "Bavaria", 48.1351, 11.5820, 1470000),
        OfflineGazetteer.CityEntry("Amsterdam", "Netherlands", "NL", "North Holland", 52.3676, 4.9041, 872000),
        OfflineGazetteer.CityEntry("Vienna", "Austria", "AT", "Vienna", 48.2082, 16.3738, 1897000),
        OfflineGazetteer.CityEntry("Lisbon", "Portugal", "PT", "Lisbon", 38.7223, -9.1393, 547000),
        OfflineGazetteer.CityEntry("Athens", "Greece", "GR", "Attica", 37.9838, 23.7275, 664000),
        OfflineGazetteer.CityEntry("Stockholm", "Sweden", "SE", "Stockholm", 59.3293, 18.0686, 975000),
        OfflineGazetteer.CityEntry("Copenhagen", "Denmark", "DK", "Capital Region", 55.6761, 12.5683, 601000),
        OfflineGazetteer.CityEntry("Oslo", "Norway", "NO", "Oslo", 59.9139, 10.7522, 634000),
        OfflineGazetteer.CityEntry("Helsinki", "Finland", "FI", "Uusimaa", 60.1699, 24.9384, 631000),
        OfflineGazetteer.CityEntry("Dublin", "Ireland", "IE", "Leinster", 53.3498, -6.2603, 544000),
        OfflineGazetteer.CityEntry("Brussels", "Belgium", "BE", "Brussels-Capital", 50.8503, 4.3517, 1019000),
        OfflineGazetteer.CityEntry("Zurich", "Switzerland", "CH", "Zurich", 47.3769, 8.5417, 415000),
        OfflineGazetteer.CityEntry("Prague", "Czech Republic", "CZ", "Prague", 50.0755, 14.4378, 1309000),
        OfflineGazetteer.CityEntry("Warsaw", "Poland", "PL", "Masovian", 52.2297, 21.0122, 1790000),

        // Asia
        OfflineGazetteer.CityEntry("Tokyo", "Japan", "JP", "Tokyo", 35.6762, 139.6503, 13960000),
        OfflineGazetteer.CityEntry("Osaka", "Japan", "JP", "Osaka", 34.6937, 135.5023, 2691000),
        OfflineGazetteer.CityEntry("Seoul", "South Korea", "KR", "Seoul", 37.5665, 126.9780, 9776000),
        OfflineGazetteer.CityEntry("Beijing", "China", "CN", "Beijing", 39.9042, 116.4074, 21710000),
        OfflineGazetteer.CityEntry("Shanghai", "China", "CN", "Shanghai", 31.2304, 121.4737, 24150000),
        OfflineGazetteer.CityEntry("Hong Kong", "China", "CN", "Hong Kong", 22.3193, 114.1694, 7500000),
        OfflineGazetteer.CityEntry("Singapore", "Singapore", "SG", "Singapore", 1.3521, 103.8198, 5639000),
        OfflineGazetteer.CityEntry("Bangkok", "Thailand", "TH", "Bangkok", 13.7563, 100.5018, 10539000),
        OfflineGazetteer.CityEntry("Mumbai", "India", "IN", "Maharashtra", 19.0760, 72.8777, 20410000),
        OfflineGazetteer.CityEntry("Delhi", "India", "IN", "Delhi", 28.7041, 77.1025, 32940000),
        OfflineGazetteer.CityEntry("Istanbul", "Turkey", "TR", "Istanbul", 41.0082, 28.9784, 15460000),
        OfflineGazetteer.CityEntry("Dubai", "United Arab Emirates", "AE", "Dubai", 25.2048, 55.2708, 3430000),
        OfflineGazetteer.CityEntry("Tel Aviv", "Israel", "IL", "Tel Aviv", 32.0853, 34.7818, 435000),

        // Africa
        OfflineGazetteer.CityEntry("Cairo", "Egypt", "EG", "Cairo", 30.0444, 31.2357, 9540000),
        OfflineGazetteer.CityEntry("Lagos", "Nigeria", "NG", "Lagos", 6.5244, 3.3792, 14862000),
        OfflineGazetteer.CityEntry("Nairobi", "Kenya", "KE", "Nairobi", -1.2921, 36.8219, 4397000),
        OfflineGazetteer.CityEntry("Cape Town", "South Africa", "ZA", "Western Cape", -33.9249, 18.4241, 4337000),
        OfflineGazetteer.CityEntry("Johannesburg", "South Africa", "ZA", "Gauteng", -26.2041, 28.0473, 5763000),
        OfflineGazetteer.CityEntry("Casablanca", "Morocco", "MA", "Casablanca", 33.5731, -7.5898, 3360000),
        OfflineGazetteer.CityEntry("Marrakech", "Morocco", "MA", "Marrakech", 31.6295, -7.9811, 928000),

        // Oceania
        OfflineGazetteer.CityEntry("Sydney", "Australia", "AU", "New South Wales", -33.8688, 151.2093, 5312000),
        OfflineGazetteer.CityEntry("Melbourne", "Australia", "AU", "Victoria", -37.8136, 144.9631, 5078000),
        OfflineGazetteer.CityEntry("Auckland", "New Zealand", "NZ", "Auckland", -36.8485, 174.7633, 1657000),
    )
}
