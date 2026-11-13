package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.common.worldgen.DensityFunctionSnapshot;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import dev.xantha.vss.common.worldgen.FreeTerraForgedRuntime;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import dev.xantha.vss.common.worldgen.WorldgenSectionCache;
import dev.xantha.vss.common.worldgen.GeneratorTerrainCapabilities;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import dev.xantha.vss.api.worldgen.TerrainSnapshots;
import dev.xantha.vss.api.worldgen.TerrainSnapshotContext;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Codec snapshot equivalent to the worldgen dump, owned by VSS. */
final class WorldgenCodecSnapshot {
    /**
     * Stays clear of the hard caps in {@code WorldgenProfileS2CPayload} so that
     * one optional section cannot push the whole generator payload over them.
     * Exceeding those caps throws while the profile is built, which costs the
     * dimension's entire prediction rather than a single backend.
     */
    private static final int GENERATOR_BYTES_HEADROOM = 1_500_000;
    private static final int GENERATOR_RAW_BYTES_HEADROOM = 7_500_000;

    private WorldgenCodecSnapshot() {
    }

    static Encoded encodeRegistries(RegistryAccess access) {
        return encodeRegistries(access, null);
    }

    static Encoded encodeRegistries(RegistryAccess access, net.minecraft.server.packs.resources.ResourceManager resources) {
        return encodeRegistries(access, resources, new DensityFunctionReferences(), new WorldgenRegistryDependencies(access));
    }

