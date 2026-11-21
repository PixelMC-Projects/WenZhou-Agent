package com.pixelmc.wenzhouai.service;

final class WzFoodBook {
    static final String MISSING = "当前服务器未安装生活调味料：胡萝卜版（solcarrot），无法查询这本食物手册。";

    static boolean namesBook(String name) {
        return name.equals("食物手册") || name.equalsIgnoreCase("solcarrot:food_book");
    }

    private WzFoodBook() {}
}
