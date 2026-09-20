package cx.gid.minecraft.blockgrep.client.builder;

import cx.gid.minecraft.blockgrep.client.builder.EditablePattern.CellRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The 3D voxel view: projection, picking, and everything drawn inside the
 * viewport rectangle.
 *
 * Cells are drawn as flat icons sorted back-to-front rather than as real
 * geometry, which keeps the whole editor inside the ordinary GUI extraction
 * pipeline — no world render pass, and identical behavior on Fabric and
 * NeoForge.
 */
final class PatternViewport
{
  /**
   * A screen-space anchor for one axis handle.
   */
  record Anchor(int x, int y) {
  }

  /**
   * Where each grid-resize button wants to sit, in the order the screen lays
   * them out.
   */
  record AxisHandles(
      Anchor xMinus,
      Anchor xPlus,
      Anchor yMinus,
      Anchor yPlus,
      Anchor zMinus,
      Anchor zPlus
  ) {
    List<Anchor> inOrder()
    {
      return List.of(xMinus, xPlus, yMinus, yPlus, zMinus, zPlus);
    }
  }

  private static final int BACKGROUND_COLOR = 0xB010141B;
  private static final int BORDER_COLOR     = 0x805A708A;
  private static final int BOUNDS_COLOR     = 0x704FA9C8;

  private static final int WILDCARD_FILL_COLOR = 0x183A6BFF;
  private static final int WILDCARD_EDGE_COLOR = 0x504F75FF;
  private static final int WILDCARD_TEXT_COLOR = 0xA0B9C8FF;

  private static final int INSPECT_COLOR = 0xFFFFD700;
  private static final int PAINT_COLOR   = 0xFF65D7FF;

  private static final int AXIS_X_COLOR = 0xFFFF5555;
  private static final int AXIS_Y_COLOR = 0xFF55FF55;
  private static final int AXIS_Z_COLOR = 0xFF5599FF;

  private static final int AXIS_X_LABEL = 0xFFFF7777;
  private static final int AXIS_Y_LABEL = 0xFF77FF77;
  private static final int AXIS_Z_LABEL = 0xFF77AAFF;

  private static final float GIZMO_LENGTH = 18;

  private static final float MIN_CELL_SPACING = 8.0f;
  private static final float MAX_CELL_SPACING = 34.0f;

  /**
   * Spacing at which an icon is drawn at its natural size.
   */
  private static final float ICON_REFERENCE_SPACING = 17.0f;

  private static final float MIN_ICON_SCALE = 0.65f;
  private static final float MAX_ICON_SCALE = 2.25f;

  /**
   * Spacing above which a wildcard cell is roomy enough to label.
   */
  private static final int WILDCARD_LABEL_SPACING = 19;

  private static final float BOUNDS_THICKNESS = 1.0f;
  private static final float GIZMO_THICKNESS  = 1.5f;

  /**
   * Stipple marking the boundary between adjacent cells.  Drawn as single
   * pixels at a fixed screen density, so it stays a hairline subdivision at
   * any zoom instead of competing with the solid bounding box.
   */
  private static final int GRID_COLOR = 0x50A8C4DC;

  /**
   * Cell spacing below which the stipple is dropped.  Finer than this the
   * dots merge into a haze that only obscures the icons.
   */
  private static final float MIN_GRID_SPACING = 13.0f;

  /**
   * Translucent cuboid marking the cell under the cursor.
   */
  private static final int HOVER_FILL_COLOR       = 0x3865D7FF;
  private static final int HOVER_EDGE_COLOR       = 0xC0AEE9FF;
  private static final float HOVER_EDGE_THICKNESS = 1.0f;

  /**
   * The six faces of a cell, as index quads into the eight corners, each
   * wound consistently so the outline closes.  Corner index bits are
   * x=1, y=2, z=4.
   */
  private static final int[][] BOX_FACES = {
      {0, 1, 3, 2}, // z low
      {4, 5, 7, 6}, // z high
      {0, 1, 5, 4}, // y low
      {2, 3, 7, 6}, // y high
      {0, 2, 6, 4}, // x low
      {1, 3, 7, 5} // x high
  };

