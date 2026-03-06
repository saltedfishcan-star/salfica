# ATD 图片榜单系统（纯前端版）

## 项目简介
ATD 是一个图片榜单（Tier List）工具，当前版本为纯前端实现，支持：

- 配置榜单标题、等级名称和颜色
- 导入本地图片文件
- 粘贴或拖拽图片直链 URL（`http/https`）
- 拖拽图片完成分级排序
- 导出 `rank-table` 区域为 PNG

## 重要限制
- 不再提供后端服务
- 不再支持网页链接自动解析图片
- 仅支持图片直链 URL；若来源站点限制外链加载，图片会显示失败态

## 技术栈
- Vue 3
- Vue Router
- TypeScript
- Vite
- vuedraggable
- html2canvas

## 目录结构
```text
.
├─ src/
│  ├─ lib/board.ts         # 数据模型与本地存储
│  ├─ views/SetupView.vue  # 配置与图片导入
│  ├─ views/BoardView.vue  # 拖拽排序与导出
│  └─ router/index.ts
├─ public/
├─ docs/
├─ package.json
└─ vite.config.ts
```

## 本地开发
在项目根目录执行：

```bash
npm install
npm run dev
```

默认开发地址：`http://localhost:5173`

## 构建
```bash
npm run build
```

## 部署说明
项目已在 `vite.config.ts` 中设置：

```ts
base: '/salfica/'
```

该配置适用于 GitHub Pages 项目站点（仓库名 `salfica`）。

## 在线访问
- GitHub Pages 地址：`https://saltedfishcan-star.github.io/salfica/`
- 若访问根域名 `https://saltedfishcan-star.github.io/` 显示 404，属于正常现象（该仓库是项目站点，不是用户站点）。

## 文档索引
- [设计文档](docs/设计文档.md)
- [学习文档（总览）](docs/学习文档.md)
- [学习文档（新手版）](docs/学习文档-新手版.md)
- [学习文档（进阶版）](docs/学习文档-进阶版.md)
