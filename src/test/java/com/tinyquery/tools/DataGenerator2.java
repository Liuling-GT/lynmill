package com.tinyquery.tools;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.Random;

public class DataGenerator2 {
    public static void main(String[] args) throws Exception {
        String[] products = {"苹果", "香蕉", "牛奶", "面包", "可乐", "薯片", "巧克力", "咖啡"};
        String[] regions = {"华东", "华北", "华南", "西南", "东北", "西北"};
        Random random = new Random();

        long start = System.currentTimeMillis();
        BufferedWriter writer = new BufferedWriter(new FileWriter("data/orders.csv"));
        writer.write("order_id,product,quantity,price,region\n");

        for (int i = 0; i < 1_000_000; i++) {
            int orderId = 1000000 + i;
            String product = products[random.nextInt(products.length)];
            int quantity = 1 + random.nextInt(100);
            int price = 5 + random.nextInt(496);
            String region = regions[random.nextInt(regions.length)];

            writer.write(orderId + "," + product + "," + quantity + "," + price + "," + region + "\n");
        }

        writer.close();
        long end = System.currentTimeMillis();
        System.out.println("生成完毕，耗时 " + (end - start) + " ms");
    }
}