  /**
   * The twelve edges of the bounding box, as index pairs into the eight
   * corners generated in x-then-z-then-y order.
   */
  private static final int[][] BOX_EDGES = {
      {0, 1},
      {0, 2},
      {1, 3},
      {2, 3},
      {4, 5},
      {4, 6},
      {5, 7},
      {6, 7},
      {0, 4},
      {1, 5},
      {2, 6},
      {3, 7}
  };

  private final Font font;
  private final RuleIcons icons;
  private final EditorCamera camera = new EditorCamera();

  private int left;
  private int top;
  private int right;
  private int bottom;

  /**
   * Cells projected for the current frame, reused for hover and picking.
   */
  private List<ProjectedCell> projected = List.of();

  PatternViewport(Font font, RuleIcons icons)
  {
    this.font  = font;
    this.icons = icons;
  }

  EditorCamera camera()
  {
    return camera;
  }

  void setBounds(int left, int top, int right, int bottom)
  {
    this.left   = left;
    this.top    = top;
    this.right  = right;
    this.bottom = bottom;
  }

  boolean contains(double x, double y)
  {
    return x >= left && x < right && y >= top && y < bottom;
  }

  int centerX()
  {
    return (left + right) / 2;
  }

  int centerY()
  {
    return (top + bottom) / 2 + 5;
  }

  // -- projection ---------------------------------------------------------

  /**
   * Fits the pattern to the viewport.  The vertical extent allows for the
   * volume's own lean, since a rotated box is taller on screen than its
   * height alone.
   */
  float cellSpacing(EditablePattern pattern, boolean sliceOnly)
  {
    float availableW = Math.max(80, right - left - 8);
    float availableH = Math.max(70, bottom - top - 20);

    float horizontalExtent = Math.max(1.5f, pattern.sizeX() + pattern.sizeZ());
    float verticalExtent   = Math.max(
        1.5f,
        (sliceOnly ? 1 : pattern.sizeY())
            + (pattern.sizeX() + pattern.sizeZ()) * 0.38f
    );

    float fit = Math.min(
                    availableW / horizontalExtent,
                    availableH / verticalExtent
                )
        * 0.92f;

    return Math.clamp(fit, MIN_CELL_SPACING, MAX_CELL_SPACING) * camera.zoom();
  }

  /**
   * How large to draw a cell's icon at the given spacing.
   *
   * Shared by rendering and picking so the two cannot drift apart; the hit
   * box is derived from this same figure.
   */
  private static float iconScale(float spacing)
  {
    return Math.clamp(
        spacing / ICON_REFERENCE_SPACING,
        MIN_ICON_SCALE,
        MAX_ICON_SCALE
    );
  }

  /**
   * Projects a model-space point, centering it on the pattern first so the
   * perspective divide stays symmetric.
   */
  private float[] project(
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      float x,
      float y,
      float z
  )
  {
    return project(
        pattern,
        sliceOnly,
        sliceY,
        x,
        y,
        z,
        cellSpacing(pattern, sliceOnly)
    );
  }

  /**
   * As above, but with the spacing supplied.  Bulk callers compute it once
   * and pass it in rather than having every point re-derive the same fit.
   */
  private float[] project(
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      float x,
      float y,
      float z,
      float spacing
  )
  {
    float centerY = sliceOnly ? sliceY + 0.5f : pattern.sizeY() / 2.0f;

    float[] p = camera.project(
        x - pattern.sizeX() / 2.0f,
        y - centerY,
        z - pattern.sizeZ() / 2.0f,
        spacing
    );

    return new float[] {centerX() + p[0], centerY() + p[1], p[2], p[3]};
  }

  private List<ProjectedCell> projectCells(
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY
  )
  {
    List<ProjectedCell> out = new ArrayList<>();

    int minY = sliceOnly ? sliceY : 0;
    int maxY = sliceOnly ? sliceY + 1 : pattern.sizeY();

    float spacing = cellSpacing(pattern, sliceOnly);

    for(int y = minY; y < maxY; y++) {
      for(int z = 0; z < pattern.sizeZ(); z++) {
        for(int x = 0; x < pattern.sizeX(); x++) {
          float[] p = project(
              pattern,
              sliceOnly,
              sliceY,
              x + 0.5f,
              y + 0.5f,
              z + 0.5f,
              spacing
          );

          out.add(new ProjectedCell(
              x,
              y,
              z,
              p[0],
              p[1],
              p[2],
              p[3],
              pattern.cell(x, y, z)
          ));
        }
      }
    }

    // Painter's order: larger depth is closer to the camera.
    out.sort(Comparator.comparingDouble(ProjectedCell::depth));
    return out;
  }

