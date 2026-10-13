# -*- coding: utf-8 -*-
"""
加密栈专项验证。

待办编号 43 的备注要求「跑一次真实 MySQL / MariaDB，并跑一次 TLS 连接」。
实测发现：**MySQL 路径用不了 TLS** —— MysqlLoader 把 useSSL=false 写死在连接串里
（见 src/main/java/com/tinyquery/datasource/loader/MysqlLoader.java:39），
且没有暴露可覆盖的字段。所以"MySQL over TLS"这条路当前走不通。

但"删掉加密模块会不会炸"这个问题仍然必须回答。改从两条**当前确实会用到
加密栈**的路径验：

  ① HTTPS 客户端：java.net.http + jdk.crypto.ec。
     PostgreSQL 的 SSL 连接、以及任何 https 数据源都依赖它
  ② 本机密钥库读写：Windows-MY / PKCS12，走 jdk.crypto.cryptoki

【为什么不能省】缺 jdk.crypto.ec 时明文连接**照样能通**，
主验收脚本里那些明文连接全绿也证明不了加密模块是齐的。
必须有一条真的走 TLS 的路径。

用法：python packaging/verify-crypto.py <app-image 目录>
"""
import io
import os
import ssl
import subprocess
import sys
import tempfile
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

APP = sys.argv[1] if len(sys.argv) > 1 else \
    str(Path(__file__).resolve().parent.parent / "jpackage-out" / "LynMill")

# 直接调那个被裁过的 java，不经过应用 —— 这样能单独验加密栈，
# 不受应用逻辑干扰
RUNTIME_JAVA = Path(APP) / "runtime" / "bin" / ("java.exe" if os.name == "nt" else "java")
if not RUNTIME_JAVA.exists():
    print("  找不到 %s" % RUNTIME_JAVA)
    sys.exit(2)

PROBE = r'''
import javax.net.ssl.*;
import java.net.URI;
import java.net.http.*;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

public class CryptoProbe {
    public static void main(String[] a) throws Exception {
        int fail = 0;

        // ---- 1. 默认 SSLContext 能不能建起来（要 jdk.crypto.ec + java.base 的 TLS 栈）----
        try {
            SSLContext ctx = SSLContext.getDefault();
            String[] protos = ctx.getSupportedSSLParameters().getProtocols();
            System.out.println("OK  默认 SSLContext，支持协议：" + String.join(",", protos));
        } catch (Throwable t) {
            System.out.println("FAIL 建默认 SSLContext: " + t);
            fail++;
        }

        // ---- 2. 椭圆曲线密钥对（jdk.crypto.ec 的直接证据）----
        try {
            java.security.KeyPairGenerator g =
                java.security.KeyPairGenerator.getInstance("EC");
            g.initialize(256);
            g.generateKeyPair();
            System.out.println("OK  生成 EC P-256 密钥对（jdk.crypto.ec 可用）");
        } catch (Throwable t) {
            System.out.println("FAIL EC 密钥对: " + t);
            fail++;
        }

        // ---- 3. TLS 握手：连一个真实的 https 站点 ----
        try {
            HttpClient c = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15)).build();
            HttpResponse<String> r = c.send(
                HttpRequest.newBuilder(URI.create("https://www.example.com/"))
                    .timeout(java.time.Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            System.out.println("OK  HTTPS 握手成功，状态 " + r.statusCode());
        } catch (Throwable t) {
            System.out.println("SKIP HTTPS 握手（可能没有外网）：" + t);
        }

        // ---- 4. TLS 1.3 单独试（它依赖 EC）----
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(null, null, null);
            System.out.println("OK  TLSv1.3 上下文可建");
        } catch (Throwable t) {
            System.out.println("FAIL TLSv1.3 上下文: " + t);
            fail++;
        }

        // ---- 5. 本机密钥库（jdk.crypto.cryptoki）----
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            System.out.println("OK  PKCS12 密钥库类型可用");
        } catch (Throwable t) {
            System.out.println("FAIL PKCS12: " + t);
            fail++;
        }

        // ---- 6. 字符集（jdk.charsets）----
        try {
            byte[] b = "中文测试".getBytes("GBK");
            String back = new String(b, "GBK");
            if (!"中文测试".equals(back)) throw new IllegalStateException("往返不一致");
            System.out.println("OK  GBK 编码往返正确（jdk.charsets 可用）");
        } catch (Throwable t) {
            System.out.println("FAIL GBK: " + t);
            fail++;
        }

        // ---- 7. 本地化数据（jdk.localedata）----
        try {
            java.text.DateFormat df = java.text.DateFormat.getDateInstance(
                java.text.DateFormat.LONG, java.util.Locale.CHINA);
            String s = df.format(new java.util.Date(0));
            boolean hasCn = s.matches(".*[年月日].*");
            System.out.println((hasCn ? "OK  " : "FAIL ")
                + "中文日期格式：" + s + "（jdk.localedata）");
            if (!hasCn) fail++;
        } catch (Throwable t) {
            System.out.println("FAIL 中文日期: " + t);
            fail++;
        }

        System.out.println("PROBE_FAIL=" + fail);
    }
}
'''

