# Android 原生播放 SDK 按需接入

这是独立 PlaybackSDK 的接入参考，不是默认能力包。未接入的项目不增加媒体依赖、权限、原生初始化或后台任务。SDK 独立维护源码，业务工程继续保留 app/base/model/support。

当前 SDK 坐标 `com.ai.media:playback-android:0.1.0`，源码工程名 PlaybackSDK，Android namespace `com.ai.playback`。内核锁定 GStreamer 1.28.8；SDK 的 `upstream.lock.json`、`tools/prepare_upstream.py` 和 `third_party` 管理开发包校验及上游源码。它不依赖生成模板或任何业务工程。

## 接入与依赖

开发时在 settings 用显式 Gradle 属性指定 SDK checkout，配置 composite build 的依赖替换；不依赖某个机器的绝对目录，不把它当作第五个业务模块：

```kotlin
providers.gradleProperty("playbackSdkPath").orNull?.let { sdkPath ->
    includeBuild(file(sdkPath)) {
        dependencySubstitution {
            substitute(module("com.ai.media:playback-android")).using(project(":android"))
        }
    }
}
```

正式分发使用 SDK 的 Maven publication，交付 AAR、POM、源码及 native/build 资料；不要只拷贝 AAR 丢失 coroutines 依赖和源码基线。仓库地址由使用工程明确提供，不能伪造已发布的公共 Maven 地址。

首次交付可先发布到 SDK 的 `build/repository` 文件 Maven 仓库；它有正常的坐标、POM 和 Gradle 元数据，不是 `flatDir` 或 `mavenLocal()` 全局缓存。消费工程通过显式仓库路径/URL 引入，PlaybackSDK 坐标限定到该仓库，缺少产物直接失败，不自动切到源码工程。之后迁移到真实远程 Maven 时，更换仓库地址，保留兼容的依赖坐标；Git 源码地址不等于 Maven 仓库。独立示例及发布/验证命令由 PlaybackSDK 的 `sample/README.md` 维护，生成项目不自动下载、发布或运行示例。

源码可修改、完整内核已从源码构建、AAR 已采用新内核及设备回归通过分别核对。采用完整源码产物时，记录 SDK/GStreamer/Cerbero 的提交或内容摘要、依赖配方/工具链、插件集合、实际链接前缀和 AAR/native 摘要；Java helper、证书与许可证也必须来自所选前缀，不能仍混用默认开发包。构建失败不得静默退回预编译内核。先保留上个已验证产物作对照，按实际受影响能力回归播放、音轨/字幕、倍速/循环、定位/逐帧、截图/导出/录制及释放。仅有源文件、快照准备成功或构建命令退出成功都不等于这些验证已完成。

独立接入验证应使用单独 Android 工程，只解析实际发布的 Maven 坐标及其传递依赖，不使用业务工程类、`includeBuild` 或本机预编译缓存冒充独立 SDK。确认最终 APK 内 native 与交付 AAR 一致，并通过公开 API 运行基本播放和释放。具体源码构建及独立 Demo 命令由 SDK README 维护；本参考不在生成项目中自动安装 Linux、下载内核或执行构建。

技术样例可先在 support 使用 `debugApi("com.ai.media:playback-android:0.1.0")`，app 的 debug 页面直接使用 SDK。这里 api 是明确暴露 SDK 公共类型，不额外创建同名转发类；release 不携带它。正式业务需要时另在 Spec 确认接入范围和依赖配置。

## 已实现契约与真实示例

[PlaybackUsageExample.kt](PlaybackUsageExample.kt) 展示创建、观察、输入句柄、Surface 和关闭。AndroidPlayer.create 在 IO 初始化 GStreamer，随后在主线程创建会话；全部命令及 close 在主线程，awaitReleased 用于确认原生资源实际结束。初始化只保留 applicationContext，没有进程级播放器或自动播放。

