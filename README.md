# Yuanshen

Minecraft Paper 1.21+ 的原神风格战斗框架插件。

当前仓库已经不是早期的单一元素反应 Demo，而是一套包含角色、武器、命座、元素战斗、GUI、玩家数据持久化、怪物抗性与管理功能的 Beta 框架。

## 项目定位

- 当前阶段: Beta
- 适合场景: 测试服、体验服、小规模玩法服、功能展示服
- 不建议直接宣传为: “完整 1:1 还原原神” 或 “适合大型正式服长期稳定运行”

更详细的系统评估见:

- [插件状态评估](docs/plugin-status.md)
- [玩家数据持久化说明](docs/persistence.md)

## 运行环境

- Java 21
- Paper 1.21+

可选依赖:

- PlaceholderAPI
- ProtocolLib
- MythicMobs

## 当前已实现的系统

- 七元素战斗与元素附着
- 元素反应流水线
- 角色定义、角色物品、角色编队与切人
- 原神武器定义、主手匹配、等级与精炼
- 命座、命星道具与命座 GUI
- 管理员 GUI、角色 GUI、命座 GUI
- 玩家属性面板、侧边栏、伤害显示开关
- PlaceholderAPI 占位符扩展
- 玩家数据持久化
- 怪物元素/物理抗性
- MythicMobs 抗性匹配
- Mod 按键桥接

## 当前默认资源

默认资源数量如下:

- 角色配置: 7
- 武器配置: 5
- 怪物抗性配置: 2

当前已接入角色:

- 胡桃
- 香菱
- 迪卢克
- 刻晴
- 凝光
- 旅行者·风
- 夜兰

当前仓库内置武器:

- `dawn-harbinger`
- `primordial-jade-winged-spear`
- `prototype-archaic`
- `rust`
- `skyward-atlas`

## 快速开始

### 1. 构建插件

```powershell
./gradlew.bat compileJava
./gradlew.bat clean shadowJar
```

构建完成后，Jar 位于:

```text
build/libs/Yuanshen-1.0.jar
```

### 2. 安装到服务端

1. 将 Jar 放入 `plugins/`
2. 启动一次服务端
3. 插件会在 `plugins/Yuanshen/` 下生成配置文件
4. 修改配置后执行 `/ys reload`

### 3. 给玩家发放内容

先查看可用内容:

```text
/ys list character
/ys list weapon
```

再发放角色、武器、命星:

```text
/ys give character 刻晴 90 6
/ys give weapon rust 90 5
/ys give constellation 1
```

### 4. 玩家开始使用

1. 输入 `/ys open` 打开角色编队界面
2. 把角色物品放入 1 到 4 号槽位
3. 输入 `/ys player set <1|2|3|4>` 切换当前出战角色
4. 将匹配类型的原神武器拿在主手
5. 使用技能或普攻进入战斗

## 技能怎么触发

当前版本的技能触发有三种常见方式:

- 指令触发:
  - `/ys skill e`
  - `/ys skill q`
- 默认交互触发:
  - 大多数角色会把 E/Q 逻辑接到右键或角色专属交互
- Mod 按键桥触发:
  - 默认示例中 `E -> /ys skill e`
  - 默认示例中 `Q -> /ys skill q`
  - 数字键 `1~4 -> /ys player set 1~4`
  - `L -> /ys open`

相关配置文件:

- `src/main/resources/mod-keybind.yml`
- `src/main/resources/config.yml`

补充说明:

- 当前版本技能触发不再依赖“空手判定”
- `skill_trigger.preserve_entity_interactions` 可以控制是否优先保留原版实体交互
- 技能能否释放还取决于当前是否已选中角色、主手武器是否匹配、技能冷却与元素能量状态

## 元素反应怎么触发

当前版本遵循“先附着，后引爆”的基本思路。

也就是:

1. 先用某种元素让目标获得元素附着
2. 再用另一种元素命中同一目标
3. 插件根据元素组合、附着量、ICD 与当前状态结算反应

常见触发例子:

- 蒸发:
  - 先挂水，再用火打
  - 先挂火，再用水打
- 融化:
  - 先挂冰，再用火打
  - 先挂火，再用冰打
