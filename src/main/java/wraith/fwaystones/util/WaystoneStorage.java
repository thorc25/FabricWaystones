package wraith.fwaystones.util;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import wraith.fwaystones.FabricWaystones;
import wraith.fwaystones.access.PlayerEntityMixinAccess;
import wraith.fwaystones.access.WaystoneValue;
import wraith.fwaystones.block.WaystoneBlock;
import wraith.fwaystones.block.WaystoneBlockEntity;
import wraith.fwaystones.integration.event.WaystoneEvents;
import wraith.fwaystones.packets.client.WaystonePacket;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WaystoneStorage {

    private static final String ID = "fwaystones";

    private final ConcurrentHashMap<String, WaystoneValue> WAYSTONES = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final PersistentState state;

    public WaystoneStorage(MinecraftServer server) {
        this.server = server;
        if (server == null) {
            this.state = null;
            return;
        }

        this.state = this.server
                .getOverworld()
                .getPersistentStateManager()
                .getOrCreate(
                        this::createState,
                        this::stateFromNbt,
                        ID
                );

        loadWaystones();
    }

    private PersistentState createState() {
        return new PersistentState() {
            @Override
            public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
                return toTag(nbt);
            }
        };
    }

    private PersistentState stateFromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        PersistentState state = this.createState();
        fromTag(nbt);
        return state;
    }

    public void fromTag(NbtCompound tag) {
        if (tag == null || !tag.contains(FabricWaystones.MOD_ID)) {
            return;
        }
        WAYSTONES.clear();

        var globals = new HashSet<String>();
        for (var element : tag.getList("global_waystones", NbtElement.STRING_TYPE)) {
            globals.add(element.asString());
        }

        var waystones = tag.getList(FabricWaystones.MOD_ID, NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < waystones.size(); ++i) {
            NbtCompound waystoneTag = waystones.getCompound(i);
            if (!waystoneTag.contains("hash") || !waystoneTag.contains("name")
                || !waystoneTag.contains("dimension") || !waystoneTag.contains("position")) {
                continue;
            }
            String name = waystoneTag.getString("name");
            String dimension = waystoneTag.getString("dimension");
            String nbtHash = waystoneTag.getString("hash");

            int[] coordinates = waystoneTag.getIntArray("position");
            int color = waystoneTag.contains("color", NbtElement.INT_TYPE) ? waystoneTag.getInt("color") : Utils.getRandomColor();
            BlockPos pos = new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
            String hash = WaystoneBlockEntity.createHashString(dimension, pos);

            // Migrate global hashes from old hash method to new.
            if (!hash.equals(nbtHash) && globals.contains(nbtHash)) {
                globals.remove(nbtHash);
                globals.add(hash);
            }

            WAYSTONES.put(hash, new Lazy(name, pos, hash, dimension, color, globals.contains(hash)));
        }
    }

    public NbtCompound toTag(NbtCompound tag) {
        if (tag == null) {
            tag = new NbtCompound();
        }
        NbtList waystones = new NbtList();
        for (Map.Entry<String, WaystoneValue> waystone : WAYSTONES.entrySet()) {
            String hash = waystone.getKey();
            WaystoneValue entity = waystone.getValue();
            if (!entity.getHash().equals(hash)) {
                continue;
            }
            NbtCompound waystoneTag = new NbtCompound();
            waystoneTag.putString("hash", hash);
            waystoneTag.putString("name", entity.getWaystoneName());
            waystoneTag.putInt("color", entity.getColor());
            BlockPos pos = entity.way_getPos();
            waystoneTag.putIntArray("position", Arrays.asList(pos.getX(), pos.getY(), pos.getZ()));
            waystoneTag.putString("dimension", entity.getWorldName());

            waystones.add(waystoneTag);
        }
        tag.put(FabricWaystones.MOD_ID, waystones);
        NbtList globals = new NbtList();
        var globalWaystones = getGlobals();
        for (String globalWaystone : globalWaystones) {
            globals.add(NbtString.of(globalWaystone));
        }
        tag.put("global_waystones", globals);
        return tag;
    }

    public void tryAddWaystone(WaystoneBlockEntity waystone) {
        if (waystone == null) {
            return;
        }
        if (WAYSTONES.containsValue(waystone)) {
            return;
        }
        boolean alreadyExists = WAYSTONES.containsKey(waystone.getHash());
        WAYSTONES.put(waystone.getHash(), waystone);
        saveWaystones(!alreadyExists);
    }

    public void tryAddWaystoneFromItemstack(WaystoneBlockEntity waystone, ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            tryAddWaystone(waystone);
            return;
        }
        NbtComponent tag = itemStack.get(DataComponentTypes.CUSTOM_DATA);
        if (tag != null) {
            NbtCompound nbt = tag.getNbt();
            if (nbt != null) {
                if (nbt.contains("waystone_name")) {
                    waystone.setName(nbt.getString("waystone_name"));
                }
                if (nbt.contains("waystone_is_global")) {
                    waystone.setGlobal(nbt.getBoolean("waystone_is_global"));
                }
                if (nbt.contains("waystone_color")) {
                    waystone.setColor(nbt.getInt("waystone_color"));
                }
                if(nbt.contains("Items")) {
                    DefaultedList<ItemStack> inventory = waystone.getInventory();
                    Inventories.readNbt(nbt, inventory, waystone.getWorld().getRegistryManager());
                    waystone.setInventory(inventory);
                }
            }
        }

        tryAddWaystone(waystone);
    }

    public void addWaystones(HashSet<WaystoneBlockEntity> waystones) {
        var added = false;
        for (WaystoneBlockEntity waystone : waystones) {
            if (waystone != null) {
                added = true;
                tryAddWaystone(waystone);
            }
        }
        if (added) {
            saveWaystones(true);
        }
    }

    public void saveWaystones(boolean sync) {
        if (server == null) {
            return;
        }
        ServerWorld world = server.getWorld(ServerWorld.OVERWORLD);

        state.markDirty();
        if (sync) {
            sendToAllPlayers();
        }
        world.getPersistentStateManager().save();
    }

    public void loadWaystones() {
        if (server == null) {
            return;
        }
        ServerWorld world = server.getWorld(ServerWorld.OVERWORLD);
        try {
            NbtCompound compoundTag = world.getPersistentStateManager().readNbt(ID, DataFixTypes.LEVEL, SharedConstants.getGameVersion().getProtocolVersion());
            state.writeNbt(compoundTag.getCompound("data"), world.getRegistryManager());
        } catch (IOException ignored) {}
        world.getPersistentStateManager().save();
    }

    public void sendToAllPlayers() {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sendToPlayer(player);
        }
    }

    public void sendToPlayer(ServerPlayerEntity player) {
        WaystonePacket packet = new WaystonePacket(toTag(null));
        ServerPlayNetworking.send(player, packet);
    }

    public void removeWaystone(String hash) {
        WaystoneEvents.REMOVE_WAYSTONE_EVENT.invoker().onRemove(hash);
        WAYSTONES.remove(hash);
        saveWaystones(false);
        forgetForAllPlayers(hash);
    }

    public void removeWorldWaystones(String dimension) {
        if (server == null) {
            return;
        }
        WAYSTONES.forEach((hash, waystone) -> {
            if (waystone.getWorldName().equals(dimension)) {
                var entity = waystone.getEntity();
                if (entity != null) {
                    entity.setOwner(null);
                }
                removeWaystone(hash);
            }
        });
    }

    public void forgetForAllPlayers(String hash) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ((PlayerEntityMixinAccess) player).fabricWaystones$forgetWaystone(hash);
        }
    }

    public void removeWaystone(WaystoneBlockEntity waystone) {
        removeWaystone(waystone.getHash());
    }

    public boolean removeIfInvalid(String hash) {
        if (WAYSTONES.containsKey(hash) && getWaystoneEntity(hash) == null) {
            removeWaystone(hash);
            return true;
        }
        return false;
    }

    public void renameWaystone(String hash, String name) {
        if (WAYSTONES.containsKey(hash)) {
            WaystoneValue waystone = WAYSTONES.get(hash);
            waystone.getEntity().setName(name);
            WaystoneEvents.RENAME_WAYSTONE_EVENT.invoker().onUpdate(hash);
            saveWaystones(true);
        }
    }

    public void recolorWaystone(String hash, int color) {
        if (WAYSTONES.containsKey(hash)) {
            WAYSTONES.get(hash).setColor(color);
            saveWaystones(true);
        }
    }

    @Nullable
    public WaystoneBlockEntity getWaystoneEntity(String hash) {
        WaystoneValue value = getWaystoneData(hash);
        return value != null ? value.getEntity() : null;
    }

    @Nullable
    public WaystoneValue getWaystoneData(String hash) {
        return WAYSTONES.getOrDefault(hash, null);
    }

    public boolean containsHash(String hash) {
        return WAYSTONES.containsKey(hash);
    }

    public List<String> getGlobals() {
        return WAYSTONES.entrySet().stream().filter(entry -> entry.getValue().isGlobal())
            .map(Map.Entry::getKey).toList();
    }

    public boolean isGlobal(String hash) {
        return WAYSTONES.containsKey(hash) && WAYSTONES.get(hash).isGlobal();
    }

    public void toggleGlobal(String hash) {
        WaystoneBlockEntity waystone = getWaystoneEntity(hash);
        if (waystone == null) {
            return;
        }
        waystone.toggleGlobal();
        sendToAllPlayers();
    }

    public void setOwner(String hash, PlayerEntity owner) {
        if (WAYSTONES.containsKey(hash)) {
            WAYSTONES.get(hash).getEntity().setOwner(owner);
        }
    }

    public HashSet<String> getAllHashes() {
        return new HashSet<>(WAYSTONES.keySet());
    }

    public int getCount() {
        return WAYSTONES.size();
    }

    @Nullable
    public String getName(String hash) {
        WaystoneValue value = getWaystoneData(hash);
        return value != null ? value.getWaystoneName() : null;
    }

    final class Lazy implements WaystoneValue {

        /**
         * unresolved name
         */
        final String name;
        final BlockPos pos;
        final String hash;
        final String dimension;
        final boolean isGlobal;
        int color;
        WaystoneBlockEntity entity;
        World world;

        Lazy(String name, BlockPos pos, String hash, String dimension, int color, boolean global) {
            this.name = name;
            this.pos = pos;
            this.hash = hash;
            this.dimension = dimension;
            this.color = color;
            this.isGlobal = global;
        }

        @Override
        public WaystoneBlockEntity getEntity() {
            if (server == null) {
                return null;
            }
            if (this.entity == null) {
                for (ServerWorld world : server.getWorlds()) {
                    if (Utils.getDimensionName(world).equals(dimension)) {
                        WaystoneBlockEntity entity = WaystoneBlock.getEntity(world, pos);
                        if (entity != null) {
                            tryAddWaystone(entity); // should allow this instance to be GCed
                            this.entity = entity;
                            this.world = world;
                        }
                        break;
                    }
                }
            }
            return this.entity;
        }

        @Override
        public String getWaystoneName() {
            return name;
        }

        @Override
        public BlockPos way_getPos() {
            return pos;
        }

        @Override
        public String getWorldName() {
            return this.dimension;
        }

        @Override
        public String getHash() {
            return this.hash;
        }

        @Override
        public int getColor() {
            return this.color;
        }

        @Override
        public void setColor(int color) {
            this.color = color;
        }

        @Override
        public boolean isGlobal() {
            return this.isGlobal;
        }
    }
}
