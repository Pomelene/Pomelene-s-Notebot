// SPDX-License-Identifier: MIT
package com.pomelene.notebot.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface GameModeInvoker {
    @Invoker("startPrediction")
    void notebot$startPrediction(ClientLevel level, PredictiveAction action);
}
