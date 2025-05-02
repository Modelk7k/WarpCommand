package net.friendlyfire.warpcommand.command;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class WarpsCommand {
    private static final Map<ResourceKey<Level>, Map<String, BlockPos>> warpPointsByWorld = new HashMap<>();
    private static File getWarpFile(MinecraftServer server, ResourceKey<Level> worldName) {
        ServerLevel world = server.getLevel(worldName);
        if (world == null) return null;
        File worldDir = world.getServer().getWorldPath(LevelResource.ROOT).toFile();
        File cultivatorModDir = new File(worldDir, "warp_mod");
        if (!cultivatorModDir.exists()) cultivatorModDir.mkdirs();
        return new File(cultivatorModDir, "warps.json");
    }
    public WarpsCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("warp")
                        .then(Commands.argument("destination", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    MinecraftServer server = context.getSource().getServer();
                                    if (player != null && server != null) {
                                        ResourceKey<Level> worldName = player.level().dimension();
                                        Map<String, BlockPos> worldWarps = warpPointsByWorld.getOrDefault(worldName, new HashMap<>());
                                        Set<String> normalizedSuggestions = new HashSet<>();

                                        for (String warp : worldWarps.keySet()) {
                                            String normalized = warp.replace("_", "").toLowerCase();
                                            if (normalizedSuggestions.add(normalized)) {
                                                builder.suggest(warp);
                                            }
                                        }
                                        Map<String, String> defaultDims = Map.of(
                                                "minecraft:overworld", "overworld",
                                                "minecraft:the_nether", "nether",
                                                "minecraft:the_end", "end"
                                        );
                                        for (ServerLevel level : server.getAllLevels()) {
                                            String id = level.dimension().location().toString();
                                            String simpleName = defaultDims.getOrDefault(id, id.split(":")[1]);
                                            String normalized = simpleName.replace("_", "").toLowerCase();
                                            if (normalizedSuggestions.add(normalized)) {
                                                builder.suggest(simpleName);
                                            }
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(this::execute))
                        .executes(this::listWarps)
        );
        dispatcher.register(
                Commands.literal("setwarp")
                        .requires(source -> {
                            ServerPlayer player = source.getPlayer();
                            return player != null && player.getTags().contains("staff");
                        })
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(this::setWarp))
        );
        dispatcher.register(
                Commands.literal("removewarp")
                        .requires(source -> {
                            ServerPlayer player = source.getPlayer();
                            return player != null && player.getTags().contains("staff");
                        })
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    ServerPlayer player = context.getSource().getPlayer();
                                    if (player != null) {
                                        ResourceKey<Level> worldName = player.level().dimension();
                                        Map<String, BlockPos> worldWarps = warpPointsByWorld.getOrDefault(worldName, new HashMap<>());
                                        for (String warp : worldWarps.keySet()) {
                                            builder.suggest(warp);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(this::removeWarp)
                        )
        );
    }
    private int execute(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player not found."));
            return 0;
        }
        String destination = StringArgumentType.getString(context, "destination").toLowerCase();
        ResourceKey<Level> worldName = player.level().dimension();
        BlockPos warpPos = warpPointsByWorld.getOrDefault(worldName, new HashMap<>()).get(destination);
        if (warpPos != null) {
            teleportToWarpPoint(player, warpPos);
            context.getSource().sendSuccess(() -> Component.literal("Teleported to warp: " + destination), true);
            return 1;
        }
        ServerLevel dimensionLevel = getDimensionByName(destination, context);
        if (dimensionLevel != null) {
            teleportPlayerToDimension(player, dimensionLevel);
            String dimensionDisplayName = destination.contains(":") ? destination.split(":")[1] : destination;
            context.getSource().sendSuccess(() -> Component.literal("Teleported to dimension: " + dimensionDisplayName), true);
            return 1;
        }
        context.getSource().sendFailure(Component.literal("Warp or dimension not found: " + destination));
        return 0;
    }
    private int listWarps(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player not found."));
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        ResourceKey<Level> worldName = player.level().dimension();
        Map<String, BlockPos> currentWorldWarps = warpPointsByWorld.getOrDefault(worldName, new HashMap<>());
        Set<String> warpNames = new LinkedHashSet<>();
        Set<String> normalizedNames = new HashSet<>(currentWorldWarps.keySet());
        warpNames.add("overworld");
        warpNames.add("nether");
        warpNames.add("end");
        warpNames.addAll(currentWorldWarps.keySet());
        for (ServerLevel level : server.getAllLevels()) {
            String fullId = level.dimension().location().toString();
            if (!fullId.equals("minecraft:overworld") && !fullId.equals("minecraft:the_nether") && !fullId.equals("minecraft:the_end")) {
                String dimName = fullId.split(":")[1].toLowerCase();
                if (!normalizedNames.contains(dimName.replace("_", ""))) {
                    warpNames.add(dimName);
                }
            }
        }
        warpNames.addAll(currentWorldWarps.keySet());
        String warpListStr = "Available warps: " + String.join(", ", warpNames);
        context.getSource().sendSuccess(() -> Component.literal(warpListStr), false);
        return 1;
    }
    private int setWarp(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player not found."));
            return 0;
        }
        if (!player.getTags().contains("staff")) {
            context.getSource().sendFailure(Component.literal("You must be a staff member to set a warp."));
            return 0;
        }
        String warpName = StringArgumentType.getString(context, "name");
        BlockPos playerPos = player.blockPosition();
        ResourceKey<Level> worldName = player.level().dimension();
        setWarpPoint(warpName, playerPos, player.server, worldName);
        context.getSource().sendSuccess(() -> Component.literal("Warp set at: " + playerPos.toString()), true);
        return 1;
    }
    private void teleportToWarpPoint(ServerPlayer player, BlockPos warpPos) {
        player.teleportTo(player.serverLevel(), warpPos.getX(), warpPos.getY(), warpPos.getZ(), player.getYRot(), player.getXRot());
    }
    private ServerLevel getDimensionByName(String dimensionName, CommandContext<CommandSourceStack> context) {
        if ("overworld".equalsIgnoreCase(dimensionName)) {
            dimensionName = "minecraft:overworld";
        } else if ("nether".equalsIgnoreCase(dimensionName)) {
            dimensionName = "minecraft:the_nether";
        } else if ("end".equalsIgnoreCase(dimensionName)) {
            dimensionName = "minecraft:the_end";
        } else {
            if (dimensionName.contains(":")) {
                dimensionName = dimensionName.toLowerCase();
            } else {
                dimensionName = "cultivatormod:" + dimensionName.toLowerCase();
            }
        }
        MinecraftServer server = context.getSource().getServer();
        if (server == null) return null;
        for (ServerLevel level : server.getAllLevels()) {
            String levelName = level.dimension().location().toString();
            if (levelName.equalsIgnoreCase(dimensionName)) {
                return level;
            }
        }
        return null;
    }
    private void teleportPlayerToDimension(ServerPlayer player, ServerLevel dimensionLevel) {
        player.teleportTo(dimensionLevel, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        player.connection.send(new ClientboundTeleportEntityPacket(player));
    }
    public void setWarpPoint(String warpName, BlockPos pos, MinecraftServer server, ResourceKey<Level> worldName) {
        warpPointsByWorld.computeIfAbsent(worldName, k -> new HashMap<>()).put(warpName.toLowerCase(), pos);
        saveWarpPoints(server, worldName);
    }
    private void saveWarpPoints(MinecraftServer server, ResourceKey<Level> worldName) {
        Map<String, BlockPos> worldWarps = warpPointsByWorld.get(worldName);
        if (worldWarps == null || worldWarps.isEmpty()) return;
        File directory = getWarpFile(server, worldName).getParentFile();
        if (!directory.exists()) directory.mkdirs();
        Gson gson = new Gson();
        JsonObject json = new JsonObject();
        worldWarps.forEach((name, pos) -> {
            JsonObject warpData = new JsonObject();
            warpData.addProperty("x", pos.getX());
            warpData.addProperty("y", pos.getY());
            warpData.addProperty("z", pos.getZ());
            json.add(name, warpData);
        });
        try (FileWriter writer = new FileWriter(getWarpFile(server, worldName))) {
            gson.toJson(json, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static void loadWarpPoints(MinecraftServer server, ResourceKey<Level> worldName) {
        File warpFile = getWarpFile(server, worldName);
        if (!warpFile.exists()) return;
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(warpFile)) {
            JsonObject json = gson.fromJson(reader, JsonObject.class);
            json.entrySet().forEach(entry -> {
                JsonObject warpData = entry.getValue().getAsJsonObject();
                int x = warpData.get("x").getAsInt();
                int y = warpData.get("y").getAsInt();
                int z = warpData.get("z").getAsInt();
                BlockPos pos = new BlockPos(x, y, z);
                warpPointsByWorld.computeIfAbsent(worldName, k -> new HashMap<>()).put(entry.getKey().toLowerCase(), pos);
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private int removeWarp(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Player not found."));
            return 0;
        }
        if (!player.getTags().contains("staff")) {
            context.getSource().sendFailure(Component.literal("You must be a staff member to remove a warp."));
            return 0;
        }
        String warpName = StringArgumentType.getString(context, "name").toLowerCase();
        ResourceKey<Level> worldName = player.level().dimension();
        Map<String, BlockPos> worldWarps = warpPointsByWorld.getOrDefault(worldName, new HashMap<>());
        if (!worldWarps.containsKey(warpName)) {
            context.getSource().sendFailure(Component.literal("Warp '" + warpName + "' does not exist."));
            return 0;
        }
        worldWarps.remove(warpName);
        saveWarpPoints(player.server, worldName);
        context.getSource().sendSuccess(() -> Component.literal("Removed warp: " + warpName), true);
        return 1;
    }
}