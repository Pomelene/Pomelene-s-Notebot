package com.pomelene.notebot;

import java.lang.reflect.InvocationTargetException;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerLevel;

/** Test-only bridge across Fabric's renamed client-world/level/connection test APIs. */
final class TestConnection {
    private final ClientGameTestContext context;
    private final TestSingleplayerContext world;
    private final Object connection;
    private final ServerLevel serverLevel;
    TestConnection(ClientGameTestContext context, TestSingleplayerContext world) {
        this.context=context;this.world=world;
        connection=invoke(world,"getConnection","getClientLevel","getClientWorld");
        serverLevel=world.getServer().computeOnServer(server -> server.overworld());
    }
    void waitForChunksDownload(){invoke(connection,"waitForChunksDownload");}
    void waitForClientboundPackets(){barrier("waitForClientboundPackets");}
    void waitForServerboundPackets(){barrier("waitForServerboundPackets");}
    ServerLevel getServerLevel(){return serverLevel;}
    private void barrier(String method) {
        try {
            var m=connection.getClass().getMethod(method);m.setAccessible(true);m.invoke(connection);
        } catch(NoSuchMethodException absent) {
            // Early Fabric test APIs expose no packet barrier. Cross both game threads,
            // then let the local connection tick; each test still asserts actual block state.
            world.getServer().runOnServer(server -> {});
            context.waitTicks(5);
        } catch(ReflectiveOperationException error){throw failure(error);}
    }
    private static Object invoke(Object target,String...names) {
        for(String name:names)try {
            var method=target.getClass().getMethod(name);method.setAccessible(true);return method.invoke(target);
        } catch(NoSuchMethodException ignored) {
        } catch(ReflectiveOperationException error){throw failure(error);}
        throw new IllegalStateException("No supported Fabric test API on "+target.getClass().getName());
    }
    private static RuntimeException failure(ReflectiveOperationException error) {
        Throwable cause=error instanceof InvocationTargetException invocation?invocation.getCause():error;
        if(cause instanceof Error assertion)throw assertion;
        return cause instanceof RuntimeException runtime?runtime:new IllegalStateException(cause);
    }
}
