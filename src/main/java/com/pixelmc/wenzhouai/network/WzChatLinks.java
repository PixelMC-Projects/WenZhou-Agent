package com.pixelmc.wenzhouai.network;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.BiConsumer;

public final class WzChatLinks {
    private WzChatLinks() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, BiConsumer<ServerPlayer, JsonObject> open) {
        var root = Commands.literal("wzview");
        for (String kind : new String[]{"shared","answer"}) {
            root.then(Commands.literal(kind).then(Commands.argument("id", UuidArgument.uuid()).executes(ctx -> {
                var player = ctx.getSource().getPlayerOrException();
                var notice = new JsonObject();
                notice.addProperty("type", "link." + kind);
                notice.addProperty("id", UuidArgument.getUuid(ctx,"id").toString());
                open.accept(player, notice);
                return 1;
            })));
        }
        dispatcher.register(root);
    }

    public static Component completed(String title, String id) {
        return line("回答已完成", title, "answer", id, "打开这段对话");
    }

    static Component shared(String sender, String title, String id) {
        return line(sender + " 分享了对话", title, "shared", id, "打开只读快照并保存到收到的分享");
    }

    private static Component line(String label, String title, String kind, String id, String tooltip) {
        return Component.literal("问舟 ").withStyle(s -> s.withColor(0x9EDCE7))
                .append(Component.literal("· " + label + "  ").withStyle(s -> s.withColor(0xBCC6D4)))
                .append(Component.literal("「" + title + "」 ↗").withStyle(s -> s.withColor(0xD3C5FF)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/wzview " + kind + " " + id))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(tooltip)))));
    }
}
