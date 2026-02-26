package inventoryreader.ir;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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

        // Pass 1: drop entire entries that are known decompression recipes ---
        // These items had recipes of the form "X: {Block of X: 1}" which created
        // bidirectional A↔B cycles with the compression recipe "Block of X: {X: 9}".
        // Dropping these entries removes the cycle while preserving the useful direction.
        Set<String> DECOMPRESSION_SKIP = new HashSet<>(Arrays.asList(
            "Iron Ingot",    // Iron Ingot -> Block of Iron  (cycle with Block of Iron -> Iron Ingot x9)
            "Emerald",       // Emerald -> Block of Emerald  (cycle with Block of Emerald -> Emerald x9)
            "Slimeball",     // Slimeball -> Slime Block     (cycle with Slime Block -> Slimeball x9)
            "Coal",          // Coal -> Block of Coal        (cycle with Block of Coal -> Coal x9)
            "Diamond",       // Diamond -> Block of Diamond  (cycle with Block of Diamond -> Diamond x9)
            "Lapis Lazuli",  // Lapis Lazuli -> Lapis Lazuli Block (cycle with reverse)
            "Wheat",         // Wheat -> Hay Bale            (cycle with Hay Bale -> Wheat x9)
            "Redstone Dust", // Redstone Dust -> Block of Redstone (cycle with reverse)
            "Gold Ingot"     // Gold Ingot -> Block of Gold  (cycle with Block of Gold -> Gold Ingot x9)
        ));

        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : input.entrySet()) {
            String output = entry.getKey();
            Map<String, Integer> ing = entry.getValue();
            if (ing == null || ing.isEmpty()) { out.put(output, ing); continue; }

            // Drop known decompression entries entirely
            if (DECOMPRESSION_SKIP.contains(output)) continue;

            Map<String, Integer> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> ie : ing.entrySet()) {
                String name = ie.getKey();
                if (name == null || name.isEmpty()) continue;
                if (name.matches("\\d+")) continue;
                // --- Pass 2: remove self-references ---
                // Items that list themselves as their own ingredient cause immediate
                // infinite recursion. Explicitly strip them out.
                // Known offenders: "White Wool", "Beastmaster Crest", "Aspect of the Leech"
                if (name.equals(output)) continue;
                cleaned.put(name, ie.getValue());
            }

            if (cleaned.isEmpty()) continue;

            out.put(output, cleaned);
        }

        // --- Pass 3: remove redundant co-ingredients ---
        // If a recipe lists both ingredient X and ingredient Y, and Y's own recipe
        // is made purely from X (e.g., Enchanted Redstone Dust requires both
        // "Redstone Dust: 160" AND "Block of Redstone: 160", while Block of Redstone
        // is itself crafted from Redstone Dust), then Y is redundant and causes the
        // recipe tree to bloat with a duplicate, deeper Redstone Dust subtree.
        // Strip the derived ingredient (Y) and keep only the base (X).
        for (Map.Entry<String, Map<String, Integer>> entry : out.entrySet()) {
            Map<String, Integer> ingredients = entry.getValue();
            if (ingredients == null || ingredients.size() < 2) continue;
            Set<String> ingredientKeys = new HashSet<>(ingredients.keySet());
            for (String candidate : ingredientKeys) {
                if (!out.containsKey(candidate)) continue; // candidate is a leaf, skip
                Map<String, Integer> candidateRecipe = out.get(candidate);
                if (candidateRecipe == null || candidateRecipe.isEmpty()) continue;
                // If every ingredient of 'candidate' is already present in this recipe,
                // then 'candidate' is derivable on-the-fly from existing ingredients —
                // listing it separately is redundant and will cause duplicate expansion.
                if (ingredientKeys.containsAll(candidateRecipe.keySet())) {
                    ingredients.remove(candidate);
                }
            }
        }

        // --- Pass 4: DFS cycle-breaker safety net ---
        // Catches any remaining cycles not covered by the explicit rules above
        // (e.g., newly added remote recipes that introduce new circular paths).
        // When a back-edge is found, the ingredient edge creating the cycle is removed.
        breakRemainingCycles(out);

        return out;
    }

    /**
     * Performs a DFS over the recipe graph and removes the specific ingredient edge
     * that creates each detected cycle, leaving the rest of the recipe intact.
     */
    private void breakRemainingCycles(Map<String, Map<String, Integer>> recipes) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new LinkedHashSet<>();
        for (String start : new ArrayList<>(recipes.keySet())) {
            if (!visited.contains(start)) {
                dfsCycleBreak(start, recipes, visited, inStack);
            }
        }
    }

    private void dfsCycleBreak(String node, Map<String, Map<String, Integer>> recipes,
                                Set<String> visited, Set<String> inStack) {
        visited.add(node);
        inStack.add(node);
        Map<String, Integer> ingredients = recipes.get(node);
        if (ingredients != null) {
            for (String ingredient : new ArrayList<>(ingredients.keySet())) {
                if (!recipes.containsKey(ingredient)) continue; // leaf — no onward cycle possible
                if (inStack.contains(ingredient)) {
                    // Back-edge detected: node -> ingredient where ingredient is an ancestor.
                    // Remove this single edge to break the cycle without discarding the whole recipe.
                    ingredients.remove(ingredient);
                } else if (!visited.contains(ingredient)) {
                    dfsCycleBreak(ingredient, recipes, visited, inStack);
                }
            }
        }
        inStack.remove(node);
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