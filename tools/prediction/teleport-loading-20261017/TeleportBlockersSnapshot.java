import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class TeleportBlockersSnapshot extends TeleportLoadingSnapshot {
    static Object invoke(Object object,String name,Object...args)throws Exception {
        Method m=Arrays.stream(object.getClass().getDeclaredMethods()).filter(method->method.getName().equals(name)
                &&method.getParameterCount()==args.length).findFirst().orElseThrow();
        m.setAccessible(true);return m.invoke(object,args);
    }
    public static void agentmain(String output,Instrumentation inst)throws Exception {
        Class<?> state=Arrays.stream(inst.getAllLoadedClasses()).filter(c->c.getName()
                .equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
        Class<?> mcType=state.getClassLoader().loadClass("net.minecraft.client.Minecraft");
        Object mc=mcType.getMethod("getInstance").invoke(null);var done=new CompletableFuture<String>();
        mcType.getMethod("execute",Runnable.class).invoke(mc,(Runnable)()->{
            try {
                var out=new StringBuilder("paused=").append(call(mc,"isPaused"));
                for(Object manager:((Map<?,?>)field(state,"MANAGERS")).values()) {
                    Map<?,?> ready=(Map<?,?>)field(manager,"ready");
                    Set<?> surfaces=(Set<?>)field(manager,"surfaceDesired"), surfaceReady=(Set<?>)field(manager,"surfaceReady");
                    Object medium=field(manager,"mediumCoverage");
                    out.append("\nmediumFrontier=").append(invoke(medium,"frontier"));
                    int printed=0;Map<String,Integer> counts=new TreeMap<>();
                    for(Object key:(List<?>)field(manager,"scheduledPlan")) {
                        Object tile=ready.get(key);int axis=tile==null?0:(int)invoke(tile,"cellAxis");
                        int target=(int)invoke(manager,"targetCellAxis",key);
                        boolean terrain=(boolean)invoke(manager,"terrainBuildNeeded",key);
                        boolean surface=surfaces.contains(key)&&!surfaceReady.contains(key);
                        if(!terrain&&!surface)continue;
                        boolean parent=(boolean)invoke(manager,"terrainParentReady",key);
                        boolean mediumAllowed=(boolean)invoke(manager,"mediumWorkAllowed",key,surface&&!terrain);
                        boolean retry=(boolean)invoke(manager,"retryReady",key);
                        boolean defer=(boolean)invoke(manager,"deferBackground",key,surface&&!terrain);
                        boolean surfaceAllowed=!surface||(boolean)invoke(manager,"surfaceBuildReady",key);
                        String reason=!retry?"retry":defer?"background":!mediumAllowed?"medium":!parent?"parent"
                                :!terrain&&!surfaceAllowed?"surface-needs-terrain":"admissible";
                        counts.merge((terrain?"terrain:":"surface:")+reason,1,Integer::sum);
                        if(printed++<65)out.append("\n").append(key).append(" axis=").append(axis).append(" target=").append(target)
                                .append(" terrain=").append(terrain).append(" surface=").append(surface).append(" blocked=").append(reason);
                    }
                    out.append("\nblockerCounts=").append(counts);
                }
                done.complete(out.toString());
            }catch(Throwable error){done.complete(error+"\n"+Arrays.toString(error.getStackTrace()));}
        });
        Files.writeString(Path.of(output),done.get(10,TimeUnit.SECONDS));
    }
}
