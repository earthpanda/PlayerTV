# TV 播放与遥控器技术验证 Spec

## 目标与范围

用户提供了小白播放器的播放页截图，要求参考其全屏视频、顶部片名/时间及底部进度布局；OK 播放/暂停、长按左右快进/后退、返回退出，触摸支持水平滑动定位和进度条操作，交互结束 5 秒后隐藏进度层。不可定位的直播不执行定位。控件文案需支持中英文。本次在 debug 技术验证页接入，正式设计宽度及截图中的图标资产仍未提供，不能将技术页当作正式页面。公开 W3C Bunny 视频仅用于播放验证。

2026-11-26 更正：先前下载的 `ml.acloudtwei.zhibo` 5.2.0 是“小白 TV”，用户确认并非所需的“小白播放器”；其分析结果不可作为本界面依据。安装任务在中止前返回成功，已安装到手机；未启动，未擅自卸载。APK 与 REA 证据暂存于系统临时目录 `playtv-reference`，下段仅保留排错记录。

静态线索：REA 解析出 12 个可见顶层类，清单中的播放器 Activity 不在可见类中；APK 仅含 `armeabi-v7a` 原生库，并含壳相关库，推测代码受保护，运行时加载机制尚未确认。Android SDK `aapt dump xmltree` 显示 `res/layout/layout001d.xml` 含全屏视频视图和底部信息布局 `layout0022`；后者含频道、当前/下个节目、时间等文字和图片节点；`layout001f` 包含频道相关列表，`layout0032` 包含进度控件及“按返回键可退出时移”文本。布局名/资源名已混淆，上述是静态结构，不能证明各节点运行时可见性、焦点、动画和实际像素效果；图标资源尚未确认并提供。REA 证据包保存在系统临时目录 `playtv-reference/xiaobai-rea-evidence.json`，APK 分析工具及摘要见上述记录。

## 设计、承载与交互

用户截图是本轮播放页视觉参考，文字及普通 shape 为技术验证实现；未提供截图中的图标和正式画板宽度。debug Manifest 将启动入口切换到测试 Activity；release 继续保留原样例。Activity 横屏全屏显示，OK 播放/暂停，长按左右预览目标并在松开后定位，返回退出；触摸画面可呼出控制层，水平滑动或拖动自定义进度条定位。手势结束或按键后 5 秒隐藏控制层，操作期间不隐藏；不可定位或时长未知时不发定位请求。遥控下键或画面向上滑动打开列表，列表独立关闭、返回键仍退出页面。pt 宽度未配置，验证页仍使用系统基线，正式页面不猜测画板宽度。

初始化、准备、缓冲、播放、暂停、结束和失败由 SDK 状态映射；显示时间及错误。结束或失败可重新载入；播放器未就绪或不支持定位时禁用定位。页面后台暂停，不自动恢复；窗口销毁后解绑 Surface。

触摸播放/暂停文字复用同一播放事件；播放状态、错误和操作提示使用中英文资源。短按左右仅呼出控制层，不执行定位；定位预览不冒充已播放位置，取消或离页不提交。长按步长和触摸灵敏度是验证页实现参数，需设备体验后确认。

方向识别采用至少两倍系统 touch slop，且主方向位移至少为另一方向的 1.5 倍；未达到条件的斜滑不触发定位或列表，锁定后该次手势不再改变方向。向上滑打开列表，向下滑不打开。触摸方向计算需对应单测及设备复验，首个安装包尚未包含此调整。

