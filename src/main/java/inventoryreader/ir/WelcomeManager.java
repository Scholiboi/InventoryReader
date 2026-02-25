package inventoryreader.ir;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import java.io.File;
import java.io.IOException;


public class WelcomeManager {
    private static final File WELCOME_FLAG_FILE = new File(FilePathManager.MOD_DIR, "welcome_shown.txt");
    private static boolean isFirstTimeUser = true;

    public static void initialize() {
        checkFirstTimeUser();
        if (!isFirstTimeUser) {
            return;
        }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                try {
                    checkFirstTimeUser();
                    if (isFirstTimeUser) {
                        showWelcomeMessage(client);
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
    }

    private static void checkFirstTimeUser() {
        isFirstTimeUser = !WELCOME_FLAG_FILE.exists();
    }

    private static void showWelcomeMessage(Minecraft client) {
        if (client.player == null) return;

        String divider = "§6§l" + "=".repeat(40);
        
        client.player.displayClientMessage(Component.literal(divider), false);
        client.player.displayClientMessage(Component.literal("§b§lSkyblock Resource Calculator").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA)
        ), false);
        
        client.player.displayClientMessage(Component.literal("§eTrack resources and recipes for Hypixel Skyblock mining."), false);
        client.player.displayClientMessage(Component.literal(""), false);
        
        MutableComponent commandsText = Component.literal("§6§lCommands:").append(Component.literal("\n§e- Press "));
        commandsText.append(Component.literal("§b[V]").setStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA)));
        commandsText.append(Component.literal("§e to open the Sandbox Viewer"));
        commandsText.append(Component.literal("\n§e- Press "));
        commandsText.append(Component.literal("§b[B]").setStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA)));
        commandsText.append(Component.literal("§e to customize the HUD widget"));
        client.player.displayClientMessage(commandsText, false);
        
        MutableComponent chatCommandsText = Component.literal("§6§lChat Commands:").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.GOLD)
        );
        client.player.displayClientMessage(chatCommandsText, false);
        client.player.displayClientMessage(Component.literal("§e- §b/ir menu§e: Open Sandbox Viewer"), false);
        client.player.displayClientMessage(Component.literal("§e- §b/ir widget§e: Open Widget Customization"), false);
        client.player.displayClientMessage(Component.literal("§e- §b/ir reset§e: Reset all mod data"), false);
        client.player.displayClientMessage(Component.literal("§e- §b/ir done§e: Acknowledge reminders"), false);
        client.player.displayClientMessage(Component.literal("§e- §b/ir§e: Show all available commands"), false);

        client.player.displayClientMessage(Component.literal(""), false);
        MutableComponent firstTimeText = Component.literal("§d§lFirst-Time Setup:").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.LIGHT_PURPLE)
        );
        client.player.displayClientMessage(firstTimeText, false);
        MutableComponent warningText = Component.literal("⚠️ For the mod to work, open all your Sacks, Backpacks and Ender Chests once in Skyblock. ⚠️").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.RED)
        );
        client.player.displayClientMessage(warningText, false);
        client.player.displayClientMessage(Component.literal("§e1. Press §b[V]§e to open the Sandbox Viewer"), false);
        client.player.displayClientMessage(Component.literal("§e2. Use the 'Modify' tab to modify resource amounts, if needed"), false);
        client.player.displayClientMessage(Component.literal("§e3. In the 'Forge' tab, select recipes to view progress"), false);
        client.player.displayClientMessage(Component.literal("§e4. Enable the HUD widget to track resources while mining"), false);
        
        try {
            WELCOME_FLAG_FILE.createNewFile();
        } catch (IOException e) {
            InventoryReader.LOGGER.error("Failed to create welcome flag file", e);
        }
        
        isFirstTimeUser = false;
        client.player.displayClientMessage(Component.literal(divider), false);
    }
}