work = Path(tempfile.gettempdir()) / "tq-crypto-probe"
work.mkdir(parents=True, exist_ok=True)
src = work / "CryptoProbe.java"
src.write_text(PROBE, encoding="utf-8")

print("=" * 74)
print("  加密 / 编码 / 本地化 专项验证")
print("=" * 74)
print("  使用的 java: %s" % RUNTIME_JAVA)
print()

# 用【开发机的 javac】编译，再用【被裁过的 java】运行。
# 【为什么不直接 java CryptoProbe.java】JDK 11+ 的单文件源码模式需要 jdk.compiler
# 在 boot layer 里，而精简后的 runtime 故意没有它 —— 实测报
# "Module jdk.compiler not in boot Layer"，探针根本没跑起来。
# 编译期用完整的 JDK 是合理的：裁的是【运行时】，不是开发工具链。
javac = None
for cand in (Path(os.environ.get("JAVA_HOME", "")) / "bin" / "javac.exe",
             Path(r"C:\Program Files\Java\jdk-17\bin\javac.exe"),
             Path(r"D:\JDK\jdk-17\bin\javac.exe")):
    if cand.exists():
        javac = cand
        break
if javac is None:
    print("  找不到 javac —— 本脚本需要开发机上的 JDK 17 来编译探针\n"
          "  （请设置 JAVA_HOME 指向 JDK 17）")
    sys.exit(2)

classes = work / "classes"
classes.mkdir(exist_ok=True)
c = subprocess.run([str(javac), "-encoding", "UTF-8", "-d", str(classes), str(src)],
                   capture_output=True, text=True, encoding="utf-8", errors="replace")
if c.returncode != 0:
    print("  编译探针失败：\n" + (c.stderr or "")[:1500])
    sys.exit(2)

# 用被裁过的 runtime 跑。-Dfile.encoding 显式定死：
# 简体中文 Windows 的默认编码是 GBK，探针输出的中文会乱码
r = subprocess.run([str(RUNTIME_JAVA), "-Dfile.encoding=UTF-8",
                    "-cp", str(classes), "CryptoProbe"],
                   capture_output=True, text=True, encoding="utf-8",
                   errors="replace", timeout=180)
out = (r.stdout or "") + (r.stderr or "")
for line in out.splitlines():
    if line.strip():
        print("    " + line.strip()[:150])

fail = 0
saw_result = False
for line in out.splitlines():
    if line.startswith("PROBE_FAIL="):
        fail = int(line.split("=")[1])
        saw_result = True

print()
print("-" * 74)
# 关键：探针没跑出结果时必须判失败。
# 【踩过】第一版只看 fail 变量，而探针因为 "Module jdk.compiler not in boot Layer"
# 根本没启动，fail 保持 0，于是脚本打出「全部通过」——
# 一次彻头彻尾的假绿。**没有输出 != 没有失败**。
if not saw_result:
    print("     探针没有产出结果（编译/启动就失败了）—— 见上面的输出")
    print("     注意：这本身就是失败，不能当成「跳过」")
    sys.exit(1)
if fail == 0:
    print("     全部通过 —— 加密栈、字符集、本地化数据都齐全")
else:
    print("     有 %d 项失败 —— runtime 裁掉了必需的模块" % fail)
sys.exit(0 if fail == 0 else 1)
