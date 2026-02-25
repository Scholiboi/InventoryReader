package inventoryreader.ir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SandboxWidget {
    private static final ResourceLocation SANDBOX_WIDGET_LAYER = ResourceLocation.fromNamespaceAndPath(InventoryReader.MOD_ID, "sandbox_widget");
    private static final int DARK_PANEL_BG = 0x99271910;
    private static final int HEADER_BG = 0xCC2C4A1B;
    private static final int GOLD = 0xFFFFB728;
    private static final int RECIPE_LEVEL_INDENT = 10;
    private static final SandboxWidget INSTANCE = new SandboxWidget();
    private boolean enabled = false;
    private String selectedRecipe = null;
    private RecipeManager.RecipeNode recipeTree = null;
    private int widgetX = 10;
    private int widgetY = 40;
    private int widgetWidth = 250;
    private int widgetHeight = 300;
    private boolean isRepositioning = false;
    private Map<String, Boolean> expandedNodes = new HashMap<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private int craftAmount = 1;
    private final ResourcesManager resourcesManager;
    private final ScheduledExecutorService scheduler;
    private int currentNodeLineHeight = 16;
    private float currentTreeScale = 1.0f;

    private SandboxWidget() {
        this.resourcesManager = ResourcesManager.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        HudElementRegistry.addLast(SANDBOX_WIDGET_LAYER, (context, tickCounter) -> {
            if (enabled && selectedRecipe != null && recipeTree != null) {
                render(context);
            }
        });
        scheduler.scheduleAtFixedRate(this::updateRecipeData, 0, 1, TimeUnit.SECONDS);
        loadConfiguration();
    }

    public static SandboxWidget getInstance() {
        return INSTANCE;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            this.recipeTree = null;
        } else {
            if (this.selectedRecipe != null && !this.selectedRecipe.isEmpty()) {
                updateRecipeData();
            }
        }
        saveConfiguration();
    }
    public boolean isEnabled() {
        return this.enabled;
    }
    public void setSelectedRecipe(String recipeName) {
        this.selectedRecipe = recipeName;
        if (recipeName != null) {
            expandedNodes.put(recipeName, true);
        }
        updateRecipeData();
        saveConfiguration();
    }
    public String getSelectedRecipe() {
        return this.selectedRecipe;
    }
    public int getWidgetX() {
        return widgetX;
    }
    public int getWidgetY() {
        return widgetY;
    }
    public void setWidgetPosition(int x, int y) {
        this.widgetX = x;
        this.widgetY = y;
        saveConfiguration();
    }
    public int getWidgetWidth() { return widgetWidth; }
    public int getWidgetHeight() { return widgetHeight; }
    public void setWidgetSize(int width, int height) {
        Minecraft client = Minecraft.getInstance();
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        this.widgetWidth = Math.max(180, Math.min(width, screenW - 20));
        this.widgetHeight = Math.max(120, Math.min(height, screenH - 20));
        saveConfiguration();
    }
    public static String getNodeKey(RecipeManager.RecipeNode node) {
        return node.name + "_" + node.amount;
    }
    public boolean isNodeExpanded(String nodeKey) {
        if (expandedNodes == null) {
            expandedNodes = new HashMap<>();
            return false;
        }
        
        Boolean result = expandedNodes.getOrDefault(nodeKey, false);
        return result != null ? result : false;
    }
    public void setNodeExpansion(String nodeKey, boolean expanded) {
        expandedNodes.put(nodeKey, expanded);
    }
    public void toggleNodeExpansion(String nodeKey) {
        if (expandedNodes == null) {
            expandedNodes = new HashMap<>();
        }
        
        Boolean currentState = expandedNodes.getOrDefault(nodeKey, false);
        if (currentState == null) {
            currentState = false;
        }
        
        boolean newState = !currentState;
        expandedNodes.put(nodeKey, newState);
        saveConfiguration();
    }
    public void saveConfiguration() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            WidgetConfig config = new WidgetConfig(
                enabled,
                selectedRecipe,
                widgetX,
                widgetY,
                widgetWidth,
                widgetHeight,
                expandedNodes,
                craftAmount
            );
            FilePathManager.file_widget_config.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(FilePathManager.file_widget_config)) {
                gson.toJson(config, writer);
            }
            InventoryReader.LOGGER.info("Widget configuration saved");
        } catch (IOException e) {
            InventoryReader.LOGGER.error("Failed to save widget configuration", e);
        }
    }
    private void loadConfiguration() {
        try {
            if (!FilePathManager.file_widget_config.exists()) {
                InventoryReader.LOGGER.info("No widget configuration file found, using defaults");
                return;
            }
            Gson gson = new Gson();
            try (FileReader reader = new FileReader(FilePathManager.file_widget_config)) {
                WidgetConfig config = gson.fromJson(reader, WidgetConfig.class);
                if (config != null) {
                    this.enabled = config.enabled;
                    this.selectedRecipe = config.selectedRecipe;
                    this.widgetX = config.widgetX;
                    this.widgetY = config.widgetY;
                    if (config.expandedNodes != null) {
                        this.expandedNodes = config.expandedNodes;
                    }
                    if (config.craftAmount > 0) {
                        this.craftAmount = config.craftAmount;
                    }
                    if (config.widgetWidth > 0) this.widgetWidth = config.widgetWidth;
                    if (config.widgetHeight > 0) this.widgetHeight = config.widgetHeight;
                    InventoryReader.LOGGER.info("Widget configuration loaded");
                    if (selectedRecipe != null) {
                        updateRecipeData();
                    }
                }
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            InventoryReader.LOGGER.error("Failed to load widget configuration", e);
        }
    }
    private static class WidgetConfig {
        boolean enabled;
        String selectedRecipe;
        int widgetX;
        int widgetY;
    int widgetWidth;
    int widgetHeight;
        Map<String, Boolean> expandedNodes;
        int craftAmount;
    public WidgetConfig(boolean enabled, String selectedRecipe, int widgetX, int widgetY, int widgetWidth, int widgetHeight,
                Map<String, Boolean> expandedNodes, int craftAmount) {
            this.enabled = enabled;
            this.selectedRecipe = selectedRecipe;
            this.widgetX = widgetX;
            this.widgetY = widgetY;
        this.widgetWidth = widgetWidth;
        this.widgetHeight = widgetHeight;
            this.expandedNodes = expandedNodes;
            this.craftAmount = craftAmount;
        }
    }
    public void startRepositioning() {
        if (enabled) {
            isRepositioning = true;
            Minecraft client = Minecraft.getInstance();
            client.mouseHandler.releaseMouse();
            if (client.player != null) {
                client.player.displayClientMessage(
                    Component.literal("Mouse cursor unlocked. Click anywhere to position the widget.")
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)), 
                    false
                );
            }
            InventoryReader.LOGGER.info("Widget repositioning mode activated");
        } else {
            InventoryReader.LOGGER.warn("Cannot reposition disabled widget");
        }
    }
    public void stopRepositioning() {
        if (isRepositioning) {
            isRepositioning = false;
            Minecraft.getInstance().mouseHandler.grabMouse();
            InventoryReader.LOGGER.info("Widget repositioning mode deactivated");
            saveConfiguration();
        }
    }
    public boolean isRepositioning() {
        return isRepositioning;
    }
    public boolean handleMouseClick(double mouseX, double mouseY) {
        if (isRepositioning) {
            widgetX = (int)mouseX;
            widgetY = (int)mouseY;
            isRepositioning = false;
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                Component message = Component.literal("Widget position set: X:" + widgetX + ", Y:" + widgetY)
                    .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN));
                client.player.displayClientMessage(message, true);
                client.mouseHandler.grabMouse();
            }
            return true;
        } else if (enabled && selectedRecipe != null && recipeTree != null) {
            return handleTreeNodeClick(mouseX, mouseY);
        }
        return false;
    }
    private boolean handleTreeNodeClick(double mouseX, double mouseY) {
        int panelWidth = widgetWidth;
        int panelX = widgetX;
        int panelY = widgetY;
        if (mouseX < panelX || mouseX > panelX + panelWidth) {
            return false;
        }
        int y = panelY + 20;
        return checkNodeClick(recipeTree, mouseX, mouseY, panelX, y, 0, selectedRecipe);
    }
    private boolean checkNodeClick(RecipeManager.RecipeNode node, double mouseX, double mouseY, int x, int y, int level, String pathKey) {
        if (node == null) return false;
    int unitIndent = Math.max(4, Math.round(RECIPE_LEVEL_INDENT * currentTreeScale));
    int indent = level * unitIndent;
    int nodeHeight = Math.max(6, currentNodeLineHeight);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int nodeWidth = Math.max(100, widgetWidth - 20) - indent;
        if (mouseY >= y && mouseY <= y + nodeHeight) {
            if (mouseX >= x + indent && mouseX <= x + indent + nodeWidth) {
                String nodeKey = makePathKey(pathKey, node.name);
                if (hasChildren) {
                    boolean expanded = expandedNodes.getOrDefault(nodeKey, false);
                    expandedNodes.put(nodeKey, !expanded);
                    return true;
                } else {
                    Minecraft client = Minecraft.getInstance();
                    Map<String, Integer> resources = resourcesManager.getAllResources();
                    int available = resources.getOrDefault(node.name, 0);
                    int remainingNeeded = node.amount;
                    boolean hasEnough = remainingNeeded == 0;
                    Component message = Component.literal("You need " + remainingNeeded + " more " + node.name + " (Have: " + available + ")")
                        .setStyle(Style.EMPTY.withColor(hasEnough ? ChatFormatting.GREEN : ChatFormatting.RED));
                    if (client.player != null) {
                        client.player.displayClientMessage(message, true);
                    }
                    return true;
                }
            }
        }
        y += nodeHeight;
    if (hasChildren && expandedNodes.getOrDefault(makePathKey(pathKey, node.name), false)) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
        boolean childClicked = checkNodeClick(child, mouseX, mouseY, x, y, level + 1, makePathKey(pathKey, node.name));
                if (childClicked) return true;
        y += getExpandedNodeHeight(child, makePathKey(pathKey, node.name)) * Math.max(6, currentNodeLineHeight) / 16;
            }
        }
        return false;
    }
    private int getExpandedNodeHeight(RecipeManager.RecipeNode node, String pathKey) {
        if (node == null) return 0;
        int height = 16;
        if (node.ingredients != null && !node.ingredients.isEmpty() && 
        expandedNodes.getOrDefault(makePathKey(pathKey, node.name), false)) {
        for (RecipeManager.RecipeNode child : node.ingredients) {
        height += getExpandedNodeHeight(child, makePathKey(pathKey, node.name));
            }
        }
        return height;
    }

    private void updateRecipeData() {
        if (!enabled || selectedRecipe == null) {
            return;
        }
        
        if (expandedNodes == null) {
            expandedNodes = new HashMap<>();
        }
        
        Map<String, Boolean> prevExpandedState = new HashMap<>(expandedNodes);
        ResourcesManager.RemainingResponse response = resourcesManager.getRemainingIngredients(selectedRecipe, craftAmount);
        
        messages.clear();
        
        if (response.messages != null && !response.messages.isEmpty()) {
            messages.add("Craftable -");
            
            List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(response.messages.entrySet());
            sortedEntries.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
            
            for (Map.Entry<String, Integer> entry : sortedEntries) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    messages.add("   " + entry.getValue() + "× " + entry.getKey());
                }
            }
        }
        
        
        
        recipeTree = convertResourceNodeToRecipeNode(response.full_recipe);
        boolean craftable = false;
        if (recipeTree != null && recipeTree.ingredients != null) {
            craftable = recipeTree.ingredients.stream().allMatch(child -> child.amount <= 0);
            if (craftable && messages.isEmpty()) {
                addMessage("Craftable -");
            } else if (!craftable && messages.isEmpty()) {
                addMessage("Craftable -");
            }
        }
        if (recipeTree != null) {
            expandedNodes.put(getNodeKey(recipeTree), true);
            preserveNodeExpansionStates(recipeTree, prevExpandedState);
        }
    }

    private RecipeManager.RecipeNode convertResourceNodeToRecipeNode(ResourcesManager.RecipeNode resourceNode) {
        if (resourceNode == null) return null;
        List<RecipeManager.RecipeNode> ingredients = new ArrayList<>();
        if (resourceNode.ingredients != null) {
            for (ResourcesManager.RecipeNode child : resourceNode.ingredients) {
                ingredients.add(convertResourceNodeToRecipeNode(child));
            }
        }
        return new RecipeManager.RecipeNode(resourceNode.name, resourceNode.amount, ingredients);
    }
    private void render(GuiGraphics context) {
        if (!enabled || selectedRecipe == null || recipeTree == null) {
            return;
        }
    Minecraft client = Minecraft.getInstance();
    int height = client.getWindow().getGuiScaledHeight();
    int panelWidth = widgetWidth;
    int visibleLines = countVisibleRecipeTreeLines(recipeTree, selectedRecipe);
    int panelMaxHeight = Math.min(height - 40, widgetHeight);

    int recipeTreeHeightMax = Math.max(0, visibleLines * 16);
    int messageLinesRaw = countMessageLines(client, panelWidth);
    int availableForMessagesMax = Math.max(0, Math.min((int)(height * 0.4), panelMaxHeight - 20 - recipeTreeHeightMax - 15));
    int messageSectionHeightEst = messageLinesRaw > 0 ? Math.min(messageLinesRaw * 10 + 20, availableForMessagesMax) : 0;

    int availableTreeHeight1 = Math.max(0, panelMaxHeight - 22 - (messageSectionHeightEst > 0 ? (messageSectionHeightEst + 15) : 0));
    int safeLines = Math.max(1, visibleLines);
    int computedLine1 = safeLines > 0 ? Math.max(6, (int)Math.floor((float)availableTreeHeight1 / (float)safeLines)) : 16;
    computedLine1 = Math.min(16, computedLine1);
    int treeHeightActual = safeLines * computedLine1;

    int availableForMessages2 = Math.max(0, Math.min((int)(height * 0.4), panelMaxHeight - 20 - treeHeightActual - 15));
    int messageSectionHeight = messageLinesRaw > 0 ? Math.min(messageLinesRaw * 10 + 20, availableForMessages2) : 0;
    int desiredPanelHeight = 20 + treeHeightActual + messageSectionHeight + 15;
    int panelHeight = Math.min(panelMaxHeight, desiredPanelHeight);
        
        int panelX = widgetX;
        int panelY = widgetY;
        
    context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, DARK_PANEL_BG);
        
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 20, HEADER_BG);
        
        int borderColor = 0xFFDAA520;
        int borderThickness = 2;
        for (int i = 0; i < borderThickness; i++) {
            drawBorder(
                context,
                panelX - i,
                panelY - i, 
                panelWidth + i * 2, 
                panelHeight + i * 2, 
                borderColor
            );
        }
        
        Component title = Component.literal("Recipe: " + selectedRecipe)
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true));
        int titleWidth = client.font.width(title);
        int maxTitleWidth = Math.max(20, panelWidth - 10);
        float titleScale = titleWidth > maxTitleWidth ? (float)maxTitleWidth / (float)titleWidth : 1.0f;
        context.pose().pushMatrix();
        context.pose().translate(panelX + (panelWidth - Math.min(titleWidth, maxTitleWidth)) / 2f, panelY + 5);
        context.pose().scale(titleScale, titleScale);
        context.drawString(
            client.font,
            title,
            0,
            0,
            0xFFFFFFFF,
            false
        );
        context.pose().popMatrix();

        if (isRepositioning) {
            String repoText = "◆ Click to place widget ◆";
            context.drawString(
                client.font,
                repoText,
                panelX + panelWidth - client.font.width(repoText) - 5,
                panelY + panelHeight - 12,
                GOLD,
                true
            );
        }
        
        context.fill(panelX, panelY + 19, panelX + panelWidth, panelY + 20, 0x99608C35);
        
    int y = panelY + 22;
    int availableTreeHeight = Math.max(0, panelHeight - 22 - (messageSectionHeight > 0 ? (messageSectionHeight + 15) : 0));
    int computedLine = safeLines > 0 ? Math.max(6, (int)Math.floor((float)availableTreeHeight / (float)safeLines)) : 16;
    computedLine = Math.min(16, computedLine);
    currentNodeLineHeight = computedLine;
    currentTreeScale = currentNodeLineHeight / 16.0f;
    int treeEndY = renderRecipeTree(context, recipeTree, panelX, y, 0, selectedRecipe);
        
        if (messageSectionHeight > 0) {
            context.fill(panelX, treeEndY, panelX + panelWidth, treeEndY + 1, 0x99608C35);
            
            int messageY = treeEndY + 6;
            
            int maxMessageY = panelY + panelHeight - 5;
            
            drawMessages(context, panelX, messageY, panelWidth, maxMessageY);
        }
    }
    private int countVisibleRecipeTreeLines(RecipeManager.RecipeNode node, String pathKey) {
        if (node == null) return 0;
        int count = 1;
        
        if (expandedNodes == null) {
            expandedNodes = new HashMap<>();
        }
        
        // Use path-based key to keep expansion stable regardless of amounts
        String nodeKey = makePathKey(pathKey, node.name);
        Boolean isExpanded = expandedNodes.getOrDefault(nodeKey, false);
        
        if (isExpanded == null) {
            isExpanded = false;
            expandedNodes.put(nodeKey, false);
        }
        
        if (node.ingredients != null && !node.ingredients.isEmpty() && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                count += countVisibleRecipeTreeLines(child, makePathKey(pathKey, node.name));
            }
        }
        return count;
    }
    private int renderRecipeTree(GuiGraphics context, RecipeManager.RecipeNode node, int x, int y, int level, String pathKey) {
        if (node == null) return y;
        Minecraft client = Minecraft.getInstance();
        int unitIndent = Math.max(4, Math.round(RECIPE_LEVEL_INDENT * currentTreeScale));
        int indent = level * unitIndent;
        boolean hasEnough = (node.amount == 0);
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = expandedNodes.getOrDefault(nodeKey, false);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int bgColor = 0x99271910;
        int mouseX = (int)(client.mouseHandler.xpos() / client.getWindow().getGuiScale());
        int mouseY = (int)(client.mouseHandler.ypos() / client.getWindow().getGuiScale());
        int nodeBaseWidth = Math.max(100, widgetWidth - 20); // account for panel padding
        int nodeHeight = Math.max(6, currentNodeLineHeight);
        boolean isHovered = mouseX >= x + indent && mouseX <= x + indent + nodeBaseWidth - indent && 
                            mouseY >= y && mouseY <= y + nodeHeight;
        int hoverEffect = isHovered ? 0x22FFFFFF : 0;
        int nodeWidth = nodeBaseWidth - indent;
        context.fill(x + indent, y, x + indent + nodeWidth, y + nodeHeight, bgColor + hoverEffect);
        int borderColor = hasEnough ? 0x88608C35 : 0x88FF5555;
        drawBorder(context, x + indent, y, nodeWidth, nodeHeight, borderColor);
        if (hasChildren) {
            String expandIcon = isExpanded ? "▼" : "▶";
            context.drawString(
                client.font,
                expandIcon,
                x + indent + Math.max(3, Math.round(5 * currentTreeScale)),
                y + Math.max(1, Math.round(4 * currentTreeScale)),
                0xFFFFFFFF,
                false
            );
        }
    int nameX = x + indent + (hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale)));
        int textColor;
        boolean isBold = (level == 0);
        if (level == 0) {
            textColor = GOLD; 
        } else {
            textColor = hasEnough ? 0xFFFFFFFF : 0xFFFF6B6B;
        }
        String amountText = node.amount + "× ";
        int amountColor = hasEnough ? 0xFF6EFF6E : 0xFFFF6B6B;
        Component itemName = Component.literal(node.name)
            .setStyle(Style.EMPTY.withColor(textColor).withBold(isBold));
        int amountWidth = client.font.width(amountText);
        int itemWidth = client.font.width(itemName);
        int totalTextWidth = amountWidth + itemWidth;
        int maxTextWidth = Math.max(10, nodeWidth - (hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale))));
        int adjustedMaxTextWidth = (int)Math.floor(maxTextWidth / Math.max(0.01f, currentTreeScale));
        float textScaleLocal = totalTextWidth > adjustedMaxTextWidth ? (float)adjustedMaxTextWidth / (float)totalTextWidth : 1.0f;
        float textScale = Math.min(1.0f, textScaleLocal) * currentTreeScale;
        context.pose().pushMatrix();
        context.pose().translate(nameX, y + Math.max(1, Math.round(4 * currentTreeScale)));
        context.pose().scale(textScale, textScale);
        context.drawString(
            client.font,
            amountText,
            0,
            0,
            amountColor,
            false
        );
        context.drawString(
            client.font,
            itemName,
            amountWidth,
            0,
            0xFFFFFFFF,
            false
        );
        context.pose().popMatrix();
        y += nodeHeight;
    if (hasChildren && isExpanded && node.ingredients.size() > 0) {
            int lineColor = 0xFF777777;
            for (int i = 0; i < node.ingredients.size(); i++) {
                RecipeManager.RecipeNode child = node.ingredients.get(i);
                int lineStartX = x + indent + Math.max(4, Math.round(6 * currentTreeScale));
                int vertLineY = y;
                int childIndentX = x + indent + unitIndent;
                int vertLen = Math.max(3, Math.round(8 * currentTreeScale));
                context.fill(lineStartX, vertLineY, lineStartX + 1, vertLineY + vertLen, lineColor);
                context.fill(lineStartX, vertLineY + vertLen, childIndentX, vertLineY + vertLen + 1, lineColor);
        int nextY = renderRecipeTree(context, child, x, y, level + 1, nodeKey);
                y = nextY;
            }
        }
        return y;
    }
    private void preserveNodeExpansionStates(RecipeManager.RecipeNode node, Map<String, Boolean> prevStates) {
        if (node == null) return;
        
        if (expandedNodes == null) {
            expandedNodes = new HashMap<>();
        }
        
    String nodeKey = makePathKey(selectedRecipe, node.name);
    if (prevStates != null && prevStates.containsKey(nodeKey)) {
            Boolean value = prevStates.get(nodeKey);
            expandedNodes.put(nodeKey, value != null ? value : false);
        }
        if (node.ingredients != null) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                preserveNodeExpansionStates(child, prevStates);
            }
        }
    }
    private void drawMessages(GuiGraphics context, int x, int y, int width, int maxY) {
        Minecraft client = Minecraft.getInstance();
        int baseHeader = 13;
        int baseLine = 10;
        int availableHeight = Math.max(0, maxY - y);
        int lines = countMessageLines(client, width);
        int desiredHeight = baseHeader + Math.max(0, (lines - 1) * baseLine);
        float scale = desiredHeight > 0 ? Math.min(1.0f, Math.max(0.4f, (float)availableHeight / (float)desiredHeight)) : 1.0f;

        if (y + Math.round(baseLine * scale) <= maxY) {
            Component messagesHeader = Component.literal("Craftable -")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true));
            context.pose().pushMatrix();
            context.pose().translate(x + 5, y);
            context.pose().scale(scale, scale);
            context.drawString(
                client.font,
                messagesHeader,
                0,
                0,
                0xFFFFFFFF,
                false
            );
            context.pose().popMatrix();
        } else {
            return;
        }

        if (messages.size() == 1 && messages.get(0).equals("Craftable -")) {
            return;
        }
        y += Math.round(baseHeader * scale);

        List<String> messagesCopy = new ArrayList<>(messages);
        messagesCopy.sort((a, b) -> {
            if (a.equals("Craftable -")) return -1;
            if (b.equals("Craftable -")) return 1;
            try {
                int amountA = extractAmount(a);
                int amountB = extractAmount(b);
                return Integer.compare(amountB, amountA);
            } catch (Exception e) {
                return 0;
            }
        });

        int unscaledWrapWidth = Math.max(10, (int)Math.floor((width - 15) / Math.max(0.01f, scale)));
        for (String message : messagesCopy) {
            if (message.equals("Craftable -")) continue;
            int textColor = message.startsWith("   ") ? 0xFFFF9D00 : 0xFFFFFFFF;
            String[] words = message.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (client.font.width(line.toString() + word) > unscaledWrapWidth) {
                    if (y + Math.round(baseLine * scale) > maxY) return;
                    context.pose().pushMatrix();
                    context.pose().translate(x + 5, y);
                    context.pose().scale(scale, scale);
                    context.drawString(
                        client.font,
                        line.toString(),
                        0,
                        0,
                        textColor,
                        false
                    );
                    context.pose().popMatrix();
                    y += Math.round(baseLine * scale);
                    line = new StringBuilder(message.startsWith("   ") ? "      " : "   ").append(word).append(" ");
                } else {
                    line.append(word).append(" ");
                }
            }
            if (line.length() > 0) {
                if (y + Math.round((baseLine - 1) * scale) > maxY) return;
                context.pose().pushMatrix();
                context.pose().translate(x + 5, y);
                context.pose().scale(scale, scale);
                context.drawString(
                    client.font,
                    line.toString(),
                    0,
                    0,
                    textColor,
                    false
                );
                context.pose().popMatrix();
                y += Math.round((baseLine - 1) * scale);
            }
        }
    }
    
    private int extractAmount(String message) {
        try {
            int xIndex = message.indexOf('×');
            if (xIndex > 0) {
                String amountStr = message.substring(0, xIndex).trim();
                return Integer.parseInt(amountStr);
            }
        } catch (Exception e) {
        }
        return 0;
    }
    public void addMessage(String message) {
        this.messages.add(message);
    }
    public List<String> getMessagesSnapshot() {
        return new ArrayList<>(this.messages);
    }
    public int getCraftAmount() {
        return craftAmount;
    }
    public void setCraftAmount(int craftAmount) {
        if (craftAmount < 1) {
            craftAmount = 1;
        }
        this.craftAmount = craftAmount;
        updateRecipeData();
        saveConfiguration();
    }
    private int countMessageLines(Minecraft client, int width) {
        int lineCount = 1;
        if (messages.isEmpty()) return lineCount;
        if (messages.size() == 1 && messages.get(0).equals("Craftable -")) return lineCount;
        for (String message : new ArrayList<>(messages)) {
            if (message.equals("Craftable -")) continue;
            String[] words = message.split(" ");
            StringBuilder line = new StringBuilder();
            int linesInMessage = 1;
            for (String word : words) {
                if (client.font.width(line.toString() + word) > width - 15) {
                    linesInMessage++;
                    line = new StringBuilder(message.startsWith("   ") ? "      " : "   ").append(word).append(" ");
                } else {
                    line.append(word).append(" ");
                }
            }
            lineCount += linesInMessage;
        }
        return lineCount;
    }

    public static String makePathKey(String parent, String name) {
        if (parent == null || parent.isEmpty()) return name == null ? "" : name;
        if (name == null || name.isEmpty()) return parent;
        return parent + ">" + name;
    }

    // Helper method to draw borders since drawBorder was removed from the rendering API in 1.21.10
    private static void drawBorder(GuiGraphics context, int x, int y, int width, int height, int color) {
        context.fill(x, y, x + width, y + 1, color);
        context.fill(x, y + height - 1, x + width, y + height, color);
        context.fill(x, y, x + 1, y + height, color);
        context.fill(x + width - 1, y, x + width, y + height, color);
    }
}

