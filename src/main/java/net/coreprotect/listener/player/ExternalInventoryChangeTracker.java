package net.coreprotect.listener.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Queue;
import net.coreprotect.thread.Scheduler;
import net.coreprotect.utility.BlockTypeUtils;
import net.coreprotect.utility.BlockUtils;
import net.coreprotect.utility.ItemUtils;
import net.coreprotect.utility.MaterialUtils;

public final class ExternalInventoryChangeTracker extends Queue implements Listener {

    private static final String GRAVESTONE_BLOCK_KEY = "gravestone:gravestone";
    private static final int INTERACTION_SUPPRESSION_TICKS = 1;
    private static final int LOGIN_BASELINE_TICKS = 20;
    // Paper can report the final slot state one or two ticks after the Bukkit
    // event that already logged the same transaction (notably item drops).
    private static final int KNOWN_ACTION_SUPPRESSION_TICKS = 3;
    private static final Map<UUID, PendingSlotChanges> PENDING_SLOT_CHANGES = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingSnapshot> PENDING_SNAPSHOTS = new ConcurrentHashMap<>();
    private static final Map<UUID, LoginBaseline> LOGIN_BASELINES = new ConcurrentHashMap<>();
    private static final Map<UUID, SuppressionToken> SUPPRESSION_TOKENS = new ConcurrentHashMap<>();
    private static final Set<UUID> JOINED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean paperSlotEventsAvailable;