- 页面持有 AndroidPlayer，或由有明确生命周期的 ViewModel 持有；View/Surface 宿主仍负责绑定与解除 Surface，离开页面停止播放，最终所有者 close。
- SAF 用 ACTION_OPEN_DOCUMENT 获取 Uri，在 IO 打开 ParcelFileDescriptor。load 在 JNI 返回前复制 fd，调用方随后关闭原 fd；不把 content Uri 强行转成路径，不把整个文件读进内存，不获取广泛存储权限。
- 网络 MP4 点播使用 SDK `loadNetwork` 的 HTTP/HTTPS 直链，保留 `loadHttps` 兼容入口；媒体数据由内核 source/解封装读取，不经过业务 JSON Repository，也不先整段下载或增加另一套播放器。HTTPS 验证证书/主机名；HTTP 必须通过宿主针对目标域名的 Network Security Config，不能全局允许明文。当前不自动重定向或重试，3xx/404/断流明确失败，避免重定向绕过来源策略。加载与播放确认分别观察，缓冲不改用户暂停意图；起播和播放中预读的水位以采用的 SDK 版本为准，队列缓冲百分比不代表整片下载百分比或可供拖回复用的磁盘缓存。不支持 Range 的源不保证定位/循环。切源、停止、离页与关闭须取消旧网络读取、隔离迟到回调并释放连接；已有本地输入仍按原契约验证。无 HLS、DRM、凭据、缓存或后台下载需求时不预先引入。
- 原生会话一个有界命令邮箱和一个 GLib 控制线程；解封装、解码、同步、输出线程复用 GStreamer。帧留在原生管线，500ms 汇报一次状态/计数，不逐帧传 Java 大数组；当前二十一类回调合并到最多一个待派发任务。
- 暂停、停止、解除 Surface 和关闭具备有界保留入口；主线程回调按当前源及事件类型合并，避免宿主繁忙时无限排队。SDK 管理音频焦点，失焦后暂停且不自动续播；命令拒绝时归还新申请的焦点。
- 本地基础扩展：`setVolume(0.0..1.0)` 和 `setMuted` 调整本播放器音频，不写系统音量；音量与静音由 native 确认且随会话保留到下一来源。`seekTo(positionMs, SeekMode.FAST/ACCURATE)` 区分关键帧定位与精确定位；定位可清空重缓冲，不能混同保留视频管线的倍速命令。`state.mediaInfo` 提供已协商的尺寸、帧率、像素比例、旋转及音频信息，未知值不伪造；切源清理旧媒体信息。全屏、等比适应/裁剪填满和窗口 Insets 留在宿主 View，进度历史留业务 Store，不由 SDK 自动持久化或切换屏幕方向。扩展的构建/设备验收以使用工程的记录为准。
- AUTO 采用 Android 硬件优先的内核 rank；HARDWARE_ONLY/SOFTWARE_ONLY 只控制视频候选，诊断取协商后的实际解码器，区分 MediaCodec 软件实现。默认不重试，不反复切换和重建解码器。
- 视频流的候选全部不可用时，应明确报告 UNSUPPORTED_MEDIA；GStreamer 可能仅暴露音频并进入 PAUSED，不能把准备状态当作视频已可播放。SDK 应区分缺失视频解码器与合法纯音频、不可用的备用视频轨和可用的其他轨道；AUTO 的正常软解回退继续保留。采用时核对实际 SDK 版本，并验证原生错误、释放及同会话重开，不在页面增加黑屏超时/自动重载旁路。
- 原生输出的生命周期验收覆盖全部分支：音频节点也可能持有管线传播的 GL context，不能只检查视频节点、控制线程和来源 fd。嵌套输出 bin 按实际浮动/非浮动引用契约移交所有权；用最终销毁通知和重复切源后的线程、fd、内存趋势核对释放，不靠额外 unref 未知引用或共享显示对象掩盖泄漏。测试探针留在测试源集。
- 自有 C++ GstGLFilter 在 GPU 对 RGBA/2D GLMemory 执行 Shader，保留 alpha，不映射 CPU 像素。NONE 保留原色，GRAYSCALE 输出灰度，节点常驻且复用同一 Shader。`setVideoEffect` 在下一视频帧切换参数，不重载/seek/重建解码器或音频输出；暂停画面在产生下一帧后更新。滤镜放在视频输出 bin，避开 playbin video-filter 自动插入的 CPU videoconvert，并启用 native-video。HARDWARE_ONLY 额外约束解码输出 GLMemory；AUTO 仍允许可用软件解码器，SOFTWARE_ONLY 可将软解结果上传 GPU。GPU 初始化/协商失败直接报错，不回退旧 CPU 灰度。
- 倍速范围 0.5–2。SDK 使用内核 INSTANT_RATE_CHANGE，只改变速率，start/stop 均为 NONE；保留视频缓冲、不跳关键帧、不重建解码器，不支持时保留旧速率并明确拒绝，不静默退回 flushing seek。音视频由同一 GstPipeline 的即时速率协议协调；音频常驻基于锁定 SoundTouch pitch 源码构建的节点，仅补偿 AudioBaseSink 速率转换的音高变化，tempo/rate 保持 1，PTS/时长不做第二次缩放。普通定位时保留输入 segment.rate，防止双重变速；重复切速的 multiplier 相对于最近的普通 segment。调速后的旧 PCM 处理须以采用版本的 SDK 契约及需求为准：采用丢弃策略时，仅在音频串行边界清理算法、ringbuffer 与设备待播数据，保留共同播放时钟和最新映射，允许被丢弃区间暂时无声，不能伪造 PTS 把后续声音提前填空。命令接受不等于清理完成或同一采样/严格下一帧已呈现；需覆盖完整瞬态与稳态音高、视频间隔、同步、暂停/seek/循环/连续切速及释放，不能仅改事件标志或通过 JVM 测试就声称无缝及同步正确。
- Android 26+ 音频输出由 SDK 自有源码 `aaudio_sink.cpp` 的 `playbackaaudiosink` 接入系统 AAudio，沿用 GstAudioSink 的输出线程和音频时钟。阻塞写入以 20ms 为可中断片段，等待容量总计最多约 250ms，状态转换等待有 100ms 截止；系统报告旧音频流断开时，由音频写入线程有界重开默认流，具体输出设备由系统决定。暂停/失焦时保持暂停，等实际继续播放才恢复音频；其他错误、重开失败或持续无进展的重连仍明确报告，不自动回退 OpenSL ES。正常释放在输出线程退出后关闭，设备恢复则由写入线程关闭旧流；实际系统 close 耗时仍须设备测量。这是针对有声 EOS 清理时 OpenSL ES 系统锁竞争的输出边界替换，不代表上游本体已全量重编或所有设备已验证。
- 输出时钟查询读取独立的设备位置快照，按 AAudio 的单调时间戳估算已消费帧并限制在已提交帧内；不能持有阻塞 PCM 写入的锁查询时钟。启动后先写入 PCM，不能等待 STARTED 后才喂首帧；设备暂时没有写入容量并非 IO 失败，需要在有总截止的等待内检查暂停/停止。
- debug native 构建通过 `PlaybackSync` 保留 load、状态、seek/倍速、缓冲边界和释放事件；正常 PLAYING 每十秒最多一条合并快照，暂停不周期输出。QoS 仅报告新增消息，在暂停/释放边界补报尚未输出的增量，不反复输出历史统计；release 不安装 probes。入口时间戳早于实际显示/发声，不输出伪精确的音画差值；需结合 QoS 和已知同步点片源验收。Logcat 日常查看使用当前应用进程及 INFO 以上过滤，避免系统 Codec2 的逐帧 Verbose 干扰；这只是查看过滤，不修改设备全局日志设置或屏蔽错误/警告。SDK 的 `docs/android-integration.md` 说明字段与采集方式。
- 缓冲日志保留起止/重新缓冲边界，中间进度每秒最多一次并记录百分比及合并消息数；重复百分比不重复通知 UI。`pipeline_released` 记录 NULL/管线释放的返回状态和耗时，`PlaybackAudio` 记录实际音频设备准备及关闭结果。厂商 Codec 枚举消息与实际协商/播放错误分开核对，不全局屏蔽 ERROR。
- 无 Service、后台播放、DRM、自动下载或 AI 模型；编码导出与生产业务另按 SDK 实际版本及 Spec 接入，不能凭 GStreamer 支持列表声称已交付。

