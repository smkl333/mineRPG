package com.example.randomclass.listeners;

import com.example.randomclass.ClassManager;
import com.example.randomclass.PlayerClass;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.enchantments.Enchantment;

import java.util.Random;

public class BlockBreakListener implements Listener {

    private final ClassManager classManager;
    private final Plugin plugin;
    private final Random random = new Random();
    private final java.util.Set<java.util.UUID> miningPlayers = new java.util.HashSet<>();

    public BlockBreakListener(ClassManager classManager, Plugin plugin) {
        this.classManager = classManager;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // 플레이어가 직접 설치한 블록은 메타데이터로 기록 (어뷰징 방지)
        event.getBlock().setMetadata("player_placed", new FixedMetadataValue(plugin, true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();

        // [0] 공통 각성 패시브 (2x2 채굴: 위, 오른쪽 대각선위, 오른쪽)
        if (tool != null && tool.getType().name().endsWith("_PICKAXE") && classManager.isJobItem(tool)) {
            if (classManager.hasCommonAwakening(player, "mining_2x2") && !miningPlayers.contains(player.getUniqueId())) {
                Block center = event.getBlock();
                java.util.List<Block> extraBlocks = get2x2Blocks(center, player);
                
                miningPlayers.add(player.getUniqueId());
                try {
                    for (Block b : extraBlocks) {
                        if (b.getType() == Material.AIR || b.getType() == Material.BEDROCK || b.getType() == Material.BARRIER || b.getType() == Material.END_PORTAL_FRAME) continue;
                        
                        BlockBreakEvent bEvent = new BlockBreakEvent(b, player);
                        org.bukkit.Bukkit.getPluginManager().callEvent(bEvent);
                        if (!bEvent.isCancelled()) {
                            // 커스텀 블록 파괴 로직
                            b.getWorld().playEffect(b.getLocation(), org.bukkit.Effect.STEP_SOUND, b.getType());
                            b.breakNaturally(tool);
                        }
                    }
                } finally {
                    miningPlayers.remove(player.getUniqueId());
                }
            }
        }

        PlayerClass pClass = classManager.getPlayerClass(player);
        if (pClass != PlayerClass.ADVENTURER) return;

        int fortuneLevel = 0;
        if (tool != null && tool.hasItemMeta() && tool.getItemMeta().hasEnchant(Enchantment.FORTUNE)) {
            fortuneLevel = tool.getItemMeta().getEnchantLevel(Enchantment.FORTUNE);
        }

        Block block = event.getBlock();
        Material type = block.getType();

        // [1] 농작물 커스텀 행운 적용 (밀, 비트루트)
        if (type == Material.WHEAT || type == Material.BEETROOTS) {
            if (block.getBlockData() instanceof Ageable) {
                Ageable ageable = (Ageable) block.getBlockData();
                if (ageable.getAge() == ageable.getMaximumAge()) {
                    Material dropMat = (type == Material.WHEAT) ? Material.WHEAT : Material.BEETROOT;
                    int extraDrop = getCropExtraDrop(fortuneLevel);
                    if (extraDrop > 0) {
                        block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(dropMat, extraDrop));
                    }
                    // 다 자란 농작물 수확 시 경험치 드랍 (구슬 형태)
                    int minExp = classManager.getAdventurerHarvestExpMin();
                    int maxExp = classManager.getAdventurerHarvestExpMax();
                    int expDrop = minExp + random.nextInt(Math.max(1, maxExp - minExp + 1));
                    if (expDrop > 0) {
                        block.getWorld().spawn(block.getLocation(), org.bukkit.entity.ExperienceOrb.class, orb -> {
                            orb.setExperience(expDrop);
                        });
                    }
                }
            }
            return;
        }

        // [1-1] 사탕수수 파괴 시 경험치 지급 (위로 연결된 모든 사탕수수 소급 지급)
        if (type == Material.SUGAR_CANE) {
            int count = 0;
            Block current = block;
            
            // 현재 부서진 블록 체크
            if (!current.hasMetadata("player_placed")) {
                count++;
            }
            
            // 위로 연결된 사탕수수들 체크 (아직 부서지기 전 상태이므로 확인 가능)
            while (current.getRelative(org.bukkit.block.BlockFace.UP).getType() == Material.SUGAR_CANE) {
                current = current.getRelative(org.bukkit.block.BlockFace.UP);
                if (!current.hasMetadata("player_placed")) {
                    count++;
                }
            }
            
            if (count > 0) {
                int minExp = classManager.getAdventurerHarvestExpMin();
                int maxExp = classManager.getAdventurerHarvestExpMax();
                for (int i = 0; i < count; i++) {
                    int expDrop = minExp + random.nextInt(Math.max(1, maxExp - minExp + 1));
                    if (expDrop > 0) {
                        block.getWorld().spawn(block.getLocation(), org.bukkit.entity.ExperienceOrb.class, orb -> {
                            orb.setExperience(expDrop);
                        });
                    }
                }
            }
            return;
        }

        // [2] 나무 커스텀 행운 적용 (원목 -> 판자 추가 드랍)
        String typeName = type.name();
        if (typeName.endsWith("_LOG") || typeName.endsWith("_STEM") || typeName.endsWith("_WOOD") || typeName.endsWith("_HYPHAE")) {
            // 설치된 블록이면 보너스 판자 제외
            if (block.hasMetadata("player_placed")) return;

            Material plankMat = getPlankMaterial(typeName);
            if (plankMat != null) {
                int extraPlanks = getWoodExtraDrop(fortuneLevel);
                if (extraPlanks > 0) {
                    block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(plankMat, extraPlanks));
                }
            }
        }
    }

