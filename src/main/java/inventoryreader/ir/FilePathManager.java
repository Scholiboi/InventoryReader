package inventoryreader.ir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Iterator;

import net.fabricmc.loader.api.FabricLoader;
import inventoryreader.ir.recipes.RecipeRegistry;
import inventoryreader.ir.recipes.RemoteRecipeFetcher;

public class FilePathManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryReader.MOD_ID);

    public static final File MOD_DIR = new File(FabricLoader.getInstance().getGameDir().toFile(), ".ir-data");
    public static final File DATA_DIR = new File(MOD_DIR, "data");
    private static final String MOD_VERSION = getModVersionString();
    private static final File file_generic = new File(FilePathManager.DATA_DIR, "allcontainerData.json");
    private static final File file_inventory = new File(FilePathManager.DATA_DIR, "inventorydata.json");
    private static final File file_resources = new File(FilePathManager.DATA_DIR, "resources.v" + MOD_VERSION + ".json");
    private static final File SACK_NAMES_FILE = new File(FilePathManager.DATA_DIR, "sackNames.txt");
    public static final File file_widget_config = new File(FilePathManager.DATA_DIR, "widget_config.json");
    public static final File FORGING_JSON = new File(FilePathManager.DATA_DIR, "forging.v" + MOD_VERSION + ".json");
    public static final File GEMSTONE_RECIPES_JSON = new File(FilePathManager.DATA_DIR, "gemstone_recipes.v" + MOD_VERSION + ".json");
    public static final File MERGED_RECIPES_JSON = new File(FilePathManager.DATA_DIR, "recipes_all.json");
    public static final File REMOTE_RECIPES_JSON = new File(FilePathManager.DATA_DIR, "recipes_remote.json");
    public static final File REMOTE_FORGE_JSON = new File(FilePathManager.DATA_DIR, "recipes_remote_forge.json");
    public static final File REMOTE_SOURCES_JSON = new File(FilePathManager.DATA_DIR, "remote_sources.json");
    public static final File REMOTE_META_JSON = new File(FilePathManager.DATA_DIR, "remote_sources_meta.json");
    /** Extracted NEU-REPO ZIP contents — read by NEURepository via the neurepoparser library. */
    public static final File NEU_REPO_EXTRACTED = new File(FilePathManager.DATA_DIR, "neu-repo-extracted");
    private static volatile boolean RESOURCES_SEEDED = false;

    static {
        initializeDirectories();
    }

    public static void initializeDirectories() {
        createDirectory(MOD_DIR);
        createDirectory(DATA_DIR);
        createDirectory(NEU_REPO_EXTRACTED);
        initializeFiles();
    }

    public static void reInitializeFiles() {
        reinitializeFiles();
    }

    private static void createDirectory(File directory) {
        if (!directory.exists()) {
            directory.mkdirs();
            LOGGER.info("Created directory: {}", directory.getAbsolutePath());
        }
    }

    private static void initializeFiles() {
    RESOURCES_SEEDED = false;
        if (!file_generic.exists()) {
            try {
                file_generic.createNewFile();
                LOGGER.info("Created file: {}", file_generic.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Failed to create file: {}", file_generic.getAbsolutePath(), e);
            }
        }

        if (!file_inventory.exists()) {
            try {
                file_inventory.createNewFile();
            } catch (IOException e) {
            }
        }
        if (!SACK_NAMES_FILE.exists()) {
            try {
                SACK_NAMES_FILE.createNewFile();
            } catch (IOException e) {
            }
        }

        migrateLegacyFilenames();

        ensureCurrentFilesWithCarryOver();

        if (!file_widget_config.exists()) {
            initializeWidgetConfigData(file_widget_config);
        }

        if (!REMOTE_SOURCES_JSON.exists()) {
            initializeRemoteSourcesConfig(REMOTE_SOURCES_JSON);
        }

    cleanupAllOldVersioned();

        try {
            RecipeRegistry.bootstrap();
        } catch (Throwable t) {
            LOGGER.warn("RecipeRegistry bootstrap failed (continuing with static files only)", t);
        }
        try {
            seedResourceNamesFromRecipes();
        } catch (Throwable t) {
            LOGGER.warn("Seeding resource names failed", t);
        }
        try {
            RemoteRecipeFetcher.fetchAsync();
        } catch (Throwable t) {
            LOGGER.warn("RemoteRecipeFetcher failed to start", t);
        }
    }

    public static File getResourcesFile() { return file_resources; }

    private static void reinitializeFiles() {
    RESOURCES_SEEDED = false;
        if (file_generic.exists()) {
            file_generic.delete();
        }
        if (file_inventory.exists()) {
            file_inventory.delete();
        }
        if (file_resources.exists()) {
            file_resources.delete();
        }
        if (file_widget_config.exists()) {
            file_widget_config.delete();
        }
        if (SACK_NAMES_FILE.exists()) {
            SACK_NAMES_FILE.delete();
        }
    if (MERGED_RECIPES_JSON.exists()) MERGED_RECIPES_JSON.delete();
    
        initializeFiles();
    }

    public static boolean areResourceNamesSeeded() { return RESOURCES_SEEDED; }

    private static void seedResourceNamesFromRecipes() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        addRecipeFileNames(FORGING_JSON, names);
        addRecipeFileNames(GEMSTONE_RECIPES_JSON, names);
        addRecipeFileNames(REMOTE_RECIPES_JSON, names);
        addRecipeFileNames(REMOTE_FORGE_JSON, names);
        ensureResourceNames(names);
        RESOURCES_SEEDED = true;
        try {
            inventoryreader.ir.ResourcesManager.getInstance().flushPendingIfReady();
        } catch (Throwable ignored) {}
    }

    private static void addRecipeFileNames(File file, java.util.Set<String> out) {
        if (file == null || !file.exists() || file.length() == 0) return;
        try (FileReader fr = new FileReader(file)) {
            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.Map<String, java.util.Map<String, Integer>>>(){}.getType();
            java.util.Map<String, java.util.Map<String, Integer>> m = new Gson().fromJson(fr, t);
            if (m == null || m.isEmpty()) return;
            out.addAll(m.keySet());
            for (java.util.Map<String, Integer> ing : m.values()) { if (ing != null) out.addAll(ing.keySet()); }
        } catch (Exception ignored) {}
    }

    private static void migrateLegacyFilenames() {
        try {
            File legacyResources = new File(DATA_DIR, "resources.json");
            if (legacyResources.exists() && !file_resources.exists()) {
                safeMove(legacyResources, file_resources);
            }

            File legacyForging = new File(DATA_DIR, "forging.json");
            if (legacyForging.exists() && !FORGING_JSON.exists()) {
                safeMove(legacyForging, FORGING_JSON);
            }

            File legacyGem = new File(DATA_DIR, "gemstone_recipes.json");
            if (legacyGem.exists() && !GEMSTONE_RECIPES_JSON.exists()) {
                safeMove(legacyGem, GEMSTONE_RECIPES_JSON);
            }
        } catch (Exception e) {
            LOGGER.warn("Legacy filename migration failed", e);
        }
    }

    private static void safeMove(File src, File dst) {
        try {
            if (!dst.getParentFile().exists()) dst.getParentFile().mkdirs();
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveErr) {
            try {
                Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!src.delete()) {
                    LOGGER.debug("Failed to delete legacy file after copy: {}", src.getAbsolutePath());
                }
            } catch (IOException copyErr) {
                LOGGER.warn("Failed to move/copy file {} -> {}", src.getAbsolutePath(), dst.getAbsolutePath());
            }
        }
    }

    private static void cleanupOldVersioned(String baseName, String currentFileName) {
        File[] olds = DATA_DIR.listFiles((dir, name) -> {
            if (!name.startsWith(baseName)) return false;
            if (!name.endsWith(".json")) return false;
            if (name.equals(currentFileName)) return false;
            return name.matches(java.util.regex.Pattern.quote(baseName) + "\\.v.+\\.json");
        });
        if (olds != null) {
            for (File f : olds) {
                try { f.delete(); } catch (Exception ignored) {}
            }
        }
        File legacy = new File(DATA_DIR, baseName + ".json");
        if (legacy.exists() && !legacy.getName().equals(currentFileName)) {
            try { legacy.delete(); } catch (Exception ignored) {}
        }
    }

    private static void cleanupAllOldVersioned() {
        cleanupOldVersioned("resources", file_resources.getName());
        cleanupOldVersioned("forging", FORGING_JSON.getName());
        cleanupOldVersioned("gemstone_recipes", GEMSTONE_RECIPES_JSON.getName());
    }

    private static String getModVersionString() {
        try {
            return FabricLoader.getInstance()
                .getModContainer(InventoryReader.MOD_ID)
                .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
                .orElse("1");
        } catch (Throwable t) {
            return "1";
        }
    }

    private static void ensureCurrentFilesWithCarryOver() {
        try {
            if (!file_resources.exists()) {
                File prev = findLatestPriorVersionedFile("resources", MOD_VERSION);
                if (prev != null) {
                    safeCopy(prev, file_resources);
                    LOGGER.info("Carried resources data forward from {} -> {}", prev.getName(), file_resources.getName());
                } else {
                    initializeResourcesData(file_resources);
                }
            }

            if (!FORGING_JSON.exists() || !GEMSTONE_RECIPES_JSON.exists()) {
                RecipeFileGenerator.initializeRecipeFiles();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to ensure current versioned files with carry-over; falling back to defaults", e);
            if (!file_resources.exists()) initializeResourcesData(file_resources);
            if (!FORGING_JSON.exists() || !GEMSTONE_RECIPES_JSON.exists()) RecipeFileGenerator.initializeRecipeFiles();
        }
    }

    private static void safeCopy(File src, File dst) {
        try {
            if (!dst.getParentFile().exists()) dst.getParentFile().mkdirs();
            Path tmp = Paths.get(dst.getAbsolutePath() + ".tmp");
            Files.copy(src.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, dst.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(tmp, dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to copy file {} -> {}", src.getAbsolutePath(), dst.getAbsolutePath());
        }
    }

    private static File findLatestPriorVersionedFile(String baseName, String currentVersion) {
        File[] candidates = DATA_DIR.listFiles((dir, name) -> name.startsWith(baseName + ".v") && name.endsWith(".json") && !name.equals(baseName + ".v" + currentVersion + ".json"));
        if (candidates == null || candidates.length == 0) return null;
        Arrays.sort(candidates, (a, b) -> {
            String va = extractVersionFromFileName(baseName, a.getName());
            String vb = extractVersionFromFileName(baseName, b.getName());
            return compareVersions(va, vb);
        });
        // Pick the highest version less than or equal to currentVersion (allows 2.0.7 -> 2.0.7-dev)
        String cur = currentVersion;
        File selected = null;
        for (int i = candidates.length - 1; i >= 0; i--) {
            String v = extractVersionFromFileName(baseName, candidates[i].getName());
            if (compareVersions(v, cur) <= 0) { selected = candidates[i]; break; }
        }
        return selected;
    }

    private static String extractVersionFromFileName(String baseName, String fileName) {
        int start = (baseName + ".v").length();
        int idx = fileName.indexOf(".json");
        if (fileName.startsWith(baseName + ".v") && idx > start) {
            return fileName.substring(start, idx);
        }
        return "0";
    }

    private static int compareVersions(String a, String b) {
        if (a == null) a = "0"; if (b == null) b = "0";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int ai = i < pa.length ? safeParseInt(pa[i]) : 0;
            int bi = i < pb.length ? safeParseInt(pb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static int safeParseInt(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    private static void initializeResourcesData(File file) {
        Map<String, Integer> resources = new LinkedHashMap<>();
        
        String[] items = {
            "Rough Amber Gemstone",
            "Flawed Amber Gemstone",
            "Fine Amber Gemstone",
            "Flawless Amber Gemstone",
            "Perfect Amber Gemstone",
            "Rough Amethyst Gemstone",
            "Flawed Amethyst Gemstone",
            "Fine Amethyst Gemstone",
            "Flawless Amethyst Gemstone",
            "Perfect Amethyst Gemstone",
            "Rough Aquamarine Gemstone",
            "Flawed Aquamarine Gemstone",
            "Fine Aquamarine Gemstone",
            "Flawless Aquamarine Gemstone",
            "Perfect Aquamarine Gemstone",
            "Rough Citrine Gemstone",
            "Flawed Citrine Gemstone",
            "Fine Citrine Gemstone",
            "Flawless Citrine Gemstone",
            "Perfect Citrine Gemstone",
            "Rough Jade Gemstone",
            "Flawed Jade Gemstone",
            "Fine Jade Gemstone",
            "Flawless Jade Gemstone",
            "Perfect Jade Gemstone",
            "Rough Jasper Gemstone",
            "Flawed Jasper Gemstone",
            "Fine Jasper Gemstone",
            "Flawless Jasper Gemstone",
            "Perfect Jasper Gemstone",
            "Rough Onyx Gemstone",
            "Flawed Onyx Gemstone",
            "Fine Onyx Gemstone",
            "Flawless Onyx Gemstone",
            "Perfect Onyx Gemstone",
            "Rough Opal Gemstone",
            "Flawed Opal Gemstone",
            "Fine Opal Gemstone",
            "Flawless Opal Gemstone",
            "Perfect Opal Gemstone",
            "Rough Peridot Gemstone",
            "Flawed Peridot Gemstone",
            "Fine Peridot Gemstone",
            "Flawless Peridot Gemstone",
            "Perfect Peridot Gemstone",
            "Rough Ruby Gemstone",
            "Flawed Ruby Gemstone",
            "Fine Ruby Gemstone",
            "Flawless Ruby Gemstone",
            "Perfect Ruby Gemstone",
            "Rough Sapphire Gemstone",
            "Flawed Sapphire Gemstone",
            "Fine Sapphire Gemstone",
            "Flawless Sapphire Gemstone",
            "Perfect Sapphire Gemstone",
            "Rough Topaz Gemstone",
            "Flawed Topaz Gemstone",
            "Fine Topaz Gemstone",
            "Flawless Topaz Gemstone",
            "Perfect Topaz Gemstone",
            "Refined Diamond",
            "Enchanted Diamond Block",
            "Enchanted Diamond",
            "Diamond",
            "Refined Mithril",
            "Enchanted Mithril",
            "Mithril",
            "Refined Titanium",
            "Enchanted Titanium",
            "Titanium",
            "Refined Umber",
            "Enchanted Umber",
            "Umber",
            "Refined Tungsten",
            "Enchanted Tungsten",
            "Tungsten",
            "Glacite Jewel",
            "Bejeweled Handle",
            "Drill Motor",
            "Treasurite",
            "Enchanted Iron Block",
            "Enchanted Iron",
            "Iron Ingot",
            "Enchanted Redstone Block",
            "Enchanted Redstone",
            "Redstone",
            "Golden Plate",
            "Enchanted Gold Block",
            "Enchanted Gold",
            "Gold Ingot",
            "Fuel Canister",
            "Enchanted Coal Block",
            "Enchanted Coal",
            "Coal",
            "Gemstone Mixture",
            "Sludge Juice",
            "Glacite Amalgamation",
            "Enchanted Glacite",
            "Glacite",
            "Mithril Plate",
            "Tungsten Plate",
            "Umber Plate",
            "Perfect Plate",
            "Mithril Drill SX-R226",
            "Mithril Drill SX-R326",
            "Ruby Drill TX-15",
            "Gemstone Drill LT-522",
            "Topaz Drill KGR-12",
            "Magma Core",
            "Jasper Drill X",
            "Polished Topaz Rod",
            "Titanium Drill DR-X355",
            "Titanium Drill DR-X455",
            "Titanium Drill DR-X555",
            "Titanium Drill DR-X655",
            "Plasma",
            "Corleonite",
            "Chisel",
            "Reinforced Chisel",
            "Glacite-Plated Chisel",
            "Perfect Chisel",
            "Divan's Drill",
            "Divan's Alloy",
            "Mithril Necklace",
            "Mithril Cloak",
            "Mithril Belt",
            "Mithril Gauntlet",
            "Titanium Necklace",
            "Titanium Cloak",
            "Titanium Belt",
            "Titanium Gauntlet",
            "Refined Mineral",
            "Titanium Talisman",
            "Titanium Ring",
            "Titanium Artifact",
            "Titanium Relic",
            "Divan's Powder Coating",
            "Glossy Gemstone",
            "Divan Fragment",
            "Helmet Of Divan",
            "Chestplate Of Divan",
            "Leggings Of Divan",
            "Boots Of Divan",
            "Amber Necklace",
            "Sapphire Cloak",
            "Jade Belt",
            "Amethyst Gauntlet",
            "Gemstone Chamber",
            "Worm Membrane",
            "Dwarven Handwarmers",
            "Dwarven Metal Talisman",
            "Pendant Of Divan",
            "Shattered Locket",
            "Relic Of Power",
            "Artifact Of Power",
            "Ring Of Power",
            "Talisman Of Power",
            "Diamonite",
            "Pocket Iceberg",
            "Petrified Starfall",
            "Starfall",
            "Pure Mithril",
            "Dwarven Geode",
            "Enchanted Cobblestone",
            "Cobblestone",
            "Titanium Tesseract",
            "Enchanted Lapis Block",
            "Enchanted Lapis Lazuli",
            "Lapis Lazuli",
            "Gleaming Crystal",
            "Scorched Topaz",
            "Enchanted Hard Stone",
            "Hard Stone",
            "Amber Material",
            "Frigid Husk",
            "Starfall Seasoning",
            "Goblin Omelette",
            "Goblin Egg",
            "Spicy Goblin Omelette",
            "Red Goblin Egg",
            "Pesto Goblin Omelette",
            "Green Goblin Egg",
            "Sunny Side Goblin Omelette",
            "Yellow Goblin Egg",
            "Blue Cheese Goblin Omelette",
            "Blue Goblin Egg",
            "Tungsten Regulator",
            "Mithril-Plated Drill Engine",
            "Titanium-Plated Drill Engine",
            "Ruby-Polished Drill Engine",
            "Precursor Apparatus",
            "Control Switch",
            "Electron Transmitter",
            "FTX 3070",
            "Robotron Reflector",
            "Superlite Motor",
            "Synthetic Heart",
            "Sapphire-Polished Drill Engine",
            "Amber-Polished Drill Engine",
            "Mithril-Infused Fuel Tank",
            "Titanium-Infused Fuel Tank",
            "Gemstone Fuel Tank",
            "Perfectly-Cut Fuel Tank",
            "Bejeweled Collar",
            "Beacon I",
            "Beacon II",
            "Glass",
            "Beacon III",
            "Beacon IV",
            "Beacon V",
            "Travel Scroll To The Dwarven Forge",
            "Enchanted Ender Pearl",
            "Ender Pearl",
            "Travel Scroll To The Dwarven Base Camp",
            "Power Crystal",
            "Secret Railroad Pass",
            "Tungsten Key",
            "Umber Key",
            "Skeleton Key",
            "Portable Campfire",
            "Match-Sticks",
            "Sulphur",
            "Stick",
            "Gemstone Gauntlet"
        };

        for(String item:items){
            resources.put(item, 0);
        }

        try(FileWriter writer = new FileWriter(file)){
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(resources, writer);
            System.out.println("Resources intialized successfully in ");
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    private static void initializeWidgetConfigData(File file) {
        LOGGER.info("Initializing widget configuration with default values");
        
        try (FileWriter writer = new FileWriter(file)) {
            Map<String, Object> widgetConfig = new LinkedHashMap<>();
            widgetConfig.put("enabled", false);
            widgetConfig.put("selectedRecipe", null);
            widgetConfig.put("widgetX", 10);
            widgetConfig.put("widgetY", 40);
            widgetConfig.put("expandedNodes", new HashMap<String, Boolean>());
            widgetConfig.put("craftAmount", 1);
            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(widgetConfig, writer);
            
            LOGGER.info("Widget configuration initialized successfully");
        } catch (IOException e) {
            LOGGER.error("Failed to initialize widget configuration file", e);
        }
    }

    private static void initializeRemoteSourcesConfig(File file) {
        try (FileWriter writer = new FileWriter(file)) {

            Map<String, Object> cfg = new LinkedHashMap<>();
            List<Map<String, String>> sources = new java.util.ArrayList<>();
            Map<String, String> neu = new LinkedHashMap<>();
            neu.put("type", "neu-zip");
            neu.put("url", "https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/refs/heads/master");
            sources.add(neu);
            cfg.put("sources", sources);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(cfg, writer);
        } catch (IOException e) {
            
        }
    }

    public static void ensureResourceNames(Set<String> names) {
        if (names == null || names.isEmpty()) return;
        try {
            if (!file_resources.exists()) {
                initializeResourcesData(file_resources);
            }
            Map<String, Integer> resources = new LinkedHashMap<>();
            try (FileReader reader = new FileReader(file_resources)) {
                java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<Map<String, Integer>>(){}.getType();
                Map<String, Integer> loaded = new Gson().fromJson(reader, t);
                if (loaded != null) resources.putAll(loaded);
            } catch (Exception ignored) {}

            boolean changed = false;

            Iterator<Map.Entry<String,Integer>> it = resources.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String,Integer> e = it.next();
                String key = e.getKey();
                if (key == null) { it.remove(); changed = true; continue; }
                String trimmed = key.trim();
                if (trimmed.isEmpty() || trimmed.matches("\\d+")) { it.remove(); changed = true; }
            }

            for (String n : names) {
                if (n == null) continue;
                String name = n.trim();
                if (name.isEmpty()) continue;
                // Skip numeric-only names (e.g., "64" coming from malformed data)
                if (name.matches("\\d+")) continue;
                if (!resources.containsKey(name)) { resources.put(name, 0); changed = true; }
            }

            // Remove plain-name duplicates when a symbol-prefixed version exists.
            // e.g. if "☂ Fine Aquamarine Gemstone" is present, remove "Fine Aquamarine Gemstone".
            Set<String> symbolStripped = new java.util.HashSet<>();
            for (String key : resources.keySet()) {
                if (key.length() > 2 && key.codePointAt(0) > 127 && key.charAt(1) == ' ') {
                    symbolStripped.add(key.substring(2));
                }
            }
            if (!symbolStripped.isEmpty()) {
                Iterator<Map.Entry<String,Integer>> sit = resources.entrySet().iterator();
                while (sit.hasNext()) {
                    String key = sit.next().getKey();
                    if (symbolStripped.contains(key)) { sit.remove(); changed = true; }
                }
            }

            if (changed) {
                try (FileWriter writer = new FileWriter(file_resources)) {
                    new GsonBuilder().setPrettyPrinting().create().toJson(resources, writer);
                }
            }
        } catch (Exception ignored) {}
    }
}