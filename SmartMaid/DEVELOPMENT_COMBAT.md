# Smart Maid 女仆战斗系统：实现与维护

更新：2026-11-24（北京时间）。核对基线：`feature/kotlin-migration`，HEAD `658e390` 及当前工作树；默认 Minecraft 26.2。本文替代 2026-09-16 的待实施方案，描述当前 Kotlin 实现、保留决策及待修项，不代表本轮完成了代码修复或游戏验收。

玩法规则与原始需求对照见 [女仆战斗系统](../女仆战斗系统.md)；本文维护源码导航和开发验证，避免两份文档重复维护实现细节。项目排期以 [下一阶段计划](../docs/superpowers/plans/2026-11-24-next-stage-development.md) 为准，迁移验证范围以 [迁移状态](KOTLIN_MIGRATION_STATUS.md) 为准。

## 1. 决策来源与修订

| 来源 | 保留结论及本次修订 |
| --- | --- |
| 原始女仆战斗需求（原 docx，现为根目录 Markdown） | 保留进食、攻击、格挡与普通动作锁定的需求；不把“有代码”写成“完整验收” |
| 2026-09-16/17 战斗方案与 [HANDOVER](HANDOVER.md) 战斗 C0—C5 章节 | 女仆中心索敌、按 DPS 选近战、力量附魔选弓、自写弓箭、受阻走位；鱼竿经 API 调研后已确认暂不做 |
| 2026-09-19 [问题记录](DEVELOPMENT_ISSUES.md) 与交接记录 | 取消盾牌 5 tick 前摇；近战走位死区改为 2.7—3.2；保留方向判定 |
| 玩家设置实现 | 护主 ACTIVE/PASSIVE/OFF；友军免伤为默认设置，可开启友军伤害；饱食消耗/回血倍率可调 |
| [任务队列](DESIGN_MAID_TASK_QUEUE.md) Q5 与当前代码 | 战斗暂停当前项、保留运行时对象并回队首，战斗结束继续；淘汰“统一取消且不恢复”的旧描述 |
| 2026-11-24 静态复核 | 增补 COMBAT-01—05；本轮未运行战斗测试，未关闭这些问题 |

旧方案中“原版鱼竿右键兜底可用”“盾牌必须等 5 tick”“玩家永远免伤”“战斗取消全部任务”已不适合作为当前实现指引。历史交接和问题日志保留当时上下文，不覆盖历史测试结论。

## 2. 当前源码导航

以下链接均指向实际文件；不再列出未创建的 MaidCombatState、MaidFishSkill 或拆分的近战/进食 Skill 类。

| 文件 | 职责与关键入口 |
| --- | --- |
| [SmartMaidEntity.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/SmartMaidEntity.kt) | registerGoals、攻击包围盒、免前摇格挡、友军设置、getProjectile、completeUsingItem、aiStep |
| [MaidCombatGoal.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidCombatGoal.kt) | 战斗生命周期、缓存目标、动作分支、冷却、退让 |
| [MaidCombatMovement.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidCombatMovement.kt) | 水平距离控制、后退、侧移、背对跳 |
| [MaidWeaponSelector.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidWeaponSelector.kt) | 近战评分、换主手、攻速冷却 |
| [MaidRangedSkill.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidRangedSkill.kt) | 找弓和弹药、蓄力、自写发射与瞄准 |
| [MaidArrow.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidArrow.kt) | 按友军伤害设置过滤玩家命中；沿用原版箭类型 |
| [MaidTargetFilter.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidTargetFilter.kt) | 附近 Monster 过滤、玩家过滤、标签/配置排除、女仆视线 |
| [MaidThreatDetector.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidThreatDetector.kt) | 箭轨迹离散外推、苦力怕膨胀检测与诊断日志 |
| [MaidShieldSkill.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/combat/MaidShieldSkill.kt) | BLOCKS_ATTACKS 物品识别、换副手、开始使用 |
| [MaidFoodData.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/MaidFoodData.kt) 与 [MaidActions.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidActions.kt) | 饱食、回血、食物评分、物品使用及近战调用 |
| [MaidTaskManager.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/maidtask/MaidTaskManager.kt) | 战斗暂停/续跑与任务门控 |
| [MaidGroundPathNavigation.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidGroundPathNavigation.kt) 与 [MaidMoveControl.kt](src/main/kotlin/com/oyxdsg/smartmaid/entity/ai/MaidMoveControl.kt) | 战斗禁用直线挖搭降级，移动与跳跃协作 |
| [MaidSettings.kt](src/main/kotlin/com/oyxdsg/smartmaid/data/MaidSettings.kt) 与 [never_target.json](src/main/resources/data/smartmaid/tags/entity_type/never_target.json) | 护主/友军/回血设置和默认排除类型 |
| [SmartMaidRenderer.kt](src/main/kotlin/com/oyxdsg/smartmaid/client/renderer/SmartMaidRenderer.kt) | 按使用动作映射拉弓、格挡等手臂姿势 |

