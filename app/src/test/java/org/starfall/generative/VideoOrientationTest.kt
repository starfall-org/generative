package org.starfall.generative

import androidx.media3.common.VideoSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.generative.shared.subfeatures.media.ui.isLandscapeVideo

class VideoOrientationTest {
    @Test fun landscapeVideoUsesDecodedDimensions() {
        assertTrue(isLandscapeVideo(VideoSize(1920, 1080)))
        assertFalse(isLandscapeVideo(VideoSize(1080, 1920)))
        assertFalse(isLandscapeVideo(VideoSize(1080, 1080)))
        assertFalse(isLandscapeVideo(VideoSize(0, 0)))
    }

    @Test fun anamorphicVideoUsesDisplayAspectRatio() {
        assertTrue(isLandscapeVideo(VideoSize(720, 1080, 0, 2f)))
        assertFalse(isLandscapeVideo(VideoSize(1920, 1080, 0, 0.5f)))
    }

    @Test fun unappliedRotationChangesDisplayOrientation() {
        assertFalse(isLandscapeVideo(VideoSize(1920, 1080, 90, 1f)))
        assertTrue(isLandscapeVideo(VideoSize(1080, 1920, 270, 1f)))
    }
}
