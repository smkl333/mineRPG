package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

public class LevelUpTicketListener implements Listener {

    private final ClassManager classManager;
    private final String GUI_TITLE = ChatColor.DARK_PURPLE + "직업 레벨 상승권 사용";

    public LevelUpTicketListener(ClassManager classManager) {
        this.classManager = classManager;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            ItemStack item = player.getInventory().getItemInMainHand();

            if (item != null && item.hasItemMeta()) {
                if (item.getItemMeta().getPersistentDataContainer().has(classManager.getLevelUpTicketKey(), PersistentDataType.BYTE)) {
                    event.setCancelled(true);
                    openLevelUpGui(player);
                }
            }
        }
    }

    private void openLevelUpGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);

        ItemStack bg = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bgMeta = bg.getItemMeta();
        bgMeta.setDisplayName(" ");
        bg.setItemMeta(bgMeta);
        for (int i = 0; i < 27; i++) inv.setItem(i, bg);

        inv.setItem(10, createJobIcon(PlayerClass.WARRIOR));
        inv.setItem(13, createJobIcon(PlayerClass.MAGE));
        inv.setItem(16, createJobIcon(PlayerClass.ADVENTURER));

        player.openInventory(inv);
    }

    private ItemStack createJobIcon(PlayerClass pClass) {
        Material mat = Material.BARRIER;
        switch (pClass) {
            case WARRIOR: mat = Material.IRON_SWORD; break;
            case MAGE: mat = Material.BLAZE_ROD; break;
            case ADVENTURER: mat = Material.COMPASS; break;
            default: break;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + pClass.getDisplayName() + " 레벨업");
        meta.setLore(Arrays.asList(
                ChatColor.GRAY + "클릭 시 레벨 상승권을 소모하여",
                ChatColor.GRAY + "이 직업의 레벨을 1 올립니다."
        ));
        meta.getPersistentDataContainer().set(classManager.getGuiTargetClassKey(), PersistentDataType.STRING, pClass.name());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals(GUI_TITLE)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        ItemStack clickedItem = event.getCurrentItem();

        if (clickedItem != null && clickedItem.hasItemMeta()) {
            String className = clickedItem.getItemMeta().getPersistentDataContainer().get(classManager.getGuiTargetClassKey(), PersistentDataType.STRING);
            if (className != null) {
                try {
                    PlayerClass targetClass = PlayerClass.valueOf(className);
                    processLevelUp(player, targetClass);
                } catch (Exception ignored) {}
            }
        }
    }

    private void processLevelUp(Player player, PlayerClass targetClass) {
        int currentLevel = classManager.getStoredLevel(player, targetClass);
        if (currentLevel >= 10) {
            player.sendMessage(classManager.getMessage("job.max_level", "&c{job} 직업은 이미 최대 레벨(10)입니다.")
                    .replace("{job}", targetClass.getDisplayName()));
            player.closeInventory();
            return;
        }

        // 인벤토리 전체에서 상승권 1개 탐색 후 제거
        boolean consumed = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.hasItemMeta()
                    && stack.getItemMeta().getPersistentDataContainer().has(classManager.getLevelUpTicketKey(), PersistentDataType.BYTE)) {
                stack.setAmount(stack.getAmount() - 1);
                consumed = true;
                break;
            }
        }
        if (!consumed) {
            player.sendMessage(classManager.getMessage("job.no_ticket", "&c직업 레벨 상승권이 인벤토리에 없습니다."));
            player.closeInventory();
            return;
        }

        int nextLevel = currentLevel + 1;
        classManager.setStoredLevel(player, targetClass, nextLevel);
        classManager.playLevelUpEffects(player, nextLevel);
        
        // 현재 적용 중인 직업이라면 버프 즉시 갱신
        if (classManager.getPlayerClass(player) == targetClass) {
            classManager.applyClassBuffs(player);
        }

        player.sendMessage(classManager.getMessage("job.ticket_use_success", "&d[직업 레벨 상승권] &a{job} 직업이 Lv.{level}(으)로 올랐습니다!")
                .replace("{job}", targetClass.getDisplayName())
                .replace("{level}", String.valueOf(nextLevel)));
        player.closeInventory();
    }
}
