package com.librestatic.lightforge.feature.places

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

data class OfflineMapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

/** Styles are generated locally; packages never supply executable style URLs or external assets. */
object OfflineMapStyle {
    fun json(
        pack: OfflineMapPackage,
        file: File,
        dark: Boolean,
        pinColor: Int,
        pinTextColor: Int,
    ): String {
        val source =
            if (pack.format.name.startsWith("PM")) "pmtiles://file://${file.absolutePath}"
            else "mbtiles://${file.absolutePath}"
        val vector = pack.format.name.endsWith("Vector")
        val background = if (dark) "#171c20" else "#f5f3ee"
        val text = if (dark) "#f1f0e9" else "#24282c"
        val sources =
            JSONObject()
                .put(
                    "base",
                    JSONObject().put("type", if (vector) "vector" else "raster").put("url", source),
                )
                .put(
                    "photos",
                    JSONObject()
                        .put("type", "geojson")
                        .put(
                            "data",
                            JSONObject()
                                .put("type", "FeatureCollection")
                                .put("features", JSONArray()),
                        ),
                )
        val layers =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "background")
                        .put("type", "background")
                        .put("paint", JSONObject().put("background-color", background))
                )
        fun layer(id: String, type: String, sourceLayer: String, paint: JSONObject) {
            layers.put(
                JSONObject()
                    .put("id", id)
                    .put("type", type)
                    .put("source", "base")
                    .put("source-layer", sourceLayer)
                    .put("paint", paint)
            )
        }
        if (vector) {
            layer(
                "landuse",
                "fill",
                "landuse",
                JSONObject()
                    .put("fill-color", if (dark) "#253b2b" else "#d5e1c4")
                    .put("fill-opacity", 0.7),
            )
            layer(
                "water",
                "fill",
                "water",
                JSONObject().put("fill-color", if (dark) "#203f55" else "#acd0e3"),
            )
            layer(
                "buildings",
                "fill",
                if (pack.schema == "protomaps-v4") "buildings" else "building",
                JSONObject().put("fill-color", if (dark) "#4c4b49" else "#d1c8bf"),
            )
            layer(
                "roads",
                "line",
                if (pack.schema == "protomaps-v4") "roads" else "transportation",
                JSONObject()
                    .put("line-color", if (dark) "#b7b2a4" else "#857d6f")
                    .put("line-width", 1.5),
            )
            layers.put(
                JSONObject()
                    .put("id", "place-labels")
                    .put("type", "symbol")
                    .put("source", "base")
                    .put("source-layer", if (pack.schema == "protomaps-v4") "places" else "place")
                    .put(
                        "layout",
                        JSONObject()
                            .put(
                                "text-field",
                                JSONArray("[\"coalesce\",[\"get\",\"name:en\"],[\"get\",\"name\"]]"),
                            )
                            .put("text-font", JSONArray().put("Noto Sans Regular"))
                            .put("text-size", 14),
                    )
                    .put(
                        "paint",
                        JSONObject()
                            .put("text-color", text)
                            .put("text-halo-color", background)
                            .put("text-halo-width", 2),
                    )
            )
        } else
            layers.put(JSONObject().put("id", "raster").put("type", "raster").put("source", "base"))
        layers.put(
            JSONObject()
                .put("id", "photo-pins")
                .put("type", "circle")
                .put("source", "photos")
                .put(
                    "paint",
                    JSONObject()
                        .put("circle-color", "#%06x".format(pinColor and 0xffffff))
                        .put("circle-radius", 18)
                        .put("circle-stroke-color", background)
                        .put("circle-stroke-width", 3),
                )
        )
        layers.put(
            JSONObject()
                .put("id", "photo-count")
                .put("type", "symbol")
                .put("source", "photos")
                .put(
                    "layout",
                    JSONObject()
                        .put("text-field", JSONArray("[\"to-string\",[\"get\",\"count\"]]"))
                        .put("text-font", JSONArray().put("Noto Sans Regular"))
                        .put("text-size", 13)
                        .put("text-allow-overlap", true),
                )
                .put(
                    "paint",
                    JSONObject().put("text-color", "#%06x".format(pinTextColor and 0xffffff)),
                )
        )
        return JSONObject()
            .put("version", 8)
            .put("glyphs", "asset://places/fonts/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
            .also(::requireLocal)
    }

    fun requireLocal(style: String) {
        val parsed = JSONObject(style)
        fun check(value: Any?) {
            when (value) {
                is JSONObject ->
                    value.keys().forEach { key ->
                        val v = value.opt(key)
                        if (key in setOf("url", "glyphs", "sprite", "tiles")) {
                            fun local(s: String) {
                                require(
                                    s.startsWith("asset://places/") ||
                                        s.startsWith("pmtiles://file:///") ||
                                        s.startsWith("mbtiles:///")
                                ) {
                                    "REMOTE_STYLE"
                                }
                            }
                            if (v is String) local(v)
                            else if (v is JSONArray)
                                (0 until v.length()).forEach { local(v.getString(it)) }
                        }
                        check(v)
                    }
                is JSONArray -> (0 until value.length()).forEach { check(value.opt(it)) }
            }
        }
        check(parsed)
    }

    fun photos(groups: List<PlacePhotoGroup>): String =
        JSONObject()
            .put("type", "FeatureCollection")
            .put(
                "features",
                JSONArray(
                    groups.map { group ->
                        JSONObject()
                            .put("type", "Feature")
                            .put(
                                "geometry",
                                JSONObject()
                                    .put("type", "Point")
                                    .put(
                                        "coordinates",
                                        JSONArray(listOf(group.longitude, group.latitude)),
                                    ),
                            )
                            .put(
                                "properties",
                                JSONObject().put("group", group.id).put("count", group.photos.size),
                            )
                    }
                ),
            )
            .toString()
}

