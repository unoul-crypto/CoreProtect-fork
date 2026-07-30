package net.coreprotect.utility;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.entity.Painting;

import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Queue;

public class MaterialUtils extends Queue {

    private static final String NAMESPACE = "minecraft:";

    private MaterialUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static int getBlockId(Material material) {
        return getBlockId(material, true);
    }

    public static int getBlockId(Material material, boolean internal) {
        if (material == null) {
            material = Material.AIR;
        }

        String registryKey = getMaterialKey(material);
        int id = getBlockId(registryKey, false);
        if (id != -1) {
            return id;
        }

        // Compatibility with records written by older fork builds, which used
        // the enum name and therefore assigned a minecraft: key to modded types.
        String enumKey = BlockTypeUtils.normalizeKey(material.name());
        if (!enumKey.equals(registryKey)) {
            id = getBlockId(enumKey, false);
            if (id != -1) {
                return id;
            }
        }

        return internal ? getBlockId(registryKey, true) : -1;
    }

    /**
     * Returns the registry key used by Bukkit for a material.
     *
     * Material#name() is only the enum name. Hybrid servers such as Mohist can
     * expose modded materials whose registry identity is namespaced, so using
     * the enum name silently turns "modid:item" into "minecraft:item".
     */
    public static String getMaterialKey(Material material) {
        if (material == null) {
            return NAMESPACE + "air";
        }

        try {
            return BlockTypeUtils.normalizeKey(material.getKey().toString());
        }
        catch (Exception | LinkageError e) {
            return BlockTypeUtils.normalizeKey(material.name());
        }
    }

    public static int getBlockId(String blockData, Material fallback, boolean internal) {
        String name = BlockTypeUtils.getBlockDataKey(blockData);
        if (name.length() == 0 && fallback != null) {
            name = fallback.getKey().toString();
        }

        return name.length() == 0 ? -1 : getBlockId(name, internal);
    }

    public static int getBlockId(String name, boolean internal) {
        int id = -1;

        name = name.toLowerCase(Locale.ROOT).trim();
        if (!name.contains(":")) {
            name = NAMESPACE + name;
        }

        if (ConfigHandler.materials.get(name) != null) {
            id = ConfigHandler.materials.get(name);
        }
        else if (internal) {
            // Check if another server has already added this material (multi-server setup)
            id = ConfigHandler.reloadAndGetId(ConfigHandler.CacheType.MATERIALS, name);
            if (id != -1) {
                return id;
            }

            int mid = ConfigHandler.materialId + 1;
            ConfigHandler.materials.put(name, mid);
            ConfigHandler.materialsReversed.put(mid, name);
            ConfigHandler.materialId = mid;
            Queue.queueMaterialInsert(mid, name);
            id = ConfigHandler.materials.get(name);
        }

        return id;
    }

    /**
     * Resolves every database ID that represents a material key.
     *
     * Older Mohist-compatible builds could store a modded Material under an
     * enum-derived minecraft: key, while newer builds store its registry key.
     * Both IDs can therefore exist in the same database and must be included in
     * lookup predicates.
     */
    public static Set<Integer> getBlockIds(String name) {
        return getBlockIds(name, getType(name));
    }

    static Set<Integer> getBlockIds(String name, Material material) {
        Set<Integer> ids = new LinkedHashSet<>();
        String normalizedName = BlockTypeUtils.normalizeKey(name);
        addKnownId(ids, normalizedName);

        if (material != null) {
            addKnownId(ids, getMaterialKey(material));
            addKnownId(ids, BlockTypeUtils.normalizeKey(material.name()));
        }
        else if (hasCustomNamespace(normalizedName)) {
            // Some Mohist builds do not resolve a modded key through
            // Material.matchMaterial(), but can resolve the enum-derived key
            // that was persisted by an older CoreProtect build.
            for (Integer id : ConfigHandler.materialsReversed.keySet()) {
                String storedName = ConfigHandler.materialsReversed.get(id);
                if (storedName == null || !storedName.startsWith(NAMESPACE)) {
                    continue;
                }
                Material storedMaterial = getType(id);
                if (storedMaterial != null && normalizedName.equals(getMaterialKey(storedMaterial))) {
                    ids.add(id);
                }
            }
        }

        return ids;
    }

    public static int getBlockdataId(String data, boolean internal) {
        int id = -1;
        data = data.toLowerCase(Locale.ROOT).trim();

        if (ConfigHandler.blockdata.get(data) != null) {
            id = ConfigHandler.blockdata.get(data);
        }
        else if (internal) {
            // Check if another server has already added this blockdata (multi-server setup)
            id = ConfigHandler.reloadAndGetId(ConfigHandler.CacheType.BLOCKDATA, data);
            if (id != -1) {
                return id;
            }

            int bid = ConfigHandler.blockdataId + 1;
            ConfigHandler.blockdata.put(data, bid);
            ConfigHandler.blockdataReversed.put(bid, data);
            ConfigHandler.blockdataId = bid;
            Queue.queueBlockDataInsert(bid, data);
            id = ConfigHandler.blockdata.get(data);
        }

        return id;
    }