固定 SDK 基线首轮已在 Windows / NDK r29 完成 JNI/C++、Kotlin 和 Java 构建，生成 debug/release AAR 及本地 Maven 产物，九项状态边界单元测试通过。两种 AAR 的三份 arm64 共享库均通过 ELF 16 KiB LOAD 对齐检查，175 份上游许可、Java helper 和证书已打包。上游本体采用锁定的官方静态开发库，自有 JNI/滤镜从源码编译；未重编全部上游。

使用工程仍需按本工程授权构建自己的变体，并验证设备解码、性能、阻塞 IO 和长时间释放。上述 SDK 构建及 JVM 状态测试不代表真实播放已验收，不把模板静态生成核对写成播放通过。

## 内嵌文本字幕接入

采用已提供字幕 API 的 SDK 时，用 `state.subtitleTracks` 的当前源对象调用 `selectSubtitleTrack(track)`，`null` 表示关闭；候选、`pendingSubtitleTrack` 和 `selectedSubtitleTrackIndex` 分开。默认关闭，停止/切源/释放清理列表与文字，不能复用旧来源索引。确认前清掉旧文字；暂停切换可能等到恢复播放才确认，不为切字幕自动播放或全局 seek。

SDK 的原生 text-sink 只接收有界的文本 buffer，GStreamer 负责解析、流选择和时间同步；当前字幕按实际视频输出时间确定。页面只显示 `state.subtitleCue?.text`，用平台字体叠加在视频区域，普通/全屏共用同一入口，不用 Java 定时器推算结束时间。文字按纯文本处理，保留换行；首版不承诺 ASS 排版特效、位图字幕、字幕延迟或字幕烧录导出。视频仍沿硬解→GLMemory→Shader→渲染，不为字幕映射视频像素或强迫软解。

