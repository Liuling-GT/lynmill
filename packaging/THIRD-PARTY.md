# Third-Party Dependencies and Licenses

The TinyQuery distribution bundles a number of third-party components. This file lists what they are, which licenses they use, and what you need to do in different redistribution scenarios.

**Applies to: TinyQuery 1.1.0** | Last updated: 2026-10-04

---

## Quick check: what do I need to do?

| Your scenario | What you need to do |
|---|---|
| Personal use | Nothing |
| Sharing with friends (non-commercial) | Nothing |
| Internal company use | Keep this file on hand for reference |
| Open source distribution | Essentially no extra obligations; including this file is recommended |
| **Closed-source commercial distribution** | **Handle the two dependencies flagged below, and include a third-party license notice** |

> This file is a factual inventory of what the distribution contains and **does not constitute legal advice**. Whether you may distribute as closed source depends on how specific license terms are interpreted; make that call yourself or consult a professional.

---

## 1. What third-party code ships in the distribution

Inside `tinyquery-1.1.0.jar` in the distribution, `BOOT-INF/lib/` contains **53 jars**; together with the bundled Java runtime, the whole application is about 176 MB (Windows) / 181 MB (Linux).

The dependency tree resolves **57 artifacts** in total; the rest are test-only and never reach the distribution.

### 1.1 Direct dependencies (7)

| Component | Version | License | Anything to watch? |
|---|---|---|---|
| `spring-boot-starter-web` | 3.2.0 | Apache-2.0 | No |
| `springdoc-openapi-starter-webmvc-ui` | 2.3.0 | Apache-2.0 | No |
| `mysql-connector-j` | 8.3.0 | **GPLv2 + Universal FOSS Exception** | **Yes**, see section 2 |
| `mariadb-java-client` | 3.3.3 | **LGPL-2.1** | **Yes**, see section 2 |
| `postgresql` | 42.7.3 | BSD-2-Clause | No |
| `sqlite-jdbc` | 3.45.1.0 | Apache-2.0 | No |
| `jna` | 5.19.1 | LGPL-2.1-or-later **or** Apache-2.0 (dual-licensed) | No, take Apache-2.0 |

### 1.2 Transitive dependencies (grouped by license)

**Apache-2.0**

`spring-boot` and its `autoconfigure` / `starter-*` family, `spring-core` `spring-web` `spring-webmvc` `spring-beans` `spring-context` `spring-aop` `spring-expression` `spring-jcl`, `tomcat-embed-core` `tomcat-embed-el` `tomcat-embed-websocket`, `jackson-*` (databind / core / annotations / jdk8 / jsr310 / parameter-names / dataformat-yaml), `snakeyaml`, `log4j-api`, `log4j-to-slf4j`, `jcl-over-slf4j`, `micrometer-observation` `micrometer-commons`, `commons-lang3`, `swagger-core-jakarta` `swagger-annotations-jakarta` `swagger-models-jakarta` `swagger-ui`, `jakarta.validation-api`, `caffeine`, `error_prone_annotations`

**MIT**

`slf4j-api`, `jul-to-slf4j`, `checker-qual`, `waffle-jna`

**Dual-licensed (either works; take the permissive one)**

| Component | Dual license | Take |
|---|---|---|
| `jakarta.annotation-api` | EPL-2.0 or GPL2-w/-CPE | EPL-2.0 |
| `logback-classic`, `logback-core` | EPL-1.0 or LGPL-2.1 | EPL-1.0 |
| `jna-platform` | LGPL-2.1-or-later or Apache-2.0 | Apache-2.0 |

**EDL-1.0** (Eclipse Distribution License, in the same family as BSD 3-Clause)

`jakarta.activation-api`, `jakarta.xml.bind-api`

> "Dual-licensed" is the easiest category to misjudge in an inventory like this: seeing LGPL may suggest copyleft obligations, when in fact the other license is often permissive. Pick either one.

---

## 2. The two dependencies to watch

These only matter for **closed-source commercial distribution**; personal use and open source distribution are unaffected.

### 2.1 MySQL driver (GPLv2 + Universal FOSS Exception)

`mysql-connector-j` is licensed under "GPLv2 with the Universal FOSS Exception".

**This exception applies only if your program is itself free/open source software.** If your program is closed source, whether you can rely on the exception to avoid GPLv2's copyleft reach is a gray area that needs a dedicated review.

If you do not plan to distribute commercially, this is a non-issue.

### 2.2 MariaDB driver (LGPL-2.1)

`mariadb-java-client` uses LGPL-2.1.

**LGPL allows closed-source programs to use it**, provided you meet three obligations:

1. Tell users that the library is covered by LGPL;
2. Provide a way to obtain the library's source code;
3. Allow users to replace the library's version (shipping it as an unmodified jar, without static linking, is normally enough).

This is easier to handle than the MySQL driver, but it must be written into your distribution notice.

### 2.3 Optional risk reduction

MariaDB is wire-compatible with MySQL, so **in theory you could keep only the MySQL driver and drop the MariaDB driver**, eliminating half of the risk above while also removing 1.43 MB of transitive dependencies (see the next section).

However, that changes a database connection path that has been fully verified. If you do go closed-source commercial later, weigh it then.

---

## 3. One unplanned transitive dependency

The distribution contains the following two components, which are not direct dependencies:

| Component | Size |
|---|---|
| `jna-platform-5.13.0.jar` | 1,363,209 bytes |
| `waffle-jna-3.3.0.jar` | 71,735 bytes |

**Source**: `mariadb-java-client` → `waffle-jna` → `jna-platform`.

The MariaDB driver depends on `waffle-jna` for SSPI / integrated authentication on Windows, and that in turn depends on `jna-platform`. It is a runtime dependency, so it is packaged into the fat jar.

**Impact**:

- **No added license risk** — `jna-platform`, like `jna`, is dual-licensed, so take Apache-2.0
- The only cost is roughly 1.43 MB of size
- **No functional conflict** — this project's own credential code declares `advapi32` directly and does not go through `jna-platform`

If size matters, add `<exclusions>` for `mariadb-java-client` in `pom.xml` to drop them; leaving them in does not affect usage.

---

## 4. How to verify this yourself

The list above comes from three mutually corroborating sources, and you can reproduce it:

**1. List the jars actually packaged into the distribution (the most reliable basis)**

```bash
python -c "import zipfile;z=zipfile.ZipFile('jpackage-out/TinyQuery/app/tinyquery-1.1.0.jar');print('\n'.join(n for n in z.namelist() if n.startswith('BOOT-INF/lib/')))"
```

**2. Resolve the full dependency tree**

```bash
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:list \
    -DincludeScope=runtime -DoutputFile=deps.txt
```

**3. Read each license**

- Start with the `<licenses>` field in each dependency's `.pom` (in your local Maven repository)
- When that field is missing, take evidence from `META-INF/LICENSE` / `NOTICE` inside the jar

> **What is actually in the fat jar is what counts.** The `<licenses>` field in a `pom.xml` may be missing (17 of the 57 here had none), may be dual-licensed, or may be absent even though the component is in the package, as with `jna-platform`. Only checking all three layers of evidence is reliable.

---

## 5. Conclusions at a glance

| Scenario | What you need to do |
|---|---|
| Personal use | Nothing |
| Sharing with friends (non-commercial) | Nothing |
| Internal company use | Keep this file on hand for reference |
| Open source distribution | The GPLv2+FOSS exception essentially holds; including this file is recommended |
| Closed-source commercial distribution | Handle 2.1 and 2.2, and include a third-party license notice |
