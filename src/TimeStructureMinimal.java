import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.DoubleToIntFunction;

/** Offline-only replay: no DEM, solar-position or visibility query is made here. */
public final class TimeStructureMinimal {
    private static final double EPS = 1e-8;
    private static final String[] METHODS = {"binarylocal30", "two_boundary_assumption", "two_window_assumption"};
    private record Case(String id, String source, String coordinates, String date, String reason,
                        NavigableMap<Double,Integer> reference, boolean synthetic) {}
    private static String q(Object value) {
        String s = String.valueOf(value);
        return '"' + s.replace("\"", "\"\"") + '"';
    }
    private static void csv(PrintWriter w, Object... fields) {
        for (int i=0; i<fields.length; i++) { if (i>0) w.print(','); w.print(q(fields[i])); }
        w.println();
    }
    private static String sha(Path p) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try (InputStream in=Files.newInputStream(p)) {
            byte[] b=new byte[65536]; int n;
            while ((n=in.read(b))!=-1) md.update(b,0,n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static void require(boolean ok, String msg) { if (!ok) throw new IllegalStateException(msg); }
    private static String[] split(String line) { return line.split(",", -1); }
    private static Map<String,NavigableMap<Double,Integer>> readTraces(Path p, String expectedHeader) throws IOException {
        Map<String,NavigableMap<Double,Integer>> groups=new LinkedHashMap<>();
        try (BufferedReader r=Files.newBufferedReader(p,StandardCharsets.UTF_8)) {
            require(expectedHeader.equals(r.readLine()), "unexpected header: "+p);
            String line;
            while ((line=r.readLine())!=null) {
                String[] x=split(line); require(x.length>=4,"bad trace row: "+p);
                if (x.length>4) require("1".equals(x[4]),"reference node excluded from integration: "+p);
                double t=Double.parseDouble(x[2]); int s=x[3].equals("U") ? -1 : Integer.parseInt(x[3]);
                require(Double.isFinite(t) && t>=0 && (s==-1 || s==0 || s==1),"invalid node: "+p);
                NavigableMap<Double,Integer> m=groups.computeIfAbsent(x[0]+"_"+x[1], k->new TreeMap<>());
                require(m.putIfAbsent(t,s)==null,"duplicate node: "+p+" "+t);
            }
        }
        return groups;
    }
    private static void validate(NavigableMap<Double,Integer> m, String label) {
        require(!m.isEmpty() && m.firstKey()==0.0,"missing sunrise: "+label);
        double d=m.lastKey(); require(d>0 && d<=1440,"invalid sunset: "+label);
        require(m.size()==(int)Math.ceil(d)+1,"MISSING_DATA or extra nodes: "+label);
        for (int t=0;t<d;t++) require(m.containsKey((double)t),"MISSING_DATA at "+t+": "+label);
    }
    private static Case synthetic(String id,double d,DoubleToIntFunction f,String reason) {
        NavigableMap<Double,Integer> m=new TreeMap<>();
        for(int i=0;i<d;i++) m.put((double)i,f.applyAsInt(i));
        m.put(d,f.applyAsInt(d)); validate(m,id);
        return new Case(id,"SYNTHETIC","","",reason,m,true);
    }
    private static double integral(NavigableMap<Double,Integer> m) {
        double[] t=new double[m.size()]; int[] s=new int[m.size()]; int i=0;
        for (var e:m.entrySet()) { t[i]=e.getKey(); s[i++]=e.getValue(); }
        return TimeStepBaseline.integrate(t,s);
    }
    private static String number(double d) { return Double.isFinite(d) ? String.format(Locale.ROOT,"%.12f",d) : ""; }
    private static int intervals(Collection<Integer> v) {
        int n=0,prev=0; for(int s:v) { if(s<0) return -1; if(s==1 && prev!=1) n++; prev=s; } return n;
    }
    private static String category(NavigableMap<Double,Integer> m) {
        int n=intervals(m.values()); return n<0 ? "UNKNOWN" : n==0 ? "DARK" : n==1 ? "SINGLE" : n==2 ? "TWO" : "THREE_PLUS";
    }
    private static int reconstructed(NavigableMap<Double,Integer> nodes,double t) {
        var a=nodes.floorEntry(t); var b=nodes.ceilingEntry(t);
        if(a==null || b==null) throw new IllegalStateException("integration nodes missing endpoint");
        if(a.getKey().equals(b.getKey())) return a.getValue();
        if(a.getValue()<0 || b.getValue()<0) return -1;
        return t < (a.getKey()+b.getKey())/2 ? a.getValue() : b.getValue();
    }
    private static void structure(Path root,PrintWriter out,Set<Path> inputs) throws Exception {
        String[][] regions={
            {"huabei_spring_128","user_runs/huabei_spring_001/fixed_1min_r1/intervals.csv","16384","2023-03-21"},
            {"jianghuai_spring_128","user_runs/jianghuai_spring_001/fixed_1min_r1/intervals.csv","16384","2023-03-21"},
            {"qinling_winter_128","user_runs/qinling_spatial_ablation_001/fixed_1min_r1/intervals.csv","16384","2023-12-21"},
            {"qinling_winter_512","user_runs/qinling_512_reference_002/fixed_1min_r1/intervals.csv","262144","2023-12-21"}};
        csv(out,"region","date","source","category","count","total","proportion");
        for(String[] a:regions) {
            Path p=root.resolve(a[1]); inputs.add(p); Map<String,Integer> counts=new LinkedHashMap<>();
            for(String k:List.of("DARK","SINGLE","TWO","THREE_PLUS","UNKNOWN")) counts.put(k,0);
            Set<String> ids=new HashSet<>();
            try(BufferedReader r=Files.newBufferedReader(p,StandardCharsets.UTF_8)) {
                require("row,col,category,light_intervals,transitions,first_boundary_min,last_boundary_min,span_minutes,psd_minutes,internal_shade_minutes".equals(r.readLine()),"interval header "+p);
                String line; while((line=r.readLine())!=null) {
                    String[] x=split(line); require(x.length==10 && ids.add(x[0]+"_"+x[1]),"duplicate or malformed interval "+p);
                    int row=Integer.parseInt(x[0]), col=Integer.parseInt(x[1]); int width=Integer.parseInt(a[2])==16384 ? 128 : 512;
                    require(row>=114 && row<114+width && col>=114 && col<114+width,"out of grid "+p);
                    int n=Integer.parseInt(x[3]); String cat=x[2];
                    String k=cat.equals("UNKNOWN") ? "UNKNOWN" : cat.equals("DARK") && n==0 ? "DARK" : cat.equals("SINGLE") && n==1 ? "SINGLE" : cat.equals("MULTI") && n==2 ? "TWO" : cat.equals("MULTI") && n>=3 ? "THREE_PLUS" : "INVALID";
                    require(counts.containsKey(k),"invalid category "+p+" "+line); counts.put(k,counts.get(k)+1);
                }
            }
            require(ids.size()==Integer.parseInt(a[2]),"incomplete grid "+p);
            for(var e:counts.entrySet()) csv(out,a[0],a[3],a[1],e.getKey(),e.getValue(),ids.size(),number((double)e.getValue()/ids.size()));
        }
    }
    private static void addSaved(List<Case> cases, Path root, String path,String header,String grid,String date,Set<Path> inputs) throws Exception {
        Path p=root.resolve(path); inputs.add(p);
        Path psd=path.contains("minute_states_all_three")
                ? root.resolve("user_runs/qinling_winter_001/fixed_1min_r1/psd.csv")
                : p.getParent().resolve("psd.csv");
        inputs.add(psd); Map<String,String[]> expected=psdRows(psd);
        for(var e:readTraces(p,header).entrySet()) {
            String[] row=expected.get(e.getKey());
            require(row!=null && Math.abs(Double.parseDouble(row[5])-e.getValue().lastKey())<EPS,
                    "MISSING_DATA or sunset mismatch: "+path+" "+e.getKey());
            cases.add(new Case(grid+"_"+e.getKey(),path,e.getKey(),date,"available complete saved trajectory",e.getValue(),false));
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("TimeStructureMinimal <project-root> <new-output-directory>");
        Path root=Path.of(args[0]).toAbsolutePath().normalize(), output=Path.of(args[1]).toAbsolutePath().normalize();
        require(output.getParent().equals(root.resolve("user_runs")) && output.getFileName().toString().startsWith("time_structure_minimal_"),"output must be a new user_runs/time_structure_minimal_* directory");
        Files.createDirectory(output);
        Set<Path> inputs=new TreeSet<>(); List<Case> cases=new ArrayList<>();
        String traceHeader="row,col,minutes_since_sunrise,state,used_for_integration";
        addSaved(cases,root,"user_runs/interval_certificate_minimal_001/results/minute_states_all_three.csv","row,col,minute_since_sunrise,state","qinling128_winter","2023-12-21",inputs);
        addSaved(cases,root,"user_runs/qinling_512_reference_002/fixed_1min_r1/samples.csv",traceHeader,"qinling512_winter","2023-12-21",inputs);
        addSaved(cases,root,"user_runs/qinling_spring_001/fixed_1min_r1/samples.csv",traceHeader,"qinling128_spring","2023-03-21",inputs);
        addSaved(cases,root,"user_runs/huabei_spring_001/fixed_1min_r1/samples.csv",traceHeader,"huabei128_spring","2023-03-21",inputs);
        addSaved(cases,root,"user_runs/jianghuai_spring_001/fixed_1min_r1/samples.csv",traceHeader,"jianghuai128_spring","2023-03-21",inputs);
        cases.add(synthetic("all_light",120,t->1,"full daylight control"));
        cases.add(synthetic("all_dark",120,t->0,"dark exhaustive-search control"));
        cases.add(synthetic("single_midday",120,t->t>=13 && t<97?1:0,"single interval control"));
        cases.add(synthetic("two_midday_dark",120,t->(t>=10&&t<40)||(t>=70&&t<100)?1:0,"two intervals control"));
        cases.add(synthetic("midday_light_hidden_dark",120,t->t>=3&&t<5?0:1,"counterexample 1"));
        cases.add(synthetic("midday_dark_single",120,t->t>=5&&t<20?1:0,"counterexample 2"));
        cases.add(synthetic("two_without_midday_dark",120,t->t>=30&&t<35?0:1,"counterexample 3"));
        cases.add(synthetic("fractional_sunset",116.5,t->t>=110?1:0,"fractional sunset and bright endpoint"));
        cases.add(synthetic("unknown_midday",120,t->t==60?-1:1,"UNKNOWN is independent"));
        try(PrintWriter structure=new PrintWriter(Files.newBufferedWriter(output.resolve("structure_counts.csv"),StandardCharsets.UTF_8))) { structure(root,structure,inputs); require(!structure.checkError(),"structure write failure"); }
        runCases(root,output,cases,inputs);
    }
    private static int[] discrepancy(NavigableMap<Double,Integer> ref,NavigableMap<Double,Integer> nodes) {
        int diff=0; List<Integer> rebuilt=new ArrayList<>();
        for(var e:ref.entrySet()) {
            int v=reconstructed(nodes,e.getKey()); rebuilt.add(v);
            if(v!=e.getValue()) diff++;
        }
        return new int[]{diff,intervals(rebuilt)};
    }
    private record Window(double start,double end) {}
    private static List<Window> windows(NavigableMap<Double,Integer> nodes) {
        List<Map.Entry<Double,Integer>> entries=new ArrayList<>(nodes.entrySet()); List<Window> out=new ArrayList<>();
        double start=Double.NaN;
        for(int i=0;i<entries.size();i++) {
            int state=entries.get(i).getValue(); if(state<0) throw new IllegalArgumentException("UNKNOWN window");
            if(state==1 && (i==0 || entries.get(i-1).getValue()==0)) start=i==0?entries.get(i).getKey():(entries.get(i-1).getKey()+entries.get(i).getKey())/2;
            if(state==1 && (i==entries.size()-1 || entries.get(i+1).getValue()==0)) {
                double end=i==entries.size()-1?entries.get(i).getKey():(entries.get(i).getKey()+entries.get(i+1).getKey())/2;
                out.add(new Window(start,end));
            }
        }
        return out;
    }
    private static void compareWindows(PrintWriter out,String id,String method,NavigableMap<Double,Integer> ref,NavigableMap<Double,Integer> rebuilt) {
        List<Window> a=windows(ref),b=windows(rebuilt); int[] ac=new int[a.size()],bc=new int[b.size()];
        for(int i=0;i<a.size();i++) for(int j=0;j<b.size();j++) if(Math.min(a.get(i).end(),b.get(j).end())>Math.max(a.get(i).start(),b.get(j).start())) {ac[i]++;bc[j]++;}
        for(int i=0;i<a.size();i++) {
            Window x=a.get(i); int match=-1; for(int j=0;j<b.size();j++) if(Math.min(x.end(),b.get(j).end())>Math.max(x.start(),b.get(j).start())) match=j;
            if(ac[i]==0) csv(out,id,method,"OMITTED",i+1,"",number(x.start()),number(x.end()),"","","","",number(x.end()-x.start()),"");
            else if(ac[i]==1 && bc[match]==1) { Window y=b.get(match); csv(out,id,method,"MATCHED",i+1,match+1,number(x.start()),number(x.end()),number(y.start()),number(y.end()),number(y.start()-x.start()),number(y.end()-x.end()),number(x.end()-x.start()),number(y.end()-y.start())); }
            else csv(out,id,method,"AMBIGUOUS",i+1,"",number(x.start()),number(x.end()),"","","","",number(x.end()-x.start()),"");
        }
        for(int j=0;j<b.size();j++) if(bc[j]==0) {Window y=b.get(j);csv(out,id,method,"EXTRA","",j+1,"","",number(y.start()),number(y.end()),"","","",number(y.end()-y.start()));}
    }
    private static Set<Double> unique(TimeStructureSearch.Result r) {
        Set<Double> result=new HashSet<>();
        for(var query:r.queries) if(query.isNew) result.add(query.time);
        require(result.size()==r.unique(),"duplicate unique query");
        require(r.requests==r.hits+r.unique(),"request accounting");
        return result;
    }
    private static void runCases(Path root,Path output,List<Case> cases,Set<Path> inputs) throws Exception {
        TimeStructureSearch.syntheticChecks();
        Path oldComparison=root.resolve("user_runs/binary_refinement_checks_001/comparison.csv");
        Path oldSamples=root.resolve("user_runs/binary_refinement_checks_001/samples.csv");
        inputs.add(oldComparison); inputs.add(oldSamples);
        Path old128=root.resolve("user_runs/qinling_binary_local_001/binarylocal_30min_r1/psd.csv");
        Path ref128=root.resolve("user_runs/qinling_winter_001/fixed_1min_r1/psd.csv");
        inputs.add(old128); inputs.add(ref128);
        inputs.add(root.resolve("user_runs/internal_shadow_failure_001/diagnosis.json"));
        Path actual128=root.resolve("user_runs/internal_shadow_failure_001/actual_queries.csv");
        inputs.add(actual128); Map<String,List<String[]>> ordered128=new HashMap<>();
        try(BufferedReader r=Files.newBufferedReader(actual128,StandardCharsets.UTF_8)) {
            String header=r.readLine();
            if (header!=null && header.startsWith("\uFEFF")) header=header.substring(1);
            require(header!=null && header.startsWith("row,col,query_order,minute_since_sunrise,"),"128 ordered query header");
            String line; while((line=r.readLine())!=null) {
                String[] p=split(line); require(p.length>=8,"malformed 128 ordered query");
                List<String[]> list=ordered128.computeIfAbsent(p[0]+"_"+p[1],k->new ArrayList<>());
                require(Integer.parseInt(p[2])==list.size(),"invalid 128 query order"); list.add(p);
            }
        }
        Map<String,String[]> original128=psdRows(old128),reference128=psdRows(ref128);
        Map<String,String[]> oldRows=new HashMap<>();
        try(BufferedReader r=Files.newBufferedReader(oldComparison,StandardCharsets.UTF_8)) {
            require(r.readLine().startsWith("kind,case_id,method,fixed1_psd"),"historical comparison header");
            String line; while((line=r.readLine())!=null) { String[] p=split(line); if(p[0].equals("SAVED")&&p[2].equals("binarylocal")) oldRows.put(p[1],p); }
        }
        Map<String,NavigableMap<Double,Integer>> oldNodes=new HashMap<>();
        try(BufferedReader r=Files.newBufferedReader(oldSamples,StandardCharsets.UTF_8)) {
            require(r.readLine().startsWith("kind,case_id,method,minutes_since_sunrise"),"historical samples header");
            String line; while((line=r.readLine())!=null) { String[] p=split(line); if(p[0].equals("SAVED")&&p[2].equals("binarylocal")) {
                NavigableMap<Double,Integer> m=oldNodes.computeIfAbsent(p[1],k->new TreeMap<>());
                require(m.putIfAbsent(Double.parseDouble(p[3]),Integer.parseInt(p[4]))==null && p[5].equals("1"),"historical duplicate/nonintegrated");
            }}
        }
        int regressions=0; List<String> checkLines=new ArrayList<>();
        try(PrintWriter ca=new PrintWriter(Files.newBufferedWriter(output.resolve("cases.csv"),StandardCharsets.UTF_8));
            PrintWriter qu=new PrintWriter(Files.newBufferedWriter(output.resolve("queries.csv"),StandardCharsets.UTF_8));
            PrintWriter no=new PrintWriter(Files.newBufferedWriter(output.resolve("integration_nodes.csv"),StandardCharsets.UTF_8));
            PrintWriter wi=new PrintWriter(Files.newBufferedWriter(output.resolve("windows.csv"),StandardCharsets.UTF_8));
            PrintWriter comp=new PrintWriter(Files.newBufferedWriter(output.resolve("comparison.csv"),StandardCharsets.UTF_8))) {
            csv(ca,"case_id","kind","source","row_col","date","reason","day_minutes","category","light_intervals","midday_minute","midday_state","sample_count");
            csv(qu,"case_id","method","request_index","time","state","purpose","is_new");
            csv(no,"case_id","method","time","state");
            csv(wi,"case_id","method","match_status","reference_index","reconstructed_index","reference_start","reference_end","reconstructed_start","reconstructed_end","start_error","end_error","reference_width_minutes","reconstructed_width_minutes");
            csv(comp,"case_id","method","status","left","right","reference_psd","strategy_psd","signed_error","absolute_error","reference_windows","reconstructed_windows","window_count_difference","reconstructed_node_differences","requests","cache_hits","unique","anchor","boundary","other","fallback","baseline_unique","delta_q","delta_fraction","qualified","strict_extra_query_budget","rejected_then_baseline_union");
            int graphCount=0;
            for(Case c:cases) {
                NavigableMap<Double,Integer> ref=c.reference(); double day=ref.lastKey(), mid=Math.floor(day/2);
                try { validate(ref,c.id()); } catch (IllegalStateException missing) {
                    csv(ca,c.id(),c.synthetic()?"SYNTHETIC":"SAVED",c.source(),c.coordinates(),c.date(),"MISSING_DATA: "+missing.getMessage(),number(day),"MISSING_DATA","",number(mid),"",ref.size());
                    for(String method:METHODS) { Object[] row=new Object[26]; Arrays.fill(row,""); row[0]=c.id(); row[1]=method; row[2]="MISSING_DATA"; csv(comp,row); }
                    checkLines.add("MISSING_DATA "+missing.getMessage()); continue;
                }
                double reference=integral(ref); int nw=intervals(ref.values());
                csv(ca,c.id(),c.synthetic()?"SYNTHETIC":"SAVED",c.source(),c.coordinates(),c.date(),c.reason(),number(day),category(ref),nw,number(mid),ref.get(mid),ref.size());
                Map<String,TimeStructureSearch.Result> all=new LinkedHashMap<>();
                for(String method:METHODS) {
                    // Only the adapter sees the reference map; searches receive oracle access only.
                    AdaptiveTimeV1.Oracle oracle=t->{ Integer state=ref.get(t); if(state==null) throw new TimeStructureSearch.MissingData("missing minute "+t); return state; };
                    TimeStructureSearch.Result result=TimeStructureSearch.run(method,day,oracle); all.put(method,result); unique(result);
                    int seq=0; for(var query:result.queries) csv(qu,c.id(),method,++seq,number(query.time),query.state,query.purpose,query.isNew);
                    for(var node:result.nodes.entrySet()) csv(no,c.id(),method,number(node.getKey()),node.getValue());
                }
                TimeStructureSearch.Result baseline=all.get("binarylocal30"); int qb=baseline.unique();
                for(var entry:all.entrySet()) {
                    String method=entry.getKey(); TimeStructureSearch.Result x=entry.getValue(); boolean ok=x.status.equals("OK") && Double.isFinite(reference) && Double.isFinite(x.psd);
                    int[] d=ok?discrepancy(ref,x.nodes):new int[]{-1,-1}; double err=ok?x.psd-reference:Double.NaN;
                    if(ok) compareWindows(wi,c.id(),method,ref,x.nodes);
                    boolean qualified=ok && Math.abs(err)<EPS && d[0]==0 && d[1]==nw;
                    int delta=qb-x.unique(); String budget=qualified && delta>0?Integer.toString(delta-1):"";
                    Set<Double> union=unique(x); union.addAll(unique(baseline));
                    csv(comp,c.id(),method,x.status,x.left,x.right,number(reference),number(x.psd),number(err),number(Math.abs(err)),nw,ok?d[1]:"",ok?d[1]-nw:"",ok?d[0]:"",x.requests,x.hits,x.unique(),x.anchor,x.boundary,x.other,x.fallback,qb,delta,qb==0?"":number((double)delta/qb),qualified,budget,union.size());
                }
                String hist=c.id().startsWith("qinling512_winter_") ? "winter_"+c.coordinates() : c.id().startsWith("qinling128_spring_") ? "spring_"+c.coordinates() : "";
                if(!hist.isEmpty()) {
                    String[] h=oldRows.get(hist); NavigableMap<Double,Integer> previous=oldNodes.get(hist);
                    require(h!=null && previous!=null,"missing historical comparison "+hist);
                    require(Math.abs(Double.parseDouble(h[3])-reference)<EPS && Math.abs(Double.parseDouble(h[4])-baseline.psd)<EPS && Integer.parseInt(h[6])==qb,"historical PSD/count mismatch "+hist);
                    require(previous.size()==baseline.nodes.size(),"historical node count mismatch "+hist);
                    Iterator<Map.Entry<Double,Integer>> a=previous.entrySet().iterator(),b=baseline.nodes.entrySet().iterator();
                    while(a.hasNext()) { var x=a.next(); var y=b.next(); require(Math.abs(x.getKey()-y.getKey())<1e-10 && x.getValue().equals(y.getValue()),"historical nodes mismatch "+hist); }
                    regressions++; checkLines.add("historical node/state/PSD/count PASS "+hist+"; query order unavailable");
                }
                if(c.id().startsWith("qinling128_winter_")) {
                    String[] prev=original128.get(c.coordinates()), refrow=reference128.get(c.coordinates());
                    require(prev!=null && refrow!=null,"missing historical 128 rows "+c.id());
                    require(Math.abs(Double.parseDouble(prev[5])-day)<EPS && Math.abs(Double.parseDouble(refrow[5])-day)<EPS,"128 sunset mismatch "+c.id());
                    require(Math.abs(Double.parseDouble(prev[6])-baseline.psd)<EPS && Integer.parseInt(prev[7])==baseline.unique() && Math.abs(Double.parseDouble(refrow[6])-reference)<EPS,"128 historical PSD/count mismatch "+c.id());
                    List<String[]> oldOrder=ordered128.get(c.coordinates());
                    require(oldOrder!=null && oldOrder.size()==baseline.queries.size(),"128 ordered query count mismatch "+c.id());
                    for(int i=0;i<oldOrder.size();i++) {
                        String[] old=oldOrder.get(i); TimeStructureSearch.Query query=baseline.queries.get(i);
                        require(Math.abs(Double.parseDouble(old[3])-query.time)<EPS && Integer.parseInt(old[6])==query.state,
                                "128 ordered query mismatch "+c.id()+" index "+i);
                    }
                    checkLines.add("128 historical reference/PSD/count/ordered query PASS "+c.id());
                }
                if((graphCount<5 && !c.synthetic()) || c.id().equals("two_midday_dark")) { writeSvg(output,c,all); graphCount++; }
            }
            require(regressions==7,"historical seven-case regression incomplete");
            require(!ca.checkError()&&!qu.checkError()&&!no.checkError()&&!wi.checkError()&&!comp.checkError(),"CSV write error");
        }
        Path src=root.resolve("src");
        try(var files=Files.list(src)) { files.filter(p->p.toString().endsWith(".java")).forEach(inputs::add); }
        Path classes=Path.of(TimeStructureMinimal.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(Files.isDirectory(classes),"isolated class directory required");
        try(var files=Files.walk(classes)) { files.filter(p->Files.isRegularFile(p)&&p.toString().endsWith(".class")).forEach(inputs::add); }
        for(String p:List.of("user_runs/qinling_winter_001/config.properties","user_runs/qinling_512_reference_002/config.properties","user_runs/qinling_spring_001/config.properties","user_runs/huabei_spring_001/config.properties","user_runs/jianghuai_spring_001/config.properties","user_runs/qinling_spatial_ablation_001/config.properties")) {
            Path config=root.resolve(p); inputs.add(config); Properties props=new Properties();
            try(Reader reader=Files.newBufferedReader(config,StandardCharsets.UTF_8)) { props.load(reader); }
            for(String key:List.of("dem","receivers","metadata")) {
                String value=props.getProperty(key); require(value!=null,"missing input path "+key+" in "+config);
                Path source=root.resolve("configs").resolve(value).normalize();
                require(Files.isRegularFile(source),"missing source input "+source); inputs.add(source);
            }
        }
        try(PrintWriter manifest=new PrintWriter(Files.newBufferedWriter(output.resolve("manifest.csv"),StandardCharsets.UTF_8))) {
            csv(manifest,"path","sha256"); for(Path p:inputs) csv(manifest,root.relativize(p),sha(p)); require(!manifest.checkError(),"manifest write error");
        }
        checkLines.add("synthetic search controls PASS");
        checkLines.add("Original binarylocal internal cache requests are not observable: baseline oracle calls recorded as other, internal hits unavailable.");
        checkLines.add("Saved trace nodes use each case's own sunset; reference is conditional fixed1, not continuous truth.");
        checkLines.add("The seven-case historical log records sorted nodes, not temporal query order; the three 128 diagnostic cases also have an ordered-query regression.");
        checkLines.add("No real noon-dark two-window trajectory found in selected existing traces; SYNTHETIC control used; no new terrain query.");
        checkLines.add("Plots show reference and queried/integration nodes; midpoint reconstruction is for visualization and per-minute comparison, not extra oracle queries.");
        checkLines.add("Selection is deliberately diagnostic and cannot estimate population fractions. Window boundaries use adjacent-node midpoint; one-to-one overlap only, ambiguous split/merge not assigned distances.");
        Files.write(output.resolve("checks.txt"),checkLines,StandardCharsets.UTF_8);
        System.out.println("OFFLINE_VALIDATION_COMPLETED "+output);
    }
    private static Map<String,String[]> psdRows(Path path) throws IOException {
        Map<String,String[]> rows=new HashMap<>();
        try(BufferedReader r=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
            require(r.readLine().startsWith("row,col,latitude,longitude,elevation_m,daylight_minutes,psd_minutes,sample_count,"),"PSD header "+path);
            String line; while((line=r.readLine())!=null) {String[] p=split(line); require(p.length>=8 && rows.putIfAbsent(p[0]+"_"+p[1],p)==null,"duplicate PSD row "+path);}
        }
        return rows;
    }
    private static void writeSvg(Path output,Case c,Map<String,TimeStructureSearch.Result> all) throws IOException {
        double day=c.reference().lastKey(), scale=800/day; StringBuilder s=new StringBuilder();
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1000\" height=\"230\" viewBox=\"0 0 1000 230\">\n<rect width=\"1000\" height=\"230\" fill=\"white\"/>");
        s.append("<text x=\"10\" y=\"20\" font-size=\"14\">").append(c.id()).append(c.synthetic()?" SYNTHETIC":" SAVED").append(" | minutes since sunrise</text>");
        drawStrip(s,c.reference(),40,scale,"REFERENCE"); int y=80;
        for(var e:all.entrySet()) {
            if(e.getValue().status.equals("OK")) drawStrip(s,e.getValue().nodes,y,scale,e.getKey());
            else s.append("<text x=\"10\" y=\"").append(y+12).append("\" font-size=\"11\">").append(e.getKey()).append(" ").append(e.getValue().status).append("</text>");
            for(var p:e.getValue().queries) if(p.isNew) s.append("<circle cx=\"").append(190+p.time*scale).append("\" cy=\"").append(y+10).append("\" r=\"1.7\" fill=\"#bb2222\"/>");
            y+=40;
        }
        s.append("<text x=\"190\" y=\"222\" font-size=\"11\">dark=gray light=yellow unknown=purple; red points=unique queries; state changes at midpoint</text></svg>");
        Files.writeString(output.resolve("case_"+c.id()+".svg"),s,StandardCharsets.UTF_8);
    }
    private static void drawStrip(StringBuilder s,NavigableMap<Double,Integer> m,int y,double scale,String label) {
        s.append("<text x=\"10\" y=\"").append(y+12).append("\" font-size=\"11\">").append(label).append("</text>");
        List<Map.Entry<Double,Integer>> e=new ArrayList<>(m.entrySet());
        for(int i=0;i<e.size();i++) {
            double start=i==0?e.get(i).getKey():(e.get(i-1).getKey()+e.get(i).getKey())/2;
            double end=i==e.size()-1?e.get(i).getKey():(e.get(i).getKey()+e.get(i+1).getKey())/2;
            int v=e.get(i).getValue(); String color=v==1?"#efc73d":v==0?"#555555":"#9c65bd";
            s.append("<rect x=\"").append(190+start*scale).append("\" y=\"").append(y).append("\" width=\"").append(Math.max(0,(end-start)*scale)).append("\" height=\"20\" fill=\"").append(color).append("\"/>");
        }
    }
}
