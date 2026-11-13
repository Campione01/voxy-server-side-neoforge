package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import dev.xantha.vss.common.worldgen.WorldgenJson;
import dev.xantha.vss.common.worldgen.WorldgenRegistryDependencies;
import dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot.Budget;
import dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot.Encoded;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Generator snapshots keep their document between stages. Every stage must give the bytes that 3324c9b gave by
 * decompressing, parsing, changing and compressing its input, and a fast build compresses only what it publishes.
 */
class WorldgenGeneratorDocumentTest {
    private static final int MAX_RAW = 8 * 1024 * 1024;

    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    private static Encoded encode(ChunkGenerator generator) {
        var access = TerrainPredictionFixtures.access();
        return WorldgenCodecSnapshot.encodeGenerator(generator, access, new DensityFunctionReferences(),
                new WorldgenRegistryDependencies(access, List.of()));
    }

    private static ChunkGenerator flat() {
        var access = TerrainPredictionFixtures.access();
        var settings = new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(java.util.Optional.empty(),
                access.registryOrThrow(Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS), List.of());
        settings.getLayersInfo().add(new net.minecraft.world.level.levelgen.flat.FlatLayerInfo(4, net.minecraft.world.level.block.Blocks.STONE));
        settings.updateLayers();
        return new net.minecraft.world.level.levelgen.FlatLevelSource(settings);
    }

    /** TerrainPredictionFixtures.noise() holds references the copied registries do not own, so it cannot be encoded. */
    private static ChunkGenerator noise() {
        var alias = new TerrainGeneratorSnapshotTest.NoiseAlias();
        return new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(alias.getBiomeSource(), alias.generatorSettings());
    }

