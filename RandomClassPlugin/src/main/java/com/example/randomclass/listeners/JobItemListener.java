package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.block.Action;
import org.bukkit.block.Block;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.Material;

public class JobItemListener implements Listener {

    private final ClassManager classManager;
    private final java.util.Map<java.util.UUID, java.util.List<ItemStack>> keptItems = new java.util.HashMap<>();

    public JobItemListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;

        ItemStack item = event.getItem();
        if (item == null)
            return;

        // 인벤토리 세이브권(END_CRYSTAL), 각성권(ENDER_EYE), 직업 전용 아이템(BELL 등) 설치 방지
        Material type = item.getType();
        boolean isTicket = (type == Material.END_CRYSTAL && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(classManager.getInventorySaveTicketKey(), PersistentDataType.BYTE))
                || (type == Material.ENDER_EYE && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(classManager.getAwakeningTicketKey(), PersistentDataType.BYTE));
        boolean isJobItem = classManager.isJobItem(item);

        if (isTicket || (isJobItem && (type == Material.BELL || type == Material.ENDER_EYE))) {
            Block clicked = event.getClickedBlock();
            if (clicked != null) {
                event.setCancelled(true);
                if (isTicket) {
                    event.getPlayer().sendMessage(ChatColor.RED + item.getItemMeta().getDisplayName() + ChatColor.RED + "은(는) 설치할 수 없습니다!");
                }
            }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (classManager.isJobItem(item)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            player.sendMessage(classManager.getMessage("job.cannot_drop", "&c직업 전용 아이템은 버릴 수 없습니다!"));

            // 삭제 로직: 드랍된 아이템의 원본(슬롯)에 다시 돌아갈 때, 언스택 아이템이 여러 개 생기는 복제 버그 방지
            org.bukkit.Bukkit.getScheduler().runTaskLater(
                    org.bukkit.plugin.java.JavaPlugin.getPlugin(com.example.randomclass.RandomClassPlugin.class),
                    () -> {
                        if (player.isOnline()) {
                            classManager.removeDuplicateJobItems(player);
                            player.updateInventory();
                        }
                    },
                    1L);
        }
    }

    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        java.util.List<ItemStack> saved = new java.util.ArrayList<>();

        // 1. 인벤토리 세이브권 보유 여부 확인
        ItemStack ticketItem = null;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.hasItemMeta() &&
                    item.getItemMeta().getPersistentDataContainer().has(classManager.getInventorySaveTicketKey(),
                            PersistentDataType.BYTE)) {
                ticketItem = item;
                break;
            }
        }

        if (ticketItem != null) {
            // 인벤토리 세이브권 소모 및 모든 아이템/경험치 보존
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);

            // 티켓 1개 소모 (setKeepInventory가 true이므로 인벤토리에서 직접 수량 조절 가능)
            if (ticketItem.getAmount() > 1) {
                ticketItem.setAmount(ticketItem.getAmount() - 1);
            } else {
                // 특정 아이템만 제거하기 위해 인벤토리에서 null 처리 (remove는 타입 기준이라 위험할 수 있음)
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    if (player.getInventory().getItem(i) != null
                            && player.getInventory().getItem(i).equals(ticketItem)) {
                        player.getInventory().setItem(i, null);
                        break;
                    }
                }
            }

            player.sendMessage(ChatColor.GREEN + "[시스템] 인벤토리 세이브권을 사용하여 아이템과 경험치를 보호했습니다!");
            return;
        }

        // 2. 세이브권이 없는 경우: 기존 직업 아이템만 보존
        java.util.Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (classManager.isJobItem(item)) {
                saved.add(item.clone());
                iterator.remove();
            }
        }

        if (!saved.isEmpty()) {
            keptItems.put(player.getUniqueId(), saved);
        }
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        java.util.List<ItemStack> saved = keptItems.remove(player.getUniqueId());
        if (saved != null) {
            for (ItemStack item : saved) {
                player.getInventory().addItem(item);
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
        if (!(event.getEntity() instanceof Player))
            return;
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
                    if (event.getWhoClicked() instanceof Player)
                        ((Player) event.getWhoClicked()).updateInventory();
                }
            }

            // 인벤토리 밖을 클릭하여 버리려는 시도 차단
            if (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player)
                    ((Player) event.getWhoClicked()).updateInventory();
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (classManager.isJobItem(event.getOldCursor())) {
            if (event.getInventory().getType() != InventoryType.PLAYER) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player)
                    ((Player) event.getWhoClicked()).updateInventory();
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

    @EventHandler
    public void onShootBow(org.bukkit.event.entity.EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();

            // 1. getConsumable 검사 (최신 API)
            ItemStack consumable = event.getConsumable();
            if (consumable != null && isRestrictedArrow(consumable)) {
                cancelBowShoot(event, player, consumable);
                return;
            }

            // 2. 인벤토리 직접 검사 (우선순위: 왼손 -> 인벤토리 0~35)
            ItemStack realArrow = null;
            if (player.getInventory().getItemInOffHand().getType() == Material.ARROW) {
                realArrow = player.getInventory().getItemInOffHand();
            } else {
                for (int i = 0; i < 36; i++) {
                    ItemStack item = player.getInventory().getItem(i);
                    if (item != null && item.getType() == Material.ARROW) {
                        realArrow = item;
                        break;
                    }
                }
            }

            if (realArrow != null && isRestrictedArrow(realArrow)) {
                cancelBowShoot(event, player, realArrow);
            }
        }
    }

    private boolean isRestrictedArrow(ItemStack item) {
        boolean isJob = classManager.isJobItem(item);
        boolean hasName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                && item.getItemMeta().getDisplayName().contains("전사의 화살");
        return isJob || hasName;
    }

    private void cancelBowShoot(org.bukkit.event.entity.EntityShootBowEvent event, Player player,
            ItemStack arrowTarget) {
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "직업 전용 화살은 일반 활이나 쇠뇌로 발사할 수 없습니다!");

        final ItemStack refundArrow = (arrowTarget != null) ? arrowTarget.clone() : null;

        // 이벤트 취소 후에도 화살이 증발하는 현상을 막기 위해 1틱 뒤에 다시 지급 후 중복 제거 로직 실행
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                org.bukkit.plugin.java.JavaPlugin.getPlugin(com.example.randomclass.RandomClassPlugin.class),
                () -> {
                    if (player.isOnline() && refundArrow != null) {
                        refundArrow.setAmount(1);
                        player.getInventory().addItem(refundArrow);
                        classManager.removeDuplicateJobItems(player);
                        player.updateInventory();
                    }
                },
                1L);
    }
}