  // -- picking ------------------------------------------------------------

  /**
   * Picks against the cells projected for the last rendered frame, so what
   * the player clicks is exactly what they saw.
   */
  CellPos pick(double mouseX, double mouseY, EditablePattern pattern, boolean sliceOnly, int sliceY)
  {
    List<ProjectedCell> cells = projected.isEmpty()
        ? projectCells(pattern, sliceOnly, sliceY)
        : projected;

    return pick(
        mouseX,
        mouseY,
        cells,
        iconScale(cellSpacing(pattern, sliceOnly))
    );
  }

  /**
   * Nearest cell whose drawn square contains the cursor.
   *
   * The hit box is the same square {@link ProjectedCell#halfWidth} sizes for
   * drawing, so the clickable area matches the artwork exactly.  A nearer
   * cell still wins a genuine overlap — that is what makes the front of the
   * volume paintable — but it can no longer claim a click that landed
   * outside its own box, which used to make cells behind it unreachable even
   * when plainly visible.
   */
  private static CellPos pick(
      double mouseX,
      double mouseY,
      List<ProjectedCell> cells,
      float iconScale
  )
  {
    ProjectedCell best = null;

    for(ProjectedCell cell: cells) {
      if(!cell.contains(mouseX, mouseY, iconScale)) continue;

      if(best == null || cell.depth() > best.depth()) {
        best = cell;
      }
    }

    return best == null ? null : best.position();
  }

  // -- axis handles -------------------------------------------------------

  /**
   * Clear air left past the silhouette before the first button of a pair,
   * and the step from it to the second, both in button widths.
   */
  private static final float HANDLE_LEAD = 1.15f;
  private static final float HANDLE_STEP = 1.15f;

  /**
   * Screen-space anchors for the six grid-resize buttons.
   *
   * Each pair lies on the screen projection of one box edge, extended past
   * the end of that edge so the buttons sit outside the volume rather than
   * over the cells.  All three edges are taken from the single rear-most
   * vertex — the corner whose center-relative position points furthest away
   * from the camera — so the edges fan out towards the viewer and the
   * buttons land clear on the opposite side.
   *
   * Choosing the origin by depth rather than by screen position makes the
   * rule orientation-agnostic: looking down picks a bottom corner and the
   * buttons rise above the volume, looking up picks a top corner and they
   * fall below it, with no special case for either.  The pairs swap corners
   * as the view orbits past a threshold, which is expected.
   *
   * @param buttonSize the caller's button size, which sets how far along
   *                   each edge direction the two buttons sit.
   */
  AxisHandles axisHandles(
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      int buttonSize
  )
  {
    float sizeX = pattern.sizeX();
    float sizeY = sliceOnly ? 1 : pattern.sizeY();
    float sizeZ = pattern.sizeZ();

    float baseY = sliceOnly ? sliceY : 0;

    // The eight corners, as 0/1 selectors per axis.
    float[][] corners = new float[8][];
    int rear          = 0;

    for(int i = 0; i < 8; i++) {
      float x = ((i & 1) == 0 ? 0 : sizeX);
      float y = baseY + ((i & 2) == 0 ? 0 : sizeY);
      float z = ((i & 4) == 0 ? 0 : sizeZ);

      corners[i] = project(pattern, sliceOnly, sliceY, x, y, z);

      // Depth grows towards the camera, so the rear vertex is the minimum.
      if(corners[i][2] < corners[rear][2]) rear = i;
    }

    // The three edges out of that corner, each flipping one axis bit.
    Anchor[] minus = new Anchor[3];
    Anchor[] plus  = new Anchor[3];

    for(int axis = 0; axis < 3; axis++) {
      int far = rear ^ (1 << axis);

      float ox = corners[rear][0];
      float oy = corners[rear][1];
      float fx = corners[far][0];
      float fy = corners[far][1];

      float dx     = fx - ox;
      float dy     = fy - oy;
      float length = (float) Math.hypot(dx, dy);

      // A degenerate edge (a 1-deep axis seen end-on) has no direction of
      // its own; fall back to straight up so the pair stays placeable.
      float ux = length < 0.001f ? 0 : dx / length;
      float uy = length < 0.001f ? -1 : dy / length;

      // A fixed lead is not always enough: seen near edge-on, a large volume
      // projects a short edge but a wide silhouette, and the button would
      // land back over the cells.  Push out until past every corner along
      // this direction, then add the lead to that.
      float clearance = 0;
      for(float[] corner: corners) {
        float along = (corner[0] - fx) * ux + (corner[1] - fy) * uy;
        clearance   = Math.max(clearance, along);
      }

      float lead = clearance + buttonSize * HANDLE_LEAD;
      float step = buttonSize * HANDLE_STEP;

      minus[axis] = new Anchor(
          Math.round(fx + ux * lead),
          Math.round(fy + uy * lead)
      );

      plus[axis] = new Anchor(
          Math.round(fx + ux * (lead + step)),
          Math.round(fy + uy * (lead + step))
      );
    }

    return new AxisHandles(
        minus[0],
        plus[0],
        minus[1],
        plus[1],
        minus[2],
        plus[2]
    );
  }

