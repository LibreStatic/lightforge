package com.librestatic.lightforge.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MlStageChainTest {
    @Test
    fun `people stages chain detection to embeddings to clustering`() {
        assertEquals(MlTaskType.FaceEmbeddings, MlChunkWorker.nextStage(MlTaskType.FaceDetection))
        assertEquals(MlTaskType.PersonClustering, MlChunkWorker.nextStage(MlTaskType.FaceEmbeddings))
        assertNull(MlChunkWorker.nextStage(MlTaskType.PersonClustering))
    }

    @Test
    fun `other tasks chain nothing`() {
        MlTaskType.entries
            .filterNot { it == MlTaskType.FaceDetection || it == MlTaskType.FaceEmbeddings }
            .forEach { assertNull(MlChunkWorker.nextStage(it)) }
    }
}
