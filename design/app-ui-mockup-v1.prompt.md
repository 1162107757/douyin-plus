# 隔空刷 App UI 设计图生成提示词

生成方式：Codex 内置 ImageGen。

## 主提示词

```text
Use case: ui-mockup
Asset type: one high-fidelity Android app design presentation board for product review
Primary request: Create one polished landscape design image showing three straight-on Android phone screens side by side for a hands-free short-video gesture controller app. The app uses the front camera to detect an upward hand wave and switch to the next video.
Scene/backdrop: clean warm off-white presentation canvas with a faint technical dot grid and restrained cobalt accents; no desk, no hands holding the phones, no perspective distortion.
Style/medium: high-fidelity native Android Material 3 product UI, modern Swiss minimalism, professional accessibility tool, realistic app screenshot quality. Calm, trustworthy, privacy-conscious, distinctive but not flashy. Crisp vector icons from one consistent rounded outline family. 8dp spacing rhythm, large 48dp touch targets, generous safe areas, strong hierarchy, subtle sharp shadows, rounded 24dp cards. Avoid excessive glassmorphism and avoid generic purple gradients.
Color palette: background #F8FAFC, cards #FFFFFF, primary cobalt #2563EB, success green #16A34A, warning orange #EA580C, text #1E293B, muted #475569, border #E2E8F0. Use color plus text/icon for every state.
Typography: clean Chinese sans-serif similar to Roboto/Noto Sans SC, highly legible, bold titles, normal body copy.
Composition/framing: 16:9 landscape board, three equal phone mockups with full screens visible and generous spacing. Small labels above the phones: "主页", "运行中", "手势校准". Main board title at top left: "隔空手势控制 · Android App". No extra explanatory paragraphs.
Screen 1 — Home:
- top app name "隔空刷" and small subtitle "免触碰 · 本地识别"
- prominent status card with green dot, exact text "准备就绪", support text "权限已完成，可以开始"
- two concise permission rows with vector camera and accessibility icons: "相机  已开启" and "辅助功能  已开启"
- one primary full-width cobalt button "开始隔空控制"
- one secondary outlined button "手势校准"
- a quiet text action "打开抖音"
- small privacy line with shield icon: "画面仅在本机处理"
Screen 2 — Running:
- top title "正在运行" and compact green status chip "识别中"
- large central dark camera-analysis card, not a real video feed: use a tasteful abstract human silhouette with one raised open hand, precise blue hand landmark dots and connecting lines, and a clear upward trajectory arrow
- instruction pill "张开手掌，向上挥动"
- feedback state card with check icon, exact text "已识别 · 切换下一个视频"
- bottom controls: outlined button "暂停" and red text/outline button "停止"
- small persistent-service row "前摄运行中 · 画面不保存"
Screen 3 — Calibration:
- top title "手势校准" with back arrow
- large rounded camera preview area with a neutral dark background, one open hand centered inside a rounded guide frame, accurate hand landmark skeleton, and a vertical green target path
- exact instruction "将手掌放入框内"
- two small result cards: "距离  合适" and "光线  良好"
- one sensitivity segmented control with labels "低", "中", "高", with "中" selected
- primary cobalt button "完成校准"
Constraints: all Chinese text above must be rendered verbatim, sharp, correctly spelled, and readable. Preserve Android safe areas and gesture navigation space. Each screen has only one visual primary action. Status is never communicated by color alone. No emojis, no illegible tiny text, no decorative fake charts, no browser chrome, no iPhone notch, no Apple styling, no brand logo, no watermark, no gibberish text.
Avoid: clutter, neon cyberpunk, purple gradient, heavy glass blur, oversized empty hero area, playful cartoon style, floating 3D objects, random English words, duplicated buttons, distorted phone frames.
```

## 收尾修正提示词

```text
Clean only the presentation canvas outside the three phone mockups. Remove the vertical slogan on the far left and the small slogan blocks at the top right and bottom right. Fill those areas with the existing warm off-white faint dot-grid background. Keep the board title, screen labels, phone frames, every phone UI element, Chinese label, icon, color, spacing, hand landmark visualization, arrow, status bar, and navigation bar exactly unchanged. Do not add new text or decorations.
```

