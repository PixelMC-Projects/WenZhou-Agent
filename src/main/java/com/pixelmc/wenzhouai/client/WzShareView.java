package com.pixelmc.wenzhouai.client;

import static com.pixelmc.wenzhouai.client.WzConversation.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class WzShareView {
    enum Page { INBOX, DETAIL, PREVIEW, EXTERNAL }
    Page page;
    JsonObject data = new JsonObject();
    final List<Turn> turns = new ArrayList<>();
    String error = "", remove = "", source = "";
    boolean loading, writing, published;
    int index;
    private long revision;
    private final WzConversation conversation;
    private final Consumer<String> notice;

    WzShareView(WzConversation conversation, Consumer<String> notice) {
        this.conversation = conversation;
        this.notice = notice;
    }

    void close() {
        page = null;
        revision++;
        data = new JsonObject();
        turns.clear();
        remove = source = error = "";
        loading = writing = published = false;
    }

    void load(Page target, String source, int pageIndex) {
        close();
        page = target;
        this.source = source;
        index = pageIndex;
        loading = true;
        long serial = revision;
        var body = id("id",source);
        body.addProperty("page",index);
        var action = switch (target) {
            case INBOX -> "share.list";
            case PREVIEW -> "share.preview";
            default -> "share.open";
        };
        conversation.request(action,body,result -> {
            if (serial != revision) return;
            loading = false;
            error = str(result,"error");
            if (!error.isEmpty()) return;
            data = result;
            if (target == Page.INBOX) index = (int)number(result,"page",0);
            for (var element : array(result,"messages")) {
                var row = element.getAsJsonObject();
                var turn = new Turn(str(row,"question"));
                turn.reply = obj(row,"reply");
                turn.modelSelection = str(row,"model");
                turn.open = false;
                turns.add(turn);
            }
            if (result.has("warning")) notice.accept(str(result,"warning"));
        });
    }

    void publish(Runnable success) {
        if (page != Page.PREVIEW || published || writing || loading || !error.isEmpty()) return;
        writing = true;
        long serial = revision;
        conversation.request("share.publish", id("id",str(data,"id")), result -> {
            if (serial != revision) return;
            writing = false;
            if (result.has("error")) { notice.accept(str(result,"error")); return; }
            published = true;
            success.run();
        });
    }

    void change(JsonObject row, boolean delete, Runnable removed) {
        if (writing || page != Page.INBOX) return;
        long serial = revision;
        writing = true;
        var body = id("id", str(row,"id"));
        body.addProperty("pinned", !flag(row,"pinned"));
        body.addProperty("page",index);
        conversation.request(delete ? "share.delete" : "share.pin", body, result -> {
            if (serial != revision) return;
            writing = false;
            if (result.has("error")) { notice.accept(str(result,"error")); return; }
            remove = "";
            data = result;
            index = (int)number(result,"page",0);
            if (delete) removed.run();
        });
    }
}
