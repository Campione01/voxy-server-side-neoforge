package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.Function;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * A client connection has no multi_noise_biome_source_parameter_list registry, so a {"preset": ...} multi-noise source
 * cannot be decoded there. encodeBiomeSource resolves a preset at the top of the encoded source; wrapper sources (for
 * example the root of ReTerraForged's or Lithostitched's wrapped Overworld) can carry one deeper in the tree.
 */
final class NestedMultiNoisePresets {
    private NestedMultiNoisePresets() { }

    static JsonElement normalize(JsonElement source, RegistryAccess access, RegistryOps<JsonElement> ops) {
        return normalize(source, preset -> {
            var id = ResourceLocation.parse(preset);
            var parameters = access.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).get(id);
            if (parameters == null) throw new IllegalStateException("Missing native multi-noise biome-source preset " + id);
            JsonObject encoded = MultiNoiseBiomeSource.DIRECT_CODEC.codec().encodeStart(ops, parameters.parameters())
                    .getOrThrow().getAsJsonObject();
            encoded.addProperty("type", "minecraft:multi_noise");
            return encoded;
        });
    }

    static JsonElement normalize(JsonElement source, Function<String, JsonObject> expandPreset) {
        if (source.isJsonObject()) {
            JsonObject original = source.getAsJsonObject();
            JsonObject result = new JsonObject();
            for (var entry : original.entrySet()) result.add(entry.getKey(), normalize(entry.getValue(), expandPreset));
            if (original.has("type") && original.get("type").isJsonPrimitive()
                    && original.get("type").getAsString().equals("minecraft:multi_noise") && original.has("preset")) {
                if (!original.get("preset").isJsonPrimitive() || !original.getAsJsonPrimitive("preset").isString()) {
                    throw new IllegalStateException("Native multi-noise preset must be a string");
                }
                JsonObject encoded = expandPreset.apply(original.get("preset").getAsString());
                result.remove("preset");
                for (var entry : encoded.entrySet()) result.add(entry.getKey(), entry.getValue().deepCopy());
            }
            return result;
        }
        if (source.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : source.getAsJsonArray()) result.add(normalize(item, expandPreset));
            return result;
        }
        return source.deepCopy();
    }
}