验证需要带已知时间点的真实字幕轨，覆盖中文/英文/多行、空白间隔、选择/关闭、暂停/定位/倍速/两种循环、停止/切源/释放；既有声音、GPU 效果、拖动和逐帧须按共享影响回归。模板只提供采用契约，实际交付和设备结果看 SDK 及使用项目记录，不因底层支持列表声称所有格式可播放。

字幕时序诊断应关联来源代次、切轨请求/最终结果、字幕区间及出现/消失时实际视频帧时间；只在变化时记录，不输出正文或逐帧刷日志。宿主可补充文字层 pre-draw 记录，但它不能证明物理屏幕扫描时间。设备验证应使用与字幕独立的已知画面标记核对显示与空白区间，明确测量窗口/误差，避免仅比较 SDK 自己计算的两个时间值就宣称同步正确。

## 音轨发现与切换

采用提供音轨接口的 SDK 时，从 `state.audioTracks` 读取当前来源的真实音轨（索引、标题、语言、编码），`state.selectedAudioTrackIndex` 表示内核选择器确认的轨道。使用列表中的 `AudioTrack` 调用 `selectAudioTrack(track)`；轨道带来源代次，不跨来源保存或按旧索引套用新文件。未知标题/语言由 UI 显示本地化兜底，无音轨时不造默认项。音轨数量超过 64 时不开放选择；每个元数据字段只保留前 256 个 Unicode 字符，字段截断不影响选择。

Accepted 仅代表入队，`state.pendingAudioTrack` 表示等待确认；同一时刻只允许一次，失败清理 pending 并报告 AUDIO_TRACK_UNAVAILABLE。native 在现有控制线程使用 playbin `current-audio`，在 20ms 检查周期内等待 combiner 的实际 active-pad 选择，播放状态下 3 秒截止后失败且不重试，不以点击或写入属性的目标值假报成功。切换采用丢弃旧声音的语义：备用轨按现有播放时钟同步并正确换算即时倍速；沿用有界预读/输出队列，在切轨后主动丢弃其中的旧数据，并在串行的新音轨边界清除旧 PCM、变速处理器尾音和设备待播数据；只清音频分支，保留管线时钟及即时倍速映射。不能等待几秒旧声音播完，也不能只设置选择器就宣称新轨已输出。播放时确认还须等新轨通过输出边界；该确认仍不代表扬声器已物理发声，实际新轨 PCM 到达延迟、音调与连续性另行验收。切换不重新 load、不 seek、不重建视频解码器/Surface，不新建线程/时钟；音量、静音、倍速和循环保持。内核可能清空音频分支，不能承诺无声缝。

准备完成后在播放或暂停状态可切换；步进/拖动进行中拒绝切换，切换等待期间拒绝定位、步进、倍速及循环重配；允许播放/暂停。硬解暂停时可能保留待切换请求，恢复播放后才确认；不能为了确认而自动播放或假报成功，暂停等待不运行高频检查。暂停、停止、切源、失焦和释放继续有效，停止/切源/解除 Surface/释放清理等待与旧轨道；代次和操作 ID 隔离迟到结果。页面负责候选选择、确认操作和结果展示，SDK 负责真实列表与切换，不新增业务 Repository 或持久化。分别验证零/单/多音轨、Unicode 标签、失效轨道、暂停/播放、倍速/循环/静音和退出资源，模板生成核对不能替代 Android 验收。

## 视频与区间循环接入

SDK 的 `setLoop(PlaybackLoop(startMs, endMs))` 设置当前来源的临时 A–B 循环，`setLoop(null)` 退出；`state.loop` 为 native 接受后的区间事实。范围为毫秒、至少 500ms，要求来源已准备、可定位且时长已知，B 不得超出时长。命令 Accepted 只代表入队，不代表完成定位或已呈现。按钮和 A 点草稿由页面持有，不把课程、书签或保留历史放进 SDK。

`setVideoLoop(true)` 重复整个当前视频，开启时保留当前位置，后续循环从零开始；`state.loop.wholeVideo` 区分整段与区间，二者互斥。显式 seek 保留整段循环，退出 A–B 循环；`setVideoLoop(false)` 或 `setLoop(null)` 清除当前模式。停止、切源和真实 Surface 更换均清除。续段不停止/重载文件，不重建解码器、音频输出或 Surface；防止边界黑屏的实际效果须用硬解/GPU 和屏幕录制逐帧核对。

内核首次执行 FLUSH|ACCURATE|SEGMENT seek 到 A（整段为当前位置），随后响应带匹配序号的 SEGMENT_DONE，用不 FLUSH 的 segment seek 排队下一段；复用原管线、音视频时钟和有界队列，不依赖 Java 进度轮询。完成消息可能早于实际呈现，不能用收到消息的时间计算物理循环间隙。暂停/恢复和即时倍速保留模式。退出循环以精确 flushing seek 在当前查询位置恢复整片，清除 B 停止边界；状态重配可能短暂缓冲。续段拒绝/意外 EOS 明确停止并报错，不无限重试或假装继续循环。

