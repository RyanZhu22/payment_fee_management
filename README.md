# OMSWeb 支付与费用管理

基于[业务逻辑](业务逻辑.md)构建的本地演示应用。前端使用 React 19、TypeScript 和 Vite，后端使用 Java 25 和 Spring Boot 4，业务数据存放在 PostgreSQL 18。Excel 由 Apache POI 处理，通知和收据由 JasperReports 生成。

## 启动

本机运行需要 Docker Desktop、Java 25、Maven、Node.js 24 和 OpenSSL。在项目目录运行以下命令。

    ./start.sh

首次运行会在 Git 忽略的 `.env` 中生成随机数据库密码和演示密码，并拉取数据库镜像、安装依赖和构建应用。看到服务启动后打开 [http://localhost:8080](http://localhost:8080)。按 Ctrl+C 停止应用。用 docker compose down 停止数据库容器，数据库卷继续保留费用记录。

也可让 Docker 构建并启动整套应用。此方式需要 Docker Desktop 和 OpenSSL。

    ./start.sh --docker

如果只改前端，启动后端后在另一个终端执行：

    npm --prefix frontend ci
    npm --prefix frontend run dev

前端开发地址为 [http://localhost:5173](http://localhost:5173)，API 请求由 Vite 转发。启动脚本可通过 SERVER_PORT 和 OMS_DB_PORT 环境变量更改本机端口。修改后端端口时，前端开发配置也需要指向相同的 API 地址。

如果已有本地数据库卷，修改 `.env` 中的 `OMS_DB_PASSWORD` 后需要同步修改数据库用户的密码。使用以下命令进入运行中的数据库容器。

    docker compose exec db psql -U omsweb -d omsweb

在 PostgreSQL 提示符下运行 `\password omsweb`，输入两次新密码，然后用 `\q` 退出。重新运行启动脚本即可保留已有数据并使用新凭据。

## 界面语言

在侧栏底部选择简体中文或 English。移动端可在顶部切换，浏览器会记住选择。报表和新生成的 PDF 跟随所选语言。已归档的 PDF 按生成时的版本下载。Excel 导入模板和 FIS 文件使用固定的接口字段，费用和付款人名称以原始业务资料为准。

## 登录与职责

本地演示账号为 `operator`、`finance`、`auditor`、`admin`。密码取自当前环境或 `.env` 里的 `OMS_DEMO_PASSWORD`。

- 运营人员维护未结算费用，按规则开立费用，上传结算凭证或提交冲正申请。
- 财务人员发布费率，运行定期计费，复核结算与冲正，并导出 FIS 文件。
- 审计员可查阅业务记录、原始结算文件和审计日志，并导出运营报表。
- 管理员可以管理账号，并可办理运营及财务操作。结算与冲正均须由另一账号复核。

非演示模式通过应用环境设置 `OMS_DEMO_ENABLED=false`。首次启动时使用 `OMS_ADMIN_USERNAME` 和 `OMS_ADMIN_PASSWORD` 创建本地管理员，密码至少 12 个字符且最多 72 个 UTF-8 字节。权限按单机构的角色和操作划分。

## 体验流程

1. 财务在「计费规则」登记计划，发布具有生效日期的规则。金额支持固定金额，附加费支持按原费用比例计算。到期日按计费日加指定天数计算。定期费按计划和月份防止重复开立，周期以所选规则的生效月份为起点。
2. 运营选择计划、费用类型和计费日，预览金额与到期日，再开立费用。申请费用填写申请编号，附加费选择原费用。费用台账提供未结算费用的人工维护入口。
3. 运营从「结算批次」下载 APPLN 或 FEESU 空白模板，按申请编号或费用 ID 填写实收金额、结算日、付款方式、银行入账日、渠道参考号和来款方。支持 Excel 日期和 YYYY-MM-DD。上传后可查看预检、保存的原文件和逐行结果。
4. 财务选择待复核批次。批准时重新核对费用快照与参考号，整批登记到账。任一费用变化会阻止入账，可驳回并上传更正后的文件。同类型与同内容的文件返回原批次。
5. 为已付款费用生成收据 PDF。需要更正到账记录时，运营提交冲正，财务批准后将费用恢复待结算，同时记录付款被冲正及原收据作废。作废 PDF 可按带有 VOID 的文件名下载，便于查阅历史凭证。
6. FPS 对账页独立转换演示 CSV 并显示异常。运营报表可按日期查看，财务可下载 FIS 演示文件，财务及审计可查询费用、规则、结算和凭证等操作的审计记录。

每份上传文件上限为 8 MB。演示模式可在模板接口附加 `example=true` 获取样例。

## 演示范围与配置

- 预置记录使用虚构公司和计划。计费规则由财务按计划登记，正式费率与历史计费结果需使用业务认可的样本核对。
- 文档编号为 `NOT/REM/RCP-年份-流水号`，可用 OMS_NOTICE_PREFIX、OMS_REMINDER_PREFIX 和 OMS_RECEIPT_PREFIX 调整前缀。FPS 请求编号前缀为 OMS_FPS_PREFIX，有效期由 OMS_FPS_EXPIRY_DAYS 设置（默认 30 天），二维码内容的基础地址由 OMS_FPS_QR_BASE_URL 设置（默认 `https://fps.example.test/pay`）。
- 二维码由后端 ZXing 按保存的演示载荷生成 PNG；原系统的二维码通常由 FPS 接口响应返回，接入真实接口时可改为渲染响应中的二维码内容。收据核验二维码的基础地址由 OMS_RECEIPT_QR_BASE_URL 设置（默认 `https://fps.example.test/receipt`）。
- PDF 使用随应用打包的 OMS Sans 字体（Noto Sans SC，SIL Open Font License），模板位于 backend/src/main/resources/reports。构建时由 ReportTemplateCompiler 预编译为 .jasper，避免在可执行 JAR 内编译报表表达式。
- 文档投递状态记录为“未投递”，邮件发送和退信字段已保留，但演示环境不调用真实邮件或邮寄服务。二维码只在新生成的 PDF 中渲染；升级前已生成的文档不会自动回填，重新生成通知/催缴后即可带二维码。
- FPS 请求采用演示编号，FIS 接口文件采用演示 CSV。报表 CSV 带 UTF-8 BOM，FIS CSV 为 UTF-8 无 BOM，便于财务核对编码。
- 本地文档默认写入 .local-data/documents。设置 OMS_STORAGE_TYPE=s3、OMS_S3_BUCKET、OMS_S3_REGION 和可选的 OMS_S3_ENDPOINT 可切换至兼容 S3 的存储。需事先创建存储桶，通过 AWS SDK 默认凭据链提供访问凭据。
- 真实上线前需取得原定期计费过程、结算及对账样例、FPS/FIS 接口规范和正式通知模板，并验证历史费用、数据权限和投递规则。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET / POST | /api/fees | 查询费用台账、新建费用 |
| PUT | /api/fees/{id} | 修改费用 |
| POST | /api/fees/periodic-run | 按生效规则运行定期计费 |
| GET / POST | /api/documents | 查询文档、生成缴费通知/催缴提醒/收据 |
| GET | /api/documents/{id}/download | 下载生成的 PDF |
| GET / POST | /api/fps-requests | 查询或生成 FPS 演示请求 |
| GET | /api/fps-requests/{id}/qr | 生成二维码 PNG，附加 `?download=true` 可下载 |
| GET | /api/settlements/template | 下载 APPLN 或 FEESU 结算模板 |
| GET / POST | /api/settlements/batches | 查询批次或上传并预检 |
| GET | /api/settlements/batches/{id}/file | 下载原始结算凭证 |
| POST | /api/settlements/batches/{id}/approve 或 reject | 复核结算批次 |
| GET / POST | /api/settlements/reversals | 查询或提交冲正 |
| POST | /api/settlements/reversals/{id}/approve 或 reject | 复核冲正 |
| GET / POST | /api/billing/schemes, /api/billing/rules | 查询或登记计划与费率 |
| POST | /api/billing/quote, /api/billing/issue | 预览或开立费用 |
| GET | /api/auth/session, /api/audit | 查询会话或审计记录 |
| GET | /api/reconciliation/template | 下载 FPS 对账演示 CSV |
| POST | /api/reconciliation/convert | 转换 FPS 对账文件 |
| GET | /api/reports, /api/reports/download | 查询或导出运营报表 |
| GET | /api/fis/export | 下载 FIS 财务接口文件 |

## 测试

运行后端测试，覆盖报表、结算预检、文档和 FPS 等业务。运行计费集成用例时，设置 `OMS_BILLING_TEST_DB_URL` 指向专用 PostgreSQL 测试库，并通过 `OMS_DB_PASSWORD` 提供测试库密码。数据库用户名默认为 `omsweb`，可用 `OMS_DB_USER` 设置。

    mvn -f backend/pom.xml test

## 目录

- frontend/ 包含工作台页面、报表页面和界面组件。
- backend/ 包含 HTTP 接口、业务服务与 Flyway 数据库迁移。
- backend/src/main/resources/reports 存放通知和收据的 JasperReports 模板，fonts 存放内嵌中文字体。
- 业务逻辑.md 记录原系统业务能力、页面编号和待核实规则。
- 面试项目介绍.md 提供项目讲解、演示脚本、常见追问和简历写法。
