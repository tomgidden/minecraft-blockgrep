package cx.gid.minecraft.blockgrep.client.config;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Reads and writes an {@code 0xAARRGGBB} colour as a hex string.
 *
 * <p>Colours were previously stored as the signed integer Java happens to hold
 * them in, so an ordinary cyan came out as {@code -18612} -- a number that
 * cannot be read, cannot be typed from memory, and gives no clue that it is a
 * colour at all. They are now written as {@code "#ffb7bf4c"}, which can be
 * pasted straight into any colour picker.
 *
 * <p>Both forms are accepted on load, so a config written by an older version
 * keeps working and is quietly rewritten as hex the next time the file is
 * saved. There is no migration step and no version field: the reader simply
 * takes whichever it is given.
 *
 * <h2>Accepted forms</h2>
 * <ul>
 *   <li>{@code "#aarrggbb"} or {@code "aarrggbb"} -- full colour with alpha</li>
 *   <li>{@code "#rrggbb"} or {@code "rrggbb"} -- assumed fully opaque</li>
 *   <li>a bare JSON number -- the historical signed integer</li>
 * </ul>
 *
 * <p>Written with a leading {@code #} and in lower case, always eight digits.
 * Eight rather than six because alpha is load-bearing here: the fill colour is
 * deliberately translucent, and dropping its alpha on save would turn every
 * fill opaque.
 */
public final class HexColor extends TypeAdapter<Integer> {

    @Override
    public void write(JsonWriter out, Integer value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        out.value(String.format("#%08x", value));
    }

    @Override
    public Integer read(JsonReader in) throws IOException {
        JsonToken token = in.peek();

        if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
        }

        // The historical representation. Kept readable indefinitely: a config
        // file is a user's own data, and breaking it to tidy up a format would
        // be a poor trade.
        if (token == JsonToken.NUMBER) {
            return in.nextInt();
        }

        String raw = in.nextString().trim();
        return parse(raw);
    }

    /**
     * Parses a hex colour, or throws {@link IOException} so Gson reports it as
     * a parse failure and the config falls back to defaults rather than loading
     * a pattern with a nonsense colour.
     */
    static int parse(String raw) throws IOException {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;

        if (hex.length() != 6 && hex.length() != 8) {
            throw new IOException("colour '" + raw + "' must be 6 or 8 hex digits");
        }

        final long value;
        try {
            value = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("colour '" + raw + "' is not hexadecimal");
        }

        // Six digits means no alpha was given. Opaque is the only sensible
        // reading: a colour written without an alpha channel is not a request
        // for an invisible one.
        if (hex.length() == 6) {
            return (int) (0xFF000000L | value);
        }

        // Long.parseLong is used rather than Integer.parseInt because eight
        // hex digits with the top bit set exceed Integer.MAX_VALUE and would
        // otherwise throw; the cast then lands it back in the signed int the
        // renderer wants.
        return (int) value;
    }
}
