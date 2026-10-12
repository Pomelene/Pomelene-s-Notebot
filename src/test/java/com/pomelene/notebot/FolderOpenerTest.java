package com.pomelene.notebot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FolderOpenerTest {
    @TempDir Path root;
    @Test void launchesOnDedicatedThreadWithOneLiteralPathArgument() throws Exception {
        Path directory=Files.createDirectory(root.resolve("目录 with spaces & characters"));
        var arguments=new AtomicReference<java.util.List<String>>();var thread=new AtomicReference<Thread>();
        Thread caller=Thread.currentThread();
        try(var opener=new FolderOpener("Windows 11","C:\\Windows",command->{arguments.set(command);thread.set(Thread.currentThread());},Duration.ofSeconds(1))) {
            opener.open(directory).get(1,TimeUnit.SECONDS);
            assertNotEquals(caller,thread.get());assertEquals(2,arguments.get().size());
            assertEquals(Path.of("C:\\Windows","explorer.exe").toString(),arguments.get().getFirst());
            assertEquals(directory.toAbsolutePath().normalize().toString(),arguments.get().get(1));
        }
    }
    @Test void aStalledLaunchTimesOutWithoutBlockingCallerOrQueuingAnotherLaunch() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var opener=new FolderOpener("Windows","C:\\Windows",command->{
            entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        },Duration.ofMillis(100))) {
            var future=opener.open(root);assertTrue(entered.await(1,TimeUnit.SECONDS));
            var timeout=assertThrows(ExecutionException.class,()->future.get(1,TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class,timeout.getCause());assertTrue(opener.busy());
            var rejected=assertThrows(ExecutionException.class,()->opener.open(root).get(1,TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,rejected.getCause());
        }finally{release.countDown();}
    }
    @Test void missingFolderFailsWithoutLaunchingAnyProcess() throws Exception {
        try(var opener=new FolderOpener("Windows","C:\\Windows",command->fail("Must not launch"),Duration.ofSeconds(1))) {
            var error=assertThrows(ExecutionException.class,()->opener.open(root.resolve("missing")).get(1,TimeUnit.SECONDS));
            assertInstanceOf(IOException.class,error.getCause());
        }
    }
    @Test void desktopCommandsDoNotContainShellOrUrlHandlers() throws Exception {
        assertEquals(java.util.List.of("/usr/bin/open",root.toString()),FolderOpener.command("Mac OS X",null,root));
        assertEquals(java.util.List.of("xdg-open",root.toString()),FolderOpener.command("Linux",null,root));
        assertThrows(IOException.class,()->FolderOpener.command("Windows",null,root));
    }
}
