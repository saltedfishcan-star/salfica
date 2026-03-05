# ATD 图片榜单系统

## 项目简介
本项目是一个“图片榜单（Tier List）”应用，包含：

- 后端：Spring Boot，负责图片链接解析与图片代理（解决跨域、统一安全校验）。
- 前端：Vue 3 + TypeScript，负责榜单配置、拖拽排序、导出图片。

## 核心功能
- 配置榜单标题、等级与颜色。
- 通过选择文件、粘贴链接、拖拽内容添加图片。
- 支持非直链页面解析图片（由后端完成）。
- 榜单页拖拽排序（等级区与待排序池互拖）。
- 导出 `rank-table` 区域为 PNG 图片。

## 技术栈
- 后端：Java 17、Spring Boot 3、Jsoup
- 前端：Vue 3、Vue Router、TypeScript、Vite、vuedraggable、html2canvas

## 目录结构
```text
.
├─ src/                       # Spring Boot 后端
│  ├─ main/java/com/example/atd
│  │  ├─ AtdApplication.java
│  │  └─ image/               # 图片解析与代理模块
│  └─ main/resources/application.properties
├─ vue_atd/                   # Vue 前端
│  ├─ src/
│  │  ├─ lib/board.ts         # 前端数据模型与本地存储逻辑
│  │  └─ views/               # Setup / Board 页面
│  └─ package.json
└─ docs/设计文档.md            # 设计文档
```

## 文档索引
- [设计文档](docs/设计文档.md)
- [学习文档（总览）](docs/学习文档.md)
- [学习文档（新手版）](docs/学习文档-新手版.md)
- [学习文档（进阶版）](docs/学习文档-进阶版.md)
- [内存兜底机制逐句说明](docs/内存兜底机制逐句说明.md)
- [防模糊改造逐句说明](docs/防模糊改造逐句说明.md)

## 快速启动

### 1. 启动后端
在项目根目录执行：

```bash
mvn spring-boot:run
```

默认端口：`http://localhost:8080`

### 2. 启动前端
在 `vue_atd` 目录执行：

```bash
npm install
npm run dev
```

默认端口（Vite）：`http://localhost:5173`

## 构建

### 后端构建
```bash
mvn package
```

### 前端构建
```bash
cd vue_atd
npm run build
```

## 接口说明（简版）
- `POST /api/image/resolve`
  - 入参：`{ "url": "..." }`
  - 出参：解析状态、图片地址、代理地址或失败原因。

- `GET /api/image/proxy?target=<encodedUrl>`
  - 返回：图片二进制流与对应 `Content-Type`。
