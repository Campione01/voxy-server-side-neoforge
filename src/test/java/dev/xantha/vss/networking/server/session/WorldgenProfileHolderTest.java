package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** invalidate drops one dimension's payload, and the all-levels payload containing it, and keeps the rest. */
class WorldgenProfileHolderTest {
    private static final ResourceKey<Level> OTHERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("confluence_dimension_patch:otherworld"));

    @BeforeAll static void bootstrap() { TerrainPredictionFixtures.bootstrap(); }

    @AfterEach void reset() { WorldgenProfileHolder.clear(); }

    private static WorldgenProfileS2CPayload payload(int marker) {
        return new WorldgenProfileS2CPayload(WorldgenProfileS2CPayload.FORMAT_VERSION, 1L, marker, 0, 1, new byte[] {(byte) marker}, List.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceKey<Level>, WorldgenProfileS2CPayload> payloads() throws Exception {
        var field = WorldgenProfileHolder.class.getDeclaredField("payloads");
        field.setAccessible(true);
        return (Map<ResourceKey<Level>, WorldgenProfileS2CPayload>) field.get(null);
    }

    @Test
    void invalidatingOneDimensionKeepsTheOtherDimensionsCached() throws Exception {
        var payloads = payloads();
        var overworld = payload(1);
        var nether = payload(2);
        payloads.put(Level.OVERWORLD, overworld);
        payloads.put(Level.NETHER, nether);
        payloads.put(OTHERWORLD, payload(3));
        payloads.put(null, payload(4));

        assertTrue(WorldgenProfileHolder.invalidate(OTHERWORLD));
        assertSame(overworld, payloads.get(Level.OVERWORLD), "the Overworld's payload stays: returning there is no build");
        assertSame(nether, payloads.get(Level.NETHER));
        assertFalse(payloads.containsKey(OTHERWORLD));
        assertFalse(payloads.containsKey(null), "the all-levels payload contains the invalidated dimension");
        assertEquals(2, payloads.size());

        assertFalse(WorldgenProfileHolder.invalidate(OTHERWORLD), "nothing left to drop");
        assertFalse(WorldgenProfileHolder.invalidate(null));
        assertEquals(2, payloads.size());
    }

    @Test
    void invalidatingADimensionWithoutAPayloadDropsOnlyTheAllLevelsPayload() throws Exception {
        var payloads = payloads();
        var overworld = payload(1);
        payloads.put(Level.OVERWORLD, overworld);
        payloads.put(null, payload(4));
        assertTrue(WorldgenProfileHolder.invalidate(Level.END));
        assertEquals(Map.of(Level.OVERWORLD, overworld), Map.copyOf(payloads));
    }

    @Test
    void clearStillDropsEveryPayload() throws Exception {
        var payloads = payloads();
        payloads.put(Level.OVERWORLD, payload(1));
        payloads.put(Level.NETHER, payload(2));
        WorldgenProfileHolder.clear();
        assertTrue(payloads.isEmpty());
    }
}
