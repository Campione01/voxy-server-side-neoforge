import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded observation only: no class transformation, GL calls, or game-setting changes. */
public class GpuUtilizationAgent {
    static final List<String> COUNTERS = List.of("frameStarts", "renderFrames", "meshUploads",
            "preparedPlans", "reusedPlans", "drawCalls", "indirectCalls", "submittedQuads");
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(String directory, Instrumentation instrumentation) {
        Thread observer = new Thread(() -> {
            try { observe(Path.of(directory), instrumentation); }
            catch (Throwable error) {
                try { Files.writeString(Path.of(directory).resolve("agent-error.txt"), error.toString()); }
                catch (Exception ignored) { }
            }
        }, "VSS-readonly-gpu-observer");
        observer.setDaemon(true);
        observer.setPriority(Thread.MIN_PRIORITY);
        observer.start();
    }
    static Object read(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static String quote(Object value) {
        if (value == null) return "null";
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    static void observe(Path output, Instrumentation instrumentation) throws Exception {
        Files.createDirectories(output);
        var loaded = Arrays.asList(instrumentation.getAllLoadedClasses());
        Class<?> minecraft = loaded.stream().filter(c -> c.getName().equals("net.minecraft.client.Minecraft")).findFirst().orElseThrow();
        Class<?> renderer = loaded.stream().filter(c -> c.getName().equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
        Object client = minecraft.getMethod("getInstance").invoke(null);
        var mx = ManagementFactory.getThreadMXBean();
        var os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        Map<Long,Long> initialCpu = new HashMap<>();
        for (long id : mx.getAllThreadIds()) initialCpu.put(id, mx.getThreadCpuTime(id));
        long start = System.nanoTime();
        for (int i = 0; i <= 15; i++) {
            var fields = new ArrayList<String>();
            fields.add("\"time\":" + quote(Instant.now()));
            fields.add("\"nanoTime\":" + System.nanoTime());
            fields.add("\"processCpuNanos\":" + os.getProcessCpuTime());
            try { fields.add("\"fps\":" + minecraft.getMethod("getFps").invoke(client)); }
            catch (ReflectiveOperationException error) {
                try { fields.add("\"fps\":" + read(minecraft, client, "fps")); }
                catch (ReflectiveOperationException failure) { fields.add("\"fpsError\":" + quote(failure)); }
            }
            fields.add("\"paused\":" + minecraft.getMethod("isPaused").invoke(client));
            try { fields.add("\"active\":" + minecraft.getMethod("isWindowActive").invoke(client)); }
            catch (ReflectiveOperationException ignored) { }
            Object screen = read(minecraft, client, "screen");
            fields.add("\"screen\":" + quote(screen == null ? null : screen.getClass().getName()));
            try {
                Object window = minecraft.getMethod("getWindow").invoke(client);
                fields.add("\"width\":" + window.getClass().getMethod("getWidth").invoke(window));
                fields.add("\"height\":" + window.getClass().getMethod("getHeight").invoke(window));
            } catch (ReflectiveOperationException ignored) { }
            for (String name : COUNTERS) {
                try {
                    Object value = read(renderer, null, name);
                    fields.add(quote(name) + ":" + (value instanceof AtomicLong a ? a.get() : value));
                } catch (ReflectiveOperationException ignored) { }
            }
            for (long id : mx.getAllThreadIds()) {
                var info = mx.getThreadInfo(id);
                if (info != null && info.getThreadName().equals("Render thread")) {
                    fields.add("\"renderCpuNanos\":" + mx.getThreadCpuTime(id));
                    fields.add("\"renderState\":" + quote(info.getThreadState()));
                }
            }
            Files.writeString(output.resolve("runtime.jsonl"), "{" + String.join(",", fields) + "}\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (i < 15) Thread.sleep(2000);
        }
        long elapsed = System.nanoTime() - start;
        var lines = new ArrayList<String>();
        lines.add("wallSeconds=" + elapsed / 1e9);
        for (long id : mx.getAllThreadIds()) {
            var info = mx.getThreadInfo(id);
            long current = mx.getThreadCpuTime(id);
            Long before = initialCpu.get(id);
            if (info != null && before != null && before >= 0 && current >= before) {
                lines.add(String.format(Locale.ROOT, "%.6f\t%.6f\t%s", (current-before)/1e9,
                        100.0*(current-before)/elapsed, info.getThreadName()));
            }
        }
        Files.write(output.resolve("thread-cpu.tsv"), lines);
        Files.writeString(output.resolve("observer-complete.txt"), Instant.now().toString());
    }
}