@Composable
fun OfflinePlacesMap(
    pack: OfflineMapPackage,
    file: File,
    groups: List<PlacePhotoGroup>,
    camera: OfflineMapCamera,
    dark: Boolean,
    onCamera: (OfflineMapCamera, PlaceBounds) -> Unit,
    onGroup: (String) -> Unit,
    onReady: () -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pinColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val pinTextColor = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
    val owner = LocalLifecycleOwner.current
    val cameraCallback by rememberUpdatedState(onCamera)
    val groupCallback by rememberUpdatedState(onGroup)
    val readyCallback by rememberUpdatedState(onReady)
    val failureCallback by rememberUpdatedState(onFailure)
    val mapView =
        remember(pack.id, dark, pinColor, pinTextColor) {
            OfflineMapNetworkGuard.install()
            MapLibre.getInstance(context)
            MapView(context).apply { onCreate(null) }
        }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    DisposableEffect(mapView, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        mapView.addOnDidFailLoadingMapListener { failureCallback() }
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isAttributionEnabled =
                false // Persistent adjacent attribution, rather than an external link dialog.
            m.uiSettings.isLogoEnabled = false
            m.setMinZoomPreference(pack.minZoom.toDouble())
            m.setMaxZoomPreference((pack.maxZoom + 2).coerceAtMost(22).toDouble())
            m.cameraPosition =
                CameraPosition.Builder()
                    .target(LatLng(camera.latitude, camera.longitude))
                    .zoom(camera.zoom)
                    .build()
            m.addOnCameraIdleListener {
                val c = m.cameraPosition
                val b = m.projection.visibleRegion.latLngBounds
                val bounds =
                    runCatching {
                            PlaceBounds(
                                b.longitudeWest.coerceIn(-180.0, 180.0),
                                b.latitudeSouth.coerceIn(-90.0, 90.0),
                                b.longitudeEast.coerceIn(-180.0, 180.0),
                                b.latitudeNorth.coerceIn(-90.0, 90.0),
                            )
                        }
                        .getOrDefault(PlaceBounds.World)
                c.target?.let {
                    cameraCallback(OfflineMapCamera(it.latitude, it.longitude, c.zoom), bounds)
                }
            }
            m.addOnMapClickListener { location ->
                val found =
                    m.queryRenderedFeatures(m.projection.toScreenLocation(location), "photo-pins")
                        .firstOrNull()
                if (found != null) {
                    groupCallback(found.getStringProperty("group"))
                    true
                } else false
            }
            runCatching {
                    m.setStyle(
                        Style.Builder()
                            .fromJson(
                                OfflineMapStyle.json(pack, file, dark, pinColor, pinTextColor)
                            )
                    ) { style ->
                        style
                            .getSourceAs<GeoJsonSource>("photos")
                            ?.setGeoJson(OfflineMapStyle.photos(groups))
                        readyCallback()
                    }
                }
                .onFailure { failureCallback() }
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            map = null
        }
    }
    LaunchedEffect(map, groups) {
        map?.getStyle {
            it.getSourceAs<GeoJsonSource>("photos")?.setGeoJson(OfflineMapStyle.photos(groups))
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}
