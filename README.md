# 医保清单 Excel 导入系统

基于 Spring Boot + Vue 3 的医保清单大数据导入系统，支持 **5 万行** Excel 的分批异步导入、实时进度与任务恢复。

## 技术栈

- **Frontend**: Vue 3 + Element Plus + Tailwind CSS + Pinia
- **Backend**: Spring Boot 3.2 + MyBatis Plus + EasyExcel
- **Database**: MySQL 8.0
- **Security**: Spring Security + JWT + BCrypt加密

## 核心能力（分批异步导入）

- **不整表入内存**：EasyExcel SAX 每 1000 行成块，解析线程与"校验+入库"线程之间用**有界队列（容量 4 块）**衔接，队列满时解析自动阻塞形成背压，内存占用恒定。
- **失败行不堆积内存**：校验失败行每 1000 行批量写入 `import_error_row` 表，支持分页查看与流式导出。
- **异步任务 + 任务编号**：上传后文件先落盘、立即返回 `batchNo`，后台线程池处理，HTTP 请求不阻塞。
- **实时进度**：前端每秒轮询，展示**总行数、已读取、已校验、失败行数、进度百分比、预估剩余时间（ETA）、处理速度**。
- **页面重开可恢复**：进度全部持久化在 `import_record`，凭任务编号可在任意页面继续查看；前端 localStorage 自动记住未完成任务。
- **后端重启自愈**：启动时自动扫描 `解析中` 任务，临时文件仍在则清理半成品后重跑，文件丢失则标记失败。
- 数据上报国家平台（模拟）、异常数据导出、JWT 登录认证。

## 处理阶段

`COUNTING(预扫描总行数)` → `PARSING(分块读取解析)` → 校验入库（与解析并行）→ `DONE / FAILED`

任务状态：`0-解析中 1-完成 2-完成(有失败行) 3-失败`

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

- `POST /api/excel/import` - 创建异步导入任务（返回 batchNo）
- `GET /api/excel/import/{batchNo}/progress` - 查询导入进度（已读取/已校验/失败/ETA）
- `GET /api/excel/import/{batchNo}/errors?pageNum=&pageSize=` - 分页查询校验失败行
- `GET /api/excel/import/{batchNo}/errors/export` - 流式导出校验失败行
- `GET /api/excel/records` - 获取导入记录
- `GET /api/excel/data/{batchNo}` - 获取批次数据
- `GET /api/excel/template` - 下载导入模板
- `POST /api/excel/report/{batchNo}` - 上报数据到国家平台
- `GET /api/excel/report/failed/{batchNo}` - 获取上报失败数据
- `POST /api/excel/report/retry/{batchNo}` - 重试上报
- `GET /api/excel/export/errors/{batchNo}` - 导出上报失败数据

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

1. 系统使用EasyExcel的SAX模式解析Excel，内存占用低，支持大文件
2. 数据每1000条批量入库，保证性能
3. 上报国家平台为模拟功能，会随机产生5%的失败率用于测试异常处理
4. 密码使用BCrypt加密存储，与数据库密码加密方式一致
