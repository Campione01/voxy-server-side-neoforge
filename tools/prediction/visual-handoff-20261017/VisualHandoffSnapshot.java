import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One bounded read of render-owned state. No class or settings changes and no GL calls. */
public class VisualHandoffSnapshot {
    static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Field f = type.getDeclaredField(name); f.setAccessible(true);
        return f.get(target instanceof Class<?> ? null : target);
    }
    static Object call(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Method m = type.getDeclaredMethod(name); m.setAccessible(true);
        return m.invoke(target instanceof Class<?> ? null : target);
    }
    public static void agentmain(String output, Instrumentation inst) throws Exception {
        Class<?> renderer = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName()
                .equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
        Class<?> mcClass = renderer.getClassLoader().loadClass("net.minecraft.client.Minecraft");
        Object mc = mcClass.getMethod("getInstance").invoke(null);
        var result = new CompletableFuture<String>();
        mcClass.getMethod("execute", Runnable.class).invoke(mc, (Runnable) () -> {
            try {
                var out = new StringBuilder();
                out.append("fps=").append(call(mc,"getFps")).append("\npaused=").append(call(mc,"isPaused"));
                Object scene = field(renderer,"scene"), plan = field(scene,"visible");
                out.append("\ncamera=").append(field(plan,"camera"));
                out.append("\nray=").append(field(renderer,"viewRay"));
                Map<?,?> entries = (Map<?,?>)field(plan,"entries");
                int hidden = 0, shown = 0;
                for (Object entry : entries.values()) {
                    if (Boolean.FALSE.equals(field(entry,"eligible"))) hidden++;
                    if (Boolean.TRUE.equals(field(entry,"visible"))) shown++;
                }
                out.append("\nretained=").append(entries.size()).append("\nineligible=").append(hidden)
                        .append("\nvisible=").append(shown);
                out.append("\nrenderer=").append(call(renderer,"diagnostics"));
                Object mask = field(renderer,"exactMask"), snapshot = field(mask,"current");
                if(snapshot != null) {
                    byte[] columns = (byte[])call(snapshot,"columns");
                    int interior=0, edge=0;
                    for(byte c:columns) { if(c == (byte)255) interior++; else if(c!=0) edge++; }
                    out.append("\nsettledColumns=").append(interior).append("\nedgeColumns=").append(edge);
                }
                result.complete(out.toString());
            } catch (Throwable error) { result.complete(error.toString()+"\n"+Arrays.toString(error.getStackTrace())); }
        });
        Files.writeString(Path.of(output),result.get(10,TimeUnit.SECONDS));
    }
}
