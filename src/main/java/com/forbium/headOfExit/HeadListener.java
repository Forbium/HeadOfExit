package com.forbium.headOfExit;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.profile.PlayerProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class HeadListener implements Listener {

    private final HeadOfExit plugin;
    private final HeadManager headManager;

    public HeadListener(HeadOfExit plugin, HeadManager headManager) {
        this.plugin = plugin;
        this.headManager = headManager;
    }

    // Игрок вышел — ставим голову
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();

        for (ItemStack item : player.getInventory().getContents()) {
            UUID ownerUUID = headManager.getOwnerFromItem(item);
            if (ownerUUID == null) continue;
            if (ownerUUID.equals(player.getUniqueId())) continue;

            player.getInventory().remove(item);

            // Если не удалось разместить — голова просто исчезает
            // можно залогировать или обработать иначе
            headManager.placeHead(ownerUUID, player.getLocation());
        }

        headManager.placeHeadOnQuit(player);
    }

    // Игрок вошёл — телепортируем и убираем голову
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Небольшая задержка чтобы мир успел загрузиться
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            headManager.handlePlayerJoin(e.getPlayer());
        }, 5L); // 5 тиков = 0.25 секунды
    }

    // Игрок ломает голову
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (headManager.isPile(e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.getLang().get("heads-here"));
            return;
        }
        Block block = e.getBlock();
        Player player = e.getPlayer();

        UUID ownerUUID = headManager.getOwnerFromBlock(block);
        if (ownerUUID == null) return;

        if (ownerUUID.equals(player.getUniqueId())) {
            e.setCancelled(true);
            player.sendMessage(plugin.getLang().get("cannot-break-own-head"));
            return;
        }

        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);
        ItemStack headItem = headManager.createHead(owner);

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(headItem);
        if (!leftover.isEmpty()) {
            e.setCancelled(true);
            player.sendMessage(plugin.getLang().get("no-inventory-space"));
            return;
        }

        e.setDropItems(false);

        // Голова теперь у игрока — сохраняем держателя
        headManager.saveHeadHolder(ownerUUID, player.getUniqueId());

        player.sendMessage(plugin.getLang().get("head-picked-up"));
    }

    // Игрок ставит голову — обновляем координаты
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        Player player = e.getPlayer();

        UUID ownerUUID = headManager.getOwnerFromItem(item);
        if (ownerUUID == null) return;

        if (ownerUUID.equals(player.getUniqueId())) {
            e.setCancelled(true);
            player.sendMessage(plugin.getLang().get("cannot-place-own-head"));
            return;
        }

        Block block = e.getBlockPlaced();

        // Записываем UUID в PDC блока
        org.bukkit.block.Skull skull = (org.bukkit.block.Skull) block.getState();
        skull.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "head_owner"),
                org.bukkit.persistence.PersistentDataType.STRING,
                ownerUUID.toString()
        );
        skull.update();

        // Регистрируем как стопку а не просто координаты
        headManager.saveHeadInPile(ownerUUID, block.getLocation());

        player.sendMessage(plugin.getLang().get("head-placed"));
    }

    // Метод 1: запрет класть голову в хранилище (работает всегда)
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        if (headManager.isPlayerHead(e.getCursor())) {
            if (e.getInventory().getType() != InventoryType.PLAYER &&
                    e.getInventory().getType() != InventoryType.CRAFTING) {
                e.setCancelled(true);
                player.sendMessage(plugin.getLang().get("cannot-store-head"));
                return;
            }
        }

        // Shift+click
        if (headManager.isPlayerHead(e.getCurrentItem())
                && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
            player.sendMessage(plugin.getLang().get("cannot-store-head"));
            return;
        }

        // Цифры 1-9
        if (e.getAction() == InventoryAction.HOTBAR_SWAP
                || e.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
            int hotbarSlot = e.getHotbarButton();
            if (hotbarSlot >= 0) {
                ItemStack hotbarItem = player.getInventory().getItem(hotbarSlot);
                if (headManager.isPlayerHead(hotbarItem)) {
                    e.setCancelled(true);
                    player.sendMessage(plugin.getLang().get("cannot-store-head"));
                    return;
                }
            }
            if (headManager.isPlayerHead(e.getCurrentItem())) {
                e.setCancelled(true);
                player.sendMessage(plugin.getLang().get("cannot-store-head"));
            }
        }
    }

    // Метод 2: логика инвентаря стопки
    @EventHandler(priority = EventPriority.HIGH)
    public void onPileInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!e.getView().getTitle().equals(plugin.getLang().get("head-inventory"))) return;

        e.setCancelled(true);

        if (e.getClickedInventory() == e.getView().getBottomInventory()) return;

        ItemStack item = e.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        UUID ownerUUID = headManager.getOwnerFromItem(item);
        if (ownerUUID == null) return;

        if (ownerUUID.equals(player.getUniqueId())) {
            player.sendMessage(plugin.getLang().get("cannot-take-own-head"));
            return;
        }

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
        if (!leftover.isEmpty()) {
            player.sendMessage(plugin.getLang().get("no-inventory-space"));
            return;
        }

        e.getClickedInventory().remove(item);
        headManager.removeHeadFromPile(ownerUUID);
        headManager.saveHeadHolder(ownerUUID, player.getUniqueId());
        player.sendMessage(plugin.getLang().get("head-picked-up"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!headManager.isPlayerHead(e.getOldCursor())) return;

        // Проверяем все слоты куда тащат предмет
        for (int slot : e.getRawSlots()) {
            if (isStorage(e.getView().getInventory(slot))) {
                e.setCancelled(true);
                player.sendMessage(plugin.getLang().get("cannot-store-head"));
                return;
            }
        }
    }

    // Проверяет является ли инвентарь хранилищем (не инвентарём игрока)
    private boolean isStorage(Inventory inventory) {
        if (inventory == null) return false;
        InventoryType type = inventory.getType();
        return type == InventoryType.CHEST
                || type == InventoryType.SHULKER_BOX
                || type == InventoryType.BARREL
                || type == InventoryType.DISPENSER
                || type == InventoryType.DROPPER
                || type == InventoryType.HOPPER
                || type == InventoryType.ENDER_CHEST;
    }
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (headManager.isPlayerHead(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.getLang().get("cannot-drop-head"));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();
        List<ItemStack> toRemove = new ArrayList<>();

        for (ItemStack item : e.getDrops()) {
            UUID ownerUUID = headManager.getOwnerFromItem(item);
            if (ownerUUID == null) continue;
            if (ownerUUID.equals(player.getUniqueId())) continue;

            toRemove.add(item);

            // Ставим голову на место смерти
            Location loc = player.getLocation().clone();
            Block block = loc.getBlock();

            while (!block.getType().isAir()) {
                loc.add(0, 1, 0);
                block = loc.getBlock();
            }

            block.setType(Material.PLAYER_HEAD);
            org.bukkit.block.Skull skull = (org.bukkit.block.Skull) block.getState();
            skull.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, "head_owner"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    ownerUUID.toString()
            );

            OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUUID);
            PlayerProfile profile = Bukkit.createPlayerProfile(ownerUUID, owner.getName());
            skull.setOwnerProfile(profile);
            skull.update();

            headManager.saveHeadLocation(ownerUUID, block.getLocation());
        }

        e.getDrops().removeAll(toRemove);
    }


    @EventHandler
    public void onBlockInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;

        if (headManager.isPile(block.getLocation())) {
            e.setCancelled(true);
            headManager.openPileInventory(e.getPlayer(), block.getLocation());
        }
    }

    @EventHandler
    public void onFrameInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame)) return;

        ItemFrame frame = (ItemFrame) e.getRightClicked();
        Player player = e.getPlayer();

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        ItemStack itemInOffHand = player.getInventory().getItemInOffHand();

        if (frame.getItem().getType() == Material.AIR) {
            UUID headID = headManager.getOwnerFromItem(itemInHand);

            if (headID != null) {
                e.setCancelled(true);
            } else {
                headID = headManager.getOwnerFromItem(itemInOffHand);
                if (headID != null) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onArmorStandInteract(PlayerArmorStandManipulateEvent e) {
        if (plugin.getConfig().getString("language", "can-hold-armor-stand").equals("false")) return;
        if (e.getSlot() != EquipmentSlot.HEAD) return;

        Player player = e.getPlayer();
        ItemStack itemInHand = e.getPlayerItem();
        ItemStack itemOnStand = e.getArmorStandItem();

        if (itemOnStand != null && itemOnStand.getType() != Material.AIR) {
            UUID headID = headManager.getOwnerFromItem(itemOnStand);
            if (headID != null) {
                headManager.saveHeadHolder(headID, player.getUniqueId());
                e.getRightClicked().setInvulnerable(false);
            }
        }

        if (itemInHand != null && itemInHand.getType() != Material.AIR) {
            UUID headID = headManager.getOwnerFromItem(itemInHand);
            if (headID != null) {
                headManager.saveHeadHolder(headID, e.getRightClicked().getUniqueId());
                e.getRightClicked().setInvulnerable(true);
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> headManager.isPile(block.getLocation()));
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(block -> headManager.isPile(block.getLocation()));
    }
}