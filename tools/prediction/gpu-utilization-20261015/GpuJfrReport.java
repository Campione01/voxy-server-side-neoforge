import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Execution/native samples are sample counts; waits are separately reported durations. */
public class GpuJfrReport {
    static void add(Map<String, Long> map, String key, long n) { map.merge(key, n, Long::sum); }
    static String method(RecordedFrame f) { return f.getMethod().getType().getName()+"."+f.getMethod().getName(); }
    static String json(Object o) {
        if (o instanceof Map<?,?> m) return "{"+String.join(",", m.entrySet().stream().map(e->json(e.getKey())+":"+json(e.getValue())).toList())+"}";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o == null) return "null";
        return "\""+o.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
    static Map<String,Long> top(Map<String,Long> values, int limit) {
        Map<String,Long> result=new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(limit).forEach(e->result.put(e.getKey(), e.getValue()));
        return result;
    }
    public static void main(String[] args) throws Exception {
        record Window(Instant from, Instant to) { }
        List<Window> windows = args.length < 3 ? List.of() : Files.readAllLines(Path.of(args[2])).stream()
                .filter(s -> !s.isBlank()).map(s -> s.split("\t"))
                .map(s -> new Window(Instant.parse(s[0]), Instant.parse(s[1]))).toList();
        Map<String,Long> threads=new HashMap<>(), inclusive=new HashMap<>(), leaf=new HashMap<>(), categories=new HashMap<>(), waits=new HashMap<>();
        long renderSamples=0, gcCount=0, gcNanos=0, gcMax=0;
        try (var file=new RecordingFile(Path.of(args[0]))) {
            while(file.hasMoreEvents()) {
                var e=file.readEvent(); String name=e.getEventType().getName();
                if (!windows.isEmpty() && windows.stream().noneMatch(w -> !e.getStartTime().isBefore(w.from())
                        && e.getStartTime().isBefore(w.to()))) continue;
                var frames=e.getStackTrace()==null ? List.<RecordedFrame>of() : e.getStackTrace().getFrames();
                if(name.equals("jdk.ExecutionSample")||name.equals("jdk.NativeMethodSample")) {
                    var thread=e.getThread("sampledThread"); if(thread==null) continue;
                    add(threads,thread.getJavaName(),1);
                    if(!thread.getJavaName().equals("Render thread")) continue;
                    renderSamples++;
                    List<String> stack=frames.stream().map(GpuJfrReport::method).toList();
                    for(String m:new HashSet<>(stack)) add(inclusive,m,1);
                    if(!stack.isEmpty())add(leaf,stack.getFirst(),1);
                    String[][] patterns={
                        {"PredictionRenderer$Scene.prepare","vss_scene_prepare"},
                        {"PredictionRefinementOcclusion.","vss_visibility_feedback"},
                        {"PredictionVoxyBoundaryBridge.","vss_voxy_boundary"},
                        {"PredictionRenderer$PreparedPass.prepare","vss_pass_prepare"},
                        {"PredictionRenderer.maintainGpuResidency","vss_residency"},
                        {"PredictionRenderer.drawPasses","vss_draw_passes"},
                        {"dev.xantha.vss.","vss_other"},
                        {"net.minecraft.client.Minecraft.tick","client_tick"},
                        {"me.cortex.voxy.","voxy_without_vss"},
                        {"net.caffeinemc.mods.sodium.","sodium_without_vss"},
                        {"glfwSwapBuffers","swap_buffers"},
                        {"limitDisplayFPS","fps_limiter"},
                        {"net.minecraft.client.renderer.","vanilla_render_other"}
                    };
                    String category="other";
                    outer:for(var p:patterns)for(String m:stack)if(m.contains(p[0])){category=p[1];break outer;}
                    add(categories,category,1);
                } else if(name.equals("jdk.GCPhasePause")) {
                    long duration=e.getDuration().toNanos();gcCount++;gcNanos+=duration;gcMax=Math.max(gcMax,duration);
                } else if(name.equals("jdk.ThreadPark")||name.equals("jdk.JavaMonitorEnter")||name.equals("jdk.ThreadSleep")) {
                    var thread=e.getThread("eventThread");
                    if(thread!=null&&thread.getJavaName().equals("Render thread")) {
                        String stack=String.join(" <- ",frames.stream().limit(6).map(GpuJfrReport::method).toList());
                        add(waits,name+" | "+stack,e.getDuration().toNanos());
                    }
                }
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("renderSamples",renderSamples); result.put("threads",top(threads,20));
        result.put("renderExclusiveCategories",top(categories,30));result.put("renderInclusive",top(inclusive,90));
        result.put("renderLeaf",top(leaf,45));result.put("renderWaitNanos",top(waits,20));
        result.put("gcPauseCount",gcCount);result.put("gcPauseNanos",gcNanos);result.put("gcPauseMaxNanos",gcMax);
        String output=json(result);Files.writeString(Path.of(args[1]),output);System.out.println(output);
    }
}
