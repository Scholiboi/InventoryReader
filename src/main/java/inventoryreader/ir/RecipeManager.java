package inventoryreader.ir;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;

public class RecipeManager {
    private static final RecipeManager INSTANCE = new RecipeManager();
    private volatile Map<String, Map<String, Integer>> recipes = Collections.emptyMap();
    private volatile List<String> recipeNames = Collections.emptyList();

    private RecipeManager() {
        loadRecipes();
    }

    public static RecipeManager getInstance() {
        return INSTANCE;
    }

    private void loadRecipes() {
        Gson gson = new Gson();
        try {
            Map<String, Map<String, Integer>> working = new LinkedHashMap<>();

            Map<String, Map<String, Integer>> forging = readRecipeMap(gson, FilePathManager.FORGING_JSON);
            Map<String, Map<String, Integer>> remote   = readRecipeMap(gson, FilePathManager.REMOTE_RECIPES_JSON);
            Map<String, Map<String, Integer>> remoteForge = readRecipeMap(gson, FilePathManager.REMOTE_FORGE_JSON);

            // Only use the hardcoded gemstone fallback when we have no remote data yet;
            // once the NEU fetch has produced remote recipes, those contain the gemstone
            // recipes with correct display names — loading both causes symbol-prefix
            // mismatches that create duplicate entries in the recipe list.
            boolean hasRemote = remote != null && !remote.isEmpty();
            Map<String, Map<String, Integer>> gemstone = hasRemote
                    ? null
                    : readRecipeMap(gson, FilePathManager.GEMSTONE_RECIPES_JSON);

            if (forging != null)     working.putAll(forging);
            if (gemstone != null)    working.putAll(gemstone);
            if (remote != null)      working.putAll(remote);
            if (remoteForge != null) working.putAll(remoteForge);

            Map<String, Map<String, Integer>> sanitized = sanitizeRecipes(working);

            List<String> newNames = new ArrayList<>(sanitized.keySet());

            Set<String> allNames = new LinkedHashSet<>(sanitized.keySet());
            for (Map<String, Integer> m : sanitized.values()) allNames.addAll(m.keySet());
            FilePathManager.ensureResourceNames(allNames);

            recipes = Collections.unmodifiableMap(sanitized);
            recipeNames = Collections.unmodifiableList(newNames);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Re-read all recipe files. Called by RemoteRecipeFetcher after a successful fetch. */
    public synchronized void reload() {
        loadRecipes();
    }

    private Map<String, Map<String, Integer>> readRecipeMap(Gson gson, File file) throws IOException {
        if (file == null || !file.exists() || file.length() == 0) return null;
        try (FileReader fr = new FileReader(file)) {
            JsonElement parsed = JsonParser.parseReader(fr);
            if (parsed == null || parsed.isJsonNull()) return null;
            JsonObject root = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            JsonObject recipesNode;
            if (root != null && root.has("recipes") && root.get("recipes").isJsonObject()) {
                recipesNode = root.getAsJsonObject("recipes");
            } else if (parsed.isJsonObject()) {
                recipesNode = parsed.getAsJsonObject();
            } else {
                return null;
            }

            java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<Map<String, Map<String, Integer>>>(){}.getType();
            return new Gson().fromJson(recipesNode, t);
        }
    }

    public List<String> getRecipeNames() {
        List<String> list = new ArrayList<>(recipeNames);
        list.sort(String::compareToIgnoreCase);
        return list;
    }

    public Map<String, Integer> getSimpleRecipe(String name, int amt) {
        Map<String, Integer> base = recipes.getOrDefault(name, Collections.emptyMap());
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : base.entrySet()) {
            result.put(entry.getKey(), entry.getValue() * amt);
        }
        return result;
    }

    public RecipeNode expandRecipe(String currentName, int multiplier) {
        if (!recipes.containsKey(currentName)) {
            return new RecipeNode(currentName, multiplier, Collections.emptyList());
        }
        List<RecipeNode> ingredients = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : recipes.get(currentName).entrySet()) {
            String item = entry.getKey();
            int qty = entry.getValue();
            RecipeNode expanded = expandRecipe(item, qty * multiplier);
            ingredients.add(expanded);
        }
        return new RecipeNode(currentName, multiplier, ingredients);
    }

