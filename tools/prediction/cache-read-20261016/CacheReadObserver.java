import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded observation: no transformation, invalidation, setting changes or generated work. */
public class CacheReadObserver {
    static ClassLoader loader;
    static Path output;
    public static void main(String[] args) throws Exception {
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); } finally { vm.detach(); }
    }
    public static void agentmain(String directory, Instrumentation instrumentation) {
        Thread thread = new Thread(() -> {
            try {
                output = Path.of(directory); Files.createDirectories(output);
                var renderer = Arrays.stream(instrumentation.getAllLoadedClasses())
                    .filter(c -> c.getName().equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
                loader = renderer.getClassLoader();
                var mcClass = type("net.minecraft.client.Minecraft");
                Object mc = mcClass.getMethod("getInstance").invoke(null);
                for (int i = 0; i < 16; i++) {
                    var result = new CompletableFuture<Map<String,Object>>();
                    final boolean gpu = i == 0 || i == 7 || i == 15;
                    mcClass.getMethod("execute", Runnable.class).invoke(mc, (Runnable) () -> {
                        try { result.complete(snapshot(mc, renderer, gpu)); }
                        catch (Throwable error) { result.complete(Map.of("error", error.toString(), "time", Instant.now().toString())); }
                    });
                    try { Files.writeString(output.resolve("runtime.jsonl"), json(result.get(10, TimeUnit.SECONDS)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
                    catch (TimeoutException timeout) { Files.writeString(output.resolve("runtime.jsonl"), json(Map.of("error","render queue timeout","time",Instant.now().toString()))+"\n", StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
                    if (i < 15) Thread.sleep(2000);
                }
                Files.writeString(output.resolve("complete.txt"), Instant.now().toString());
            } catch (Throwable error) {
                try { Files.writeString(Path.of(directory).resolve("error.txt"), error.toString()); } catch (Exception ignored) { }
            }
        }, "VSS-readonly-cache-observer");
        thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); thread.start();
    }
    static Class<?> type(String name) throws Exception { return Class.forName(name, false, loader); }
    static Object field(Class<?> cls,Object object,String name) throws Exception {
        var f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    static Object field(Object object,String name) throws Exception { return field(object.getClass(),object,name); }
    static Object invoke(Object object,String name) throws Exception {
        var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);
    }
    static Map<String,Object> scalars(Class<?> cls,Object object) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        for(var f:cls.getDeclaredFields()) {
            if(Modifier.isStatic(f.getModifiers()) != (object==null))continue;
            f.setAccessible(true);Object v=f.get(object);
            if(v instanceof AtomicLong n) result.put(f.getName(),n.get());
            else if(v instanceof AtomicInteger n) result.put(f.getName(),n.get());
            else if(v instanceof LongAdder n) result.put(f.getName(),n.sum());
            else if(v instanceof Number || v instanceof Boolean) result.put(f.getName(),v);
        }
        return result;
    }
    static Map<String,Object> snapshot(Object mc,Class<?> renderer,boolean gpu) throws Exception {
        long begin=System.nanoTime();
        var data=new LinkedHashMap<String,Object>();data.put("time",Instant.now().toString());data.put("nanoTime",begin);
        data.put("fps",mc.getClass().getMethod("getFps").invoke(mc));
        data.put("paused",mc.getClass().getMethod("isPaused").invoke(mc));
        data.put("active",mc.getClass().getMethod("isWindowActive").invoke(mc));
        Object screen=field(mc,"screen");data.put("screen",screen==null?null:screen.getClass().getName());
        var rt=Runtime.getRuntime();data.put("heapUsed",rt.totalMemory()-rt.freeMemory());data.put("heapMax",rt.maxMemory());
        data.put("renderer",scalars(renderer,null));
        var source=renderer.getProtectionDomain().getCodeSource();data.put("rendererCodeSource",source==null?null:source.getLocation().toString());
        var properties=new LinkedHashMap<String,Object>();
        for(String key:List.of("vss.disablePredictionVegetationLod","vss.disablePredictionAquaticLod","vss.disablePredictionGpuScene")) properties.put(key,System.getProperty(key));
        data.put("properties",properties);
        var managers=(Map<?,?>)field(type("dev.xantha.vss.client.prediction.ClientPredictionState"),null,"MANAGERS");
        var states=new ArrayList<Map<String,Object>>();
        for(var manager:managers.values()) {
            var state=scalars(manager.getClass(),manager);
            for(String key:List.of("ready","pending","cachePending","cacheCandidates","deferredUntil","cacheMissUntil")) {
                Object value=field(manager,key);state.put(key+"Count",value instanceof Map<?,?> map?map.size():((Collection<?>)value).size());
            }
            for(String key:List.of("executor","cacheExecutor")) {
                var pool=(ThreadPoolExecutor)field(manager,key);state.put(key,Map.of("active",pool.getActiveCount(),"queued",pool.getQueue().size(),"completed",pool.getCompletedTaskCount()));
            }
            Object disk=field(manager,"diskCache");
            if(disk!=null) {
                var stats=scalars(disk.getClass(),disk);stats.put("root",field(disk,"root").toString());state.put("disk",stats);
            }
            states.add(state);
        }
        data.put("managers",states);
        var mx=ManagementFactory.getThreadMXBean();
        var threads=new ArrayList<Map<String,Object>>();
        for(long id:mx.getAllThreadIds()) {
            var info=mx.getThreadInfo(id);
            if(info!=null && (info.getThreadName().startsWith("vss-")||info.getThreadName().equals("Render thread")))
                threads.add(Map.of("id",id,"name",info.getThreadName(),"state",info.getThreadState().toString(),"cpuNanos",mx.getThreadCpuTime(id)));
        }
        data.put("threads",threads);
        if(gpu) {
            var plants=new LinkedHashMap<String,Object>();
            Object batch=field(renderer,null,"indirectBatch");
            if(batch!=null) {
                plants.put("batch",scalars(batch.getClass(),batch));
                Object scene=field(batch,"gpuScene");
                if(scene!=null) {
                    plants.put("scene",scalars(scene.getClass(),scene));
                    List<?> nodes=(List<?>)field(scene,"uploadedNodes");
                    int capacity=((Number)field(scene,"nodeCapacity")).intValue();
                    int buffer=((Number)field(scene,"choiceBuffer")).intValue();
                    int[] choices=new int[capacity];
                    if(buffer!=0 && capacity>0)type("org.lwjgl.opengl.GL45C").getMethod("glGetNamedBufferSubData",int.class,long.class,int[].class).invoke(null,buffer,0L,choices);
                    int[] tiers=new int[5],keptTiers=new int[5];int enabled=0,withSmall=0,keptSmall=0;
                    var items=new ArrayList<Map<String,Object>>();
                    for(Object node:nodes) {
                        int index=((Number)invoke(node,"index")).intValue(),flags=((Number)invoke(node,"representations")).intValue();
                        int choice=choices[index],tier=(choice>>>3)&7;
                        if((flags&8)!=0)enabled++;
                        if(tier<5){tiers[tier]++;if((choice&1)!=0)keptTiers[tier]++;}
                        Object draw=invoke(node,"draw"),tileGpu=invoke(draw,"gpu"),packed=field(tileGpu,"packed");
                        boolean small=packed!=null && Boolean.TRUE.equals(invoke(packed,"hasGroundCoverLod"));
                        if(small){withSmall++;if((choice&1)!=0)keptSmall++;}
                        if(small && items.size()<200)items.add(Map.of("node",index,"flags",flags,"choice",Integer.toUnsignedLong(choice),"tier",tier,"keep",(choice&1)!=0,"bounds",invoke(draw,"bounds").toString()));
                    }
                    plants.put("nodes",nodes.size());plants.put("enabledNodes",enabled);plants.put("smallPlantNodes",withSmall);plants.put("keptSmallPlantNodes",keptSmall);plants.put("tiers",tiers);plants.put("keptTiers",keptTiers);plants.put("smallPlantExamples",items);
                }
            }
            data.put("vegetation",plants);
        }
        data.put("observationMillis",(System.nanoTime()-begin)/1e6);return data;
    }
    static String json(Object v) {
        if(v==null)return "null";
        if(v instanceof Map<?,?> m){var list=new ArrayList<String>();m.forEach((k,x)->list.add(json(k.toString())+":"+json(x)));return "{"+String.join(",",list)+"}";}
        if(v instanceof Collection<?> c)return "["+String.join(",",c.stream().map(CacheReadObserver::json).toList())+"]";
        if(v instanceof int[] a)return "["+String.join(",",Arrays.stream(a).mapToObj(Integer::toString).toList())+"]";
        if(v instanceof Number || v instanceof Boolean)return v.toString();
        return "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
    }
}
