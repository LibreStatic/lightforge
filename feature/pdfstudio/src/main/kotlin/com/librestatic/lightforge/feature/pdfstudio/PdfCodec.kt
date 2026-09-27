package com.librestatic.lightforge.feature.pdfstudio

import org.json.JSONArray
import org.json.JSONObject

object PdfCodec {
    /** Bumped from 1 in Phase G1a to add the text layer and the images'/texts' shared `z` paint
     * order (see [PdfLayers]). [decode] still accepts a version-1 payload unchanged (no `z`, no
     * `texts`) — every new field below has a default that reproduces the old, images-only
     * behavior. Any version outside 1..2 is rejected outright, not silently upgraded. */
    private const val CURRENT_VERSION = 2

    fun encode(p: PdfProject): String =
        JSONObject()
            .apply {
                put("format", "com.librestatic.lightforge.pdf-project")
                put("version", CURRENT_VERSION)
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
                                                put("z", i.z)
                                            }
                                        }
                                    ),
                                )
                                put(
                                    "texts",
                                    JSONArray(
                                        page.texts.map { t ->
                                            JSONObject()
                                                .put("id", t.id)
                                                .put("text", t.text)
                                                .put("x", t.x)
                                                .put("y", t.y)
                                                .put("w", t.width)
                                                .put("h", t.height)
                                                .put("size", t.sizePt)
                                                .put("font", t.font.name)
                                                .put("weight", t.weight.name)
                                                .put("align", t.align.name)
                                                .put("ink", t.ink.name)
                                                .put("z", t.z)
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
        val version = o.getInt("version")
        require(o.getString("format") == "com.librestatic.lightforge.pdf-project" && version in 1..CURRENT_VERSION)
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
                                        z = i.optInt("z", 0),
                                    )
                                },
                            // Absent entirely in a version-1 payload — decodes to no texts, same
                            // as an images-only project always used to look.
                            texts =
                                page.optJSONArray("texts")?.let { texts ->
                                    List(texts.length()) { x ->
                                        val t = texts.getJSONObject(x)
                                        PdfText(
                                            id = t.getString("id"),
                                            text = t.getString("text"),
                                            x = t.getDouble("x"),
                                            y = t.getDouble("y"),
                                            width = t.getDouble("w"),
                                            height = t.getDouble("h"),
                                            sizePt = t.getDouble("size"),
                                            font = PdfFontFamily.valueOf(t.getString("font")),
                                            weight = PdfFontWeight.valueOf(t.getString("weight")),
                                            align = PdfTextAlign.valueOf(t.getString("align")),
                                            ink = PdfInk.valueOf(t.getString("ink")),
                                            z = t.optInt("z", 0),
                                        )
                                    }
                                } ?: emptyList(),
                        )
                    },
            )
            .validate()
    }
}
