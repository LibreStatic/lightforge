package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfModelsTest {
    private val hash = "a".repeat(64)

    private fun image() = PdfImage(asset = hash, width = 80.0, height = 40.0)

    private fun project() =
        PdfProject(
            name = "Test",
            assets = listOf(PdfAsset(hash, "image/jpeg", 800, 400)),
            pages = listOf(PdfPage(images = listOf(image()))),
        )

    @Test
    fun jsonRoundTrip() {
        val p = project()
        assertEquals(p, PdfCodec.decode(PdfCodec.encode(p)))
    }

    @Test
    fun unitsArePhysical() {
        assertEquals(25.4, PdfUnit.Inch.factor(300), 0.0)
        assertEquals(25.4, PdfUnit.Pixel.factor(300) * 300, .000001)
    }

    @Test
    fun resizeRetainsRatioAndReachesThePageEdgeNotJustMargins() {
        // R4 review fix: manual resize treats margins as a guide, not a wall, so an oversized
        // request is only capped by the physical page (constrainToPage), not the margin box.
        val page = PdfPage()
        val i = PdfGeometry.resize(image(), page, 800.0, 40.0, true)
        assertEquals(2.0, i.width / i.height, .000001)
        assertTrue(i.x + i.width <= page.width + .000001)
        assertTrue(i.y + i.height <= page.height + .000001)
        assertTrue(i.x >= 0 && i.y >= 0)
    }

    @Test
    fun unlockedBoxChangesWithoutChangingSourceAsset() {
        val i = PdfGeometry.resize(image().copy(locked = false), PdfPage(), 100.0, 40.0, true)
        assertEquals(100.0, i.width, 0.0)
        assertEquals(40.0, i.height, 0.0)
        assertEquals(hash, i.asset)
    }

    @Test
    fun gridFitsEveryImageAndPreservesFit() {
        val p = PdfGeometry.grid(PdfPage(images = List(24) { image() }), 6, 4.0)
        assertEquals(24, p.images.size)
        p.images.forEach {
            assertTrue(it.x >= 10 && it.y >= 10)
            assertTrue(it.x + it.width <= 200.000001)
            assertTrue(it.y + it.height <= 287.000001)
            assertEquals(PdfFit.Contain, it.fit)
        }
    }

    @Test
    fun gridRowsHintDistinguishesTemplatesSharingAColumnCount() {
        // 4 (2x2) and 6 (2x3) both use 2 columns in portrait; without a rows hint they'd both
        // collapse to ceil(imageCount / columns) rows and produce identical frame heights
        // (Phase F item 0 carry-over bug).
        val page = PdfPage(images = List(4) { image() })
        val asFour = PdfGeometry.grid(page, columns = 2, gap = 4.0, rowsHint = 2)
        val asSix = PdfGeometry.grid(page, columns = 2, gap = 4.0, rowsHint = 3)
        assertEquals(4, asFour.images.size)
        assertEquals(4, asSix.images.size)
        assertTrue(asFour.images[0].height > asSix.images[0].height)
        asFour.images.forEach { assertTrue(it.y + it.height <= page.height + .000001) }
        asSix.images.forEach { assertTrue(it.y + it.height <= page.height + .000001) }
    }

    @Test
    fun gridRowsHintNeverDropsImagesBelowItsCapacity() {
        // A rows hint smaller than what the image count needs (e.g. applying the 4-photo
        // template to a page that already has 7 images) must still fit every image, never clip.
        val page = PdfPage(images = List(7) { image() })
        val p = PdfGeometry.grid(page, columns = 2, gap = 4.0, rowsHint = 2)
        assertEquals(7, p.images.size)
        p.images.forEach {
            assertTrue(it.y + it.height <= page.height + .000001)
            assertTrue(it.x + it.width <= page.width + .000001)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidGeometry() {
        project().copy(pages = listOf(PdfPage(width = Double.NaN))).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingSource() {
        project().copy(assets = emptyList()).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalHash() {
        project().copy(assets = listOf(PdfAsset("../../private", "image/jpeg"))).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownOrientation() {
        project().copy(assets = listOf(PdfAsset(hash, "image/jpeg", 1, 1, 9))).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyProjects() {
        project().copy(pages = emptyList()).validate()
    }

    @Test
    fun exifRoundTrip() {
        val p = project().copy(assets = listOf(PdfAsset(hash, "image/jpeg", 400, 800, 6)))
        assertEquals(p, PdfCodec.decode(PdfCodec.encode(p)))
    }

    @Test
    fun resizeFromBottomRightCornerMatchesLegacySingleHandle() {
        val page = PdfPage()
        val viaCorner =
            PdfGeometry.resizeFromCorner(image(), page, PdfGeometry.Corner.BottomRight, 20.0, 10.0)
        val viaResize = PdfGeometry.resize(image(), page, 100.0, 50.0, true)
        assertEquals(viaResize.width, viaCorner.width, .0001)
        assertEquals(viaResize.height, viaCorner.height, .0001)
        assertEquals(viaResize.x, viaCorner.x, .0001)
        assertEquals(viaResize.y, viaCorner.y, .0001)
    }

    @Test
    fun resizeFromTopLeftCornerKeepsOppositeCornerFixed() {
        val page = PdfPage()
        val i = image().copy(x = 50.0, y = 50.0, width = 80.0, height = 40.0)
        val right = i.x + i.width
        val bottom = i.y + i.height
        val resized = PdfGeometry.resizeFromCorner(i, page, PdfGeometry.Corner.TopLeft, -10.0, -10.0)
        assertEquals(right, resized.x + resized.width, .0001)
        assertEquals(bottom, resized.y + resized.height, .0001)
        assertTrue(resized.width > i.width)
    }

    @Test
    fun resizeFromCornerNeverProducesNonFiniteOrNegativeSize() {
        val page = PdfPage()
        val i = image()
        for (corner in PdfGeometry.Corner.entries) {
            val resized = PdfGeometry.resizeFromCorner(i, page, corner, -1000.0, -1000.0)
            assertTrue(resized.width.isFinite() && resized.width > 0)
            assertTrue(resized.height.isFinite() && resized.height > 0)
        }
    }

    @Test
    fun resizeFromCornerKeepsAnchorFixedEvenWhenOverflowForcesAShrink() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val i = image().copy(x = 150.0, y = 150.0, width = 40.0, height = 30.0)
        val right = i.x + i.width
        val bottom = i.y + i.height
        for (corner in PdfGeometry.Corner.entries) {
            // A huge drag toward the anchor requests a size that cannot fit the page from that
            // anchor; the anchor corner itself must still land exactly where it started.
            val resized = PdfGeometry.resizeFromCorner(i, page, corner, -1000.0, -1000.0)
            when (corner) {
                PdfGeometry.Corner.TopLeft -> {
                    assertEquals(right, resized.x + resized.width, .0001)
                    assertEquals(bottom, resized.y + resized.height, .0001)
                }
                PdfGeometry.Corner.TopRight -> {
                    assertEquals(i.x, resized.x, .0001)
                    assertEquals(bottom, resized.y + resized.height, .0001)
                }
                PdfGeometry.Corner.BottomLeft -> {
                    assertEquals(right, resized.x + resized.width, .0001)
                    assertEquals(i.y, resized.y, .0001)
                }
                PdfGeometry.Corner.BottomRight -> {
                    assertEquals(i.x, resized.x, .0001)
                    assertEquals(i.y, resized.y, .0001)
                }
            }
        }
    }

    @Test
    fun alignRightAndBottomOnAnOverWideImageStaysWithinMargins() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        // Wider/taller than the margin box: page.width - 2*margin = 190 < 250.
        val wide = image().copy(width = 250.0, height = 40.0)
        val right = PdfGeometry.align(wide, page, PdfGeometry.Align.Right)
        assertTrue("Right align must not produce a negative x", right.x >= 0)
        assertTrue(right.x >= page.margin - .0001)
        val bottom = PdfGeometry.align(wide.copy(width = 40.0, height = 250.0), page, PdfGeometry.Align.Bottom)
        assertTrue("Bottom align must not produce a negative y", bottom.y >= 0)
        project().copy(pages = listOf(page.copy(images = listOf(right)))).validate()
        project().copy(pages = listOf(page.copy(images = listOf(bottom)))).validate()
    }

    @Test
    fun alignCenterAndLeftTopRoundTrip() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val i = image().copy(width = 40.0, height = 20.0)
        val centered = PdfGeometry.align(i, page, PdfGeometry.Align.Center)
        assertEquals((page.width - i.width) / 2, centered.x, .0001)
        val left = PdfGeometry.align(i, page, PdfGeometry.Align.Left)
        assertEquals(page.margin, left.x, .0001)
        val top = PdfGeometry.align(i, page, PdfGeometry.Align.Top)
        assertEquals(page.margin, top.y, .0001)
    }

    @Test
    fun alignRelativeToMarginsDefaultMatchesThreeArgOverload() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val i = image().copy(width = 40.0, height = 20.0)
        PdfGeometry.Align.entries.forEach { align ->
            val old = PdfGeometry.align(i, page, align)
            val explicit = PdfGeometry.align(i, page, align, relativeToMargins = true)
            assertEquals(align.name, old.x, explicit.x, .0001)
            assertEquals(align.name, old.y, explicit.y, .0001)
        }
    }

    @Test
    fun constrainToPageAllowsPlacementInsideMarginsButNotPastTheEdge() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        // 3mm from the top-left page edge: inside the physical page but inside the margin band
        // (margin = 10mm) too — constrain() would have pushed this out to x=y=10.
        val i = image().copy(x = 3.0, y = 3.0, width = 40.0, height = 20.0)
        val result = PdfGeometry.constrainToPage(i, page)
        assertEquals(3.0, result.x, .0001)
        assertEquals(3.0, result.y, .0001)
        // Still can't escape the physical page.
        val overflow = image().copy(x = -50.0, y = -50.0, width = 40.0, height = 20.0)
        val clamped = PdfGeometry.constrainToPage(overflow, page)
        assertEquals(0.0, clamped.x, .0001)
        assertEquals(0.0, clamped.y, .0001)
    }

    @Test
    fun resizeCanGrowPastTheMarginBoxUpToThePageEdge() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        // Anchored at the page's top-left physical corner (inside the margin band); growing width
        // to 205mm only overflows the *margin* box (210 - 2*10 = 190), not the physical page.
        val i = image().copy(x = 0.0, y = 0.0, width = 80.0, height = 40.0, locked = false)
        val resized = PdfGeometry.resize(i, page, 205.0, 40.0, true)
        assertEquals(205.0, resized.width, .0001)
    }

    @Test
    fun resizeFromCornerCanGrowPastTheMarginBoxUpToThePageEdge() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val i = image().copy(x = 0.0, y = 0.0, width = 80.0, height = 40.0, locked = false)
        val resized = PdfGeometry.resizeFromCorner(i, page, PdfGeometry.Corner.BottomRight, 125.0, 0.0)
        // 80 + 125 = 205mm, which only fits if the room was computed to the page edge (210mm),
        // not the margin box (190mm available from x=0).
        assertEquals(205.0, resized.width, .0001)
    }

    @Test
    fun moveImageToReachesThePageEdgeNotJustTheMarginBox() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val i = image().copy(width = 40.0, height = 20.0)
        val atEdge = PdfGeometry.constrainToPage(i.copy(x = 0.0, y = 0.0), page)
        assertEquals(0.0, atEdge.x, .0001)
        assertEquals(0.0, atEdge.y, .0001)
    }

    @Test
    fun alignRelativeToPageIgnoresMargins() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val i = image().copy(width = 40.0, height = 20.0)
        val left = PdfGeometry.align(i, page, PdfGeometry.Align.Left, relativeToMargins = false)
        assertEquals(0.0, left.x, .0001)
        val right = PdfGeometry.align(i, page, PdfGeometry.Align.Right, relativeToMargins = false)
        assertEquals(page.width - i.width, right.x, .0001)
        val top = PdfGeometry.align(i, page, PdfGeometry.Align.Top, relativeToMargins = false)
        assertEquals(0.0, top.y, .0001)
        val bottom = PdfGeometry.align(i, page, PdfGeometry.Align.Bottom, relativeToMargins = false)
        assertEquals(page.height - i.height, bottom.y, .0001)
        // Symmetric margins: page-relative and margin-relative Center/Middle agree.
        val center = PdfGeometry.align(i, page, PdfGeometry.Align.Center, relativeToMargins = false)
        assertEquals((page.width - i.width) / 2, center.x, .0001)
    }

    @Test
    fun replaceAssetKeepsFrameGeometry() {
        val oldAsset = PdfAsset(hash, "image/jpeg", 800, 400)
        val newHash = "b".repeat(64)
        val newAsset = PdfAsset(newHash, "image/jpeg", 800, 400)
        val i = image().copy(x = 12.0, y = 34.0, width = 80.0, height = 40.0, rotation = 90)
        val replaced = PdfGeometry.replaceAsset(i, oldAsset, newAsset)
        assertEquals(newHash, replaced.asset)
        assertEquals(i.x, replaced.x, .0001)
        assertEquals(i.y, replaced.y, .0001)
        assertEquals(i.width, replaced.width, .0001)
        assertEquals(i.height, replaced.height, .0001)
        assertEquals(i.rotation, replaced.rotation)
        assertEquals(i.fit, replaced.fit)
    }

    @Test
    fun replaceAssetResetsFocusOnlyWhenCoverAndAspectDiffers() {
        val square = PdfAsset(hash, "image/jpeg", 400, 400)
        val wide = PdfAsset("b".repeat(64), "image/jpeg", 1600, 400)
        val i = image().copy(fit = PdfFit.Cover, focusX = .1, focusY = .9)
        val differentAspect = PdfGeometry.replaceAsset(i, square, wide)
        assertEquals(.5, differentAspect.focusX, .0001)
        assertEquals(.5, differentAspect.focusY, .0001)
        val sameAspect = PdfGeometry.replaceAsset(i, square, square.copy(hash = "c".repeat(64)))
        assertEquals(.1, sameAspect.focusX, .0001)
        assertEquals(.9, sameAspect.focusY, .0001)
        val containFit = PdfGeometry.replaceAsset(i.copy(fit = PdfFit.Contain), square, wide)
        assertEquals(.1, containFit.focusX, .0001)
        assertEquals(.9, containFit.focusY, .0001)
    }

    @Test
    fun textJsonRoundTrip() {
        val p =
            project()
                .copy(
                    pages =
                        listOf(
                            PdfPage(
                                images = listOf(image()),
                                texts =
                                    listOf(
                                        PdfText(
                                            text = "Hello, Ελλάδα, Россия",
                                            x = 5.0,
                                            y = 6.0,
                                            width = 90.0,
                                            height = 30.0,
                                            sizePt = 18.0,
                                            font = PdfFontFamily.Serif,
                                            weight = PdfFontWeight.Bold,
                                            align = PdfTextAlign.End,
                                            ink = PdfInk.Blue,
                                            z = 3,
                                        )
                                    ),
                            )
                        )
                )
        assertEquals(p, PdfCodec.decode(PdfCodec.encode(p)))
    }

    @Test
    fun combinedElementCountOfTwentyFourIsTheHardLimit() {
        val page = PdfPage(images = List(20) { image() }, texts = List(4) { PdfText(text = "x") })
        project().copy(pages = listOf(page)).validate()
        val overflowByOneText =
            page.copy(texts = page.texts + PdfText(text = "y"))
        assertTrue(
            runCatching { project().copy(pages = listOf(overflowByOneText)).validate() }.isFailure
        )
        val overflowByOneImage = page.copy(images = page.images + image())
        assertTrue(
            runCatching { project().copy(pages = listOf(overflowByOneImage)).validate() }.isFailure
        )
    }

    @Test
    fun textsAreNotAllowedOnAnImportedPdfPage() {
        val pdfHash = "b".repeat(64)
        val p =
            project()
                .copy(
                    assets = project().assets + PdfAsset(pdfHash, "application/pdf"),
                    pages =
                        listOf(
                            PdfPage(source = pdfHash, texts = listOf(PdfText(text = "not allowed")))
                        ),
                )
        assertTrue(runCatching { p.validate() }.isFailure)
    }

    @Test
    fun rejectsOutOfRangeTextSizeAndBounds() {
        assertTrue(
            runCatching {
                    project()
                        .copy(pages = listOf(PdfPage(texts = listOf(PdfText(text = "x", sizePt = 5.0)))))
                        .validate()
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    project()
                        .copy(
                            pages =
                                listOf(
                                    PdfPage(
                                        texts =
                                            listOf(
                                                PdfText(
                                                    text = "x",
                                                    x = 190.0,
                                                    width = 50.0,
                                                    height = 10.0,
                                                )
                                            )
                                    )
                                )
                        )
                        .validate()
                }
                .isFailure
        )
    }

    @Test
    fun rejectsTextLengthOutsideOneToTwoThousandCharacters() {
        assertTrue(
            runCatching {
                    project().copy(pages = listOf(PdfPage(texts = listOf(PdfText(text = ""))))).validate()
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    project()
                        .copy(pages = listOf(PdfPage(texts = listOf(PdfText(text = "x".repeat(2001))))))
                        .validate()
                }
                .isFailure
        )
        // Exactly at the limit is fine.
        project().copy(pages = listOf(PdfPage(texts = listOf(PdfText(text = "x".repeat(2000)))))).validate()
    }

    @Test
    fun rejectsUnsupportedGlyphsAtValidationTime() {
        assertTrue(
            runCatching {
                    project()
                        .copy(pages = listOf(PdfPage(texts = listOf(PdfText(text = "مرحبا")))))
                        .validate()
                }
                .isFailure
        )
    }

    @Test
    fun elementIdsAreUniqueAcrossImagesAndTexts() {
        val sharedId = "shared-id"
        val page =
            PdfPage(
                images = listOf(image().copy(id = sharedId)),
                texts = listOf(PdfText(id = sharedId, text = "x")),
            )
        assertTrue(runCatching { project().copy(pages = listOf(page)).validate() }.isFailure)
    }

    @Test
    fun paintOrderSortsByZKeepingImagesBeforeTextsOnTies() {
        val a = image().copy(id = "a", z = 0)
        val b = image().copy(id = "b", z = 2)
        val t1 = PdfText(id = "t1", text = "one", z = 0)
        val t2 = PdfText(id = "t2", text = "two", z = 1)
        val page = PdfPage(images = listOf(a, b), texts = listOf(t1, t2))
        val order = PdfLayers.order(page)
        val ids =
            order.map {
                when (it) {
                    is PdfLayers.Element.Img -> it.image.id
                    is PdfLayers.Element.Txt -> it.text.id
                }
            }
        // z=0: image "a" then text "t1" (images-before-texts tie-break); then z=1 "t2"; then z=2 "b".
        assertEquals(listOf("a", "t1", "t2", "b"), ids)
    }

    @Test
    fun nextZIsOneMoreThanTheHighestExistingZ() {
        val page =
            PdfPage(images = listOf(image().copy(z = 5)), texts = listOf(PdfText(text = "x", z = 2)))
        assertEquals(6, PdfLayers.nextZ(page))
        assertEquals(0, PdfLayers.nextZ(PdfPage()))
    }

    @Test
    fun constrainedExtremeRatioIsFinite() {
        for (n in 1..1000) {
            val i =
                PdfGeometry.constrain(image().copy(width = n * 13.13, height = n * .171), PdfPage())
            assertTrue(i.width.isFinite())
            assertTrue(i.x >= 10)
        }
    }

    // --- Phase G1b: selection generalization and text-box geometry ---

    @Test
    fun elementRefDistinguishesImageAndText() {
        val byIndex: PdfElementRef = PdfElementRef.Image(2)
        val byId: PdfElementRef = PdfElementRef.Text("abc")
        assertEquals(2, (byIndex as PdfElementRef.Image).index)
        assertEquals("abc", (byId as PdfElementRef.Text).id)
        assertNotEquals(byIndex, byId)
    }

    @Test
    fun elementIdReadsEitherKindByItsOwnId() {
        val image = image()
        val text = PdfText(text = "hi")
        assertEquals(image.id, PdfLayers.elementId(PdfLayers.Element.Img(image)))
        assertEquals(text.id, PdfLayers.elementId(PdfLayers.Element.Txt(text)))
    }

    @Test
    fun withZOnlyChangesTheMatchingElementRegardlessOfKind() {
        val a = image().copy(z = 0)
        val b = PdfText(text = "b", z = 1)
        val page = PdfPage(images = listOf(a), texts = listOf(b))
        val bumped = PdfLayers.withZ(page, a.id, 9)
        assertEquals(9, bumped.images.single().z)
        assertEquals(1, bumped.texts.single().z) // untouched
        val bumpedText = PdfLayers.withZ(page, b.id, 9)
        assertEquals(0, bumpedText.images.single().z) // untouched
        assertEquals(9, bumpedText.texts.single().z)
        // No matching id: a no-op.
        assertEquals(page, PdfLayers.withZ(page, "missing", 5))
    }

    @Test
    fun constrainTextToPageScalesDownOnlyWhenNeededAndClampsPosition() {
        val page = PdfPage(width = 210.0, height = 297.0)
        val fits = PdfText(text = "x", x = 300.0, y = 300.0, width = 50.0, height = 20.0)
        val clamped = PdfGeometry.constrainTextToPage(fits, page)
        assertEquals(50.0, clamped.width, .0001) // no upscale/downscale needed
        assertTrue(clamped.x + clamped.width <= page.width + .0001)
        assertTrue(clamped.y + clamped.height <= page.height + .0001)
        val tooWide = PdfText(text = "x", width = 5000.0, height = 20.0)
        val scaled = PdfGeometry.constrainTextToPage(tooWide, page)
        assertTrue(scaled.width <= page.width + .0001)
    }

    @Test
    fun resizeTextFromCornerKeepsTheOppositeCornerFixed() {
        val page = PdfPage(width = 210.0, height = 297.0)
        val t = PdfText(text = "x", x = 50.0, y = 50.0, width = 40.0, height = 20.0)
        val resized = PdfGeometry.resizeTextFromCorner(t, page, PdfGeometry.Corner.BottomRight, 10.0, 5.0)
        // Anchor (top-left) unchanged; box grows to the right/down.
        assertEquals(50.0, resized.x, .0001)
        assertEquals(50.0, resized.y, .0001)
        assertEquals(50.0, resized.width, .0001)
        assertEquals(25.0, resized.height, .0001)
        val resizedTopLeft =
            PdfGeometry.resizeTextFromCorner(t, page, PdfGeometry.Corner.TopLeft, -10.0, -5.0)
        // Anchor (bottom-right) unchanged: right edge stays at 90, bottom edge stays at 70.
        assertEquals(90.0, resizedTopLeft.x + resizedTopLeft.width, .0001)
        assertEquals(70.0, resizedTopLeft.y + resizedTopLeft.height, .0001)
    }

    // --- Phase G1b round 2 fix (item C): layer ops must have a visible effect on tied z's ---

    @Test
    fun normalizeZReordersTwoImagesTiedAtZeroBringForward() {
        val a = image().copy(z = 0)
        val b = image().copy(z = 0)
        val page = PdfPage(images = listOf(a, b))
        // "Bring a forward" (a is behind b at index 0): the naive swap-two-z-values approach is a
        // no-op here since both start at z=0 - normalizeZ must still produce a's front.
        val reordered = PdfLayers.normalizeZ(page, listOf(b.id, a.id))
        val order = PdfLayers.order(reordered).map { PdfLayers.elementId(it) }
        assertEquals(listOf(b.id, a.id), order)
        // Strictly increasing z per position, not just "any values that happen to sort right".
        val zById = reordered.images.associateBy({ it.id }, { it.z })
        assertTrue(zById.getValue(b.id) < zById.getValue(a.id))
    }

    @Test
    fun normalizeZReordersATiedImageAndText() {
        val image = image().copy(z = 0)
        val text = PdfText(text = "t", z = 0)
        val page = PdfPage(images = listOf(image), texts = listOf(text))
        val bringTextForward = PdfLayers.normalizeZ(page, listOf(image.id, text.id))
        assertEquals(
            listOf(image.id, text.id),
            PdfLayers.order(bringTextForward).map { PdfLayers.elementId(it) },
        )
        val bringImageForward = PdfLayers.normalizeZ(page, listOf(text.id, image.id))
        assertEquals(
            listOf(text.id, image.id),
            PdfLayers.order(bringImageForward).map { PdfLayers.elementId(it) },
        )
    }

    @Test
    fun normalizeZSendsAnElementToFrontOrBackAmongTies() {
        val a = image().copy(z = 0)
        val b = image().copy(z = 0)
        val c = image().copy(z = 0)
        val page = PdfPage(images = listOf(a, b, c))
        val toFront = PdfLayers.normalizeZ(page, listOf(b.id, c.id, a.id)) // a moved to the end
        assertEquals(
            listOf(b.id, c.id, a.id),
            PdfLayers.order(toFront).map { PdfLayers.elementId(it) },
        )
        val toBack = PdfLayers.normalizeZ(page, listOf(c.id, a.id, b.id)) // c moved to the start
        assertEquals(
            listOf(c.id, a.id, b.id),
            PdfLayers.order(toBack).map { PdfLayers.elementId(it) },
        )
    }

    @Test
    fun alignTextMatchesImageAlignSemantics() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val t = PdfText(text = "x", x = 50.0, y = 50.0, width = 40.0, height = 20.0)
        val left = PdfGeometry.alignText(t, page, PdfGeometry.Align.Left)
        assertEquals(page.margin, left.x, .0001)
        val right = PdfGeometry.alignText(t, page, PdfGeometry.Align.Right)
        assertEquals(page.width - page.margin - t.width, right.x, .0001)
        val centered = PdfGeometry.alignText(t, page, PdfGeometry.Align.Center)
        assertEquals((page.width - t.width) / 2, centered.x, .0001)
    }
}