    public Map<String, Map<String, Integer>> getAllRecipes() {
        return new LinkedHashMap<>(recipes);
    }

    public RecipeResponse getRecipe(String name, int amt) {
        if (!recipes.containsKey(name)) {
            return null; 
        }
        
        Map<String, Integer> simpleRecipe = getSimpleRecipe(name, amt);
        RecipeNode fullRecipe = expandRecipe(name, amt);
        
        return new RecipeResponse(name, simpleRecipe, fullRecipe);
    }

    private Map<String, Map<String, Integer>> sanitizeRecipes(Map<String, Map<String, Integer>> input) {
        if (input == null || input.isEmpty()) return Collections.emptyMap();

        Set<String> baseMaterials = new HashSet<>(Arrays.asList(
            "Diamond", "Iron Ingot", "Coal", "Gold Ingot", "Lapis Lazuli", "Emerald", "Redstone", "Quartz", "White Wool", "Hay Bale", "Melon"
        ));

        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : input.entrySet()) {
            String output = entry.getKey();
            Map<String, Integer> ing = entry.getValue();
            if (ing == null || ing.isEmpty()) { out.put(output, ing); continue; }

            Map<String, Integer> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> ie : ing.entrySet()) {
                String name = ie.getKey();
                if (name == null || name.isEmpty()) continue;
                if (name.matches("\\d+")) continue;
                cleaned.put(name, ie.getValue());
            }

            if (baseMaterials.contains(output)) {
                if (containsBlockIngredientForBase(output, cleaned.keySet())) {
                    continue;
                }
            }

            if (cleaned.isEmpty()) continue;

            out.put(output, cleaned);
        }
        return out;
    }

    private boolean containsBlockIngredientForBase(String base, Collection<String> ingredientNames) {
        String core = base;
        if (base.endsWith(" Ingot")) {
            core = base.substring(0, base.length() - " Ingot".length());
        }
        String a = "Block of " + core;
        String b = core + " Block";
        List<String> variants = new ArrayList<>();
        variants.add(a);
        variants.add(b);
        if ("Redstone".equals(core)) variants.add("Redstone Block");
        if ("Emerald".equals(core)) variants.add("Emerald Block");
        if ("Coal".equals(core)) variants.add("Block of Coal");
        if ("Diamond".equals(core)) variants.add("Block of Diamond");
        if ("Gold".equals(core)) variants.add("Block of Gold");
        if ("Iron".equals(core)) variants.add("Block of Iron");
        if ("Quartz".equals(core)) variants.add("Block of Quartz");
        if ("Lapis Lazuli".equals(core)) variants.add("Lapis Lazuli Block");

        for (String n : ingredientNames) {
            for (String v : variants) {
                if (v.equalsIgnoreCase(n)) return true;
            }
        }
        return false;
    }

    public static class RecipeResponse {
        public String name;
        public Map<String, Integer> simple_recipe;
        public RecipeNode full_recipe;
        public Map<String, Integer> messages;
        
        public RecipeResponse(String name, Map<String, Integer> simpleRecipe, RecipeNode fullRecipe) {
            this.name = name;
            this.simple_recipe = simpleRecipe;
            this.full_recipe = fullRecipe;
            this.messages = new LinkedHashMap<>();
        }
        
        public RecipeResponse(String name, Map<String, Integer> simpleRecipe, RecipeNode fullRecipe, Map<String, Integer> messages) {
            this.name = name;
            this.simple_recipe = simpleRecipe;
            this.full_recipe = fullRecipe;
            this.messages = messages;
        }
    }

    public static class RecipeNode {
        public String name;
        public int amount;
        public List<RecipeNode> ingredients;
        
        public RecipeNode(String name, int amount, List<RecipeNode> ingredients) {
            this.name = name;
            this.amount = amount;
            this.ingredients = ingredients;
        }
    }
}
