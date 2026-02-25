package inventoryreader.ir.mixin;

import inventoryreader.ir.StorageReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class SlotClickMixin {

    @Inject(method = "clicked", at = @At("HEAD"))
    private void onClickSlotHead(int slotIndex, int button, ClickType actionType, Player player, CallbackInfo ci) {
        handleSlotClick(slotIndex, button, actionType, player);
    }

    @Inject(method = "clicked", at = @At("RETURN"))
    private void onClickSlotReturn(int slotIndex, int button, ClickType actionType, Player player, CallbackInfo ci) {
        handleSlotClick(slotIndex, button, actionType, player);
    }

    @Unique
    private void handleSlotClick(int slotIndex, int button, ClickType actionType, Player player) {
        Screen currentScreen = Minecraft.getInstance().screen;
        String title = currentScreen != null ? currentScreen.getTitle().getString() : "Unknown";

        if (!title.contains("Backpack") && !title.contains("Ender Chest")) {
            return;
        }
        StorageReader storageReader = StorageReader.getInstance();
        storageReader.saveContainerContents((AbstractContainerMenu)(Object)this, title);
    }
}