## 3. 接入与当前执行顺序

战斗 Goal 注册在 goalSelector 优先级 1，持有 MOVE/LOOK；跟随、散步、环视优先级为 2/3/4。FloatGoal 为 0；L0 安全规则仍独立执行，不应把设计优先级理解为所有代码的严格执行先后。

targetSelector 优先级：1 为主人受击，护主非 OFF 时可启动；2 为主人攻击目标，仅 ACTIVE；3 为附近最近 Monster，仅 ACTIVE。附近目标由女仆中心及女仆视线过滤。canUse 检查目标存活、canAttack 和坐下状态，没有重新检查主人视线。

| 顺序 | 当前处理 | 限制 |
| --- | --- | --- |
| 1 | 对缓存目标设置注视，计算三维距离 | 未同步最新 maid.target，见 COMBAT-04 |
| 2 | 有盾且有威胁则举盾、停止移动、返回 | 可抢占物品使用；不递减原动作冷却 |
| 3 | 拉弓但无可用弓箭时松弦 | 不等于覆盖所有动作切换 |
| 4 | 半血以下且有食物则进入退让/进食 | isUsingItem 未区分弓与食物，见 COMBAT-02 |
| 5 | 距离大于 6 且 canShoot 则远程 | 小于 10 时尝试拉开距离；无独立 15 格分支 |
| 6 | 攻击包围盒与目标相交则近战 | 用 3.2 作水平扩展参数，不是 3.2 格球形中心距 |
| 7 | 其余情况寻路逼近 | 此分支不递减 actionCooldown |

战斗激活时，队列当前项经 pauseCurrent(COMBAT) 保留并回队首；支持 Resumable 的任务保存状态，战后继续调度。旧 setTask 直接入口仍拒绝战斗中新任务，不能把该规则扩大为所有队列入口都拒绝入队。

stop() 会释放使用中物品、停止导航和移动。canContinueToUse() 只检查缓存目标的存活/可攻击及坐下状态，不足以落实“目标丢失后 3 秒退出”。

## 4. 子系统维护要点

### 4.1 距离、走位与近战

决策的 distanceTo 是三维距离，keepDistance 的误差是水平距离，近战判定是扩展包围盒相交。测试必须分别记录，不能仅凭常量宣称固定中心距命中。

近战走位 wantedDistance=3.0、maxDistance=3.2，死区 2.7—3.2。默认侧移为 0；后退受阻时尝试背对跳及侧移，不能据此保证所有墙角都能脱困。keepDistance 的调用时机受 Goal 调度影响。

武器评分以基础属性和物品 MAINHAND modifier 金额求和，按伤害乘攻速选择；未完整模拟所有属性运算类型、附魔及特殊命中效果。攻击通过 MaidActions.attack → doHurtTarget，友军伤害设置在实体和箭命中处分别拦截。

### 4.2 远程

仅 BowItem 分支；按 POWER 等级选弓，同级取先找到的。没有兼容箭则不进入远程。