  // -- rendering ----------------------------------------------------------

  /**
   * @return the cell under the cursor, or {@code null}.
   */
  CellPos render(
      GuiGraphicsExtractor graphics,
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      CellPos selected,
      boolean inspectMode,
      int mouseX,
      int mouseY
  )
  {
    float spacing   = cellSpacing(pattern, sliceOnly);
    float iconScale = iconScale(spacing);

    graphics.fill(left, top, right, bottom, BACKGROUND_COLOR);
    graphics.outline(left, top, right - left, bottom - top, BORDER_COLOR);
    graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);

    drawBounds(graphics, pattern, sliceOnly, sliceY);
    drawVoxelGrid(graphics, pattern, sliceOnly, sliceY, spacing);

    projected = projectCells(pattern, sliceOnly, sliceY);

    CellPos hovered = contains(mouseX, mouseY)
        ? pick(mouseX, mouseY, projected, iconScale)
        : null;

    // Behind the icons, so the cuboid reads as the cell containing them
    // rather than a pane floating in front.
    if(hovered != null) {
      drawHoverCell(graphics, pattern, sliceOnly, sliceY, hovered);
    }

    for(ProjectedCell cell: projected) {
      drawCell(graphics, cell, spacing, iconScale, selected, hovered, inspectMode);
    }

    drawGizmo(graphics);
    graphics.disableScissor();

