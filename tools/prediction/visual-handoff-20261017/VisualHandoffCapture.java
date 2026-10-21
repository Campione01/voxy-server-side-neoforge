import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Arm the existing one-frame readback; never alters world, camera or render settings. */
public class VisualHandoffCapture {
    public static void agentmain(String output, Instrumentation inst) throws Exception {
        Class<?> renderer = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName()
                .equals("dev.xantha.vss.client.prediction.PredictionRenderCapture")).findFirst().orElseThrow();
        Class<?> mcClass = renderer.getClassLoader().loadClass("net.minecraft.client.Minecraft");
        Object mc = mcClass.getMethod("getInstance").invoke(null);
        mcClass.getMethod("execute", Runnable.class).invoke(mc, (Runnable) () -> {
            try {
                var capture = (CompletableFuture<?>)renderer.getMethod("request", double.class, double.class).invoke(null,.5,.56);
                capture.whenComplete((path,error) -> {
                    try { Files.writeString(Path.of(output),error == null ? path.toString() : error.toString()); }
                    catch(Exception ignored) { }
                });
            } catch(Exception error) { try { Files.writeString(Path.of(output),error.toString()); } catch(Exception ignored) { } }
        });
    }
}