文件描述符输入必须核对实际调度与授权，不能用可直接打开的文件路径代替 fd 输入验收。持有可读 fd 不代表能通过 `/proc/self/fd` 重新打开它；必须覆盖另一 UID 的 Provider 授权、调用方关闭原 fd 后的实际读取、定位/循环及释放。普通文件由 SDK 保留复制 fd，直接随机读取并保留 demux pull 调度；不解析外部路径或扩大权限。管道等非普通输入保留流式读取，并按实际能力拒绝定位/循环。音频变速节点使用仓库内 SoundTouch pitch 源码构建的独立类型，换段前排空旧段尾部，新 SEGMENT 重建输出时间戳，单位变换为 1 时保持整数时间值；不把旧段 PCM 时间接到新段，也不注入伪造 FLUSH 来重置单个节点。该局部源码构建不等于重编整个 GStreamer。静音仅改变音量，不切换源、时钟或循环；必须覆盖静音与非静音的真实 fd 整段/A–B 回放、两路有效且递增的 running-time、PCM 持续输出和有界续段次数。片源本身的黑帧应忠实播放，不以自动跳过黑色内容掩盖边界问题。

循环续段沿用最近一次 flushing seek 的基础 segment.rate；即时倍速继续由管线协调，不能在下一段重复乘上最新倍速。音高补偿也保留最近即时倍率，只有 flush 才清除。SDK 对 GstBaseSink 的跨段即时倍率换算采用仓库源码修复：实际经过时间为上游经过时间除以倍率。构建时只在派生的 gstbase 静态库中替换该源码对象，不覆盖校验过的官方开发包。回归须在切速之后跨多个循环边界，测实际输出帧间隔和音调；切速后短时间继续供帧不代表跨段正确。

即时音高补偿放在自有音频输出 bin 内，位于 playbin 播放队列之后；转换、补偿和音频 sink 之间不再增加排队节点。不能在 playbin 的 audio-filter 位置提前生成数秒旧倍速 PCM，再让输出端先切速。回归须包含真实 playbin 的内部队列与循环组合，并检查 2× 回到 1× 后实际输出 PCM 的恢复时间；直接连接 demux/decoder 到 sink 的测试不能覆盖内部队列及事件传播差异。

循环时输出端可能已经持有下一段，切速的共同 clock anchor 却仍位于上一段；也可能输出线程收到切速时已落后于 anchor。前向即时变速应保持原裁切范围，围绕共同 anchor 换算时间映射，不能因媒体位置外推为负数而跳过速率更新，也不能把外推位置强制夹回段起止点。SDK 的源码 BaseSink 修复需用 anchor 在段前/段后的确定性事件用例及多轮实播分别验证，不能只凭切速命令被接受判断成功。

采用前核对 SDK 版本确实包含上述 API；按真实有声文件验证非关键帧 A/B、长短区间、多次循环、暂停/倍速、清除/定位/切源和资源退出。native/JVM 测试与构建分别记录，不能据此宣称听感无缝或真实屏幕落点已验收。逐帧和拖动预览须采用下文专用 API，普通 SeekMode 不自动提供这两项能力。

## 拖动时主画面预览

采用包含 `beginScrub()`、`scrubTo(positionMs)`、`endScrub(positionMs)` 和 `cancelScrub()` 的 SDK 后，页面将按下、移动、松手/取消映射为拖动会话，普通/全屏共用同一播放器和 Surface。前提为已准备、可定位、有视频且时长已知的非实时来源；Accepted 仅代表请求入队。`state.scrub` 区分 DRAGGING、SETTLING、COMPLETE，携带本次操作 ID、最新请求位置和实际视频 preroll 的媒体时间；目标位置与已输出画面分别展示，不能把滑块移动当作定位完成。

SDK 在现有 GLib 控制线程中处理可替换目标，预览最多每 100ms 发起一次关键帧定位，松手后的最后请求使用精确定位。匹配本次 seek 的视频 SEGMENT/新 buffer 后，还须等 PAUSED preroll 完成才确认输出；不能用不携带 seek 身份的 ASYNC_DONE 单独判断成功。当前版本不设置固定的定位总期限，慢速持续读取时可继续等待；新目标取代旧目标，停止、切源和关闭取消旧读取。HTTP 读写阻塞配置与实际来源错误仍独立生效，不自动重试。旧 SDK 的超时状态仅为兼容保留，不将固定 5 秒写成当前行为。复用解码器、GL 输出和内核时钟，不新增生产像素读回、帧缓存、解码线程或 Java 定时 seek。定位允许重新缓冲并保留上一张画面；实际落点、黑帧和延迟需设备验证，长关键帧间隔时拖动预览可能跳跃。