startUsingItem(MAIN_HAND) 推进原版蓄力；满蓄力约 20 个游戏 tick，或 ticksUsingItem 达到 24 时尝试释放。自写发射包含目标速度预判、下坠补偿、满蓄力暴击和力量伤害；箭不可拾取。每次发射直接缩减一支弹药并扣弓耐久，当前没有无限附魔免耗箭分支，不宣称与玩家全部附魔行为等价。

### 4.3 进食与回血

血量 <50% 且找到食物才触发，营养优先、其次饱和度。dist<7 时累计退让，走位目标 8；达到 40 次退让回调后允许原地进食。物品使用期间走位目标 7。

completeUsingItem 补记 FOOD；MaidFoodData 按游戏规则、饱食度和设置倍率驱动回血/饥饿。食物用时由原版使用流程决定，34 只是额外冷却计数，不能代替消费完成检测。

### 4.4 盾牌与威胁

盾牌按 BLOCKS_ATTACKS 组件识别，背包 0—35 搜索后换到副手 40；副手已有盾则直接同步。beginBlock 中断当前使用并开始副手使用。

箭检测扫描范围 16、预测 30 游戏 tick、女仆包围盒膨胀 0.8，按当前速度逐 tick 外推采样点；不是完整考虑重力/阻挡的轨迹碰撞求解。苦力怕扫描范围 4，要求 swellDir>0 且 swelling>0.6。

SmartMaidEntity.getItemBlockingWith 去掉原版前摇；未移除朝向与具体伤害判定。当前面向的是缓存战斗目标，不保证自动转向每个来袭方向。威胁存在持续举盾，消失收盾，期间停止移动；固定 10 tick 持盾尚未实现。

### 4.5 鱼竿调研结论

历史 API 调研确认：FishingRodItem.use 要求 Player；原版 FishingHook 在无玩家 owner 时丢弃，右键兜底也不能构成可用攻击。既有决定为暂不做，不保留伪可用的 FISH 状态。

重新立项需要独立评估 LivingEntity owner、自研鱼钩、抛出/回收/拉拽及渲染同步；不是本轮文档维护或 Kotlin 迁移门禁的隐含任务。

## 5. 计时审查

| 名称 | 代码参数 | 单位与注意事项 |
| --- | --- | --- |
| 近战冷却 | clamp(round(20 / attackSpeed), 14, 26) | actionCooldown 的递减次数 |
| 进食冷却 | 34 | 开始吃时设置，正在使用物品时不递减 |
| 进食退让 | 40 | 退让分支的累计调用次数，未保证 2 秒 |
| 远程释放阈值 | 满蓄力或 ticksUsingItem>=24 | 原版使用计时，按游戏 tick 推进 |
| 发射后冷却 | 6 | actionCooldown 的递减次数 |
| 普通失败/进食失败 | 10 / 20 | actionCooldown 的递减次数 |
| TARGET_LOST_TIMEOUT | 60 | 只在缓存目标空/死的 tick 分支增加，受生命周期提前停止影响，不能等同于有效 3 秒滞回 |
| 举盾 | 无固定时长 | 威胁分支控制，不设 10 tick 持盾窗口 |

本次核对 26.2 依赖源码：Goal.requiresUpdateEveryTick 默认 false；Mob.serverAiStep 交替调用完整 GoalSelector.tick 和 tickRunningGoals(false)，后者仅更新要求每 tick 的 Goal。MaidCombatGoal 没有覆写该方法，也未对冷却计数做 adjustedTickDelay 适配。

因此不能用“20 次 Goal 回调=1 秒”描述当前行为。拟修复时应统一每游戏 tick 连续更新与动作决策节拍：持续更新移动、物品使用和威胁检测；普通动作是否可切换由明确的活动动作与锁定状态决定。不得简单把每帧决策整体延迟到冷却结束而停止更新拉弓和进食。

## 6. 待修项与建议顺序

