package com.tinyquery.export;

import com.tinyquery.i18n.Messages;

import java.io.IOException;
import java.io.InputStream;

/**
 * 提供 PDF 用的中文字体资源。
 *
 * <p>字体从 jar 内读取（{@code src/main/resources/fonts}），随程序一起分发，
 * 因此换台电脑打开导出的 PDF 不会缺字 —— 这是「导出报告发给别人」场景的硬要求。
 * 早先考虑过用系统字体，但三平台的系统字体各不相同、且服务端未必拿得到，
 * 排版会不可控。</p>
 *
 * <p>字体是 WenQuanYi Micro Hei 的子集（Apache-2.0），经 pyftsubset 裁到
 * 汉字基本区 + 拉丁 + 数字 + 中文标点，约 3 MB。授权全文见同目录下的
 * {@code LynMillSans-LICENSE.txt}。</p>
 */
public final class PdfFonts {

    /** 字体在 resources 里的路径 */
    private static final String FONT_RESOURCE = "/fonts/LynMillSans-subset.ttf";

    private PdfFonts() {
        // 工具类，不实例化
    }

    /**
     * 打开内嵌字体。
     *
     * <p>每次导出一份，因为 {@link PdfWriter} 在写出过程中会对字体做
     * 「本文件用到了哪些字形」的登记 —— 那是按文件统计的状态，不能跨文件复用。</p>
     *
     * @throws IOException 字体资源缺失时。这种情况说明打包漏了 resources，
     *                     应当直接失败而不是悄悄降级成英文
     */
    public static InputStream open() throws IOException {
        InputStream in = PdfFonts.class.getResourceAsStream(FONT_RESOURCE);
        if (in == null) {
            // 原先硬编码中文，英文模式下会显示中文（单向漏翻）
            throw new IOException(Messages.get("error.fontMissing", FONT_RESOURCE));
        }
        return in;
    }

    /** 字体资源是否存在。供启动自检使用。 */
    public static boolean available() {
        try (InputStream in = PdfFonts.class.getResourceAsStream(FONT_RESOURCE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }
}
