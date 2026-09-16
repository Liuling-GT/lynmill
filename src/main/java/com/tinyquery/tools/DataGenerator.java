package com.tinyquery.tools;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.Random;

public class DataGenerator {
    public static void main(String[] args) throws Exception {
        String[] names = {"张三", "李四", "王五", "赵六", "孙七", "周八", "吴九", "郑十"};
        String[] cities = {"北京", "上海", "深圳", "杭州", "广州", "成都", "武汉", "南京"};
        Random random = new Random();

        long start = System.currentTimeMillis();
        BufferedWriter writer = new BufferedWriter(new FileWriter("data/huge.csv"));
        writer.write("name,age,city,salary\n");

        for (int i = 0; i < 1_000_000; i++) {
            String name = names[random.nextInt(names.length)] + i;
            int age = 18 + random.nextInt(43);
            String city = cities[random.nextInt(cities.length)];
            int salary = 3000 + random.nextInt(47000);
            writer.write(name + "," + age + "," + city + "," + salary + "\n");
        }

        writer.close();
        long end = System.currentTimeMillis();
        System.out.println("生成完毕，耗时 " + (end - start) + " ms");
    }
}
