package dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal;

import com.mojang.serialization.MapCodec;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Test stand-in with the two accessors of Lithostitched 1.8.1's InjectorBiomeSource that VSS reads by name. */
public final class InjectorBiomeSource extends BiomeSource {
    private final BiomeSource direct;
    private final BiomeSource root;

    public InjectorBiomeSource(BiomeSource direct, BiomeSource root) {
        this.direct = direct;
        this.root = root;
    }

    public BiomeSource directDelegate() { return direct; }

    public BiomeSource rootDelegate() { return root; }

    @Override protected MapCodec<? extends BiomeSource> codec() { throw new UnsupportedOperationException(); }

    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return direct.possibleBiomes().stream(); }

    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) { return direct.getNoiseBiome(x, y, z, sampler); }
}
