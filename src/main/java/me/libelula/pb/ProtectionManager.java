/*
 *            This file is part of  LibelulaProtectionBlocks.
 *
 *   LibelulaProtectionBlocks is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *   LibelulaProtectionBlocks is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with  LibelulaProtectionBlocks.
 *  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package me.libelula.pb;

import com.sk89q.worldguard.protection.flags.DefaultFlag;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * @author Diego D'Onofrio <ddonofrio@member.fsf.org>
 */
public class ProtectionManager {

    private final Main plugin;
    private final TextManager tm;
    private final Set<Material> materialsCache = new TreeSet<>();
    private final Map<UUID, ProtectionBlock> uuidsCache = new TreeMap<>();
    private final Set<ProtectionBlock> createdBlocks = new TreeSet<>();
    private final Map<Location, ProtectionBlock> placedBlocks = new TreeMap<>(new LocationComparator());
    private final Map<UUID, Set<ProtectionBlock>> playersBlocks = new TreeMap<>();
    private final Map<String, Integer> permissions = new TreeMap<>();
    private final Set<Material> fenceReplaces = new TreeSet<>();
    private Set<String> configurableFlags = new TreeSet<>();
    private final ReentrantLock _pb_mutex = new ReentrantLock();
    private final ItemStack air = new ItemStack(Material.AIR);
    private final File pbFile;

    public ProtectionManager(Main plugin) {
        this.plugin = plugin;
        tm = plugin.tm;
        pbFile = new File(plugin.getDataFolder(), "pb.yml");
    }

    public void initialize() {
        load();
    }

    public void addFenceFlag(Player player) {
        ItemStack itemInHand = player.getItemInHand();
        ProtectionBlock pb = getPB(itemInHand);
        if (pb != null) {
            if (pb.hasFence()) {
                plugin.sendMessage(player,
                        ChatColor.RED + tm.getText("already_fence"));
            } else {
                pb.setFence(true);
                player.setItemInHand(pb.getItemStack());
            }
        } else {
            plugin.sendMessage(player, ChatColor.RED + tm.getText("block_not_pb"));
        }
    }

    public boolean createProtectionBlock(Player player,
                                         int maxX, int maxY, int maxZ) {
        boolean result = true;
        ItemStack itemInHand = player.getItemInHand();
        if (ProtectionBlock.validateMaterial(itemInHand.getType())) {
            if (itemInHand.getAmount() != 1) {
                plugin.sendMessage(player, ChatColor.RED + tm.getText("only_one_solid_block"));
            } else {
                if (isProtectionBlock(itemInHand)) {
                    plugin.sendMessage(player, ChatColor.RED + tm.getText("block_already_pb"));
                } else {
                    ProtectionBlock pb = generateBlock(itemInHand.getType(),
                            itemInHand.getData(), player.getName(), maxX, maxY, maxZ);
                    player.setItemInHand(pb.getItemStack());
                    plugin.sendMessage(player, tm.getText("protection_block_created"));
                }
            }
        } else {
            plugin.sendMessage(player, ChatColor.RED + tm.getText("not_proper_material"));
            plugin.sendMessage(player, tm.getText("must_be_solid_blocks"));
            result = false;
        }

        return result;
    }

    @SuppressWarnings("deprecation")
    public ProtectionBlock generateBlock(Material material, MaterialData materialData,
                                         String ownerName, int maxX, int maxY, int maxZ) {
        ProtectionBlock pb = new ProtectionBlock(plugin);
        ItemStack is;
        if (materialData != null) {
            is = new ItemStack(material, 1, (short) 0, materialData.getData());
        } else {
            is = new ItemStack(material, 1);
        }
        is.setData(materialData);
        ItemMeta dtMeta = is.getItemMeta();
        dtMeta.setDisplayName(tm.getText("protection_block_name",
                Integer.toString(maxX),
                Integer.toString(maxY),
                Integer.toString(maxZ)));
        List<String> loreText = new ArrayList<>();
        loreText.add(tm.getText("created_by", ownerName));
        loreText.add(pb.getUuid().toString().substring(0, 18));
        loreText.add(pb.getUuid().toString().substring(19));
        pb.setName(dtMeta.getDisplayName());
        pb.setLoreText(loreText);
        dtMeta.setLore(loreText);
        is.setItemMeta(dtMeta);
        pb.setItemStack(is);
        pb.setSizeX(maxX);
        pb.setSizeY(maxY);
        pb.setSizeZ(maxZ);
        _pb_mutex.lock();
        try {
            materialsCache.add(pb.getMaterial());
            createdBlocks.add(pb);
            uuidsCache.put(pb.getUuid(), pb);
        } finally {
            _pb_mutex.unlock();
        }

        return pb;
    }

