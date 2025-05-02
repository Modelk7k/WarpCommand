package net.friendlyfire.warpcommand.event;

import net.friendlyfire.warpcommand.WarpCommand;
import net.friendlyfire.warpcommand.command.WarpsCommand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = WarpCommand.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class ModServerEvents {
    @SubscribeEvent
    private static void onCommandRegister(RegisterCommandsEvent event) {
        new WarpsCommand(event.getDispatcher());
    }
}
