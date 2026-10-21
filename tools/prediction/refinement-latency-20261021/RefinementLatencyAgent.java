import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded read-only observation: no class transformation, world reads or GL calls. */
public final class RefinementLatencyAgent {
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(String directory, Instrumentation instrumentation) {
        var thread = new Thread(() -> {
            Path output = Path.of(directory);
            try {
                Files.createDirectories(output);
                Class<?> state = find(instrumentation, "dev.xantha.vss.client.prediction.ClientPredictionState");
                Class<?> renderer = find(instrumentation, "dev.xantha.vss.client.prediction.PredictionRenderer");
                Class<?> minecraft = find(instrumentation, "net.minecraft.client.Minecraft");
                Object client = minecraft.getMethod("getInstance").invoke(null);
                for (int i = 0; i <= 15; i++) {
                    StringBuilder text = new StringBuilder(Instant.now().toString()).append('\n');
                    text.append("paused=").append(minecraft.getMethod("isPaused").invoke(client)).append('\n');
                    Object scene = read(renderer, null, "scene");
                    text.append("renderer=").append(call(scene, "diagnostics")).append('\n');
                    text.append("boundaryVersion=").append(read(scene.getClass(), scene, "boundaryVersion"))
                        .append(",published=").append(read(scene.getClass(), scene, "publishedBoundaryVersion")).append('\n');
                    for (Object manager : ((Map<?, ?>)read(state, null, "MANAGERS")).values()) {
                        try { text.append(call(manager, "surfaceDiagnostics")).append('\n'); }
                        catch (Exception error) { text.append(error).append('\n'); }
                        for (String name : List.of("executor", "cacheExecutor")) {
                            var pool = (ThreadPoolExecutor)read(manager.getClass(), manager, name);
                            text.append(name).append(":active=").append(pool.getActiveCount())
                                .append(",queued=").append(pool.getQueue().size())
                                .append(",completed=").append(pool.getCompletedTaskCount()).append('\n');
                        }
                    }
                    Files.writeString(output.resolve(String.format("sample-%02d.txt", i)), text);
                    if (i < 15) Thread.sleep(2000);
                }
                Files.writeString(output.resolve("complete.txt"), Instant.now().toString());
            } catch (Throwable failure) {
                try { Files.writeString(output.resolve("error.txt"), failure.toString()); }
                catch (Exception ignored) { }
            }
        }, "vss-readonly-refinement-observer");
        thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); thread.start();
    }
    private static Class<?> find(Instrumentation instrumentation, String name) {
        return Arrays.stream(instrumentation.getAllLoadedClasses()).filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
    }
    private static Object read(Class<?> type, Object target, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object call(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
}
