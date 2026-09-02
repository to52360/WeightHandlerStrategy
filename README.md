### 依赖项目

> [Hearthstone-Script](https://github.com/xjw580/Hearthstone-Script)项目的插件

## ⚠️ 免责申明

本项目仅供学习交流 **`Java`**、**`Kotlin`** 以及 **`炉石传说`** 玩法，不得用于任何违反法律法规及游戏协议的地方！🚨😡

## 📖 协议

本项目遵循 **[GPL3.0开源协议](LICENSE)** 及 **[禁止商用附加协议](LICENSE1)**

## 目的

能够更为快速实现复杂和可扩展的权重规则,最终能通过Ai依赖可靠专属元语言生成可靠的规则,避免直接ai生成不该出现的预期

## 使用

解压到Hearthstone-Script根目录,plugin目录下两个基础插件移到lib目录

## 效果
基于战场计算权重的策略,例如在手牌对应种族就加权重,通过配置绑定到组,然后通过组id关联到权重表(CardWeight)的weight,依赖数据也是

## 权重的组成

1.主体思路  
权重按照评分树,再通过评分选出最优组合
2.大概计算规则  
组权重由weightHandlerStrategy.db配置  
总权重由combo编排权重 (表combo_plan_definition)+单卡权重决定（条件规则经 condition_tree_config，评估树配置经 tree_config
驱动）

## 问题

1.发现卡牌还是有问题(地标不会触发发现事件,会触发发现,也不会正确抉择,底层问题)  
2.由于一开始只想写个打出权重,由于不太理想,写了部分攻击/发现/换牌逻辑,为了快速实现
耦合在打出逻辑的基础数据里(CardWeightInfo,ComboCard)  
3.由于没UI,导致配置信息要使用编码或者数据库,无必要提醒和限制,导致容易配错  
3.1 ComboCard一些配置使用ParseCardWeightInfo接口配置(弃牌术的DropParse,应该用UI/配置文件)  
4.共用ComboCard模型导致难于调试,额外的状态导致增加扩展和维护复杂性  
5.存在combo,但是不够费用打出的处理策略没有
6.程序主体没有扩展接口(没有想情况需要什么扩展接口)
7.不支持热加载,改配置需要重启软件

### combo 编排说明

combo 编排由 `combo_plan_definition` 表承载，语义拆成正交字段（不再挤在一个字段里）：

- `coreGroupIds` / `depGroupIds`：核心组与依赖组，引用分组 binding id
- `score`：同组加权
- `coreMutex`：硬互斥，同一 combo 下多个核心候选不能同时进入本轮组合（该字段同时被起手换牌消费）
- `relation`：组级顺序，`SCORE_ONLY` / `CORE_BEFORE_DEP` / `DEP_BEFORE_CORE`

历史（已于 2026-09-02 拆除）：早期用 `combo_info` 一张表承载全部 combo 语义，并把 「换牌互斥 / 出牌顺序 / 同组加权」三种语义塞进同一个
`combo_weight` 字段，
`combo_type` 也是字符串、运行期按名字派发解析实现——扩展和维护都很麻烦。 该旧体系（`ComboParse` 一族 / `ParseCombo` /
`ComboInfoDao` 及 `combo_info` 表）已随新体系落地整体删除。





### 未来

1.大概就这样,效果不太好,需要更好的效果需要工作量有点多,且无法确定我的策略的方向是正确的  
2.实践理论的项目,现在找到了问题,算是完成了找问题的目标




