# 播放页面：UI 与能力协作

状态：协作清单，2026-11-26。用户提供的截图是播放布局参考；当前只做基本使用，UI 效果后续再细化。错误下载的小白 TV 不是参考对象。

## UI 负责

- 全屏画面、顶部来源/时间、底部自定义进度、操作提示与中英文；无操作 5 秒隐藏，交互期间保留。
- OK 播放/暂停，长按左右预览并松开提交定位，返回退出；触摸呼出、水平滑动/拖动进度条定位，下键/上滑打开列表。
- 列表区分焦点所在、已选择和已确认播放；正在播放的名称跑马灯。不能把点击或请求入队当成已播放。
- 不可定位或时长未知时禁止提交定位；不能把直播或未知时长伪造成有进度的点播。

## 请能力侧确认或完善

2026-11-26 HTTP 403 提示边界：业务侧可识别来源输入、选台时自身网络请求的 HTTP 403 并提示；SDK 内部播放清单、分片等请求失败目前只暴露 `PlaybackFailure.code` 与 `diagnostic`，没有结构化 HTTP 状态码，UI 无法可靠区分 403。请能力侧评估在不暴露 URL/凭据的前提下为实际收到的 HTTP 错误提供可选状态码，并验证分片请求、切源和旧错误路径；不要把所有 `SOURCE_IO` 或 `PIPELINE_FAILED` 当作 403。

| 需求 | 验证页当前入口 | 能力侧需要核对 |
|---|---|---|
| 播放/暂停和停止 | `AndroidPlayer` 与 `PlaybackSnapshot.phase` | 准备、缓冲、暂停意图、失败/退出释放；确认状态变化代表的时点 |
| 指定位置定位 | debug `SeekTo(positionMs)` → `seekTo` | 可定位和时长状态、定位完成/失败、连续操作及取消；UI 当前只在松手提交一次 |
| 缓冲提示 | `PlaybackSnapshot.bufferedPercent` + `BUFFERING` | 右上角仅缓冲期显示水位百分比，不作为整片下载百分比或可回看缓存区间 |
| 当前来源与切换 | debug `SelectSource` → `loadHttps`，按 SDK generation 过滤 | 切源取消和旧回调隔离；来源身份、加载中/失败/播放中状态的正式契约 |
| MP4/WebM/Ogg/MKV/HLS/DASH | 15 个公开测试地址，含 GitHub 文件与 3 个直播源 | 分别按容器、编码、设备实播验证及失败提示；不要把 HTTP 响应成功当成播放通过 |
| M3U 频道/分类列表 | 尚未接入 | 下载、解析、来源身份、列表加载/失败/取消和频道切换；本地 15 项仅为验证样本 |
| 直播 | 依赖 `seekable`、`durationMs` | 确认直播类型、DVR/时移范围与可定位性的契约；未知时 UI 保守禁用定位 |

SDK、协议、解码及媒体资源由能力侧负责；UI 不新增播放器时钟、不推断缓存为可回看内容。调试样本是否切源、播放成功由真实 SDK 状态决定，HLS/DASH 目前未验收。

## 能力侧反馈（2026-11-26）

- `CommandResult.Accepted` 表示命令已接收，不代表已出画、听到声音或定位完成；播放确认观察当前来源的 SDK 状态，物理音画仍需设备验收。`close()` 主线程调用、非阻塞，确需等待底层结束时使用 `awaitReleased()`。
- `seekTo` 默认 FAST，可能落在关键帧附近；它返回 Accepted 不提供一次定位完成的独立确认。`state.scrub` 属于 `beginScrub`/`scrubTo`/`endScrub` 流程，不能拿普通 `seekTo` 假定会产生完成事件；该流程的 COMPLETE 也不是物理屏幕扫出确认。未知时长/不可定位保持禁用，直播 DVR 边界尚未验收。
- SDK 的 generation 负责区分已经提交到播放器的来源；来源解析尚未提交前仍需 UI/调用方自己的请求序号及 Job。新选择或退出应取消旧解析，并在结果返回后再次核对来源序号、宿主状态及 player 所有者，避免旧结果调用 load。
- GitHub 直链的可选 `NetworkRedirectResolver.resolve(uri)` 已在独立 SDK 工作区 `D:/work/as/PlaybackSDK-playtv` 的 `codex/playtv-network` 候选实现，候选 AAR 只在该工作区的本地仓库，**尚未发布为 PlayTV 正式依赖**。返回 `Resolved.uri` 后才可交给原 `loadNetwork`；`Failed` 由 UI 转成用户提示，切源或退出取消协程。解析不自动播放、不保证媒体解码；不输出 URI 或查询参数到日志。该入口未实现 GitHub 仓库浏览、HLS 子请求或 Emby 鉴权。
- vivo V2244A / Android 14 已通过一轮公开 GitHub 文件的解析、出画、暂停及定位后恢复；同一候选下，原接口直连可访问 raw MP4 也通过相同基础专项，原接口访问 302 仍报错。声音与真实 TV 遥控器尚未验收，不能将候选 AAR 当作全部旧功能回归通过。
- HLS 样例在同手机上解析顶层地址成功，但现有候选播放报 PIPELINE_FAILED，日志提示缺少 `application/x-hls` 解封装；当前 SDK 构建配置未选择 HLS 插件。HLS 尚不可作为可用来源，后续需处理插件及子清单/分片请求并实播验证。
- 后续仅在另一个隔离实验构建中启用 HLS 和 MPEG-TS 插件：上述样例越过缺插件阶段，但 AAC 解码失败；不同样例曾进入 PLAYING，测试被切前台中断，未取得完整验收证据。根因仍待确认，不应接入该实验包。详见 `D:/work/as/PlaybackSDK-playtv/docs/network-playback.md`。

