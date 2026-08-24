# 剪剪相传 · UI 差距清单

> 从属于《设计纲领》（`DESIGN-CHARTER.md`）与 `tokens.md`。
> 本文件记录「章程 → 实现」尚未合拢的差距，按影响排序；每完成一轮打磨更新一次。
> 最后更新：2026-08-24（WPF 夜间主题 / Windows 随包字体 打磨轮之后）

---

## 一、本轮已合拢的差距（记录留档）

| 项 | 落地 |
|---|---|
| 应用图标 | 折线标记应用图标（`docs/design/icons/app-icon.svg` + 16 网格小帧变体）。Windows `app.ico`（16–256 八档，`scripts/generate-app-icons.py` 生成）接入 exe（`ApplicationIcon`）与全部窗口（`Window.Icon`）；Android 自适应图标（前景/背景/monochrome 三层矢量，minSdk 29 无需光栅 mipmap），默认机器人不再出现 |
| Windows 标题栏 | 三个窗口全部 `WindowChrome` 自绘：灰蓝 `sf3` 顶栏、品牌折线 + 衬线名、窗控三钮（关闭钮悬停用 err 着色盒）。主窗标题栏承载 暂停/私密 开关与四段 mini rail（纲领 3.7：rail 在标题栏复用） |
| 配对二维码窗口 | 纯白静区保留，外加流动蓝四角取景框；倒计时转 Mono「机器的声音」；无可达地址 = 赭色着色盒（需要你操作，不是错误）；指纹四位一组、八组一行、两行、t1 最高对比；文案全中文 |
| 配对批准窗口 | 请求方用设备邻近色着色盒 + 手机字形；重配对警示 = err 着色盒（真 error：密钥替换）；批准 = 唯一实心主按钮 |
| Android 配对页 | 全中文文案（与 `ui_preview.html` 逐字一致）；证书变更 = 赭黄整块警示 + 「我已核实 — 替换配对」责任按钮（全应用唯一）；指纹两行分组；扫码取景框 = charter 卡 + 流动蓝四角；幽灵按钮族对齐章程 |
| Android 系统表面 | 通知/QS 磁贴小图标换折线标记（单色、2.4 线宽，对齐 16 网格托盘视觉重量）；`setColor` 接流动蓝；同步通道补描述、去角标；前台通知状态文案全中文并入 `strings.xml` |
| 托盘/杂项中文化 | 托盘菜单、气泡提示、「需要你操作」缘由、配对不可用弹窗全部转中文 |
| WPF 夜间主题 | `CharterTokensNight.xaml` 同键夜间字典（tokens §2 夜值，夜间抬起 = 提亮面色）；`CharterThemeManager` 读 `SystemUsesLightTheme`（与托盘同一取值，缺省视为深色）、监听 `SystemEvents.UserPreferenceChanged` 运行时整体替换字典；三个窗口约 170 处 `StaticResource` 刷成 `DynamicResource`，切主题无需重启 |
| Windows 随包字体（Sans/Mono） | `Resources/Fonts/` 打包 Noto Sans SC 400/500、Plus Jakarta Sans 400/500/600、JetBrains Mono 400/500（OFL 许可证随文件）；`CsSans`/`CsMono` 改指随包字体，三窗口不再落 `Segoe UI / 微软雅黑`，Mono 链中文回落到随包 Noto Sans SC；`Consolas` 仅作加载失败兜底 |

---

## 二、剩余差距（按影响排序）

### P1 · 直接可见的缺口

| 差距 | 现状 | 需要做 |
|---|---|---|
| **字体随包分发（剩余部分）** | Windows Sans/Mono 已随包（见上）；衬线仍落 `SimSun`（Noto Serif SC 单字重 ~11MB，暂缓）；**Android 三个声音仍全部落系统/OEM 字体** | Android 打包 `NotoSansSC`、`JetBrainsMono`（Compose `FontFamily(Font(...))`）；衬线两端一起裁决：要么接受体积打包 `NotoSerifSC-SemiBold`，要么改章程 |
| **空状态 / 首次运行** | 历史空列表是空白区域；首次运行没有「去配对」的引导 | 空状态是三处衬线时刻之一（纲领 3.5）：Serif 短句 + 幽灵配对按钮；两端都缺 |
| **卡片深度不足**（Windows） | 主窗卡片是 1px 边框平面卡，无 `sh-1` 阴影、无 `sf-grad` 受光 | tokens §8：最外层挂一次 `DropShadowEffect`，内层 1px 边框，最内 2px 顶部高光渐变 |

