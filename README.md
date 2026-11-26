# 消防管理系统

管理消防设备台账、维保单位、应急演练和隐患整改。后端使用 JDK 8、Spring Boot 2.7 和 Oracle 10g，前端使用 Vue 3 和 shadcn-vue。构建后，前端随 Java 应用一起运行。

## Windows 本地构建

| 工具 | 要求 |
| --- | --- |
| Git | 用于克隆项目 |
| PowerShell | 7.2 或以上，使用 `pwsh` 运行脚本 |
| Java | JDK 8，将 `JAVA_HOME` 指向 JDK 安装目录 |
| Maven | 3.9，确保 `mvn.cmd` 在 `PATH` 上 |
| Node.js 和 pnpm | Node.js 24 和 pnpm 10.24.0。可用 `npm.cmd install --global pnpm@10.24.0` 安装 pnpm |

```powershell
git clone https://github.com/lingmzz/FireManageSystem.git
Set-Location FireManageSystem
.\Build-Local.ps1
```

构建会运行后端测试，无需数据库服务器。应用运行时通过 JDBC Thin 连接已有的 Oracle 10g，本机只需安装上表所列的工具。

## 初始化远程 Oracle 10g

1. 请数据库管理员在已有实例上为应用准备独立账户，授予 `CREATE SESSION`、`CREATE TABLE` 和 `CREATE SEQUENCE` 权限，并分配表空间配额。建议数据库使用支持中文的字符集，比如 `AL32UTF8`。
2. 首次部署时，用应用账号在空 schema 中执行 [Oracle 建表脚本](backend/src/main/resources/schema.sql)。脚本创建数据表、索引、主键序列和审批流程的基础配置。
3. 首次启动时设置 `FIRE_BOOTSTRAP_PASSWORD`，至少 10 位。应用在空库内创建 `admin`、角色和菜单。后续启动沿用库中的账号。

已有 MySQL 数据时，先备份数据库和 `.local/fire-drill-files/`。如需保留历史数据，须单独导入 Oracle，保留记录 ID 和关联关系，并将 `fm_id_seq` 的起始值设为大于已导入的最大 ID。迁移已有管理员时，首次登录继续使用数据库中的原密码。

## 连接 Oracle 并启动

将示例中的主机、端口、服务名和账号替换为数据库管理员提供的值。使用 Service Name 时，连接格式为 `jdbc:oracle:thin:@//主机:端口/服务名`。使用 SID 时，格式为 `jdbc:oracle:thin:@主机:端口:SID`。

```powershell
$env:FIRE_DB_URL = 'jdbc:oracle:thin:@//db.example.com:1521/FIREDB'
$env:FIRE_DB_USER = 'fire_manage'
$env:FIRE_DB_PASSWORD = Read-Host '输入应用数据库用户密码' -MaskInput
try {
    # 只在空库首次启动时设置初始管理员密码
    $env:FIRE_BOOTSTRAP_PASSWORD = Read-Host '设置初始管理员密码' -MaskInput
    .\Start-Local.ps1
} finally {
    Remove-Item Env:FIRE_DB_PASSWORD, Env:FIRE_BOOTSTRAP_PASSWORD -ErrorAction SilentlyContinue
}
```

启动后打开 [消防管理系统](http://127.0.0.1:48080/)。首次使用 `admin` 和上一步设定的密码登录。

- 默认数据库配置见 [外部数据库配置模板](config/application-external.example.yml)。也可把配置放到 Git 忽略的 `.local/`，通过 `.\Start-Local.ps1 -DatabaseConfig .\.local\application-oracle.yml` 加载。
- 端口冲突时，使用 `.\Start-Local.ps1 -AppPort 48081` 指定应用端口。
- 日志位于 `.local/logs/`，查看 `app.log` 和 `app-error.log`。停止时，根据 `.local/logs/app.pid` 找到进程，确认命令行包含本项目的 `fire-manage.jar` 后结束该进程。
- `.local/` 保存应用日志和演练附件，`.tools/` 可保存本机工具与依赖缓存，两个目录均由 Git 忽略。

## 开发

- `backend/` 是后端，`frontend/` 是前端。修改后先停止后台应用，然后运行 `.\Build-Local.ps1` 和 `.\Start-Local.ps1`。可执行包位于 `backend/target/fire-manage.jar`。
- 本机已有全部依赖时，可以运行 `.\Build-Local.ps1 -Offline`。`-Online` 与默认构建行为相同。
- 需要前端热更新时，在默认端口启动后端，然后在 `frontend/` 内运行 `pnpm.cmd dev`，打开 [Vite 开发地址](http://127.0.0.1:5173/)。Vite 会将 `/api` 转发到本机的 `48080` 端口。

## 业务功能

- 用户、角色和菜单管理支持用户启停、密码重置、角色权限和菜单层级配置。审批记录的可见范围按处理权限与办理记录控制。
- 在「消防单位与授权」下维护长车段的科室和车站。在「用户管理」分配车站、科室或车务段消防管理员角色，再在「用户范围」配置所属单位。科室范围包括所辖车站。
- 消防设备台账支持 Excel 模板和导入导出、库存领用、更换、年检、报废、批量年检和批量报废。车站可更新存放地点、登记年检。库存和在用设备都参与保质期提醒，截止当天仍计入三个月内到期，之后标为已过期。在用设备的年检以最近记录为准，往年记录会提示年检，缺少记录会标为待年检。
- 维保单位可关联车站和设备。应急演练可记录时间、人数和总结，也可上传总结文件和图片。附件按所属单位权限下载。
- 普通隐患整改从上报、指派、提交整改到复核销项，支持复核退回。车站消防隐患由科室确认后交车务段总销号，科室和段级直属隐患直接交车务段总销号。退回后可重新上报，办理历史随流程保留。
