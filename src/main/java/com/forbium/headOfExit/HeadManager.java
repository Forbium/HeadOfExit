package com.forbium.headOfExit;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class HeadManager {

    private final HeadOfExit plugin;
    private final NamespacedKey ownerKey; // ключ для хранения UUID в PDC

    private File dataFile;
    private FileConfiguration data;

    public HeadManager(HeadOfExit plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "head_owner");
        loadData();
    }

    // ==================== ДАННЫЕ (файл) ====================

    private void loadData() {
        dataFile = new File(plugin.getDataFolder(), "heads.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    public void saveData() {
        try { data.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    // Сохранить координаты головы игрока
    public void saveHeadLocation(UUID playerUUID, Location loc) {
        String path = "heads." + playerUUID;
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
        data.set(path + ".holder", null); // очищаем держателя!
        saveData();
    }

    // Удалить данные о голове игрока
    public void removeHeadData(UUID playerUUID) {
        data.set("heads." + playerUUID, null);
        saveData();
    }

    // Получить координаты головы игрока
    public Location getHeadLocation(UUID playerUUID) {
        String path = "heads." + playerUUID;
        if (!data.contains(path)) return null;

        String worldName = data.getString(path + ".world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        double x = data.getDouble(path + ".x");
        double y = data.getDouble(path + ".y");
        double z = data.getDouble(path + ".z");

        return new Location(world, x, y, z);
    }

    // Есть ли голова у этого игрока в мире
    public boolean hasHead(UUID playerUUID) {
        return data.contains("heads." + playerUUID);
    }

    // ==================== ГОЛОВА (предмет) ====================

    // Создать предмет головы с UUID владельца в PDC
    public ItemStack createHead(OfflinePlayer owner) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        // Скин по UUID — работает и для офлайн игроков
        PlayerProfile profile = Bukkit.createPlayerProfile(owner.getUniqueId(), owner.getName());
        meta.setOwnerProfile(profile);

        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
        meta.setDisplayName(plugin.getLang().get("head-owner") + owner.getName());
        head.setItemMeta(meta);
        return head;
    }

    public void saveHeadHolder(UUID ownerUUID, UUID holderUUID) {
        String path = "heads." + ownerUUID;
        data.set(path + ".holder", holderUUID.toString());
        data.set(path + ".world", null); // очищаем координаты блока!
        data.set(path + ".x", null);
        data.set(path + ".y", null);
        data.set(path + ".z", null);
        saveData();
    }

    public UUID getHeadHolder(UUID ownerUUID) {
        String path = "heads." + ownerUUID + ".holder";
        if (!data.contains(path)) return null;
        try {
            return UUID.fromString(data.getString(path));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Получить UUID владельца из предмета головы (или null если это не наша голова)
    public UUID getOwnerFromItem(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof SkullMeta meta)) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(ownerKey, PersistentDataType.STRING)) return null;

        String uuidStr = pdc.get(ownerKey, PersistentDataType.STRING);
        try {
            return UUID.fromString(uuidStr); // строку превращаем обратно в UUID
        } catch (IllegalArgumentException e) {
            return null; // если строка не является UUID
        }
    }

    // Получить UUID владельца из блока головы (или null если это не наша голова)
    public UUID getOwnerFromBlock(Block block) {
        if (!(block.getState() instanceof Skull skull)) return null;

        PersistentDataContainer pdc = skull.getPersistentDataContainer();
        if (!pdc.has(ownerKey, PersistentDataType.STRING)) return null;

        String uuidStr = pdc.get(ownerKey, PersistentDataType.STRING);
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== ЛОГИКА ====================

    // Поставить голову игрока на его позицию при выходе
    public void placeHeadOnQuit(Player player) {
        // Убираем старую голову если есть
        if (hasHead(player.getUniqueId())) {
            Location oldLoc = getHeadLocation(player.getUniqueId());
            if (oldLoc != null) {
                Block oldBlock = oldLoc.getBlock();
                if (player.getUniqueId().equals(getOwnerFromBlock(oldBlock))) {
                    oldBlock.setType(Material.AIR);
                }
            }
        }

        placeHead(player.getUniqueId(), player.getLocation());
    }

    // Игрок вошёл — телепортируем на голову и убираем её
    public void handlePlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();
        if (!hasHead(uuid)) return;

        UUID holderUUID = getHeadHolder(uuid);

        if (holderUUID != null) {
            Player holder = Bukkit.getPlayer(holderUUID);
            if (holder != null && holder.isOnline()) {
                removeHeadFromHolder(uuid, holder);
                player.teleport(holder.getLocation());
                player.sendMessage(plugin.getLang().get("appeared-at-player") + holder.getName());
            } else {
                player.sendMessage(plugin.getLang().get("holder-offline"));
            }
            removeHeadData(uuid);
            return;
        }

        // Голова в стопке
        String pileKey = getPilePath(uuid);
        if (pileKey != null) {
            Location pileLoc = pileKeyToLocation(pileKey);
            if (pileLoc != null) {
                player.teleport(pileLoc.clone().add(0.5, 0, 0.5));
            }
            removeHeadFromPile(uuid); // удаляем только свою голову
            removeHeadData(uuid);
            return;
        }

        removeHeadData(uuid);
    }

    // Вспомогательный метод — убрать голову из инвентаря держателя
    private void removeHeadFromHolder(UUID ownerUUID, Player holder) {
        for (ItemStack item : holder.getInventory().getContents()) {
            if (ownerUUID.equals(getOwnerFromItem(item))) {
                holder.getInventory().remove(item);
                break;
            }
        }
    }

    // Проверить является ли предмет нашей головой
    public boolean isPlayerHead(ItemStack item) {
        return getOwnerFromItem(item) != null;
    }

    public boolean placeHead(UUID ownerUUID, Location startLoc) {
        Location loc = startLoc.clone();
        Block block = loc.getBlock();

        // Ищем место: либо уже существующая стопка, либо свободный блок
        int attempts = 0;
        Location pileFound = null;
        Location freeFound = null;

        Location search = loc.clone();
        for (int i = 0; i < 10; i++) {
            Block b = search.getBlock();
            if (isPile(search) && freeFound == null) {
                pileFound = search.clone(); // нашли стопку — приоритет
                break;
            }
            if (b.getType().isAir() && freeFound == null) {
                freeFound = search.clone();
            }
            search.add(0, 1, 0);
        }

        Location target = pileFound != null ? pileFound : freeFound;
        if (target == null) return false;

        if (!target.getWorld().isChunkLoaded(target.getBlock().getChunk())) return false;
        int y = target.getBlockY();
        if (y < target.getWorld().getMinHeight() || y >= target.getWorld().getMaxHeight()) return false;

        // Ставим блок если его нет
        Block targetBlock = target.getBlock();
        if (targetBlock.getType().isAir()) {
            targetBlock.setType(Material.PLAYER_HEAD);
            // Ставим скин первого игрока в стопке (или текущего)
            org.bukkit.block.Skull skull = (org.bukkit.block.Skull) targetBlock.getState();
            skull.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, "pile");
            PlayerProfile profile = Bukkit.createPlayerProfile(ownerUUID, Bukkit.getOfflinePlayer(ownerUUID).getName());
            skull.setOwnerProfile(profile);
            skull.update();
        }

        saveHeadInPile(ownerUUID, target);
        return true;
    }


    // Ключ координаты для стопки
    private String locKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    // Сохранить голову в стопку на координатах
    public void saveHeadInPile(UUID ownerUUID, Location loc) {
        String pileKey = locKey(loc);
        String ownerPath = "heads." + ownerUUID;
        String pilePath = "headpiles." + pileKey;

        // Записываем игроку где его голова
        data.set(ownerPath + ".pile", pileKey);
        data.set(ownerPath + ".world", null);
        data.set(ownerPath + ".x", null);
        data.set(ownerPath + ".y", null);
        data.set(ownerPath + ".z", null);
        data.set(ownerPath + ".holder", null);

        // Добавляем UUID в список стопки
        List<String> uuids = data.getStringList(pilePath);
        if (!uuids.contains(ownerUUID.toString())) {
            uuids.add(ownerUUID.toString());
        }
        data.set(pilePath, uuids);

        saveData();
    }

    // Убрать голову из стопки
    public void removeHeadFromPile(UUID ownerUUID) {
        String pilePath = getPilePath(ownerUUID);
        if (pilePath == null) return;

        List<String> uuids = data.getStringList("headpiles." + pilePath);
        uuids.remove(ownerUUID.toString());

        if (uuids.isEmpty()) {
            // Стопка пустая — убираем блок и данные стопки
            data.set("headpiles." + pilePath, null);
            Location loc = pileKeyToLocation(pilePath);
            if (loc != null) loc.getBlock().setType(Material.AIR);
        } else {
            data.set("headpiles." + pilePath, uuids);
        }

        data.set("heads." + ownerUUID + ".pile", null);
        saveData();
    }

    // Получить ключ стопки для игрока
    public String getPilePath(UUID ownerUUID) {
        return data.getString("heads." + ownerUUID + ".pile");
    }

    // Получить все UUID голов в стопке
    public List<UUID> getHeadsInPile(Location loc) {
        String pileKey = locKey(loc);
        List<String> uuids = data.getStringList("headpiles." + pileKey);
        return uuids.stream().map(UUID::fromString).collect(Collectors.toList());
    }

    // Проверить является ли блок стопкой голов
    public boolean isPile(Location loc) {
        return data.contains("headpiles." + locKey(loc));
    }

    // Конвертировать ключ обратно в Location
    public Location pileKeyToLocation(String pileKey) {
        try {
            String[] parts = pileKey.split(",");
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return new Location(world, Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (Exception e) {
            return null;
        }
    }

    // Открыть виртуальный инвентарь стопки для игрока
    public void openPileInventory(Player player, Location loc) {
        List<UUID> uuids = getHeadsInPile(loc);
        if (uuids.isEmpty()) return;

        int size = (int) Math.ceil(uuids.size() / 9.0) * 9;
        size = Math.max(9, Math.min(size, 54));

        Inventory inv = Bukkit.createInventory(null, size, plugin.getLang().get("head-inventory"));

        for (int i = 0; i < uuids.size(); i++) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(uuids.get(i));
            inv.setItem(i, createHead(owner));
        }

        player.openInventory(inv);
    }
}