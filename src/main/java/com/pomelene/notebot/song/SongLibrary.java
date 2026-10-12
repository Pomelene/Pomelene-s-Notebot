package com.pomelene.notebot.song;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable UI snapshots, generation cancellation and lazy phonetic dictionaries. No Minecraft APIs. */
public final class SongLibrary implements AutoCloseable {
    public record Snapshot(Path directory,String query,List<SongSearch.Entry> entries,boolean loading,String error) {
        public Snapshot { entries=List.copyOf(entries); }
    }
    private record Cached(long modified,long size,SongSearch.Entry basic,SongSearch.Entry phonetic) {}
    private final Path root;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"Notebot directory worker");t.setDaemon(true);return t;
    });
    private final AtomicInteger generation=new AtomicInteger();
    private final Map<Path,Cached> cache=new HashMap<>();
    private SongSearch phonetics;
    private volatile Snapshot snapshot;
    public SongLibrary(Path root) {this.root=root.toAbsolutePath().normalize();snapshot=new Snapshot(this.root,"",List.of(),false,"");}
    public Snapshot snapshot(){return snapshot;}
    public Path root(){return root;}
    public void refresh(){var view=snapshot;request(view.directory(),view.query());}
    public void request(Path directory,String query) {
        Path path=directory.toAbsolutePath().normalize();
        if(!path.startsWith(root))return;
        int request=generation.incrementAndGet();
        snapshot=new Snapshot(path,query,List.of(),true,"");
        worker.execute(() -> scan(path,query,request));
    }
    private void scan(Path directory,String query,int request) {
        var entries=new ArrayList<SongSearch.Entry>(); String error="";
        try {
            if(!directory.startsWith(root)||Files.isSymbolicLink(directory))throw new IOException("Invalid directory");
            boolean search=!query.isBlank(),roman=search&&query.codePoints().anyMatch(c -> c<128&&Character.isLetter(c));
            // Normal browsing never initializes dictionaries or recursively indexes the library.
            if(roman&&phonetics==null)phonetics=new SongSearch();
            try(var stream=Files.walk(directory,search?64:1)) {
                var iterator=stream.iterator();
                while(iterator.hasNext()) {
                    if(request!=generation.get())return;
                    Path path=iterator.next();
                    if(path.equals(directory)||Files.isSymbolicLink(path))continue;
                    boolean folder=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
                    if(search&&folder)continue;
                    if(!folder&&(!Files.isRegularFile(path)||!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbs")))continue;
                    long modified=Files.getLastModifiedTime(path).toMillis(),size=folder?0:Files.size(path);
                    Cached cached=cache.get(path);
                    if(cached==null||cached.modified()!=modified||cached.size()!=size) {
                        String filename=path.getFileName().toString(),title=folder?filename:SongSearch.title(path);
                        cached=new Cached(modified,size,new SongSearch.Entry(path,folder,title,List.of(SongSearch.normalize(filename),SongSearch.normalize(title))),null);
                    }
                    SongSearch.Entry entry=cached.basic();
                    if(roman) {
                        if(cached.phonetic()==null)cached=new Cached(modified,size,cached.basic(),phonetics.entry(path,folder));
                        entry=cached.phonetic();
                    }
                    cache.put(path,cached);
                    if(!search||entry.matches(query))entries.add(entry);
                    if(cache.size()>50000)cache.clear();
                    if(entries.size()>=50000) {error="Result limit: 50000";break;}
                }
            }
        } catch(Exception | LinkageError failure) {
            error=failure.getClass().getSimpleName()+": "+Objects.toString(failure.getMessage(),"");
        }
        entries.sort(Comparator.comparing(SongSearch.Entry::folder).reversed()
                .thenComparing(e -> e.path().getFileName().toString(),String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.path().toString()));
        if(request==generation.get())snapshot=new Snapshot(directory,query,entries,false,error);
    }
    public void createFolder(String name) throws IOException {
        if(name.isBlank()||name.equals(".")||name.equals("..")||name.matches(".*[<>:\"/\\\\|?*].*")||name.endsWith(".")||name.endsWith(" "))
            throw new IOException("Invalid folder name");
        Path directory=snapshot.directory();
        if(!directory.startsWith(root)||Files.isSymbolicLink(directory))throw new IOException("Invalid directory");
        Files.createDirectory(directory.resolve(name));refresh();
    }
    @Override public void close(){generation.incrementAndGet();worker.shutdownNow();}
}
