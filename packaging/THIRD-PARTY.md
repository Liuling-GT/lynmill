# 第三方依赖与许可证

TinyQuery 的发布包里打包了若干第三方组件。本文件列出它们分别是什么、采用什么许可证，以及在不同分发场景下你需要做什么。

**对应版本：TinyQuery 1.0.0** ｜ 最后更新：2026-10-04

---

## 快速判断：我需要做什么

| 你的使用场景 | 需要做的事 |
|---|---|
| 自己使用 | 不需要做任何事 |
| 分享给朋友（非商业） | 不需要做任何事 |
| 公司内部使用 | 建议保留本文件备查 |
| 开源分发 | 基本没有额外义务，建议随附本文件 |
| **闭源商业分发** | **需要处理下面「需要注意的两个依赖」，并附一份第三方许可声明** |

> 本文件是对发布包内容的事实清点，**不构成法律意见**。是否可闭源分发涉及对具体许可证条款的解读，请自行判断或咨询专业人士。

---

## 一、发布包里有哪些第三方代码

发布包中的 `tinyquery-1.0.0.jar` 里，`BOOT-INF/lib/` 下共 **53 个 jar**；连同自带的 Java 运行时，整个应用约 176 MB（Windows）/ 181 MB（Linux）。

依赖树共解析出 **57 个 artifact**，其余为仅测试期使用、不会进入发布包的部分。

### 1.1 直接依赖（7 个）

| 组件 | 版本 | 许可证 | 是否需要留意 |
|---|---|---|---|
| `spring-boot-starter-web` | 3.2.0 | Apache-2.0 | 否 |
| `springdoc-openapi-starter-webmvc-ui` | 2.3.0 | Apache-2.0 | 否 |
| `mysql-connector-j` | 8.3.0 | **GPLv2 + Universal FOSS Exception** | **是**，见第二节 |
| `mariadb-java-client` | 3.3.3 | **LGPL-2.1** | **是**，见第二节 |
| `postgresql` | 42.7.3 | BSD-2-Clause | 否 |
| `sqlite-jdbc` | 3.45.1.0 | Apache-2.0 | 否 |
| `jna` | 5.19.1 | LGPL-2.1-or-later **或** Apache-2.0（双许可） | 否，取 Apache-2.0 即可 |

### 1.2 传递依赖（按许可证分组）

**Apache-2.0**

`spring-boot` 及其 `autoconfigure` / `starter-*` 系列、`spring-core` `spring-web` `spring-webmvc` `spring-beans` `spring-context` `spring-aop` `spring-expression` `spring-jcl`、`tomcat-embed-core` `tomcat-embed-el` `tomcat-embed-websocket`、`jackson-*`（databind / core / annotations / jdk8 / jsr310 / parameter-names / dataformat-yaml）、`snakeyaml`、`log4j-api`、`log4j-to-slf4j`、`jcl-over-slf4j`、`micrometer-observation` `micrometer-commons`、`commons-lang3`、`swagger-core-jakarta` `swagger-annotations-jakarta` `swagger-models-jakarta` `swagger-ui`、`jakarta.validation-api`、`caffeine`、`error_prone_annotations`

**MIT**

`slf4j-api`、`jul-to-slf4j`、`checker-qual`、`waffle-jna`

**双许可（两种都可以，取宽松的那种即可）**

| 组件 | 双许可 | 取用 |
|---|---|---|
| `jakarta.annotation-api` | EPL-2.0 或 GPL2-w/-CPE | EPL-2.0 |
| `logback-classic`、`logback-core` | EPL-1.0 或 LGPL-2.1 | EPL-1.0 |
| `jna-platform` | LGPL-2.1-or-later 或 Apache-2.0 | Apache-2.0 |

**EDL-1.0**（Eclipse Distribution License，与 BSD 三条款同类）

`jakarta.activation-api`、`jakarta.xml.bind-api`

> 「双许可」是这类清点里最容易误判的一类：看到 LGPL 就以为有 copyleft 义务，其实另一条许可往往是宽松的，任选其一即可。

