package cx.gid.minecraft.blockgrep.client.config;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/**
 * Reads and writes an {@code 0xAARRGGBB} color as a hex string.
 *
 * <p>Colors were previously stored as the signed integer Java happens to hold
 * them in, so an ordinary cyan came out as {@code -18612} -- a number that
 * cannot be read, cannot be typed from memory, and gives no clue that it is a
 * color at all. They are now written as {@code "#ffb7bf4c"}, which can be
 * pasted straight into any color picker.
 *
 * <p>Both forms are accepted on load, so a config written by an older version
 * keeps working and is quietly rewritten as hex the next time the file is
 * saved. There is no migration step and no version field: the reader simply
 * takes whichever it is given.
 *
 * <h2>Accepted forms</h2>
 * <ul>
 *   <li>{@code "#aarrggbb"} or {@code "aarrggbb"} -- full color with alpha</li>
 *   <li>{@code "#rrggbb"} or {@code "rrggbb"} -- alpha supplied by the field:
 *       opaque for an outline, faint for a fill</li>
 *   <li>a bare JSON number -- the historical signed integer</li>
 * </ul>
 *
 * <p>Written with a leading {@code #} and in lower case, always eight digits.
 * Eight rather than six because alpha is load-bearing here: the fill color is
 * deliberately translucent, and dropping its alpha on save would turn every
 * fill opaque.
 *
 * <h2>Why two subclasses</h2>
 * A six-digit color has no alpha, and the sensible default differs by field:
 * an outline written {@code "#ff00ff"} means a solid magenta line, whereas a
 * fill written the same way means a faint magenta wash -- the same convention
 * {@link SavedPattern#SavedPattern(String, String, Symmetry, int, boolean)}
 * already applies when a pattern is created from a bare hue. Gson picks an
 * adapter per field, so the difference is carried by {@link Stroke} and
 * {@link Fill} rather than by anything at the call site.
 */
public abstract class HexColor extends TypeAdapter<Integer>
{
  /**
   * Adapter for an outline color: a six-digit value is opaque.
   */
  public static final class Stroke extends HexColor
  {
    public Stroke()
    {
      super(SavedPattern.STROKE_ALPHA);
    }
  }

  /**
   * Adapter for a fill color: a six-digit value takes the faint fill alpha.
   */
  public static final class Fill extends HexColor
  {
    public Fill()
    {
      super(SavedPattern.FILL_ALPHA);
    }
  }

  /**
   * Alpha applied when the value read has none, as {@code 0xAA000000}.
   */
  private final int defaultAlpha;

  private HexColor(int defaultAlpha)
  {
    this.defaultAlpha = defaultAlpha;
  }

  @Override
  public void write(JsonWriter out, Integer value) throws IOException
  {
    if(value == null) {
      out.nullValue();
      return;
    }
    out.value(String.format("#%08x", value));
  }

  @Override
  public Integer read(JsonReader in) throws IOException
  {
    JsonToken token = in.peek();

    if(token == JsonToken.NULL) {
      in.nextNull();
      return null;
    }

    // The historical representation. Kept readable indefinitely: a config
    // file is a user's own data, and breaking it to tidy up a format would
    // be a poor trade.
    if(token == JsonToken.NUMBER) {
      return in.nextInt();
    }

    return parse(in.nextString().trim(), defaultAlpha);
  }

  /**
   * Parses a hex color, or throws {@link IOException} so Gson reports it as
   * a parse failure and the config falls back to defaults rather than loading
   * a pattern with a nonsense color.
   *
   * @param defaultAlpha alpha to apply to a six-digit value, as
   *                     {@code 0xAA000000}
   */
  static int parse(String raw, int defaultAlpha) throws IOException
  {
    String hex = raw.startsWith("#") ? raw.substring(1) : raw;

    if(hex.length() != 6 && hex.length() != 8) {
      throw new IOException("color '" + raw + "' must be 6 or 8 hex digits");
    }

    final long value;
    try {
      value = Long.parseLong(hex, 16);
    }
    catch(NumberFormatException e) {
      throw new IOException("color '" + raw + "' is not hexadecimal");
    }

    if(hex.length() == 6) {
      return defaultAlpha | (int) value;
    }

    // Long.parseLong is used rather than Integer.parseInt because eight
    // hex digits with the top bit set exceed Integer.MAX_VALUE and would
    // otherwise throw; the cast then lands it back in the signed int the
    // renderer wants.
    return (int) value;
  }
}
