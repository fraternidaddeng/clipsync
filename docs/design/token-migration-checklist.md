# 令牌迁移清单（现有硬编码色 → 纲领令牌）

> 逐条列出现有 UI 代码里的每一个绿色 / 硬编码颜色，映射到 `tokens.md` 的令牌名与十六进制值。
> 盘点基准：`feature/stage-4`（`ced3bd3`）——它是 UI 代码的最新形态；`main` 上的颜色是它的子集（`main` 无 `DetailWindow.xaml`，也无 `#EEF1F5` / `#3D4450`）。
> 现有 UI 是随手生成的壳子，**版面结构本身也会重构**；本清单只保证「没有一个旧色值能溜进新 UI」。
> 执行本清单**不属于**本次任务（本次只交付文档与预览），供后续实装用。

**盘点结论**：绿色共 4 处（`#146C43` / `#72D69E` / `#00391F` / `#27844F`，色相全部落在禁用区 100–180），其余硬编码 27 处、`White` 字面量 6 处、`Consolas` 1 处。Compose 屏幕代码（`ui/` 下除 `Theme.kt`）与托盘图标工厂（`TrayIconFactory.cs`，用 `SystemIcons.Application`）没有额外硬编码色。

---

## 一、`android/app/src/main/java/com/clipsync/android/ui/theme/Theme.kt`

### LightColors → 日间令牌

| 行 | M3 角色 | 现值 | 问题 | 新令牌 | 新值 |
|---|---|---|---|---|---|
| 11 | `primary` | `#146C43` | **绿色，禁用区** | `flow` | `#215f8f` |
| 12 | `onPrimary` | `Color.White` | 硬编码 | `on-flow` | `#f6f9fc` |
| 13 | `secondary` | `#53646F` | 非灰蓝族 | `t3` | `#6b7a8b` |
| 14 | `background` | `#F7F8FA` | 纯灰，无蓝味，无阶梯 | `bg`（+ `bg-grad` + 颗粒） | `#e2e9f2` |
| 15 | `surface` | `#F7F8FA` | 与背景同色 = 零层次 | `sf`（+ `sf-grad`） | `#f6f9fc` |
| 16 | `onSurface` | `#171A1C` | 纯灰 | `t1` | `#1c2733` |
| 17 | `onSurfaceVariant` | `#5D6469` | 纯灰 | `t3` | `#6b7a8b` |
| 18 | `outline` | `#7A858C` | 纯灰 | `ln2` | `#bcc9d9` |
| 19 | `error` | `#B3261E` | M3 默认红 | `err` | `#a8342b` |

### DarkColors → 夜间令牌

| 行 | M3 角色 | 现值 | 问题 | 新令牌 | 新值 |
|---|---|---|---|---|---|
| 23 | `primary` | `#72D69E` | **绿色，禁用区** | `flow` | `#6fa8d4` |
| 24 | `onPrimary` | `#00391F` | **绿色，禁用区** | `on-flow` | `#0c1116` |
| 25 | `secondary` | `#B8C8D2` | 非灰蓝族 | `t3` | `#8b98a8` |
| 26 | `background` | `#111416` | 无蓝味 | `bg`（+ `bg-grad` + 颗粒） | `#0c1116` |
| 27 | `surface` | `#111416` | 与背景同色 = 零层次 | `sf` | `#1b232e` |
| 28 | `onSurface` | `#E2E5E7` | 纯灰 | `t1` | `#e3e9f0` |
| 29 | `onSurfaceVariant` | `#BFC6CA` | 纯灰 | `t3` | `#8b98a8` |
| 30 | `outline` | `#899399` | 纯灰 | `ln2` | `#3e4a59` |
| 31 | `error` | `#FFB4AB` | M3 默认红 | `err` | `#e0776c` |

### 缺失项（现在没有、必须补的）

| 项 | 令牌 | 日间 / 夜间 |
|---|---|---|
| `tertiary`（召唤赭） | `act` | `#9b6b24` / `#d9a15c` |
| `surfaceVariant` | `sf3` | `#e7edf5` / `#1f2833` |
| `outlineVariant` | `ln` | `#cfd9e6` / `#2c3744` |
| 阶梯 z−1 / z2、渐变、设备色 dev-1…5、状态 `-bg`/`-ln` 半透明变体 | `ClipSyncColors`（CompositionLocal） | 见 `tokens.md` 第十一节 |

