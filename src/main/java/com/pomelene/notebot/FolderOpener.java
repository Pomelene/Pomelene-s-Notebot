package com.pomelene.notebot;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Launch the platform file manager without SDL, shell command parsing, or waiting for its exit. */
public final class FolderOpener implements AutoCloseable {
    @FunctionalInterface interface Launcher { void start(List<String> command) throws IOException; }
    private final String os,windowsDirectory;
    private final Launcher launcher;
    private final Duration timeout;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"Notebot folder launcher");thread.setDaemon(true);return thread;
    });
    public FolderOpener(){this(System.getProperty("os.name"),System.getenv("WINDIR"),
            command -> new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(),Duration.ofSeconds(5));}
    FolderOpener(String os,String windowsDirectory,Launcher launcher,Duration timeout){
        this.os=os;this.windowsDirectory=windowsDirectory;this.launcher=launcher;this.timeout=timeout;
    }
    public boolean busy(){return busy.get();}
    public CompletableFuture<Void> open(Path directory){
        if(!busy.compareAndSet(false,true))return CompletableFuture.failedFuture(new IOException("Another folder request is still running"));
        var result=new CompletableFuture<Void>();
        try {
            worker.execute(()->{
                try{
                    Path path=directory.toAbsolutePath().normalize();
                    if(!Files.isDirectory(path))throw new IOException("Folder does not exist: "+path);
                    launcher.start(command(os,windowsDirectory,path));result.complete(null);
                }catch(Exception e){result.completeExceptionally(e);}
                finally{busy.set(false);}
            });
        }catch(RejectedExecutionException e){busy.set(false);result.completeExceptionally(e);}
        // Timeout reports the failure; it does not kill the file manager or queue repeated launches.
        return result.orTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS);
    }
    static List<String> command(String os,String windowsDirectory,Path directory) throws IOException{
        String platform=os.toLowerCase(Locale.ROOT);
        if(platform.startsWith("windows")){
            if(windowsDirectory==null||windowsDirectory.isBlank())throw new IOException("Windows directory is unavailable");
            return List.of(Path.of(windowsDirectory,"explorer.exe").toString(),directory.toString());
        }
        if(platform.contains("mac"))return List.of("/usr/bin/open",directory.toString());
        if(platform.contains("linux"))return List.of("xdg-open",directory.toString());
        throw new IOException("Unsupported desktop platform: "+os);
    }
    @Override public void close(){worker.shutdownNow();}
}
