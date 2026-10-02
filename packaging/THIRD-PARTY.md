# TinyQuery 第三方依赖与许可证清单

> 第 22 步（阶段 2 · 第二刀）交付物。
> 生成方式：`mvn dependency:list` 解析真实依赖树 + 逐个读取本地仓库 `.pom` 的
> `<licenses>` 字段 + 读取 jar 内 `META-INF/LICENSE` / `NOTICE` 文件 +
> 直接列出 fat jar 的 `BOOT-INF/lib/` 实际内容。**全部为实测数据，不含凭记忆的推断。**
>
> 生成日期：2026-10-02 ｜ 对应版本：TinyQuery 1.0.0

---

## 0. 一句话结论

**自己用、发给朋友：不需要做任何事。**
若要**闭源商业分发**：`mysql-connector-j`（GPLv2 + FOSS 例外）与
`mariadb-java-client`（LGPL-2.1）这两个是真正的风险点，需要专门处理（见第 3 节）。

> ⚠️ 本文件是**事实清点**，不是法律意见。「能不能闭源分发」的结论请自行确认或咨询专业人士 ——
> 这也是原清单里明确标注「不确定到能给结论的程度」的那一条。

---

## 1. 实际发布包里有什么（实测）

打包产物 `tinyquery-1.0.0.jar` 的 `BOOT-INF/lib/` 下共 **53 个 jar**，
加上 `app/TinyQuery.exe` + `runtime/`（自带 JRE，共 175.5 MB）。

依赖解析结果：**57 个 artifact**（53 个进 fat jar，其余为 test scope 或与打包无关）。

### 1.1 直接依赖（写在 pom.xml 里的 7 个）

| 坐标 | 版本 | 许可证 | 判定 |
|---|---|---|---|
| `org.springframework.boot:spring-boot-starter-web` | 3.2.0 | Apache-2.0 | ✅ 宽松 |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.3.0 | Apache-2.0（读自 jar 内 LICENSE） | ✅ 宽松 |
| `com.mysql:mysql-connector-j` | 8.3.0 | **GPLv2 + Universal FOSS Exception** | ⚠️ 见第 3 节 |
| `org.mariadb.jdbc:mariadb-java-client` | 3.3.3 | **LGPL-2.1** | ⚠️ 见第 3 节 |
| `org.postgresql:postgresql` | 42.7.3 | BSD-2-Clause | ✅ 宽松 |
| `org.xerial:sqlite-jdbc` | 3.45.1.0 | Apache-2.0 | ✅ 宽松 |
| `net.java.dev.jna:jna` | 5.19.1 | LGPL-2.1-or-later **或** Apache-2.0（双许可，我们选 Apache-2.0） | ✅ 宽松 |

### 1.2 传递依赖（会进发布包，按许可证分组）

**Apache-2.0**（宽松，无义务）
`spring-boot` / `spring-boot-autoconfigure` / `spring-boot-starter-*` 系列、
`spring-core` `spring-web` `spring-webmvc` `spring-beans` `spring-context`
`spring-aop` `spring-expression` `spring-jcl`、
`tomcat-embed-core` `tomcat-embed-el` `tomcat-embed-websocket`、
`jackson-*`（databind / core / annotations / jdk8 / jsr310 / parameter-names / dataformat-yaml）、
`snakeyaml`、`log4j-api`、`log4j-to-slf4j`、`jcl-over-slf4j`、
`micrometer-observation` `micrometer-commons`、`commons-lang3`、
`swagger-core-jakarta` `swagger-annotations-jakarta` `swagger-models-jakarta` `swagger-ui`、
`jakarta.validation-api`、`caffeine`、`error_prone_annotations`

> 其中 `springdoc-openapi-starter-common` 与 `springdoc-openapi-starter-webmvc-api`：
> 这两个的 pom **没有 `<licenses>` 字段**，jar 内也没有 LICENSE 条目。
> 归类依据是**同项目的 `springdoc-openapi-starter-webmvc-ui` jar 内含 Apache-2.0 全文**，
> 以及 springdoc 项目对外声明。这三者同版本同项目，实际风险极低，
> 但严格来说这两个是**推断而非实测**，此处如实标注。
> `swagger-*` 三个的 pom 无 licenses 字段，但 jar 内 `META-INF/LICENSE` 是 Apache-2.0 全文（实测）。

**MIT**
`org.slf4j:slf4j-api`、`org.slf4j:jul-to-slf4j`、`checker-qual`、
**`com.github.waffle:waffle-jna`**（读自其 pom 内嵌的 MIT 全文）

**EPL-2.0 或 GPL2-w/-CPE**（双许可，任选 → 取 EPL-2.0）
`jakarta.annotation:jakarta.annotation-api`

**EPL-1.0 或 LGPL-2.1**（双许可，任选 → 取 EPL-1.0）
`logback-classic`、`logback-core`（license 声明在其父 pom `logback-parent` 里）

**EDL-1.0**（Eclipse Distribution License，BSD 三条款风格）
`jakarta.activation-api`、`jakarta.xml.bind-api`

**LGPL-2.1-or-later 或 Apache-2.0**（双许可）
`net.java.dev.jna:jna-platform`

提示：`jakarta.annotation-api` 与 `logback` 都是**双许可**，取宽松的那个即可，
不构成 copyleft 义务。这是本次清点里容易误判的一类。