## 验收对齐

播放、暂停、触摸、遥控器、可定位/不可定位、5 秒隐藏、切源与返回释放都需要真实设备复测。每个变更记录版本、设备、场景、有效轮次及失败证据；详细需求与结果维护在 [验证 Spec](../feature/test-tv-playback.md)，不将本清单当成完成证明。

## 设备结果与待排查

### 待能力者处理：GitHub WebM 样本原生崩溃

- 触发：V2244A / Android 14，在 PlayTV 列表从 Bunny 或 Oceans 切换到 `GITHUB_WEBM`（`https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.webm`）。17:16:41、17:17:27 两次发生原生崩溃，系统 `dumpsys activity exit-info com.ai.playtv` 记录 `APP CRASH(NATIVE)`、signal 6。此前 17:07:02 也有原生崩溃，但当时缺少来源标识，不能将其直接归为同一原因。
- 证据链：第二次日志显示 `source=GITHUB_WEBM generation=2`，创建 `c2.android.vorbis.decoder` 后，`gst_amc_audio_dec_handle_frame` 报输入 dequeue `-10000`，`gst_amc_audio_dec_loop` 的 `gstamcaudiodec.c:492` 报 `code should not be reached`，随后 `SIGABRT`。第一次则先报获取 input buffer 失败后出现同类信号。进程日志见 `build/verification/tv-ui-20261126/crash-process-15160.log` 和 `crash-process-16692.log`；后者为日志记录确认的 Bunny → WebM。
- 目前证据将问题定位到原生音频解码/错误处理链，**具体根因、是否为 SDK 自有修改或上游兼容缺口、跨机型范围均待能力者查证**；不能仅凭错误码归咎平台，也不能按 WebM 扩展名认定所有 WebM 都失败。当前 SDK AAR 静态摘要 `fb04deaa5f3bb147915dcf5da98bb74ffcb9b7500f303b90df20d4e95077453d`；崩溃进程的完整 APK 摘要尚待核实。
- UI 暂时禁用这一个已触发崩溃的样本并明确显示待修复，不视为修复；保留完整链接和错误证据供 SDK 独立重现。请能力者定位后修复实际责任层，验证失败时不杀进程、同会话可恢复，再按共享播放链影响等级完成至少 20 次有效专项及原 MP4/MKV、暂停/切源/释放回归；其他格式按实际影响补验。

- V2244A / Android 14、APK SHA-256 `541078991de1214710a3b9d793cfabbba46d1bd67c047c6cec4a5655f6d04d96`：Bunny MP4 达到 PLAYING 和 ENDED；GitHub Matroska MKV 样本一次观察到 PLAYING 和视频画面。仅限这次文件及设备，声音、定位和不同编码仍待验证。
- 17:03:31 观察到 `PIPELINE_FAILED`，当时缺少来源标识，无法确认是哪个链接或格式；请勿据此认定某项不支持。验证页已补充仅含样本枚举及代次的调试日志，下一构建可按同源重试并区分网络/协议/解码/输出原因。
- HLS、DASH、Ogg、WebM、直播及各来源受网络/重定向影响的实际表现待逐项验证；失效项只有同一地址、设备和日志可归因后才移交能力侧修复。