    static Encoded encodeRegistries(RegistryAccess access, net.minecraft.server.packs.resources.ResourceManager resources,
                                    DensityFunctionReferences references, WorldgenRegistryDependencies dependencies) {
        RegistryOps<JsonElement> ops = dependencies.ops();
        // ReTerraForged's structure rules filter structure placement; a client predicting its structures needs them.
        dependencies.requireIfPresent(ResourceLocation.parse("reterraforged:worldgen/structure_rule"));
        JsonObject root = new JsonObject();
        root.add("density_functions", WorldgenSectionCache.densities(access, dependencies, references, WorldgenCodecSnapshot::encodeDensities));
        for (var entry : references.definitions().entrySet()) {
            root.getAsJsonObject("density_functions").add(entry.getKey(), entry.getValue());
        }
        root.add("noises", WorldgenSectionCache.section(
                access, dependencies, Registries.NOISE, NormalNoise.NoiseParameters.DIRECT_CODEC, WorldgenCodecSnapshot::encodeRegistry));
        // Keep the same worldgen inputs as the WorldgenDump.  The
        // client can then run feature/structure probes without asking the
        // server for another metadata round trip.
        root.add("biomes", optional(access, dependencies, Registries.BIOME, Biome.DIRECT_CODEC));
        root.add("configured_carvers", optional(
                access, dependencies, Registries.CONFIGURED_CARVER, ConfiguredWorldCarver.DIRECT_CODEC));
        root.add("placed_features", optional(
                access, dependencies, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC));
        root.add("configured_features", optional(
                access, dependencies, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC));
        JsonObject structures = optional(access, dependencies, Registries.STRUCTURE, Structure.DIRECT_CODEC);
        root.add("structures", structures);
        root.add("structure_sets", filterStructureSets(structures, optional(
                access, dependencies, Registries.STRUCTURE_SET, StructureSet.DIRECT_CODEC)));
        JsonObject structurifyPolicy = StructurifyPredictionSnapshot.capture();
        if (structurifyPolicy != null) root.add("vss_structure_policy", structurifyPolicy);
        root.add("processor_lists", optional(access, dependencies, Registries.PROCESSOR_LIST,
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.DIRECT_CODEC));
        root.add("template_pools", optional(access, dependencies, Registries.TEMPLATE_POOL,
                net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool.DIRECT_CODEC));
        if (resources != null) root.add("structure_templates", encodeTemplates(resources));
        root.add("custom_registries", dependencies.encode());
        root.add("registry_tags", dependencies.encodeTags());
        if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging) {
            var parts = new java.util.ArrayList<String>();
            for (String key : root.keySet().stream().sorted().toList())
                parts.add(key + "=" + snapshotHash(root.get(key)));
            VSSLogger.debug("VSS worldgen registry sections: " + String.join(",", parts));
            logEntryHashes("configured_features", root.getAsJsonObject("configured_features"));
            logEntryHashes("processor_lists", root.getAsJsonObject("processor_lists"));
        }
        return compress(root);
    }

    /** Registry sections between two builds of one server come from WorldgenSectionCache. */
    private static <T> JsonObject optional(RegistryAccess access, WorldgenRegistryDependencies dependencies,
                                           ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
        return WorldgenSectionCache.section(access, dependencies, key, codec, WorldgenCodecSnapshot::encodeOptional);
    }

    private static void logEntryHashes(String section, JsonObject entries) {
        if (entries == null) return;
        var line = new StringBuilder("VSS worldgen registry entries ").append(section).append(':');
        int count = 0;
        for (String id : entries.keySet().stream().sorted().toList()) {
            if (count++ % 32 == 0 && count > 1) {
                VSSLogger.debug(line.toString());
                line = new StringBuilder("VSS worldgen registry entries ").append(section).append(':');
            }
            line.append(' ').append(id).append('=').append(snapshotHash(entries.get(id)));
        }
        if (count > 0) VSSLogger.debug(line.toString());
    }

    private static String snapshotHash(JsonElement value) {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(dev.xantha.vss.common.worldgen.WorldgenJson.bytes(value));
            return java.util.HexFormat.of().formatHex(hash, 0, 8);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static JsonObject encodeTemplates(net.minecraft.server.packs.resources.ResourceManager resources) {
        return WorldgenTemplateCatalog.snapshot(resources).manifest();
    }

    static Encoded encodeGenerator(ChunkGenerator generator, RegistryAccess access, DensityFunctionReferences references,
                                   WorldgenRegistryDependencies dependencies) {
        return encodeGenerator(generator, access, references, dependencies, null);
    }

    static Encoded encodeGenerator(ChunkGenerator generator, RegistryAccess access, DensityFunctionReferences references,
                                   WorldgenRegistryDependencies dependencies, String terrablenderRegionType) {
        var extension = TerrainSnapshots.capture(new TerrainSnapshotContext(generator, access, dependencies));
        if (extension.isPresent() && extension.get().plan().kind() == TerrainPredictionPlan.Kind.NOISE_GRAPH)
            return encodeOtherGenerator(generator, access, dependencies, extension);
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            return encodeOtherGenerator(generator, access, dependencies, extension);
        }
        try {
            return encodeNoiseGenerator(generator, noise, access, references, dependencies, terrablenderRegionType, extension);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException | LinkageError failure) {
            if (extension.isEmpty()) throw failure;
            VSSLogger.warn("VSS optional noise context unavailable; retaining the explicit LOD source", failure);
            return encodeOtherGenerator(generator, access, dependencies, extension);
        }
    }

    private static Encoded encodeNoiseGenerator(ChunkGenerator generator, NoiseBasedChunkGenerator noise,
            RegistryAccess access, DensityFunctionReferences references, WorldgenRegistryDependencies dependencies,
            String terrablenderRegionType, java.util.Optional<dev.xantha.vss.api.worldgen.TerrainSnapshot> extension) {
        boolean freeTerraRuntime = FreeTerraForgedRuntime.supports(generator);
        boolean inheritedNoise = freeTerraRuntime || GeneratorTerrainCapabilities.inheritsImplementation(
                generator.getClass(), NoiseBasedChunkGenerator.class);
        RegistryOps<JsonElement> ops = dependencies.ops();
        JsonObject root = new JsonObject();
        NoiseGeneratorSettings settings = noise.generatorSettings().value();
        if (requiresAppliedDensityGraph()) settings = DensityFunctionSnapshot.applied(settings);
        JsonObject encodedSettings = NoiseGeneratorSettings.DIRECT_CODEC
                .encodeStart(ops, settings)
                .getOrThrow().getAsJsonObject();
        root.add("settings", requiresAppliedDensityGraph() ? references.settings(encodedSettings) : encodedSettings);
        if (!inheritedNoise) {
            root.addProperty("vss_unsupported_reason", "Custom noise generator requires a registered backend: "
                    + generator.getClass().getName());
        }
        BiomeSource snapshotSource = freeTerraRuntime
                ? FreeTerraForgedRuntime.acquisitionSource(generator) : noise.getBiomeSource();
        root.add("biome_source", encodeBiomeSource(snapshotSource, access, ops));
        if (freeTerraRuntime) {
            root.add(FreeTerraForgedRuntime.FIELD, FreeTerraForgedRuntime.snapshot(generator));
            dependencies.require(ResourceLocation.parse("freeterraforged:worldgen/biome_modifier"));
            dependencies.require(ResourceLocation.parse("freeterraforged:worldgen/structure_rule"));
        }
        // TerraBlender's positional region tree/surface-rule context is not
        // serialized by the vanilla codecs. A plain reconstructed generator
        // would draw the wrong biomes even with matching client mod jars.
        var mods = net.neoforged.fml.ModList.get();
        JsonObject noiseSeeds = noiseSeedAliases(access.registryOrThrow(Registries.NOISE).keySet(),
                mods != null && mods.isLoaded("tectonic"));
        if (!noiseSeeds.isEmpty()) root.add("noise_seed_aliases", noiseSeeds);
        snapshotFreeTerraForged(root, access, dependencies, id -> mods != null && mods.isLoaded(id));
        if (root.has("vss_freeterraforged") && mods != null) {
            for (var variant : FreeTerraForgedVariant.values()) mods.getModContainerById(variant.namespace)
                    .ifPresent(mod -> root.addProperty("vss_freeterraforged_release", mod.getModInfo().getVersion().toString()));
        }
        BiomeSource regionSource = mods != null && mods.isLoaded("terrablender") && terrablenderRegionType != null
                ? TerraBlenderRegionSource.regionSource(noise.getBiomeSource()) : noise.getBiomeSource();
        boolean unwrappedRegions = regionSource != noise.getBiomeSource();
        if (mods != null && mods.isLoaded("terrablender") && terrablenderRegionType != null
                && regionSource instanceof MultiNoiseBiomeSource) {
            // TerraBlender's positional region grid and per-region climate
            // points live outside the vanilla codecs. Snapshot them so the
            // client backend can replay the routing; untagged dimensions are
            // never initialized by TerraBlender and keep vanilla behavior.
            JsonObject regions = TerraBlenderRegionSnapshot.capture(terrablenderRegionType, access);
            if (regions != null && inheritedNoise) {
                if (unwrappedRegions) TerraBlenderRegionSource.markUnwrapped(regions, noise.getBiomeSource());
                TerraBlenderRegionSource.markSurfaceRoute(regions, regionSource);
                root.add("vss_terrablender", regions);
            } else if (regions == null && unwrappedRegions) {
                // Before the injector root was read this generator had no region snapshot and no refusal.
                VSSLogger.warn("VSS TerraBlender region capture returned nothing for the root of Lithostitched's injector; the wrapped source is sent without regions");
            } else if (regions == null) {
                root.addProperty("vss_unsupported_reason",
                        "TerraBlender positional regions require a registered prediction backend");
            }
        }
        if (mods != null && mods.isLoaded("blueprint")) {
            // Blueprint's ModdedBiomeSource codec carries only the wrapped
            // source: the slice array, its size and both positional seeds are
            // constructor state and never reach the vanilla codecs. Snapshot
            // them so the client backend can replay getSlice instead of
            // behaving as a plain forwarder for a source that routes most of
            // the world through slices.
            JsonObject blueprint = BlueprintBiomeSnapshot.capture(noise.getBiomeSource(), access, ops,
                    mods.isLoaded("terrablender") ? terrablenderRegionType : null);
            if (blueprint != null) {
                if (inheritedNoise) {
                    root.add("vss_blueprint", blueprint);
                } else {
                    root.addProperty("vss_unsupported_reason",
                            "Blueprint biome slices require a registered prediction backend");
                }
            }
        }
        TerrainPredictionPlan plan = root.has("vss_unsupported_reason")
                ? TerrainPredictionPlan.unavailable(root.get("vss_unsupported_reason").getAsString())
                : TerrainPredictionPlan.noise("vss:noise_graph", 1, true);
        plan = applyExtension(root, plan, extension);
        root.add(TerrainPredictionPlan.FIELD, plan.encode());
        if (generator.getClass() != NoiseBasedChunkGenerator.class) snapshotActualGenerator(root, generator, access, dependencies);
        Encoded encoded = defer(root);
        if (unwrappedRegions && root.get("vss_terrablender") instanceof JsonObject regions
                && regions.has(TerraBlenderRegionSource.SOURCE_MARKER) && exceedsHeadroom(encoded)) {
            // The unwrapped region section is the optional part: without it the snapshot is what it was before.
            root.remove("vss_terrablender");
            VSSLogger.warn("VSS TerraBlender region section from Lithostitched's injector root exceeds the generator snapshot budget; sent without it");
            encoded = defer(root);
        }
        if (root.has("vss_generator") && exceedsHeadroom(encoded)) {
            root.remove("vss_generator");
            root.addProperty("vss_generator_error", "Optional generator codec exceeds snapshot budget");
            encoded = defer(root);
        }
        // The generator payload has hard caps (see WorldgenProfileS2CPayload):
        // exceeding them throws while the profile is built, which would cost
        // the whole dimension's prediction rather than just this backend. A
        // Blueprint slice table can be megabytes on its own, so if the payload
        // no longer fits with headroom, give up the optional section and keep
        // the core snapshot intact, but reject prediction: the wrapper codec
        // alone silently discards Blueprint and nested TerraBlender routing.
        if (root.has("vss_blueprint") && exceedsHeadroom(encoded)) {
            root.remove("vss_blueprint");
            root.addProperty("vss_unsupported_reason", "Blueprint biome slice snapshot exceeds payload budget");
            root.add(TerrainPredictionPlan.FIELD, TerrainPredictionPlan.unavailable(
                    "Blueprint biome slice snapshot exceeds payload budget").encode());
            VSSLogger.warn("VSS dropped the Blueprint slice snapshot to stay inside the generator"
                    + " payload budget (" + encoded.bytes().length + " B compressed, "
                    + encoded.rawSize() + " B raw); prediction unavailable for this dimension");
            encoded = defer(root);
        }
        return exceedsHeadroom(encoded) ? unavailableSnapshot("Terrain projection exceeds snapshot budget") : encoded;
    }

    private static TerrainPredictionPlan applyExtension(JsonObject root, TerrainPredictionPlan fallback,
            java.util.Optional<dev.xantha.vss.api.worldgen.TerrainSnapshot> extension) {
        if (extension.isEmpty()) return fallback;
        var snapshot = extension.get();
        if (snapshot.plan().kind() == TerrainPredictionPlan.Kind.NOISE_GRAPH) {
            root.entrySet().clear();
            snapshot.parameters().entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue()));
        } else root.add("vss_lod_parameters", snapshot.parameters());
        root.remove("vss_unsupported_reason");
        return snapshot.plan();
    }

    private static Encoded encodeOtherGenerator(ChunkGenerator generator, RegistryAccess access,
            WorldgenRegistryDependencies dependencies,
            java.util.Optional<dev.xantha.vss.api.worldgen.TerrainSnapshot> extension) {
        var root = new JsonObject();
        TerrainPredictionPlan plan = TerrainPredictionPlan.unavailable(
                "Generator needs a noise projection or LOD source: " + generator.getClass().getName());
        if (extension.isEmpty() && generator instanceof net.minecraft.world.level.levelgen.FlatLevelSource flat
                && GeneratorTerrainCapabilities.inheritsImplementation(generator.getClass(),
                        net.minecraft.world.level.levelgen.FlatLevelSource.class)) {
            var data = new JsonObject();
            data.addProperty("biome", flat.settings().getBiome().unwrapKey().orElseThrow().location().toString());
            var layers = new JsonArray();
            for (var layer : flat.settings().getLayersInfo()) {
                var encoded = new JsonObject();
                encoded.addProperty("height", layer.getHeight());
                encoded.add("state", net.minecraft.world.level.block.state.BlockState.CODEC
                        .encodeStart(dependencies.ops(), layer.getBlockState()).getOrThrow());
                layers.add(encoded);
            }
            data.add("layers", layers);
            root.add("flat_layers", data);
            plan = new TerrainPredictionPlan(TerrainPredictionPlan.Kind.FLAT_LAYERS, "vss:flat_layers", 1, false,
                    "Layered base terrain; custom features require their own decoration context");
        }
        plan = applyExtension(root, plan, extension);
        root.add(TerrainPredictionPlan.FIELD, plan.encode());
        if (plan.kind() == TerrainPredictionPlan.Kind.UNAVAILABLE) root.addProperty("vss_unsupported_reason", plan.reason());
        snapshotActualGenerator(root, generator, access, dependencies);
        var encoded = defer(root);
        if (root.has("vss_generator") && exceedsHeadroom(encoded)) {
            root.remove("vss_generator");
            root.addProperty("vss_generator_error", "Optional generator codec exceeds snapshot budget");
            encoded = defer(root);
        }
        return exceedsHeadroom(encoded) ? unavailableSnapshot("Terrain projection exceeds snapshot budget") : encoded;
    }

    private static void snapshotActualGenerator(JsonObject root, ChunkGenerator generator, RegistryAccess access,
            WorldgenRegistryDependencies dependencies) {
        try {
            JsonElement encoded = ChunkGenerator.CODEC.encodeStart(dependencies.ops(), generator).getOrThrow();
            if (generator instanceof NoiseBasedChunkGenerator noise && encoded.isJsonObject()) {
                // The client does not necessarily have the server's noise-settings registry.
                var settings = noise.generatorSettings().value();
                if (requiresAppliedDensityGraph()) settings = DensityFunctionSnapshot.applied(settings);
                encoded.getAsJsonObject().add("settings", NoiseGeneratorSettings.DIRECT_CODEC
                        .encodeStart(dependencies.ops(), settings).getOrThrow());
                encoded.getAsJsonObject().add("biome_source", encodeBiomeSource(noise.getBiomeSource(), access, dependencies.ops()));
            }
            root.add("vss_generator", encoded);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException | LinkageError failure) {
            root.addProperty("vss_generator_error", failure.getClass().getSimpleName());
            VSSLogger.debug("VSS optional generator codec unavailable for " + generator.getClass().getName() + ": " + failure);
        }
    }
    private static boolean exceedsHeadroom(Encoded encoded) {
        if (Budget.assume(encoded)) return false;
        return encoded.bytes().length > GENERATOR_BYTES_HEADROOM || encoded.rawSize() > GENERATOR_RAW_BYTES_HEADROOM;
    }
    private static Encoded unavailableSnapshot(String reason) {
        var root = new JsonObject();
        root.add(TerrainPredictionPlan.FIELD, TerrainPredictionPlan.unavailable(reason).encode());
        root.addProperty("vss_unsupported_reason", reason);
        return compress(root);
    }

    static void snapshotFreeTerraForged(JsonObject root, RegistryAccess access,
            WorldgenRegistryDependencies dependencies, java.util.function.Predicate<String> loaded) {
        for (var variant : FreeTerraForgedVariant.values()) {
            if (!loaded.test(variant.namespace) || !variant.hasPreset(access)) continue;
            // Cell markers do not serialize the preset/noise lookups made by RandomState.
            dependencies.require(ResourceLocation.parse(variant.namespace + ":worldgen/preset"));
            dependencies.require(ResourceLocation.parse(variant.namespace + ":worldgen/noise"));
            root.addProperty("vss_freeterraforged", true);
            return;
        }
    }

    // Tectonic's NoisesMixin changes only the positional RNG name. The
    // parameters still belong to the original noise registry entry. A data
    // pack with the same namespace, without this mixin, must keep its own seed.
    static JsonObject noiseSeedAliases(java.util.Set<ResourceLocation> noiseIds, boolean tectonicLoaded) {
        JsonObject aliases = new JsonObject();
        if (tectonicLoaded) noiseIds.stream().sorted().forEach(id -> {
            if (id.getNamespace().equals("tectonic") && id.getPath().startsWith("parameter/")) {
                aliases.addProperty(id.toString(), ResourceLocation.withDefaultNamespace(
                        id.getPath().substring("parameter/".length())).toString());
            }
        });
        return aliases;
    }

    private static boolean requiresAppliedDensityGraph() {
        var mods = net.neoforged.fml.ModList.get();
        return mods != null && (mods.isLoaded("tectonic") || mods.isLoaded("lithostitched"));
    }

    private static JsonObject encodeDensities(RegistryAccess access, RegistryOps<JsonElement> ops,
                                              DensityFunctionReferences references) {
        if (!requiresAppliedDensityGraph()) {
            return encodeRegistry(access, ops, Registries.DENSITY_FUNCTION, DensityFunction.DIRECT_CODEC);
        }
        JsonObject result = new JsonObject();
        for (var entry : access.registryOrThrow(Registries.DENSITY_FUNCTION).entrySet()) {
            try {
                var encoded = DensityFunction.DIRECT_CODEC.encodeStart(ops,
                        DensityFunctionSnapshot.applied(entry.getValue())).getOrThrow();
                result.add(entry.getKey().location().toString(), references.compact(encoded));
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Cannot snapshot density " + entry.getKey().location(), failure);
            }
        }
        return result;
    }

    /**
     * Resolve vanilla's compact multi-noise preset form before sending the
     * generator to a client. A client connection does not expose the
     * multi_noise_biome_source_parameter_list registry, so leaving
     * {"preset":"minecraft:overworld"} in the payload makes BiomeSource's
     * codec fail and prevents prediction from initializing. Wrappers such as
     * Lithostitched's injector encode their source inside a delegate field.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static JsonElement encodeBiomeSource(BiomeSource source, RegistryAccess access,
                                         RegistryOps<JsonElement> ops) {
        JsonObject encoded = BiomeSource.CODEC.encodeStart(ops, source)
                .getOrThrow().getAsJsonObject();
        resolveMultiNoisePresets(encoded, access, ops);
        // Convert HolderSet encodings to stable biome ids. This keeps the
        // payload independent of client-side holder internals and mirrors the
        // handling used for inline multi-noise biome lists.
        if (encoded.has("biomes")
                && (!encoded.get("biomes").isJsonArray()
                || containsInlineBiome(encoded.getAsJsonArray("biomes")))) {
            HolderSet biomes = Biome.LIST_CODEC.parse(ops, encoded.get("biomes")).getOrThrow();
            JsonArray ids = new JsonArray();
            biomes.stream().forEach(value -> {
                Holder<?> holder = (Holder<?>) value;
                String id = holder.unwrapKey()
                        .map(key -> key.location().toString())
                        .orElseThrow(() -> new IllegalStateException(
                                "Inline biomes cannot be sampled"));
                ids.add(id);
            });
            encoded.add("biomes", ids);
        }
        return encoded;
    }

    /** Mutates only the freshly encoded snapshot, preserving wrapper fields and routing. */
    static void resolveMultiNoisePresets(JsonElement encoded, RegistryAccess access,
                                         RegistryOps<JsonElement> ops) {
        if (encoded.isJsonArray()) {
            for (JsonElement child : encoded.getAsJsonArray()) resolveMultiNoisePresets(child, access, ops);
        } else if (encoded.isJsonObject()) {
            JsonObject object = encoded.getAsJsonObject();
            // A mod's own `preset` field need not describe a vanilla biome source.
            if (object.has("type") && object.get("type").isJsonPrimitive()
                    && object.get("type").getAsString().equals("minecraft:multi_noise")
                    && object.has("preset")) {
                ResourceLocation presetId = ResourceLocation.parse(object.get("preset").getAsString());
                MultiNoiseBiomeSourceParameterList preset = access
                        .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).get(presetId);
                if (preset == null) throw new IllegalStateException("Unknown multi-noise preset " + presetId);
                JsonObject resolved = MultiNoiseBiomeSource.DIRECT_CODEC.codec()
                        .encodeStart(ops, preset.parameters()).getOrThrow().getAsJsonObject();
                object.remove("preset");
                object.add("biomes", resolved.get("biomes"));
            }
            for (var child : object.entrySet()) resolveMultiNoisePresets(child.getValue(), access, ops);
        }
    }

    private static boolean containsInlineBiome(JsonArray biomes) {
        for (JsonElement element : biomes) {
            if (element.isJsonPrimitive()
                    || element.isJsonObject() && element.getAsJsonObject().has("parameters")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static <T> JsonObject encodeRegistry(
            RegistryAccess access,
            RegistryOps<JsonElement> ops,
            ResourceKey<? extends Registry<T>> registryKey,
            Codec<T> codec) {
        JsonObject object = new JsonObject();
        for (Map.Entry<ResourceKey<T>, T> entry : access.registryOrThrow(registryKey).entrySet()) {
            JsonElement encoded;
            try {
                encoded = encodeEntry(ops, codec, entry.getValue());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Cannot snapshot " + registryKey.location()
                        + " entry " + entry.getKey().location(), exception);
            }
            object.add(entry.getKey().location().toString(), encoded);
        }
        return object;
    }

    private static <T> JsonElement encodeEntry(RegistryOps<JsonElement> ops, Codec<T> codec, T value) {
        JsonElement encoded = codec.encodeStart(ops, value).getOrThrow();
        if (value instanceof ConfiguredFeature<?, ?> feature) {
            encoded = dev.xantha.vss.common.worldgen.VanillaFeatureSnapshot.correct(feature, encoded);
        }
        if (value instanceof Structure structure) {
            encoded.getAsJsonObject().add("biomes", structureBiomeIds(structure.biomes().stream()));
        }
        if (value instanceof StructureSet set && set.placement() instanceof
                net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement rings) {
            // The client snapshot owns new biome holders but not the server's
            // dynamic biome tags. Preserve the actual preferred-biome set.
            encoded.getAsJsonObject().getAsJsonObject("placement")
                    .add("preferred_biomes", structureBiomeIds(rings.preferredBiomes().stream()));
        }
        return encoded;
    }

    static JsonArray structureBiomeIds(java.util.stream.Stream<Holder<Biome>> holders) {
        return sortedStructureBiomeIds(holders.map(holder -> holder.unwrapKey()
                .map(key -> key.location().toString()).orElse("")));
    }

    static JsonArray sortedStructureBiomeIds(java.util.stream.Stream<String> ids) {
        JsonArray biomes = new JsonArray();
        ids.filter(value -> !value.isEmpty()).sorted().forEach(biomes::add);
        return biomes;
    }

    static <T> JsonObject encodeOptional(RegistryAccess access, RegistryOps<JsonElement> ops,
                                        ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
        JsonObject result = new JsonObject();
        Registry<T> registry;
        try {
            registry = access.registryOrThrow(key);
        } catch (RuntimeException | LinkageError failure) {
            VSSLogger.warn("VSS optional worldgen registry unavailable: " + key.location() + ": " + failure);
            return result;
        }
        for (var entry : registry.entrySet()) {
            try {
                result.add(entry.getKey().location().toString(), encodeEntry(ops, codec, entry.getValue()));
            } catch (RuntimeException | LinkageError failure) {
                // Mod codecs can throw IllegalAccessError/NoClassDefFoundError,
                // not just a DFU error. One optional entry must not abort login
                // or erase the other entries from this prediction snapshot.
                VSSLogger.warn("VSS omitted optional worldgen entry " + key.location()
                        + " / " + entry.getKey().location() + ": " + failure);
            }
        }
        return result;
    }

    static JsonObject filterStructureSets(JsonObject structures, JsonObject encodedSets) {
        JsonObject sets = encodedSets.deepCopy();
        boolean removed;
        do {
            removed = false;
            for (String id : java.util.List.copyOf(sets.keySet())) {
                String missing = missingStructureDependency(sets.getAsJsonObject(id), structures, sets);
                if (missing == null) continue;
                // Drop the entire set: removing one member would change its
                // weights and predict a different building at the same start.
                sets.remove(id);
                removed = true;
                VSSLogger.warn("VSS omitted prediction structure set " + id
                        + " because its snapshot is missing " + missing);
            }
            // Exclusion zones can refer to a set omitted in the previous pass.
        } while (removed);
        return sets;
    }

    private static String missingStructureDependency(JsonObject set, JsonObject structures, JsonObject sets) {
        for (JsonElement entry : set.getAsJsonArray("structures")) {
            JsonElement structure = entry.getAsJsonObject().get("structure");
            if (structure.isJsonPrimitive() && structure.getAsJsonPrimitive().isString()
                    && !structures.has(structure.getAsString())) {
                return "structure " + structure.getAsString();
            }
        }
        JsonObject placement = set.getAsJsonObject("placement");
        if (!placement.has("exclusion_zone")) return null;
        JsonElement other = placement.getAsJsonObject("exclusion_zone").get("other_set");
        if (other.isJsonPrimitive() && other.getAsJsonPrimitive().isString()
                && !sets.has(other.getAsString())) return "structure set " + other.getAsString();
        return other.isJsonObject() ? missingStructureDependency(other.getAsJsonObject(), structures, sets) : null;
    }

    private static Encoded compress(JsonObject object) {
        byte[] raw = dev.xantha.vss.common.worldgen.WorldgenJson.bytes(object);
        LodByteCompression.Result compressed = LodByteCompression.compressForNetwork(raw, false);
        return new Encoded(compressed.method(), compressed.originalLength(), compressed.bytes());
    }

    /** Optional JSON metadata keeps the wire format compatible with older clients. */
    static Encoded withDimensionType(Encoded generator, net.minecraft.world.level.dimension.DimensionType type) {
        if (generator.isEmpty()) return generator;
        try {
            JsonObject root = document(generator);
            root.add("vss_dimension_type", net.minecraft.world.level.dimension.DimensionType.DIRECT_CODEC
                    .encodeStart(JsonOps.INSTANCE, type).getOrThrow());
            Encoded encoded = defer(root);
            return exceedsHeadroom(encoded) ? unavailableSnapshot("Dimension type metadata exceeds snapshot budget") : encoded;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot append prediction dimension type", failure);
        }
    }

    /** Capture hidden server biome routing after the ordinary codec snapshot, before publication. */
    static Encoded withRuntimeBiomeSource(Encoded generator, BiomeSource source, long seed) {
        if (generator.isEmpty()) return generator;
        try {
            JsonObject root = document(generator);
            TerrainPredictionPlan plan = TerrainPredictionPlan.read(root);
            if (plan.kind() != TerrainPredictionPlan.Kind.NOISE_GRAPH || !plan.backend().equals("vss:noise_graph")) return generator;
            try {
                JsonObject end = TerraBlenderEndSnapshot.capture(source, seed);
                if (end == null) return generator;
                root.add(TerraBlenderEndSnapshot.FIELD, end);
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException | LinkageError failure) {
                String reason = "Required TerraBlender End routing could not be captured: " + failure;
                root.addProperty("vss_unsupported_reason", reason);
                root.add(TerrainPredictionPlan.FIELD, TerrainPredictionPlan.unavailable(reason).encode());
                VSSLogger.warn(reason);
            }
            Encoded encoded = defer(root);
            return exceedsHeadroom(encoded) ? unavailableSnapshot("TerraBlender End routing exceeds snapshot budget") : encoded;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot append server biome routing", failure);
        }
    }

    /**
     * A generator snapshot. Built from bytes it is what the record was. Built by {@link WorldgenCodecSnapshot#defer} it
     * keeps its document and serialises and compresses it the first time anything reads its bytes, so stages that extend
     * a snapshot work on a copy of its document instead of decompressing and parsing it, and a build compresses each
     * generator once.
     */
    static final class Encoded {
        private JsonObject document;
        private boolean materialized;
        private int compression;
        private int rawSize;
        private byte[] bytes;

        Encoded(int compression, int rawSize, byte[] bytes) {
            this.compression = compression;
            this.rawSize = rawSize;
            this.bytes = bytes;
            this.materialized = true;
        }

        private Encoded(JsonObject document) {
            this.document = document;
        }

        static Encoded empty() {
            return new Encoded(LodByteCompression.METHOD_NONE, 0, new byte[0]);
        }

        int compression() {
            materialize();
            return compression;
        }

        int rawSize() {
            materialize();
            return rawSize;
        }

        byte[] bytes() {
            materialize();
            return bytes;
        }

        /** As bytes().length == 0, without compressing: a document always serialises to at least "{}". */
        synchronized boolean isEmpty() {
            return document == null && bytes.length == 0;
        }

        private synchronized JsonObject retained() {
            return document;
        }

        /** The snapshot leaves the build: its bytes are fixed and its document is no longer kept. */
        synchronized void publish() {
            materialize();
            document = null;
        }

        private synchronized void materialize() {
            if (materialized) return;
            byte[] raw = dev.xantha.vss.common.worldgen.WorldgenJson.bytes(document);
            LodByteCompression.Result result = LodByteCompression.compressForNetwork(raw, false);
            compression = result.method();
            rawSize = result.originalLength();
            bytes = result.bytes();
            materialized = true;
        }
    }

    /** A snapshot of root's current members; root may change afterwards, the members themselves may not. */
    static Encoded defer(JsonObject root) {
        return new Encoded(copy(root));
    }

    /**
     * A stage's own root for a snapshot: a new object over the snapshot's members, or its parsed bytes when it has no
     * document. Stages add, replace or remove members of the root; they never change a member's value in place.
     */
    static JsonObject document(Encoded generator) throws java.io.IOException {
        JsonObject retained = generator.retained();
        if (retained != null) return copy(retained);
        byte[] raw = LodByteCompression.decompress(generator.bytes(), generator.compression(), generator.rawSize(),
                dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES);
        return com.google.gson.JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject copy(JsonObject root) {
        JsonObject copy = new JsonObject();
        for (var member : root.entrySet()) copy.add(member.getKey(), member.getValue());
        return copy;
    }

    /**
     * The headroom checks of one generator build (see WorldgenProfileBuilder). Running fast, a check on a deferred
     * snapshot is assumed to pass and the snapshot is not compressed for it. The build then keeps its result only when
     * the finished snapshot contains every member of each assumed one and stays clear of the headroom by a margin;
     * otherwise it runs again with every check exact, as each stage compressed its snapshot before.
     */
    static final class Budget {
        static final int RAW_MARGIN = 512 * 1024;
        static final int ENCODED_MARGIN = 512 * 1024;
        private static final boolean EXACT_ONLY = Boolean.getBoolean("vss.worldgenProfile.exactBudget");
        private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();
        private static final ThreadLocal<Boolean> FORCE_EXACT = new ThreadLocal<>();
        private final boolean exact;
        private final Budget previous;
        private final java.util.List<java.util.Set<String>> assumed = new java.util.ArrayList<>();

        private Budget(boolean exact, Budget previous) {
            this.exact = exact;
            this.previous = previous;
        }

        /** True when builds on this thread run every check exact from the start. */
        static boolean exactOnly() {
            return EXACT_ONLY || Boolean.TRUE.equals(FORCE_EXACT.get());
        }

        /** Runs action with every build on this thread exact, for the cache's uncached reference build. */
        static <T> T exactly(java.util.function.Supplier<T> action) {
            Boolean before = FORCE_EXACT.get();
            FORCE_EXACT.set(Boolean.TRUE);
            try { return action.get(); }
            finally {
                if (before == null) FORCE_EXACT.remove();
                else FORCE_EXACT.set(before);
            }
        }

        static Budget open(boolean exact) {
            Budget budget = new Budget(exact, CURRENT.get());
            CURRENT.set(budget);
            return budget;
        }

        void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }

        /** Whether the finished snapshot of this build can be published as built (always, when every check ran exact). */
        boolean settle(Encoded generator) {
            if (exact || assumed.isEmpty()) return true;
            JsonObject finished = generator == null ? null : generator.retained();
            if (finished == null) return false;
            for (var members : assumed) if (!finished.keySet().containsAll(members)) return false;
            return generator.rawSize() <= GENERATOR_RAW_BYTES_HEADROOM - RAW_MARGIN
                    && generator.bytes().length <= GENERATOR_BYTES_HEADROOM - ENCODED_MARGIN;
        }

        private static boolean assume(Encoded encoded) {
            Budget budget = CURRENT.get();
            if (budget == null || budget.exact) return false;
            JsonObject document = encoded.retained();
            if (document == null) return false;
            budget.assumed.add(java.util.Set.copyOf(document.keySet()));
            return true;
        }
    }
}