    public static String getBlockDataString(int id) {
        // Internal ID pulled from DB
        String blockdata = "";
        String cachedBlockdata = ConfigHandler.blockdataReversed.get(id);
        if (cachedBlockdata != null) {
            blockdata = cachedBlockdata;
        }
        return blockdata;
    }

    public static String getBlockName(int id) {
        String name = "";
        String cachedName = ConfigHandler.materialsReversed.get(id);
        if (cachedName != null) {
            name = cachedName;
        }
        return name;
    }

    public static String getBlockDisplayName(int id, int data) {
        String storedName = getBlockName(id);
        if (hasCustomNamespace(storedName)) {
            return storedName;
        }

        Material material = getType(id);
        if (material != null) {
            String registryKey = getMaterialKey(material);
            if (hasCustomNamespace(registryKey)) {
                return registryKey;
            }
            return StringUtils.nameFilter(material.name().toLowerCase(Locale.ROOT), data);
        }

        return storedName;
    }

    public static String getBlockNameShort(int id) {
        String name = getBlockName(id);
        if (name.startsWith(NAMESPACE)) {
            name = name.substring(NAMESPACE.length());
        }

        return name;
    }

    public static Material getType(int id) {
        // Internal ID pulled from DB
        Material material = null;
        String blockName = getBlockName(id);
        if (!blockName.isEmpty() && id > 0) {
            material = Material.matchMaterial(blockName);
            if (material != null) {
                return material;
            }

            String name = blockName.toUpperCase(Locale.ROOT);
            if (name.startsWith(NAMESPACE.toUpperCase(Locale.ROOT))) {
                name = name.substring(NAMESPACE.length());
            }
            name = net.coreprotect.bukkit.BukkitAdapter.ADAPTER.parseLegacyName(name);
            material = Material.getMaterial(name);

            if (material == null) {
                material = Material.getMaterial(name, true);
            }
        }

        return material;
    }

    public static Material getType(String name) {
        // Name entered by user
        Material material = null;
        name = name.trim();
        if (!name.startsWith("#")) {
            material = Material.matchMaterial(name);
            if (material != null) {
                return material;
            }

            name = name.toUpperCase(Locale.ROOT);
            if (name.startsWith(NAMESPACE.toUpperCase(Locale.ROOT))) {
                name = name.substring(NAMESPACE.length());
            }

            name = net.coreprotect.bukkit.BukkitAdapter.ADAPTER.parseLegacyName(name);
            material = Material.matchMaterial(name);
        }

        return material;
    }

    public static int getArtId(String name, boolean internal) {
        int id = -1;
        name = name.toLowerCase(Locale.ROOT).trim();

        if (ConfigHandler.art.get(name) != null) {
            id = ConfigHandler.art.get(name);
        }
        else if (internal) {
            // Check if another server has already added this art (multi-server setup)
            id = ConfigHandler.reloadAndGetId(ConfigHandler.CacheType.ART, name);
            if (id != -1) {
                return id;
            }

            int artID = ConfigHandler.artId + 1;
            ConfigHandler.art.put(name, artID);
            ConfigHandler.artReversed.put(artID, name);
            ConfigHandler.artId = artID;
            Queue.queueArtInsert(artID, name);
            id = ConfigHandler.art.get(name);
        }

        return id;
    }

    public static String getPaintingArtName(Painting painting) {
        return net.coreprotect.bukkit.BukkitAdapter.ADAPTER.getPaintingArtKey(painting);
    }

    public static String getArtName(int id) {
        // Internal ID pulled from DB
        String artname = "";
        String cachedName = ConfigHandler.artReversed.get(id);
        if (cachedName != null) {
            artname = cachedName;
        }
        return artname;
    }

    public static int getMaterialId(Material material) {
        return getBlockId(material, true);
    }

    private static boolean hasCustomNamespace(String name) {
        int separator = name == null ? -1 : name.indexOf(':');
        return separator > 0 && !name.startsWith(NAMESPACE);
    }

    private static void addKnownId(Set<Integer> ids, String name) {
        Integer id = ConfigHandler.materials.get(name);
        if (id != null && id >= 0) {
            ids.add(id);
        }
    }

    public static boolean listContains(Set<Material> list, Material value) {
        boolean result = false;
        for (Material list_value : list) {
            if (list_value.equals(value)) {
                result = true;
                break;
            }
        }
        return result;
    }

    public static int rolledBack(int rolledBack, boolean isInventory) {
        switch (rolledBack) {
            case 1: // just block rolled back
                return isInventory ? 0 : 1;
            case 2: // just inventory rolled back
                return isInventory ? 1 : 0;
            case 3: // block and inventory rolled back
                return 1;
            default: // no rollbacks
                return 0;
        }
    }

    public static int toggleRolledBack(int rolledBack, boolean isInventory) {
        switch (rolledBack) {
            case 1: // just block rolled back
                return isInventory ? 3 : 0;
            case 2: // just inventory rolled back
                return isInventory ? 0 : 3;
            case 3: // block and inventory rolled back
                return isInventory ? 1 : 2;
            default: // no rollbacks
                return isInventory ? 2 : 1;
        }
    }
}