---

## 2. ⚠️ 实测发现：发布包里有两个「计划外」的依赖

清单（第 14 步 +「凭据管理器与 JNA」表）记录写着：

> 「实现只引 jna 一个包（1.9MB），**不引 jna-platform**」
> 「要不要 jna-platform：**不要**」

**实测与这条记录不符。** 发布包里确实存在：

| 包 | 大小 | 来源 |
|---|---|---|
| `jna-platform-5.13.0.jar` | 1,363,209 字节 | **传递依赖**，非我们主动引入 |
| `waffle-jna-3.3.0.jar` | 71,735 字节 | **传递依赖** |

**来源链（已实测确认）**：
`org.mariadb.jdbc:mariadb-java-client:3.3.3` → `com.github.waffle:waffle-jna:3.3.0`
→ `net.java.dev.jna:jna-platform:5.13.0`（在 waffle-jna 的 pom 里明写该 artifactId）。

**为什么会这样**：MariaDB 驱动为了在 Windows 上做 SSPI / 集成认证，
依赖了 waffle-jna，而 waffle-jna 又依赖 jna-platform。
`jna-platform` 是**运行时**依赖（compile scope），所以会被打进 fat jar。

**结论与影响**：
- 我们「只引 jna」在**编译期**是对的，但**打包后并不成立** —— jna-platform 照样进包。
- 好消息：`jna-platform` 与 `jna` 一样是**双许可 LGPL-2.1-or-later 或 Apache-2.0**，
  取 Apache-2.0 即可，**不增加许可证风险**。
- 代价只是多约 1.43 MB 体积，且**当前不会造成功能冲突**（我们自己的凭据代码走
  `advapi32` 直接声明，不依赖 jna-platform）。
- 已在本文件记录事实，**未改动 pom**（要剔除需显式 `<exclusions>`，
  且属于分发优化、不在第 20–22 步范围）。

---

## 3. 两个真正的许可风险点

### 3.1 `com.mysql:mysql-connector-j:8.3.0` — GPLv2 + Universal FOSS Exception

- pom 内 `<licenses>` 原文：`The GNU General Public License, v2 with Universal FOSS Exception, v1.0`
- **FOSS 例外的适用前提是「你的程序本身也是自由/开源软件」**。
- TinyQuery 目前**未开源** → 闭源分发时，GPLv2 的传染性是否被例外豁免，
  **不能想当然认定没问题**，属于需要专门确认的灰色地带。
- 若不打算商业分发（自己用 / 发朋友），**不构成问题**。

### 3.2 `org.mariadb.jdbc:mariadb-java-client:3.3.3` — LGPL-2.1

- pom 内 `<licenses>` 原文：`LGPL-2.1`（无 URL）
- LGPL **允许**闭源程序使用，义务是：
  1. 告知用户该库受 LGPL 保护；
  2. 提供该库源码的获取途径；
  3. 允许用户替换该库版本（原样以 jar 形式打包一般可满足，未静态链接）。
- 相比 MySQL 驱动，这一条**更好处理**，但需要写进分发说明。

### 3.3 一个可选的减风险动作（不建议现在做）

MariaDB 与 MySQL 协议兼容，**理论上可以删掉 MariaDB 驱动、只用 MySQL 驱动**，
从而同时消除 3.1 与 3.2 中的一半风险，并顺带去掉 waffle-jna + jna-platform（省约 1.43 MB）。

**但第 14 步的端到端验证正是用 MariaDB（192.168.3.25 / demo.social_media 8000 行）跑通的**，
删驱动会动到已验证的链路。**建议：记录在案，发布前再决策。**

---

## 4. 复核方法（可复现）

```powershell
# 1. 解析真实依赖树并落盘
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:list `
    -DincludeScope=runtime -DoutputFile=deps.txt

# 2. 读每个依赖 pom 的许可证字段（本地仓库）
#    见 C:\Users\Lenovo\AppData\Local\Temp\tq\licenses.py

# 3. pom 里没写 licenses 的，从 jar 内 META-INF/LICENSE 找证据
#    见 C:\Users\Lenovo\AppData\Local\Temp\tq\jarlic.py

# 4. 列出发布包里实际打进去的 jar（最终依据）
python -c "import zipfile;z=zipfile.ZipFile(r'jpackage-out\TinyQuery\app\tinyquery-1.0.0.jar');print('\n'.join(n for n in z.namelist() if n.startswith('BOOT-INF/lib/')))"
```

> 经验：**以 fat jar 内实际内容为准**。
> pom 的 `<licenses>` 字段可能缺失（本次 57 个里有 17 个缺）、可能是双许可、
> 也可能像 jna-platform 一样「没写却在包里」——三层证据互相印证才可靠。

---

## 5. 结论速查

| 场景 | 需要做什么 |
|---|---|
| 自己用 | 什么都不用做 |
| 发给朋友（非商业） | 什么都不用做 |
| 公司内部使用 | 建议保留本清单备查 |
| **闭源商业分发** | 处理 3.1（MySQL GPLv2+FOSS）与 3.2（MariaDB LGPL）；补一份第三方许可声明 |
| 开源分发 | GPLv2+FOSS 例外基本成立，但仍建议附上本清单 |