拖动中暂时暂停音视频，完成最终定位后按拖动前的播放意图恢复；期间显式暂停或失焦取消自动恢复。`cancelScrub()` 放弃尚未提交的目标，等待已有定位完成后保持暂停。停止/切源/真实 Surface 更换/释放取消操作并隔离迟到回调；同 Surface 全屏不重建会话。拖动期间拒绝普通 seek、播放、倍速、循环重配和逐帧；整段循环保留，A–B 在开始拖动时退出。音量/静音/已准备的 GPU 效果仍可更新，不改变源或另开时钟。

App 只保留触摸宿主及目标的展示草稿，调度和播放意图以 SDK 为准；ACTION_CANCEL 取消会话，宿主停止不自动恢复，键盘/无障碍调整继续使用普通定位。必须验证快速往返拖动、最后请求不丢失、暂停无声、恢复及失焦、倍速/循环、末尾、切源/销毁和真实硬解/GPU；静态生成与状态单测不代表设备通过。

## 暂停后逐帧接入

采用包含 `stepForward()` 的 SDK 后，已准备且可定位的非实时视频在 PAUSED 才能请求下一帧；一次只处理一个请求，观察 `state.frameStep` 的 STEPPING、COMPLETE 或 END_OF_MEDIA。Accepted 仅为入队；完成还须匹配 native STEP_DONE 及重新 preroll，并提供视频 sink 实际 last-sample 的 `framePositionNs`（PTS 经过样本自身 segment 转成媒体时间，不能把 MP4 重定位过的原始 PTS 直接当进度）。不能使用固定 33ms、元数据帧率或关键帧 seek 冒充逐帧，不向 Java 投递像素。

实现采用 playbin 的视频 BUFFERS step，由 playsink 协调音频 TIME step，整个过程保持 PAUSED、无音频播放。STEP flush 是跳过步进数据的内核语义，并非管线 FLUSH seek。保留 GL 输出窗口及解码器；实际屏幕无黑帧仍需设备验证。进行中拒绝第二次步进及播放/seek/倍速/循环重配；stop/load/真实 Surface 更换/释放可取消并隔离迟到结果。到末尾保留画面，重播从头开始；循环继续遵循当前范围。

App 只负责普通/全屏按钮、禁用状态及本地化提示，不自建帧时钟或按进度轮询 seek。采用前确认 SDK 已交付该接口，分别验证实际输出帧序号/PTS、VFR/B 帧、静音暂停、循环和恢复；JVM/生成检查不能替代 native 与屏幕验证。

## 视频纹理处理标准

本节是采用本播放器时的视频显示/滤镜默认标准，不强制未接入媒体的工程启用 SDK，也不将魔方等普通 3D 场景解释成需要视频解码。

1. **默认链路**：硬件解码优先 → GPU 纹理 → GPU Shader → 渲染。Android MediaCodec 的 external-OES 经 glcolorconvert 在 GPU 转成 RGBA/2D 后进入自有滤镜；颜色、亮度等适合 GPU 的效果沿此入口扩展，不另写 CPU 逐像素播放链。
2. **解码与滤镜分开选择**：GPU 滤镜不强迫软解。要求全程硬解纹理时显式用 HARDWARE_ONLY；AUTO 可以按内核候选降为软解，必须显示实际解码器，不能将“勾选灰度”或“MediaCodec 名称”当作硬解证据。软件解码、截图/导出、必须使用 CPU 的 AI 输入属于明确例外，应在功能 Spec 说明格式、采样频率、拷贝/线程/队列预算和失败行为。
3. **资源与同步**：Shader/FBO/纹理由 GL 上下文线程创建、复用和释放，输入纹理与写入目标分离。已准备的效果开关/强度优先更新线程安全参数，在下一帧生效，不为了灰度开关重载媒体；需要增删节点或更换输出格式的效果另定义重配置边界。复用内核的时间戳、同步和有界队列，不逐帧投递 Java 像素、不每帧编译 Shader、不用 glFinish 忙等、不为滤镜另起主时钟；OES 变换和同步由内核 GL 组件承接。GPU 链中禁止常规 CPU map、gldownload 或 glReadPixels 读回；“无 CPU 读回”不等于零 GPU 拷贝。
4. **能力与失败**：本灰度是 SDR 显示效果，未交付 HDR 色调映射或导出。GPU/格式不支持或 Shader 失败必须明确报错；不能静默退回 CPU 灰度、无限重试或靠扩大队列掩盖吞吐不足。新增效果先审阅 SDK 自有 grayscale_filter.cpp 的实现，不改上游即不能声称已改变上游核心。
5. **验收证据**：静态约束 + 原生编译 + 同片源设备回归分别记录。debug 的 PlaybackVideo 记录实际解码输出 caps、GLMemory/2D 滤镜 caps 和 shader_ready；PlaybackSync 提供供帧/QoS/释放证据。对比 NONE 与 GRAYSCALE 的真实有声视频、画面、帧率、负载、暂停/seek/倍速、EOS/退出，不能用测试色条或 JVM 状态测试替代硬解验收，也不能从入口 PTS 宣称物理音画同步。

