package inventoryreader.ir;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;

public class StorageViewerMod implements ClientModInitializer{
    private static AbstractContainerMenu lastScreenHandler = null;
    private final StorageReader storageReader = StorageReader.getInstance();
    private final SackReader sackReader = SackReader.getInstance();
    private int tickCounter = 0;

    private boolean hasHandledMenuOpen = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
    }

    private void onEndClientTick(Minecraft client) {
        if (client.screen != null && client.player != null) {
            AbstractContainerMenu currentHandler = client.player.containerMenu;

            if (currentHandler != lastScreenHandler) {
                lastScreenHandler = currentHandler;
                tickCounter = 0;
                hasHandledMenuOpen = false;
            }

            if (tickCounter < 1) {
                tickCounter++;
            } else if (!hasHandledMenuOpen) {
                handleMenuOpen(client);
                hasHandledMenuOpen = true;
            }
        }
    }

    private void handleMenuOpen(Minecraft client) {
        Screen currentScreen = client.screen;
        if (currentScreen.getClass().getName().contains("SandboxViewer")) {
            return;
        }
        String title = currentScreen.getTitle().getString();
        storageReader.saveContainerContents(client.player.containerMenu, title);
        if (title.contains("Sack")) {
            sackReader.saveLoreComponents(client.player.containerMenu, title);
        }
    }
}