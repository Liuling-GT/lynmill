package com.tinyquery.api;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载响应头的单元测试。
 *
 * <p>守的是一个很隐蔽的坑：{@code Content-Disposition} 里的 {@code filename=}
 * 只能是 ASCII。中文数据源名（例如「成绩单」）被原样塞进去以后，
 * Tomcat 判定响应头非法，会<b>把整个 Content-Disposition 丢掉</b> ——
 * 前端于是拿不到文件名，只能退回默认名，而服务端不报任何错。
 * 单看服务端日志什么都查不出来，只有对着真实 HTTP 响应才发现头不见了。</p>
 */
class DownloadHeaderTest {

    // 通过反射调私有方法，避免为了测试把方法放宽可见性
    private static String[] buildHeaders(String fileName) throws Exception {
        QueryController c = new QueryController(null);
        Method m = QueryController.class
                .getDeclaredMethod("setDownloadHeaders", HttpServletResponse.class, String.class);
        m.setAccessible(true);
        CapturingResponse resp = new CapturingResponse();
        m.invoke(c, resp, fileName);
        return new String[]{resp.value};
    }

    private static String asciiFileName(String fileName) throws Exception {
        QueryController c = new QueryController(null);
        Method m = QueryController.class.getDeclaredMethod("asciiFileName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(c, fileName);
    }

    /** 只记录 header 的极简响应对象 */
    private static class CapturingResponse implements HttpServletResponse {
        String value;

        @Override
        public void setHeader(String name, String v) {
            if ("Content-Disposition".equalsIgnoreCase(name)) {
                this.value = v;
            }
        }

        @Override
        public void addHeader(String name, String v) {
            setHeader(name, v);
        }

        // ---- 以下方法本测试用不到 ----
        @Override public void addCookie(jakarta.servlet.http.Cookie cookie) { }
        @Override public boolean containsHeader(String name) { return false; }
        @Override public String encodeURL(String url) { return url; }
        @Override public String encodeRedirectURL(String url) { return url; }
        @Override public void sendError(int sc, String msg) { }
        @Override public void sendError(int sc) { }
        @Override public void sendRedirect(String location) { }
        @Override public void setDateHeader(String name, long date) { }
        @Override public void addDateHeader(String name, long date) { }
        @Override public void setIntHeader(String name, int value) { }
        @Override public void addIntHeader(String name, int value) { }
        @Override public void setStatus(int sc) { }
        @Override public int getStatus() { return 200; }
        @Override public String getHeader(String name) { return null; }
        @Override public java.util.Collection<String> getHeaders(String name) { return java.util.List.of(); }
        @Override public java.util.Collection<String> getHeaderNames() { return java.util.List.of(); }
        @Override public String getCharacterEncoding() { return "UTF-8"; }
        @Override public String getContentType() { return null; }
        @Override public jakarta.servlet.ServletOutputStream getOutputStream() { return null; }
        @Override public java.io.PrintWriter getWriter() { return new java.io.PrintWriter(new java.io.StringWriter()); }
        @Override public void setCharacterEncoding(String charset) { }
        @Override public void setContentLength(int len) { }
        @Override public void setContentLengthLong(long len) { }
        @Override public void setContentType(String type) { }
        @Override public void setBufferSize(int size) { }
        @Override public int getBufferSize() { return 0; }
        @Override public void flushBuffer() { }
        @Override public void resetBuffer() { }
        @Override public boolean isCommitted() { return false; }
        @Override public void reset() { }
        @Override public void setLocale(java.util.Locale loc) { }
        @Override public java.util.Locale getLocale() { return java.util.Locale.getDefault(); }
    }

    @Test
    void 中文文件名不会产生非ASCII的头值() throws Exception {
        String[] h = buildHeaders("成绩单_20261015_200000.xlsx");
        String v = h[0];
        assertTrue(v.startsWith("attachment; "), "头值前缀不对: " + v);

        // filename= 那一段必须是纯 ASCII，否则 Tomcat 会丢掉整个头
        int i = v.indexOf("filename=\"");
        int j = v.indexOf('"', i + 10);
        String plain = v.substring(i + 10, j);
        assertTrue(plain.chars().allMatch(c -> c < 0x80),
                "filename= 里出现了非 ASCII 字符，响应头会被丢弃: " + plain);
        assertFalse(plain.contains("?"), "出现了无法表示的字符: " + plain);
    }

    @Test
    void 中文文件名完整保留在文件名星号写法里() throws Exception {
        String[] h = buildHeaders("成绩单_20261015_200000.xlsx");
        String v = h[0];
        assertTrue(v.contains("filename*=UTF-8''"), "缺少 RFC 5987 写法: " + v);

        // 解回来应等于原名
        String enc = v.substring(v.indexOf("filename*=UTF-8''") + 17);
        String decoded = java.net.URLDecoder.decode(enc, StandardCharsets.UTF_8);
        assertEquals("成绩单_20261015_200000.xlsx", decoded,
                "RFC 5987 段解码后应还原出中文原名");
    }

    @Test
    void 空格编码成百分号20而不是加号() throws Exception {
        // URLEncoder 默认按 form 语义把空格编成 '+'，但在 RFC 5987 与
        // 文件名场景里 '+' 是字面加号，会把文件名改错
        String[] h = buildHeaders("my report 2026.xlsx");
        String v = h[0];
        assertTrue(v.contains("%20"), "空格应编码为 %20: " + v);
        assertFalse(v.contains("+"), "不应出现 + 号: " + v);
    }

    @Test
    void 纯ASCII文件名保持可读() throws Exception {
        String[] h = buildHeaders("sales_20261015.csv");
        String v = h[0];
        assertTrue(v.contains("filename=\"sales_20261015.csv\""),
                "纯 ASCII 文件名应原样保留: " + v);
        assertEquals("sales_20261015.csv", asciiFileName("sales_20261015.csv"));
    }

    @Test
    void 引号与非法字符被安全处理() throws Exception {
        // 文件名里的引号会提前闭合 filename="..." 这个带引号的串，
        // 进而破坏整个头；必须转义掉
        String out = asciiFileName("a\"b\\c:d.xlsx");
        assertFalse(out.contains("\""), "双引号应被转义: " + out);
        assertFalse(out.contains("\\"), "反斜杠应被转义: " + out);
        // 冒号等不属于 tspecials 的字符可以保留，但至少不能破坏语法
        assertTrue(out.startsWith("a"), out);
    }

    @Test
    void 三种扩展名都带正确的后缀() throws Exception {
        // 前端靠这个后缀判断文件类型，不能丢
        for (String ext : new String[]{"csv", "xlsx", "pdf"}) {
            String[] h = buildHeaders("数据_" + ext + "." + ext);
            String v = h[0];
            int i = v.indexOf("filename=\"");
            int j = v.indexOf('"', i + 10);
            assertTrue(v.substring(i + 10, j).endsWith("." + ext),
                    ext + " 的后缀丢失: " + v);
        }
    }
}
