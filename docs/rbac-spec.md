# neton-application RBAC Spec

> *Application Scaffold RBAC — User / Role / Menu / Permission*

> **状态**：脚手架实现规范（Scaffold Implementation Spec）
> **归属**：`neton-application` / `module-system`
> **不属于**：Neton Framework 核心安全模型
> **依赖**：`neton-security` 提供的 `Identity` / `PermissionEvaluator` / `@Permission` 抽象（见 [framework security spec](https://netonframework.github.io/spec/security)）
> **范围**：`User` / `Role` / `Menu` / `UserRole` / `RoleMenu` / `PermissionInfo` 计算流程
> **不包含**：框架层 Guard 机制、前端 UI 实现、数据级权限、多租户

---

## 目录

1. [范围与定位](#一范围与定位)
2. [数据模型](#二数据模型)
3. [权限字符串格式](#三权限字符串格式)
4. [通配规则](#四通配规则)
5. [菜单模型与 type 语义](#五菜单模型与-type-语义)
6. [计算链路](#六计算链路)
7. [super_admin 判定（SPI 化）](#七super_admin-判定spi-化)
8. [缓存策略](#八缓存策略)
9. [RouteGroup 权限命名空间](#九routegroup-权限命名空间)
10. [与框架抽象的对接](#十与框架抽象的对接)
11. [当前缺口与待修复项](#十一当前缺口与待修复项)
12. [冻结约束](#十二冻结约束)

---

## 一、范围与定位

### 1.1 本文是什么

本文是 **neton-application 脚手架** 中 `module-system` 模块对 RBAC 的**实现规范**。它定义脚手架自己的 User/Role/Menu 数据模型、权限字符串语义、缓存策略与命名空间约定。

**不是 Neton Framework 的一部分**。其他基于 Neton Framework 构建的项目可以选择不使用这套 RBAC 实现，自行设计自己的权限模型，只要符合 framework 的 `Identity` / `PermissionEvaluator` 抽象即可。

### 1.2 本文冻结什么

| 项目 | 状态 |
|------|------|
| RBAC 数据模型字段（User/Role/Menu/关系表） | 冻结 |
| `@Permission` 字符串格式语义（三段式 + 连字符） | 冻结 |
| action 词汇表（读一律 `query`，写 create/update/delete） | 冻结（2026-09） |
| 通配规则（`*:*:*` / `module:*:*` / `module:res:*`） | 冻结 |
| 菜单 `type` 取值与语义（1/2/3） | 冻结 |
| `PermissionInfo` 计算流程 | 冻结 |
| `super_admin` 判定方式 | 冻结为 SPI |
| 缓存键、TTL、失效时机 | 冻结 |
| RouteGroup 与权限命名空间约定 | 冻结 |

### 1.3 本文不冻结什么

- 框架层 `Authenticator` / `Guard` / `PermissionEvaluator` 接口形态（属于 framework spec）
- 前端菜单渲染、按钮级隐藏、路由守卫具体实现
- 数据级权限（行级、字段级）—— 本文只覆盖功能权限（接口 + 菜单）
- 多租户隔离（独立 spec）

### 1.4 模型选型说明

采用 **User → Role → Menu → Permission** 四层标准 RBAC：

- 不允许 `User → Permission` 直连（用户的权限**只能**通过角色继承）
- 不允许 `Role → Permission` 直连（角色的权限**只能**通过分配菜单继承）
- 权限串挂在 **Menu** 上（每个菜单/按钮节点持有 0 或 1 个 permission 字符串）

**理由**：菜单是用户在 UI 上能看到的最小单位，把权限挂在菜单上保证"看得见 = 用得了，看不见 = 用不了"，前后端语义一致。直接给用户分权限或给角色分权限会导致"权限有了但菜单没出"的对不齐问题。

> 这是**脚手架的设计选择**，不是框架要求。其他项目可以选不同模型（ABAC、ReBAC 等），只要最终能填充 `Identity.permissions: Set<String>` 即可。

---

## 二、数据模型

### 2.1 表清单

| 表 | 用途 |
|----|------|
| `system_users` | 用户主表 |
| `system_roles` | 角色 |
| `system_menus` | 菜单/按钮（permission 字符串挂在这里） |
| `system_user_roles` | 用户 ↔ 角色 多对多 |
| `system_role_menus` | 角色 ↔ 菜单 多对多 |

### 2.2 关键字段冻结

#### `Role`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 主键 |
| `code` | String | **角色代码**（如 `super_admin`、`order_manager`），全局唯一，业务/SPI 通过 code 识别 |
| `name` | String | 显示名 |
| `status` | Int | 1=启用 / 0=禁用；非 1 视同未授予 |

#### `Menu`

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Long | 主键 |
| `name` | String | 显示名 |
| `type` | Int | **1=目录 / 2=菜单 / 3=按钮**（见 §5） |
| `parentId` | Long | 父节点 id；顶级为 0 |
| `permission` | String? | 权限串；目录通常为 null，按钮必须有；菜单可有可无 |
| `path` | String? | 前端路由路径（type=2 必填） |
| `component` | String? | 前端组件名（type=2 必填） |
| `icon` | String? | 图标名 |
| `sort` | Int | 排序，升序 |
| `status` | Int | 1=启用 / 0=禁用；0 不进菜单树也不计入权限 |

---

## 三、权限字符串格式

### 3.1 标准格式

```
<module>:<resource>:<action>
```

**强制三段式**，冒号分隔，全小写，**复合词用连字符 `-`**。

| 段 | 含义 | 示例 |
|----|------|------|
| `module` | 业务模块 | `system` / `infra` / `member` / `pay` / `platform` / `gateway` |
| `resource` | 资源/聚合根，**必须与该资源的路由段逐字一致** | `user` / `mail-account` / `wallet-recharge-package` |
| `action` | 操作类型，取自 §3.4 的冻结词汇表 | `query` / `create` / `update` / `delete` |

> **分隔符为什么是连字符**：2026-09 全量核对时，代码里 26 个多词资源段**全部**用连字符
> （`api-access-log`、`mail-account`、`wallet-recharge-package`…），且与路由段完全一致
> （`/system/mail-account`、`/platform/charge-record`）；用下划线的只有一个孤例，已改正。
> 规范此前写的是下划线，但没有一处代码遵守 —— 一条零遵守率的冻结规则不是标准。
> 现按「资源段 = 路由段」对齐：开发者在控制器上看到 `@Controller("/system/mail-account")`，
> 就知道权限串一定是 `system:mail-account:*`，不需要再记第二套拼写。

**resource 段与路由段不一致是 bug**，CI 应当拦下来。真实案例：`ChargeRecordController` 的路由是
`/platform/charge-record`，权限串却写成 `platform:charge:*`，导致菜单里种的 `platform:charge-record:query`
永远匹配不上后端 —— 授了权也是 403。

### 3.2 示例

```
system:user:query            # 列表、下拉、详情——所有读都是它
system:user:create
system:user:update
system:user:delete
system:user:update-password  # 业务动作，复合词用连字符
pay:wallet-recharge-package:query
platform:charge-record:delete
gateway:group-override:query
```

### 3.3 禁止形式

```
❌ user:query                  缺 module 段
❌ system.user.query           错误分隔符
❌ System:User:Query           大写
❌ system:user:query,update    多 action 合并
❌ system::query               空段
❌ system:user                 缺 action 段（读也必须显式写 query）
❌ system:user:page            page / list / detail / overview 一律并入 query（§3.4）
❌ system:mail_account:query   复合词用连字符，不用下划线
❌ pay:wallet-freeze:menu      menu 不是动作（真实出现过，见 §3.4 注）
❌ platform:charge:query       resource 段与路由 /platform/charge-record 不一致
```

### 3.4 action 词汇表（**冻结**）

| Action | 用途 |
|--------|------|
| `query` | **一切读**：分页列表、不分页列表、下拉数据、单条详情、统计概览 |
| `create` | 创建 |
| `update` | 更新（含状态变更、排序等一切改动） |
| `delete` | 删除 |
| `export` / `import` | 导出 / 导入（有实际接口时才用） |
| `<verb>` | 业务专属动作，复合词用连字符，如 `update-password` / `mark-paid` / `assign` |

**读不分形状。** 这是本表最重要的一条：同一资源的分页、下拉、详情共用一个 `query`。
脚手架的主流模块一直如此 —— `system:user:query` 同时挂在 `/page`、`/simple-list`、`/get/{id}`、
`/get/{id}/with-roles` 四个接口上。

为什么不保留 `page` / `list` 的区分：
- **运营看不懂。** 角色授权页面上并排出现 `pay:order:page` 与 `pay:wallet:overview`，
  没有人能判断它们是不是同一类权限。
- **拆了也没人用对。** 菜单授的是「页面」，而一个列表页本来就要同时调列表接口和详情接口。
  把它们拆成两个权限的实际后果是「列表能看、点进去 403」，没有任何场景需要这种粒度。
- **它不是数据级权限。** 真要控制「只能看自己的单」，那是行级权限，本文 §1.3 明确不覆盖，
  再多的 action 词也解决不了。

2026-09 的全量统一把 27 个 `page` / `list` / `detail` / `overview` 并入了 `query`，
涉及 payment（12）、platform（7）、gateway（8）。合并是**放宽**：原先只授了列表权限的角色
现在也能看详情 —— 可以接受，理由见上。

> **注**：`pay:wallet-freeze:menu` 曾经真实存在 —— 「这一行是个菜单」被写进了 action 段。
> action 段只能是动作，不能是这一行在 UI 里的角色。

### 3.5 一致性约束（CI 应当检查）

除了格式，还有两条**存在性**约束。§9.4 已指出格式检查管不到它们，这里给出判定方法：

1. **菜单里的 permission 必须在后端存在。** 否则运营勾了也没用 —— 权限集里多一个串，
   没有任何接口认它。全量核对方法：
   ```
   后端：grep -rhoE '@Permission\("[^"]+"\)' <各模块>/src | sed 's/@Permission("//;s/")//' | sort -u
   菜单：SELECT DISTINCT permission FROM system_menus WHERE permission <> '' AND status = 1
   comm -13 后端 菜单   # 输出即死授权，必须为空
   ```
   2026-09 首次跑这条检查时查出 8 条，包括四个指向根本不存在的 `/infra/config` 控制器的按钮。

2. **菜单指向的页面，后端必须真有接口。** 判断依据是后端有没有对应路由与 `@Permission`，
   **不是前端有没有 `.tsx`**。前端模块里躺着一个页面文件，不代表它调得通 ——
   `pay/notify/index` 与 `pay/app/index` 两页就是这样：前端页面齐全，
   而 module-payment 根本没有 `/pay/notify`、`/pay/app` 控制器，实测 404。

反方向（后端有 `@Permission`、菜单里没有）不是 bug 但是缺口：那个能力只有 super_admin 用得上，
任何角色都授不了。新增受权限保护的接口时，同一个 PR 里补上菜单种子。

--------|------|
| `list` | 不分页列表（少量、字典级） |
| `page` | 分页列表 |
| `query` | 单条查询 / 详情 |
| `create` | 创建 |
| `update` | 更新 |
| `delete` | 删除 |
| `export` | 导出 |
| `import` | 导入 |
| `<verb>` | 业务专属动作，snake_case，如 `assign_role` / `reset_password` |

> 这是**脚手架约定**。框架层 `@Permission` 注解只把字符串当 opaque 标识，不做格式校验。脚手架可以在 CI/lint 阶段强制此格式（见 §9.3）。

---

## 四、通配规则

### 4.1 三种通配形式（冻结）

| 通配 | 语义 | 典型用途 |
|------|------|---------|
| `*:*:*` | 全局超级权限 | super_admin |
| `<module>:*:*` | 整模块超管 | 模块管理员（如 `payment:*:*`） |
| `<module>:<resource>:*` | 资源所有 action | 资源管理员（如 `system:user:*`） |

**不支持**：
- ❌ `*:user:list`（`*` 只能从最左段开始连续出现）
- ❌ `system:*:list`（同上）
- ❌ `*` 单字符（必须三段）

### 4.2 匹配算法

脚手架必须在启动时向 `SecurityBuilder.setPermissionEvaluator(...)` 注入实现了以下算法的 `WildcardPermissionEvaluator`：

```kotlin
class WildcardPermissionEvaluator : PermissionEvaluator {
    override fun allowed(identity: Identity, required: String, ctx: RequestContext): Boolean {
        if ("*:*:*" in identity.permissions) return true             // 全局
        val parts = required.split(":")
        if (parts.size != 3) return false                            // 非法 required 一律拒
        val (m, r, a) = parts
        if ("$m:*:*" in identity.permissions) return true            // 模块
        if ("$m:$r:*" in identity.permissions) return true           // 资源
        return required in identity.permissions                      // 精确
    }
}
```

> 框架默认 evaluator 只做精确匹配（`permission in identity.permissions`），**不支持通配**。脚手架必须显式覆盖，否则 `*:*:*` 等通配权限实际无效。

### 4.3 Required 字符串规范

`@Permission("xxx")` 的字符串：
- **必须**是三段式精确权限串
- **不允许**写通配（通配只能出现在 identity.permissions 里，不能出现在 `@Permission` 注解里）

```kotlin
✅ @Permission("system:user:list")
❌ @Permission("system:user:*")    // 注解里不允许通配
❌ @Permission("system:*:*")
```

---

## 五、菜单模型与 type 语义

### 5.1 type 取值

| type | 含义 | 必填字段 | 是否进菜单树 | 是否参与权限计算 |
|------|------|---------|-------------|-----------------|
| `1` | 目录（Directory） | `name`、`parentId` | ✅ 是 | 通常无 permission |
| `2` | 菜单（Menu / Page） | `name`、`path`、`component` | ✅ 是 | 可有 permission |
| `3` | 按钮（Button / Action） | `name`、`permission` | ❌ 否（不出现在菜单树中） | ✅ 必有 permission |

### 5.2 树构建规则

```
buildMenuTree(menus, parentId = 0):
  for each menu where parentId matches and type != 3:
    children = buildMenuTree(menus, menu.id)
    emit MenuVO(menu, children)
```

按钮（type=3）**不进树**，但其 `permission` 字符串会被收集到 `permissions` 列表。

### 5.3 状态过滤

`status != 1` 的菜单：
- 不进菜单树
- 其 `permission` 不进 permissions 列表
- 其子菜单**整体被丢弃**（即使子菜单 status=1，父级禁用则子级也不可达）

---

## 六、计算链路

### 6.1 标准流程

```
PermissionLogic.getPermissionInfo(userId):

  1. user           = UserTable.get(userId)              ; 不存在 → 404
  2. roleIds        = UserRoleTable.where(userId).map(roleId)
  3. roles          = RoleTable.where(id in roleIds, status=1)
  4. roleCodes      = roles.map(code)

  5. if SuperAdminEvaluator.isSuperAdmin(roleCodes):
       allMenus    = MenuTable.where(status=1).orderBy(sort)
       return PermissionInfoVO(
           user, roleCodes,
           permissions = ["*:*:*"],
           menus = buildMenuTree(allMenus, 0)
       )

  6. menuIds        = RoleMenuTable.where(roleId in roleIds).map(menuId)
  7. menus          = MenuTable.where(id in menuIds, status=1)
  8. permissions    = menus.mapNotNull(permission).distinct()
  9. menuTree       = buildMenuTree(menus, 0)

  10. return PermissionInfoVO(user, roleCodes, permissions, menuTree)
```

### 6.2 PermissionInfoVO 字段冻结

```kotlin
data class PermissionInfoVO(
    val user: UserInfoVO,            // id/username/nickname/avatar/homePath
    val roles: List<String>,         // 角色 code 列表
    val permissions: List<String>,   // 权限串列表（含通配，super 时为 ["*:*:*"]）
    val menus: List<MenuVO>          // 菜单树（不含按钮）
)
```

### 6.3 Identity 注入约定

请求进入业务逻辑时，`Identity.permissions` 应当**已经包含**步骤 8 计算出的列表（通常通过 JWT claim 或登录时缓存）。`PermissionEvaluator` 在 `@Permission` 检查时直接读这个集合，不再回查 DB。

---

## 七、super_admin 判定（SPI 化）

### 7.1 当前问题

`PermissionLogic` 第 21 行硬编码：

```kotlin
private const val SUPER_ADMIN_ROLE = "super_admin"
```

**冻结要求：禁止硬编码字符串**。改为 SPI。

### 7.2 SPI 接口

```kotlin
fun interface SuperAdminEvaluator {
    /** 给定用户的角色 code 集合，判断是否为超级管理员 */
    fun isSuperAdmin(roleCodes: Collection<String>): Boolean
}
```

### 7.3 默认实现

```kotlin
class CodeMatchSuperAdminEvaluator(
    private val codes: Set<String> = setOf("super_admin")
) : SuperAdminEvaluator {
    override fun isSuperAdmin(roleCodes: Collection<String>) =
        roleCodes.any { it in codes }
}
```

### 7.4 配置入口

```toml
# config/security.conf
[security]
super_admin_codes = ["super_admin", "root"]
```

应用初始化时根据配置注入 `CodeMatchSuperAdminEvaluator`，业务可替换为自定义实现（如基于 `user.id == 1` 或外部 IAM 系统判定）。

---

## 八、缓存策略

### 8.1 缓存目标

`PermissionLogic.getPermissionInfo(userId)` 每次查 5+ 张表，**必须缓存**。

### 8.2 缓存键

```
rbac:permission_info:<userId>
```

### 8.3 缓存值

完整 `PermissionInfoVO` 序列化（JSON）。

### 8.4 TTL

- 默认 **15 分钟**
- 可通过 `config/security.conf` 覆盖：`security.permission_cache_ttl_seconds`
- 设置为 `0` 表示禁用缓存（dev 环境推荐）

### 8.5 失效时机（必须主动驱逐）

| 触发事件 | 驱逐范围 |
|---------|---------|
| `assignUserRole(userId, ...)` | `rbac:permission_info:<userId>` |
| `assignRoleMenu(roleId, ...)` | 所有持有该 roleId 的用户：`rbac:permission_info:<allUserIdsWithRole>` |
| 角色 `status` 变更 | 同上 |
| 菜单 `status` 变更 | 全量驱逐 `rbac:permission_info:*`（影响面太广，简单粗暴） |
| 菜单删除 | 全量驱逐 |
| 用户主动登出 | 自身 key |

### 8.6 实现位置

- 缓存层使用框架的 `neton-cache`（L1 + L2）
- 驱逐逻辑写在 `PermissionLogic` / `RoleLogic` / `MenuLogic` 的写操作之后
- **禁止**通过定时全量刷新做"懒失效"，必须事件驱逐

---

## 九、RouteGroup 权限命名空间

### 9.1 路由组划分（已冻结）

| RouteGroup | 用途 | 权限命名空间约定 |
|-----------|------|-----------------|
| `/admin` | 管理后台 | 权限串 module 段使用业务模块名（`system` / `member` / `payment` / ...） |
| `/app` | 普通会员前台 | **不使用 `@Permission`**，仅靠登录态（`@RequireAuth`） |
| `/platform` | 第三方开放 API | 不使用 `@Permission`，靠 AppId/AppSecret 签名鉴权（独立体系，与 RBAC 无关） |

### 9.2 命名空间规则

- `@Permission("xxx")` **只允许**用在 `/admin` 路由组的控制器
- `/app` 控制器使用 `@RequireAuth` 表达"需登录"，不引入权限粒度
- `/platform` 控制器使用专门的客户端身份认证机制，不进入本 RBAC 体系

### 9.3 检查约束

CI/lint 阶段应当检查：
- `controller/admin/**` 下 `@Permission` 字符串符合 §3 格式
- `controller/app/**` 不出现 `@Permission`（出现即报错，提示改为 `@RequireAuth`）
- `controller/platform/**` 不出现 `@Permission` 与 `@RequireAuth`

---

## 十、与框架抽象的对接

### 10.1 框架提供什么

`neton-security`（框架层）提供：

| 抽象 | 用途 |
|------|------|
| `Identity` | 请求中的用户身份（id / roles / permissions） |
| `@Permission("xxx")` | 控制器/方法注解，触发权限检查 |
| `PermissionEvaluator` SPI | 决定 `identity.permissions` 与 `required` 的匹配规则 |
| `Authenticator` SPI | 从请求中提取 `Identity`（JWT、Session、Mock 等） |
| `Guard` 体系 | 角色/匿名/自定义守卫 |

### 10.2 脚手架做什么

`neton-application/module-system` 在框架抽象之上：

| 职责 | 落点 |
|------|------|
| 定义 RBAC 数据模型 | `model/User.kt` / `Role.kt` / `Menu.kt` / `UserRole.kt` / `RoleMenu.kt` |
| 实现 `PermissionLogic.getPermissionInfo` | 把 DB 数据展开成 `Set<String>` |
| 注入 `WildcardPermissionEvaluator` | 启动时调用 `SecurityBuilder.setPermissionEvaluator(...)` |
| 注入 `SuperAdminEvaluator` | 通过 `NetonContext.bind(SuperAdminEvaluator::class, impl)` |
| Identity 填充 | 登录时把计算结果写进 JWT claim 或缓存，供 framework `JwtAuthenticator` 读出 |
| 缓存与失效 | `neton-cache` + 事件驱逐 |

### 10.3 不重叠原则

- **框架不知道** `Role`、`Menu` 是什么 —— 它只接收 `Identity.permissions: Set<String>`
- **脚手架不修改** framework 安全抽象 —— 只通过 SPI 注入实现
- 任何想替换 RBAC 模型的项目（ABAC/ReBAC/外部 IAM）只需提供自己的 evaluator 与 Identity 填充逻辑，**不需要 fork 框架**

---

## 十一、当前缺口与待修复项

> 本节是落地清单，按优先级排列。

### P0（影响正确性）

1. **super_admin 通配实际不生效** —— 框架默认 `PermissionEvaluator` 是精确匹配，当前 `PermissionLogic` 给 super_admin 返回 `["*:*:*"]` 但**没有任何代码注入支持通配的 evaluator**。结果：super_admin 用户调用 `@Permission("system:user:list")` 接口会被拒。
   - **修复**：在 `SystemModuleInitializer` 实现 `WildcardPermissionEvaluator`（按 §4.2 算法），通过 `SecurityBuilder.setPermissionEvaluator(...)` 注入
2. **`SUPER_ADMIN_ROLE` 硬编码** —— 见 §7.1，需改为 SPI

### P1（影响可维护性）

3. **`PermissionLogic.getPermissionInfo` 无缓存** —— 见 §8
4. **`UserInfoVO.homePath` 默认 `/analytics`** —— 但 `analytics` 目录已删，需要改为 `/dashboard` 或从配置读
5. **菜单 `type` 取值无枚举** —— `Int 1/2/3` 散落多处，需要 `enum class MenuType { DIRECTORY, MENU, BUTTON }`，DB 仍存 Int

### P2（规范化补强）

6. **缺少 lint/CI 检查** —— §9.3 约束目前靠人工 review
7. **缺少权限串单元测试** —— `WildcardPermissionEvaluator` 必须有契约测试覆盖 §4.2 全部分支
8. **缺少缓存驱逐契约测试** —— §8.5 全部触发事件应有测试

---

## 十二、冻结约束

| 维度 | 冻结内容 |
|------|---------|
| **模型形态** | `User → Role → Menu → Permission` 四层；不允许 user/role 直挂 permission |
| **权限串格式** | `module:resource:action` 三段式（§3） |
| **通配语法** | 仅 `*:*:*` / `m:*:*` / `m:r:*` 三种（§4.1） |
| **`@Permission` 注解** | 不允许写通配（§4.3） |
| **菜单 type** | 1=目录 / 2=菜单 / 3=按钮（§5.1） |
| **super_admin 判定** | 必须 SPI（`SuperAdminEvaluator`，§7） |
| **缓存** | `rbac:permission_info:<userId>` + 事件驱逐（§8） |
| **RouteGroup** | `@Permission` 仅 admin-api；app-api 仅 `@RequireAuth`；platform-api 走独立认证（§9） |

> 以上冻结**仅对本脚手架生效**。Neton Framework 不强加这些选择给其他用户。

---

## 附录 A：典型场景

### A.1 添加新业务模块的权限

假设新增"工单"模块 `ticket`：

```kotlin
@Controller("/admin/ticket")
class TicketController(...) {
    @Get("/page")
    @Permission("ticket:ticket:page")
    suspend fun page(...): PageResponse<...>

    @Post("/create")
    @Permission("ticket:ticket:create")
    suspend fun create(...)

    @Post("/{id}/assign")
    @Permission("ticket:ticket:assign")
    suspend fun assign(...)
}
```

DBA 在 `system_menus` 表中插入对应菜单和按钮节点（permission 列填上述串），然后通过角色管理 UI 把这些菜单分配给目标角色即可。

### A.2 super_admin 看到完整菜单 + 全权访问

- super_admin 角色无需在 `system_role_menus` 中分配任何菜单
- `PermissionLogic.getPermissionInfo` 会跳过 RoleMenu 查询，直接返回所有 `status=1` 菜单 + `["*:*:*"]`
- `WildcardPermissionEvaluator` 见到 `*:*:*` 直接放行任何 `@Permission` 检查

### A.3 用户既有 `system:user:page` 又有 `system:user:*`

- `identity.permissions = ["system:user:page", "system:user:*"]`
- 请求 `@Permission("system:user:delete")`
- evaluator 检查：`*:*:*` 不在 → `system:*:*` 不在 → `system:user:*` **命中** → 放行

### A.4 用户角色被禁用后

- 管理员把用户某个角色 `status` 改为 0
- `PermissionLogic` 触发驱逐 `rbac:permission_info:<allAffectedUserIds>`
- 用户下一次请求重算权限，新的 JWT/缓存中不含被禁角色的菜单与权限

---

## 附录 B：常见疑问

**Q：为什么不允许 `User → Permission` 直接绑定？**
A：直接绑定会导致"权限有了但菜单看不到"或"菜单看到了但点不动"的对不齐问题。强制走 Role + Menu 保证 UI 与后端一致。如果业务确实需要"个别用户特权"，应通过创建专属角色实现。

**Q：为什么菜单的 `permission` 字段可空？**
A：目录（type=1）通常没有具体操作，不需要权限串；菜单（type=2）有些只是"可见即可访问"无须细控；只有按钮（type=3）强制要有权限串。允许 null 是为了表达"无操作粒度"。

**Q：通配为什么只允许从左到右连续？**
A：`*:user:list` 这种"中间通配"在工程上意义不明（"任何模块下的 user 资源的 list 操作"是个怪需求），且会让权限审计变得不可读。从左到右的层级通配（模块管理员 → 资源管理员）才是真实业务模型。

**Q：缓存失效为什么菜单变更要全量驱逐？**
A：菜单与角色是 N:M 关系，一个菜单变更可能影响所有持有相关角色的用户。精确计算"哪些用户受影响"成本高于全量驱逐，且菜单变更本身是低频操作。简单粗暴。

**Q：app-api 真的不需要权限粒度吗？普通用户也有不同等级啊？**
A：会员等级、VIP 区分等属于**业务规则**，不是 RBAC 范畴。在业务 logic 中显式判断 `member.level >= 3` 即可，不要硬塞进权限体系。RBAC 解决的是"管理员能不能操作系统"，不解决"会员能不能享用某功能"。

**Q：为什么这套 RBAC 不写进 Neton Framework？**
A：框架要保持"对实现选择中立"。如果把这套 User/Role/Menu 模型写进框架，等于绑架所有 Neton 用户必须用这套数据结构。脚手架的实现选择应该停留在脚手架层。
