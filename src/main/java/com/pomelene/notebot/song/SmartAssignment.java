// SPDX-License-Identifier: MIT
package com.pomelene.notebot.song;

import java.util.*;

/** Deterministic symbolic arrangement analysis; no audio, random seeds or clock inputs. */
public final class SmartAssignment {
    public static final String VERSION = "arrangement-rules-2";
    public enum Preference { BALANCED, PIANO, ORCHESTRAL, ACOUSTIC, ELECTRONIC, BRIGHT;
        public Preference next() { return values()[(ordinal()+1)%values().length]; }
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }
    public record Suggestion(int instrument, String role, String confidence, List<String> reasons) {
        public Suggestion { reasons=List.copyOf(reasons); }
    }
    public record Result(Map<Integer,Suggestion> suggestions, String style, String complexity) {
        public Result { suggestions=Collections.unmodifiableMap(new TreeMap<>(suggestions)); }
    }
    private static final String[] ROLES={"melody","chord","bass","drums"};
    private static final int[] PREFERENCE={0,15,5,6,1,12,16,17,18,19,7,8,9,10,13,14,2,3,4,11};
    private SmartAssignment() {}

    private static final class Stats {
        long count, onsets, repeated, steps, jumps, low, high, weight;
        int lastTick=-1,lastPitch,intervalCount;
        final long[] pitches=new long[128],intervals=new long[256],pitchSteps=new long[25];
        void add(NbsSong.Note n,int pitch,int volume,int minimum,int maximum,double quantum) {
            count++;weight+=volume;pitches[pitch]++;
            if(pitch==minimum)low++;if(pitch==maximum)high++;
            if(n.tick()!=lastTick) {
                onsets++;
                if(lastTick>=0) {
                    int distance=Math.abs(pitch-lastPitch); pitchSteps[Math.min(24,distance)]++;
                    if(distance==0)repeated++;if(distance<=4)steps++;if(distance>=12)jumps++;
                    int interval=(int)Math.min(255,Math.max(1,Math.round((n.tick()-lastTick)/quantum)));
                    intervals[interval]++;intervalCount++;
                }
                lastTick=n.tick();lastPitch=pitch;
            }
        }
        double chord() {return count==0?0:1-onsets/(double)count;}
        double repeat() {return repeated/(double)Math.max(1,onsets-1);}
        double step() {return steps/(double)Math.max(1,onsets-1);}
        double low() {return low/(double)Math.max(1,count);}
        double high() {return high/(double)Math.max(1,count);}
        double regular() {return Arrays.stream(intervals).max().orElse(0)/(double)Math.max(1,intervalCount);}
        int distinct() {int n=0;for(long c:pitches)if(c>0)n++;return n;}
        int percentile(double fraction) {
            long target=Math.max(1,(long)Math.ceil(count*fraction)),sum=0;
            for(int p=0;p<128;p++){sum+=pitches[p];if(sum>=target)return p;}return 45;
        }
    }

