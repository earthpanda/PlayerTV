# MCP 布局规则

实现前读功能 Spec、项目约定、组件目录、设计系统和资产登记；先核对用户手动提供的全部必需资源，再给出复用选择与关键布局映射。

- 保留视觉/交互/测量关系，不照抄设计 Group 树。纯分组压平；有背景、点击、滚动、裁剪、动效或复用职责才保留容器。
- 主体/复杂对齐默认 ConstraintLayout，简单横排/竖排才用 LinearLayout，叠层用 FrameLayout，大量重复项用 RecyclerView。不能机械套三层容器，也不靠 weight/空白 View 默认模拟复杂分布。
- fill 在 ConstraintLayout 中为对应方向 `0dp` 加两侧约束；普通容器为 match_parent；LinearLayout 主轴剩余空间为 `0dp + weight`。hug 为受边界限制的 wrap_content，fixed 为项目规定单位的设计尺寸。
- 先读父子 sizing 与测量边界，不能只读子项。滚动方向的无限空间不是剩余高度，比例尺寸要有可求解边界。
- padding 属于容器，gap 属于相邻项；压平时不重复计入。relativeX/Y 不一定是应追加的 margin。长文案同时考虑最大宽度、换行、截断及 constrainedWidth。
- 约束 chain/barrier/guideline 可代替无职责的包装容器。触摸区和图标视觉大小分别处理。
- 假系统状态栏不实现；系统 inset 由实际基础能力处理。当前未提供公共业务导航栏，不能引用原工程组件名冒充可用实现。
- 复杂区域以 nodeId/原名到 Android ID/组件的简短映射追溯，允许多对一和“纯分组，无独立 View”；注释保留 MCP 原名。
- 单位及资产停止策略只在项目约定维护，不以平台差异或占位解释绕过。Figma 只用 MCP，无法读取则告知并暂停设计依赖工作。