列表暂列 7 个 HTTPS MP4、GitHub 的 WebM/Ogg/MKV，以及 HLS、DASH、3 个公开直播样本；这只是调试样本，非 IPTV 目录。2026-11-26 本机对 7 个 MP4、WebM/Ogg/MKV 和 HLS/DASH 获取文件头或清单均获 HTTP 206；所选 3 个直播清单返回 200，各自首个媒体分片返回 206。3 个 GitHub MP4 还验证了非零 Range 的 206。这仅证实当时地址响应，不证明设备或 SDK 能播放、后续仍可用。GitHub 文件来自 [mediaelement-files](https://github.com/mediaelement/mediaelement-files)、[MDN interactive-examples](https://github.com/mdn/interactive-examples)、[Matroska 测试文件](https://github.com/matroska-org/matroska-test-files)，直播地址从 [iptv-org/iptv](https://github.com/iptv-org/iptv) 的公开清单筛选。列表以焦点、已选和 SDK PLAYING 状态分别表达焦点、选中和正在播放；正在播放的名称允许跑马灯。请求被接受仅表示开始加载；实际播放以 SDK 状态为准。WebM/Ogg/MKV/HLS/DASH 支持情况待能力侧验证，失败展示现有错误状态。选源和指定位置定位仅在 debug 验证页通过现有 SDK API 接入，双方后续接口及能力缺口见 [UI 与能力协作](../collaboration/player-ui-capability.md)。

## 数据契约与封装边界

`model/src/debug/.../contract/tvplayback` 定义 UI State/Event/Effect；`app/src/debug/.../TvPlaybackViewModel` 管理页面播放器、加载和命令，`TvPlaybackActivity` 管理 Surface 和焦点。直接使用 `AndroidPlayer` 主线程命令与 state；SDK 负责网络、解码、音频焦点和释放。复用 `BaseMviActivity`、`BaseMviViewModel` 与 Factory；模板 sample 未改动。

## 网络、并发、资源与生命周期

直接 HTTPS 媒体由 SDK 加载，未引入 Repository 或网络库；地址只作验证。单页单实例，异步创建由 ViewModel 作用域持有；Surface 由 Activity 绑定和解绑，onCleared 关闭播放器。不使用 Service、持久存储、运行时权限或后台播放。后台暂停与 Surface 重建需专项验证，不包括生产端进程恢复。

## 变更影响与回归验证

基线：debug 和 release 原启动模板 SampleActivity，已构建安装但未做播放验证。允许 debug 启动入口变化，release 启动及模板代码应保留。验证 debug 构建、合并清单、手机实际播放和暂停、定位、ADB 模拟遥控器按键及退出；手机不等价于电视和真实遥控器。正式界面、其他网络源、电视 ABI 与遥控器真机尚未验证。SDK AAR 的历史 SHA-256 为 `fb04deaa5f3bb147915dcf5da98bb74ffcb9b7500f303b90df20d4e95077453d`，本轮构建前已核对一致。

2026-11-26 UI 调整：用户反馈手机上已看到 Bunny 画面，此为用户报告，非本轮重新实测。调整前验证页使用平台 Button 和 Activity 内的焦点颜色；本轮仅改 debug 控件类型、状态颜色、中英文文案及不可定位时焦点回退。保持点击事件、播放状态判断、定位步长、Surface 和后台生命周期不变。新旧路径需在同一设备验证：初始焦点、左右移动及确认、播放/暂停、可定位时前后跳转、定位失效时焦点回退、返回/后台及再次进入；每项可重复场景至少 3 次有效轮次。目标电视和物理遥控器另验，不能以手机替代。

同日进一步将验证页播放阶段和通用错误提示改为中英文资源；ViewModel 状态、事件和 SDK 错误处理未改，具体故障码仍可通过已有日志诊断。需分别切换中英文验证所有可到达状态和长文案显示，并回归既有播放、定位及焦点；当前尚未执行构建或设备复测。

同日按截图调整全屏与输入：新增触摸/遥控手势、自定义进度条、5 秒隐藏及调试播放列表；debug Contract 增加绝对定位和选择来源事件，ViewModel 复用 SDK `seekTo` 和 `loadHttps` 并按来源代次过滤状态。允许改变验证页控件和输入；需保持视频画面、音频焦点、后台暂停、返回释放、现有 Bunny 播放及失败语义。计划单测覆盖手势映射、边界及无效时长；设备复测至少 3 轮，覆盖短按/长按/松开/取消、触摸拖动/上滑、直播不可定位、列表焦点/选择/播放中、切源失败、5 秒隐藏、返回和双语。正式电视环境与真实截图差异分别记录。实际执行结果见下，不以静态检查代替设备回归。

右上角复用 SDK `PlaybackSnapshot.bufferedPercent`，仅 `BUFFERING` 时显示，独立于 5 秒隐藏的控制层。该值是缓冲队列水位，不是整部视频的下载百分比或可回看范围；参考 JustSoSo 验证页使用方式，实际设备网络缓冲场景待验证。

## 完成状态

本轮 `:app:testDebugUnitTest :app:assembleDebug --no-daemon --offline` 通过，6 项定位及手势方向单测、0 失败/错误/跳过，结果在 `app/build/test-results/testDebugUnitTest/TEST-com.ai.playtv.ui.test.tvplayback.TvPreviewSeekMathTest.xml`；debug APK SHA-256 `801b622a71395892edcacd8c4e3eda8a7105f06c13ee884a7df66a099afe6d89`。此前一次运行与另一会话构建有时间重叠，已在确认无 Gradle 进程后重跑；构建结束后未见本次残留 Gradle 进程。`python tools/check_architecture.py` 检查 37 个 Kotlin 文件，0 违规。以上不证明真实画面、按键、触摸、直播、切源或资源释放通过。

2026-11-26 本轮按用户明确要求执行 `:app:assembleDebug --no-daemon --offline` 成功，APK SHA-256 `541078991de1214710a3b9d793cfabbba46d1bd67c047c6cec4a5655f6d04d96`；安装到 V2244A / Android 14，设备 APK 摘要一致。两次安装器交互被同设备其他会话切换前台中断、返回 `INSTALL_FAILED_ABORTED`，第三次成功。启动后日志依次出现 PREPARING、BUFFERING，约 0.4 秒后像素游戏验证抢占前台；因此不能判断起播或画面。截图和安装证据在 `build/verification/tv-ui-20261126/`，被其他应用占用的截图不作为本页视觉证据。

手机恢复前台后，Bunny 播放日志出现 PLAYING 并走到 ENDED；之后观察到 GitHub Matroska MKV 来源显示不同视频画面，状态为 PLAYING（截图 `touch-show.png`、`touch-after-6s.png`），同一操作中控制层在 6 秒后隐藏。本轮这些场景各为 1 次观察，不足以覆盖所需 3 次或证明其他编码、音频与定位。17:03:31 有一次 `PIPELINE_FAILED`，该安装包没有来源标识日志，无法归属具体地址；不得归因为某一格式。随后被其他会话的安装器切走，暂未完成其余来源与操作复测。

更正此前对“闪退”的判断：系统 `exit-info` 记录 17:07:02、17:16:41、17:17:27 均为 PlayTV 原生崩溃；后两次日志确认均在切换 `GITHUB_WEBM` 后出现，音频解码失败并触发原生断言/`SIGABRT`。根因仍由能力侧定位，详见[协作记录](../collaboration/player-ui-capability.md)。此样本在最新源码中暂时禁用并标明待修复，不声称已修复；最新 APK 尚未安装验证。

当前验证页仍待设备验收：截图中的图标资产和正式设计宽度未提供，视觉还原与真实电视交互待验收。M3U 列表加载/解析、其余格式解码、长按/横滑/上滑、暂停、后台释放及受影响旧播放路径仍待验证；用户侧反馈的旧 Bunny 画面不代表本轮回归通过。

## 网络地址识别与导入（debug 技术验证）

输入框取得焦点后，触摸其范围外的页面会收起键盘、清除焦点及当前输入错误提示，并保留地址；触摸提交和测试来源仍应正常执行。遥控器焦点移动不拦截。该行为复用 `KeyboardController`，控制器随 Activity 销毁释放；需在中英文、键盘显示/隐藏及提交时验证。

退出崩溃追溯：`deb8ba...` 包在 V2244A 的 19:03:14、19:04:51 两次退出播放页发生 Java 崩溃；`build/verification/source-entry-20261126/crash-close-15912.log` 记录 `NetworkOnMainThreadException`，栈为 `NetworkDependencies.close → ConnectionPool.evictAll → TLS socket.close`，由 ViewModel.onCleared 主线程调用。问题来源为本次采用的网络模板原同步关闭方式和接入时遗漏生命周期线程验证，修改层是公共网络依赖及模板；改为 close 立即禁止新操作、IO 异步关闭，提供 awaitClosed 等待实际完成。全局审阅本项目网络/文件/关闭调用：响应体正常、失败和取消收尾在 IO；Retrofit JSON 转换及拦截器由 OkHttp 工作线程执行、错误体由 Retrofit 缓冲；文件写入已在 IO，模板及业务项目的目录规范化和 Uri 授权也改为挂起并切到 IO。其余 UI 控制器的 close 只清理 UI/回调，播放器 close 的非阻塞契约由 SDK 负责。该审阅是静态范围检查，不保证所有平台/第三方内部实现无阻塞；修复后需进行至少 20 次同条件播放后返回、并发 close/取消和旧路径验证。

取消路径仍需专项核查：OkHttp 4.12 `RealCall.cancel()` 会同步调用活动 Exchange 的取消方法；Retrofit 挂起请求被主线程取消时是否导致设备上的 socket 关闭阻塞尚未验证，不能由响应体关闭切至 IO 推断整个取消链均在 IO。

追加复测：`deb8ba...` 包在同一设备、同一 VideoLingo `demo.mp4` 地址上共完成 20 次进入并达到 SDK PLAYING；第 4 至第 20 次记录在 `build/verification/source-entry-20261126/direct-repeat.txt`。该直链的 20 次目标已达到，旧失败根因依然待查；此结果不替代其他格式、失败恢复及仓库解析的验收。

新增 debug 地址输入页，选择示例或输入 HTTPS 地址后点击解析，使用模板公共操作 Loading，允许取消；成功后进入播放页、自动选择首个可用候选。直接媒体、HLS 或 DASH 是单项列表；M3U 频道列表和 `名称,地址` TXT 生成多个候选，最多加载前 100 个，超限、无法支持的条目或扫描截断需明确提示。已知会原生闪退的 WebM 样本仍禁用。来源维度分为 GitHub 仓库、GitHub 文件/附件和普通网络地址；内容维度分为媒体候选、频道列表、HLS、DASH、未知。仓库按 GitHub 默认分支 tree 查文件及 Markdown 中直接链接，至多检查 12 个 Markdown 和 8 个列表文件；结果是候选，不能当作已解码。扩展名仅作发现线索，HLS 与频道列表依内容判断。网站及网盘分享页不能直接交 SDK；用户所列工具仓库亦不能假设含现成视频。GitHub API 限流、私有仓库、HTTP、组播、需要额外请求头或交互授权的源暂不能完整处理。失败、空结果、部分结果和解析中均有状态；禁止重复提交，取消和离页由 ViewModel 取消；不会在日志显示 URL、凭据或签名。结果保留原始地址，选择时重新解析有限次 HTTPS 跳转，不持久化签名地址。

真机 VideoLingo 首次解析后出现 `utils.ts`、`next-env.d.ts` 排在 `demo.mp4` 前，播放器首项报 `UNSUPPORTED_MEDIA`；列表截图 `build/verification/source-entry-20261126/videolingo-list.png` 显示了错误候选。证据支持的根因是本次解析实现把 `.ts` 扩展名直接当作 MPEG-TS 文件，而该仓库中的同后缀文件是 TypeScript 源码；属于本轮业务解析实现遗漏，并非据此证明 SDK 的 TS 播放能力缺失。修复层为 model 的来源解析：仓库内 `.ts` 候选最多抽查 8 项、读取头部验证 188 字节包同步特征，通过才放入播放列表，超限提示部分结果；用户直接输入 TS 或清单内的 TS 地址仍交播放侧。该探测只覆盖常见 188 字节包，其他 TS 变体可能漏检。修复后需同样的真实仓库及单测复验，不以网络地址响应代替播放验收。

用户给出的 IPTV2025 IPv4 清单在 V2244A 上成功生成频道列表（截图 `build/verification/source-entry-20261126/iptv-list.png`），但首个 HTTPS 候选 CCTV17 在交给 SDK 前的地址解析阶段失败，原因尚未定位，不能将其归为 SDK 解码问题。检查发现当首项解析失败且 SDK 仍处于 IDLE 时，播放键不能正确重试该来源；原因是本次业务层仅保留 SDK 已接受的来源，未保留待解析来源，修复层为 ViewModel，记录待解析项并在 IDLE/失败时重试。需用可控失败/恢复场景和设备实际重试复验，不能仅凭代码修改宣称已修复。

`-DTest=true` 时显示示例地址选择，留空可进入原测试列表；未传或 false 时不显示示例，留空提示输入且不进入播放。构建验证默认传 true。测试列表追加 FLV、AVI、MOV、M4V、TS 及标明音频的 MP3/M4A 样本；GitHub 上的 FLV/MP3/M4A 和 filesamples 上四个视频在本机曾返回媒体响应，后者不支持本次 Range 请求，不能据此认定 SDK 播放或定位可用。扩展名为 AAC 的候选经 ffprobe 实为 MP4 音频容器，未当作裸 AAC 添加。

复用现有全屏列表及 BaseMvi 组件，以文字输入控件作为技术预览；既有焦点、播放、手势、进度及后台行为需保持。用户明确的图片资产与正式宽度仍未提供，页面不是正式 UI。网络使用项目模板 network 的客户端和 Retrofit/Gson，GitHub JSON 走类型化 Service/Repository，其他清单及跳转有界读取，超时、关闭响应体、取消及无鉴权跨域。新增 OkHttp 4.12.0、Retrofit/converter-gson 2.9.0、Gson 2.10.1；feedback 模板提供可取消 Loading，不新增播放依赖。验证须覆盖分类、相对地址、失败/取消、部分扫描、直接地址和旧播放路径，单测、构建、真机分别记录实际结果。来源示例只用于发现与验证，响应成功不代表影片可播放或之后一直有效。

本轮 `:model:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --no-daemon --offline -DTest=true` 成功，解析规则/仓库夹具 8 项和原手势 6 项通过；其仓库测试使用受控假 Service，不等同真实网络链路。另以 `-DTest=false` 生成 BuildConfig 并核对 false，再恢复 true 构建；false 包交互尚未在设备验证。APK SHA-256 `707ca6c234f06477ed6b7e5c5567bc9bf8af79dafbf6421a78d8e8816b670d8c`，V2244A / Android 14 安装后拉回摘要一致；输入页及测试来源入口显示一次，其他会话的安装器随后抢占前台，空地址进入、实际解析与旧播放流程尚未完成有效设备验证。桌面网络对用户指定 VideoLingo 仓库的 GitHub API 请求返回 403 限流；此前原始媒体文件和附件重定向能响应，不代表本轮 APK 已解析成功。结构扫描 70 个 Kotlin 文件、0 确定性违规；代码变化后仍须重跑并审阅。按一般功能默认 3 次有效轮次，当前尚未达到，不得称为完整验收。

后续验证：`707ca6...` 包的空地址进入默认 Bunny 并达到 PLAYING 共 3 次，其中一次截图显示画面；该结果不替代新包全部旧路径回归。`aa8b6e...` 包修正 `.ts` 误判后，设备 GitHub API 返回 `403 rate limit exceeded`，未达到目标仓库复测场景；此为 0 个有效修复复测轮次，目标 10 次，暂不能称该问题已验收。该包的 IPTV2025 清单生成了频道列表但首项解析阶段失败，参见上文。

同设备对 VideoLingo 的 `demo.mp4` 直接地址，`4d47be...` 包显示解析失败；设备系统 curl 对同地址返回 200。未记录到失败异常类型，故原因和来源尚未查实；业务解析实现随后将媒体响应的读取及关闭移到 IO 线程，避免可能的主线程网络操作，属于依据调用链的推断性修正。`deb8ba18e2b7c27aee701fd239584867a86e7969d020579614f5f0951fb961ac` 包安装后同地址 3 次达到 PLAYING，一次截图见 `build/verification/source-entry-20261126/direct-io-result.png`，列表仅含一项的截图见 `direct-single-list.png`；这一结果支持新路径可用，但不能证明旧失败根因或完整修复。该公共解析链按较高影响需 20 次有效同条件复测，当时完成 3 次（后续补足至 20 次，见上方追加记录）；仍需覆盖取消、失败恢复和旧来源。最新包 16 项单测通过，含解析与受控仓库夹具 10 项、手势 6 项；结构扫描 71 个 Kotlin 文件、0 硡定性违规。IPTV 同一首项在最新包显示播放失败，缺少可归因日志，待核对 SDK 初始化、网络和流内容，不得直接认定解码故障。手机安装器与其他会话交替占用，真实电视、false 包、声音及其余容器仍待验收。
