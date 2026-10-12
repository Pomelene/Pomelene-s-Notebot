package com.pomelene.notebot.song;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SongLibraryTest {
    @TempDir Path root;
    private SongLibrary.Snapshot finished(SongLibrary library) throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        while(library.snapshot().loading()&&System.nanoTime()<deadline)Thread.sleep(10);
        assertFalse(library.snapshot().loading());return library.snapshot();
    }
    @Test void browseIsNonRecursiveAndSearchFindsNestedReadings() throws Exception {
        Path child=Files.createDirectory(root.resolve("音乐"));Files.createFile(child.resolve("千本桜.nbs"));
        Files.createFile(root.resolve("月亮代表我的心.nbs"));
        try(var library=new SongLibrary(root)) {
            library.refresh();var view=finished(library);assertEquals(2,view.entries().size());assertTrue(view.entries().getFirst().folder());
            library.request(root,"senbonzakura");view=finished(library);assertEquals(1,view.entries().size());assertEquals(child,view.entries().getFirst().path().getParent());
            library.request(root,"yldbwdx");assertEquals(1,finished(library).entries().size());
        }
    }
    @Test void staleRequestsCannotReplaceLatestFolder() throws Exception {
        Path child=Files.createDirectory(root.resolve("child"));Files.createFile(child.resolve("inside.nbs"));
        try(var library=new SongLibrary(root)) {
            library.request(root,"unrelated");library.request(child,"");
            var view=finished(library);assertEquals(child,view.directory());assertEquals("inside.nbs",view.entries().getFirst().title());
            Thread.sleep(50);assertEquals(view,library.snapshot());
            library.request(root.getParent(),"");assertEquals(view,library.snapshot());
        }
    }
    @Test void invalidFolderAndMissingDirectoryAreRecoverable() throws Exception {
        try(var library=new SongLibrary(root)) {
            assertThrows(java.io.IOException.class,()->library.createFolder("../outside"));
            library.createFolder("new");finished(library);assertTrue(Files.isDirectory(root.resolve("new")));
            library.request(root.resolve("gone"),"");var view=finished(library);assertFalse(view.error().isEmpty());
            library.request(root,"");assertTrue(finished(library).error().isEmpty());
        }
    }
}
