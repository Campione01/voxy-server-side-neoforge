package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** "Legacy RTF prediction initialized: dimension=..." names the level being decoded, not the Overworld for every level. */
class LegacyRtfLevelLabelTest {
    private static final String COMPAT = "dev/xantha/vss/client/prediction/FreeTerraForgedCompat";
    private static final String PROFILE = "dev/xantha/vss/networking/payloads/WorldgenProfileS2CPayload$DimensionProfile";

    @Test
    void theLabelIsTheLevelSetByTheDecoderAndUnknownOutsideIt() {
        FreeTerraForgedCompat.LEVEL.remove();
        assertEquals("unknown level", FreeTerraForgedCompat.levelLabel());
        FreeTerraForgedCompat.LEVEL.set("confluence_dimension_patch:otherworld");
        try {
            assertEquals("confluence_dimension_patch:otherworld", FreeTerraForgedCompat.levelLabel());
        } finally {
            FreeTerraForgedCompat.LEVEL.remove();
        }
    }

    /** The Java sampler sets the profile's own level key right before its one state factory call and removes it after. */
    @Test
    void theDecoderSetsTheProfilesLevelAroundItsOneStateFactoryCall() throws Exception {
        ClassNode decoder = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream("dev/xantha/vss/client/prediction/ClientWorldgenProfileDecoder.class")) {
            new ClassReader(input.readAllBytes()).accept(decoder, 0);
        }
        MethodNode sampler = decoder.methods.stream()
                .filter(m -> m.name.equals("decodeJavaSampler") && m.desc.startsWith("(L" + PROFILE + ";"))
                .findFirst().orElseThrow();
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode node : sampler.instructions) if (node.getOpcode() >= 0) code.add(node);

        int create = -1;
        for (int i = 0; i < code.size(); i++)
            if (code.get(i) instanceof MethodInsnNode call && call.owner.equals(COMPAT) && call.name.equals("create")) {
                assertEquals(-1, create, "one state factory call");
                create = i;
            }
        assertNotEquals(-1, create);

        int set = -1;
        for (int i = 0; i < code.size(); i++) if (threadLocal(code.get(i), "set")) set = i;
        assertTrue(set >= 0 && set < create, "LEVEL is set before the state factory call");
        int level = set - 5;
        assertTrue(level >= 0 && code.get(level) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(COMPAT) && field.name.equals("LEVEL"), "the set is on FreeTerraForgedCompat.LEVEL");
        assertTrue(code.get(level + 1) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0,
                "the value comes from the profile argument");
        assertCall(code.get(level + 2), PROFILE, "levelKey");
        assertCall(code.get(level + 3), "net/minecraft/resources/ResourceKey", "location");
        assertCall(code.get(level + 4), "net/minecraft/resources/ResourceLocation", "toString");

        boolean removed = false;
        for (int i = create + 1; i < code.size() && !removed; i++)
            removed = threadLocal(code.get(i), "remove") && code.get(i - 1) instanceof FieldInsnNode field && field.name.equals("LEVEL");
        assertTrue(removed, "LEVEL is removed after the state factory call");
    }

    /** The legacy initializer's label argument is levelLabel(), not a constant. */
    @Test
    void theInitializerLogsTheLabelTheDecoderSet() throws Exception {
        ClassNode api = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream(COMPAT + "$Api.class")) {
            new ClassReader(input.readAllBytes()).accept(api, 0);
        }
        MethodNode initialize = api.methods.stream().filter(m -> m.name.equals("initialize")).findFirst().orElseThrow();
        int calls = 0;
        for (AbstractInsnNode node : initialize.instructions) {
            if (!(node instanceof MethodInsnNode call && call.owner.equals("dev/xantha/vss/client/prediction/LegacyRtfRuntime")
                    && call.name.equals("initialize"))) continue;
            calls++;
            AbstractInsnNode argument = node.getPrevious();
            while (argument.getOpcode() < 0) argument = argument.getPrevious();
            assertCall(argument, COMPAT, "levelLabel");
        }
        assertEquals(1, calls);
    }

    private static boolean threadLocal(AbstractInsnNode node, String name) {
        return node instanceof MethodInsnNode call && call.owner.equals("java/lang/ThreadLocal") && call.name.equals(name);
    }

    private static void assertCall(AbstractInsnNode node, String owner, String name) {
        assertTrue(node instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name),
                "expected " + owner + "." + name);
    }
}