### P2 · 章程规定但未接线

| 差距 | 现状 | 需要做 |
|---|---|---|
| 设备邻近色按配对顺序分配 | Windows 历史来源盒硬编码 `dev2`、设备行硬编码 `dev1`；Android 对端色未用 | 按配对顺位 1..5 取 `dev-N`，存储在设备记录上、允许手动改 |
| 动效令牌 | 两端均无统一的 `cubic-bezier(.16,1,.3,1)` + 时长档；「需要你操作」的 2.6s 描边脉动未做；配对成功的一次性镜面流光未做 | WPF `Storyboard` / Compose `CubicBezierEasing(0.16f,1f,0.3f,1f)` 接入交互过渡 |
| 超椭圆 | 两端全部是普通圆角矩形（n=2） | n≈4–5 超椭圆：WPF 自绘 `Geometry`、Compose 自定义 `Shape`；两端共享 n |
| Windows 托盘浮窗 | tokens §12.6 规格已定（440px、3 秒、无颗粒无时间性动效、标题栏右侧 rail），未实现 | 独立 `Popup`/无边框窗 + 最近条目 + rail |
| QS 磁贴状态 | 磁贴常为可用态，未随服务/暂停状态切 active/inactive | `TileService.qsTile.state` 接 `ClipboardSyncService.connectionStates` |
| 托盘主题只采样一次 | 深浅任务栏切换后托盘图标不换套（启动时读一次注册表） | 监听 `SystemEvents.UserPreferenceChanged` 重载图标 |

### P3 · 核验与打磨

| 差距 | 现状 | 需要做 |
|---|---|---|
| Win10 下自绘 chrome 表现 | `GlassFrameThickness=-1` 在 Win11 有圆角+投影；Win10 直角待实机核验；配对两窗 `SizeToContent=Height` 与 WindowChrome 组合的首次布局也需实机确认无底部空隙 | 实机核验；必要时按系统版本回退 |
| MIUI 通知表现 | `setColor`/折线小图标在 MIUI 会被二次改写（纲领 5.8 预期内） | 实机核验 Redmi；不达标则接受系统绘制 |
| 夜间设备色 | 藕紫 305 / 灰粉 335 的夜值仍未实机核验（纲领 §六 遗留） | 实机比对后修 `tokens.md` |
| 高 DPI 的 QR 整数缩放 | `pixelsPerModule=8` 固定；150% 缩放下模块非整像素 | 按 DPI 取整 `pixelsPerModule` |
| 可访问性 | 窗控钮有 `AutomationProperties`，但开关/来源盒的读屏文案不全；`t4` 压 `sf3` 的对比度边缘案例 | 系统性过一遍 AutomationProperties / contentDescription |
| Android 状态栏色硬编码 | `values/styles.xml` 直写 `#E2E9F2` / `#0C1116`，与 tokens 重复 | 收进 `colors.xml` 引用（与 `cs_flow` 同处） |
| 历史图片项 | 74dp 缩略条（纲领 §六 待裁决）完全未做——当前阶段仅文本同步 | 随图片同步阶段一起做 |

---

## 三、明确不做 / 已裁决

- **托盘浮窗、通知、磁贴不铺颗粒**（tokens §5）：现状正确，保持。
- **绿色**：全部资产核查过，无色相 100–180 出现。
- **Android 启动图标夜间变体**：自适应图标背景自带 charter 底色，深色主题人格由 monochrome 层（API 33+ 主题图标）承担，不做第二套。
- **红色关闭钮**：窗控关闭钮悬停用 err 着色盒（低彩度红族着色盒语汇），不用系统饱和红——「关闭」是惯例位置，红族提示破坏性边界，但不喊叫。
