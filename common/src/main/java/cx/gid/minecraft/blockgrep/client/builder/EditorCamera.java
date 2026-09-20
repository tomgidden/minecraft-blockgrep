package cx.gid.minecraft.blockgrep.client.builder;

/**
 * Orbit camera for the voxel viewport.
 *
 * The projection is a mild perspective rather than a true orthographic one:
 * depth is accumulated during rotation anyway, so dividing by it costs
 * nothing and gives the grid enough convergence to read as a solid volume.
 * {@link #PERSPECTIVE} is deliberately weak — a strong division would make
 * the near face of a 16-cube dwarf the far face and wreck the palette-sized
 * icons.
 */
final class EditorCamera
{
  /**
   * Default orientation; also what the center button restores.
   */
  private static final float DEFAULT_YAW   = -0.72f;
  private static final float DEFAULT_PITCH = 0.52f;
  private static final float DEFAULT_ZOOM  = 1.0f;

  private static final float MIN_PITCH = -1.25f;
  private static final float MAX_PITCH = 1.25f;
  private static final float MIN_ZOOM  = 0.45f;
  private static final float MAX_ZOOM  = 2.4f;

  private static final float ORBIT_YAW_RATE   = 0.018f;
  private static final float ORBIT_PITCH_RATE = 0.015f;
  private static final float ZOOM_RATE        = 1.12f;

  /**
   * Strength of the perspective divide, as the camera's distance in cells.
   * Larger is flatter.
   *
   * Chosen so the effect stays mild across the whole legal size range: a full
   * 16-cube spans roughly 1.9x from its nearest cell to its farthest, a
   * typical 8-cube about 1.35x, and a 4-cube is near enough flat.  Pulling
   * the camera closer (~14) reaches 3.3x on a 16-cube, which reads as a
   * fisheye and saturates the clamps below; pushing it past ~80 is
   * indistinguishable from the orthographic view this replaced.
   */
  private static final float PERSPECTIVE = 40.0f;

  /**
   * Clamps on the perspective scale, so a cell can never invert or grow
   * without bound.  At {@link #PERSPECTIVE} these are a backstop rather than
   * a working limit — nothing in the legal size range reaches them.
   */
  private static final float MIN_PERSPECTIVE_SCALE = 0.55f;
  private static final float MAX_PERSPECTIVE_SCALE = 1.8f;

  private float yaw   = DEFAULT_YAW;
  private float pitch = DEFAULT_PITCH;
  private float zoom  = DEFAULT_ZOOM;

  float zoom()
  {
    return zoom;
  }

  void reset()
  {
    yaw   = DEFAULT_YAW;
    pitch = DEFAULT_PITCH;
    zoom  = DEFAULT_ZOOM;
  }

  void orbit(double dragX, double dragY)
  {
    yaw += (float) dragX * ORBIT_YAW_RATE;
    pitch = Math.clamp(
        pitch - (float) dragY * ORBIT_PITCH_RATE,
        MIN_PITCH,
        MAX_PITCH
    );
  }

  void zoomBy(double wheelDelta)
  {
    zoom = Math.clamp(
        (float) (zoom * Math.pow(ZOOM_RATE, wheelDelta)),
        MIN_ZOOM,
        MAX_ZOOM
    );
  }

  /**
   * Rotates a model-space point and applies the perspective divide.
   *
   * The point is expected to be pre-centered on the pattern's midpoint, so
   * that the divisor is symmetric about the origin and the volume neither
   * drifts nor shears as it turns.
   *
   * @return {@code {x, y, depth, scale}} in screen units relative to the
   *         viewport center; {@code scale} is the perspective factor already
   *         applied, exposed so callers can size icons and pick radii to
   *         match.
   */
  float[] project(float centeredX, float centeredY, float centeredZ, float spacing)
  {
    float cy = (float) Math.cos(yaw);
    float sy = (float) Math.sin(yaw);
    float cp = (float) Math.cos(pitch);
    float sp = (float) Math.sin(pitch);

    float rx    = cy * centeredX - sy * centeredZ;
    float rz    = sy * centeredX + cy * centeredZ;
    float ry    = cp * centeredY - sp * rz;
    float depth = sp * centeredY + cp * rz;

    float scale = perspectiveScale(depth);

    return new float[] {
        rx * spacing * scale,
        -ry * spacing * scale,
        depth,
        scale
    };
  }

  /**
   * Rotates a direction without perspective.  Used for the axis gizmo, which
   * is a fixed-size overlay rather than part of the scene.
   */
  float[] projectDirection(float x, float y, float z, float length)
  {
    float cy = (float) Math.cos(yaw);
    float sy = (float) Math.sin(yaw);
    float cp = (float) Math.cos(pitch);
    float sp = (float) Math.sin(pitch);

    float rx = cy * x - sy * z;
    float rz = sy * x + cy * z;
    float ry = cp * y - sp * rz;

    return new float[] {rx * length, -ry * length};
  }

  /**
   * Perspective factor for a given depth.  Positive depth is towards the
   * camera, so it enlarges.
   */
  float perspectiveScale(float depth)
  {
    return Math.clamp(
        PERSPECTIVE / (PERSPECTIVE - depth),
        MIN_PERSPECTIVE_SCALE,
        MAX_PERSPECTIVE_SCALE
    );
  }
}
