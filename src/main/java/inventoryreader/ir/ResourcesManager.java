package inventoryreader.ir;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.io.File;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;

public class ResourcesManager {

    private static final ResourcesManager INSTANCE = new ResourcesManager();
    private static final File resourcesFile = FilePathManager.getResourcesFile();
    private static final Object RES_FILE_LOCK = new Object();
    private final Map<String, Integer> pendingChanges = new LinkedHashMap<>();

    private ResourcesManager() {}

    public static ResourcesManager getInstance() {
        return INSTANCE;
    }

    private void directSave(Map<String, Integer> resources) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        synchronized (RES_FILE_LOCK) {
            atomicWriteJson(resourcesFile, gson.toJson(resources));
        }
    }

    public void saveData(Map<String, Integer> data) {
        if (!FilePathManager.areResourceNamesSeeded()) {
            synchronized (pendingChanges) {
                mergeInto(pendingChanges, data);
            }
            return;
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        synchronized (RES_FILE_LOCK) {
            Map<String, Integer> resources = new java.util.LinkedHashMap<>();
            Type type = new TypeToken<Map<String, Integer>>(){}.getType();
            try (FileReader reader = new FileReader(resourcesFile)) {
                Map<String, Integer> loaded = gson.fromJson(reader, type);
                if (loaded != null) {
                    resources.putAll(loaded);
                }
            } catch (IOException e) {
                // ignore: treat as empty
            }
            resources.entrySet().removeIf(e -> {
                String k = e.getKey();
                return k == null || k.trim().isEmpty() || k.trim().matches("\\d+");
            });
            Map<String, Integer> toApply = new LinkedHashMap<>();
            synchronized (pendingChanges) {
                if (!pendingChanges.isEmpty()) {
                    toApply.putAll(pendingChanges);
                    pendingChanges.clear();
                }
            }
            mergeInto(toApply, data);
            applyDeltas(resources, toApply);
            atomicWriteJson(resourcesFile, gson.toJson(resources));
        }
    }

    public boolean flushPendingIfReady() {
        if (!FilePathManager.areResourceNamesSeeded()) return false;
        Map<String, Integer> snapshot;
        synchronized (pendingChanges) {
            if (pendingChanges.isEmpty()) return true;
            snapshot = new LinkedHashMap<>(pendingChanges);
            pendingChanges.clear();
        }
        saveData(snapshot);
        return true;
    }

    private void mergeInto(Map<String, Integer> target, Map<String, Integer> delta) {
        if (delta == null || delta.isEmpty()) return;
        for (Map.Entry<String, Integer> e : delta.entrySet()) {
            String k = e.getKey();
            if (k == null || k.trim().isEmpty() || k.trim().matches("\\d+")) continue;
            int v = e.getValue() == null ? 0 : e.getValue();
            target.put(k, target.getOrDefault(k, 0) + v);
        }
    }

    private void applyDeltas(Map<String, Integer> resources, Map<String, Integer> delta) {
        for (Map.Entry<String, Integer> e : delta.entrySet()) {
            String cleaned = cleanItemName(e.getKey());
            if (cleaned.isEmpty()) {
                continue;
            }
            String targetKey = cleaned;
            int value = e.getValue();
            if (resources.containsKey(targetKey)) {
                resources.put(targetKey, resources.get(targetKey) + value);
                continue;
            }
            String[] itemSplit = cleaned.split(" ");
            if (itemSplit.length > 1) {
                String itemRefined = String.join(" ", Arrays.copyOfRange(itemSplit, 1, itemSplit.length)).trim();
                if (!itemRefined.trim().matches("\\d+") && resources.containsKey(itemRefined)) {
                    resources.put(itemRefined, resources.get(itemRefined) + value);
                }
            }
        }
    }

    private String cleanItemName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("✪", "")
                  .replace("➊", "")
                  .replace("➋", "")
                  .replace("➌", "")
                  .replace("➍", "")
                  .replace("➎", "")
                  .trim();
    }

    public Map<String, Integer> getAllResources() {
        Gson gson = new Gson();
        if (!resourcesFile.exists() || resourcesFile.length() == 0) {
            return new LinkedHashMap<>();
        }
        try (FileReader reader = new FileReader(resourcesFile)) {
            Type type = new TypeToken<Map<String, Integer>>(){}.getType();
            Map<String, Integer> resources = gson.fromJson(reader, type);
            if (resources != null) {
                return new LinkedHashMap<>(resources);
            }
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            // On parse error, return empty snapshot; writes are atomic so this should be rare
        }
        return new LinkedHashMap<>();
    }

    // Write JSON via a temp file then atomically move into place to avoid partial reads
    private void atomicWriteJson(File target, String json) {
        try {
            File dir = target.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = File.createTempFile(target.getName(), ".tmp", dir);
            try (FileWriter writer = new FileWriter(tmp)) {
                writer.write(json);
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicFail) {
                // Fallback if filesystem doesn't support ATOMIC_MOVE
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Integer getResourceByName(String name) {
        Map<String, Integer> resources = getAllResources();
        return resources.getOrDefault(name, 0);
    }

    public void setResourceAmount(String name, int amount) {
        Map<String, Integer> resources = getAllResources();
        resources.put(name, amount);
        directSave(resources);
    }

    public void craft(String name, int amt) {
        RecipeManager rm = RecipeManager.getInstance();
        Map<String, Map<String, Integer>> forging = rm.getAllRecipes();
        Map<String, Integer> myResources = getAllResources();
        myResources.put(name, myResources.getOrDefault(name, 0) + amt);
        for (Map.Entry<String, Integer> entry : forging.get(name).entrySet()) {
            craftItem(entry.getKey(), entry.getValue() * amt, forging, myResources);
        }
        saveData(myResources);
    }
    
    private void craftItem(String currentItem, int multiplier, Map<String, Map<String, Integer>> forging, Map<String, Integer> myResources) {
        if (forging.containsKey(currentItem)) {
            int available = myResources.getOrDefault(currentItem, 0);
            if (available < multiplier) {
                int remaining = multiplier - available;
                myResources.put(currentItem, 0);
                for (Map.Entry<String, Integer> entry : forging.get(currentItem).entrySet()) {
                    craftItem(entry.getKey(), entry.getValue() * remaining, forging, myResources);
                }
            } else {
                myResources.put(currentItem, available - multiplier);
            }
        } else {
            int current = myResources.getOrDefault(currentItem, 0);
            myResources.put(currentItem, Math.max(0, current - multiplier));
        }
    }
    
    public void updateResourcesAfterCraft(String name, int amt) {
        craft(name, amt);
    }

    public Map<String, Integer> getSimpleRemainingIngredients(String name, int amt) {
        Map<String, Integer> resources = getAllResources();
        Map<String, Integer> needed = new LinkedHashMap<>();
        calculateNeeded(name, amt, resources, needed);
        return needed;
    }

    private void calculateNeeded(String name, int amt, Map<String, Integer> resources, Map<String, Integer> needed) {
        RecipeManager rm = RecipeManager.getInstance();
        if (!rm.getAllRecipes().containsKey(name)) {
            int have = resources.getOrDefault(name, 0);
            if (have < amt) {
                needed.put(name, amt - have);
            }
            return;
        }
        Map<String, Integer> recipe = rm.getAllRecipes().get(name);
        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            String ingredient = entry.getKey();
            int required = entry.getValue() * amt;
            int have = resources.getOrDefault(ingredient, 0);
            if (rm.getAllRecipes().containsKey(ingredient)) {
                calculateNeeded(ingredient, required, resources, needed);
            } else {
                if (have < required) {
                    needed.put(ingredient, needed.getOrDefault(ingredient, 0) + (required - have));
                }
            }
        }
    }

    public List<ResourceEntry> getAllResourceEntries() {
        Map<String, Integer> map = getAllResources();
        List<ResourceEntry> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getValue() > 0) {
                list.add(new ResourceEntry(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }
    
    public List<ResourceEntry> getAllResourceEntriesIncludingZero() {
        Map<String, Integer> map = getAllResources();
        List<ResourceEntry> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            list.add(new ResourceEntry(entry.getKey(), entry.getValue()));
        }
        // Sort resources by name for better readability in the UI
        Collections.sort(list, (a, b) -> a.name.compareTo(b.name));
        return list;
    }

    public RemainingResponse getRemainingIngredients(String name, int amt) {
        RecipeManager rm = RecipeManager.getInstance();
        Map<String, Map<String, Integer>> forging = rm.getAllRecipes();
        Map<String, Integer> highestPossibleResources = getAllResources();
        Map<String, Integer> currentAvailableResources = new LinkedHashMap<>(highestPossibleResources);
        Map<String, Integer> messages = new LinkedHashMap<>();
        
        // Initialize resources with default values to avoid null pointer exceptions
        initializeResourceMaps(name, forging, highestPossibleResources, currentAvailableResources);
        
        int old = highestPossibleResources.getOrDefault(name, 0);
        buildRecipe(name, amt, forging, highestPossibleResources, currentAvailableResources, messages);
        int updated = highestPossibleResources.getOrDefault(name, 0);
        
        RecipeNode fullRecipe;
        if (updated - old >= amt) {
            fullRecipe = expandRequiredRecipe(name, (updated-old)-amt, forging, highestPossibleResources);
        } else {
            fullRecipe = expandRequiredRecipe(name, amt-(updated-old), forging, highestPossibleResources);
        }
        return new RemainingResponse(name, fullRecipe, messages);
    }

    private void buildRecipe(String currentItem, int multiplier, Map<String, Map<String, Integer>> forging, 
                            Map<String, Integer> highestPossibleResources, Map<String, Integer> currentAvailableResources, 
                            Map<String, Integer> messages) {
        Map<String, Integer> recipe = forging.get(currentItem);
        Map<String, Integer> madeResources = new LinkedHashMap<>();
        if (recipe == null) return;
        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            String item = entry.getKey();
            int quantity = entry.getValue();
            if (forging.containsKey(item)) {
                int need = Math.max(0, (quantity * multiplier) - currentAvailableResources.getOrDefault(item, 0));
                if (need > 0) {
                    buildRecipe(item, need, forging, highestPossibleResources, currentAvailableResources, messages);
                    madeResources.put(item, currentAvailableResources.getOrDefault(item, 0));
                    currentAvailableResources.put(item, 0);
                }else{
                    madeResources.put(item, quantity * multiplier);
                    currentAvailableResources.put(item, currentAvailableResources.getOrDefault(item, 0) - quantity * multiplier);
                }
            }
        }
        for (Map.Entry<String, Integer> entry : madeResources.entrySet()) {
            String item = entry.getKey();
            int quantity = entry.getValue();
            if (quantity > 0) {
                currentAvailableResources.put(item, 
                        currentAvailableResources.getOrDefault(item, 0) + quantity);
            }
        }
        check(currentItem, multiplier, forging, highestPossibleResources, currentAvailableResources, messages);
    }

    private void check(String currentItem, int multiplier, Map<String, Map<String, Integer>> forging, 
                       Map<String, Integer> highestPossibleResources, Map<String, Integer> currentAvailableResources, 
                       Map<String, Integer> messages) {
        Map<String, Integer> recipe = forging.get(currentItem);
        if (recipe == null) return;
        
        List<Integer> count = new ArrayList<>();
        Map<String, Integer> possibleItemsDict = new LinkedHashMap<>();
        
        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            String baseItem = entry.getKey();
            int quantityOfBaseItem = entry.getValue();
            int possibleItems = currentAvailableResources.getOrDefault(baseItem, 0) / quantityOfBaseItem;
            possibleItemsDict.put(baseItem, possibleItems);
            count.add(multiplier - possibleItems);
        }
        
        int maxcount = count.stream().mapToInt(i -> i).max().orElse(0);
        maxcount = Math.max(maxcount, 0); // If maxcount <= 0, we have enough resources
        
        int amountAbleToCraft = multiplier - maxcount;
        
        highestPossibleResources.put(currentItem, 
                                    highestPossibleResources.getOrDefault(currentItem, 0) + amountAbleToCraft);
        currentAvailableResources.put(currentItem, 
                                     currentAvailableResources.getOrDefault(currentItem, 0) + amountAbleToCraft);
        
        if (amountAbleToCraft > 0) {
            messages.put(currentItem, 
                          messages.getOrDefault(currentItem, 0) + amountAbleToCraft);
        }
        
        allocate(currentItem, multiplier, maxcount, possibleItemsDict, forging, 
                highestPossibleResources, currentAvailableResources);
    }

    private void allocate(String currentItem, int multiplier, int maxcount, Map<String, Integer> possibleItemsDict,
                          Map<String, Map<String, Integer>> forging, Map<String, Integer> highestPossibleResources, 
                          Map<String, Integer> currentAvailableResources) {
        Map<String, Integer> recipe = forging.get(currentItem);
        if (recipe == null) return;
        
        int amountAbleToCraftOfHigherMaterial = multiplier - maxcount;

        for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
            String baseItem = entry.getKey();
            int quantityOfBaseItem = entry.getValue();
            highestPossibleResources.put(baseItem, 
                   highestPossibleResources.getOrDefault(baseItem, 0) - 
                   quantityOfBaseItem * amountAbleToCraftOfHigherMaterial);
        }

        if (multiplier != 0) {
            for (Map.Entry<String, Integer> entry : recipe.entrySet()) {
                String baseItem = entry.getKey();
                int quantityOfBaseItem = entry.getValue();
                int possibleItems = possibleItemsDict.getOrDefault(baseItem, 0);
                int amountLeftToAllocate = Math.min(multiplier, possibleItems);
                currentAvailableResources.put(baseItem, 
                       currentAvailableResources.getOrDefault(baseItem, 0) - 
                       quantityOfBaseItem * amountLeftToAllocate);
            }
        }
    }

    private RecipeNode expandRequiredRecipe(String currentName, int multiplier, Map<String, Map<String, Integer>> forging, Map<String, Integer> highestPossibleResources) {
        if (!forging.containsKey(currentName)) {
            int have = highestPossibleResources.getOrDefault(currentName, 0);
            if (have < multiplier) {
                int temp = have;
                highestPossibleResources.put(currentName, 0);
                return new RecipeNode(currentName, multiplier - temp, Collections.emptyList());
            } else {
                highestPossibleResources.put(currentName, have - multiplier);
                return new RecipeNode(currentName, 0, Collections.emptyList());
            }
        } else {
            List<RecipeNode> ingredients = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : forging.get(currentName).entrySet()) {
                String item = entry.getKey();
                int qty = entry.getValue();
                if (forging.containsKey(item)) {
                    if (highestPossibleResources.getOrDefault(item, 0) < qty * multiplier) {
                        RecipeNode expanded = expandRequiredRecipe(item, 
                                             (qty * multiplier) - highestPossibleResources.getOrDefault(item, 0), 
                                             forging, highestPossibleResources);
                        ingredients.add(expanded);
                        highestPossibleResources.put(item, 0);
                    } else {
                        highestPossibleResources.put(item, highestPossibleResources.getOrDefault(item, 0) - qty * multiplier);
                        RecipeNode expanded = expandRequiredRecipe(item, 0, forging, highestPossibleResources);
                        ingredients.add(expanded);
                    }
                } else {
                    if (highestPossibleResources.getOrDefault(item, 0) < qty * multiplier) {
                        RecipeNode expanded = expandRequiredRecipe(item, qty * multiplier, forging, highestPossibleResources);
                        ingredients.add(expanded);
                    } else {
                        highestPossibleResources.put(item, highestPossibleResources.getOrDefault(item, 0) - qty * multiplier);
                        RecipeNode expanded = expandRequiredRecipe(item, 0, forging, highestPossibleResources);
                        ingredients.add(expanded);
                    }
                }
            }
            return new RecipeNode(currentName, multiplier, ingredients);
        }
    }

    private void initializeResourceMaps(String targetItem, Map<String, Map<String, Integer>> forging, 
                                       Map<String, Integer> highestPossibleResources,
                                       Map<String, Integer> currentAvailableResources) {
        highestPossibleResources.putIfAbsent(targetItem, 0);
        currentAvailableResources.putIfAbsent(targetItem, 0);
        
        if (forging.containsKey(targetItem)) {
            Map<String, Integer> recipe = forging.get(targetItem);
            for (String ingredient : recipe.keySet()) {
                highestPossibleResources.putIfAbsent(ingredient, 0);
                currentAvailableResources.putIfAbsent(ingredient, 0);
                
                if (forging.containsKey(ingredient)) {
                    initializeResourceMaps(ingredient, forging, highestPossibleResources, currentAvailableResources);
                }
            }
        }
    }

    public static class ResourceEntry {
        public String name;
        public int amount;
        public ResourceEntry(String name, int amount) {
            this.name = name;
            this.amount = amount;
        }
    }

    public static class RemainingResponse {
        public String name;
        public RecipeNode full_recipe;
        public Map<String, Integer> messages;
        public List<String> messagesList;
        
        public RemainingResponse(String name, RecipeNode fullRecipe, Map<String, Integer> messages) {
            this.name = name;
            this.full_recipe = fullRecipe;
            this.messages = messages;
            
            // Create a list version of messages for compatibility with the Python implementation
            this.messagesList = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : messages.entrySet()) {
                this.messagesList.add("You need to craft x" + entry.getValue() + " " + entry.getKey());
            }
        }
        
        public RemainingResponse(String name, RecipeNode fullRecipe, List<String> messagesList) {
            this.name = name;
            this.full_recipe = fullRecipe;
            this.messagesList = messagesList;
            
            // Convert list to map for backward compatibility
            this.messages = new LinkedHashMap<>();
            for (String msg : messagesList) {
                String[] parts = msg.split(" ");
                if (parts.length >= 4) {
                    String itemName = extractItemName(parts);
                    try {
                        int amount = Integer.parseInt(parts[3].substring(1));
                        this.messages.put(itemName, this.messages.getOrDefault(itemName, 0) + amount);
                    } catch (NumberFormatException e) {
                        // Skip if we can't parse the amount
                    }
                }
            }
        }
        
        private String extractItemName(String[] parts) {
            if (parts.length > 5) {
                StringBuilder nameBuilder = new StringBuilder(parts[4]);
                for (int i = 5; i < parts.length; i++) {
                    nameBuilder.append(" ").append(parts[i]);
                }
                return nameBuilder.toString();
            }
            return parts[4];
        }
    }

    public static class RecipeNode {
        public String name;
        public int amount;
        public java.util.List<RecipeNode> ingredients;
        
        public RecipeNode(String name, int amount, java.util.List<RecipeNode> ingredients) {
            this.name = name;
            this.amount = amount;
            this.ingredients = ingredients;
        }
    }
}
