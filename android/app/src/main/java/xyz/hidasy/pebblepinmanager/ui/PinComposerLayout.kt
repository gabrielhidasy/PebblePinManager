package xyz.hidasy.pebblepinmanager.ui

/**
 * Layout modes for [PinComposerScreen].
 *
 * - [SINGLE_PANE]: Stacked vertical layout used on compact portrait screens (standard phones and
 *   closed foldables).
 * - [TWO_PANE]: Side-by-side layout placing the status card & pin composer on the left column and
 *   sent timeline pins on the right column (unfolded foldables like Galaxy Z Fold, tablets, and
 *   standard phones in landscape).
 */
enum class PinComposerLayoutMode {
    SINGLE_PANE,
    TWO_PANE,
}

/**
 * Pure layout breakpoint policy for [PinComposerScreen].
 *
 * Unfolded book-style foldables (e.g. Galaxy Z Fold 7) have a nearly square inner display whose
 * width is typically 650–840dp even when held vertically (height slightly greater than width), so
 * width-based breakpoints are required rather than checking orientation alone. Standard phones in
 * landscape also enter [PinComposerLayoutMode.TWO_PANE], including compact or zoomed displays
 * whose usable width after system bar / display cutout insets is at least
 * [LANDSCAPE_TWO_PANE_MIN_WIDTH_DP].
 */
internal object PinComposerLayoutPolicy {
    /** Material 3 Medium width breakpoint (covers unfolded foldables & tablets in any orientation). */
    const val TWO_PANE_MIN_WIDTH_DP: Float = 600f

    /**
     * Lower width threshold applied only when the viewport is wider than tall (landscape phones
     * where system bars / display cutouts or display zoom slightly reduce available width below
     * 600dp), while staying well above portrait phone widths even with a soft keyboard open.
     */
    const val LANDSCAPE_TWO_PANE_MIN_WIDTH_DP: Float = 540f

    fun resolveLayoutMode(
        availableWidthDp: Float,
        availableHeightDp: Float,
    ): PinComposerLayoutMode {
        val isWideEnough = availableWidthDp >= TWO_PANE_MIN_WIDTH_DP
        val isLandscapePhoneWideEnough =
            availableWidthDp >= LANDSCAPE_TWO_PANE_MIN_WIDTH_DP &&
                availableWidthDp > availableHeightDp
        return if (isWideEnough || isLandscapePhoneWideEnough) {
            PinComposerLayoutMode.TWO_PANE
        } else {
            PinComposerLayoutMode.SINGLE_PANE
        }
    }
}
