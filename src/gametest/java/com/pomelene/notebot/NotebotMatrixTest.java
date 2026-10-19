package com.pomelene.notebot;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** The same behavior suite runs on every supported Minecraft / Fabric environment. */
public final class NotebotMatrixTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        new NotebotGameTest().runTest(context);
        context.runOnClient(mc -> NotebotClient.controller().stop(false));
        new NotebotDetectionTest().runTest(context);
        new NotebotControlEventsTest().runTest(context);
        System.out.println("NOTEBOT_MATRIX_OK");
    }
}