    public static Result analyze(NbsSong song) {
        return analyze(song,Preference.BALANCED);
    }
    public static Result analyze(NbsSong song,Preference preference) {
        Stats[] instruments=new Stats[256];
        Stats global=new Stats();
        var tracks=new TreeMap<Long,Stats>();
        Stats[][] windows=new Stats[256][64];
        long[] known=new long[20];
        // NbsReader emits stable ascending tick/layer order. Traverse the complete score.
        List<NbsSong.Note> notes=song.notes();
        double quantum=Math.max(1,song.tempo()/8);
        for(int start=0;start<notes.size();) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            int end=start+1,tick=notes.get(start).tick(),min=127,max=0;
            while(end<notes.size()&&notes.get(end).tick()==tick)end++;
            for(int i=start;i<end;i++){int p=pitch(song,notes.get(i));min=Math.min(min,p);max=Math.max(max,p);}
            for(int i=start;i<end;i++) {
                var n=notes.get(i);int id=n.instrument(),p=pitch(song,n);
                int volume=n.velocity()*song.metadata().layerVolumes().getOrDefault(n.layer(),100)/100;
                if(instruments[id]==null)instruments[id]=new Stats();
                instruments[id].add(n,p,volume,min,max,quantum);global.add(n,p,volume,min,max,quantum);
                long key=((long)id<<32)|Integer.toUnsignedLong(n.layer());
                // Bound optional track detail independently of the total note capacity.
                if(!tracks.containsKey(key)&&tracks.size()>=8192)key=((long)id<<32)|0xFFFFFFFFL;
                tracks.computeIfAbsent(key,k->new Stats()).add(n,p,volume,min,max,quantum);
                int w=(int)Math.min(63,(long)tick*64/Math.max(1,song.length()));
                if(windows[id][w]==null)windows[id][w]=new Stats();
                windows[id][w].add(n,p,volume,min,max,quantum);
                if(song.isVanillaInstrument(id))known[id]+=volume;
            }
            start=end;
        }
        double totalKnown=Arrays.stream(known).sum();
        long[] familyHints = new long[3];
        for(int id=0;id<256;id++)if(instruments[id]!=null&&!song.isVanillaInstrument(id)) {
            String h=hints(song,id);long w=instruments[id].weight;
            if(contains(h,"piano","guitar","flute","violin","strings","bass","钢琴","吉他","笛","弦","贝斯"))familyHints[0]+=w;
            if(contains(h,"synth","chip","square","8bit","electronic","合成","电子"))familyHints[1]+=w;
            if(contains(h,"bell","chime","mallet","xylophone","钟","木琴"))familyHints[2]+=w;
        }
        double familyMass=totalKnown+Arrays.stream(familyHints).sum();
        double acoustic=(sum(known,0,1,5,6,14)+familyHints[0])/Math.max(1,familyMass);
        double electronic=(sum(known,13,15)+familyHints[1])/Math.max(1,familyMass);
        double bright=(sum(known,7,8,9,10)+familyHints[2])/Math.max(1,familyMass);
        String style=familyMass==0?"unknown":electronic>0.45?"electronic":bright>0.45?"bright":acoustic>0.5?"acoustic":"mixed";
        double complexity=Math.min(1,global.chord()*1.3+Math.min(0.35,tracks.size()/50.0)+Math.min(0.25,global.distinct()/128.0));
        String complexityName=complexity>0.7?"complex":complexity>0.4?"moderate":"simple";
        var result=new TreeMap<Integer,Suggestion>();
        int[] assigned=new int[20];
        for(int id=0;id<256;id++) {
            Stats s=instruments[id];
            if(s==null||song.isVanillaInstrument(id))continue;
            String hints=hints(song,id);
            double[] roles=roleScores(s,global,hints);
            // Layer votes keep a lead line from disappearing inside a mixed custom instrument.
            double[] votes=new double[4];double voteWeight=0,polyphonicWeight=0;
            Set<Integer> trackRoles=new HashSet<>();
            for(var entry:tracks.subMap((long)id<<32,true,((long)id<<32)|0xFFFFFFFFL,true).entrySet()) {
                Stats track=entry.getValue();String layerName=song.metadata().layerNames().getOrDefault((int)(long)entry.getKey(),"");
                double[] scored=roleScores(track,global,hints+" "+normalize(layerName));
                double w=Math.sqrt(track.count);voteWeight+=w;
                polyphonicWeight+=track.chord()*w;
                if(track.count>=8)trackRoles.add(best(scored));
                for(int role=0;role<4;role++)votes[role]+=scored[role]*w;
            }
            boolean mixedTracks=trackRoles.size()>1;
            if(voteWeight>0) {
                for(int role=0;role<4;role++)roles[role]=0.3*roles[role]+0.7*votes[role]/voteWeight;
                // Multiple independent single-note layers are not evidence of a chordal instrument.
                if(polyphonicWeight/voteWeight<0.15 && s.chord()>0.25)roles[1]-=1.2;
            }
            int role=best(roles),second=runnerUp(roles,role);
            int agreeing=0,activeWindows=0;
            for(Stats window:windows[id])if(window!=null&&window.count>=4) {
                activeWindows++;if(best(roleScores(window,global,hints))==role)agreeing++;
            }
            double stability=activeWindows==0?0.5:agreeing/(double)activeWindows;
            double[] candidates=new double[20];
            for(int target=0;target<20;target++) {
                candidates[target]=roleAffinity(target,roles);
                candidates[target]+=0.7*known[target]/Math.max(1,totalKnown);
                candidates[target]+=familyBonus(target,acoustic,electronic,bright);
                candidates[target]+=nameBonus(target,hints);
                candidates[target]+=preferenceBonus(preference,target,role);
                boolean percussion=target==2||target==3||target==4||target==11;
                if(percussion&&role!=3)candidates[target]-=4;
                if(role==2&&target!=1&&target!=12&&target!=19)candidates[target]-=1.3;
                // A gentle timbral separation preference in busy scores; sharing is still allowed.
                candidates[target]-=Math.min(0.65,assigned[target]*0.12)*complexity;
                if(role==0&&complexity>0.7&&(target==6||target>=16))candidates[target]+=0.25;
            }
            int target=PREFERENCE[0];
            for(int candidate:PREFERENCE)if(candidates[candidate]>candidates[target]+1e-9)target=candidate;
            assigned[target]++;
            double margin=roles[role]-roles[second];
            String confidence=mixedTracks||s.count<8||stability<0.55||margin<0.6?"low":margin>1.7&&stability>=0.75?"high":"medium";
            // Sparse windows provide no stability evidence; they do not prove a mixed role.
            String label=mixedTracks||(activeWindows>0&&stability<0.55)||margin<0.35?"mixed":ROLES[role];
            var reasons=new ArrayList<String>();reasons.add("role_"+ROLES[role]);
            reasons.add(s.chord()>0.25?"simultaneous":"mostly_single");
            if(s.low()>0.45&&s.percentile(0.5)<global.percentile(0.5))reasons.add("low_register");
            if(s.regular()>0.5&&s.repeat()>0.5)reasons.add("repeated_rhythm");
            if(s.step()>0.65&&s.distinct()>4)reasons.add("connected_line");
            if(nameBonus(target,hints)>0)reasons.add("metadata_hint");
            reasons.add("style_"+style);reasons.add("complexity_"+complexityName);
            if(label.equals("mixed"))reasons.add("mixed_sections");
            result.put(id,new Suggestion(target,label,confidence,reasons));
        }
        return new Result(result,style,complexityName);
    }
    private static int pitch(NbsSong song,NbsSong.Note n) {
        boolean custom=!song.isVanillaInstrument(n.instrument());
        int base=custom?song.metadata().baseKeys().getOrDefault(n.instrument(),45)-45:0;
        return Math.max(0,Math.min(127,n.key()+base+(int)Math.round(n.cents()/100.0)));
    }
    private static double[] roleScores(Stats s,Stats global,String hint) {
        double chord=s.chord(),mono=1-chord,relative=(s.percentile(0.5)-global.percentile(0.5))/12.0;
        double few=s.distinct()<=3?1:s.distinct()<=6?0.4:0;
        double melody=2*mono+1.4*s.step()+1.3*s.high()+Math.min(1,Math.max(0,relative))+(s.distinct()>5?0.6:0)-0.8*s.repeat();
        double harmony=4.5*chord+0.5*s.regular()+(s.distinct()>=6?0.6:0);
        double triadic=(s.pitchSteps[3]+s.pitchSteps[4]+s.pitchSteps[7])/(double)Math.max(1,s.onsets-1);
        if(triadic>0.6 && s.regular()>0.45 && s.percentile(0.9)-s.percentile(0.1)<=24 && s.distinct()>=3)harmony+=1.5;
        boolean bassRegister=s.percentile(0.5)<40 || relative< -0.6;
        double bass=(bassRegister?2:0.5)*s.low()+Math.min(2,Math.max(0,-relative))+mono+(s.percentile(0.5)<33?0.7:0);
        double drums=1.3*few+1.2*s.repeat()+s.regular()-1.2*chord-(s.distinct()>8?1.2:0);
        // Name evidence is intentionally bounded and supplemented by score structure.
        if(contains(hint,"melody","lead","vocal","solo","主旋律","人声"))melody+=2;
        if(contains(hint,"chord","pad","strings","accompaniment","arpeggio","伴奏","和弦","琶音"))harmony+=1.8;
        if(contains(hint,"bass","sub","低音","贝斯")&&!contains(hint,"drum","kick","底鼓"))bass+=2.4;
        boolean percussionHint=contains(hint,"drum","kick","snare","hihat","hi hat","cymbal","percussion","鼓","镲");
        if(percussionHint)drums+=3;
        else {
            // Repetition alone also describes bass ostinatos, pads and arpeggiated accompaniment.
            drums-=1.8;
            if(bassRegister && s.low()>0.45 && s.percentile(0.5)<=global.percentile(0.5)) {bass+=1;drums-=1;}
            if(contains(hint,"pad","strings","chord","piano","bass","伴奏","和弦","钢琴","弦","贝斯"))drums-=1.5;
        }
        return new double[]{melody,harmony,bass,drums};
    }
    private static double roleAffinity(int i,double[] r) {
        double[][] weights={{1,.85,0,0},{.1,.2,1,0},{0,0,0,1},{0,0,0,1},{0,0,0,1},
            {.6,1,.1,0},{1,.35,0,0},{.7,.35,0,.15},{.65,.45,0,0},{.75,.4,0,.15},
            {.75,.65,0,.1},{.05,0,0,.75},{.05,.05,.95,0},{.75,.45,.3,0},{.55,.6,.1,0},
            {.95,.9,.1,0},{1,.4,.1,0},{.95,.45,.2,0},{.85,.5,.3,0},{.75,.5,.4,0}};
        double max=Arrays.stream(r).max().orElse(0),sum=0,total=0;
        for(int k=0;k<4;k++){double probability=Math.exp((r[k]-max)/1.2);sum+=weights[i][k]*probability;total+=probability;}
        return 3*sum/total;
    }
    private static double preferenceBonus(Preference p,int target,int role) {
        if(role==3)return 0; // Timbre preferences never convert a rhythmic part into a melody.
        if(role==2) return switch(p) {
            case ORCHESTRAL -> target==19?0.8:target==1?0.3:0;
            case ELECTRONIC -> target==12?0.6:target==1?0.3:0;
            default -> target==1?0.4:0;
        };
        return switch(p) {
            case BALANCED -> 0;
            case PIANO -> target==15?1.4:target==0?0.8:0;
            case ORCHESTRAL -> target==6?1:target>=16?0.9:target==0?0.3:0;
            case ACOUSTIC -> target==5?1.1:target==0||target==14?0.7:0;
            case ELECTRONIC -> target==13?1.3:target==15?0.7:0;
            case BRIGHT -> target==7||target==8||target==9||target==10?1:0;
        };
    }
    private static double nameBonus(int i,String h) {
        if(i==2&&contains(h,"kick","bass drum","底鼓"))return 2;
        if(i==3&&contains(h,"snare","clap","军鼓"))return 2;
        if(i==4&&contains(h,"hihat","hi hat","cymbal","镲"))return 2;
        if(i==1&&contains(h,"bass","贝斯"))return 1.4;
        if(i==5&&contains(h,"guitar","吉他"))return 1.4;
        if(i==6&&contains(h,"flute","笛"))return 1.4;
        if(i==15&&contains(h,"piano","keyboard","钢琴"))return 1.2;
        if(i==13&&contains(h,"synth","chip","8bit","square","合成"))return 1.2;
        if(i>=16&&contains(h,"trumpet","brass","horn","小号","铜管"))return 1.2;
        if((i==7||i==8)&&contains(h,"bell","chime","钟"))return 1.2;
        return 0;
    }
    private static double familyBonus(int i,double acoustic,double electronic,double bright) {
        return i==13||i==15?0.5*electronic:i==7||i==8||i==9||i==10?0.5*bright:
            i==0||i==1||i==5||i==6||i==14?0.4*acoustic:0;
    }
    private static String hints(NbsSong s,int id) {
        return normalize(s.customInstruments().getOrDefault(id,"")+" "+s.metadata().soundFiles().getOrDefault(id,""));
    }
    private static String normalize(String s) {return s.toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');}
    private static boolean contains(String h,String... words) {for(String w:words)if(h.contains(w))return true;return false;}
    private static double sum(long[] a,int... ids) {double n=0;for(int i:ids)n+=a[i];return n;}
    private static int best(double[] s) {int best=0;for(int i=1;i<s.length;i++)if(s[i]>s[best]+1e-9)best=i;return best;}
    private static int runnerUp(double[] s,int exclude) {int best=exclude==0?1:0;for(int i=0;i<s.length;i++)if(i!=exclude&&s[i]>s[best]+1e-9)best=i;return best;}
}
