# UI 预览稿（mockups）

历史列表宪章预览，对应 `main` 提交 `c513f78`（信息架构归位 + 滚动条宪章化）。
色值逐一取自 `windows/ClipSync.App/Resources/CharterTokens.xaml`（日）与
`CharterTokensNight.xaml`（夜）。

| 文件 | 内容 |
| --- | --- |
| `history-ui-preview.html` | 自包含 HTML+CSS 预览（日/夜并排），零外部依赖，离线可开 |
| `history-ui-preview-day.png` | 日间主题渲染图（2x） |
| `history-ui-preview-night.png` | 夜间主题渲染图（2x） |

## 怎么打开

- 直接双击 `history-ui-preview.html`（任意浏览器，离线即可）；
- 或命令行：macOS `open docs/mockups/history-ui-preview.html`，
  Windows `start docs\mockups\history-ui-preview.html`，
  Linux `xdg-open docs/mockups/history-ui-preview.html`；
- 或直接看两张 PNG。

## 重生成 PNG

HTML 支持 `?theme=day` / `?theme=night` 单主题渲染：

```bash
google-chrome --headless=new --force-device-scale-factor=2 --window-size=768,700 \
  --screenshot=docs/mockups/history-ui-preview-day.png \
  "file://$PWD/docs/mockups/history-ui-preview.html?theme=day"
```

`night` 同理。仅为文档预览，不属于应用代码。