---

## 二、`windows/ClipSync.App/App.xaml`

| 行 | 现状 | 动作 |
|---|---|---|
| 5 | `<Application.Resources />` 空字典 | 建立日/夜两套 `ResourceDictionary`（`Cs*` 键，见 `tokens.md` 第十二节）。**下面所有 XAML 十六进制字面量迁移后必须归零。** |

---

## 三、`windows/ClipSync.App/MainWindow.xaml`

| 行 | 位置 | 现值 | 问题 | 新令牌（资源键） | 新值（日间） |
|---|---|---|---|---|---|
| 16 | 根 Grid `Background` | `#F7F8FA` | 纯灰底 | `bg` → `CsBgBrush`（178° 渐变刷） | `#e2e9f2` |
| 22 | 标题栏 Border `Background` | `White` | 硬编码 | `sf` → `CsSurfaceBrush` | `#f6f9fc` |
| 22 | 标题栏 `BorderBrush` | `#DDE1E7` | 硬编码 | `ln` → `CsLineBrush` | `#cfd9e6` |
| 30 | 副标题 `Foreground` | `#5E6672` | 硬编码 | `t3` → `CsText3Brush` | `#6b7a8b` |
| 61 | 历史 ListBox `BorderBrush` | `#CBD1D9` | 硬编码 | `ln` → `CsLineBrush` | `#cfd9e6` |
| 77 | 缩略图槽 `Background` | `#EEF1F5` | 硬编码 | `sf-in` → `CsSurfaceInBrush`（z−1 凹陷 + 内阴影） | `#dde5ef` |
| 89 | 图片说明 `Foreground` | `#3D4450` | 硬编码 | `t2` → `CsText2Brush` | `#3d4a59` |
| 93 | 来源+时间戳行 `Foreground` | `#6D7580` | 硬编码 | `t4` → `CsText4Brush`；时间戳换 JetBrains Mono；来源改**低彩度着色盒**（`dev-1…5`） | `#9aa7b6` |
| 110 | 设置面板 `Background` / `BorderBrush` | `White` / `#CBD1D9` | 硬编码 | `sf` / `ln` | `#f6f9fc` / `#cfd9e6` |
| 133 | 图片同步说明 `Foreground` | `#6D7580` | 硬编码 | `t3` → `CsText3Brush` | `#6b7a8b` |
| 143 | 地址重启提示 `Foreground` | `#6D7580` | 硬编码 | `t3` | `#6b7a8b` |
| 151 | 明文导出警告 `Foreground` | `#6D7580` | 硬编码 | `t3` | `#6b7a8b` |
| 156 | 导出状态 `Foreground` | `#3B424C` | 硬编码 | `t2` | `#3d4a59` |
| 164 | 设备 ListBox `BorderBrush` | `#CBD1D9` | 硬编码 | `ln` | `#cfd9e6` |
| 173 | 设备平台/最后在线 `Foreground` | `#6D7580` | 硬编码 | `t3` | `#6b7a8b` |
| 176 | 已吊销状态文字 `Foreground` | `#A4262C` | **把「事实」画成错误** | `t3`（灰 = 陈述事实；红只留给 error） | `#6b7a8b` |
| 190 | 吊销按钮 `Foreground` | `#A4262C` | 用颜色恐吓破坏性操作 | `t2`（破坏性操作靠二次确认防护，不靠红色） | `#3d4a59` |
| 198 | 状态圆点 Ellipse `Fill` | `#27844F` | **绿色，禁用区**；且是被否决的红绿状态灯 | `flow` → `CsFlowBrush`；随后整体改造为四段 rail（满填充 = 就绪） | `#215f8f` |

---

## 四、`windows/ClipSync.App/DetailWindow.xaml`（仅存在于 feature/stage-4）

| 行 | 位置 | 现值 | 问题 | 新令牌 | 新值（日间） |
|---|---|---|---|---|---|
| 11 | 根 Grid `Background` | `#F7F8FA` | 纯灰底 | `bg` → `CsBgBrush` | `#e2e9f2` |
| 21 | 创建时间 `Foreground` | `#6D7580` | 硬编码 | `t4` + JetBrains Mono | `#9aa7b6` |
| 27 | 正文 TextBox `BorderBrush` | `#CBD1D9` | 硬编码；且输入面无凹陷 | `ln`；底色补 `sf-in`（z−1） | `#cfd9e6` / `#dde5ef` |

