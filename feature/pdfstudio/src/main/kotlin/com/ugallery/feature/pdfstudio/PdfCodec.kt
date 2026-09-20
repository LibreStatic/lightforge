package com.ugallery.feature.pdfstudio

import org.json.JSONArray
import org.json.JSONObject

object PdfCodec {
    fun encode(p: PdfProject): String =
        JSONObject()
            .apply {
                put("format", "com.ugallery.pdf-project")
                put("version", 1)
                put("id", p.id)
                put("name", p.name)
                put("unit", p.unit.name)
                put("dpi", p.dpi)
                put("columns", p.columns)
                put("gap", p.gap)
                put("snap", p.snap)
                put("updated", p.updated)
                put(
                    "assets",
                    JSONArray(
                        p.assets.map { a ->
                            JSONObject()
                                .put("hash", a.hash)
                                .put("mime", a.mime)
                                .put("width", a.width)
                                .put("height", a.height)
                                .put("orientation", a.orientation)
                        }
                    ),
                )
                put(
                    "pages",
                    JSONArray(
                        p.pages.map { page ->
                            JSONObject().apply {
                                put("id", page.id)
                                put("w", page.width)
                                put("h", page.height)
                                put("margin", page.margin)
                                put("source", page.source)
                                put("sourcePage", page.sourcePage)
                                put("rotation", page.rotation)
                                put(
                                    "images",
                                    JSONArray(
                                        page.images.map { i ->
                                            JSONObject().apply {
                                                put("id", i.id)
                                                put("asset", i.asset)
                                                put("x", i.x)
                                                put("y", i.y)
                                                put("w", i.width)
                                                put("h", i.height)
                                                put("fit", i.fit.name)
                                                put("fx", i.focusX)
                                                put("fy", i.focusY)
                                                put("locked", i.locked)
                                                put("rotation", i.rotation)
                                            }
                                        }
                                    ),
                                )
                            }
                        }
                    ),
                )
            }
            .toString()

    fun decode(raw: String): PdfProject {
        require(raw.length <= 2_000_000)
        val o = JSONObject(raw)
        require(o.getString("format") == "com.ugallery.pdf-project" && o.getInt("version") == 1)
        val a = o.getJSONArray("assets")
        val pages = o.getJSONArray("pages")
        return PdfProject(
                id = o.getString("id"),
                name = o.getString("name"),
                unit = PdfUnit.valueOf(o.getString("unit")),
                dpi = o.getInt("dpi"),
                columns = o.optInt("columns", 2),
                gap = o.optDouble("gap", 4.0),
                snap = o.optBoolean("snap", false),
                updated = o.getLong("updated"),
                assets =
                    List(a.length()) { n ->
                        a.getJSONObject(n).let {
                            PdfAsset(
                                it.getString("hash"),
                                it.getString("mime"),
                                it.getInt("width"),
                                it.getInt("height"),
                                it.optInt("orientation", 1),
                            )
                        }
                    },
                pages =
                    List(pages.length()) { n ->
                        val page = pages.getJSONObject(n)
                        val images = page.getJSONArray("images")
                        PdfPage(
                            id = page.getString("id"),
                            width = page.getDouble("w"),
                            height = page.getDouble("h"),
                            margin = page.getDouble("margin"),
                            source = page.optString("source").takeIf(String::isNotEmpty),
                            sourcePage = page.optInt("sourcePage"),
                            rotation = page.optInt("rotation"),
                            images =
                                List(images.length()) { x ->
                                    val i = images.getJSONObject(x)
                                    PdfImage(
                                        id = i.getString("id"),
                                        asset = i.getString("asset"),
                                        x = i.getDouble("x"),
                                        y = i.getDouble("y"),
                                        width = i.getDouble("w"),
                                        height = i.getDouble("h"),
                                        fit = PdfFit.valueOf(i.getString("fit")),
                                        focusX = i.getDouble("fx"),
                                        focusY = i.getDouble("fy"),
                                        locked = i.getBoolean("locked"),
                                        rotation = i.optInt("rotation"),
                                    )
                                },
                        )
                    },
            )
            .validate()
    }
}