    private static DimensionType type(ResourceKey<DimensionType> key) {
        return TerrainPredictionFixtures.access().registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key);
    }

    private static JsonObject parse(Encoded value) throws Exception {
        byte[] raw = LodByteCompression.decompress(value.bytes(), value.compression(), value.rawSize(), MAX_RAW);
        return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 3324c9b's stage: decompress and parse the input, add the member, serialise and compress. */
    private static LodByteCompression.Result legacyAppend(Encoded input, String key, JsonElement value) throws Exception {
        JsonObject root = parse(input);
        root.add(key, value);
        return LodByteCompression.compressForNetwork(WorldgenJson.bytes(root), false);
    }

    private static void assertSameBytes(LodByteCompression.Result expected, Encoded actual, String what) {
        assertEquals(expected.method(), actual.compression(), what);
        assertEquals(expected.originalLength(), actual.rawSize(), what);
        assertArrayEquals(expected.bytes(), actual.bytes(), what);
    }

    private static boolean materialized(Encoded value) throws Exception {
        var field = Encoded.class.getDeclaredField("materialized");
        field.setAccessible(true);
        return field.getBoolean(value);
    }

    private static boolean exceedsHeadroom(Encoded value) throws Exception {
        var method = WorldgenCodecSnapshot.class.getDeclaredMethod("exceedsHeadroom", Encoded.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, value);
    }

    private static String incompressible(int bytes, long seed) {
        byte[] data = new byte[bytes];
        new java.util.Random(seed).nextBytes(data);
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    @Test
    void eachStageGivesTheBytesOfDecompressingAndParsingItsInput() throws Exception {
        for (boolean fast : new boolean[] {false, true}) {
            for (var generator : List.of(flat(), noise())) {
                var budget = Budget.open(!fast);
                try {
                    Encoded base = encode(generator);
                    assertSameBytes(LodByteCompression.compressForNetwork(WorldgenJson.bytes(parse(base)), false), base,
                            "a parsed snapshot serialises to its own bytes");
                    Encoded eager = new Encoded(base.compression(), base.rawSize(), base.bytes());
                    var staged = new ArrayList<byte[]>();
                    for (var key : List.of(BuiltinDimensionTypes.OVERWORLD, BuiltinDimensionTypes.NETHER, BuiltinDimensionTypes.END)) {
                        var value = DimensionType.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, type(key)).getOrThrow();
                        var expected = legacyAppend(base, "vss_dimension_type", value);
                        String what = generator.getClass().getSimpleName() + " " + key.location() + (fast ? " fast" : " exact");
                        assertSameBytes(expected, WorldgenCodecSnapshot.withDimensionType(base, type(key)), what + " from the document");
                        assertSameBytes(expected, WorldgenCodecSnapshot.withDimensionType(eager, type(key)), what + " from bytes");
                        staged.add(expected.bytes());
                    }
                    for (int i = 0; i < staged.size(); i++)
                        for (int j = i + 1; j < staged.size(); j++)
                            assertFalse(java.util.Arrays.equals(staged.get(i), staged.get(j)),
                                    "each dimension type has its own snapshot; bytes are never shared between them");
                } finally {
                    budget.close();
                }
            }
        }
    }

    @Test
    void aStageChangesOnlyItsOwnCopy() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("a", 1);
        root.add("settings", JsonParser.parseString("{\"surface_rule\":{\"type\":\"minecraft:bandlands\"}}"));
        JsonObject expected = root.deepCopy();
        Encoded snapshot = WorldgenCodecSnapshot.defer(root);
        root.addProperty("late", true);
        JsonObject stage = WorldgenCodecSnapshot.document(snapshot);
        stage.remove("a");
        stage.addProperty("b", 2);
        Encoded next = WorldgenCodecSnapshot.defer(stage);
        assertEquals(expected, parse(snapshot), "neither the caller's later change nor the next stage reaches a snapshot");
        JsonObject following = expected.deepCopy();
        following.remove("a");
        following.addProperty("b", 2);
        assertEquals(following, parse(next));
        Encoded published = WorldgenCodecSnapshot.defer(expected);
        byte[] bytes = published.bytes().clone();
        published.publish();
        assertArrayEquals(bytes, published.bytes());
        assertEquals(expected, WorldgenCodecSnapshot.document(published), "a published snapshot is read from its bytes");
    }

    @Test
    void aFastBuildAssumesChecksAndKeepsOnlyFinishedSnapshotsClearOfTheHeadroomByTheMargin() throws Exception {
        var budget = Budget.open(false);
        try {
            JsonObject root = new JsonObject();
            root.addProperty("settings", "small");
            Encoded base = WorldgenCodecSnapshot.defer(root);
            assertFalse(exceedsHeadroom(base));
            assertFalse(materialized(base), "an assumed check compresses nothing");
            JsonObject next = WorldgenCodecSnapshot.document(base);
            next.addProperty("vss_dimension_type", "t");
            Encoded finished = WorldgenCodecSnapshot.defer(next);
            assertFalse(materialized(finished));
            assertTrue(budget.settle(finished));
            assertTrue(materialized(finished), "settling compresses the finished snapshot, once");
            assertFalse(materialized(base), "the intermediate snapshot is never compressed");
            JsonObject other = new JsonObject();
            other.addProperty("vss_dimension_type", "t");
            assertFalse(budget.settle(WorldgenCodecSnapshot.defer(other)),
                    "a finished snapshot without every member of an assumed one is built again exactly");
        } finally {
            budget.close();
        }

        JsonObject near = new JsonObject();
        near.addProperty("blob", incompressible(1_100_000, 1L));
        budget = Budget.open(false);
        try {
            Encoded fast = WorldgenCodecSnapshot.defer(near);
            assertFalse(exceedsHeadroom(fast));
            assertFalse(budget.settle(fast), "inside the headroom but not by the margin: built again exactly");
        } finally {
            budget.close();
        }
        budget = Budget.open(true);
        try {
            Encoded exact = WorldgenCodecSnapshot.defer(near);
            assertFalse(exceedsHeadroom(exact), "the exact check passes: the snapshot is inside the headroom");
            assertTrue(materialized(exact), "an exact check compresses, as each stage did before");
            assertTrue(budget.settle(exact));
        } finally {
            budget.close();
        }

        JsonObject over = new JsonObject();
        over.addProperty("blob", incompressible(1_800_000, 2L));
        budget = Budget.open(false);
        try {
            Encoded fast = WorldgenCodecSnapshot.defer(over);
            assertFalse(exceedsHeadroom(fast));
            assertFalse(budget.settle(fast));
        } finally {
            budget.close();
        }
        assertTrue(exceedsHeadroom(WorldgenCodecSnapshot.defer(over)), "outside a build every check is exact");
        boolean insideExactly = Budget.exactly(Budget::exactOnly);
        assertTrue(insideExactly, "the cache's reference build runs every check exact");
        assertFalse(Budget.exactOnly());
    }
}