SDK 的 `docs/gpu-video-processing.md` 维护具体代码入口、管线及验证方法；模板保留采用约束和调用示例，不复制底层实现或宣称每个设备已经通过。

## 当前帧截图接入

[FramePngWriter.kt](FramePngWriter.kt) 提供可按需采用的 PNG/MediaStore 写入实现，仅在 docs，未自动启用；调用者提供目录名、旧系统写权限及任务生命周期。

采用提供 `suspend AndroidPlayer.captureFrame()` 的 SDK：从主线程请求，返回 `VideoFrame`（当前来源代次、视频实际输出帧的媒体时间、尺寸和像素比例）。这是滤镜后视频帧，保留原始像素尺寸并按显示方向旋转/镜像，不包含宿主字幕、控件、黑边或裁剪；暂停时截取最后一帧，不因效果参数刚切换而重新处理旧画面。`VideoFrame.toBitmap()` 与 PNG 编码在后台执行；Bitmap 用完由调用方 recycle，不把 Bitmap/像素放进 model State。

截图是按需、有界的 GPU 读回例外，不改变正常播放链路：一次一个请求，RGBA 上限 32MiB，复用 sink 已显示样本与 GL 同步，禁止为截图 pause/seek/重建解码器或逐帧缓存像素。无帧、超限或不支持的格式明确失败，仅截图失败，不结束播放。协程取消、停止、切源、Surface 绑定请求或 close 使旧请求失效；调用方仍核对来源代次和操作身份，编码完成后才能提交保存结果。

保存是宿主职责。用户导出选 MediaStore/SAF，后台写 PNG，成功关闭流、确认提交后将 Uri、文件名、尺寸及帧时间放进页面 State；失败/取消只清理本任务未提交的文件。Android 10+ 使用 MediaStore pending 行无需存储写权限；旧系统按实际公共目录写权限处理，不申请广泛读取权限来实现截图。离开页面取消未提交任务，已保存图片由用户管理，不因清缓存删除。PNG 使用方形像素，非 1:1 像素比例需明确是保留原始栅格还是重采样，不能默默拉伸。

验收覆盖真实硬解/GPU 的非对称图案、灰度、旋转/镜像及实际帧时间；连续请求/无帧/取消、切源/释放、PNG 解码一致性和失败补偿；对受影响的播放/暂停、声音、倍速、循环、拖动与逐帧做回归。静态检查不能替代像素与设备结果。

## 精确 A–B 片段导出接入

仅采用实际提供 VideoClipExporter 的 SDK；[ClipMp4Writer.kt](ClipMp4Writer.kt) 是可按需采用的 MediaStore 保存实现，不自动加入运行源集。导出以独立任务捕获本地完整文件、A/B、当前音轨序号和显式导出效果，固定 1×、无字幕/宿主裁剪，输出 H.264/AAC MP4。原播放器倍速、音量、静音、循环及播放灰度不自动写进文件。

原 export(source, output, request, onProgress) 和 Writer.save(source, request, onProgress) 保持默认原画；采用支持效果的 SDK 时显式传入 VideoEffect.NONE/GRAYSCALE 重载，不能改变旧 ClipExportRequest 构造和默认语义。导出设置与播放效果分开，任务创建前捕获为不可变参数，权限回调也核对该选择；进行中调播放效果不能改变正在生成的视频。灰度在独立 OES Shader 内用 0.299/0.587/0.114 计算，保留 alpha，继续 GPU→硬编，不能读回 CPU 再处理。宿主状态标明该任务效果，不靠当前播放开关推断保存结果。

SDK 负责 MediaExtractor 解封装、硬件解码 Surface→GPU→硬件编码 Surface 及 MP4 封装；PCM 裁切/编码只属于音频。视频禁止逐帧 CPU 读回或隐式软解回退。音视频共用 A 作为输出时间原点；视频按实际帧落点裁切，音频按采样裁切并明确 AAC 编码延迟/尾部填充误差，不以关键帧截取冒充精确模式。设备或源格式不支持时明确失败。SDK 源码与系统编解码实现边界分别说明，不宣称修改了 GStreamer 上游。