---

## 二、需要注意的两个依赖

只有在**闭源商业分发**时才需要处理；自己用或开源分发不受影响。

### 2.1 MySQL 驱动（GPLv2 + Universal FOSS Exception）

`mysql-connector-j` 的许可证是「GPLv2 附通用自由开源软件例外」。

**这个例外的适用前提是：你的程序本身也是自由/开源软件。** 如果你的程序闭源，能否依靠该例外豁免 GPLv2 的传染性，属于需要专门确认的灰色地带。

不打算商业分发的话，这一条不构成问题。

### 2.2 MariaDB 驱动（LGPL-2.1）

`mariadb-java-client` 采用 LGPL-2.1。

**LGPL 允许闭源程序使用它**，条件是履行三项义务：

1. 告知使用者该库受 LGPL 保护；
2. 提供获取该库源码的途径；
3. 允许使用者替换该库的版本（以未修改的 jar 形式打包、不做静态链接，通常即可满足）。

相比 MySQL 驱动，这一条更好处理，但需要写进分发说明。

### 2.3 可选的减风险做法

MariaDB 与 MySQL 协议兼容，因此**理论上可以只保留 MySQL 驱动、删掉 MariaDB 驱动**，从而同时消除上面两条风险中的一半，并顺带去掉 1.43 MB 的传递依赖（见下一节）。

但这会改动已经被完整验证过的数据库连接链路。如果将来确实要做闭源分发，再据此权衡。

---

## 三、一个计划之外的传递依赖

发布包里存在下面两个组件，它们并不是直接引入的：

| 组件 | 大小 |
|---|---|
| `jna-platform-5.13.0.jar` | 1,363,209 字节 |
| `waffle-jna-3.3.0.jar` | 71,735 字节 |

**来源**：`mariadb-java-client` → `waffle-jna` → `jna-platform`。

MariaDB 驱动为了在 Windows 上支持 SSPI / 集成认证而依赖 `waffle-jna`，后者又依赖 `jna-platform`。它是运行时依赖，所以会被打进 fat jar。

**影响**：

- 许可证方面**没有新增风险** —— `jna-platform` 与 `jna` 一样是双许可，取 Apache-2.0 即可
- 代价只是大约 1.43 MB 的体积
- **不会造成功能冲突** —— 本项目自己的凭据代码直接声明 `advapi32`，不经过 `jna-platform`

如果在意体积，可以在 `pom.xml` 里对 `mariadb-java-client` 加 `<exclusions>` 剔除它们；不剔除也不影响使用。

---

## 四、如何自行复核

上面的清单来自三个互相印证的信息源，你也可以自己复现：

**1. 列出实际打进发布包的 jar（最可靠的依据）**

```bash
python -c "import zipfile;z=zipfile.ZipFile('jpackage-out/TinyQuery/app/tinyquery-1.0.0.jar');print('\n'.join(n for n in z.namelist() if n.startswith('BOOT-INF/lib/')))"
```

**2. 解析完整依赖树**

```bash
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:list \
    -DincludeScope=runtime -DoutputFile=deps.txt
```

**3. 逐个读许可证**

- 先看各依赖 `.pom` 里的 `<licenses>` 字段（在本地 Maven 仓库中）
- 该字段缺失时，从 jar 内的 `META-INF/LICENSE` / `NOTICE` 取证据

> **以 fat jar 里的实际内容为准。** `pom.xml` 的 `<licenses>` 字段可能缺失（本次 57 个里有 17 个没有）、可能是双许可，也可能像 `jna-platform` 那样「没声明却在包里」。三层证据都看过才可靠。

---

## 五、结论速查

| 场景 | 需要做什么 |
|---|---|
| 自己用 | 什么都不用做 |
| 发给朋友（非商业） | 什么都不用做 |
| 公司内部使用 | 建议保留本文件备查 |
| 开源分发 | GPLv2+FOSS 例外基本成立，建议随附本文件 |
| 闭源商业分发 | 处理 2.1、2.2 两条，并附一份第三方许可声明 |
