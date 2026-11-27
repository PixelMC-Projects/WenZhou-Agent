/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pixelmc.hub.client.render.HubDraw;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class WzModelPicker {
    static final String TRIGGER = "wz-model";
    static final String DEEPSEEK = "deepseek";
    static final String TERRA = "terra-api";
    static final String TERRA_LABEL = "GPT-6.1 Terra";
    private static final String MODEL_ROW = "wz-model-choice-";
    private static final String EFFORT_ROW = "wz-model-effort-";
    private static final float MENU_WIDTH = 234, ROW_HEIGHT = 31, TRIGGER_HEIGHT = 30;
    private static final int VISIBLE_MODELS = 7;
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._-]{1,96}");
    private static final List<String> CHATGPT_EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    private static final Option DEFAULT_MODEL = new Option(DEEPSEEK, "DeepSeek",
            List.of("inherit", "off", "low", "high", "max"));

    record Option(String id, String label, List<String> efforts) {}

    record State(boolean connected, boolean managedOnly, List<Option> options, Option selected,
                 String effort, String effectiveEffort) {
        static State from(JsonObject value) {
            if (value == null) return null;
            boolean connected = WzConversation.flag(value, "connected");
            boolean managedOnly = WzConversation.flag(value, "managedOnly");
            Option deepSeek = managedOnly ? new Option(DEEPSEEK, DEFAULT_MODEL.label(), List.of()) : DEFAULT_MODEL;
            Map<String, Option> options = new LinkedHashMap<>();
            options.put(DEEPSEEK, deepSeek);
            for (JsonElement entry : WzConversation.array(value, "models")) {
                if (!entry.isJsonObject()) continue;
                JsonObject row = entry.getAsJsonObject();
                String id = WzConversation.str(row, "id");
                if (!MODEL_ID.matcher(id).matches() || options.containsKey(id)) continue;
                if (TERRA.equals(id)) {
                    options.put(id, new Option(id, TERRA_LABEL, List.of()));
                    continue;
                }
                if (managedOnly || !connected) continue;
                String label = WzConversation.str(row, "label").replaceAll("\\s+", " ").strip();
                List<String> efforts = new ArrayList<>();
                for (JsonElement element : WzConversation.array(row, "efforts")) {
                    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
                    String effort = element.getAsString();
                    if (CHATGPT_EFFORTS.contains(effort) && !efforts.contains(effort)) efforts.add(effort);
                }
                options.put(id, new Option(id, label.isBlank() ? id : label, List.copyOf(efforts)));
            }
            if (options.containsKey(TERRA)) {
                options.replaceAll((id, option) -> !TERRA.equals(id) && TERRA_LABEL.equals(option.label())
                        ? new Option(id, TERRA_LABEL + "（套餐）", option.efforts()) : option);
            }

            String modelId = WzConversation.str(value, "model");
            Option model = options.getOrDefault(modelId, deepSeek);
            String effort = WzConversation.str(WzConversation.obj(value, "efforts"), model.id());
            if (!model.efforts().contains(effort))
                effort = model.efforts().isEmpty() ? "auto"
                        : model.efforts().contains("medium") ? "medium" : model.efforts().get(0);
            String effective = WzConversation.str(value, "effectiveEffort");
            if (!model.id().equals(modelId) || !model.efforts().contains(effective) || effective.equals("inherit"))
                effective = effort.equals("inherit") ? "low" : effort;
            return new State(connected, managedOnly, List.copyOf(options.values()), model, effort, effective);
        }

        boolean terra() { return TERRA.equals(selected.id()); }
        boolean chatGpt() { return !DEEPSEEK.equals(selected.id()) && !terra(); }
        boolean showEffort() { return !managedOnly && !terra(); }

        String snapshot() { return showEffort() ? selected.id() + "/" + effectiveEffort : selected.id(); }

        boolean accepts(String id, String effort) {
            for (Option option : options)
                if (option.id().equals(id)) return effort == null || option.efforts().contains(effort);
            return false;
        }
    }

    private record Bounds(float x, float y, float w, float h) {
        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final WzPage.Host host;
    private final WzConversation conversation;
    private Bounds trigger = new Bounds(0, 0, 0, 0), menu = trigger;
    private final List<Bounds> activeRows = new ArrayList<>();
    private boolean open;
    private int firstModel;

    WzModelPicker(WzPage.Host host, WzConversation conversation) {
        this.host = host;
        this.conversation = conversation;
    }

    boolean open() { return open; }

    void close() {
        open = false;
        activeRows.clear();
    }

    boolean focused(String id) {
        return id != null && (id.equals(TRIGGER) || id.startsWith(MODEL_ROW) || id.startsWith(EFFORT_ROW));
    }

    boolean containsMenu(float x, float y) { return open && menu.contains(x, y); }

    boolean contains(float x, float y) { return trigger.contains(x, y) || containsMenu(x, y); }

    boolean actionable(float x, float y) {
        return activeRows.stream().anyMatch(bounds -> bounds.contains(x, y));
    }

    float triggerWidth(HubDraw draw, State state) {
        return Math.min(144, draw.textWidth(state.selected().label(), 12, false))
                + (state.showEffort() ? draw.textWidth(effortLabel(state.effectiveEffort()), 12, false) + 14 : 0) + 27;
    }

    void drawTrigger(HubDraw draw, WzReferenceTools.Hits hits, State state, float x, float mid, float width,
                     float mx, float my, String focus, int ink, int muted, int rule,
                     boolean enabled, Runnable toggle) {
        float y = mid - TRIGGER_HEIGHT / 2;
        trigger = new Bounds(x, y, width, TRIGGER_HEIGHT);
        boolean hovered = enabled && (trigger.contains(mx, my) || TRIGGER.equals(focus));
        if (hovered || open) draw.roundRect(x, y, width, TRIGGER_HEIGHT, 8, alpha(ink, .08f));
        int color = hovered || open ? ink : alpha(muted, enabled ? 1 : .65f);
        String effort = effortLabel(state.effectiveEffort());
        float textWidth = width - 27;
        String label = state.selected().label();
        if (state.showEffort()) {
            float effortWidth = draw.textWidth(effort, 12, false);
            textWidth -= effortWidth + 14;
            float effortX = x + width - 23 - effortWidth;
            draw.rect(effortX - 6, mid - 6, 1, 12, rule);
            draw.text(effort, effortX, mid + 4, 12, color, false);
            label += " · 思考强度：" + effort;
        }
        draw.text(ellipsize(draw, state.selected().label(), 12, textWidth), x + 8, mid + 4, 12, color, false);
        WzGlyph.draw(draw, "down", x + width - 19, mid - 6.5f, 13, color);
        if (enabled || open) hits.add(TRIGGER, label, x, y, width, TRIGGER_HEIGHT, toggle);
    }

    void toggle(State state) {
        if (open) {
            close();
            host.wenzhouFocus(TRIGGER);
        } else if (state != null && !conversation.busy()) {
            open = true;
            firstModel = 0;
            focusOption(state, state.options().indexOf(state.selected()));
        }
    }

    void drawMenu(HubDraw draw, WzReferenceTools.Hits hits, State state, float mx, float my, String focus,
                  int ink, int muted, int blue, int rule, int paper) {
        if (!open) return;
        activeRows.clear();
        int models = state.options().size();
        int visible = Math.min(models, VISIBLE_MODELS);
        firstModel = Math.min(firstModel, models - visible);
        boolean showLocked = !state.connected() && !state.managedOnly();
        boolean showTerraNotice = state.accepts(TERRA, null);
        float lockedHeight = showLocked ? 53 : 0;
        float h = 33 + visible * ROW_HEIGHT + lockedHeight
                + (state.showEffort() ? 45 : 0) + (showTerraNotice ? 54 : 0);
        float x = trigger.x + trigger.w - MENU_WIDTH;
        float y = trigger.y - h - 10;
        if (y < 63 && trigger.y + trigger.h + h + 10 < 846) y = trigger.y + trigger.h + 10;
        menu = new Bounds(x, y, MENU_WIDTH, h);
        WzWinter winter = WzWinter.forDraw(draw);
        if (winter != null) winter.popover(draw, x, y, MENU_WIDTH, h);
        else {
            draw.boxShadow(x, y, MENU_WIDTH, h, 13, 0, 8, 24, -5, 0x28152541);
            draw.roundRect(x, y, MENU_WIDTH, h, 13, paper);
            draw.strokeRoundRect(x + .5f, y + .5f, MENU_WIDTH - 1, h - 1, 12.5f, 1, rule);
        }
        draw.text("选择模型", x + 13, y + 21, 11, winter != null ? winter.pine() : muted, false);
        boolean enabled = !conversation.busy();
        float rowY = y + 28;
        for (int i = firstModel; i < firstModel + visible; i++, rowY += ROW_HEIGHT) {
            Option option = state.options().get(i);
            String id = MODEL_ROW + option.id();
            boolean selected = state.selected().id().equals(option.id());
            Bounds row = new Bounds(x + 6, rowY, MENU_WIDTH - 12, ROW_HEIGHT - 1);
            if (id.equals(focus) || enabled && row.contains(mx, my))
                draw.roundRect(row.x, row.y, row.w, row.h, 7, alpha(blue, .1f));
            draw.text(ellipsize(draw, option.label(), 13, row.w - 32),
                    row.x + 7, row.y + 20, 13, selected ? blue : ink, false);
            if (selected) WzGlyph.draw(draw, "check", row.x + row.w - 24, row.y + 7, 16, blue);
            if (enabled) option(hits, id, option.label(), row, () -> {
                if (state.managedOnly() || TERRA.equals(option.id())) {
                    close();
                    host.wenzhouFocus(TRIGGER);
                }
                conversation.setModel(option.id(), null, host::feedbackError);
            });
        }
        if (models > visible) {
            float trackHeight = visible * ROW_HEIGHT;
            float thumbHeight = Math.max(13, trackHeight * visible / models);
            float thumbY = y + 28 + (trackHeight - thumbHeight) * firstModel / (models - visible);
            draw.roundRect(x + MENU_WIDTH - 5, thumbY, 2, thumbHeight, 1, muted);
        }
        if (showLocked) {
            draw.text("ChatGPT", x + 13, rowY + 20, 13, alpha(muted, .62f), false);
            WzGlyph.draw(draw, "lock", x + MENU_WIDTH - 29, rowY + 7, 15, muted);
            draw.text("在 Manager 登录 ChatGPT 后可用", x + 13, rowY + 42, 11, muted, false);
            rowY += lockedHeight;
        }
        if (showTerraNotice) {
            draw.rect(x + 9, rowY + 1, MENU_WIDTH - 18, 1, rule);
            draw.text("Terra 由 OpenAI 处理问题、相关前文", x + 13, rowY + 16, 11, muted, false);
            draw.text("和回答所需的游戏查询资料。", x + 13, rowY + 31, 11, muted, false);
            draw.text("项目余额不可用时改用 DeepSeek。", x + 13, rowY + 46, 11, muted, false);
            rowY += 54;
        }
        if (!state.showEffort()) return;
        draw.rect(x + 9, rowY + 1, MENU_WIDTH - 18, 1, rule);
        draw.text("思考强度", x + 13, rowY + 17, 11, muted, false);
        if (state.selected().efforts().isEmpty()) draw.text("自动", x + 13, rowY + 37, 12, muted, false);
        float effortX = x + 10;
        for (String effort : state.selected().efforts()) {
            String id = EFFORT_ROW + effort;
            String label = effortLabel(effort);
            float width = Math.max(27, draw.textWidth(label, 12, false) + 12);
            Bounds row = new Bounds(effortX, rowY + 22, width, 23);
            boolean selected = state.effort().equals(effort);
            if (selected || id.equals(focus) || enabled && row.contains(mx, my))
                draw.roundRect(row.x, row.y, row.w, row.h, 6, alpha(blue, selected ? .15f : .09f));
            draw.text(label, effortX + (width - draw.textWidth(label, 12, false)) / 2,
                    row.y + 16, 12, selected ? blue : ink, false);
            if (enabled) option(hits, id, label, row, () -> {
                close();
                host.wenzhouFocus(TRIGGER);
                conversation.setModel(state.selected().id(), effort, host::feedbackError);
            });
            effortX += width + 3;
        }
    }

    private void option(WzReferenceTools.Hits hits, String id, String label, Bounds row, Runnable action) {
        activeRows.add(row);
        hits.add(id, label, row.x, row.y, row.w, row.h, action);
    }

    boolean scroll(float x, float y, double amount, State state) {
        if (!containsMenu(x, y)) return false;
        int last = Math.max(0, state.options().size() - VISIBLE_MODELS);
        firstModel = Math.max(0, Math.min(last, firstModel - (int) Math.signum(amount)));
        return true;
    }

    boolean key(int key, String focused, State state) {
        if (!open || !focused(focused)) return false;
        int direction = switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_UP -> -1;
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_DOWN -> 1;
            default -> 0;
        };
        if (direction == 0 && key != GLFW.GLFW_KEY_HOME && key != GLFW.GLFW_KEY_END) return false;
        int models = state.options().size();
        List<String> efforts = state.selected().efforts();
        int count = models + efforts.size();
        int current = -1;
        for (int i = 0; i < models; i++)
            if ((MODEL_ROW + state.options().get(i).id()).equals(focused)) current = i;
        for (int i = 0; i < efforts.size(); i++)
            if ((EFFORT_ROW + efforts.get(i)).equals(focused)) current = models + i;
        int index = switch (key) {
            case GLFW.GLFW_KEY_HOME -> 0;
            case GLFW.GLFW_KEY_END -> count - 1;
            default -> current < 0 ? 0 : Math.floorMod(current + direction, count);
        };
        if (index < models) focusOption(state, index);
        else host.wenzhouFocus(EFFORT_ROW + efforts.get(index - models));
        return true;
    }

    private void focusOption(State state, int index) {
        if (index < firstModel) firstModel = index;
        if (index >= firstModel + VISIBLE_MODELS) firstModel = index - VISIBLE_MODELS + 1;
        host.wenzhouFocus(MODEL_ROW + state.options().get(index).id());
    }

    private static int alpha(int color, float opacity) {
        return color & 0x00ffffff | Math.round((color >>> 24) * opacity) << 24;
    }

    private static String ellipsize(HubDraw draw, String text, float size, float width) {
        if (draw.textWidth(text, size, false) <= width) return text;
        int end = text.length();
        do {
            end = text.offsetByCodePoints(end, -1);
        } while (draw.textWidth(text.substring(0, end) + "…", size, false) > width && end > 0);
        return text.substring(0, end) + "…";
    }

    private static String effortLabel(String effort) {
        return switch (effort) {
            case "inherit" -> "跟随全局";
            case "off" -> "关闭";
            case "low" -> "低";
            case "medium" -> "中";
            case "high" -> "高";
            case "xhigh" -> "极高";
            case "max" -> "最高";
            default -> "自动";
        };
    }
}
