package com.pixelmc.wenzhouai;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pixelmc.wenzhouai.manager.WzAdminBackend;
import com.pixelmc.wenzhouai.manager.WzAdminServer;
import com.pixelmc.wenzhouai.manager.WzManagerStorage;
import com.pixelmc.wenzhouai.network.WzNetwork;
import com.pixelmc.wenzhouai.service.WzService;
import com.pixelmc.wenzhouai.service.WenzhouReadTools;
import net.minecraftforge.event.OnDatapackSyncEvent;
import com.pixelmc.pixelmcmanager.api.ManagerBridgeApi;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("wenzhouai")
public final class WenzhouAi {
    private WzAdminServer admin;

    public WenzhouAi() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            String version =
                    ModList.get()
                            .getModContainerById("wenzhouai")
                            .orElseThrow()
                            .getModInfo()
                            .getVersion()
                            .toString();
            WzManagerStorage.register(version);
            ManagerBridgeApi.onCallback("wenzhouai", (operation, json) -> {
                if ("accessChanged".equals(operation)) {
                    var input = JsonParser.parseString(json).getAsJsonObject();
                    WzService.invalidate(UUID.fromString(input.get("player").getAsString()));
                } else if ("quotaChanged".equals(operation)) {
                    var input = JsonParser.parseString(json).getAsJsonObject();
                    WzService.quotaChanged(input.has("player") && !input.get("player").isJsonNull()
                            ? UUID.fromString(input.get("player").getAsString()) : null);
                }
            });
        }
        WzNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(this::start);
        MinecraftForge.EVENT_BUS.addListener(this::stop);
        MinecraftForge.EVENT_BUS.addListener(this::datapackSync);
    }

    private void start(ServerStartedEvent event) {
        var server = event.getServer();
        WenzhouReadTools.refreshRecipeIndex(server);
        WzService.start(server);
        if (server.isDedicatedServer()) {
            try {
                admin =
                        WzAdminServer.start(
                                FMLPaths.CONFIGDIR.get(),
                                new WzAdminBackend(
                                        server.getWorldPath(LevelResource.ROOT)
                                                .resolve("wenzhouai/admin-audit.jsonl"),
                                        WzManagerStorage.DIRECTORY,
                                        WzService.adminData())::request);
            } catch (IOException | RuntimeException unavailable) {
                LogUtils.getLogger()
                        .error(
                                "WenZhou administration could not start. Check the local admin listener and world data.");
            }
        }
    }

    private void datapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null)
            WenzhouReadTools.refreshRecipeIndex(event.getPlayerList().getServer());
    }

    private void stop(ServerStoppingEvent event) {
        WenzhouReadTools.clearRecipeIndex();
        if (admin != null) {
            admin.close();
            admin = null;
        }
        WzService.stop(event.getServer());
    }
}
