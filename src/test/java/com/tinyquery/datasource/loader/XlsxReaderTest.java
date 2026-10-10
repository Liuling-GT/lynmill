package com.tinyquery.datasource.loader;

import com.tinyquery.datasource.spec.DataSourceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link XlsxReader} 的解析行为。
 *
 * <p>重点覆盖真实文件里没有的分支：共享字符串表、日期、各种单元格类型。
 * 见 {@link XlsxReaderRealFileTest} 了解为什么需要另外造样本。</p>
 */
@DisplayName("XlsxReader 解析")
class XlsxReaderTest {

    // ==================== 两种字符串存储 ====================

    @Test
    @DisplayName("共享字符串：t=s 时按下标取值")
    void readsSharedStrings(@TempDir Path dir) throws IOException {
        // Excel 自己存盘用的是这种。openpyxl 生成的样本里一个都没有，
        // 所以必须手工造 —— 这是最容易写错、且真实文件测不到的分支
        Path file = XlsxFixture.builder()
                .sharedStrings("姓名", "年龄", "张三", "30")
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.sharedCell("A1", 0) + XlsxFixture.sharedCell("B1", 1))
                        + XlsxFixture.row(2,
                        XlsxFixture.sharedCell("A2", 2) + XlsxFixture.sharedCell("B2", 3)))
                .writeTo(dir.resolve("shared.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            List<LinkedHashMap<String, Object>> rows = reader.readSheet(null);
            assertEquals(1, rows.size());
            assertEquals("张三", rows.get(0).get("姓名"));
        }
    }

    @Test
    @DisplayName("内联字符串：t=inlineStr 直接取文本")
    void readsInlineStrings(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "姓名") + XlsxFixture.inlineCell("B1", "城市"))
                        + XlsxFixture.row(2,
                        XlsxFixture.inlineCell("A2", "李四") + XlsxFixture.inlineCell("B2", "北京")))
                .writeTo(dir.resolve("inline.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            List<LinkedHashMap<String, Object>> rows = reader.readSheet(null);
            assertEquals("李四", rows.get(0).get("姓名"));
            assertEquals("北京", rows.get(0).get("城市"));
        }
    }

    @Test
    @DisplayName("共享字符串里的富文本分段要拼起来，不能只取第一段")
    void joinsRichTextSegments(@TempDir Path dir) throws IOException {
        // 一个 <si> 里含多个 <r><t>，比如「粗体」这两个字被分成两段。
        // 只取第一个 <t> 会丢字，而且丢得毫无规律，用户会以为文件坏了
        Path file = XlsxFixture.builder()
                .sharedStringsRich("完整", "的内容")
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "列"))
                        + XlsxFixture.row(2, XlsxFixture.sharedCell("A2", 0)))
                .writeTo(dir.resolve("rich.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals("完整的内容", reader.readSheet(null).get(0).get("列"));
        }
    }

    // ==================== 数据类型 ====================

    @Test
    @DisplayName("整数读成 Long 而不是 Double —— 否则界面显示 36.0")
    void readsIntegersAsLong(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "值"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "36"))
                        + XlsxFixture.row(3, XlsxFixture.numberCell("A3", "0")))
                .writeTo(dir.resolve("int.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            List<LinkedHashMap<String, Object>> rows = reader.readSheet(null);
            assertEquals(36L, rows.get(0).get("值"));
            assertEquals(0L, rows.get(1).get("值"));
            assertInstanceOf(Long.class, rows.get(0).get("值"),
                    "整数必须是 Long：读成 Double 会让界面显示成 36.0");
        }
    }

    @Test
    @DisplayName("小数保留小数")
    void readsDecimals(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "值"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "3.14")))
                .writeTo(dir.resolve("dec.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals(3.14, (Double) reader.readSheet(null).get(0).get("值"), 1e-9);
        }
    }

    @Test
    @DisplayName("布尔读成 Boolean")
    void readsBooleans(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "启用") + XlsxFixture.inlineCell("B1", "停用"))
                        + XlsxFixture.row(2,
                        XlsxFixture.boolCell("A2", true) + XlsxFixture.boolCell("B2", false)))
                .writeTo(dir.resolve("bool.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals(Boolean.TRUE, reader.readSheet(null).get(0).get("启用"));
            assertEquals(Boolean.FALSE, reader.readSheet(null).get(0).get("停用"));
        }
    }

    @Test
    @DisplayName("公式取缓存值，不自行计算")
    void readsFormulaCachedValue(@TempDir Path dir) throws IOException {
        // 本工具刻意不做公式求值。Excel 存盘时会把算好的结果写进 <v>，
        // 直接用它就好。若文件从没被 Excel 打开过（别的程序生成的），
        // 可能没有 <v>，那时读出来是空 —— 宁可空着也不给一个自己算的数
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "合计"))
                        + XlsxFixture.row(2, XlsxFixture.formulaCell("A2", "100")))
                .writeTo(dir.resolve("formula.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals(100L, reader.readSheet(null).get(0).get("合计"));
        }
    }

    // ==================== 日期 ====================

    @Test
    @DisplayName("内置日期格式的序列号还原成 yyyy-MM-dd")
    void readsBuiltinDateFormat(@TempDir Path dir) throws IOException {
        // 45999 = 2025-12-08（用 openpyxl 独立核对过）。numFmtId=14 是内置日期格式
        Path file = XlsxFixture.builder()
                .styles(0, 14)
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "日期"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "45999", 1)))
                .writeTo(dir.resolve("date.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals("2025-12-08", reader.readSheet(null).get(0).get("日期"));
        }
    }

    @Test
    @DisplayName("自定义日期格式串也能识别")
    void readsCustomDateFormat(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .styles("164=yyyy/mm/dd", 0, 164)
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "日期"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "45999", 1)))
                .writeTo(dir.resolve("customdate.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals("2025-12-08", reader.readSheet(null).get(0).get("日期"));
        }
    }

    @Test
    @DisplayName("没有日期格式的样式，数字就是数字")
    void numberWithoutDateFormatStaysNumeric(@TempDir Path dir) throws IOException {
        // 同样是 45999，但没有日期格式 —— 必须原样当数字。
        // 这一条是防「把所有数字都当日期」的过度处理
        Path file = XlsxFixture.builder()
                .styles(0, 0)
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "数量"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "45999", 1)))
                .writeTo(dir.resolve("plainnum.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals(45999L, reader.readSheet(null).get(0).get("数量"));
        }
    }

    @Test
    @DisplayName("1900 闰年 bug：序列号 60 之后要偏移一天")
    void handlesExcel1900LeapYearBug(@TempDir Path dir) throws IOException {
        // Excel 为兼容 Lotus 1-2-3 认为 1900 年是闰年，序列号 60 对应
        // 一个不存在的 1900-02-29。不照抄这个偏移，所有日期都会差一天。
        //   59 → 1900-02-28
        //   61 → 1900-03-01（60 被跳过）
        //   45999 → 2025-12-08
        Path file = XlsxFixture.builder()
                .styles(0, 14)
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "前") + XlsxFixture.inlineCell("B1", "后"))
                        + XlsxFixture.row(2,
                        XlsxFixture.numberCell("A2", "59", 1) + XlsxFixture.numberCell("B2", "61", 1))
                        + XlsxFixture.row(3,
                        XlsxFixture.numberCell("A3", "45999", 1) + XlsxFixture.numberCell("B3", "1", 1)))
                .writeTo(dir.resolve("leap.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            List<LinkedHashMap<String, Object>> rows = reader.readSheet(null);
            assertEquals("1900-02-28", rows.get(0).get("前"), "序列号 59");
            assertEquals("1900-03-01", rows.get(0).get("后"), "序列号 61，60 是被跳过的那天");
            assertEquals("2025-12-08", rows.get(1).get("前"), "序列号 45999");
            assertEquals("1900-01-01", rows.get(1).get("后"), "序列号 1");
        }
    }

    @Test
    @DisplayName("带时间的日期：小数部分换算成时刻")
    void readsDateTime(@TempDir Path dir) throws IOException {
        // 45999.5 = 2025-12-08 12:00:00
        Path file = XlsxFixture.builder()
                .styles(0, 22)
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "时间"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "45999.5", 1)))
                .writeTo(dir.resolve("datetime.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            // ISO_LOCAL_DATE_TIME 一律带秒。表格里 OOXML 的日期时间就是
            // 完整形式，保持一致比省两个字符更重要 ——
            // 字符串比较排序时才不会出现 12:00 与 12:00:30 谁在前的问题
            assertEquals("2025-12-08T12:00:00", reader.readSheet(null).get(0).get("时间"));
        }
    }

    @Test
    @DisplayName("午夜 00:00 的日期时间只给日期，不缀多余的时间")
    void midnightDropsRedundantTime(@TempDir Path dir) throws IOException {
        // 45999.0 配日期时间格式时，算出来的时刻是 00:00。
        // 缀上「T00:00」没有信息量，反而让用户以为这个时间有意义
        Path file = XlsxFixture.builder()
                .styles(0, 22)
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "时间"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "45999", 1)))
                .writeTo(dir.resolve("midnight.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals("2025-12-08", reader.readSheet(null).get(0).get("时间"));
        }
    }

    @Test
    @DisplayName("负数与大数不当作日期，退回数字")
    void rejectsUnreasonableDateSerials(@TempDir Path dir) throws IOException {
        // 格式是日期，但值根本不像日期（负数、超出 Excel 支持的范围）。
        // 硬套公式会算出一个荒唐的年份，不如原样给数字
        Path file = XlsxFixture.builder()
                .styles(0, 14)
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "值"))
                        + XlsxFixture.row(2, XlsxFixture.numberCell("A2", "-5", 1)))
                .writeTo(dir.resolve("baddate.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals(-5L, reader.readSheet(null).get(0).get("值"));
        }
    }

    // ==================== 列位置 ====================

    @Test
    @DisplayName("空单元格被跳过时，后面的值不能串到前一列")
    void missingCellsDoNotShiftColumns(@TempDir Path dir) throws IOException {
        // 第 2 行只有 B 列有值。若按顺序追加而不是按引用定位，
        // B 的值会跑到 A 列去 —— 整行数据静默错位，用户很难发现
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "甲") + XlsxFixture.inlineCell("B1", "乙")
                                + XlsxFixture.inlineCell("C1", "丙"))
                        + XlsxFixture.row(2, XlsxFixture.inlineCell("B2", "只有乙")))
                .writeTo(dir.resolve("gap.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            LinkedHashMap<String, Object> row = reader.readSheet(null).get(0);
            assertNull(row.get("甲"));
            assertEquals("只有乙", row.get("乙"));
            assertNull(row.get("丙"));
        }
    }

    @Test
    @DisplayName("双字母列号（AA、AB）解析正确")
    void resolvesMultiLetterColumns(@TempDir Path dir) throws IOException {
        assertEquals(0, XlsxReader.columnIndexOf("A1"));
        assertEquals(25, XlsxReader.columnIndexOf("Z1"));
        assertEquals(26, XlsxReader.columnIndexOf("AA1"));
        assertEquals(27, XlsxReader.columnIndexOf("AB10"));
        assertEquals(-1, XlsxReader.columnIndexOf("1A"));
        assertEquals(-1, XlsxReader.columnIndexOf(""));
        assertEquals(-1, XlsxReader.columnIndexOf(null));

        // 反向：列号 → 字母，用于表头为空的兜底命名
        assertEquals("A", XlsxReader.columnLetter(0));
        assertEquals("Z", XlsxReader.columnLetter(25));
        assertEquals("AA", XlsxReader.columnLetter(26));
    }

    @Test
    @DisplayName("表头为空或重名时给出可用的列名，不丢列")
    void fillsBlankAndDuplicateHeaders(@TempDir Path dir) throws IOException {
        // 空表头与重名表头在真实文件里很常见（合并单元格展开之后尤其明显）。
        // 不处理的话 LinkedHashMap 会把同名的覆盖掉，用户莫名其妙少一列
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1,
                        XlsxFixture.inlineCell("A1", "名称") + XlsxFixture.inlineCell("B1", "")
                                + XlsxFixture.inlineCell("C1", "名称"))
                        + XlsxFixture.row(2,
                        XlsxFixture.inlineCell("A2", "1") + XlsxFixture.inlineCell("B2", "2")
                                + XlsxFixture.inlineCell("C2", "3")))
                .writeTo(dir.resolve("headers.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            LinkedHashMap<String, Object> row = reader.readSheet(null).get(0);
            assertEquals(3, row.size(), "三列都必须在，重名不能互相覆盖");
            assertEquals("1", row.get("名称"));
            assertEquals("2", row.get("列B"));
            assertEquals("3", row.get("名称_2"));
        }
    }

    // ==================== 多工作表 ====================

    @Test
    @DisplayName("能列出全部工作表，且顺序与文件一致")
    void listsAllSheets(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("第一张", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "x")))
                .sheet("第二张", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "y")))
                .sheet("第三张", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "z")))
                .writeTo(dir.resolve("multi.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals(List.of("第一张", "第二张", "第三张"), reader.sheetNames());
        }
    }

    @Test
    @DisplayName("按名字读指定工作表；不填则读第一张")
    void readsNamedSheet(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("表一", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "列"))
                        + XlsxFixture.row(2, XlsxFixture.inlineCell("A2", "来自表一")))
                .sheet("表二", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "列"))
                        + XlsxFixture.row(2, XlsxFixture.inlineCell("A2", "来自表二")))
                .writeTo(dir.resolve("named.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals("来自表二", reader.readSheet("表二").get(0).get("列"));
            assertEquals("来自表一", reader.readSheet(null).get(0).get("列"));
            assertEquals("来自表一", reader.readSheet("  ").get(0).get("列"),
                    "空白名应当等同于「不填」");
        }
    }

    @Test
    @DisplayName("工作表名不存在时报错，并列出所有可用的名字")
    void reportsAvailableSheetsWhenNameMissing(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("销售", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "x")))
                .sheet("库存", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "y")))
                .writeTo(dir.resolve("missing.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            DataSourceException e = assertThrows(DataSourceException.class,
                    () -> reader.readSheet("不存在的表"));
            // 用户打错字时，最想知道的就是到底有哪些表
            assertTrue(e.getMessage().contains("销售"), "提示里应列出可用工作表：" + e.getMessage());
            assertTrue(e.getMessage().contains("库存"), "提示里应列出可用工作表：" + e.getMessage());
        }
    }

    // ==================== 异常输入 ====================

    @Test
    @DisplayName("只有表头、没有数据行时返回空列表而不是报错")
    void headerOnlyReturnsEmpty(@TempDir Path dir) throws IOException {
        Path file = XlsxFixture.builder()
                .sheet("Sheet1", XlsxFixture.row(1, XlsxFixture.inlineCell("A1", "列")))
                .writeTo(dir.resolve("headeronly.xlsx"));

        try (XlsxReader reader = XlsxReader.open(file.toString())) {
            assertEquals(List.of(), reader.readSheet(null));
        }
    }

    @Test
    @DisplayName("打开一个不是 xlsx 的文件时给出明确的错误")
    void rejectsNonXlsxFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("fake.xlsx");
        Files.writeString(file, "这不是一个 ZIP 文件");

        // 不应抛 ZIP 相关的底层异常（用户看不懂），而应是本项目的 DataSourceException
        assertThrows(DataSourceException.class, () -> XlsxReader.open(file.toString()));
    }

    @Test
    @DisplayName("空工具方法不炸：columnIndexOf 对畸形输入返回 -1")
    void columnIndexOfIsTotal(@TempDir Path dir) {
        // 这些不该抛异常，只该返回 -1（调用方据此跳过该单元格）
        assertDoesNotThrow(() -> XlsxReader.columnIndexOf("!!!"));
        assertDoesNotThrow(() -> XlsxReader.columnIndexOf("A"));
        assertEquals(-1, XlsxReader.columnIndexOf("A"));
    }
}
