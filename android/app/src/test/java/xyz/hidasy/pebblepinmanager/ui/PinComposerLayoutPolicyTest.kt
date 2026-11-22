package xyz.hidasy.pebblepinmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PinComposerLayoutPolicyTest {

    @Test
    fun `closed foldable and standard phones in portrait use single pane`() {
        val portraitProfiles = listOf(
            360f to 800f, // Compact Android phone portrait
            380f to 880f, // Galaxy Z Fold cover screen portrait
            412f to 915f, // Standard phone portrait
            448f to 998f, // Large phone portrait
        )

        portraitProfiles.forEach { (width, height) ->
            assertEquals(
                "Expected SINGLE_PANE for ${width}x${height}dp",
                PinComposerLayoutMode.SINGLE_PANE,
                PinComposerLayoutPolicy.resolveLayoutMode(width, height),
            )
        }
    }

    @Test
    fun `portrait phone with tall soft keyboard stays single pane`() {
        assertEquals(
            PinComposerLayoutMode.SINGLE_PANE,
            PinComposerLayoutPolicy.resolveLayoutMode(
                availableWidthDp = 412f,
                availableHeightDp = 340f,
            ),
        )
    }

    @Test
    fun `unfolded foldable square inner display uses two pane in both vertical and horizontal orientations`() {
        val foldableProfiles = listOf(
            656f to 728f, // Z Fold 7 unfolded vertical (high density)
            700f to 780f, // Z Fold unfolded vertical (portrait-ish square)
            750f to 832f, // Z Fold 7 unfolded vertical (420dpi)
            832f to 750f, // Z Fold 7 unfolded rotated (landscape-ish square)
            600f to 680f, // Exact Medium breakpoint threshold
        )

        foldableProfiles.forEach { (width, height) ->
            assertEquals(
                "Expected TWO_PANE for ${width}x${height}dp",
                PinComposerLayoutMode.TWO_PANE,
                PinComposerLayoutPolicy.resolveLayoutMode(width, height),
            )
        }
    }

    @Test
    fun `standard phones in landscape use two pane`() {
        val landscapePhoneProfiles = listOf(
            800f to 360f, // Standard phone landscape
            915f to 412f, // Large phone landscape
            720f to 360f, // Phone landscape after system bars & cutout
            560f to 330f, // Compact/zoomed phone landscape after cutout + 3-button nav
            540f to 320f, // Exact landscape minimum width threshold
        )

        landscapePhoneProfiles.forEach { (width, height) ->
            assertEquals(
                "Expected TWO_PANE for ${width}x${height}dp",
                PinComposerLayoutMode.TWO_PANE,
                PinComposerLayoutPolicy.resolveLayoutMode(width, height),
            )
        }
    }

    @Test
    fun `narrow split screen panes fall back to single pane`() {
        val narrowSplitProfiles = listOf(
            350f to 760f, // Half of unfolded Z Fold in vertical split screen
            539f to 360f, // Just below landscape threshold
            599f to 720f, // Just below portrait/square medium threshold
        )

        narrowSplitProfiles.forEach { (width, height) ->
            assertEquals(
                "Expected SINGLE_PANE for ${width}x${height}dp",
                PinComposerLayoutMode.SINGLE_PANE,
                PinComposerLayoutPolicy.resolveLayoutMode(width, height),
            )
        }
    }

    @Test
    fun `tablets use two pane in portrait and landscape`() {
        assertEquals(
            PinComposerLayoutMode.TWO_PANE,
            PinComposerLayoutPolicy.resolveLayoutMode(768f, 1024f),
        )
        assertEquals(
            PinComposerLayoutMode.TWO_PANE,
            PinComposerLayoutPolicy.resolveLayoutMode(1280f, 800f),
        )
    }
}
