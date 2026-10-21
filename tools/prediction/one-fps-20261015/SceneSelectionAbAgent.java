import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Temporarily bypasses the new GPU selection path, then restores its original property. */
public class SceneSelectionAbAgent {
    private static final String PROPERTY="vss.disableGpuPredictionSelection";
    public static void main(String[] args) throws Exception {
        var vm=VirtualMachine.attach(args[0]);
        try {vm.loadAgent(args[1],args[2]);} finally {vm.detach();}
    }
    static Object field(Class<?> type,Object object,String name) throws ReflectiveOperationException {
        var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    static String quote(Object value) {
        return value==null?"null":"\""+value.toString().replace("\\","\\\\").replace("\"","\\\"")+"\"";
    }
    public static void agentmain(String directory,Instrumentation inst) {
        var thread=new Thread(()->{
            String previous=System.getProperty(PROPERTY);
            var output=Path.of(directory);
            try {
                Files.createDirectories(output);
                var classes=Arrays.asList(inst.getAllLoadedClasses());
                var mc=classes.stream().filter(c->c.getName().equals("net.minecraft.client.Minecraft")).findFirst().orElseThrow();
                var renderer=classes.stream().filter(c->c.getName().equals("dev.xantha.vss.client.prediction.PredictionRenderer")).findFirst().orElseThrow();
                var client=mc.getMethod("getInstance").invoke(null);
                for(int i=0;i<=30;i++) {
                    if(i==6) System.setProperty(PROPERTY,"true");
                    if(i==24) {if(previous==null)System.clearProperty(PROPERTY);else System.setProperty(PROPERTY,previous);}
                    var values=new ArrayList<String>();
                    values.add("\"time\":"+quote(Instant.now()));
                    values.add("\"phase\":"+quote(i<6?"original":i<24?"bypass":"restored"));
                    values.add("\"nanoTime\":"+System.nanoTime());
                    values.add("\"fps\":"+mc.getMethod("getFps").invoke(client));
                    values.add("\"paused\":"+mc.getMethod("isPaused").invoke(client));
                    values.add("\"active\":"+mc.getMethod("isWindowActive").invoke(client));
                    Object screen=field(mc,client,"screen");
                    values.add("\"screen\":"+quote(screen==null?null:screen.getClass().getName()));
                    for(String name:List.of("renderFrames","frameStarts","drawCalls","indirectCalls"))
                        values.add(quote(name)+":"+field(renderer,null,name));
                    Object entity=mc.getMethod("getCameraEntity").invoke(client);
                    if(entity!=null)for(String name:List.of("getX","getY","getZ","getXRot","getYRot"))
                        values.add(quote(name)+":"+entity.getClass().getMethod(name).invoke(entity));
                    Files.writeString(output.resolve("selection-ab.jsonl"),"{"+String.join(",",values)+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    if(i<30)Thread.sleep(1000);
                }
            } catch(Throwable error) {
                try {Files.writeString(output.resolve("selection-ab-error.txt"),error.toString());}catch(Exception ignored){}
            } finally {
                if(previous==null)System.clearProperty(PROPERTY);else System.setProperty(PROPERTY,previous);
                try {Files.writeString(output.resolve("selection-ab-restored.txt"),Instant.now()+"\noriginal="+previous+"\nrestored="+System.getProperty(PROPERTY)+"\n");}catch(Exception ignored){}
            }
        },"VSS-bounded-scene-ab");
        thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);thread.start();
    }
}
