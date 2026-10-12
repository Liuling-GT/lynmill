package com.tinyquery.datasource.loader;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真实文件验证解析器。
 *
 * <p>{@link XlsxReaderTest} 用的是手工拼的最小 xlsx —— 那些样本干净、规整，
 * 只包含我想到的情况。而手写解析器的风险恰恰在于没想到的情况。</p>
 *
 * <p>本项目的两个清单文件是真实使用中产生的：由 openpyxl 写出、又被 Excel
 * 打开编辑过，含中文列名、多行单元格（备注里有换行）、空表头列、5 张工作表。
 * 拿它们跑一遍，比再补十个自造样本都管用。</p>
 *
 * <p>不硬断言行数：清单文件的内容会变（每处理一条待办就更新它），断言具体行数
 * 会让这道测试在每次更新清单后失败 —— 那不是发现了 bug，只是数据变了。
 * 所以这里断言的是结构与不变量：表头正确、列数一致、中文没乱码、多行文本完整。
 * 这些才是不该变的。</p>
 *
 * <p>文件不在时跳过（{@link Assumptions}），不让 CI 或别人的机器上失败 ——
 * 这个文件属于本机的资料目录，不是代码仓库的一部分。</p>
 */
@DisplayName("XlsxReader 真实文件")
class XlsxReaderRealFileTest {

    private static final Path CHECKLIST = Path.of("D:\\TinyQuery-Data\\02-清单\\TinyQuery-待办清单.xlsx");
    private static final Path DESKTOP_CHECKLIST = Path.of("D:\\TinyQuery-Data\\02-清单\\TinyQuery-桌面化清单.xlsx");

    /**
     * 读真实文件的第一个工作簿。作为参照的那个文件里，备注列含大量换行 ——
     * 这是最容易在解析时被截断的地方
     */
    @Test
    @DisplayName("真实清单：表头、行数、中文都不出问题")
    void readsRealChecklist() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(CHECKLIST),
                "本机没有清单文件，跳过（这不是代码问题）");

        try (XlsxReader reader = XlsxReader.open(CHECKLIST.toString())) {
            assertFalse(reader.sheetNames().isEmpty(), "至少要有一张工作表");

            List<LinkedHashMap<String, Object>> rows = reader.readSheet(null);

            // 用第一行的键集当表头 —— readSheet 已经保证每行列一致
            assertFalse(rows.isEmpty(), "清单不该是空的");
            LinkedHashMap<String, Object> first = rows.get(0);

            assertTrue(first.containsKey("编号"),
                    "表头应当含「编号」列。实际列：" + first.keySet()
                            + "（若出现乱码，说明中文解码有问题）");
            assertTrue(first.containsKey("事项"), "表头应当含「事项」列");
            assertTrue(first.containsKey("备注"), "表头应当含「备注」列");

            // 每行列数必须一致：空单元格补位若写错，某些行会少列
            for (int i = 0; i < rows.size(); i++) {
                assertEquals(first.size(), rows.get(i).size(),
                        "第 " + (i + 1) + " 行列数与表头不一致 —— 空单元格补位有问题");
            }
        }
    }

    @Test
    @DisplayName("真实清单：多行单元格里的换行不丢")
    void multiLineCellsSurvive() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(CHECKLIST), "本机没有清单文件，跳过");

        try (XlsxReader reader = XlsxReader.open(CHECKLIST.toString())) {
            List<LinkedHashMap<String, Object>> rows = reader.readSheet(null);

            // 备注列里有大量多行文本。只要有一行的备注含换行，就说明
            // 解析器没有把内容截断在第一个换行处（XML 里换行是字面字符，
            // 但若用 BufferedReader 逐行读就会断）
            boolean foundMultiLine = rows.stream()
                    .map(r -> r.get("备注"))
                    .filter(v -> v instanceof String)
                    .map(String::valueOf)
                    .anyMatch(s -> s.contains("\n"));

            assertTrue(foundMultiLine,
                    "备注列里应当存在含换行的单元格。一个都没找到，说明多行内容被截断了");
        }
    }

    @Test
    @DisplayName("真实清单：中文没有乱码")
    void chineseTextIsNotMangled() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(CHECKLIST), "本机没有清单文件，跳过");

        try (XlsxReader reader = XlsxReader.open(CHECKLIST.toString())) {
            String all = reader.readSheet(null).toString();

            // 乱码的典型形态：问号串、替换字符、或 UTF-8 被按 GBK 读出的
            // 「寮犱笁」式结果。这里只查最明显的两种
            assertFalse(all.contains("\uFFFD"), "出现了替换字符 U+FFFD，说明解码有问题");
            assertFalse(all.contains("????"), "出现了问号串，说明字符集丢过信息");

            assertTrue(all.matches("(?s).*[\\u4e00-\\u9fff].*"),
                    "一个汉字都没有 —— 中文解码肯定有问题");
        }
    }

    @Test
    @DisplayName("真实多工作表文件：五张表都能读，且各有自己的表头")
    void readsMultiSheetRealFile() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(DESKTOP_CHECKLIST),
                "本机没有桌面化清单文件，跳过");

        try (XlsxReader reader = XlsxReader.open(DESKTOP_CHECKLIST.toString())) {
            List<String> sheets = reader.sheetNames();
            assertTrue(sheets.size() >= 2,
                    "这个文件应当有多张工作表，实际：" + sheets);

            // 每张表都要真的读得出来，不能只是名字列得对
            for (String name : sheets) {
                List<LinkedHashMap<String, Object>> rows = reader.readSheet(name);
                assertNotNull(rows, "工作表「" + name + "」读出来是 null");
                assertFalse(rows.isEmpty(), "工作表「" + name + "」一行都没有");
                assertFalse(rows.get(0).isEmpty(), "工作表「" + name + "」表头是空的");
            }
        }
    }

    @Test
    @DisplayName("真实文件里的空表头列被补上了名字，没有丢列")
    void blankHeadersGetFallbackNames() throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(DESKTOP_CHECKLIST), "本机没有文件，跳过");

        try (XlsxReader reader = XlsxReader.open(DESKTOP_CHECKLIST.toString())) {
            // 这个文件的最后一张表有两列没写表头。
            // 不补名的话 LinkedHashMap 会把这些列折叠成一个键，列就丢了
            for (String name : reader.sheetNames()) {
                List<LinkedHashMap<String, Object>> rows = reader.readSheet(name);
                if (rows.isEmpty()) {
                    continue;
                }
                for (String header : rows.get(0).keySet()) {
                    assertNotNull(header, "工作表「" + name + "」有 null 表头");
                    assertFalse(header.isBlank(), "工作表「" + name + "」有空白表头，说明没补上兜底名");
                }
            }
        }
    }
}