    public static void setPaperSlotEventsAvailable() {
        paperSlotEventsAvailable = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            JOINED_PLAYERS.add(player.getUniqueId());
        }
    }

    public static void recordSlotChange(Player player, int slot, ItemStack oldItem, ItemStack newItem) {
        UUID uuid = player.getUniqueId();
        if (!JOINED_PLAYERS.contains(uuid) || !isEnabled(player) || SUPPRESSION_TOKENS.containsKey(uuid) || PENDING_SNAPSHOTS.containsKey(uuid)) {
            return;
        }

        LoginBaseline loginBaseline = LOGIN_BASELINES.get(uuid);
        if (loginBaseline != null && loginBaseline.consumeIfLoaded(slot, newItem)) {
            if (loginBaseline.isConsumed()) {
                LOGIN_BASELINES.remove(uuid, loginBaseline);
            }
            return;
        }

        PendingSlotChanges pending = PENDING_SLOT_CHANGES.compute(uuid, (key, existing) -> {
            PendingSlotChanges result = existing == null ? new PendingSlotChanges(player) : existing;
            result.record(slot, oldItem, newItem);
            return result;
        });
        if (pending.markScheduled()) {
            Object scheduleToken = pending.scheduleToken;
            Scheduler.scheduleSyncDelayedTask(CoreProtect.getInstance(), () -> flushSlotChanges(player, scheduleToken), player, 1);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        suppress((Player) event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            suppress((Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            suppress((Player) event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerItemBreak(PlayerItemBreakEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPickupArrow(PlayerPickupArrowEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Projectile)) {
            return;
        }
        ProjectileSource shooter = ((Projectile) entity).getShooter();
        if (shooter instanceof Player) {
            suppress((Player) shooter);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        suppress(event.getPlayer());
    }

    /**
     * Gravestone restores its contents directly through the Minecraft player
     * inventory when the grave is destroyed. Mohist consequently exposes the
     * block break to Bukkit, but no pickup or inventory-click event describing
     * the restored items. Capture the inventory while the Bukkit break event is
     * still running and compare it after the mod has completed the destruction.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGravestoneBreak(BlockBreakEvent event) {
        if (isGravestone(event.getBlock())) {
            captureSnapshot(event.getPlayer(), event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockIgnite(BlockIgniteEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        suppress(event.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerFish(PlayerFishEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerItemDamage(PlayerItemDamageEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerItemMend(PlayerItemMendEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityResurrect(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player) {
            suppress((Player) event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        suppress(event.getPlayer(), INTERACTION_SUPPRESSION_TICKS);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerTakeLecternBook(PlayerTakeLecternBookEvent event) {
        suppress(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!paperSlotEventsAvailable) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PENDING_SLOT_CHANGES.remove(uuid);
        SUPPRESSION_TOKENS.remove(uuid);
        LoginBaseline baseline = new LoginBaseline(player.getInventory().getContents());
        LOGIN_BASELINES.put(uuid, baseline);
        JOINED_PLAYERS.add(uuid);
        Scheduler.scheduleSyncDelayedTask(CoreProtect.getInstance(), () -> LOGIN_BASELINES.remove(uuid, baseline), player, LOGIN_BASELINE_TICKS);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            captureSnapshot((Player) event.getWhoClicked());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!paperSlotEventsAvailable) {
            captureOnlinePlayerSnapshots();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerCommand(ServerCommandEvent event) {
        if (!paperSlotEventsAvailable) {
            captureOnlinePlayerSnapshots();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PENDING_SLOT_CHANGES.remove(uuid);
        PENDING_SNAPSHOTS.remove(uuid);
        LOGIN_BASELINES.remove(uuid);
        SUPPRESSION_TOKENS.remove(uuid);
        JOINED_PLAYERS.remove(uuid);
    }

    private static void captureOnlinePlayerSnapshots() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            captureSnapshot(player);
        }
    }

    private static void captureSnapshot(Player player) {
        captureSnapshot(player, player == null ? null : player.getLocation());
    }

    private static void captureSnapshot(Player player, Location location) {
        if (!isEnabled(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        PendingSnapshot snapshot = PENDING_SNAPSHOTS.computeIfAbsent(uuid, ignored -> new PendingSnapshot(player, location));
        if (snapshot.markScheduled()) {
            Object scheduleToken = snapshot.scheduleToken;
            Scheduler.scheduleSyncDelayedTask(CoreProtect.getInstance(), () -> flushSnapshot(player, scheduleToken), player, 1);
        }
    }

    private static boolean isGravestone(org.bukkit.block.Block block) {
        if (block == null) {
            return false;
        }

        if (isGravestoneKey(MaterialUtils.getMaterialKey(block.getType()))) {
            return true;
        }

        try {
            return isGravestoneKey(block.getBlockData().getAsString());
        }
        catch (Exception | LinkageError e) {
            return false;
        }
    }

    static boolean isGravestoneKey(String key) {
        return GRAVESTONE_BLOCK_KEY.equals(BlockTypeUtils.getBlockDataKey(key));
    }

    private static void suppress(Player player) {
        suppress(player, KNOWN_ACTION_SUPPRESSION_TICKS);
    }

    private static void suppress(Player player, int ticks) {
        if (!isEnabled(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        PENDING_SLOT_CHANGES.remove(uuid);
        long expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ticks * 50L);
        SuppressionToken requestedToken = new SuppressionToken(expiresAt);
        SuppressionToken activeToken = SUPPRESSION_TOKENS.compute(uuid, (key, existing) -> existing != null && existing.expiresAt >= expiresAt ? existing : requestedToken);
        if (activeToken == requestedToken) {
            Scheduler.scheduleSyncDelayedTask(CoreProtect.getInstance(), () -> SUPPRESSION_TOKENS.remove(uuid, requestedToken), player, ticks);
        }
    }

    private static void flushSlotChanges(Player player, Object scheduleToken) {
        UUID uuid = player.getUniqueId();
        PendingSlotChanges pending = PENDING_SLOT_CHANGES.get(uuid);
        if (pending == null || pending.scheduleToken != scheduleToken || !PENDING_SLOT_CHANGES.remove(uuid, pending)) {
            return;
        }
        if (SUPPRESSION_TOKENS.containsKey(uuid) || !isEnabled(player)) {
            return;
        }

        List<ItemStack> oldItems = new ArrayList<>();
        List<ItemStack> newItems = new ArrayList<>();
        for (SlotChange change : pending.slotChanges.values()) {
            addValidItem(oldItems, change.oldItem);
            addValidItem(newItems, change.newItem);
        }
        queueDifference(player.getName(), pending.location, oldItems, newItems);
    }

    private static void flushSnapshot(Player player, Object scheduleToken) {
        UUID uuid = player.getUniqueId();
        PendingSnapshot snapshot = PENDING_SNAPSHOTS.get(uuid);
        if (snapshot == null || snapshot.scheduleToken != scheduleToken || !PENDING_SNAPSHOTS.remove(uuid, snapshot) || !isEnabled(player)) {
            return;
        }

        List<ItemStack> oldItems = validItems(snapshot.contents);
        List<ItemStack> newItems = validItems(player.getInventory().getContents());
        queueDifference(player.getName(), snapshot.location, oldItems, newItems);
    }

    static void queueDifference(String user, Location location, Collection<ItemStack> oldContents, Collection<ItemStack> newContents) {
        InventoryDelta delta = calculateDifference(oldContents, newContents);
        List<ItemStack> removed = delta.removed;
        List<ItemStack> added = delta.added;

        if (removed.isEmpty() && added.isEmpty()) {
            return;
        }

        String loggingItemId = user.toLowerCase(Locale.ROOT) + "." + location.getBlockX() + "." + location.getBlockY() + "." + location.getBlockZ();
        int itemId = getItemId(loggingItemId);
        if (!added.isEmpty()) {
            ConfigHandler.itemsExternalAdd.compute(loggingItemId, (key, items) -> append(items, added));
        }
        if (!removed.isEmpty()) {
            ConfigHandler.itemsExternalRemove.compute(loggingItemId, (key, items) -> append(items, removed));
        }
        int time = (int) (System.currentTimeMillis() / 1000L) + 1;
        Queue.queueItemTransaction(user, location.clone(), time, 0, itemId);
    }

    static InventoryDelta calculateDifference(Collection<ItemStack> oldContents, Collection<ItemStack> newContents) {
        List<ItemStack> removed = cloneItems(oldContents);
        List<ItemStack> added = cloneItems(newContents);

        for (ItemStack oldItem : removed) {
            if (!isValid(oldItem)) {
                continue;
            }
            for (ItemStack newItem : added) {
                if (!isValid(newItem) || !oldItem.isSimilar(newItem)) {
                    continue;
                }
                int shared = Math.min(oldItem.getAmount(), newItem.getAmount());
                oldItem.setAmount(oldItem.getAmount() - shared);
                newItem.setAmount(newItem.getAmount() - shared);
                if (oldItem.getAmount() == 0) {
                    break;
                }
            }
        }

        removed.removeIf(item -> !isValid(item));
        added.removeIf(item -> !isValid(item));
        return new InventoryDelta(removed, added);
    }

    private static List<ItemStack> append(List<ItemStack> existing, Collection<ItemStack> additions) {
        List<ItemStack> result = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        result.addAll(cloneItems(additions));
        return result;
    }

    private static List<ItemStack> validItems(ItemStack[] contents) {
        List<ItemStack> result = new ArrayList<>();
        if (contents != null) {
            for (ItemStack item : contents) {
                addValidItem(result, item);
            }
        }
        return result;
    }

    private static List<ItemStack> cloneItems(Collection<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        if (items != null) {
            for (ItemStack item : items) {
                addValidItem(result, item);
            }
        }
        return result;
    }

    private static void addValidItem(List<ItemStack> items, ItemStack item) {
        if (isValid(item)) {
            items.add(item.clone());
        }
    }

    private static boolean isValid(ItemStack item) {
        return item != null && item.getAmount() > 0 && !BlockUtils.isAir(item.getType());
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        boolean firstValid = isValid(first);
        boolean secondValid = isValid(second);
        if (!firstValid || !secondValid) {
            return firstValid == secondValid;
        }
        return first.getAmount() == second.getAmount() && first.isSimilar(second);
    }

    private static boolean isEnabled(Player player) {
        if (player == null || !player.isOnline() || player.getWorld() == null) {
            return false;
        }
        Config config = Config.getConfig(player.getWorld());
        return config.ITEM_TRANSACTIONS && config.EXTERNAL_INVENTORY_TRANSACTIONS;
    }

    static final class InventoryDelta {
        private final List<ItemStack> removed;
        private final List<ItemStack> added;

        private InventoryDelta(List<ItemStack> removed, List<ItemStack> added) {
            this.removed = removed;
            this.added = added;
        }

        List<ItemStack> getRemoved() {
            return cloneItems(removed);
        }

        List<ItemStack> getAdded() {
            return cloneItems(added);
        }
    }

    private static final class SuppressionToken {
        private final long expiresAt;

        private SuppressionToken(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    static final class LoginBaseline {
        private final ItemStack[] contents;
        private final boolean[] pendingSlots;
        private int pendingCount;

        LoginBaseline(ItemStack[] contents) {
            ItemStack[] capturedContents = ItemUtils.getContainerState(contents);
            this.contents = capturedContents == null ? new ItemStack[0] : capturedContents;
            this.pendingSlots = new boolean[this.contents.length];
            for (int i = 0; i < this.pendingSlots.length; i++) {
                this.pendingSlots[i] = true;
            }
            this.pendingCount = this.pendingSlots.length;
        }

        synchronized boolean consumeIfLoaded(int slot, ItemStack newItem) {
            if (slot < 0 || slot >= pendingSlots.length || !pendingSlots[slot]) {
                return false;
            }

            if (sameItem(contents[slot], newItem)) {
                return true;
            }

            pendingSlots[slot] = false;
            pendingCount--;
            return false;
        }

        synchronized boolean isConsumed() {
            return pendingCount == 0;
        }
    }

    private static final class PendingSlotChanges {
        private final Location location;
        private final Map<Integer, SlotChange> slotChanges = new ConcurrentHashMap<>();
        private final Object scheduleToken = new Object();
        private boolean scheduled;

        private PendingSlotChanges(Player player) {
            this.location = player.getLocation().clone();
        }

        private void record(int rawSlot, ItemStack oldItem, ItemStack newItem) {
            slotChanges.compute(rawSlot, (slot, existing) -> existing == null ? new SlotChange(oldItem, newItem) : existing.update(newItem));
        }

        private synchronized boolean markScheduled() {
            if (scheduled) {
                return false;
            }
            scheduled = true;
            return true;
        }
    }

    private static final class SlotChange {
        private final ItemStack oldItem;
        private ItemStack newItem;

        private SlotChange(ItemStack oldItem, ItemStack newItem) {
            this.oldItem = cloneItem(oldItem);
            this.newItem = cloneItem(newItem);
        }

        private SlotChange update(ItemStack item) {
            this.newItem = cloneItem(item);
            return this;
        }

        private static ItemStack cloneItem(ItemStack item) {
            return item == null || item.getType() == Material.AIR ? null : item.clone();
        }
    }

    private static final class PendingSnapshot {
        private final ItemStack[] contents;
        private final Location location;
        private final Object scheduleToken = new Object();
        private boolean scheduled;

        private PendingSnapshot(Player player, Location location) {
            this.contents = ItemUtils.getContainerState(player.getInventory().getContents());
            this.location = (location == null ? player.getLocation() : location).clone();
        }

        private synchronized boolean markScheduled() {
            if (scheduled) {
                return false;
            }
            scheduled = true;
            return true;
        }
    }
}
