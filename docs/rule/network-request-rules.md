# PlayTV 网络请求与 Repository 规则

本文件维护普通 HTTP/JSON 请求从发起到页面渲染的执行约定。所有类的模块、目录和命名只在 [数据契约](data-contract-rules.md) 维护；技术选型和依赖交付状态只在 [依赖目录](build-dependency-catalog.md) 维护；图片请求见 [图片加载](image-loading-rules.md)。公共实现优先复用，是否启用及真实 API 必须查 [组件目录](ui-component-catalog.md) 与源码。

## 固定链路与职责

```text
UI 派发 Event
  → ViewModel 校验并发条件、更新加载状态
  → Repository 调用 Service
  → support 提供的客户端执行 HTTP，转换器解析 DTO
  → model 核对 HTTP/业务结果、必需字段，转换为领域或展示数据
  → Repository 返回结果
  → ViewModel 提交 State
  → UI 渲染文本、列表，并通过图片入口加载图片
```

- Activity/Fragment/Adapter 不创建 HTTP 客户端、不调用 Service、不解析 JSON、不判断服务端业务码。ViewModel 不拼 URL、RequestBody、Header，不依赖 Retrofit/OkHttp。
- Service 只声明路径、请求方式、参数和 DTO；普通 JSON 优先使用带类型的 `@Body`，不把所有请求做成 `Any`、Map 或手工序列化字符串。表单、文件和动态 URL 按接口的真实协议声明。
- Repository 表达数据动作，负责业务成功判定、数据校验、转换以及真实需要的源选择；不发 Toast、不导航、不持有 UI。简单请求不强制增加 UseCase 或 RemoteDataSource。
- 图片由 UI 把 State 中的图片来源交给图片入口；ViewModel/Repository 不下载成 Bitmap 再塞入 State。请求返回成功与某张图片加载失败分别处理。

## 客户端配置和依赖组装

1. app 的组合根读取本项目环境配置，创建客户端、Service、Repository，再通过已有 ViewModel Factory 注入。Application 只启动明确需要的最小能力；不生成全业务全局 Manager。
2. `NetworkConfig` 表达 baseUrl、超时和诊断开关等技术参数。真实地址由项目自己的 app 配置提供，不写进 support/base，不设置 Sony 或公共演示站点为隐式默认地址。
3. 普通 JSON 客户端的初始默认值为 connect/read/write/call timeout = 15/30/30/60 秒，统一由配置表达并允许覆盖。这是通用起点，未经过业务耗时测量；上传、下载和流式调用另定义客户端/超时，不盲目复用总时限。
4. 公共头和凭据通过构造注入的提供者传入。support 不读取业务 UserManager、KV、语言单例；可变凭据不能在创建客户端时永久捕获。每次操作捕获自己的账号/环境代次，切换流程遵循 [Manager 规则](manager-lifecycle-rules.md)。
5. API 与图片凭据按目标域名明确隔离。禁止把 API Token、签名、Cookie 自动附加到任意动态 URL/CDN；重定向后的凭据处理也要限定可信来源。
6. 客户端按配置/作用域复用，不为每个请求或每行列表创建实例。环境切换后重建依赖图，或使用受控的 Service 提供方式；不能只替换全局 Retrofit，却让旧 Repository 继续引用旧 Service。
7. SDK 依赖默认 `implementation`。Service 的 Retrofit 注解、返回类型以及 DTO 的 JSON 注解由实际定义模块显式声明依赖；网络封装若公开 Retrofit/OkHttp 类型，必须按数据契约登记 `api` 的必要范围，不能宣称完全隐藏 SDK。
8. 业务联网需声明 `INTERNET`（普通权限，不弹运行时授权）。默认 HTTPS；开发 HTTP 例外只对需要的域名/构建变体开放，不全局允许明文、不关闭证书或主机名校验。Gradle 仓库的 HTTP 设置与 App 运行时网络策略无关。

## Repository 统一出口

新增普通 REST Repository 的默认签名为 `suspend fun loadItems(...): Result<ItemPage>`：成功值是可用的领域/展示数据，失败值携带可分类异常。`ItemPage` 只是命名示意，不预设真实字段。已有本地样例不因此批量改签名；流式、持续观察和下载进度另写专用契约。

处理顺序固定为：

1. 执行挂起 Service 方法，检查 HTTP 状态，保留必要的 HTTP code 和 requestId。
2. 使用当前接口对应的响应 DTO；有响应包络时核对业务成功条件，无包络的接口直接校验数据。不能全工程假设 `code == 0/200` 或存在 `success/data`。
3. 校验本次动作必需字段。`204`、允许的 `null`、空列表分别按端点语义处理，不能把任意空响应当成功或统一转为空列表。
4. 在 Mapper/Repository 转换为调用方需要的数据。相对图片地址如需解析，使用项目明确配置的资源基址；不拿 API baseUrl 猜图片域名，不修改签名 URL 的查询参数。
5. 返回 `Result.success(data)`；预期请求失败经边界转换后返回 `Result.failure(error)`，保留 cause。ViewModel 只处理业务结果/错误类别，不再拆包络或重复判断业务成功码。

封装挂起请求时先捕获并重新抛出 `CancellationException`，再处理预期异常；取消不能放进 `Result.failure`。普通 `runCatching` 不直接包裹整条挂起链路，不捕获 Throwable 后吞掉取消、致命错误或程序缺陷。Retrofit 挂起请求不需要再启动独立 Scope；阻塞 IO/大型转换的线程选择见 [业务开发](business-development-rules.md)。

