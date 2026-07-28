package net.coreprotect.paper.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import net.coreprotect.listener.player.ExternalInventoryChangeTracker;

public final class PaperInventorySlotChangeListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        ExternalInventoryChangeTracker.recordSlotChange(event.getPlayer(), event.getSlot(), event.getOldItemStack(), event.getNewItemStack());
    }
}
