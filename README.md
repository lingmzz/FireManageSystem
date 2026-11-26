# 消防管理系统

管理消防设备台账、维保单位、应急演练和隐患整改。后端使用 Spring Boot 和 MySQL，前端使用 Vue 3 和 shadcn-vue。构建后，前端随 Java 应用一起运行。

## Windows 本地启动

| 工具 | 要求 |
| --- | --- |
| Git | 用于克隆项目 |
| PowerShell | 7.2 或以上，使用 `pwsh` 运行脚本 |
| Java | JDK 21，将 `JAVA_HOME` 指向 JDK 安装目录 |
| Maven | 3.9，确保 `mvn.cmd` 在 `PATH` 上 |
| Node.js 和 pnpm | Node.js 24，确保 `node.exe` 在 `PATH` 上。用 `npm.cmd install --global pnpm@10.24.0` 安装 pnpm |
| MySQL | MySQL Community Server 8.0 的 Windows 版本，安装目录下有 `bin\mysqld.exe` 和 `bin\mysql.exe` |

打开 PowerShell 7，克隆后进入项目根目录。把 `FIRE_MYSQL_HOME` 改为自己电脑上的 MySQL 安装目录。如果 `mysqld.exe` 已在 `PATH` 上，可以省略这一行。

```powershell
git clone https://github.com/lingmzz/FireManageSystem.git
Set-Location FireManageSystem
$env:FIRE_MYSQL_HOME = 'C:\Program Files\MySQL\MySQL Server 8.0'
.\Build-Local.ps1
.\Start-Local.ps1
```

首次构建会下载依赖并运行后端测试。启动脚本会在 `.local/mysql-data` 建立独立的 MySQL 实例，应用自动建表并创建初始角色、菜单和管理员。以后使用同一个目录启动时会继续使用已有数据库。

打开 [消防管理系统](http://127.0.0.1:48080/)，用 `admin` 和 `.local/admin-credentials.txt` 内随机生成的密码首次登录。可在登录后修改密码，该文件记录的是首次生成的值。

## 初始化和配置

| 配置 | 默认值或位置 |
| --- | --- |
| 应用地址 | `http://127.0.0.1:48080/` |
| 项目数据库 | `127.0.0.1:13306` 上的 `fire_manage` |
| 应用数据库配置 | `.local/application-local.yaml`，首次初始化时生成 |
| 初始化脚本 | [scripts/Initialize-Database.ps1](scripts/Initialize-Database.ps1)，也可单独运行以启动项目数据库 |
| 数据库表结构 | [schema.sql](backend/src/main/resources/schema.sql)，由 Spring Boot 在启动时执行 |
| 演练附件 | `.local/fire-drill-files/` |
| 启动日志 | `.local/logs/`，应用看 `app.log`，数据库看 `mysql-error.log` |

端口冲突时，可以为新建的本地实例指定端口。之后启动该数据库时，使用首次初始化时的 `-MySqlPort`。

```powershell
.\Start-Local.ps1 -AppPort 48081 -MySqlPort 13307
```

`.local/` 保存数据库、附件和随机生成的凭据，`.tools/` 保存可选的本机工具与依赖缓存。这两个目录均通过 Git 忽略。迁移本机数据时，先关闭服务并备份数据库与附件，再把数据导入新环境。

### 停止本地服务

后台应用的进程号记录在 `.local/logs/app.pid`。在任务管理器中找到该进程，确认命令行包含本项目的 `fire-manage.jar` 后结束它。然后在项目根目录正常关闭项目 MySQL。

```powershell
& "$env:FIRE_MYSQL_HOME\bin\mysqladmin.exe" '--defaults-file=.local/mysql-admin.cnf' shutdown
```

如果使用 `PATH` 查找 MySQL，直接运行 `mysqladmin.exe` 并传入同样的参数。

### 使用已有的 MySQL 实例

通过 MySQL 管理员在本机实例上执行以下语句。`IDENTIFIED BY RANDOM PASSWORD` 会返回应用数据库用户的密码。若已有该用户，可复用现有用户名和密码。

```sql
CREATE DATABASE IF NOT EXISTS fire_manage CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'fire_manage'@'127.0.0.1' IDENTIFIED BY RANDOM PASSWORD;
GRANT ALL PRIVILEGES ON fire_manage.* TO 'fire_manage'@'127.0.0.1';
```

构建后使用 [外部数据库配置模板](config/application-external.example.yml) 启动应用。模板默认连接 `127.0.0.1:3306` 上的 `fire_manage`。库名、地址或用户名不同时，可在启动前设置 `FIRE_DB_URL` 和 `FIRE_DB_USER`。管理员初始密码至少 10 位，仅在空库首次启动时使用。

```powershell
.\Build-Local.ps1
$env:FIRE_DB_PASSWORD = Read-Host '输入应用数据库用户密码' -MaskInput
$env:FIRE_BOOTSTRAP_PASSWORD = Read-Host '设置初始管理员密码' -MaskInput
try {
    & "$env:JAVA_HOME\bin\java.exe" -jar .\backend\target\fire-manage.jar '--spring.config.additional-location=file:./config/application-external.example.yml'
} finally {
    Remove-Item Env:FIRE_DB_PASSWORD, Env:FIRE_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
}
```

使用这个命令时，应用在当前终端运行，按 `Ctrl+C` 可退出。

## 开发

- `backend/` 是后端，`frontend/` 是前端。修改后先停止后台应用，然后在项目根目录运行 `.\Build-Local.ps1` 和 `.\Start-Local.ps1`。可执行包位于 `backend/target/fire-manage.jar`。
- 本机已有全部依赖时，可以运行 `.\Build-Local.ps1 -Offline`。`-Online` 与默认构建行为相同。
- 需要前端热更新时，在默认端口启动后端，然后在 `frontend/` 内运行 `pnpm.cmd dev`，打开 [Vite 开发地址](http://127.0.0.1:5173/)。Vite 会将 `/api` 转发到本机的 `48080` 端口。

## 业务功能

- 用户、角色和菜单管理支持用户启停、密码重置、角色权限和菜单层级配置。审批记录的可见范围按处理权限与办理记录控制。
- 在「消防单位与授权」下维护长车段的科室和车站。在「用户管理」分配车站、科室或车务段消防管理员角色，再在「用户范围」配置所属单位。科室范围包括所辖车站。
- 消防设备台账支持 Excel 模板和导入导出、库存领用、更换、年检、报废、批量年检和批量报废。车站可更新存放地点、登记年检。库存和在用设备都参与保质期提醒，截止当天仍计入三个月内到期，之后标为已过期。在用设备的年检以最近记录为准，往年记录会提示年检，缺少记录会标为待年检。
- 维保单位可关联车站和设备。应急演练可记录时间、人数和总结，也可上传总结文件和图片。附件按所属单位权限下载。
- 普通隐患整改从上报、指派、提交整改到复核销项，支持复核退回。车站消防隐患由科室确认后交车务段总销号，科室和段级直属隐患直接交车务段总销号。退回后可重新上报，办理历史随流程保留。
