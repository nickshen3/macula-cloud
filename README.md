<h2 align="center">Macula Cloud</h2>

<p align="center">
	<strong>基于Macula Boot开发的通用(认证、权限等)技术应用平台</strong>
</p>

> 🚀 **从零部署？** 见 [docs/quickstart.md](docs/quickstart.md)（含 Windows 常见坑速查）。改进计划见 [IMPROVEMENT-PLAN.md](IMPROVEMENT-PLAN.md)。其他文档：[Redis HA](deploy/redis-ha.md) ｜ [授权码+PKCE 迁移方案](docs/oauth2-authcode-pkce-migration.md) ｜ [升级窗口手册](docs/upgrade-window-playbook.md) ｜ [System 拆分评估](docs/system-split-assessment.md)

<p align="center">
    <a href="https://github.com/macula-projects/macula-cloud/blob/main/LICENSE" target="_blank">
        <img src="https://img.shields.io/github/license/macula-projects/macula-cloud.svg" >
    </a>
    <a href="https://central.sonatype.com/search?q=macula&smo=true" target="_blank">
        <img src="https://img.shields.io/maven-central/v/dev.macula.boot/macula-boot-starters" />
    </a>
    <a>
        <img src="https://img.shields.io/badge/JDK-1.8+-green.svg" >
    </a>
    <a>
        <img src="https://img.shields.io/badge/SpringBoot-2.7+-green.svg" >
    </a>
    <a>
        <img src="https://img.shields.io/badge/SpringCloud-2021.x+-green.svg" >
    </a>
</p>

## 概述

基于Macula Boot的微服务应用开发平台，提供多租户、应用管理、权限、工作流、低代码、报表、批处理、数据订阅、资源中心、API管理、表结构管理和SQL审计等通用技术平台能力。

### Macula Cloud Gateway 网关中心

平台对外统一入口，提供统一认证、鉴权、接口加解密等服务

### Macula Cloud API 平台对外API SDK

提供各微服务给外部访问的接口定义

### Macula Cloud IAM 认证中心

提供基于OAUTH/CAS/OIDC/SAML协议的统一认证服务，所有服务经过网站认证

### Macula Cloud ID ID中心

统一的ID生成服务

### Macula Cloud System 管理中心

统一的租户、应用、用户、权限等管理

### Macula Cloud Seata 分布式事务管理

分布式事务管理

### Macula Cloud SnailJob 任务管理（唯一调度组件）

Snail Job 任务管理和重试管理（xxl-job 已于 P2-1 下线：模块内嵌 3000+ 行 admin 源码自维护成本高，且与 snail-job 功能重叠）

控制台 http://localhost:9086/snail-job（默认账号 admin/admin，服务端 17888 grpc）

### Macula Cloud RocketMQ MQ管理（TODO）

基于RocketMQ 和 RocketMQ Connect管理

### Macula Cloud Docs（TODO）

API接口文档和数据库结构文档服务

### Macula Cloud OSS 资源中心(TODO)

资源管理服务

### Macula Cloud ...

该模块下面是可选择的一些加快开发周期的低代码应用，比如低代码、报表、 工作流管理等应用

## 编译说明

通过pl指定需要编译的模块，api模块为必须要指定的模块，需要指定需要编译的profile.包括local、dev、stg、pet、prd等

```shell
mvn clean package -DskipTests=true -Pdev -pl macula-cloud-api,macula-cloud-api/macula-cloud-system-api,macula-cloud-system
```

## License

Macula Boot and Macula Cloud is Open Source software released under the Apache 2.0 license.
