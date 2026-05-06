package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;

public class JobItemListener implements Listener {

    private final ClassManager classManager;

    public JobItemListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (classManager.isJobItem(item)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(classManager.getMessage("job.cannot_drop", "&c직업 전용 아이템은 버릴 수 없습니다!"));
            // 클라이언트와 서버 간의 아이템 개수/위치 동기화를 강제 수행
            player.updateInventory();
        }
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        // 사망 시 드랍 리스트에서 직업 아이템을 제거하여 복제 방지
        java.util.Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (classManager.isJobItem(item)) {
                iterator.remove();
            }
        }
    }

    @EventHandler
    public void onCraft(org.bukkit.event.inventory.PrepareItemCraftEvent event) {
        // 조합 재료로 직업 아이템 사용 불가
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (classManager.isJobItem(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        ItemStack item = event.getItem().getItemStack();
        
        if (classManager.isJobItem(item)) {
            if (item.hasItemMeta()) {
                String ownerUuid = item.getItemMeta().getPersistentDataContainer()
                        .get(classManager.getOwnerItemKey(), PersistentDataType.STRING);
                
                if (ownerUuid != null && !ownerUuid.equals(player.getUniqueId().toString())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (classManager.isJobItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        
        // 클릭한 아이템이나 커서에 있는 아이템이 직업 아이템인 경우
        if (classManager.isJobItem(item) || classManager.isJobItem(cursor)) {
            // 상단 인벤토리가 플레이어 인벤토리가 아닐 때 (상자 등)
            if (event.getInventory().getType() != InventoryType.PLAYER) {
                if (event.getRawSlot() < event.getInventory().getSize() || event.isShiftClick()) {
                    event.setCancelled(true);
                    event.getWhoClicked().sendMessage(ChatColor.RED + "직업 전용 아이템은 다른 보관함에 넣을 수 없습니다!");
                    if (event.getWhoClicked() instanceof Player) ((Player) event.getWhoClicked()).updateInventory();
                }
            }
            
            // 인벤토리 밖을 클릭하여 버리려는 시도 차단
            if (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player) ((Player) event.getWhoClicked()).updateInventory();
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (classManager.isJobItem(event.getOldCursor())) {
            if (event.getInventory().getType() != InventoryType.PLAYER) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player) ((Player) event.getWhoClicked()).updateInventory();
            }
        }
    }

    @EventHandler
    public void onHopperMove(org.bukkit.event.inventory.InventoryMoveItemEvent event) {
        if (classManager.isJobItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        if (classManager.isJobItem(event.getItem())) {
            event.setCancelled(true);
            event.getEnchanter().sendMessage(ChatColor.RED + "직업 전용 아이템은 인챈트할 수 없습니다!");
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getItem(0);
        if (classManager.isJobItem(first)) {
            event.setResult(null); // 모루 결과물 제거
        }
    }
}