下列均为静态发现，未标记为运行时复现通过。先补最小复现场景，再修复、复测；不以更新文档关闭代码问题。

| 编号 | 优先级 | 代码位置/问题 | 建议处理与退出条件 |
| --- | --- | --- | --- |
| COMBAT-01 | 高 | MaidCombatGoal 未请求每 tick，计数与预期游戏时间不一致 | 明确调用频率、统一计时单位；用 tickCount 差值验证近战、远程、退让及连续走位 |
| COMBAT-02 | 高 | handleEat 的 isUsingItem 将拉弓视为进食 | 区分弓、食物与盾；动作切换明确释放；半血拉弓场景能够完成或取消拉弓并进入进食，无无限持弓 |
| COMBAT-03 | 中 | 各分支独立检查冷却，切换和盾后恢复语义不完整 | 建立统一活动动作/锁定规则；明确进食完成后是否额外等待；锁定期普通动作不相互打断，盾可抢占且之后正确恢复决策 |
| COMBAT-04 | 中 | canUse 缓存 target，运行时不跟随 maid.target | 定义目标同步、失效、切换及超时退出；原目标死亡/越界/清空后不追旧目标，主人新受击目标可按既定优先级接入 |
| COMBAT-05 | 待对齐 | 固定 0.5 秒持盾、格挡走位与代码存在差距 | 对齐原始需求与当前威胁持续策略，明确最低持盾时长、刷新、移动及冷却恢复；实现前不宣称已满足原需求 |

COMBAT 编号只表示本专项内部顺序，不替换全项目 N1/N2/N3/P3 的排期；本轮不自动加入迁移合并门禁。

## 7. 定向回归清单

| 场景 | 必须观测的结果 |
| --- | --- |
| 无弓近战、不同攻速武器 | 实际出手 tick 差、选武器结果；上下/斜向距离下的包围盒判定 |
| 有弓时 6 格两侧及约 10/15 格 | 分支边界、拉弓/释放、消耗与移动；区分水平距离和三维距离 |
| 拉弓中目标贴近、血量跌破一半 | 动作锁定或明确取消，无持弓死锁、无使用状态串扰 |
| 吃食物中受击回血跨过半血 | 物品消费与饱食变化、动作结束/取消一致，无错误弓释放 |
| 平地退让、墙角进食 | 退让次数与真实 tick 差；达到上限后可消费食物，无无限后退 |
| 箭矢、苦力怕，有盾/无盾 | 举盾/收盾、实际血量、方向、动作抢占与无盾持续行动 |
| 战斗中换目标、清空目标、目标死亡/越界 | 实际攻击目标与索敌器一致，正确退出或重选，不追缓存旧目标 |
| ACTIVE/PASSIVE/OFF、坐下与友军设置 | 来源门控、默认玩家免伤与显式开启后的行为；已有目标清理边界清楚 |
| 可接续任务 → 战斗 → 战后 | 当前项暂停/回队首，战后继续，无任务丢失和资源泄漏 |
| 受阻后退、侧移、背对跳 | 无持续无效走位；战斗不挖不搭，坐下不移动 |

采用 [测试体系](TEST_SYSTEM.md) 与 [Loom 操作卡](../docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md) 的启动和归档方式，不恢复退役启动器。主玩法验证使用独立 26.2 测试世界；26.3 沿用现有验证边界，不把 26.2 实测推广为 26.3 输入通过。

修复记录至少包含：实际 HEAD、版本、场景与输入、目标实体、起止游戏 tick、动作/使用状态、前后血量和库存、通过/失败/未验证结论。盾牌依据实际扣血及格挡状态判断，不能以 hurtServer 入口原始伤害断言漏盾。

## 8. 本次维护结果

仅更新文档。保留历史 2026-09 的调研及真机结果来源；不把历史“已实现 C0—C5”扩大为鱼竿已交付、当前全部战斗通过，或本轮关闭 COMBAT 问题。当前构建、互操作测试、基础 AutoTest、GUI 报告的覆盖范围仍以迁移状态为准。
