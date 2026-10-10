package com.librestatic.lightforge

import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationEngine
import com.librestatic.lightforge.core.preferences.FrameInterpolationEngine as PreferredEngine

/**
 * Maps the persisted engine preference to the interpolator's engine by name; core:preferences
 * cannot depend on core:frame-interpolation, so the two enums are declared separately.
 */
internal fun PreferredEngine.toInterpolationEngine(): FrameInterpolationEngine =
    FrameInterpolationEngine.valueOf(name)
