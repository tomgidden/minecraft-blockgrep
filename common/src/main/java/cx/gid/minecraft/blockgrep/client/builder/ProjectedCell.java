package cx.gid.minecraft.blockgrep.client.builder;

import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.CellRule;

/**
 * One cell after projection.
 *
 * @param depth greater is nearer the camera, for painter's-order sorting.
 * @param scale the perspective factor already applied, so icon size and hit
 *              box can match what was drawn.
 */
record ProjectedCell(
    int x,
    int y,
    int z,
    float screenX,
    float screenY,
    float depth,
    float scale,
    CellRule rule
) {
  /**
   * Smallest icon drawn, however far the cell recedes.
   */
  private static final int MIN_ICON_PIXELS = 11;

  /**
   * Nominal item icon size, before scaling.
   */
  private static final int ICON_PIXELS = 16;

  /**
   * Slack around the icon, so the marker does not clip its own artwork.
   */
  private static final int MARKER_PADDING = 2;

  /**
   * Smallest half-width, so a distant cell stays clickable.
   */
  private static final int MIN_HALF_WIDTH = 7;

  CellPos position()
  {
    return new CellPos(x, y, z);
  }

  int iconPixels(float iconScale)
  {
    return Math.max(MIN_ICON_PIXELS, Math.round(ICON_PIXELS * iconScale * scale));
  }

  /**
   * Half-width of this cell's on-screen square.
   *
   * Drawing and hit-testing both go through here, so the box the player sees
   * around a cell is exactly the box that will catch a click on it.  Keeping
   * them in one place matters: when the hit zone was derived separately it
   * grew wider than the marker, and a nearer cell would swallow clicks meant
   * for one behind it that was not even visually covered.
   */
  int halfWidth(float iconScale)
  {
    return Math.max(MIN_HALF_WIDTH, iconPixels(iconScale) / 2 + MARKER_PADDING);
  }

  /**
   * True when the point lies within this cell's square.
   */
  boolean contains(double pointX, double pointY, float iconScale)
  {
    int half = halfWidth(iconScale);

    return Math.abs(pointX - screenX) <= half
        && Math.abs(pointY - screenY) <= half;
  }
}
