# neton-application 开发硬规范 v1

> 状态：立即生效
> 适用范围：`neton-application`、`neton-application-module-member`、`neton-application-module-payment`、`neton-application-module-platform`、`neton-application-admin`
> 目标：把业务项目中反复出现的问题收敛成统一规则，反向打磨 Neton 框架与脚手架

---

## 1. 总原则

- `neton-application*` 是 Neton 的主验证场景，优先通过真实项目发现问题、沉淀规范、再反哺框架。
- 任何高频业务约束，一旦在两个及以上模块重复出现，就必须上升为脚手架规范。
- 任何会影响前后端协作、生成代码、数据库一致性的规则，都必须写入本文档，禁止仅靠口头约定。

---

## 2. 字段规范

### 2.1 主键规范

- 所有核心业务表必须使用单一主键字段 `id`。
- 主键类型统一为 `Long / BIGINT`。
- 禁止使用业务字段冒充资源主键。
- 禁止通过 `save` 语义隐式判断“新增还是修改”。

### 2.2 二值字段规范

- 所有落库的二值字段统一使用 `Int / TINYINT / SMALLINT / INTEGER`。
- 所有落库二值字段只允许 `0` 和 `1`。
- 禁止在持久化 Model 中继续新增 `Boolean` 类型字段。
- `Boolean` 仅允许出现在 VO、前端视图态或纯计算字段中。

### 2.3 二值语义命名

- `status`：
  `0 = 停用/禁用`
  `1 = 启用/正常`
- `deleted`：
  `0 = 未删除`
  `1 = 已删除`
- `default_status` / `is_default`：
  `0 = 否`
  `1 = 是`
- `master`：
  `0 = 否`
  `1 = 是`
- 其他所有是/否字段：
  `0 = 否`
  `1 = 是`

### 2.4 状态常量

- 后端必须为高频状态建立统一常量或枚举封装。
- 前端必须为高频状态建立统一 options/tag 映射。
- SQL 注释、后端常量、前端文案必须保持同一语义。

---

## 3. 接口规范

### 3.1 基础 CRUD 路由

单资源接口统一遵循以下约定：

- `GET /xxx/page`
- `GET /xxx/list`
- `GET /xxx/simple-list`
- `GET /xxx/get/{id}`
- `POST /xxx/create`
- `PUT /xxx/update`
- `DELETE /xxx/delete/{id}`
- `DELETE /xxx/delete-list?ids=1,2,3`

### 3.2 主键约束

- 单资源读取必须使用主键路径参数：`/get/{id}`。
- 单资源删除必须使用主键路径参数：`/delete/{id}`。
- 批量删除统一使用 `ids`。
- 禁止把单资源查询写成 `GET /get?id=1`。

例外场景：

- 单例配置资源允许使用：
  - `GET /get`
  - `PUT /update`
- 当前登录用户资料资源允许使用：
  - `GET /get`
  - `PUT /update`
  - `PUT /update-password`
- 上述例外必须由“单例”或“当前身份上下文”驱动，不能退化成任意 query 参数读取。

### 3.3 业务键查询

- 按业务键查询必须显式命名：
  - `/get-by-key`
  - `/get-by-code`
  - `/get-by-username`
- 禁止把业务键查询伪装成标准 CRUD。
- 禁止使用 `save` 这类混合语义接口替代 `create/update`。

### 3.4 写接口入参

- 所有 `create/update` 接口必须使用 DTO。
- 禁止 Controller 直接接收持久化 Model 作为写接口入参。
- DTO 负责输入校验、字段裁剪和语义表达。
- Model 只服务于 Table/Logic/数据库映射。

### 3.5 Controller 命名规范

- Controller 类名只表达资源本身，不表达端侧前缀。
- 在 `controller/admin/...` 下：
  - 使用 `UserController`
  - 不使用 `AdminUserController`
- 在 `controller/app/...` 下：
  - 使用 `UserController` 或 `MemberUserController`
  - 不使用 `AppUserController`
- 在 `controller/open/...` 下：
  - 使用 `OrderController` 或 `PlatformOrderController`
  - 不使用 `OpenOrderController`
- 端侧语义由目录层级和 `@Controller("/xxx")` 路径表达，不由类名前缀表达。

### 3.5.1 跨仓改名（重命名 public symbol 时必读）

重命名任何被其它仓消费的 public symbol（class / object / top-level fun）时，**必须 grep 所有 canonical included build 独立仓，而不仅是当前 application 仓和它的 fork**。

一次 `AppFileLogic → FileUploadLogic` 的改名只扫了 `neton-application/` 子树，漏掉了通过 `includeBuild` 引入的 canonical `neton-application-module-member`，导致 member 编译断裂（`Unresolved reference 'AppFileLogic'`）——member 的 `MemberProfileLogic` 消费了 module-infra 的这个类。

