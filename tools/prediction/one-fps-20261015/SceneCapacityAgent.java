import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded metadata observation. Does not transform classes or call GL/world APIs. */
public class SceneCapacityAgent {
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1],args[2]); } finally { vm.detach(); }
    }
    static Object read(Object target,String name) throws ReflectiveOperationException {
        Class<?> type=target instanceof Class<?> c?c:target.getClass();
        var field=type.getDeclaredField(name);field.setAccessible(true);
        return field.get(target instanceof Class<?>?null:target);
    }
    static Object call(Object target,String name) throws ReflectiveOperationException {
        var method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(target);
    }
    public static void agentmain(String directory,Instrumentation inst) {
        var thread=new Thread(()->{
            try {
                var output=Path.of(directory);Files.createDirectories(output);
                var renderer=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName().equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
                for(int i=0;i<40;i++) {
                    var fields=new ArrayList<String>();fields.add("time="+Instant.now());
                    Object batch=read(renderer,"indirectBatch");
                    if(batch!=null) {
                        for(String name:List.of("gpuSelection","selectionFailed","plannedSelection","encodedItems","uploadedBytes","lastOpaqueFrame","lastMdiCommands","lastDirectTiles"))
                            fields.add(name+"="+read(batch,name));
                        Object scene=read(batch,"gpuScene");
                        if(scene!=null) {
                            for(String name:List.of("frame","rejected","encodedItems","uploadedBytes","removedItems","preparationHits","nodeCapacity"))
                                fields.add("scene."+name+"="+read(scene,name));
                            for(String name:List.of("entries","regions","plans")) fields.add("scene."+name+"="+((Map<?,?>)read(scene,name)).size());
                            Object input=read(scene,"source");
                            if(input instanceof List<?> source) {
                                fields.add("scene.source="+source.size());
                                long quads=0,runs=0,meshlets=0;int count=0;
                                for(Object draw:source) {
                                    Object gpu=call(draw,"gpu"),packed=call(gpu,"packed");if(packed==null) continue;
                                    Object bounds=read(packed,"occlusionBounds");
                                    quads+=((Number)call(packed,"quadCount")).longValue();
                                    runs+=((int[])read(bounds,"renderEnds")).length;
                                    meshlets+=((float[])read(bounds,"values")).length/6;
                                    count++;
                                }
                                fields.add("source.quads="+quads);fields.add("source.kindRuns="+runs);
                                fields.add("source.meshlets="+meshlets);fields.add("source.packed="+count);
                            }
                        }
                    }
                    Files.writeString(output.resolve("scene-capacity.txt"),String.join("\t",fields)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    if(i<39) Thread.sleep(500);
                }
                Files.writeString(output.resolve("scene-observer-complete.txt"),Instant.now().toString());
            } catch(Throwable error) {
                try {Files.writeString(Path.of(directory).resolve("scene-agent-error.txt"),error.toString());} catch(Exception ignored){}
            }
        },"VSS-readonly-scene-observer");
        thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);thread.start();
    }
}