    return hovered;
  }

  private void drawCell(
      GuiGraphicsExtractor graphics,
      ProjectedCell cell,
      float spacing,
      float iconScale,
      CellPos selected,
      CellPos hovered,
      boolean inspectMode
  )
  {
    int px = Math.round(cell.screenX());
    int py = Math.round(cell.screenY());

    // Near cells are drawn larger; the hit box follows the same figures.
    float scale    = iconScale * cell.scale();
    int iconPixels = cell.iconPixels(iconScale);

    if(cell.rule().isAny()) {
      drawWildcardCell(graphics, px, py, iconPixels, spacing * cell.scale());
    }
    else {
      icons.drawRule(graphics, cell.rule(), px, py, scale);
    }

    // The hovered cell gets a translucent cuboid instead, so only the
    // selection needs a flat marker here.
    CellPos position = cell.position();
    if(position.equals(selected) && !position.equals(hovered)) {
      int half  = cell.halfWidth(iconScale);
      int color = inspectMode ? INSPECT_COLOR : PAINT_COLOR;

      graphics.outline(px - half, py - half, half * 2, half * 2, color);
    }
  }

  private void drawWildcardCell(
      GuiGraphicsExtractor graphics,
      int px,
      int py,
      int iconPixels,
      float spacing
  )
  {
    int radius = Math.max(3, iconPixels / 3);

    graphics.fill(
        px - radius,
        py - radius,
        px + radius,
        py + radius,
        WILDCARD_FILL_COLOR
    );

    graphics.outline(
        px - radius,
        py - radius,
        radius * 2,
        radius * 2,
        WILDCARD_EDGE_COLOR
    );

    if(spacing > WILDCARD_LABEL_SPACING) {
      graphics.centeredText(font, "?", px, py - 4, WILDCARD_TEXT_COLOR);
    }
  }

  private void drawBounds(
      GuiGraphicsExtractor graphics,
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY
  )
  {
    float minY = sliceOnly ? sliceY : 0;
    float maxY = sliceOnly ? sliceY + 1 : pattern.sizeY();

    float[][] corners = new float[8][];
    int at            = 0;

    for(int y = 0; y < 2; y++) {
      for(int z = 0; z < 2; z++) {
        for(int x = 0; x < 2; x++) {
          corners[at++] = project(
              pattern,
              sliceOnly,
              sliceY,
              x == 0 ? 0 : pattern.sizeX(),
              y == 0 ? minY : maxY,
              z == 0 ? 0 : pattern.sizeZ()
          );
        }
      }
    }

    for(int[] edge: BOX_EDGES) {
      GuiLines.line(
          graphics,
          corners[edge[0]][0],
          corners[edge[0]][1],
          corners[edge[1]][0],
          corners[edge[1]][1],
          BOUNDS_THICKNESS,
          BOUNDS_COLOR
      );
    }
  }

  /**
   * Dotted lines along every interior cell boundary, so individual voxels
   * read as separate cells rather than one open box.
   *
   * Only the three faces of the box nearest the camera are ruled.  Ruling
   * all six would double the line count for no gain, since the far ones sit
   * behind the cells and mostly hide anyway.  The whole grid is skipped once
   * the cells are too small for the dashes to resolve.
   */
  private void drawVoxelGrid(
      GuiGraphicsExtractor graphics,
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      float spacing
  )
  {
    if(spacing < MIN_GRID_SPACING) return;

    int nx = pattern.sizeX();
    int ny = sliceOnly ? 1 : pattern.sizeY();
    int nz = pattern.sizeZ();

    float baseY = sliceOnly ? sliceY : 0;

    // Which corner of each axis is nearest, so the ruled faces are the ones
    // facing the camera.
    boolean farX = isFarFace(pattern, sliceOnly, sliceY, 0);
    boolean farY = isFarFace(pattern, sliceOnly, sliceY, 1);
    boolean farZ = isFarFace(pattern, sliceOnly, sliceY, 2);

    float faceX = farX ? 0 : nx;
    float faceY = baseY + (farY ? 0 : ny);
    float faceZ = farZ ? 0 : nz;

    // Face at constant X: rule along Y and Z.
    for(int i = 1; i < ny; i++) {
      dot(graphics, pattern, sliceOnly, sliceY, faceX, baseY + i, 0, faceX, baseY + i, nz);
    }
    for(int k = 1; k < nz; k++) {
      dot(graphics, pattern, sliceOnly, sliceY, faceX, baseY, k, faceX, baseY + ny, k);
    }

    // Face at constant Y: rule along X and Z.
    for(int i = 1; i < nx; i++) {
      dot(graphics, pattern, sliceOnly, sliceY, i, faceY, 0, i, faceY, nz);
    }
    for(int k = 1; k < nz; k++) {
      dot(graphics, pattern, sliceOnly, sliceY, 0, faceY, k, nx, faceY, k);
    }

    // Face at constant Z: rule along X and Y.
    for(int i = 1; i < nx; i++) {
      dot(graphics, pattern, sliceOnly, sliceY, i, baseY, faceZ, i, baseY + ny, faceZ);
    }
    for(int j = 1; j < ny; j++) {
      dot(graphics, pattern, sliceOnly, sliceY, 0, baseY + j, faceZ, nx, baseY + j, faceZ);
    }
  }

  /**
   * @return true when the low end of this axis is the one further from the
   *         camera.
   */
  private boolean isFarFace(
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      int axis
  )
  {
    float midX = pattern.sizeX() / 2.0f;
    float midY = (sliceOnly ? sliceY : 0) + (sliceOnly ? 1 : pattern.sizeY()) / 2.0f;
    float midZ = pattern.sizeZ() / 2.0f;

    float lowX = axis == 0 ? 0 : midX;
    float lowY = axis == 1 ? (sliceOnly ? sliceY : 0) : midY;
    float lowZ = axis == 2 ? 0 : midZ;

    float highX = axis == 0 ? pattern.sizeX() : midX;
    float highY = axis == 1 ? (sliceOnly ? sliceY + 1 : pattern.sizeY()) : midY;
    float highZ = axis == 2 ? pattern.sizeZ() : midZ;

    float[] low  = project(pattern, sliceOnly, sliceY, lowX, lowY, lowZ);
    float[] high = project(pattern, sliceOnly, sliceY, highX, highY, highZ);

    return low[2] < high[2];
  }

  private void dot(
      GuiGraphicsExtractor graphics,
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      float x0,
      float y0,
      float z0,
      float x1,
      float y1,
      float z1
  )
  {
    float[] a = project(pattern, sliceOnly, sliceY, x0, y0, z0);
    float[] b = project(pattern, sliceOnly, sliceY, x1, y1, z1);

    GuiLines.dottedLine(graphics, a[0], a[1], b[0], b[1], GRID_COLOR);
  }

  /**
   * Outlines the hovered cell as a translucent cuboid, so it is obvious
   * which voxel a click will land on — the flat icons alone give no depth
   * cue about which cell the cursor is really over.
   */
  private void drawHoverCell(
      GuiGraphicsExtractor graphics,
      EditablePattern pattern,
      boolean sliceOnly,
      int sliceY,
      CellPos cell
  )
  {
    float[][] corners = new float[8][];

    for(int i = 0; i < 8; i++) {
      corners[i] = project(
          pattern,
          sliceOnly,
          sliceY,
          cell.x() + ((i & 1) == 0 ? 0 : 1),
          cell.y() + ((i & 2) == 0 ? 0 : 1),
          cell.z() + ((i & 4) == 0 ? 0 : 1)
      );
    }

    // The three faces whose outward normal points towards the camera.  A
    // cube shows at most three; picking them by depth avoids drawing the
    // hidden ones over the top.
    for(int[] face: BOX_FACES) {
      float depth = 0;
      for(int index: face) depth += corners[index][2];

      // Compare against the opposite face, which shares no corner.
      float other = 0;
      for(int i = 0; i < 8; i++) {
        boolean inFace = false;
        for(int index: face) inFace |= index == i;

        if(!inFace) other += corners[i][2];
      }

      if(depth < other) continue;

      GuiLines.fillQuad(
          graphics,
          corners[face[0]],
          corners[face[1]],
          corners[face[2]],
          corners[face[3]],
          HOVER_FILL_COLOR
      );

      GuiLines.quadOutline(
          graphics,
          corners[face[0]],
          corners[face[1]],
          corners[face[2]],
          corners[face[3]],
          HOVER_EDGE_THICKNESS,
          HOVER_EDGE_COLOR
      );
    }
  }

  private void drawGizmo(GuiGraphicsExtractor graphics)
  {
    int ox = left + 22;
    int oy = top + 25;

    float[] x = camera.projectDirection(1, 0, 0, GIZMO_LENGTH);
    float[] y = camera.projectDirection(0, 1, 0, GIZMO_LENGTH);
    float[] z = camera.projectDirection(0, 0, 1, GIZMO_LENGTH);

    GuiLines.line(graphics, ox, oy, ox + x[0], oy + x[1], GIZMO_THICKNESS, AXIS_X_COLOR);
    GuiLines.line(graphics, ox, oy, ox + y[0], oy + y[1], GIZMO_THICKNESS, AXIS_Y_COLOR);
    GuiLines.line(graphics, ox, oy, ox + z[0], oy + z[1], GIZMO_THICKNESS, AXIS_Z_COLOR);

    graphics.text(font, "X", Math.round(ox + x[0]), Math.round(oy + x[1] - 4), AXIS_X_LABEL);
    graphics.text(font, "Y", Math.round(ox + y[0]), Math.round(oy + y[1] - 4), AXIS_Y_LABEL);
    graphics.text(font, "Z", Math.round(ox + z[0]), Math.round(oy + z[1] - 4), AXIS_Z_LABEL);
  }
}
