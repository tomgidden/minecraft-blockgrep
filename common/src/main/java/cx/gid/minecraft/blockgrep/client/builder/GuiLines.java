package cx.gid.minecraft.blockgrep.client.builder;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Arbitrary-angle line drawing on top of the GUI's axis-aligned primitives.
 *
 * The GUI exposes only {@code fill}, {@code horizontalLine} and
 * {@code verticalLine}, none of which can slant.  However {@code fill}
 * snapshots the current pose into its render state and applies it per-vertex
 * ({@code ColoredRectangleRenderState.buildVertices} calls
 * {@code addVertexWith2DPose}), so a rectangle drawn under a rotated pose
 * reaches the GPU as a genuinely rotated quad with float vertices — properly
 * angled and antialiased, not stair-stepped.
 *
 * Each line is therefore one quad, where stamping a square per pixel along
 * the span costs one draw per pixel of length.  That difference is what makes
 * the per-voxel grid affordable at all: the twelve bounding edges alone ran
 * to roughly two thousand draws a frame under the old approach, and the cell
 * rules would have been an order of magnitude worse again.
 */
final class GuiLines
{
  /**
   * Local-space length the rectangle is built at before the pose scales it
   * down.  {@code fill} takes integers, so a short line would otherwise
   * quantize badly; building long and scaling keeps sub-pixel accuracy.
   */
  private static final int PRECISION = 1024;

  /**
   * Screen-space step between stipple dots: one pixel lit, one skipped.
   */
  private static final float DOT_PERIOD = 2.0f;

  private GuiLines()
  {
  }

  /**
   * Draws a line of the given thickness between two points.
   */
  static void line(
      GuiGraphicsExtractor graphics,
      float x0,
      float y0,
      float x1,
      float y1,
      float thickness,
      int color
  )
  {
    float dx     = x1 - x0;
    float dy     = y1 - y0;
    float length = (float) Math.sqrt(dx * dx + dy * dy);

    if(length < 0.001f) return;

    graphics.pose().pushMatrix();
    graphics.pose().translate(x0, y0);
    graphics.pose().rotate((float) Math.atan2(dy, dx));
    graphics.pose().scale(length / PRECISION, thickness);

    // The unit rectangle straddles y=0, so the stroke grows evenly either
    // side of the path rather than hanging off one edge of it.
    graphics.pose().translate(0, -0.5f);
    graphics.fill(0, 0, PRECISION, 1, color);

    graphics.pose().popMatrix();
  }

  /**
   * Stipples a line as single pixels, one on and one off, at a fixed screen
   * density that does not change with zoom.
   *
   * At this spacing a rotated quad per dot would be pure overhead — the quad
   * is a pixel across — so each dot is just a 1x1 {@code fill}, needing no
   * pose work at all.  The result is a hairline that stays visually the same
   * weight however large the geometry is drawn, which is what makes it read
   * as a subdivision rather than competing with the solid bounding box.
   *
   * The pattern is anchored at {@code (x0, y0)}, so edges meeting at a
   * corner start together rather than beating against each other.
   */
  static void dottedLine(
      GuiGraphicsExtractor graphics,
      float x0,
      float y0,
      float x1,
      float y1,
      int color
  )
  {
    float dx     = x1 - x0;
    float dy     = y1 - y0;
    float length = (float) Math.sqrt(dx * dx + dy * dy);

    if(length < 0.001f) return;

    float ux = dx / length;
    float uy = dy / length;

    // One lit pixel every other pixel along the run.
    int dots = (int) (length / DOT_PERIOD);

    for(int i = 0; i <= dots; i++) {
      float at = i * DOT_PERIOD;

      int x = Math.round(x0 + ux * at);
      int y = Math.round(y0 + uy * at);

      graphics.fill(x, y, x + 1, y + 1, color);
    }
  }

  /**
   * Draws the outline of a quad, for one face of a voxel.
   */
  static void quadOutline(
      GuiGraphicsExtractor graphics,
      float[] a,
      float[] b,
      float[] c,
      float[] d,
      float thickness,
      int color
  )
  {
    line(graphics, a[0], a[1], b[0], b[1], thickness, color);
    line(graphics, b[0], b[1], c[0], c[1], thickness, color);
    line(graphics, c[0], c[1], d[0], d[1], thickness, color);
    line(graphics, d[0], d[1], a[0], a[1], thickness, color);
  }

  /**
   * Fills a convex quad, by splitting it into two triangles drawn as
   * degenerate-free spans.
   *
   * There is no triangle primitive, so each triangle is approximated by
   * scanning it in thin horizontal bands.  Bands are cheap enough for the
   * single hovered voxel, which is the only thing that needs a translucent
   * fill, but would not be for every cell.
   */
  static void fillQuad(
      GuiGraphicsExtractor graphics,
      float[] a,
      float[] b,
      float[] c,
      float[] d,
      int color
  )
  {
    fillTriangle(graphics, a, b, c, color);
    fillTriangle(graphics, a, c, d, color);
  }

  private static void fillTriangle(
      GuiGraphicsExtractor graphics,
      float[] a,
      float[] b,
      float[] c,
      int color
  )
  {
    float minY = Math.min(a[1], Math.min(b[1], c[1]));
    float maxY = Math.max(a[1], Math.max(b[1], c[1]));

    int top    = Math.round(minY);
    int bottom = Math.round(maxY);

    if(bottom <= top) return;

    for(int y = top; y < bottom; y++) {
      float scanY = y + 0.5f;

      float left  = Float.MAX_VALUE;
      float right = -Float.MAX_VALUE;

      // Intersect the scanline with each edge.
      float[][] edges = {{a[0], a[1], b[0], b[1]}, {b[0], b[1], c[0], c[1]}, {c[0], c[1], a[0], a[1]}};

      for(float[] e: edges) {
        float y0 = e[1];
        float y1 = e[3];

        if((scanY < y0 && scanY < y1) || (scanY >= y0 && scanY >= y1)) continue;

        float t = (scanY - y0) / (y1 - y0);
        float x = e[0] + (e[2] - e[0]) * t;

        left  = Math.min(left, x);
        right = Math.max(right, x);
      }

      if(right > left) {
        graphics.fill(Math.round(left), y, Math.round(right), y + 1, color);
      }
    }
  }
}
