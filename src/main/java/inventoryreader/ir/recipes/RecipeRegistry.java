package inventoryreader.ir.recipes;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import inventoryreader.ir.FilePathManager;
import inventoryreader.ir.RecipeFileGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RecipeRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("IR-RecipeRegistry");
    private static final Gson GSON = new Gson();

    private static final List<RecipeProvider> providers = new ArrayList<>();
    private static volatile Map<String, Recipe> byOutput = Collections.emptyMap();

    private RecipeRegistry() {}

    public static void bootstrap() {
        if (!FilePathManager.FORGING_JSON.exists() || !FilePathManager.GEMSTONE_RECIPES_JSON.exists()) {
            RecipeFileGenerator.initializeRecipeFiles();
        }
        if (providers.isEmpty()) {
            providers.add(new StaticJsonProvider("static:forging", FilePathManager.FORGING_JSON, (byte)0, 10));
            providers.add(new StaticJsonProvider("static:gemstone", FilePathManager.GEMSTONE_RECIPES_JSON, (byte)1, 10));
            providers.add(new StaticJsonProvider("remote:cached", FilePathManager.REMOTE_RECIPES_JSON, (byte)2, 20));
            providers.add(new StaticJsonProvider("remote:forge", FilePathManager.REMOTE_FORGE_JSON, (byte)0, 25));
            File user = new File(FilePathManager.DATA_DIR, "user_recipes.json");
            providers.add(new StaticJsonProvider("user:overrides", user, (byte)3, 30));
        }
        Map<String, Recipe> merged = mergeAll();
        if (!merged.isEmpty()) {
            publish(merged);
            writeMergedToDisk(merged);
        }
        RemoteRecipeFetcher.fetchAsync();
    }

    public static Recipe get(String output) {
        return byOutput.get(output);
    }

    public static Map<String, Recipe> snapshot() {
        return byOutput;
    }

    private static void publish(Map<String, Recipe> merged) {
        byOutput = Collections.unmodifiableMap(merged);
    }

    private static Map<String, Recipe> mergeAll() {
        List<RecipeProvider> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparingInt(RecipeProvider::priority).reversed());
        Map<String, Recipe> merged = new LinkedHashMap<>();
        Map<String, Integer> srcPrio = new HashMap<>();
        for (RecipeProvider p : sorted) {
            try {
                Map<String, Recipe> m = p.load();
                if (m == null || m.isEmpty()) continue;
                for (Map.Entry<String, Recipe> e : m.entrySet()) {
                    String key = e.getKey();
                    Recipe rec = e.getValue();
                    Integer cur = srcPrio.get(key);
                    if (cur == null || p.priority() > cur) {
                        merged.put(key, rec);
                        srcPrio.put(key, p.priority());
                    }
                }
            } catch (Exception ex) {
                LOGGER.warn("Provider {} failed: {}", p.id(), ex.toString());
            }
        }
        return merged;
    }

    private static void writeMergedToDisk(Map<String, Recipe> merged) {
        Map<String, Object> wire = new LinkedHashMap<>();
        for (Map.Entry<String, Recipe> e : merged.entrySet()) {
            Recipe r = e.getValue();
            Map<String, Object> v = new LinkedHashMap<>(4);
            v.put("ing", r.ing);
            v.put("cnt", r.cnt);
            v.put("c", r.category);
            v.put("s", r.sourcePriority);
            wire.put(e.getKey(), v);
        }
        File tmp = new File(FilePathManager.DATA_DIR, "recipes_all.json.tmp");
        File out = FilePathManager.MERGED_RECIPES_JSON;
        try (FileWriter fw = new FileWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(wire, fw);
        } catch (Exception e) {
            LOGGER.warn("Failed writing merged recipes: {}", e.toString());
            return;
        }
        try {
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                LOGGER.warn("Failed to move merged recipes file: {}", ex.toString());
            }
        }
    }

    static final class StaticJsonProvider implements RecipeProvider {
        private final String id;
        private final File file;
        private final byte category;
        private final int priority;
        StaticJsonProvider(String id, File file, byte category, int priority) {
            this.id = id; this.file = file; this.category = category; this.priority = priority;
        }
        public String id() { return id; }
        public int priority() { return priority; }
        public Map<String, Recipe> load() throws Exception {
            if (!file.exists() || file.length() == 0) return Collections.emptyMap();
            try (FileReader fr = new FileReader(file, StandardCharsets.UTF_8)) {
                com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseReader(fr);
                if (parsed == null || !parsed.isJsonObject()) return Collections.emptyMap();
                com.google.gson.JsonObject root = parsed.getAsJsonObject();
                com.google.gson.JsonObject recipesNode = (root.has("recipes") && root.get("recipes").isJsonObject())
                        ? root.getAsJsonObject("recipes") : root;
                java.lang.reflect.Type t = new TypeToken<Map<String, Map<String, Number>>>(){}.getType();
                Map<String, Map<String, Number>> raw = GSON.fromJson(recipesNode, t);
                if (raw == null || raw.isEmpty()) return Collections.emptyMap();
                Map<String, Recipe> out = new LinkedHashMap<>(raw.size());
                for (Map.Entry<String, Map<String, Number>> e : raw.entrySet()) {
                    String output = e.getKey();
                    Map<String, Number> ingMap = e.getValue();
                    if (ingMap == null || ingMap.isEmpty()) continue;
                    String[] ing = new String[ingMap.size()];
                    short[] cnt = new short[ingMap.size()];
                    int i = 0;
                    for (Map.Entry<String, Number> in : ingMap.entrySet()) {
                        ing[i] = in.getKey();
                        cnt[i] = in.getValue() == null ? 0 : (short)Math.min(Short.MAX_VALUE, in.getValue().intValue());
                        i++;
                    }
                    out.put(output, new Recipe(output, ing, cnt, category, (byte)priority));
                }
                return out;
            }
        }
    }
}
