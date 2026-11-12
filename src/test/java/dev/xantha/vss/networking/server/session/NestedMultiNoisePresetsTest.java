package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Nested multi-noise presets are resolved for the client; ReTerraForged's structure rules travel when RTF is there. */
class NestedMultiNoisePresetsTest {
    @BeforeAll static void bootstrap() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }

    private static JsonObject expanded(String preset) {
        var result = new JsonObject();
        result.addProperty("type", "minecraft:multi_noise");
        result.addProperty("resolved", preset);
        return result;
    }

    @Test
    void presetsAnywhereInTheSourceTreeAreResolvedAndNothingElseChanges() {
        var source = JsonParser.parseString("""
                {"type":"lithostitched:injector","base":{"type":"example:wrapper","inner":[
                  {"type":"minecraft:multi_noise","preset":"minecraft:overworld"},
                  {"type":"minecraft:fixed","biome":"minecraft:plains"}]},
                 "preset":"not:a_multi_noise_field"}
                """);
        var normalized = NestedMultiNoisePresets.normalize(source, NestedMultiNoisePresetsTest::expanded).getAsJsonObject();
        var inner = normalized.getAsJsonObject("base").getAsJsonArray("inner");
        assertFalse(inner.get(0).getAsJsonObject().has("preset"));
        assertEquals("minecraft:overworld", inner.get(0).getAsJsonObject().get("resolved").getAsString());
        assertEquals(source.getAsJsonObject().getAsJsonObject("base").getAsJsonArray("inner").get(1), inner.get(1));
        assertEquals("not:a_multi_noise_field", normalized.get("preset").getAsString(), "only multi_noise presets are resolved");
        assertNotSame(source, normalized);
        assertTrue(source.getAsJsonObject().getAsJsonObject("base").getAsJsonArray("inner").get(0).getAsJsonObject().has("preset"),
                "the input tree is not modified");
        assertThrows(IllegalStateException.class, () -> NestedMultiNoisePresets.normalize(
                JsonParser.parseString("{\"type\":\"minecraft:multi_noise\",\"preset\":{\"x\":1}}"), NestedMultiNoisePresetsTest::expanded));
    }

    @Test
    void reTerraForgedStructureRulesAreRequiredOnlyWhereTheirRegistryExists() throws Exception {
        var key = ResourceKey.<Integer>createRegistryKey(ResourceLocation.parse("reterraforged:worldgen/structure_rule"));
        var registry = new MappedRegistry<Integer>(key, Lifecycle.stable());
        registry.freeze();
        List<RegistryDataLoader.RegistryData<?>> codecs = List.of(new RegistryDataLoader.RegistryData<>(key, Codec.INT, false));
        var withRtf = new WorldgenRegistryDependencies(new RegistryAccess.ImmutableRegistryAccess(List.of(registry)), codecs);
        assertTrue(withRtf.requireIfPresent(key.location()));
        assertTrue(withRtf.encode().has("reterraforged:worldgen/structure_rule"));
        var without = new WorldgenRegistryDependencies(RegistryAccess.EMPTY, List.of());
        assertFalse(without.requireIfPresent(key.location()));
        assertTrue(without.encode().isEmpty());
        var density = new MappedRegistry<>(net.minecraft.core.registries.Registries.DENSITY_FUNCTION, Lifecycle.stable());
        density.freeze();
        var noise = new MappedRegistry<>(net.minecraft.core.registries.Registries.NOISE, Lifecycle.stable());
        noise.freeze();
        var server = new RegistryAccess.ImmutableRegistryAccess(List.of(registry, density, noise));
        var snapshot = WorldgenRegistrySnapshotTestBridge.encode(server, new WorldgenRegistryDependencies(server, codecs));
        assertTrue(snapshot.getAsJsonObject("custom_registries").has("reterraforged:worldgen/structure_rule"),
                "encodeRegistries asks for RTF's structure rules");
    }

}
