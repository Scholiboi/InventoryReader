# Inventory Reader (Skyblock Resource Calculator)

Inventory Reader is a Fabric client mod for Hypixel Skyblock that tracks player inventory, containers, and sack data and provides an interactive HUD and recipe/crafting planner.

Key capabilities
- Real-time inventory and container tracking
- Recipe parsing and ingredient aggregation
- Crafting planner that shows missing ingredients and craftable items
- Configurable in-game HUD widget with live preview and positioning
- Local persistence of all data (no servers)

Quick links
- Releases: https://github.com/Scholiboi/InventoryReader-HypixelMining/releases
- Issues: https://github.com/Scholiboi/InventoryReader-HypixelMining/issues

Requirements
- Minecraft 1.21.5+
- Fabric Loader (compatible with the supported Minecraft version)
- Java 21 runtime

Installation
1. Install Fabric Loader and run Minecraft once on the target profile.
2. Place the mod jar into your `mods/` folder.
3. Launch Minecraft with the Fabric profile.

Running from source (developer)
1. Clone the repository.
2. Install Java 21 and ensure Gradle/Gradlew can run on your system.
3. From the project root run:

```bash
./gradlew build
```

4. The built mod jar is in `build/libs/`.

Where data is stored
All runtime data is stored under a hidden directory in your Minecraft folder: `.ir-data/data/`.
Important files and purpose:
- `allcontainerData.json` — persisted container snapshots
- `inventorydata.json` — player inventory snapshots
- `resources.v<version>.json` — canonical list of tracked resources (versioned)
- `widget_config.json` — HUD widget position/size/expansion and craft amount
- `forging.v<version>.json`, `gemstone_recipes.v<version>.json` — local recipe files
- `recipes_remote.json` — processed snapshot extracted from remote recipe sources (if configured)
- `remote_sources_meta.json` — ETag/mtime metadata for remote fetch caching

Remote recipes: what happens and security
- The mod can fetch remote recipe sources defined in `remote_sources.json`.
- NEU-style ZIPs (NotEnoughUpdates archives) are streamed and parsed in-memory. The ZIP file itself is not saved to disk. Only the processed recipe snapshot (JSON) is persisted to `recipes_remote.json`.
- Metadata such as ETag or mtime is stored in `remote_sources_meta.json` to avoid re-downloading unchanged sources.

Security notes (brief)
- No code from remote sources is executed. The fetcher parses JSON entries only and converts them into internal recipe mappings.
- ZIP entries are not extracted to disk (ZipInputStream is processed in-memory), minimizing filesystem attack surface and avoiding ZIP-slip risks.
- To harden further you can configure only HTTPS sources and/or local file paths in `remote_sources.json`.

Commands & UI
- `/ir menu` — Open the main resource/recipe GUI
- `/ir widget` — Open widget customization and positioning
- `/ir reset` — Reset local mod data (clears local snapshots)

HUD behavior
- The HUD shows the selected recipe tree and a craftable panel. The tree and craftable area auto-scale to fit the widget size. Expansion state is preserved across sessions.

Development notes
- Remote fetching is implemented in `src/main/java/inventoryreader/ir/recipes/RemoteRecipeFetcher.java`.
- The app writes snapshots using `FilePathManager` into `.ir-data/data/`.

Troubleshooting
- If remote fetching fails, check `remote_sources.json` and `remote_sources_meta.json` for ETag/mtime state. Logs will include fetch errors.
- If the HUD behaves oddly, try deleting `widget_config.json` (it will be recreated) and re-opening the widget customization screen.

License & attribution
- Core project: CC-BY-SA-4.0 (see `LICENSE`)
- Remote recipe data and item maps may be sourced from NotEnoughUpdates-REPO; follow their attribution and license requirements when reusing their data.

Contact
- Report issues or feature requests on GitHub: https://github.com/Scholiboi/InventoryReader-HypixelMining/issues
