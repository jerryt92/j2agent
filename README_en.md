[简体中文](README.md) | English

[![GitHub](https://img.shields.io/badge/GitHub-J2Agent-blue?logo=github)](https://github.com/jerryt92/j2agent)

J2Agent is an agent platform based on Java Spring Boot. Built on RAG (Retrieval-Augmented Generation), MCP tool integration, and the Spring AI Alibaba Agent runtime, it provides extensible multi-agent chat, knowledge retrieval, and pluggable business agents for the Java ecosystem. The platform supports mainstream LLM APIs such as Ollama and OpenAI, and integrates Milvus, MySQL, and Redis for vector search and conversational memory.

## Contributors

<a href="https://github.com/jerryt92/j2agent/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=jerryt92/j2agent" />
</a>

## One-Click Deployment with Docker

All Docker configurations are located in the `docker/` directory. By default, it starts Milvus (v2.6.9), MySQL, Redis, and J2Agent.

1. Pull all dependency images (optional)

```shell
docker pull maven:3.8.8-amazoncorretto-21-debian
docker pull eclipse-temurin:21-jre
docker pull alpine/git
docker pull milvusdb/milvus:v2.6.9
docker pull debian:bookworm-slim
```

2. Pull frontend

```shell
rm -rf j2agent-starter/src/main/resources/dist
git clone -b dist https://github.com/jerryt92/j2agent-ui.git j2agent-starter/src/main/resources/dist
```

Windows

```shell
Remove-Item -Recurse -Force j2agent-starter\src\main\resources\dist
```

```shell
git clone -b dist https://github.com/jerryt92/j2agent-ui.git j2agent-starter\src\main\resources\dist
```

3. Deploy

```shell
docker compose -f docker/docker-compose.yml up -d --build
```

Configurable options (`docker/.env`, see `docker/.env.example`):

- `J2AGENT_BASE_PATH`: Host configuration/data root directory (default `~/j2agent`)
- `COMPOSE_PROJECT_NAME`: Container prefix (default `j2agent`)
- `J2AGENT_PORT`: Service port (default `30111`)
- `TAG`: Image tag
- `I18N`: Locale (e.g. `zh_CN` / `en_US`)

Access:

- UI: `http://localhost:30111/` (port follows `J2AGENT_PORT`)
- Health check: `http://localhost:30111/v1/api/j2agent/health-check`

Host access within containers:

- macOS/Windows: `host.docker.internal`
- Linux: `host.docker.internal` (requires Docker 20.10+ and `extra_hosts: ["host.docker.internal:host-gateway"]`)

## Demo

![demo](assets/demo.gif)

## Architecture

### Platform Overview

```mermaid
graph TD
    subgraph presentation ["Presentation Layer"]
        dialog["Chat Dialog"]
        agentUi["AgentUi Event Consumer"]
        uiStateMachine["UI State Machine & Event Visualization"]
    end

    subgraph access ["Access Layer"]
        chatCtrl["ChatController (ChatApi + WS)"]
        login["Login Interceptors"]
        plugin["Agent Extensions (prodplugin-j2agent-agent)"]
    end

    subgraph runtime ["Agent Runtime"]
        chatSvc["ChatService"]
        router["AgentRouter"]
        aiAgent["AiAgent Base Class"]
        sm["AgentTurnStateMachine"]
        toolEmitter["ToolEventEmitter"]
        ws["WebSocket AgentUiEventEnvelope"]

        subgraph memory ["Conversation Memory"]
            chatMemory["ChatMemory"]
            redisRepo["RedissonCachingChatMemoryRepository"]
        end

        subgraph rag ["Knowledge Retrieval RAG"]
            knowSvc["KnowledgeService"]
            retriever["AbstractCollectionKbRetriever"]
            ragAdvisor["RetrievalAugmentationAdvisor"]
        end

        subgraph tools ["Tools & Skills"]
            mcp["McpService"]
            toolReg["Tool Registration"]
            toolInterceptor["AgentUiToolEventInterceptor"]
            skillReg["Skill Registration"]
        end
    end

    subgraph infrastructure ["Infrastructure"]
        mysql["MySQL"]
        redis["Redis"]
        milvus["Milvus"]
        springAiModel["Spring AI ChatModel"]
        llmApi["LLM Provider APIs"]
    end

    chatCtrl --> chatSvc
    chatSvc --> router
    plugin --> router
    router --> aiAgent
    aiAgent --> chatMemory
    aiAgent --> retriever
    aiAgent --> toolReg
    aiAgent --> skillReg
```

### Technology Stack (Spring AI)

```mermaid
graph TD
    subgraph assistant ["Assistant Agent"]
        aa1["Code-as-Action"]
        aa2["GraalVM Multi-Language Sandbox"]
        aa3["Multi-Dimensional Evaluation + Dynamic Prompt"]
        aa4["Experience Learning & Fast Path"]
        aa5["Knowledge Retrieval SPI"]
        aa6["Tool Ecosystem (MCP/HTTP)"]
        aa7["Proactive Services"]
        aa8["Multi-Channel SPI"]
    end

    subgraph saia ["Spring AI Alibaba"]
        saia1["Agent Framework"]
        saia2["Augmented LLM"]
        saia3["Context Engineering & Human-in-the-Loop"]
        saia4["Cloud & Model Integration"]
        saia5["Multi-Agent Orchestration"]
        saia6["Graph Runtime"]
        saia7["Hooks / Interceptors"]
        saia8["Skills"]
    end

    subgraph sai ["Spring AI"]
        sai1["Model Abstraction"]
        sai2["Vector Store & RAG"]
        sai3["ChatClient + Prompt Templates"]
        sai4["Advisor Chain"]
        sai5["Chat Memory"]
        sai6["Tools & Structured Output"]
        sai7["McpClient Protocol"]
    end

    subgraph jvm ["JVM"]
        java21["Java 21"]
    end

    subgraph deploy ["Deployment"]
        dockerNode["Docker"]
    end

    assistant --> saia
    saia --> sai
    sai --> jvm
    jvm --> deploy
```

### Code Boundaries

```mermaid
graph TD
    subgraph platform ["Platform Code"]
        pTools["Generic Tools"]
        pLoadedTools["All Loaded Tools"]
        pSkills["Generic Skills"]
        pLoadedSkills["All Loaded Skills"]
        pRag["Generic RAG"]
        pMemory["Default Memory Strategy"]

        pTools --> pLoadedTools
        pSkills --> pLoadedSkills
    end

    subgraph dev ["Agent Developer Code"]
        dTools["Business-Specific Tools"]
        dSkills["Business-Specific Skills"]
        dRag["Custom RAG"]
        dMemory["Custom Memory Strategy"]

        dTools --> pLoadedTools
        dSkills --> pLoadedSkills
        dRag --> pRag
        dMemory --> pMemory
    end
```

## Purpose

Many open-source agent / RAG platforms are implemented in Python. As a Java developer, J2Agent aims to provide Agent runtime, RAG, MCP, and pluggable business agents in one Java-native stack.

## Features

- **Multi-model support**: Compatible with Ollama and OpenAI-style interfaces.
- **Vector database integration**: Supports Milvus for various performance scenarios.
- **Agent runtime**: Spring AI Alibaba `ReactAgent` with `AiAgent` abstraction and `AgentRouter` multi-agent routing.
- **Function Calling**: Enables LLMs to call APIs from other systems.
- **MCP support**: Model Context Protocol for standardized tool invocation.
- MCP Client interacts with LLM via Function Calling instead of prompts to save tokens.
- **Skills progressive disclosure**: Load skill docs on demand via `read_skill` and `SkillRegistry`.
- **AgentUi event stream**: WebSocket `AgentUiEventEnvelope` for tool calls and state machine visualization.
- **Java ecosystem optimization**: Designed for Java developers integrating agent capabilities.
- **JDK 21**: Virtual threads for improved concurrency.
- **Knowledge management**: Knowledge base CRUD and hit testing.

## Interface

Dynamic frosted-glass UI with dark mode support.

![ui1](assets/ui/1.png)

![ui2](assets/ui/2.png)

![ui3](assets/ui/3.png)

## Knowledge Management

![ui4](assets/ui/4.png)

![ui5](assets/ui/5.png)

![ui6](assets/ui/6.png)

![ui7](assets/ui/7.png)

## To Be Improved

- **Rerank**: Reranking for retrieval results.
- Streamable HTTP transport for MCP (awaiting Spring AI release).
- **Knowledge base maintenance**: Create, import, export, and delete knowledge bases.

## Default Account Credentials

admin  
j2agent@2025

## Frontend

[j2agent-ui](https://github.com/jerryt92/j2agent-ui)
