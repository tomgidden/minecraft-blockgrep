### Minecraft 26.3 compatibility

Minecraft 26.3 has some significant changes, including a switch from GLFW to SDL,
and some substantial internal API differences. While it would be possible to
backport to 26.1.2 and 26.2, it'd be messy and probably unreliable, so this
release is only compatible with 26.3 and later.

### 3D pattern builder

Patterns can now be drawn instead of typed. **Edit pattern…** in the settings
screen opens an orbitable voxel editor: paint cells from the full block and tag
palettes, and the Block Grep source is generated as you go.

Left-click paints, right-click erases to an **Any Block** wildcard, middle-click
picks a cell's rule, drag orbits and the wheel zooms. On a trackpad or
single-button mouse, shift-click stands in for right-click and ctrl-click
(cmd-click on macOS) for middle-click.

Set the pattern size with the **Grid** fields, or with the `+`/`−` buttons that
float beside the volume and follow it as you orbit. The `+` tool builds
alternatives and `!` negates a choice; Y-slice controls, undo/redo and
capture-from-world are in the toolbar.

The 3D Pattern Editor was contributed by [@isavaible](https://github.com/isavaible)
