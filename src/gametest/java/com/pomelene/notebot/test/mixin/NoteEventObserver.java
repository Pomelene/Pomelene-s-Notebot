// SPDX-License-Identifier: MIT
package com.pomelene.notebot.test.mixin;

import com.pomelene.notebot.test.TestNoteEvents;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class NoteEventObserver {
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void notebotTest$event(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        if (packet.getBlock() == net.minecraft.world.level.block.Blocks.NOTE_BLOCK)
            TestNoteEvents.received.incrementAndGet();
    }
}
