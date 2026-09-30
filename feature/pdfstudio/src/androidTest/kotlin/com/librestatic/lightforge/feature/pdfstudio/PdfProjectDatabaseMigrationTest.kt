package com.librestatic.lightforge.feature.pdfstudio

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Room v8 -> v9 (Phase E): projects gains pageCount/sourceBytes/coverPageId, gallery_deliveries
 * gains the Phase F placement columns. Both are additive (non-destructive) migrations. */
class PdfProjectDatabaseMigrationTest {
    @Test
    fun eightToNinePreservesProjectsAndGalleryDeliveriesWithNewColumnDefaults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "pdf-migration-${UUID.randomUUID()}.db"
        val helper = MigrationTestHelper(instrumentation, PdfProjectDatabase::class.java)
        try {
            helper.createDatabase(name, 8).use { db ->
                db.execSQL(
                    "INSERT INTO projects(id,name,updated,manifest,editor) VALUES('p1','My project',1000,'{}','')"
                )
                db.execSQL(
                    "INSERT INTO gallery_deliveries(id,name,uris,error) VALUES('d1','Selection',' [] ',NULL)"
                )
            }
            helper
                .runMigrationsAndValidate(
                    name,
                    9,
                    true,
                    PdfProjectDatabase.MIGRATION_8_9,
                )
                .use { db ->
                    db.query(
                            "SELECT name,updated,manifest,editor,pageCount,sourceBytes,coverPageId FROM projects WHERE id='p1'"
                        )
                        .use { c ->
                            assertTrue(c.moveToFirst())
                            assertEquals("My project", c.getString(0))
                            assertEquals(1000L, c.getLong(1))
                            assertEquals("{}", c.getString(2))
                            assertEquals("", c.getString(3))
                            assertEquals(0, c.getInt(4))
                            assertEquals(0L, c.getLong(5))
                            assertTrue(c.isNull(6))
                        }
                    db.query(
                            "SELECT name,uris,error,targetProjectId,targetPageId,placementX,placementY FROM gallery_deliveries WHERE id='d1'"
                        )
                        .use { c ->
                            assertTrue(c.moveToFirst())
                            assertEquals("Selection", c.getString(0))
                            assertEquals(" [] ", c.getString(1))
                            assertTrue(c.isNull(2))
                            assertTrue(c.isNull(3))
                            assertTrue(c.isNull(4))
                            assertTrue(c.isNull(5))
                            assertTrue(c.isNull(6))
                        }
                }
        } finally {
            check(context.deleteDatabase(name))
        }
    }
}
