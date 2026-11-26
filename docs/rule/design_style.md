# 设计系统

当前只有技术预览的中性颜色和文字样式，不代表新产品 UI Kit 已确认。真实页面先读取用户的 MCP/设计系统，不套用 Sony 或预览样式。

- 已提供的颜色与文字样式在 base/src/main/res/values/colors.xml、styles.xml；app 的 Theme.Template 使用 Light.NoActionBar，未宣称支持动态深色切换。
- 优先复用真实语义一致的 token、TextAppearance、shape/selector。spacing/padding/radius 等 token 在有新设计依据时登记，不凭经验填品牌数值。
- 样式背景按形状职责和状态命名；MCP 图标/图片保持同名，不为资源复用随意改业务前缀。外观相似不足以证明资产身份相同。
- 圆角背景优先 shape/Drawable；图片变换使用实际启用的 image 能力及 ImageLoader，先核对组件目录，不调用未启用的 API。
- 普通单色图标在使用处按 token tint，多色/品牌保留原始颜色。字体、行高、字重、阴影等以设计依据为准，不从一个静态状态推断业务状态。

资产规则与单位只在项目约定维护，布局映射只在 MCP 手册维护。正式设计确认后更新本文件及实际样式资源。
