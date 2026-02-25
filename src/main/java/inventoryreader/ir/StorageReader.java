package inventoryreader.ir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class StorageReader {
    private static final File DATA_FILE = new File(FilePathManager.DATA_DIR, "allcontainerData.json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final ResourcesManager RESOURCES_MANAGER = ResourcesManager.getInstance();
    private static final StorageReader INSTANCE = new StorageReader();

    private StorageReader() {}

    public static StorageReader getInstance() {
        return INSTANCE;
    }

    private final Map<String, Integer> changesData = new HashMap<>();

    public Map<String, Map<String, Integer>> loadAllContainerDataFromFile() {
        Type type = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
        if (!DATA_FILE.exists()) {
            return new HashMap<>();
        }
        try (FileReader reader = new FileReader(DATA_FILE)) {
            Map<String, Map<String, Integer>> data = gson.fromJson(reader, type);
            return data != null ? data : new HashMap<>();
        } catch (IOException e) {
            InventoryReader.LOGGER.error("Failed to read data from file", e);
            return new HashMap<>();
        }
    }

    private void saveAllContainerDataToFile(Map<String, Map<String, Integer>> allcontainerData) {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            gson.toJson(allcontainerData, writer);
        } catch (IOException e) {
            InventoryReader.LOGGER.error("Failed to save data to file", e);
        }
    }

    public void saveContainerContents(AbstractContainerMenu handler, String title) {
        Map<String, Map<String, Integer>> fileSaveContainerData = loadAllContainerDataFromFile();

        if (!title.contains("Backpack") && !title.contains("Ender Chest") && !title.contains("The Forge") && !title.contains("Accessory Bag")) {
            return;
        }

        if (fileSaveContainerData.containsKey(title)) {
            compareContainerData(handler, title, fileSaveContainerData);
            return;
        }

        List<Slot> slots = handler.slots;
        int slotsToIterate = slots.size() - 36;
        Map<String, Integer> newData = new HashMap<>();

        for (int i = 0; i < slotsToIterate; i++) {
            Slot slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                String itemName = stack.getHoverName().getString();
                int itemCount = stack.getCount();
                newData.put(itemName, newData.getOrDefault(itemName, 0) + itemCount);
            }
        }
        fileSaveContainerData.put(title, newData);
        saveAllContainerDataToFile(fileSaveContainerData);
        RESOURCES_MANAGER.saveData(newData);
    }

    public void compareContainerData(AbstractContainerMenu handler, String title, Map<String, Map<String, Integer>> allcontainerData) {
        List<Slot> slots = handler.slots;
        int slotsToIterate = slots.size() - 36;

        Map<String, Integer> previousData = allcontainerData.get(title);
        Map<String, Integer> newData = new HashMap<>();

        for (int i = 0; i < slotsToIterate; i++) {
            Slot slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                String itemName = stack.getHoverName().getString();
                int itemCount = stack.getCount();
                newData.put(itemName, newData.getOrDefault(itemName, 0) + itemCount);
            }
        }

        for (Map.Entry<String, Integer> entry : newData.entrySet()) {
            String itemName = entry.getKey();
            int newCount = entry.getValue();
            int previousCount = previousData.getOrDefault(itemName, 0);

            if (newCount != previousCount) {
                changesData.put(itemName, newCount-previousCount);
                previousData.put(itemName, newCount);
            }
        }

        for (Map.Entry<String, Integer> entry : previousData.entrySet()) {
            if (!newData.containsKey(entry.getKey()) && entry.getValue() > 0) {
                changesData.put(entry.getKey(), -entry.getValue());
            }
        }

        previousData.entrySet().removeIf(entry -> !newData.containsKey(entry.getKey()) || entry.getValue() == 0);

        allcontainerData.put(title, previousData);
        saveAllContainerDataToFile(allcontainerData);
        RESOURCES_MANAGER.saveData(changesData);
        changesData.clear();
    }

    public void clearAllData() {
        changesData.clear();
    }
}