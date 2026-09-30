package com.librestatic.lightforge

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.librestatic.lightforge.core.mediastore.MediaAction
import com.librestatic.lightforge.core.mediastore.MediaActionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaActionHapticsTest {
    @Test fun favoritesToggleAndDestructiveActionsConfirm() {
        assertEquals(HapticFeedbackType.ToggleOn, mediaActionHapticType(MediaAction.Favorite(true), MediaActionPhase.Complete))
        assertEquals(HapticFeedbackType.ToggleOff, mediaActionHapticType(MediaAction.Favorite(false), MediaActionPhase.Complete))
        assertEquals(HapticFeedbackType.Confirm, mediaActionHapticType(MediaAction.Trash(true), MediaActionPhase.Complete))
        assertEquals(HapticFeedbackType.Confirm, mediaActionHapticType(MediaAction.Delete, MediaActionPhase.Complete))
    }

    @Test fun failuresRejectAndCancelsOrWritesStaySilent() {
        assertEquals(
            HapticFeedbackType.Reject,
            mediaActionHapticType(MediaAction.Delete, MediaActionPhase.RequestFailed(1, emptyList(), "denied")),
        )
        assertNull(mediaActionHapticType(MediaAction.Delete, MediaActionPhase.Cancelled(1, emptyList())))
        assertNull(mediaActionHapticType(MediaAction.Write, MediaActionPhase.Complete))
        assertNull(mediaActionHapticType(MediaAction.Trash(true), MediaActionPhase.ReadyForChunk))
    }
}
