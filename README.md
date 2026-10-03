# TinyQuery

**本地零运维的多数据源 SQL 查询引擎。** 把 CSV / Excel 导出的表格、MySQL、PostgreSQL、SQLite 拖进来，直接用 SQL 查，不需要装数据库、不需要写代码。

单个可执行程序，自带运行时，免安装 JDK。数据不出本机。

---

## 目录

- [这是什么](#这是什么)
- [功能特性](#功能特性)
- [支持的数据源](#支持的数据源)
- [下载与运行](#下载与运行)
- [⚠️ 首次打开提示（程序未签名）](#-首次打开提示程序未签名)
- [快速上手](#快速上手)
- [SQL 支持范围与限制](#sql-支持范围与限制)
- [数据存放位置](#数据存放位置)
- [安全说明](#安全说明)
- [从源码构建](#从源码构建)
- [已知问题](#已知问题)
- [第三方依赖与许可](#第三方依赖与许可)

---

## 这是什么

平时想查一个 CSV 文件，要么用 Excel 打开（几十万行就卡死），要么为了跑一句 SQL 去装 MySQL 再导数据。TinyQuery 想做的是把这件事缩短到"双击 → 拖文件 → 写 SQL"。

它的做法是把数据加载进内存里的**列式存储**，用**向量化**方式执行查询。所以对扫描类查询（`count(*)`、条件过滤、聚合）比逐行处理快得多。

**适合**：百万行以内、单机、临时或反复分析同一批数据。
**不适合**：TB 级数据、多用户并发、需要事务写入的场景 —— 那些应该用 ClickHouse / DuckDB / 真正的数据库。

---

## 功能特性

- **多种数据源统一成 SQL 表**：CSV、MySQL/MariaDB、PostgreSQL、SQLite 注册后都能用同一套 SQL 查，甚至可以在结果之上继续分析
- **列式存储 + 向量化执行**：扫描与聚合快，适合宽表与大批量过滤
- **结果分页**：默认每页 50 行，大结果集不会一次塞满浏览器
- **图表**：查询结果可以直接画成折线/柱状/饼图，聚合在服务端算
- **导出 CSV**：带 UTF-8 BOM，Excel 打开中文列名不乱码
- **数据源持久化**：注册过的数据源写在 `datasources.json` 里，重开程序自动恢复
- **密码可选保存**：勾选后存进**系统凭据管理器**（Windows 凭据管理器 / macOS 钥匙串），不写进配置文件
- **中文友好**：CSV 自动探测编码（UTF-8 / GBK）、剥离 BOM、支持中文路径与中文文件名
- **只监听本机**：绑定 `127.0.0.1`，并带一次性访问令牌，局域网内其它机器访问不到

---

## 支持的数据源

| 类型 | 需要填 | 说明 |
|---|---|---|
| **CSV 文件** | 文件路径 | 可配编码、分隔符、注释前缀、引号感知 |
| **SQLite 文件** | 数据库文件路径 + 表名 | 一个文件里的每张表注册成一个数据源 |
| **MySQL / MariaDB** | 主机、端口、库名、表名、用户名、密码 | 加载时执行 `SELECT *` 全表读取，大表注意内存 |
| **PostgreSQL** | 主机、端口、库名、schema、表名、用户名、密码 | 同上；标识符不加引号时会折叠成小写，请用小写书写 |

> **注册时的"名字"就是 SQL 里的表名。** 例如注册时取名 `sales`，之后写 `select * from sales`。

---

## 下载与运行

程序是**免安装**的：解压出来直接运行，不需要预先装 Java（运行时已经打包在里面）。

### Windows

1. 解压 `TinyQuery-1.0.0-win.zip`
2. 双击里面的 `TinyQuery.exe`

> 不要只把 `TinyQuery.exe` 单独拖出来 —— 它要靠同目录下的 `app\` 和 `runtime\` 才能启动。

### macOS

1. 打开 `TinyQuery-1.0.0.dmg`，把 TinyQuery 拖进「应用程序」
2. 首次打开见下一节的放行方法

> 当前提供的是 **Apple Silicon（M1~M4）** 版本。Intel Mac 请从源码构建，或用 jar 直接运行（见[从源码构建](#从源码构建)）。

### Linux

**方式一：压缩包（推荐，所有发行版通用）**

```bash
tar -xzf TinyQuery-1.0.0-linux-x86_64.tar.gz
./TinyQuery/bin/TinyQuery
```

**方式二：安装包**

```bash
sudo dpkg -i tinyquery_1.0.0-1_amd64.deb     # Debian / Ubuntu 系
sudo rpm -i tinyquery-1.0.0-1.x86_64.rpm     # Fedora / RHEL 系
```

装完可以从应用菜单启动。

> `.deb` / `.rpm` 需要系统里有**中文字体**，否则控制窗口与托盘菜单里的中文会显示成方块。装法：`sudo apt install fonts-noto-cjk`。

---

## ⚠️ 首次打开提示（程序未签名）

**这是正常现象，不是文件损坏，也不是病毒。**

本项目是开源的非盈利项目，没有购买代码签名证书（Windows 证书与 Apple Developer 账号都要钱）。所以系统会提示"未知发布者"。按下面的方法放行一次即可，之后不再提示。

### Windows：SmartScreen 拦了

看到「Windows 已保护你的电脑」时：

> 点 **更多信息** → 点 **仍要运行**

**如果是从浏览器下载的**（网盘、QQ、邮件附件等），一定会弹这个。
**如果是用 U 盘或局域网拷贝过去的**，通常不会弹 —— 因为本地拷贝不带"网络来源标记"。

### macOS：Gatekeeper 拦了

提示「无法打开，因为无法验证开发者」时，**二选一**：

- **右键点图标 → 选「打开」→ 再点「打开」**（直接双击不行）
- 或执行一次：
  ```bash
  xattr -dr com.apple.quarantine /Applications/TinyQuery.app
  ```

### Linux

一般不会拦。若提示权限不足：

```bash
chmod +x TinyQuery/bin/TinyQuery
```

---

## 快速上手

1. 启动程序，会自动打开浏览器进入界面（没自动打开就手动访问日志里打印的地址）
2. 左侧「数据源」→「新增」，选类型、填参数、起个名字
3. 在查询框里写 SQL，点执行

**示例**：假设注册了一个叫 `sample` 的 CSV（列：`name, age, city, salary`）

```sql
select * from sample                       -- 全部
select name, age from sample where age > 25 -- 过滤
select * from sample order by salary desc limit 10   -- 排序取前 10
select count(*), avg(salary) from sample   -- 聚合
select city, count(*) from sample where age > 20   -- 注意：无 GROUP BY，见下方限制
```

> **注意：表名不要加引号。** 反引号、双引号、方括号都会被当成表名的一部分，报「数据源不存在」。写 `sample`，不要写 `` `sample` ``。

---

## SQL 支持范围与限制

这是一个**轻量查询引擎**，不是完整的 SQL 数据库。支持的是常用子集。

### 支持

| 类别 | 语法 |
|---|---|
| 投影 | `select a, b`、`select *`、`select a as 别名` |
| 过滤 | `where`、`and` / `or` / `not`、`=` `!=` `>` `>=` `<` `<=` |
| 集合 | `in (...)`、`between a and b`、`is null` / `is not null` |
| 排序 | `order by 单列 [asc\|desc]` |
| 分页 | `limit n`（服务端另有 page / pageSize 分页） |
| 聚合 | `count(*)`、`count(列)`、`sum`、`avg`、`min`、`max` |

### 不支持（会报错）

| 语法 | 说明 |
|---|---|
| `group by` | **有意不做**。当前聚合是整体聚合，不做分组 |
| `order by a, b` | 只支持单列排序 |
| `limit n offset m` / `limit m, n` | 只支持 `limit n` |
| `where (a > 1)` | **条件外层的括号不支持**，会报 `Unknown column:(a` |
| `select distinct` | 不支持 |
| `join` / 子查询 / `union` | 不支持 |
| 表名加引号 | `` `t` ``、`"t"`、`[t]` 都不行 |

### ⚠️ 报错信息可能误导

目前有一类已知的报错不准确：**写出不支持的语法时，错误信息可能说「数据源不存在」**，而实际上数据源是好的，是不支持那个语法。

例如：

```
select city, count(*) from sample group by city
→ 报：数据源不存在：sample group by city
```

它把 `group by city` 当成了表名的一部分。**遇到「数据源不存在」但名字看着没问题时，先检查是不是用了上面表格里不支持的语法。**

（根因是解析器按空格切分、对不支持的子句没有提前拦截，已在待办清单里记为第 1 条。）

---

## 数据存放位置

程序自己的东西（数据源配置、日志、锁文件）放在**用户数据目录**，不在程序目录里：

| 平台 | 路径 |
|---|---|
| Windows | `%APPDATA%\TinyQuery\` |
| macOS | `~/Library/Application Support/TinyQuery/` |
| Linux | `~/.local/share/TinyQuery/`（遵循 `$XDG_DATA_HOME`） |

目录内容：

```
datasources.json     已注册的数据源（不含密码）
tinyquery.lock       单实例锁（进程级，退出即释放）
tinyquery.url        当前实例的访问地址
logs/tinyquery.log   运行日志 —— 出问题先看这里
```

可用环境变量 `TINYQUERY_HOME` 整体覆盖这个目录（便携部署用）。

**卸载程序不会删除这个目录**，也不会删除已保存的密码（见下）。

---

## 安全说明

- **只监听 `127.0.0.1`**，局域网/公网无法访问
- **所有 API 需要令牌**：进程启动时随机生成，注入到首页；不带令牌的请求一律 401
  - 请求头：`X-TinyQuery-Token`
  - 每次启动令牌都会变
- 数据全部在内存里，除了你主动注册的文件，不读写别的东西
- **密码**：勾选「保存密码」后存进系统凭据管理器，条目标名为 `TinyQuery/<数据源名>`；**不勾选则完全不落盘**

> ⚠️ **卸载程序不会清除已保存的密码。** 需要自己删：
> - Windows：控制面板 → 凭据管理器 → Windows 凭据 → 找 `TinyQuery/...`
> - macOS：钥匙串访问 → 搜 `TinyQuery`
> - Linux：当前版本尚未支持保存密码（见[已知问题](#已知问题)）

---

## 从源码构建

### 环境要求

| | 版本 |
|---|---|
| JDK | **17**（jpackage 从 JDK 14 起随 JDK 提供） |
| Maven | 3.9+ |
| Node.js | **`^22.18.0 \|\| >=24.12.0`** |

### 构建

```bash
# 1. 前端（必须先做：后端构建带"dist 新鲜度"防呆检查）
cd tinyquery-frontend
npm install
npm run build

# 2. 后端 + 测试
cd ..
mvn clean package          # 产出 target/tinyquery-1.0.0.jar，并跑全部测试

# 3. 打成免安装程序
bash    packaging/package-linux.sh     # Linux：tar.gz（加 --deb / --rpm 出安装包）
pwsh -File packaging/package.ps1       # Windows
bash    packaging/make-icns.sh         # macOS：先由 PNG 生成 icns
```

`packaging/` 下每个脚本的头部注释都写了**为什么这么做**（踩过的坑、平台限制），改动前值得一读。

### 直接跑 jar（不打包）

```bash
java -jar target/tinyquery-1.0.0.jar
```

这条路径在**任何装了 JDK 17 的机器**上都能用，包括没有现成安装包的平台（例如 Intel Mac）。此时它按"开发模式"运行，**不会自动打开浏览器**，需手动访问日志里的地址。

### 运行测试

```bash
mvn test
```

约 340 个测试，其中一部分是平台专属的（Windows / macOS 各自跳过对方那些），所以不同平台的"跳过数"不同，属正常。

---

## 已知问题

| 问题 | 影响 | 说明 |
|---|---|---|
| **不支持的语法报"数据源不存在"** | 排查困难 | 见[上方说明](#-报错信息可能误导) |
| **Linux 无法保存密码** | 每次要手输 | 尚未实现 Linux 的 Secret Service（D-Bus）凭据存储；界面里"保存密码"会置灰，功能不受影响 |
| **Linux 缺中文字体时菜单显示方块** | 观感 | 装 `fonts-noto-cjk` 即可 |
| **`.deb` 在极简系统上可能卸不掉** | 卸载 | 若系统没有 `/usr/share/desktop-directories`，`prerm` 会失败导致卸载中止。变通：`sudo dpkg --purge --force-all tinyquery`。详见下方 |
| **日志文件未强制 UTF-8** | 别的编辑器打开可能乱码 | 按系统默认编码写 |
| **打包体积偏大** | 约 180 MB | jpackage 默认带完整 JDK；后续可用 jlink 裁剪到 60~80 MB |
| **Intel Mac 暂无安装包** | 需自行构建 | 可用 jar 方式运行 |

### 关于 `.deb` 卸载失败（Linux）

现象：`sudo dpkg -r tinyquery` 报

```
xdg-desktop-menu: No writable system menu directory found.
dpkg: error processing package tinyquery (--remove):
 installed tinyquery package pre-removal script subprocess returned error exit status 3
```

原因：jpackage 生成的 `prerm` 脚本带 `set -e`，其中调用了 `xdg-desktop-menu uninstall`。该命令会**顺带处理 `*.directory` 文件**，而那条路径要求系统的 `/usr/share/desktop-directories` 存在且可写 —— 最小化安装、WSL、纯服务器上通常没有这个目录，于是命令以 3 退出，`set -e` 中止整个卸载，`/opt/tinyquery` 原封不动留着。

**带桌面环境的常规发行版一般不受影响**（该目录由桌面组件提供）。

变通办法（任选）：

```bash
# 1) 强制卸载
sudo dpkg --purge --force-all tinyquery

# 2) 或先补上缺失的目录再正常卸载
sudo mkdir -p /usr/share/desktop-directories
sudo dpkg -r tinyquery
```

---

## 第三方依赖与许可

主要依赖：Spring Boot、Vue 3、Element Plus、JNA，以及各数据库的 JDBC 驱动。

完整的依赖清单、版本与许可证（含 LGPL 类依赖的说明）见 [`packaging/THIRD-PARTY.md`](packaging/THIRD-PARTY.md)。
