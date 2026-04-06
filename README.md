# Yuanshen

Minecraft Paper 1.21+ 的原神风格战斗插件。

当前版本已经包含：
- 七元素与元素反应
- 角色编队、角色物品与角色技能
- 原神武器物品、精炼与主手检测
- 角色命座、命星消耗与命座 GUI
- 玩家面板、侧边栏、PlaceholderAPI 占位符
- 怪物元素/物理抗性与 MythicMobs 抗性匹配
- 玩家冷却、能量、角色槽位与运行时状态持久化

## 环境要求

- Java 21
- Paper 1.21+

可选依赖：
- PlaceholderAPI
- ProtocolLib
- MythicMobs

补充说明：
- 当前版本不再依赖 MMOItems。
- 当前没有 `/ysmark` 命令。

## 已实现角色

- 胡桃
- 香菱
- 旅行者·风
- 夜兰
- 迪卢克
- 刻晴
- 凝光

## 玩家使用

1. 使用 `/yuanshen juese` 打开角色编队界面。
2. 将插件生成的角色物品放入 1 到 4 号槽位。
3. 使用 `/yuanshen player set <1-4>` 切换当前出战角色。
4. 将匹配类型的原神武器拿在主手，角色技能和普攻才会按当前角色生效。
5. 大多数角色右键释放 E、潜行右键释放 Q。
6. 近战角色沿用插件自己的普攻/重击逻辑；弓角色会按各自实现保留弓的交互方式。

补充说明：
- 默认允许空手触发技能。
- 如果关闭空手触发，则手持物品 Lore 需要包含 `原神武器`。

## 指令

- `/yuanshen reload`
- `/yuanshen stats [玩家]`
- `/yuanshen damage <toggle|on|off> [玩家]`
- `/yuanshen sidebar <toggle|on|off> [玩家]`
- `/yuanshen juese`
- `/yuanshen juese list`
- `/yuanshen juese [give] <角色> [等级] [命座] [玩家]`
- `/yuanshen juese mingxing [数量] [玩家]`
- `/yuanshen player set <1|2|3|4>`
- `/yuanshen weapon list`
- `/yuanshen weapon give <武器名> [等级] [精炼] [玩家]`
- `/yuanshen admin`
- `/yuanshen ys <火|水|冰|雷|风|岩|草|清除>`

`/ys` 是 `/yuanshen` 的别名，也可以直接用 `/ys 火` 这类写法快速切元素。

## 配置文件

- `src/main/resources/config.yml`
- `src/main/resources/attributes.yml`
- `src/main/resources/sidebar.yml`
- `src/main/resources/character-gui.yml`
- `src/main/resources/starter-characters.yml`
- `src/main/resources/characters/*.yml`
- `src/main/resources/juese/*.yml`
- `src/main/resources/weapons/*.yml`
- `src/main/resources/mobs/*.yml`

服务端运行后会在 `plugins/Yuanshen/` 下生成对应配置。

## 主要功能

### 1. 元素反应

支持：
- 蒸发
- 融化
- 超载
- 感电
- 超导
- 冻结
- 碎冰
- 燃烧
- 原激化
- 超激化
- 蔓激化
- 绽放
- 超绽放
- 烈绽放
- 扩散
- 结晶

### 2. 角色与武器系统

角色物品会写入并解析：
- 角色 ID
- 角色等级
- 角色命座

武器物品会写入并解析：
- 武器 ID
- 武器等级
- 武器精炼

角色编队界面只存角色，不再在 GUI 内存武器；战斗时只检测主手武器。

### 3. 命座系统

当前支持：
- 命星物品发放与消耗
- 角色命座升级
- 命座总览 GUI
- 命座名称与描述展示
- 多个角色命座效果接入实际战斗逻辑

命座名称/描述可在角色配置里覆盖，未配置时会回退到内置文本。

### 4. 怪物抗性与 MythicMobs

`mobs/*.yml` 可为不同怪物单独配置：
- `entity-type`
- `mythic-mob-id`
- 全元素抗性
- 单元素抗性
- 物理抗性
- 减抗上限
- 最终抗性上下限

默认示例：
- `zombie.yml`
- `skeleton.yml`

如果安装了 MythicMobs，插件会优先按 `mythic-mob-id` 匹配抗性配置；匹配不到时再回退到原版实体类型。

### 5. PlaceholderAPI

安装 PlaceholderAPI 后，可使用 `%yuanshen_*%` 占位符。

常用占位符示例：
- `%yuanshen_character%`
- `%yuanshen_element%`
- `%yuanshen_damage%`
- `%yuanshen_crit_rate%`
- `%yuanshen_current_energy%`
- `%yuanshen_e_cooldown%`
- `%yuanshen_q_cooldown%`
- `%yuanshen_particle_status%`

### 6. 数据持久化

以下内容会保存到 `plugins/Yuanshen/playerdata.yml`：
- 当前角色槽位
- 4 个角色槽内物品
- 技能冷却
- 角色能量
- 侧边栏开关
- 伤害提示开关
- 当前手动元素
- 部分角色运行时状态

## 当前明确不包含

- 通用的完整原神 UI 还原
- 所有角色的 1:1 原作细节还原
- 所有角色与命座的完整收录

## 开发说明

构建命令：

```powershell
./gradlew.bat compileJava
./gradlew.bat clean shadowJar
```

产物：

```text
build/libs/Yuanshen-1.0.jar
```
