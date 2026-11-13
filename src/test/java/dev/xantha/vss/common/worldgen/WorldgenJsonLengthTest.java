package dev.xantha.vss.common.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** length counts exactly the UTF-8 bytes that bytes returns, without building them. */
class WorldgenJsonLengthTest {
    @Test
    void lengthIsTheByteLengthOfTheCanonicalSerialisation() {
        for (String text : List.of("plain", "\u00e9 \u00fc \u00df", "\u4e2d\u6587 \u533a\u57df", "\uD83C\uDF0D pair", "lone \uD800 high", "lone \uDC00 low",
                "ends high \uD800", "\uD800\uD800\uDC00 high then pair", "quote \" backslash \\ newline \n tab \t \u2028 \u0001 <&>")) {
            JsonObject root = new JsonObject();
            root.addProperty("text", text);
            root.addProperty("float", 0.1f);
            root.addProperty("double", 1.0E-4);
            root.addProperty("long", 1L << 40);
            root.addProperty("flag", true);
            root.add("none", JsonNull.INSTANCE);
            JsonArray array = new JsonArray();
            array.add(text);
            array.add(-3);
            array.add(new JsonObject());
            root.add("array", array);
            JsonObject nested = new JsonObject();
            nested.addProperty("z " + text, text);
            nested.add("a", new JsonArray());
            root.add("nested", nested);
            assertEquals(WorldgenJson.bytes(root).length, WorldgenJson.length(root), text);
            assertEquals(WorldgenJson.bytes(array).length, WorldgenJson.length(array), text);
        }
    }
}
