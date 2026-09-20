### Minecraft 26.3

Updated for Minecraft 26.3. This build targets 26.3 only — use 26.0.0 for 26.2.

Minecraft moved from GLFW to SDL for input in 26.3, so the keybinds are
rebuilt on the new API. Both remain unbound by default and appear in the
controls screen under **Block Grep**.

### Colours are now hex

Pattern colours are written to `config/blockgrep.json` as hex strings
(`"#ff00e5ff"`) instead of the signed integers they used to be — `-18612` was
not a readable way to store a colour.

Existing config files load unchanged and are rewritten as hex the first time
the settings are saved; nothing needs doing. Colours can be given as
`#aarrggbb`, or as `#rrggbb` to let the alpha default — opaque for an outline,
faint for a fill.

### Smaller things

- The settings screen reopens on the pattern you were last editing, rather
  than jumping back to the top of the list.
- The default "Diamond ore pair" pattern now matches deepslate diamond ore too,
  in any orientation.
- `./gradlew distclean` removes all build output, caches and IDE artifacts —
  for anyone building from source.