一个进程至多一个在途导出，不堆积请求；队列、单帧等待和无进展期限有界，取消须等待 codec/EGL/muxer/文件副本真正释放。codec/EGL 固定在单一工作线程，不能因进度回调挂起而切换 EGL 所属线程。首个入选视频帧覆盖片段起点，后续帧保持相对 A 的实际间隔；保留 AAC 负时间预热包，由 MP4 edit list 表达裁切，不能通过丢包或独立平移音轨改变同步。缺失颜色参数时显式协商 SDR 颜色标准、范围和传递函数，独立解码检查实际像素，不能只看编码成功。调用方持有页面 Job，离页/切源/停止取消，复核任务身份后提交结果；首版不跨页面/进程恢复，不擅自启动 Service。进度限频，成功只能在 muxer 完成、文件关闭及相册发布后确认。失败/取消只删除本任务未提交产物，保留源文件及已发布视频。

验证用非关键帧边界、B 帧/VFR、旋转、无音频/多音轨、已知画面/声音标记，独立解析和解码输出文件核对范围、时间轴、声音及无字幕；灰度同时检查中性色和源画面加权亮度，旧原画断言保持。覆盖效果快照、取消、不同效果间重复、空间/格式失败和资源退出，并回归原播放、循环、倍速、拖动、逐帧及截图。模板静态通过不代表实际编码/设备验证通过。

## 实际播放输出录制接入

采用实际提供 `AndroidPlayer.startRecording` / `PlaybackRecording` 的 SDK；[PlaybackRecordingWriter.kt](PlaybackRecordingWriter.kt) 是页面作用域的相册保存参考，不自动加入源集。它与源文件 A–B 离线重编码不同：开始后采集实际显示的最终 GPU 纹理与实际消费的最终 PCM，按单调时钟输出 H.264/AAC MP4，中途滤镜、速度、音量、静音、定位与循环直接反映在文件里。暂停/缓冲时间保留为定格和无声，不在结束时按最终参数重新处理源片段。

主线程 start，先设置 `recording.setComposition(crop, overlayBitmap)`，`awaitStarted()` 后才显示录制中。开始时固定画布（偶数宽高、16–1920）；裁剪可持续更新，字幕由宿主把同一文字层绘成透明 ARGB_8888 Bitmap，只有字幕/布局变化时复制上传，不能把视频帧读回 CPU。录制只包含画面区域，不含操作按钮；不支持的设备/格式明确失败。GPU 样本及 PCM 队列有界，编码在独立线程，录制失败不终止播放；清缓冲后未实际播出的旧 PCM 不能进入成品。

页面 Job 调用 Writer.save，结束按钮完成 Deferred 信号，取消/停止/切源/离页取消 Job；onRecording 的临时技术句柄不能放进 model State。SDK finish/cancel 等待编码/EGL/文件副本退出，Writer 关闭输出、确认非空后才发布 MediaStore pending 行。失败只删除本任务未发布文件，保留源文件与已发布成品；不创建后台 Service、不跨进程恢复。旧系统公共 Movies 写权限由宿主负责，10+ 无需存储写权限。

真实验证必须在同一次录制里切换灰度/原色、倍速/静音/音量，独立解码输出检查像素、时长与声音，覆盖暂停、循环、字幕、裁剪及取消/失败释放；同时回归无录制播放、音轨、调速清 PCM、截图和旧离线导出。模板静态检查不证明设备录制正确。

## 外挂 SRT 字幕接入

采用提供 `SrtSubtitle.read(input, title)` 与 `player.addSubtitle(document, generation)` 的 SDK：在 IO 读取并解析 UTF-8/BOM SRT，成功后在 Main 向捕获的来源代次提交。SDK 校验文件上限 2MiB、最多 4096 句、单句 4096 Unicode 字符及总正文 1MiB，拒绝非法编码/时间与超限，不截断成假成功；输入流由调用者 use 关闭。解析失败保留原字幕；解析期间停止/切源/宿主退出则丢弃结果。App 不解析字幕，不自建字幕时钟。

首版一份外挂文档，替换成功自动选中，列在与内嵌字幕相同的目录，`SubtitleTrack.external` 表明来源。关闭仍调用 `selectSubtitleTrack(null)`，再次从目录选择可重新显示；停止/切源/释放清理。原生沿现有字幕轮询，以实际视频输出帧的媒体时间选择有界缓存文本，覆盖重叠、间隔、暂停/定位/倍速/循环/步进/拖动。导入不重载视频、不 seek、不重建音视频节点，不新增主时钟、视频像素读取或生产工作线程。SRT 样式标签首版作为纯文本，不承诺 ASS/位图/编码探测。

文件入口由宿主按场景提供：正式导入优先系统文档 Uri；已有共享根目录技术页可复用已授权的只读列表。不要为了选择字幕放宽后台播放/离页释放规则；不持久化来源授权或文档。验证应使用独立已知时间点文件，检查替换/关闭/切回、失败保留、代次隔离以及受影响的内嵌字幕和音视频旧路径，静态生成不代表设备通过。
