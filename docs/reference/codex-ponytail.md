# Codex 的 Ponytail 技能接入

用途：为 Codex 提供按需的差异审查、仓库审查与已标注简化项清单。接入边界唯一维护在 [AGENTS](../../AGENTS.md#codex-与-ponytail)；本文维护版本、安装步骤和验证方法，不替代业务验收。

## 已审阅来源与版本

| 项目 | 固定值 |
|---|---|
| 上游 | [DietrichGebert/ponytail](https://github.com/DietrichGebert/ponytail)；官网 [ponytail.dev](https://ponytail.dev/) |
| 版本 | 5.3.3 |
| Commit | `96608e078b2d5ea607c5c3b8b5a54d1c1fba6846`；安装使用此 commit，不跟随 `main` |
| 许可 | MIT；每个安装的技能目录保留该 commit 的 LICENSE |
| 默认技能 | `skills/ponytail-review`、`skills/ponytail-audit`、`skills/ponytail-debt` |
| 加载方式 | 本地技能；每个技能的 `agents/openai.yaml` 设置 `policy.allow_implicit_invocation: false` |
| Android 影响 | 无 Gradle 依赖、运行时初始化、Manifest 或业务源码改动；生成器不执行安装、不联网 |

2026-11-05 核对上游技能、插件清单和生命周期脚本：复用已有能力、追踪调用方及保留校验与本项目一致；主模式的“最短差异优先”与必要重构准则冲突。一个小测试不能替代回归，审查建议也不能取消必要技术封装。审查技能中的英语输出、`shortcut:` 例外和交付结论受本项目语言、缺陷追溯及验收要求约束。

完整插件的 SessionStart/SubagentStart 会注入原始规则。官方文档说明启动钩子内容作为额外 developer 上下文加入；不能仅在项目 Markdown 声明优先级就认为冲突已消除。因此当前接入只安装上述三个审查技能，不启用主模式与自动注入。官网效果数据来自其他模型/宿主的基准，不能作为本项目实测收益。

## 初次接入与已有安装迁移

1. 确认当前执行者为 Codex，找到当前环境实际使用的 `CODEX_HOME` 和可安装技能目录。默认通常为用户目录下 `.codex`；按当前技能目录/宿主配置确认，不修改环境变量来迁就路径。检查同名技能、启用配置及已安装插件，避免安装两份。
2. 本项目规则已选择此接入方式，可执行约定范围的首次安装；不为同一授权重复确认。宿主权限或工具不支持时说明真实限制，不绕过信任检查，不把无法安装写成已可用。
3. 从当前技能目录读取并使用 `skill-installer` 的安装器，只传缺失项。安装器路径按其真实位置解析，示例：

   ```text
   python -X utf8 <skill-installer目录>/scripts/install-skill-from-github.py --repo DietrichGebert/ponytail --ref 96608e078b2d5ea607c5c3b8b5a54d1c1fba6846 --path skills/ponytail-review skills/ponytail-audit skills/ponytail-debt
   ```

   已存在的目录先核对来源与版本，不覆盖用户改动；部分缺失则只安装缺失项。若本地没有可用安装器，说明缺口，不另行下载执行未知安装脚本。
4. 每个技能补充以下 `agents/openai.yaml`，使其只能显式调用。若文件已存在，保留其他元数据、只调整该策略；不得覆盖未知本地改动：

   ```yaml
   policy:
     allow_implicit_invocation: false
   ```

   `SKILL.md` 保持上游内容，保留固定 commit 的 LICENSE。记录上游版本/commit、技能源码摘要和本地调用策略，区分原始文件与本地元数据。
5. 如果已有完整插件，备份当前用户配置后，仅将 `[plugins."ponytail@ponytail"]` 的 `enabled` 设为 `false`。保留插件安装文件、市场配置与信任记录；核对其他配置未变化。不要通过全局关闭 hooks、改写全局 AGENTS 或修改插件缓存来处理冲突。本机配置及其备份可能含凭据，留在用户配置目录，不写入项目或输出其内容。
6. 新技能可在下一轮被发现；为确保旧插件不再向后续会话注入指令，重新启动 Codex 或新开会话，按实际宿主核对。已经注入当前会话的上下文不能靠修改文件撤回，不把文件修改等同于当前会话已卸载。

插件安装与技能安装是两种接入方式。不要在上述步骤之后再执行官网完整插件安装命令，避免同时加载两套规则。非 Codex 工具不执行这些安装步骤。

## 使用与验证

| 需求 | 显式调用示例 | 边界 |
|---|---|---|
| 审查本次变更 | `$ponytail-review 审查当前差异，按项目准则用中文报告` | 阅读关联调用链并报告；不会因此自动修复或运行测试 |
| 审查指定范围 | `$ponytail-audit 审查指定目录` | 按用户范围查证问题；注明未读/未验证部分 |
| 查看已标注简化项 | `$ponytail-debt` | 只收集相应注释；没有注释不等于没有技术债，也不豁免已知问题 |

安装完成后逐项核对：

- 三个目录及 SKILL.md 存在，源码与固定 commit 一致，LICENSE 保留；本地元数据禁止隐式调用。
- 官方插件列表/宿主显示完整 Ponytail 插件未启用；其他插件和钩子配置与改前一致。仅有历史模式文件或信任记录不代表插件仍在运行。
- 新会话可发现这三个技能，普通编码任务不隐式启用；用户明确调用时，按实际可用的技能名加载。独立技能使用 `$ponytail-review` 等名字，不能照搬完整插件的命名空间或其他宿主的斜杠命令。
- 安装、配置、发现和实际调用分别记录。没有在新会话调用时，只报告已安装/已配置，保留发现和调用待确认；这些结果不代表 Android 构建或业务回归通过。

更新版本、主模式或加载方式前重新阅读相关技能与钩子、比较本项目准则，并更新此处的固定版本及采用记录。保持初始化器离线及现有项目独立维护，不重跑初始化器覆盖已有工程。

## 核对来源

- [上游固定版本安装说明](https://github.com/DietrichGebert/ponytail/blob/96608e078b2d5ea607c5c3b8b5a54d1c1fba6846/INSTALL.md)
- [上游固定版本主规则](https://github.com/DietrichGebert/ponytail/blob/96608e078b2d5ea607c5c3b8b5a54d1c1fba6846/skills/ponytail/SKILL.md)
- [OpenAI 官方技能文档](https://learn.chatgpt.com/docs/build-skills)：显式调用策略与技能元数据。
- [OpenAI 官方钩子文档](https://learn.chatgpt.com/docs/hooks)：注入上下文、信任与启用边界。