- 超载:
  - 火 + 雷
- 感电:
  - 水 + 雷
- 超导:
  - 冰 + 雷
- 冻结:
  - 水 + 冰
- 碎冰:
  - 目标已冻结时，再受到合适的重击/物理打击
- 燃烧:
  - 草 + 火
- 原激化:
  - 草 + 雷
- 超激化:
  - 已进入激化态后，再受到雷伤
- 蔓激化:
  - 已进入激化态后，再受到草伤
- 绽放:
  - 草 + 水，生成草原核
- 超绽放:
  - 草原核再受到雷影响
- 烈绽放:
  - 草原核再受到火影响
- 扩散:
  - 风命中已带火/水/冰/雷附着的目标
- 结晶:
  - 岩命中已带火/水/冰/雷附着的目标

当前已支持的反应:

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

## 主命令

插件主命令为:

- `/yuanshen`
- `/ys`

常用命令:

- `/ys reload`
- `/ys stats [玩家]`
- `/ys damage <toggle|on|off> [玩家]`
- `/ys sidebar <toggle|on|off> [玩家]`
- `/ys skill <e|q>`
- `/ys open`
- `/ys list <character|weapon>`
- `/ys give character <角色ID> [等级] [命座] [玩家]`
- `/ys give weapon <武器ID> [等级] [精炼] [玩家]`
- `/ys give constellation [数量] [玩家]`
- `/ys player set <1|2|3|4>`
- `/ys admin`
- `/ys <火|水|冰|雷|风|岩|草|清除>`

## 配置结构

核心配置:

- `src/main/resources/config.yml`
- `src/main/resources/attributes.yml`
- `src/main/resources/sidebar.yml`
- `src/main/resources/character-gui.yml`
- `src/main/resources/starter-characters.yml`
- `src/main/resources/mod-keybind.yml`
- `src/main/resources/skills.yml`

内容资源:

- `src/main/resources/juese/*.yml`
- `src/main/resources/weapons/*.yml`
- `src/main/resources/mobs/*.yml`

服务端启动后会复制到:

- `plugins/Yuanshen/`

## PlaceholderAPI

安装 PlaceholderAPI 后，可使用 `%yuanshen_*%` 占位符。

常用示例:

- `%yuanshen_character%`
- `%yuanshen_element%`
- `%yuanshen_damage%`
- `%yuanshen_crit_rate%`
- `%yuanshen_crit_damage%`
- `%yuanshen_current_energy%`
- `%yuanshen_e_cooldown%`
- `%yuanshen_q_cooldown%`
- `%yuanshen_particle_status%`
- `%yuanshen_sidebar_enabled%`

## 数据持久化

当前会保存到 `plugins/Yuanshen/playerdata.yml` 的内容包括:

- 当前角色槽位
- 角色槽中的角色物品
- 部分技能冷却
- 角色能量
- 侧边栏开关
- 伤害显示开关
- 当前手动元素
- 部分角色运行时状态

更详细说明见:

- [Player Data Persistence](docs/persistence.md)

## 仓库文档与站点

仓库中已经包含一套拆分好的展示站点与 Wiki 页面:

- [官网首页](html/home.html)
- [功能总览](html/features.html)
- [内容资源](html/content.html)
- [命令与 GUI](html/commands.html)
- [Wiki 总览](html/wiki.html)
- [Wiki: 安装部署](html/wiki-install.html)
- [Wiki: 玩家上手](html/wiki-player.html)
- [Wiki: 管理员说明](html/wiki-admin.html)
- [Wiki: 配置说明](html/wiki-config.html)

如果你是在本地查看仓库，直接打开根目录的 `index.html` 即可跳转到站点首页。

## 当前明确不包含

- 完整 1:1 复刻原神全部战斗细节
- 完整角色池与完整武器池
- 面向大型公开服的长期稳定性保证

## 开发说明

项目使用:

- Java 21
- Gradle
- Paper API 1.21.4

构建命令:

```powershell
./gradlew.bat compileJava
./gradlew.bat clean shadowJar
```

如果你只是想验证源码是否能正常编译，执行 `./gradlew.bat compileJava` 即可。