    private void revertPlacedPb(final ProtectionBlock pb, final BlockPlaceEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            e.getPlayer().setItemInHand(pb.getItemStack());
            e.getBlock().setType(Material.AIR);
            pb.setLocation(null);
        });
    }

    public void placePb(final BlockPlaceEvent e) {
        e.getPlayer().setItemInHand(air);
        final List<String> lore = e.getItemInHand().getItemMeta().getLore();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String uuidString = lore.get(1).concat("-")
                    .concat(lore.get(2));
            ProtectionBlock pb = getPb(UUID.fromString(uuidString));
            pb.setPlayerUUID(e.getPlayer().getUniqueId());
            pb.setPlayerName(e.getPlayer().getName());
            pb.setLocation(e.getBlock().getLocation());
            if (plugin.getWG().overlapsUnownedRegion(pb.getRegion(),
                    e.getPlayer())) {
                plugin.sendMessage(e.getPlayer(), ChatColor.RED
                        + tm.getText("overlaps"));
                revertPlacedPb(pb, e);
            } else if (!e.getPlayer().hasPermission("pb.place")) {
                plugin.sendMessage(e.getPlayer(), ChatColor.RED
                        + tm.getText("not_permission_active_pb"));
                revertPlacedPb(pb, e);
            } else if (!e.getPlayer().hasPermission("pb.protection.unlimited")
                    && playersBlocks.get(e.getPlayer().getUniqueId()) != null) {
                int playerBlocks = playersBlocks.get(e.getPlayer().getUniqueId()).size();
                if (getMaxProtections(e.getPlayer()) > playerBlocks) {
                    placePb(pb, e);
                } else {
                    plugin.sendMessage(e.getPlayer(), ChatColor.RED
                            + tm.getText("over_pb_limit"));
                    revertPlacedPb(pb, e);
                }
            } else {
                placePb(pb, e);
            }
        });
    }

    private void placePb(ProtectionBlock pb, BlockPlaceEvent e) {
        ajustPriority(pb);
        _pb_mutex.lock();
        try {
            createdBlocks.remove(pb);
            placedBlocks.put(e.getBlock().getLocation(), pb);
            Set<ProtectionBlock> pbs
                    = playersBlocks.get(e.getPlayer().getUniqueId());
            if (pbs == null) {
                pbs = new TreeSet<>();
            }
            pbs.add(pb);
            _pb_mutex.lock();
            try {
                playersBlocks.remove(e.getPlayer().getUniqueId());
                playersBlocks.put(e.getPlayer().getUniqueId(), pbs);
            } finally {
                _pb_mutex.unlock();
            }
            generateWgRegion(pb);
        } finally {
            _pb_mutex.unlock();
        }
        if (pb.hasFence()) {
            pb.drawFence();
            pb.setFence(false);
        }
    }

    @SuppressWarnings("deprecation")
    public void breakProtectionBlock(final BlockBreakEvent e) {
        e.setExpToDrop(0);

        final ProtectionBlock pb = placedBlocks.get(e.getBlock().getLocation());
        Player player = e.getPlayer();
        if (!pb.getRegion().getOwners().contains(plugin.getWG().wrapPlayer(player))
                && !e.getPlayer().hasPermission("pb.break.others")) {
            plugin.sendMessage(player, ChatColor.RED
                    + tm.getText("not_owned_by_you"));
            Bukkit.getScheduler().runTask(plugin, () -> {
                e.getBlock().setType(pb.getMaterial());
                e.getBlock().setData(pb.getItemStack().getData().getData());
            });
        } else {
            HashMap<Integer, ItemStack> remaining
                    = e.getPlayer().getInventory().addItem(pb.getItemStack());
            if (remaining.size() > 0) {
                plugin.sendMessage(player, ChatColor.RED
                        + tm.getText("not_inventory_space"));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    e.getBlock().setType(pb.getMaterial());
                    e.getBlock().setData(pb.getItemStack().getData().getData());
                });
            } else {
                removeBlock(pb);
            }
        }
    }

    public void removeBlock(ProtectionBlock block) {
        _pb_mutex.lock();
        try {
            if (block.getLocation() != null) {
                plugin.getWG().removeRegion(block);
                placedBlocks.remove(block.getLocation());
            }
            if (block.getPlayerUUID() != null) {
                playersBlocks.get(block.getPlayerUUID()).remove(block);
            }
            createdBlocks.add(block);
        } finally {
            _pb_mutex.unlock();
        }
    }

    public boolean shouldCancelItemDrop(Location location) {
        return Optional.ofNullable(placedBlocks.get(location))
                .map(block -> !block.isHidden())
                .orElse(false);
    }

    private void generateWgRegion(final ProtectionBlock block) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getWG().createRegion(block));
    }

    public boolean isProtectionBlock(Block block) {
        return materialsCache.contains(block.getType()) && placedBlocks.containsKey(block.getLocation());
    }

    public ProtectionBlock getPB(ItemStack is) {
        ProtectionBlock result = null;
        if (materialsCache.contains(is.getType())) {
            List<String> lore = is.getItemMeta().getLore();
            if (lore != null && lore.size() >= 3) {
                String uuidString = lore.get(1).concat("-").concat(lore.get(2));
                result = uuidsCache.get(UUID.fromString(uuidString));
            }
        }
        return result;
    }

    public boolean isProtectionBlock(ItemStack is) {
        boolean result = false;
        if (materialsCache.contains(is.getType())) {
            List<String> lore = is.getItemMeta().getLore();
            if (lore != null && lore.size() >= 3) {
                String uuidString = lore.get(1).concat("-").concat(lore.get(2));
                result = uuidsCache.containsKey(UUID.fromString(uuidString));
            }
        }
        return result;
    }

    public ProtectionBlock getPb(UUID uuid) {
        return uuidsCache.get(uuid);
    }

    public void ajustPriority(ProtectionBlock pb) {
        _pb_mutex.lock();
        try {
            for (ProtectionBlock oPb : placedBlocks.values()) {
                if (pb.equals(oPb) || oPb.getWorld() == null
                        || !oPb.getWorld().getUID().equals(pb.getWorld().getUID())) {

                    continue;
                }
                if (oPb.getMin().getBlockX() <= pb.getLocation().getBlockX()
                        && oPb.getMin().getBlockY() <= pb.getLocation().getBlockY()
                        && oPb.getMin().getBlockZ() <= pb.getLocation().getBlockZ()
                        && oPb.getMax().getBlockX() >= pb.getLocation().getBlockX()
                        && oPb.getMax().getBlockY() >= pb.getLocation().getBlockY()
                        && oPb.getMax().getBlockZ() >= pb.getLocation().getBlockZ()) {
                    if (pb.getRegion().getPriority() <= oPb.getRegion().getPriority()) {
                        pb.getRegion().setPriority(oPb.getRegion().getPriority() + 1);
                    }
                }
            }
        } finally {
            _pb_mutex.unlock();
        }
    }

    public void addPlayer(final Player player, final String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            ProtectionBlock pb = null;

            @Override
            public void run() {
                _pb_mutex.lock();
                try {
                    for (ProtectionBlock pbO : placedBlocks.values()) {
                        if (pbO.getPlayerUUID().equals(player.getUniqueId())
                                || player.hasPermission("pb.addmember.others")) {
                            if (pbO.getRegion().contains(player.getLocation().getBlockX(),
                                    player.getLocation().getBlockY(),
                                    player.getLocation().getBlockZ())) {
                                pb = pbO;
                                break;
                            }
                        }
                    }
                } finally {
                    _pb_mutex.unlock();
                }
                if (pb == null) {
                    plugin.sendMessage(player, ChatColor.RED
                            + tm.getText("not_in_your_parea"));
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getWG().addMemberPlayer(pb.getRegion(), playerName);
                        plugin.sendMessage(player,
                                tm.getText("player_member_added", playerName));
                    });
                }
            }
        });
    }

    public void delPlayer(final Player player, final String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            ProtectionBlock pb = null;

            @Override
            public void run() {
                _pb_mutex.lock();
                try {
                    for (ProtectionBlock pbO : placedBlocks.values()) {
                        if (pbO.getPlayerUUID().equals(player.getUniqueId())
                                || player.hasPermission("pb.addmember.others")) {
                            if (pbO.getRegion().contains(player.getLocation().getBlockX(),
                                    player.getLocation().getBlockY(),
                                    player.getLocation().getBlockZ())) {
                                pb = pbO;
                                break;
                            }
                        }
                    }
                } finally {
                    _pb_mutex.unlock();
                }
                if (pb == null) {
                    plugin.sendMessage(player, ChatColor.RED
                            + tm.getText("not_in_your_parea"));
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (plugin.getWG().delMemberPlayer(pb.getRegion(), playerName)) {
                            plugin.sendMessage(player,
                                    tm.getText("player_member_removed", playerName));
                        } else {
                            plugin.sendMessage(player,
                                    tm.getText("player_member_not_a_member", playerName));
                        }
                    });
                }
            }
        });
    }

    public int getMaxProtections(Player player) {
        int result = 1;
        if (player.hasPermission("pb.protection.multiple")) {
            for (String permName : permissions.keySet()) {
                if (player.hasPermission(permName)) {
                    int permValue = permissions.get(permName);
                    if (result < permValue) {
                        result = permValue;
                    }
                }
            }
        }
        return result;
    }

    public Set<Location> getPbLocations() {
        return this.placedBlocks.keySet();
    }

    public boolean isHidden(Location loc) {
        boolean result = true;
        ProtectionBlock pb = placedBlocks.get(loc);
        if (pb != null) {
            result = pb.isHidden();
        }
        return result;
    }

    public void hide(final Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            ProtectionBlock pb = null;

            @Override
            public void run() {
                _pb_mutex.lock();
                try {
                    for (ProtectionBlock pbO : placedBlocks.values()) {
                        if (pbO.getPlayerUUID().equals(player.getUniqueId())
                                || player.hasPermission("pb.hide.others")) {
                            if (pbO.getRegion().contains(player.getLocation().getBlockX(),
                                    player.getLocation().getBlockY(),
                                    player.getLocation().getBlockZ())) {
                                pb = pbO;
                                break;
                            }
                        }
                    }
                } finally {
                    _pb_mutex.unlock();
                }
                if (pb == null) {
                    plugin.sendMessage(player, ChatColor.RED
                            + tm.getText("not_in_your_parea"));
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (pb.isHidden()) {
                            plugin.sendMessage(player, ChatColor.RED
                                    + tm.getText("pb_is_already_hidden"));
                        } else {
                            pb.setHidden(true);
                            pb.getLocation().getBlock().setType(Material.AIR);
                        }
                    });
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    public void unhide(final Player player, final boolean force) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            ProtectionBlock pb = null;

            @Override
            public void run() {
                _pb_mutex.lock();
                try {
                    for (ProtectionBlock pbO : placedBlocks.values()) {
                        if (pbO.getPlayerUUID().equals(player.getUniqueId())
                                || player.hasPermission("pb.hide.others")) {
                            if (pbO.getRegion().contains(player.getLocation().getBlockX(),
                                    player.getLocation().getBlockY(),
                                    player.getLocation().getBlockZ())) {
                                pb = pbO;
                                break;
                            }
                        }
                    }
                } finally {
                    _pb_mutex.unlock();
                }
                if (pb == null) {
                    plugin.sendMessage(player, ChatColor.RED
                            + tm.getText("not_in_your_parea"));
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!pb.isHidden() && !force) {
                            plugin.sendMessage(player, ChatColor.RED
                                    + tm.getText("pb_is_already_visible"));
                            if (player.hasPermission("pb.unhide.force")) {
                                plugin.sendMessage(player,
                                        tm.getText("use_force_modifier"));
                            }
                        } else {
                            pb.setHidden(false);
                            pb.getLocation().getBlock().setTypeIdAndData(pb.getMaterial().getId(),
                                    pb.getItemStack().getData().getData(), false);
                        }
                    });
                }
            }
        });
    }

    public void showInfo(final Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            ProtectionBlock pb = null;

            @Override
            public void run() {
                _pb_mutex.lock();
                try {
                    for (ProtectionBlock pbO : placedBlocks.values()) {
                        if (pbO.getPlayerUUID().equals(player.getUniqueId())
                                || player.hasPermission("pb.info.others")) {
                            if (pbO.getRegion().contains(player.getLocation().getBlockX(),
                                    player.getLocation().getBlockY(),
                                    player.getLocation().getBlockZ())) {
                                pb = pbO;
                                break;
                            }
                        }
                    }
                } finally {
                    _pb_mutex.unlock();
                }
                if (pb == null) {
                    plugin.sendMessage(player, ChatColor.RED
                            + tm.getText("not_in_your_parea"));
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.sendMessage(player, pb.getInfo()));
                }
            }
        });
    }

    public boolean fenceCanReplace(Material material) {
        return fenceReplaces.contains(material);
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        _pb_mutex.lock();

        try {
            createdBlocks.forEach(block -> config.set("created." + block.getUuid().toString(), block.getConfigurationSection()));
            placedBlocks.values().forEach(block -> config.set("placed." + block.getRegionName(), block.getConfigurationSection()));
        } finally {
            _pb_mutex.unlock();
        }

        try {
            config.save(pbFile);
        } catch (IOException ex) {
            plugin.alert(tm.getText("error_saving", ex.getMessage()));
        }
    }

    public void load() {
        permissions.clear();
        fenceReplaces.clear();

        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("protection-multiple");
        if (cs == null) {
            return;
        }

        for (String key : cs.getKeys(false)) {
            permissions.put("pb.protection.multiple." + key, cs.getInt(key));
        }

        for (String materialName : plugin.getConfig().getStringList("flags.fence.replace-materials")) {
            Material mat = Material.getMaterial(materialName);
            if (mat == null) {
                plugin.alert(tm.getText("fence_flag_invalid_material", materialName));
            } else {
                fenceReplaces.add(mat);
            }
        }

        configurableFlags = plugin.getConfig().getStringList("player.configurable-flags").stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        if (!pbFile.exists()) {
            return;
        }

        YamlConfiguration blocksConfig = YamlConfiguration.loadConfiguration(pbFile);
        _pb_mutex.lock();

        try {
            materialsCache.clear();
            uuidsCache.clear();
            createdBlocks.clear();
            placedBlocks.clear();
            playersBlocks.clear();
            if (blocksConfig.contains("created")) {
                for (String uuidString : blocksConfig.getConfigurationSection("created")
                        .getKeys(false)) {
                    ProtectionBlock pb = new ProtectionBlock(plugin);
                    pb.load(blocksConfig.getConfigurationSection("created." + uuidString));
                    materialsCache.add(pb.getMaterial());
                    uuidsCache.put(pb.getUuid(), pb);
                    createdBlocks.add(pb);
                    if (pb.getPlayerUUID() != null) {
                        Set<ProtectionBlock> playerPbs = playersBlocks.computeIfAbsent(pb.getPlayerUUID(), id -> new TreeSet<>());
                        playerPbs.add(pb);
                        playersBlocks.remove(pb.getPlayerUUID());
                        playersBlocks.put(pb.getPlayerUUID(), playerPbs);
                    }
                }
            }
            if (blocksConfig.contains("placed")) {
                for (String pbLocationString : blocksConfig.getConfigurationSection("placed")
                        .getKeys(false)) {
                    ProtectionBlock pb = new ProtectionBlock(plugin);
                    pb.setRegionId(pbLocationString);
                    pb.load(blocksConfig.getConfigurationSection("placed." + pbLocationString));
                    materialsCache.add(pb.getMaterial());
                    uuidsCache.put(pb.getUuid(), pb);
                    placedBlocks.put(pb.getLocation(), pb);
                    Set<ProtectionBlock> playerPbs = playersBlocks.computeIfAbsent(pb.getPlayerUUID(), id -> new TreeSet<>());
                    playerPbs.add(pb);
                    playersBlocks.remove(pb.getPlayerUUID());
                    playersBlocks.put(pb.getPlayerUUID(), playerPbs);
                }
            }
        } finally {
            _pb_mutex.unlock();
        }
    }

    public Set<String> getConfigurableFlags() {
        return configurableFlags;
    }

    public void setFlag(final Player player, final String flagName, final String value) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            ProtectionBlock pb = null;

            @Override
            public void run() {
                _pb_mutex.lock();
                try {
                    for (ProtectionBlock pbO : placedBlocks.values()) {
                        if (pbO.getPlayerUUID().equals(player.getUniqueId())
                                || player.hasPermission("pb.pb.modifyflags.others")) {
                            if (pbO.getRegion().contains(player.getLocation().getBlockX(),
                                    player.getLocation().getBlockY(),
                                    player.getLocation().getBlockZ())) {
                                pb = pbO;
                                break;
                            }
                        }
                    }
                } finally {
                    _pb_mutex.unlock();
                }
                if (pb == null) {
                    plugin.sendMessage(player, ChatColor.RED + tm.getText("not_in_your_parea"));
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.getWG().setFlag(pb.getRegion(), pb.getWorld(),
                            DefaultFlag.fuzzyMatchFlag(plugin.getWG().getFlagRegistry(), flagName), value));
                }
            }
        });
    }

    public void addPlacedBlock(ProtectionBlock pb) {
        _pb_mutex.lock();
        try {
            materialsCache.add(pb.getMaterial());
            uuidsCache.put(pb.getUuid(), pb);
            placedBlocks.put(pb.getLocation(), pb);

            Set<ProtectionBlock> blocks = playersBlocks.computeIfAbsent(pb.getPlayerUUID(), id -> new TreeSet<>());
            blocks.add(pb);
        } finally {
            _pb_mutex.unlock();
        }
    }

    @SuppressWarnings("deprecation")
    public void removeAllPS(final CommandSender cs, final String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            if (player == null) {
                plugin.sendMessage(cs, ChatColor.RED + tm.getText("never_played", playerName));
            } else {
                if (getBlocksFor(player) != null) {
                    plugin.sendMessage(cs, tm.getText("removing_pbs", playerName));

                    Set<ProtectionBlock> pbs = new TreeSet<>(getBlocksFor(player));
                    if (pbs.isEmpty()) {
                        plugin.sendMessage(cs, ChatColor.RED + tm.getText("has_no_pbs", playerName));
                    } else {
                        pbs.forEach(protectionBlock -> {
                            removeBlock(protectionBlock);
                            if (protectionBlock.isPlaced()) {
                                protectionBlock.removeRegion();
                            }
                        });
                        plugin.sendMessage(cs, tm.getText("player_pbs_deleted", pbs.size() + "", playerName));
                    }
                }
            }
        });

    }

    public Set<ProtectionBlock> getBlocksFor(OfflinePlayer player) {
        return playersBlocks.get(player.getUniqueId());
    }

    private class LocationComparator implements Comparator<Location> {
        @Override
        public int compare(Location o1, Location o2) {
            int resp;
            resp = o1.getWorld().getUID().compareTo(o2.getWorld().getUID());
            if (resp == 0) {
                resp = o1.getBlockX() - o2.getBlockX();
                if (resp == 0) {
                    resp = o1.getBlockY() - o2.getBlockY();
                    if (resp == 0) {
                        resp = o1.getBlockZ() - o2.getBlockZ();
                    }
                }
            }
            return resp;
        }
    }
}
