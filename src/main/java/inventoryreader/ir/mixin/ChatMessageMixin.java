package inventoryreader.ir.mixin;

import com.google.gson.Gson;

import inventoryreader.ir.InventoryReader;
import inventoryreader.ir.ResourcesManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.HoverEvent.ShowText;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

@Mixin(ClientPacketListener.class)
public class ChatMessageMixin {
    @Unique
    private static final Gson GSON = new Gson();

    @Unique
    private static final ResourcesManager RESOURCESMANAGER = ResourcesManager.getInstance();

    @Unique
    private static final Pattern SACKS_PATTERN_1 = Pattern.compile(
        "\\[Sacks\\] [+-].+ items?, [+-].+ items?\\. \\(Last .+s\\.\\).*"
    );

    @Unique
    private static final Pattern SACKS_PATTERN_2 = Pattern.compile(
        "\\[Sacks\\] [+-].+ items?\\. \\(Last .+s\\.\\).*"
    );

    @Unique
    private static final AtomicBoolean isProcessing = new AtomicBoolean(false);

    @Unique
    private static final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void onGameMessage(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (!isProcessing.compareAndSet(false, true)) {
            return;
        }
        try{
            Component text = packet.content();
            List<Component> contents = text.getSiblings();

            String messageString = text.getString();
            Map<String, Integer> itemMap = new HashMap<>();

            Matcher matcher1 = SACKS_PATTERN_1.matcher(messageString);
            Matcher matcher2 = SACKS_PATTERN_2.matcher(messageString);

            boolean is1stMatcher = matcher1.find();
            boolean is2ndMatcher = matcher2.find();

            if ((is1stMatcher || is2ndMatcher)&&(Thread.currentThread().getName().contains("Render"))) {
                if (is1stMatcher){
                    InventoryReader.LOGGER.warn("Matcher 1 found, processing hover text");
                    HoverEvent hoverEvent1 = contents.getFirst().getStyle().getHoverEvent();
                    HoverEvent hoverEvent2 = contents.get(3).getStyle().getHoverEvent();
                    ShowText innerText1 = (ShowText) hoverEvent1;
                    ShowText innerText2 = (ShowText) hoverEvent2;

                    Component hoverChildren1 = null;
                    Component hoverChildren2 = null;
                    if (innerText1 != null) {
                        hoverChildren1 = innerText1.value();
                    }
                    if (innerText2 != null) {
                        hoverChildren2 = innerText2.value();
                    }
                    if (hoverChildren1 == null || hoverChildren1.getSiblings().isEmpty() ||
                        hoverChildren2 == null || hoverChildren2.getSiblings().isEmpty()) {
                        InventoryReader.LOGGER.warn("Hover text is empty or null");
                        return;
                    }
                    List<Component> hoverChildrenList1 = hoverChildren1.getSiblings();
                    List<Component> hoverChildrenList2 = hoverChildren2.getSiblings();
                    itemMap = getItemMapFromText(hoverChildrenList1, itemMap);
                    itemMap = getItemMapFromText(hoverChildrenList2, itemMap);
                } else {
                    InventoryReader.LOGGER.warn("Matcher 2 found, processing hover text");
                    HoverEvent hoverEvent = contents.getFirst().getStyle().getHoverEvent();
                    ShowText innerText = (ShowText) hoverEvent;
                    Component hoverChildren = null;
                    if (innerText != null) {
                        hoverChildren = innerText.value();
                    }
                    if (hoverChildren == null || hoverChildren.getSiblings().isEmpty()) {
                        InventoryReader.LOGGER.warn("Hover text is empty or null");
                        return;
                    }
                    List<Component> hoverChildrenList = hoverChildren.getSiblings();
                    itemMap = getItemMapFromText(hoverChildrenList, itemMap);
                }
                RESOURCESMANAGER.saveData(itemMap);
            }
        } finally {
            isProcessing.set(false);
        }
    }

    @Unique
    private Map<String, Integer> getItemMapFromText(List<Component> contents, Map<String, Integer> itemMap) {
        int count = 0;
        String name = "";
        for (int i = 0; i < contents.size()-1; i++) {
            if (contents.get(i).getString().isEmpty()) {
                continue;
            }
            if (i % 4 == 0) {
                String num = contents.get(i).getString().trim().replaceAll(",", "");
                count = Integer.parseInt(num);
            } else if (i % 4 == 1) {
                name = contents.get(i).getString().trim();
                InventoryReader.LOGGER.info("Found item: " + name + " with count: " + count);
                if (itemMap.containsKey(name)) {
                    count += itemMap.get(name);
                }
                itemMap.put(name, count);
            }
        }
        return itemMap;
    }
}