---

## 五、`windows/ClipSync.App/Pairing/PairingApprovalWindow.xaml`

| 行 | 位置 | 现值 | 问题 | 新令牌 | 新值（日间） |
|---|---|---|---|---|---|
| 12 | 根 StackPanel `Background` | `White` | 硬编码 | `sf` → `CsSurfaceBrush` | `#f6f9fc` |
| 14 | 正文 `Foreground` | `#3B424C` | 硬编码 | `t2` | `#3d4a59` |
| 16 | 设备信息盒 `Background` | `#F7F8FA` | 与窗口底几乎同色 = 零层次 | `sf-in`（z−1 凹陷面） | `#dde5ef` |
| 16 | 设备信息盒 `BorderBrush` | `#DDE1E7` | 硬编码 | `ln` | `#cfd9e6` |
| 19 | 平台文字 `Foreground` | `#5E6672` | 硬编码 | `t3` | `#6b7a8b` |
| 24 | 重配对警告 `Foreground` | `#A4262C` | 硬编码（语义恰好正确） | `err`（证书变更属于纲领允许红色的 error；整块处理见 `ui_preview.html` 配对屏） | `#a8342b` |

---

## 六、`windows/ClipSync.App/Pairing/PairingQrWindow.xaml`

| 行 | 位置 | 现值 | 问题 | 新令牌 | 新值（日间） |
|---|---|---|---|---|---|
| 10 | 根 StackPanel `Background` | `White` | 硬编码 | `sf` → `CsSurfaceBrush` | `#f6f9fc` |
| 12 | 扫码提示 `Foreground` | `#5E6672` | 硬编码 | `t3` | `#6b7a8b` |
| 15 | 二维码盒 `Background` | `White` | 硬编码（但语义必须保留） | `qr-quiet` → `CsQrQuietBrush`——**二维码静区保持纯白、不随主题**，否则灰蓝底干扰识别 | `#ffffff`（日夜同值） |
| 15 | 二维码盒 `BorderBrush` | `#DDE1E7` | 硬编码 | `ln` | `#cfd9e6` |
| 18 | 倒计时 `Foreground` | `#5E6672` | 硬编码 | `t3`（中文句子，不上 Mono） | `#6b7a8b` |
| 21 | 无可用网络接口 `Foreground` | `#A4262C` | **把「需要你操作」画成错误** | `act`（召唤赭：需要用户接入网络，不是故障） | `#9b6b24` |
| 27 | 设备名 `Foreground` | `#3B424C` | 硬编码 | `t2` | `#3d4a59` |
| 28 | 指纹 `FontFamily` | `Consolas` | 未随包分发、非设计字体 | JetBrains Mono（随包分发，`Fonts/#JetBrains Mono`） | — |
| 28 | 指纹 `Foreground` | `#3B424C` | 硬编码 | `t1`（指纹是全应用最高风险比对，用最高对比 + 四位分组 + 两行） | `#1c2733` |

---

## 七、伴随事项（不是颜色，但同批处理）

| 项 | 现状 | 动作 |
|---|---|---|
| 托盘图标 | `TrayIconFactory.cs` 用 `SystemIcons.Application` | 换折线标记（6 顶点单笔画，16px 可辨；「需要你操作」时转折点亮赭黄小点） |
| 中文字体 | 两端均未显式指定 | 随包分发 Noto Sans SC（+ Noto Serif SC 600、JetBrains Mono、Plus Jakarta Sans），见 `tokens.md` 第六节 |
| 夜间主题（WPF） | 完全没有 | 日/夜两套资源字典运行时切换；夜间抬起 = 变亮，不是加深阴影 |
| 阴影 / 高光 / 渐变 | 完全没有（全平面） | 按 `tokens.md` 第八节三层嵌套 Border 落地 |
| 圆角 | 4 / 6 混用 | Windows 统一 14（窗）/ 10（卡）/ 8（控件），嵌套定律 `内 = 外 − 内边距` |
