package dev.xantha.vss.testutil;

import com.google.gson.JsonObject;
import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

public final class TerrainPredictionFixtures {
    private static RegistryAccess access;
    private TerrainPredictionFixtures() { }
    public static void bootstrap() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build/tmp/terrain-capability-tests"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    public static synchronized RegistryAccess access() {
        if (access == null) {
            var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
            var values = new ArrayList<Registry<?>>();
            lookup.listRegistries().forEach(key -> values.add(copy(lookup.lookupOrThrow(key))));
            access = new RegistryAccess.ImmutableRegistryAccess(values);
        }
        return access;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Registry<T> copy(HolderLookup.RegistryLookup<T> source) {
        var result = new MappedRegistry<T>((net.minecraft.resources.ResourceKey) source.key(), Lifecycle.stable());
        source.listElements().forEach(holder -> result.register(holder.key(), holder.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
        return result.freeze();
    }
    public static NoiseBasedChunkGenerator noise() {
        return new NoiseBasedChunkGenerator(new FixedBiomeSource(access().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS)),
                access().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));
    }
    public static JsonObject emptyRegistries() {
        var result = new JsonObject();
        for (String name : List.of("noises", "density_functions", "biomes", "configured_features", "placed_features"))
            result.add(name, new JsonObject());
        return result;
    }
}