同一项目复用统一的请求结果处理入口；差异以包络解析/业务判定策略注入，不复制每个 Repository 的 try/catch/错误映射，也不制造支持所有协议的万能 BaseResponse。Repository 接口仅在需要替换数据源、隔离实现或验证边界时增加，不强制每个类配一个只有单实现的接口。

已启用 network 时直接使用 model/network/protocol/RequestExecutor：`execute(request, convert)` 统一保留取消和技术错误，convert 只增加当前协议校验/映射；预期业务/协议失败抛 RepositoryFailure 或其语义子类。程序缺陷继续抛出，由页面边界记录，不在 Repository 吞成空数据。同后端复杂包络判定再提取 `<Backend>ResponsePolicy`，不在每个接口各写一套。

普通 `suspend fun ...(): Dto` 由 Retrofit 对非 2xx 抛 HttpException；需要 HTTP 状态码/204 时声明 `Response<Dto>` 并使用 `executeResponse`，convert 明确判断 nullable body 与 statusCode。此入口面向 JSON DTO，原始流式 body/下载不使用它。组装与关闭使用 app/di/NetworkDependencies，Service/Repository 在所属功能依赖中创建后复用。

## 错误、重试与可追踪性

| 分类 | 处理边界 |
|---|---|
| 连接、DNS、TLS、超时 | 网络边界记录技术类别与 cause；由 Repository 转换为稳定的调用方错误 |
| HTTP 非成功状态 | 保留状态码；错误体按已知协议有界解析，不能因 `body() == null` 丢失 HTTP 原因 |
| JSON 解析失败 | 区分于断网；保留解析 cause，不把原始整包响应写日志 |
| 业务拒绝/鉴权失效 | model 解析当前项目 code/requestId；会话协调者处理失效，拦截器不直接退出登录或跳页面 |
| 必需字段缺失/映射失败 | model 报数据协议错误；不能用空字符串、0 或空列表掩盖 |
| 用户取消/作用域结束 | 向上传播取消，恢复相关忙状态，不显示请求失败 Toast |

错误类型归属按数据契约，不在 ViewModel 临时声明。UI 按错误语义映射 strings.xml，不直接展示 exception.message、服务端原始 message 或堆栈。可以展示的业务文案须由本项目协议明确。

- 应用层默认不自动重试。底层连接恢复能力必须记录，不等同于业务重试；有副作用操作说明服务端幂等。不能在拦截器、Repository、ViewModel 三层各重试一遍。
- 诊断记录请求操作名/路径模板、method、耗时、HTTP 状态、错误类别和安全的关联 ID。Header、query、body 默认不输出；调试开启也须结构化脱敏、限制长度，生产不输出原始请求/响应。
- 日志不能为打印请求而重读一次性 body、完整缓冲文件/流式响应或消费 response body；下载/流式请求不走全量 JSON 日志转换器。响应体所有权明确，原始 body 在消费后关闭。
- 不把所有 401/403、超时或解析失败都等同于登录过期；鉴权去重与账号切换仍按业务/Manager 规则执行。

## 可变部分与复用部分

项目通常只补地址、端点、请求/响应 DTO、包络判定、必要 Header、映射和页面 Spec。请求传输、取消、诊断、结果处理与图片工具沿用已交付公共能力；不要每做一个接口重新选库或再建一套网络工具。

存在多后端、无包络接口或特殊上传时允许按协议增加适配器，不能修改所有接口共用的成功判定来迁就一个端点。新增字段需要核对 JSON 字段名、空值与 R8；具体依赖版本及反射保护在依赖目录和源码记录。

## 链路交付验收

公共基线及src/debug 调用示例见 [公共基础](common-foundation.md)；文档、公共工具、实际调用和运行验证是不同状态；缺工具时先补工具并登记，不能只按本文类名调用。后续技术样例至少用可控 HTTP 响应通过实际客户端与 JSON 转换器，提供 JSON 和图片内容，走完 Service → Repository → ViewModel → UI；直接返回 Kotlin 假数据只验证页面，不验证请求链路。

需要覆盖：成功、空数据、HTTP 失败、业务拒绝、坏 JSON、必需字段缺失、取消/超时、快速重复、页面销毁，以及图片失败与列表复用。环境/账号切换仅在启用对应能力时验证。具体样例 Spec 记录输入、预期、已完成与未执行项；运行验证仍需按 AGENTS 明确授权。

技术演练没有真实接口时，可以在 Spec 明确制定本地测试协议、场景和无品牌协议夹具；标明这些字段/成功条件只用于 demo，不作为业务推断。客户端仍须经过实际 HTTP 和解析器，不能以假 Repository 替代传输验收。源集和夹具目录遵循数据契约。

夹具服务默认仅绑定桌面 loopback，设备访问通过明确配置的调试通道（例如 USB adb reverse）；不能把桌面 localhost 与设备 localhost 当作同一地址。地址、端口、启动/停止与映射步骤写进夹具 README。若使用 loopback HTTP，仅在 debug 对确切域名/IP开放明文；不为了样例全局改生产网络策略。创建夹具文件、启动服务、运行测试分别记录，不能互相冒充。
