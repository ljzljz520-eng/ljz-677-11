# Excel数据导入系统

基于 Spring Boot + Vue 3 的Excel大数据导入系统，支持5万条数据**异步分批流式导入**、实时进度查询与断点恢复。

## 技术栈

- **Frontend**: Vue 3 + Element Plus + Tailwind CSS + Pinia
- **Backend**: Spring Boot 3.2 + MyBatis Plus + EasyExcel（SAX 流式）
- **Database**: MySQL 8.0
- **Security**: Spring Security + JWT + BCrypt加密

## 核心功能

- **异步任务化导入**：上传仅落盘并立即返回任务编号，后台线程 SAX 流式解析，HTTP 不长时间阻塞
- **分批解析入库**：每 1000 行校验并批量入库，内存中始终只有一批，5 万行不撑爆堆
- **实时进度**：已读取 / 已校验 / 成功 / 失败行数、百分比、速率（行/秒）、预计剩余时间
- **断点恢复**：进度落库，页面关闭重开凭任务编号继续查看；服务重启自动恢复处理中的任务
- **失败行管理**：校验失败行落 `import_row_error` 表，分页查看、导出修正，不在内存累积
- 数据上报国家平台（模拟）、用户登录认证（BCrypt）

## 启动指南

### 1. 确保 Docker Desktop 已启动

### 2. 在根目录执行

```bash
docker compose up -d --build
```

### 3. 等待容器启动完成（首次构建约3-5分钟）

查看日志：

```bash
docker compose logs -f
```

## 服务地址

| 服务        | 地址                                  |
| ----------- | ------------------------------------- |
| Frontend    | http://localhost:3000                 |
| Backend API | http://localhost:8080                 |
| Swagger文档 | http://localhost:8080/swagger-ui.html |
| Database    | localhost:3306                        |

## 测试账号

| 用户名 | 密码     |
| ------ | -------- |
| admin  | admin123 |

## 项目结构

```
677/
├── backend/                    # Spring Boot后端
│   ├── src/main/java/com/excel/
│   │   ├── config/            # 配置类
│   │   ├── controller/        # 控制器
│   │   ├── dto/               # 数据传输对象
│   │   ├── entity/            # 实体类
│   │   ├── listener/          # EasyExcel监听器
│   │   ├── mapper/            # MyBatis Mapper
│   │   ├── service/           # 服务层
│   │   └── utils/             # 工具类
│   └── Dockerfile
├── frontend/                   # Vue 3前端
│   ├── src/
│   │   ├── api/               # API接口
│   │   ├── assets/            # 静态资源
│   │   ├── components/        # 组件
│   │   ├── router/            # 路由
│   │   ├── stores/            # Pinia状态管理
│   │   └── views/             # 页面
│   └── Dockerfile
└── docker-compose.yml          # 容器编排
```

## API接口

### 认证接口

- `POST /api/auth/login` - 用户登录

### Excel接口

- `POST /api/excel/tasks` - 创建导入任务（上传Excel，立即返回任务编号）
- `GET /api/excel/tasks/{taskNo}` - 查询导入进度（已读取/已校验/失败行数、百分比、速率、ETA）
- `GET /api/excel/tasks/{taskNo}/errors` - 分页查询校验失败行
- `GET /api/excel/tasks/{taskNo}/errors/export` - 导出失败行Excel
- `GET /api/excel/records` - 获取导入任务记录
- `GET /api/excel/data/{batchNo}` - 获取批次数据
- `GET /api/excel/template` - 下载导入模板
- `POST /api/excel/report/{batchNo}` - 上报数据到国家平台
- `GET /api/excel/report/failed/{batchNo}` - 获取上报失败数据
- `POST /api/excel/report/retry/{batchNo}` - 重试上报
- `GET /api/excel/export/errors/{batchNo}` - 导出上报失败数据

### 导入任务状态

| status | 含义 |
| ------ | ---- |
| 0      | 排队/处理中（PENDING / COUNTING / PARSING / SAVING） |
| 1      | 完成（无失败行） |
| 2      | 完成但有失败行 |
| 3      | 失败 / 中断 |

> `.xlsx` 会先流式预扫描总行数（基于 zip 内 worksheet XML 的 SAX 计数，不加载 POI 模型），用于显示百分比和预计完成时间；`.xls` 无法廉价预扫描，按实际读取行数展示。

## 数据导入模板

| 字段     | 说明                | 是否必填 |
| -------- | ------------------- | -------- |
| 数据编号 | 唯一标识            | 是       |
| 姓名     | 姓名（最多50字符）  | 是       |
| 身份证号 | 18位身份证号        | 否       |
| 手机号   | 11位手机号          | 否       |
| 金额     | 数值，不能为负      | 否       |
| 地址     | 地址（最多200字符） | 否       |
| 备注     | 备注信息            | 否       |

## 注意事项

1. 上传后文件先落盘到 `app.import.temp-dir`（默认 `./import-temp`），后台线程从磁盘 SAX 流式解析；任务结束自动删除临时文件
2. 每 1000 行批量入库/落错误行，进度每约 500ms 落库一次，5 万行场景内存占用与行数无关
3. 任务进度持久化在 `import_record`，失败行在 `import_row_error`；页面重开凭任务编号（或 `?task=xxx`）恢复轮询
4. 服务重启时处理中且临时文件仍在的任务会自动重新执行（已入库数据会先清理，保证幂等）；临时文件丢失则标记为失败
5. 上报国家平台为模拟功能，会随机产生5%的失败率用于测试异常处理
6. 密码使用BCrypt加密存储
