package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The TerraBlender region snapshot is taken from the root of Lithostitched's injector layers, and only from there. */
class TerraBlenderRegionSourceTest {
    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    private static MultiNoiseBiomeSource overworld() {
        var lists = TerrainPredictionFixtures.access().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        return MultiNoiseBiomeSource.createFromPreset(lists.getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
    }

    @Test
    void injectorLayersOverAMultiNoiseRootAreUnwrappedAndAnythingElseIsLeftAsItIs() {
        var root = overworld();
        assertSame(root, TerraBlenderRegionSource.regionSource(root));
        var inner = new InjectorBiomeSource(root, root);
        var outer = new InjectorBiomeSource(inner, root);
        assertSame(root, TerraBlenderRegionSource.underInjectors(outer));
        assertSame(root, TerraBlenderRegionSource.regionSource(outer));
        BiomeSource fixed = new FixedBiomeSource(TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
        assertSame(fixed, TerraBlenderRegionSource.regionSource(fixed));
        assertNull(TerraBlenderRegionSource.underInjectors(fixed));
        var foreignLayer = new InjectorBiomeSource(fixed, root);
        assertNull(TerraBlenderRegionSource.underInjectors(foreignLayer), "a non-injector layer between the injectors and the root");
        assertSame(foreignLayer, TerraBlenderRegionSource.regionSource(foreignLayer));
        var fixedRoot = new InjectorBiomeSource(fixed, fixed);
        assertSame(fixedRoot, TerraBlenderRegionSource.regionSource(fixedRoot), "only a MultiNoiseBiomeSource root carries TerraBlender regions");
    }

    @Test
    void unwrappedSnapshotsAreMarkedAndNoSurfaceMarkerWithoutBiolith() {
        var section = new JsonObject();
        var regions = new JsonArray();
        var base = new JsonObject();
        base.addProperty("name", "minecraft:overworld");
        regions.add(base);
        section.add("regions", regions);
        var root = overworld();
        TerraBlenderRegionSource.markUnwrapped(section, root);
        assertFalse(section.has(TerraBlenderRegionSource.SOURCE_MARKER), "a plain MultiNoiseBiomeSource is not unwrapped");
        TerraBlenderRegionSource.markUnwrapped(section, new InjectorBiomeSource(root, root));
        assertEquals(TerraBlenderRegionSource.UNWRAPPED, section.get(TerraBlenderRegionSource.SOURCE_MARKER).getAsString());
        TerraBlenderRegionSource.markSurfaceRoute(section, root);
        assertFalse(section.has(TerraBlenderRegionSource.SURFACE_MARKER), "Biolith is not installed");
    }
}
