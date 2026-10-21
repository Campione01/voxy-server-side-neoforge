import jdk.jfr.EventType;
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline analysis only. Categories are inclusive except explicit phase fields. */
public final class CacheJfrReport {
    static final String PREFIX = "dev.xantha.vss.client.prediction.";
    static final Map<String, Long> counts = new TreeMap<>();
    static final Map<String, Profile> profiles = new TreeMap<>();
    static final Map<Long, Allocation> allocations = new TreeMap<>();
    static final Map<String, Map<String, Long>> seconds = new TreeMap<>();
    static final Map<String, Long> exceptionClasses = new TreeMap<>(), exceptionTop = new TreeMap<>();
    static final List<Map<String, Object>> reads = new ArrayList<>(), writes = new ArrayList<>(), settings = new ArrayList<>();
    static final List<Map<String, Object>> heaps = new ArrayList<>();
    static final Map<Long, String> eventTypes = new TreeMap<>();
    static Instant first, last;
    static long gcCount, gcNanos, gcMax;
    static final class Allocation {
        String name; long first, last, firstNanos, lastNanos; int points;
        Allocation(String name, long value, long time) { this.name=name; first=last=value; firstNanos=lastNanos=time; }
    }
    static final class Profile {
        String thread; long id, samples, meshReadSamples, baseReadSamples, fullReadSamples, terrainReadSamples, allocationSamples, allocationWeight, firstAllocationWeight;
        final Map<String, Long> inclusive = new TreeMap<>(), leaves = new TreeMap<>(), meshPhases = new TreeMap<>(), meshLeaves = new TreeMap<>(), allocationInclusive = new TreeMap<>(), allocationLeaves = new TreeMap<>(), waitsNanos = new TreeMap<>(), waitCounts = new TreeMap<>();
        Profile(String name, long number) { thread=name;id=number; }
        Map<String, Object> json() {
            var out = new LinkedHashMap<String,Object>();
            out.put("thread",thread);out.put("id",id);out.put("samples",samples);
            out.put("meshReadSamples",meshReadSamples);out.put("baseMeshReadSamples",baseReadSamples);out.put("fullMeshReadSamples",fullReadSamples);out.put("terrainReadSamples",terrainReadSamples);
            out.put("inclusive",top(inclusive,45));out.put("leaves",top(leaves,30));out.put("meshReadExclusivePhases",meshPhases);out.put("meshReadLeaves",top(meshLeaves,20));
            out.put("allocationSamples",allocationSamples);out.put("allocationSampleWeight",allocationWeight);out.put("firstAllocationSampleWeight",firstAllocationWeight);
            out.put("allocationInclusiveWeight",top(allocationInclusive,25));out.put("allocationLeavesWeight",top(allocationLeaves,20));out.put("waitNanos",top(waitsNanos,12));out.put("waitCounts",top(waitCounts,12));return out;
        }
    }
    public static void main(String[] args) throws Exception {
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            for (EventType type : recording.readEventTypes()) eventTypes.put(type.getId(), type.getName());
            while(recording.hasMoreEvents()) {
                var e=recording.readEvent();String type=e.getEventType().getName();add(counts,type,1);
                if(first==null||e.getStartTime().isBefore(first))first=e.getStartTime();if(last==null||e.getEndTime().isAfter(last))last=e.getEndTime();
                if(type.equals("jdk.ActiveSetting")) {
                    String event=eventTypes.getOrDefault(e.getLong("id"), "unknown");
                    if(Set.of("jdk.FileRead","jdk.FileWrite","jdk.ExecutionSample","jdk.NativeMethodSample","jdk.ThreadAllocationStatistics","jdk.ObjectAllocationSample","jdk.JavaMonitorEnter","jdk.ThreadPark").contains(event))settings.add(Map.of("event",event,"name",e.getString("name"),"value",e.getString("value")));
                }
                if(type.equals("jdk.ExecutionSample")||type.equals("jdk.NativeMethodSample")) sample(e);
                else if(type.equals("jdk.ObjectAllocationSample")) allocation(e);
                else if(type.equals("jdk.ThreadAllocationStatistics")) allocationStat(e);
                else if(type.equals("jdk.FileRead")||type.equals("jdk.FileWrite")) io(e,type);
                else if(type.equals("jdk.ThreadPark")||type.equals("jdk.JavaMonitorEnter")||type.equals("jdk.JavaMonitorWait")) waitEvent(e,type);
                else if(type.equals("jdk.JavaExceptionThrow") && e.getStackTrace()!=null) {
                    var methods=methods(e);if(methods.stream().anyMatch(s->s.startsWith(PREFIX))) {
                        add(exceptionClasses,e.getClass("thrownClass").getName()+":"+e.getString("message"),1);
                        add(exceptionTop,methods.isEmpty()?"none":methods.getFirst(),1);
                    }
                } else if(type.equals("jdk.GCHeapSummary")) {
                    heaps.add(Map.of("time",e.getStartTime().toString(),"when",e.getString("when"),"used",e.getLong("heapUsed"),"gcId",e.getLong("gcId")));
                } else if(type.equals("jdk.GCPhasePause")) { gcCount++;long n=e.getDuration().toNanos();gcNanos+=n;gcMax=Math.max(gcMax,n); }
            }
        }
        var output=new LinkedHashMap<String,Object>();output.put("start",first.toString());output.put("end",last.toString());output.put("durationSeconds",Duration.between(first,last).toNanos()/1e9);output.put("events",counts);output.put("activeSettings",settings);
        output.put("profiles",profiles.values().stream().map(Profile::json).toList());output.put("seconds",seconds);output.put("fileReads",reads);output.put("fileWrites",writes);output.put("vssExceptionClasses",top(exceptionClasses,20));output.put("vssExceptionTop",top(exceptionTop,20));output.put("heap",heaps);output.put("gc",Map.of("count",gcCount,"pauseNanos",gcNanos,"maxPauseNanos",gcMax));
        var allocationOut=new ArrayList<Map<String,Object>>();
        for(var entry:allocations.entrySet()) {var a=entry.getValue();allocationOut.add(Map.of("id",entry.getKey(),"thread",a.name,"points",a.points,"durationSeconds",(a.lastNanos-a.firstNanos)/1e9,"bytes",Math.max(0,a.last-a.first)));}output.put("threadAllocationDeltas",allocationOut);
        Files.writeString(Path.of(args[1]),json(output));System.out.println("Wrote "+args[1]+"; samples="+profiles.values().stream().mapToLong(p->p.samples).sum());
    }
    static RecordedThread thread(RecordedEvent e) { for(String field:List.of("sampledThread","thread","eventThread"))if(e.hasField(field)) {var t=e.getThread(field);if(t!=null)return t;}return null; }
    static Profile profile(RecordedThread t) { if(t==null)return null;return profiles.computeIfAbsent(t.getJavaName()+"#"+t.getJavaThreadId(),ignored->new Profile(t.getJavaName(),t.getJavaThreadId())); }
    static List<String> methods(RecordedEvent e) {var trace=e.getStackTrace();if(trace==null)return List.of();return trace.getFrames().stream().map(f->f.getMethod().getType().getName()+"."+f.getMethod().getName()).toList();}
    static boolean contains(List<String> methods,String name){return methods.stream().anyMatch(m->m.contains(name));}
    static void sample(RecordedEvent e) {
        var t=thread(e);var p=profile(t);if(p==null)return;var m=methods(e);p.samples++;
        boolean mesh=contains(m,"PredictionDiskCache.readMesh"),base=contains(m,"PredictionDiskCache.readMeshBase"),terrain=contains(m,"PredictionDiskCache.readTerrain");
        if(mesh){p.meshReadSamples++;if(base)p.baseReadSamples++;else p.fullReadSamples++;add(p.meshPhases,phase(m),1);if(!m.isEmpty())add(p.meshLeaves,m.getFirst(),1);}if(terrain)p.terrainReadSamples++;
        for(String method:new HashSet<>(m))if(method.startsWith(PREFIX)||method.startsWith("java.util.zip."))add(p.inclusive,method,1);
        if(!m.isEmpty())add(p.leaves,m.getFirst(),1);
        if(p.thread.startsWith("vss-")||p.thread.equals("Render thread")) {String key=e.getStartTime().toString().substring(0,19);var sec=seconds.computeIfAbsent(key,ignored->new TreeMap<>());add(sec,p.thread,1);if(mesh)add(sec,"meshRead",1);if(base)add(sec,"baseMeshRead",1);if(terrain)add(sec,"terrainRead",1);if(contains(m,"PredictionScanMerge"))add(sec,"planeMerge",1);if(contains(m,"PredictionDisplayGeometry"))add(sec,"displayLod",1);}
    }
    static String phase(List<String> m) { for(String method:m) {if(method.contains("PredictionScanMerge")||method.contains("PredictionScanMesher2D"))return "planeMerge";if(method.contains("PredictionDisplayGeometry"))return "displayLod";if(method.contains("PredictionSpatialOrder")||method.contains("PredictionVegetationRanges"))return "rangeGrouping";if(method.contains("PredictionMeshletBounds")||method.contains("PredictionOwnership"))return "boundsOwnership";if(method.contains("VssLodSpriteTable")||method.contains("PredictionMaterialPalette"))return "materials";if(method.contains("Inflater"))return "inflate";if(method.contains("PredictionRegionStorage"))return "regionRead";if(method.contains("PredictionPackedMesh"))return "otherPackedMesh";if(method.contains("PredictionMeshCodec"))return "parseMesh";}return "other"; }
    static void allocation(RecordedEvent e) {var p=profile(thread(e));if(p==null)return;long weight=e.getLong("weight");p.allocationWeight+=weight;if(p.allocationSamples++==0)p.firstAllocationWeight=weight;var m=methods(e);String klass=e.getClass("objectClass").getName();if(!m.isEmpty())add(p.allocationLeaves,klass+" @ "+m.getFirst(),weight);for(String method:new HashSet<>(m))if(method.startsWith(PREFIX))add(p.allocationInclusive,method,weight);}
    static void allocationStat(RecordedEvent e) {var t=thread(e);if(t==null)return;long n=e.getLong("allocated"),time=e.getStartTime().getEpochSecond()*1_000_000_000L+e.getStartTime().getNano();var a=allocations.computeIfAbsent(t.getJavaThreadId(),ignored->new Allocation(t.getJavaName(),n,time));a.points++;a.last=n;a.lastNanos=time;}
    static void waitEvent(RecordedEvent e,String type){var p=profile(thread(e));if(p==null)return;var m=methods(e);String firstVss=m.stream().filter(s->s.startsWith(PREFIX)).findFirst().orElse("other");add(p.waitsNanos,type+":"+firstVss,e.getDuration().toNanos());add(p.waitCounts,type+":"+firstVss,1);}
    static void io(RecordedEvent e,String type){var t=thread(e);var row=new LinkedHashMap<String,Object>();row.put("time",e.getStartTime().toString());row.put("path",e.getString("path"));row.put("bytes",e.getLong(type.equals("jdk.FileRead")?"bytesRead":"bytesWritten"));row.put("nanos",e.getDuration().toNanos());row.put("thread",t==null?null:t.getJavaName());row.put("stack",methods(e));(type.equals("jdk.FileRead")?reads:writes).add(row);}
    static Map<String,Long> top(Map<String,Long> map,int limit){var out=new LinkedHashMap<String,Long>();map.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(limit).forEach(e->out.put(e.getKey(),e.getValue()));return out;}
    static void add(Map<String,Long> map,String key,long value){map.merge(key,value,Long::sum);}
    static String json(Object o) {if(o==null)return "null";if(o instanceof Number||o instanceof Boolean)return o.toString();if(o instanceof Map<?,?> m){var out=new ArrayList<String>();m.forEach((k,v)->out.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",out)+"}";}if(o instanceof Collection<?> list)return "["+String.join(",",list.stream().map(CacheJfrReport::json).toList())+"]";return "\""+o.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
}
