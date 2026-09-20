package cx.gid.minecraft.blockgrep.client.builder;

/**
 * A voxel coordinate within the pattern grid.
 */
record CellPos(int x, int y, int z) {
  CellPos clampTo(EditablePattern pattern)
  {
    return new CellPos(
        Math.clamp(x, 0, pattern.sizeX() - 1),
        Math.clamp(y, 0, pattern.sizeY() - 1),
        Math.clamp(z, 0, pattern.sizeZ() - 1)
    );
  }
}
