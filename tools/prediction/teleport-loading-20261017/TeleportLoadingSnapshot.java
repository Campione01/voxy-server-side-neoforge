import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, read-only client-thread snapshots. No class changes, world writes or GL calls. */
public class TeleportLoadingSnapshot {
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
    static String executor(Object value) {
        ThreadPoolExecutor e = (ThreadPoolExecutor)value;
        return "active="+e.getActiveCount()+",queued="+e.getQueue().size()+",completed="+e.getCompletedTaskCount();
    }
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        String[] options=args.split("\\|",2);
        Path output=Path.of(options[0]); int count=options.length>1?Integer.parseInt(options[1]):1;
        Class<?> state = Arrays.stream(inst.getAllLoadedClasses()).filter(c -> c.getName()
                .equals("dev.xantha.vss.client.prediction.ClientPredictionState")).findFirst().orElseThrow();
        Class<?> mcClass = state.getClassLoader().loadClass("net.minecraft.client.Minecraft");
        Object mc=mcClass.getMethod("getInstance").invoke(null);
        Files.writeString(output, "pid="+ProcessHandle.current().pid()+"\n");
        for(int index=0;index<count;index++) {
            var result=new CompletableFuture<String>();
            final boolean detailed=index==0||index==count-1;
            mcClass.getMethod("execute",Runnable.class).invoke(mc,(Runnable)()->{
                try {
                    var out=new StringBuilder("at=").append(Instant.now()).append(" paused=")
                            .append(call(mc,"isPaused")).append(" fps=").append(call(mc,"getFps"));
                    Map<?,?> managers=(Map<?,?>)field(state,"MANAGERS");
                    for(var item:managers.entrySet()) {
                        Object m=item.getValue();
                        out.append("\ndimension=").append(item.getKey()).append(" camera=")
                                .append(field(m,"cameraBlockX")).append(",").append(field(m,"cameraBlockY"))
                                .append(",").append(field(m,"cameraBlockZ"));
                        out.append("\ndemand=").append(call(m,"demandSnapshot"));
                        out.append("\nexecutor={").append(executor(field(m,"executor"))).append("} cacheExecutor={")
                                .append(executor(field(m,"cacheExecutor"))).append("}");
                        for(String name:List.of("memoryBudget","cpuBudget"))
                            out.append("\n").append(name).append("={").append(call(field(m,name),"diagnostics")).append("}");
                        for(String name:List.of("foregroundWorkPending","previewWorkPending","mediumCoveragePending",
                                "scheduleAgain","viewCompletionRecorded","viewStartedNanos"))
                            out.append("\n").append(name).append("=").append(field(m,name));
                        out.append("\nloading=").append(call(field(m,"loadingProgress"),"view"));
                        for(String name:List.of("preparedWork","cacheCandidates","preparedReclaim","fallbackKeys",
                                "backgroundPending","refinementPending","surfacePending","failedAt","deferredUntil",
                                "desiredGrace","scheduledPlan","scheduledLeaves")) {
                            Object value=field(m,name);
                            out.append("\n").append(name).append("=")
                                    .append(value instanceof Map<?,?> map?map.size():((Collection<?>)value).size());
                        }
                        if(detailed) out.append("\nsurface=").append(call(m,"surfaceDiagnostics"));
                    }
                    result.complete(out.append("\n\n").toString());
                } catch(Throwable error) { result.complete(error+"\n"+Arrays.toString(error.getStackTrace())); }
            });
            Files.writeString(output,result.get(10,TimeUnit.SECONDS),StandardOpenOption.APPEND);
            if(index+1<count) Thread.sleep(2000);
        }
    }
}
