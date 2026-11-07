package dev.loretools;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.level.ServerPlayer;

/** Optional dependency isolated from the built-in backend's class loading. */
final class LuckPermsBridge {
    private LuckPermsBridge() {}
    static boolean allowed(ServerPlayer player, String node) {
        LuckPerms api = LuckPermsProvider.get();
        if (api.getUserManager().getUser(player.getUUID()) == null) return false;
        return api.getPlayerAdapter(ServerPlayer.class).getPermissionData(player).checkPermission(node) == Tristate.TRUE;
    }
}