    private int getCropExtraDrop(int fortuneLevel) {
        // 행운 1: 30%로 1개 추가
        // 행운 2: 60%로 1개 추가
        // 행운 3: 100%로 1개 추가, 30%로 2개 추가
        double chance = random.nextDouble();
        if (fortuneLevel >= 3) {
            return (chance < 0.3) ? 2 : 1;
        } else if (fortuneLevel == 2) {
            return (chance < 0.6) ? 1 : 0;
        } else {
            return (chance < 0.3) ? 1 : 0;
        }
    }

    private int getWoodExtraDrop(int fortuneLevel) {
        // 행운 1: 50% 확률로 1개
        // 행운 2: 100% 확률로 1개, 25% 확률로 2개
        // 행운 3: 100% 확률로 1개, 75% 확률로 2개
        double r = Math.random();
        if (fortuneLevel >= 3) {
        return r < 0.75 ? 2 : 1;   
        }
        if (fortuneLevel == 2) {
            if (r < 0.25) return 2;  
        return 1;                
        }
        return random.nextBoolean() ? 1 : 0;
    }

    private Material getPlankMaterial(String logName) {
        String prefix;
        if (logName.endsWith("_LOG")) {
            prefix = logName.replace("_LOG", "");
        } else if (logName.endsWith("_STEM")) {
            prefix = logName.replace("_STEM", "");
        } else if (logName.endsWith("_WOOD")) {
            prefix = logName.replace("_WOOD", "");
        } else if (logName.endsWith("_HYPHAE")) {
            prefix = logName.replace("_HYPHAE", "");
        } else {
            return null;
        }

        // STRIPPED_OAK -> OAK
        if (prefix.startsWith("STRIPPED_")) {
            prefix = prefix.replace("STRIPPED_", "");
        }

        try {
            return Material.valueOf(prefix + "_PLANKS");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private java.util.List<Block> get2x2Blocks(Block center, Player player) {
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        org.bukkit.util.RayTraceResult rayTrace = player.rayTraceBlocks(6.0, org.bukkit.FluidCollisionMode.NEVER);
        org.bukkit.block.BlockFace face = org.bukkit.block.BlockFace.UP; // 기본값
        
        if (rayTrace != null && rayTrace.getHitBlockFace() != null) {
            face = rayTrace.getHitBlockFace();
        }

        int dxUp = 0, dyUp = 0, dzUp = 0;
        int dxRight = 0, dyRight = 0, dzRight = 0;

        if (face == org.bukkit.block.BlockFace.UP || face == org.bukkit.block.BlockFace.DOWN) {
            // 바닥이나 천장을 캘 때: 플레이어의 시선(Yaw) 기준
            float yaw = player.getLocation().getYaw();
            yaw = (yaw % 360 + 360) % 360;
            
            if (yaw >= 45 && yaw < 135) { // 서쪽을 볼 때
                dxUp = -1; dzUp = 0;
                dxRight = 0; dzRight = -1; // 오른쪽은 북쪽
            } else if (yaw >= 135 && yaw < 225) { // 북쪽을 볼 때
                dxUp = 0; dzUp = -1;
                dxRight = 1; dzRight = 0; // 오른쪽은 동쪽
            } else if (yaw >= 225 && yaw < 315) { // 동쪽을 볼 때
                dxUp = 1; dzUp = 0;
                dxRight = 0; dzRight = 1; // 오른쪽은 남쪽
            } else { // 남쪽을 볼 때
                dxUp = 0; dzUp = 1;
                dxRight = -1; dzRight = 0; // 오른쪽은 서쪽
            }
        } else {
            // 벽면을 캘 때: 위쪽은 항상 Y+1
            dyUp = 1;
            switch (face) {
                case NORTH: // 블록의 북쪽 면 (플레이어는 남쪽을 봄)
                    dxRight = -1; // 서쪽이 오른쪽
                    break;
                case SOUTH: // 블록의 남쪽 면 (플레이어는 북쪽을 봄)
                    dxRight = 1; // 동쪽이 오른쪽
                    break;
                case EAST: // 블록의 동쪽 면 (플레이어는 서쪽을 봄)
                    dzRight = -1; // 북쪽이 오른쪽
                    break;
                case WEST: // 블록의 서쪽 면 (플레이어는 동쪽을 봄)
                    dzRight = 1; // 남쪽이 오른쪽
                    break;
                default:
                    dxRight = 1;
                    break;
            }
        }

        blocks.add(center.getRelative(dxUp, dyUp, dzUp)); // 위쪽 블록
        blocks.add(center.getRelative(dxRight, dyRight, dzRight)); // 오른쪽 블록
        blocks.add(center.getRelative(dxUp + dxRight, dyUp + dyRight, dzUp + dzRight)); // 대각선 위쪽 블록

        return blocks;
    }
}