改 public symbol 前，至少 grep 这些仓：
- `newgate`（当前发行版仓，本地目录名沿用 fork 时的命名）
- `neton-application-module-{system,infra,member,payment,platform,gateway}`（canonical 通用模块，本发行版六个全装配）
- `neton` / `geolite4k`（框架与 included build）
- 同一 base 的其它下游发行版 fork（若有）

命中的仓各自独立提交跟进（fork 同步规则不变）。

### 3.6 Controller 方法命名规范

标准 CRUD 方法优先使用以下命名：

- `page`
- `list`
- `simpleList`
- `get`
- `create`
- `update`
- `delete`
- `deleteList`

补充规则：

- 单例配置资源使用：
  - `get`
  - `update`
- 业务键查询使用：
  - `getByKey`
  - `getByCode`
  - `getByUsername`
- 状态切换使用：
  - `updateStatus`
- 特殊领域动作使用明确动词，不要伪装成 CRUD：
  - `assignRole`
  - `resetPassword`
  - `bindSocialUser`

禁止：

- `save`
- `edit`
- `remove`
- `queryInfo`
- `detail`（如语义其实是标准主键读取）

### 3.7 分页规范

- 分页参数统一使用：
  - `pageNo`
  - `pageSize`
- 分页返回统一使用 `PageResponse<T>`。
- 前后端禁止混用 `page/size` 与 `pageNo/pageSize`。

---

## 4. 模块规范

### 4.1 模块职责

- `module-system`：用户、角色、菜单、权限、字典、消息等核心基础业务。
- `module-infra`：配置、文件、任务、API 日志、基础设施能力。
- `module-member`：会员域。
- `module-payment`：支付域。
- `module-platform`：开放平台域。

### 4.2 模块边界

- 模块之间允许依赖 `system`，禁止随意跨域直连。
- 若跨模块依赖不可避免，优先通过 Logic 或明确的共享服务注入，不直接绕过到对方 Table。
- 通用能力先放 `system/infra`，不要在业务模块里各自复制。

### 4.3 初始化规范

- 每个模块都应通过 `ModuleInitializer` 注册：
  - Table
  - Logic
  - RouteInitializer
- 模块初始化时禁止塞入隐式业务规则，必须在文档中声明。

---

## 5. SQL 规范

- 所有表字段命名统一使用 snake_case。
- 所有时间字段统一使用：
  - `created_at`
  - `updated_at`
- 软删除统一使用：
  - `deleted`
- 二值字段默认值必须明确写出 `0` 或 `1`。
- 初始化 SQL 中的状态注释必须明确写清：
  - `0=正常, 1=停用`
  - `0=否, 1=是`

---

## 6. 前端规范

### 6.1 前端 API 命名

- 前端 API 层必须严格镜像后端接口语义。
- 若后端改为 `/get/{id}`，前端必须同步改，不保留旧式兼容路径。
- 前端不得自行发明与后端不一致的 CRUD 风格。

### 6.2 状态与字典

- 所有状态字段必须通过统一 options/tag 组件渲染。
- 前端禁止在页面里手写零散状态文案。
- `0/1` 的业务含义必须由共享常量或字典统一驱动。

### 6.3 模板瘦身

- `neton-application-admin` 必须以当前后端能力为准做裁剪。
- 对当前后端未提供的模块，优先删除目录、API、菜单和依赖，而不是只隐藏入口。
- 禁止长期保留与当前产品无关的大型模板残留。

---

## 7. 测试规范

### 7.1 规范测试目标

后续应逐步补齐以下测试：

- 持久化字段不允许新增 `Boolean` 落库字段
- 单资源接口必须使用 `/get/{id}`
- 所有写接口必须使用 DTO
- 所有分页接口必须使用 `pageNo/pageSize`
- 所有 `status/deleted/default/master` 语义必须符合本文档

### 7.2 回归优先级

- 先补脚手架级规范测试
- 再补模块级 CRUD 回归测试
- 最后补跨模块集成测试

---

## 8. 当前已识别的重点整改项

### P0

- 统一所有落库 `Boolean` 字段为 `Int(0/1)`
- 清理 `GET /get?id=...` 风格接口
- 清理 `save/upsert by business key` 风格接口
- 禁止 Controller 写接口直接接收 Model

### P1

- 统一分页参数
- 统一批量删除约定
- 建立后端状态常量与前端状态字典
- 清理前端无关模板残留

### P2

- 建立规范扫描脚本或测试
- 为 KSP/脚手架增加规则生成与校验能力

---

## 9. 执行方式

- 先改文档，再改代码。
- 每次代码整改都必须对应本文档中的一条或多条规则。
- 若发现本文档与真实业务冲突，先修正文档，再调整代码，不允许代码先行漂移。
