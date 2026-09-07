---
title: "LangChain、LangGraph、LlamaIndex 有什么区别？"
source: "https://articles.zsxq.com/id_9655nvcw3e3j.html"
author:
published:
created: 2026-09-06
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 前言

最近这段时间，我们可能会经常听到：LangChain、LangGraph、LlamaIndex，但它们有什么区别呢？

这三个框架确实容易搞混——它们都跟大模型相关，都来自Python生态，而且名字还都挺像。

但如果你把它们的 **核心定位** 搞清楚，事情就简单多了。

今天这篇文章，就专门跟大家一起聊聊这个话题，希望对你会有所帮助。

## 一、一张图看懂三个框架的本质区别

在深入细节之前，先上一张全景图：

![image.png](https://article-images.zsxq.com/FukzFe74zrc861UQBonPfdkTKh1z)

这张图展示的是三个框架各自要解决的核心问题：

- **LangChain** 解决“怎么调用模型、怎么管理Prompt、怎么让模型用工具”
- **LlamaIndex** 解决“怎么加载文档、怎么建索引、怎么精准检索”
- **LangGraph** 解决“怎么管理状态、怎么处理循环、怎么恢复故障”

**三个框架各管一摊，谁也不是谁的替代品。**

下面我逐一拆解。

## 二、LangChain

它是AI应用的“乐高积木盒”。

### 2.1 它是什么？

LangChain是 **出现最早的基于LLM应用的框架** ，主打链式（Chain）结构，提供了模型调用、工具调用、智能体创建、中间件等基础能力。

它的核心定位是 **业界首个标准化大模型应用开发基础框架** 。

截至2026年，LangChain在GitHub上拥有 **95K+ Star** ，生态中集成了上百个LLM、向量数据库和工具。

**一句话说清：LangChain是一套“AI应用开发的标准零件库”——不管你做什么AI应用，大概率都能从里面找到现成的组件。**

### 2.2 LangChain的核心架构

![image.png](https://article-images.zsxq.com/Fm36GEb70U5g8kW_QVcOY_BnJtSB)

### 2.3 代码示例

一个最简单的LangChain应用——让模型回答问题：

```python
from langchain.chat_models import ChatOpenAI
from langchain.schema import HumanMessage

model = ChatOpenAI(model="gpt-4")
response = model.invoke([HumanMessage(content="什么是微服务架构？")])
print(response.content)
```

加上RAG检索：

```python
from langchain.document_loaders import TextLoader
from langchain.text_splitter import CharacterTextSplitter
from langchain.vectorstores import FAISS
from langchain.embeddings import OpenAIEmbeddings

loader = TextLoader("knowledge.txt")
documents = loader.load()
text_splitter = CharacterTextSplitter(chunk_size=1000)
docs = text_splitter.split_documents(documents)

embeddings = OpenAIEmbeddings()
vectorstore = FAISS.from_documents(docs, embeddings)

retriever = vectorstore.as_retriever()
docs = retriever.get_relevant_documents("什么是微服务？")
```

### 2.4 优缺点

**优点：**

- **生态最完善** ，集成了上百个LLM、向量数据库和工具
- **模块化设计** ，像搭乐高一样组合各种能力
- **降低入门门槛** ，让“搭一个Demo”变得极其简单

**缺点：**

- **学习曲线陡峭** ——概念太多，初学者容易迷失
- **性能开销** ——封装层多，相比原生调用有额外延迟
- **版本迭代快** ，早期版本不稳定导致一些开发者有阴影

### 2.5 适用场景

| 场景 | 推荐程度 | 理由 |
| --- | --- | --- |
| **快速原型验证** | ✅✅✅ | 模块化设计，几天就能跑通一个Demo |
| **多模型切换/A/B测试** | ✅✅✅ | 统一接口，换模型改一行配置 |
| **需要丰富集成的项目** | ✅✅✅ | 生态最全，工具最多 |
| **生产级高性能场景** | ⚠️ 需评估 | 封装层有性能开销 |

## 三、LangGraph

它是Agent的“生产流水线”。

### 3.1 它是什么？

LangGraph是由LangChain团队开发的 **有状态循环图编排运行时** ，专为复杂LLM Agent应用而生。

它解决了一个LangChain没解决的问题： **当Agent需要循环推理、条件分支、多步工具调用时，怎么让流程既可控又可观测？**

**一句话说清：LangChain是“零件”，LangGraph是“把这些零件组装成一条可调试、可恢复的生产流水线”。**

截至2026年，LangGraph已成为LangChain生态中 **构建有状态Agent的推荐方式** ，在生产环境中被广泛采用。

### 3.2 LangGraph的工作流程

![image.png](https://article-images.zsxq.com/Fi3_JAIioZad02PcnnEHxN9uL6fl)

**关键设计：检查点（Checkpointer）机制**

![image.png](https://article-images.zsxq.com/FhO1XbhckqcQujkaquydGGhhQWEm)

LangGraph的检查点机制会自动保存每一步执行后的状态。

如果Agent在执行到第5步时崩溃了，重启后可以从第5步继续，而不是从头开始。

这让Agent能够运行数小时甚至数天而不丢失进度。

### 3.3 代码示例

```python
from langgraph.graph import StateGraph, END
from typing import TypedDict, List

class AgentState(TypedDict):
    messages: List[dict]
    tool_results: List[str]
    done: bool

def llm_node(state: AgentState) -> AgentState:
    # 调用LLM，决定是否需要工具
    return state

def tool_node(state: AgentState) -> AgentState:
    # 执行工具调用
    return state

def should_continue(state: AgentState) -> str:
    if state["done"]:
        return "end"
    return "tool"

graph = StateGraph(AgentState)
graph.add_node("llm", llm_node)
graph.add_node("tool", tool_node)
graph.set_entry_point("llm")
graph.add_conditional_edges("llm", should_continue, {
    "tool": "tool",
    "end": END
})
graph.add_edge("tool", "llm")  # 循环！

app = graph.compile()
result = app.invoke({"messages": [{"role": "user", "content": "查一下北京天气"}]})
```

### 3.4 优缺点

**优点：**

- **状态驱动** ：所有决策基于明确定义的状态，可追溯、可审计
- **检查点+中断模型** ：让生产级的长运行Agent变得可行
- **混合确定性+Agent步骤** ：手写逻辑和LLM决策可以在同一张图中混合

**缺点：**

- **学习曲线陡峭**
- **图模型有局限** ：不是所有流程都适合用图表示

### 3.5 适用场景

| 场景 | 推荐程度 | 理由 |
| --- | --- | --- |
| **复杂Agent工作流（10+步骤）** | ✅✅✅ | 图结构天然支持复杂流程 |
| **需要审计追踪的金融/医疗场景** | ✅✅✅ | 状态可追溯、可审计 |
| **需要错误恢复和重试的系统** | ✅✅✅ | 检查点机制 |

## 四、LlamaIndex

它是RAG的“数据仓库管理员”。

### 4.1 它是什么？

LlamaIndex（原名GPT Index）是一个 **专门为RAG（检索增强生成）设计的数据框架** 。

它的核心使命是： **把非结构化数据与LLM无缝连接** 。

截至2026年，LlamaIndex在GitHub上拥有 **44K+ Star** ，通过LlamaHub提供了 **300+个数据连接器** ，覆盖Notion、Google Drive、Slack、PDF、数据库等数据源。

**一句话说清：如果LangChain是“工具箱”，LlamaIndex就是“专门管数据怎么存、怎么查的工具箱”。**

### 4.2 LlamaIndex的RAG全链路

![image.png](https://article-images.zsxq.com/Fv4tX4VU2rsboCzrcaeTXEegS9s8)

### 4.3 代码示例

```python
from llama_index.core import SimpleDirectoryReader, VectorStoreIndex
from llama_parse import LlamaParse

# 简单版本
documents = SimpleDirectoryReader("./data").load_data()
index = VectorStoreIndex.from_documents(documents)
query_engine = index.as_query_engine()
response = query_engine.query("公司的请假流程是什么？")

# 复杂PDF使用LlamaParse
parser = LlamaParse(result_type="markdown")
documents = parser.load_data("./complex_report.pdf")
index = VectorStoreIndex.from_documents(documents)
```

### 4.4 优缺点

**优点：**

- **RAG领域的天花板** ，数据摄取和检索能力极强
- **开箱即用** ，几行代码就能跑通一个企业知识库
- 支持 **混合搜索、重排序** 等高级功能

**缺点：**

- **定位聚焦** ，主要解决RAG问题，通用Agent编排能力不如LangChain

### 4.5 适用场景

| 场景 | 推荐程度 | 理由 |
| --- | --- | --- |
| **企业内部知识库** | ✅✅✅ | 90%的企业需求，LlamaIndex是王者 |
| **几百份财务报告PDF解析** | ✅✅✅ | 自动分块+多种索引 |
| **需要混合检索/重排序** | ✅✅✅ | 开箱即用 |

## 五、三个框架组合使用的最佳实践

三个框架最优雅的方案从来不是“三选一”，而是 **组合使用** ：

![image.png](https://article-images.zsxq.com/FuoDJoRBHbOug1dH_xjlUFxNWA89)

**组合方案的优势：**

- **LlamaIndex做数据层** ：从各种数据源摄取文档、建立多类型索引
- **LangGraph做编排层** ：定义Agent的状态图、处理多轮推理和工具调用
- **LangChain做基础层** ：提供模型调用、工具定义、Prompt管理

## 六、详细对比总表

| 对比维度 | LangChain | LangGraph | LlamaIndex |
| --- | --- | --- | --- |
| **核心定位** | 通用LLM应用框架 | 有状态Agent编排运行时 | RAG数据框架 |
| **核心抽象** | Chain / Agent | StateGraph（状态图） | Index / Query Engine |
| **GitHub Stars** | 95K+ | 15K+ | 44K+ |
| **生态规模** | 100+集成 | LangChain生态内 | 300+数据连接器 |
| **主要优势** | 生态最全、入门快 | 状态管理、可观测性 | RAG能力最强 |
| **主要劣势** | 性能开销、学习曲线 | 学习曲线陡 | 定位聚焦 |
| **最适合** | 快速原型、多模型集成 | 生产级复杂Agent | 企业知识库、RAG |
| **开源协议** | MIT | MIT | MIT |

## 七、写在最后

回到最初的问题： **LangChain、LangGraph、LlamaIndex到底有什么区别？**

**LangChain是“零件箱”** ——提供所有基础零件，让你能快速组装AI应用。它的优势是生态全、入门快。

**LangGraph是“流水线图纸”** ——把零件组装成一条可调试、可恢复的生产流水线。它的优势是状态管理、可观测性、支持长运行Agent。

**LlamaIndex是“数据仓库管理员”** ——专门管数据怎么存、怎么查。它的优势是RAG能力最强、数据连接器最多。

这三个框架在2026年已经形成了非常清晰的分工。

**LangChain提供零件，LangGraph负责组装成流水线，LlamaIndex专门解决数据问题** 。

它们不是“三选一”的竞争关系，而是可以组合使用的互补关系。

开源地址：

![](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAYAAABccqhmAAAQAElEQVR4AeydgZLjuK5D59z//+f7mtM3bywSjhlbTuIEW62JCYMgBW2xKqqe3f/81//YATvwtQ7854//sQN24Gsd8AD42qP3xu3Anz8eAP63wA58qQOxbQ+AcMHLDnypAx4AX3rw3rYdCAc8AMIFLzvwpQ54AHzpwXvb3+3AbfceADcn/GkHvtABD4AvPHRv2Q7cHGgPAOAPvH7dGp/xCXU/ShdGXocDYw78xkdy4VcDHvtUNTsY1DoqD3q8nAs1D3pY1npGDGNvqiaMHHhNrHpTWHsAqGRjdsAOXM+BZcceAEs3/GwHvswBD4AvO3Bv1w4sHfAAWLrhZzvwZQ4cGgD//e9//5y5zj4L1fvZNWfqH+kfti+nlD7UPLUnGHmKo/S7GIz6UGNVEyoPepjS62DdPe3ldXq4cfLnoQGQxRzbATtwLQc8AK51Xu7WDkx1wANgqp0WswPXcsAD4Frn5W7twG4HVOL0AQC9CxUYeaq5vRiM2qDjrn6+nIGqp7RyXsTQy1V6GYOqFTXyynndGPbr5x6gas3uY2/NnBdxt7e9PKh+wDa2t95a3vQBsFbIuB2wA+/ngAfA+52JO7IDT3PAA+BpVruQHXidA2uVP3IAxHe4zoLt71xQOR3t4CjTA5+1lD7UfhUvY92eoKcPI0/pw8gBHXdz855mx7mP2fqv0PvIAfAKI13TDlzRAQ+AK56ae7YDkxzwAJhkpGXswLs6cK8vD4B77vidHfhwBz5iAIC+PIL7ePds8+UP3NeF4++7vXV4UPvJeVA5ULGcF3H2R8XB27ug9qFqwMhTnG4PR3K7Nd6B9xED4B2MdA924IoOeABc8dTcsx1oOrBF8wDYcsjv7cAHO+AB8MGH663ZgS0Hpg8AdXnSwbYavfc+69/jbr3LWhHnnMBmrqwfMYwXWlDj4HXW3l472sGB7d6gclRfobd3ZT2oNZU29Hgqdy+We+3Ge+ut5U0fAGuFjNsBO/BcBzrVPAA6LpljBz7UAQ+ADz1Yb8sOdBzwAOi4ZI4d+FAHDg0AqJcnMA/reg5jTXWhorQUD0YtoKQC5X+UWkg/APR4P9Tyk3srhB8gcyL+gVs/MPbWSvohRY28fuBTf3K9iGHsH2j1ELl5tRJ/SMBw7j9Q6wfGPJgbqya62KEB0C1inh2wA+/pgAfAe56Lu7IDT3HAA+ApNruIHXhPBzwA3vNc3JUd2O3AI4ntAZAvTl4VdzYH9ZKlkxcctS8Y9YLXWUpL5SkebNeEkQMoeYnlmpIkQGC4CAMEqwcBLS3Yx8t7jLjX2X5W1HiH1d1BewB0Bc2zA3bgOg54AFznrNypHZjugAfAdEstaAde58CjlT0AHnXMfDvwQQ5MHwBQL2xgxLr+wZgHOs566hImcyIGrQcjHtzl6uovcx59VjUy1tWEcT/Qi7v6M3l5j0di1RfUvSuewnIvULWgYkoLejyVOxObPgBmNmctO2AHznXAA+Bcf61uB57mwJ5CHgB7XHOOHfgQB9oDAOp3FqhY9iV/b4oYah5ULLidlWvCPK2sHTFUfahYcPOCyoOKdfKUNzkv4i4vuK9esO3FWo9Qc2HEVO47+wPb/as9dbH2AOgKmmcH7MB1HPAAuM5ZuVM7sOrA3hceAHudc54d+AAHPAA+4BC9BTuw14H2AOhelGSeaixzIlY8GC9AQMcqt4NB1evkRb+dpbRUnuLNxGB7n92+urxO/0e0YN+eujWh6sOIKS2FwZgH/FE85VnmKc4RrD0AjhRxrh2wA+c5cETZA+CIe861Axd3wAPg4gfo9u3AEQc8AI6451w7cHEHpg8AqBceMGLKs3zZsRar3A4GYw/Qv4jZqw+1JlRM6cPIU36oPIV1cmGsB8f8gVFP9QUjB1C08p8Ng15vgMyFEZdFBZh9FJQ2BGMPgMwFhj1kUsQwcoCAW2v6AGhVNckO2IG3cMAD4C2OwU3Ygdc44AHwGt9d1Q68hQMeAG9xDG7CDjzuwIyMQwMgX4pEnJsKLC9guNgActrfGCi8rBXxX/LGH8HLC6q+ksl5HU7kzORB7RUqpmpC5UV/W0tpdbEt7XivtALvrE5uhxO1FE9hMPqoOF0s6ubVzc28rBNx5qzFhwbAmqhxO2AHruGAB8A1zsld2oFTHPAAOMVWi9qBcx2Ype4BMMtJ69iBCzrQHgAwXoCAjvd6AFUvLjPygm2e6gFqnuLlehFDzYURU1pdLGrk1c3NvKwTceZEDNv9w8gBIrWsqJEXMFzglqQTABhr5p4ihpEDOg7u1jphC0Uy91AIPwDUPfzArZ/2AGipmWQH7MClHPAAuNRxuVk78OfPTA88AGa6aS07cDEHDg2A/P1ExcoPxVMY1O82ipdrdDiRo3iwXTNyZy6oNWHEVD3Vv+J1MBjrQe9v3IU2bOcGL69u/1D1s1Y3VjUV1tXLPKi9Kn2oPNiHKf3c11p8aACsiRq3A3bgGg54AFzjnNylHfjrwOw/PABmO2o9O3AhBzwALnRYbtUOzHagPQDURQPUS4tOg1DzoGLdmjDmqh66Wh2e0oexB+hfoim9jKm+MidimNcHVC2oWNTNCyoPtrGsE7HaO1StzIvczoKqBdtYR/sop7MnqL1267YHQFfQPDtgB85x4AxVD4AzXLWmHbiIAx4AFzkot2kHznDAA+AMV61pBy7iQHsAQO+iASoPRixfbESs/IIxD86/WINaM/cW/eaVOWsxbOurXKh5ULHcV8TQ4wV3uVQfy/f3nnPuPe7yHdRes9ZaDGPuGm8WDmM9QEoDw9+MBCRPgcDfXPj9XHp171lpKaw9AFSyMTtgB67tgAfAtc/P3duBQw54AByyz8l24NoOeABc+/zc/Rc4cOYW2wPg3oXD8l1udvnu9pw5a/GNv/yE38sQ+Pe5fB/PSg/+8WH9OfLzUnodDGqdrN2NVT2VC7Wmyu1gSl/lwbk1oeqr3jLW7TXnrcVZb43XwbNWxJ08qF5AxUKvs9oDoCNmjh2wA9dywAPgWuflbu3AVAc8AKbaaTE7MNeBs9U8AM522Pp24I0dODQAYPvyAbY54Y+6AIFebuQvF9Q8pa+wpc7aM1R9xe3qQ9WDEVNaMHKg/5uSUHNhH6Z6y35A1c6c2TH0akLlQcXyPqFyunvIWhHDfr1u3cw7NACymGM7YAeu5YAHwLXOy91+kQPP2KoHwDNcdg078KYOeAC86cG4LTvwDAcODYC4uNha3U1AvQBR2h29bh7UmlCxs2sq/bwHqH1lTsTQ46maGQu9zoJaM2upGGoeVGxvrspTWGePwYGxN6WlMBjzAEXbjUVveXXFDg2AbhHz7IAdeMyBZ7E9AJ7ltOvYgTd0wAPgDQ/FLdmBZznQHgDA8J8mAnb3CLS0YD8Pai6M2O4NHEjM39XW4k4JGPcDyDSg5XdOhpoHFct5R+I1Pzp4rtvJCQ7UPUHFgru1cg8Rq5zA9yylBbXXrnZ7AHQFzbMDduCYA8/M9gB4ptuuZQfezAEPgDc7ELdjB57pgAfAM912LTvwZg5MHwAwXkioS4uuBypXYR29vXlKu6sFoxeAkisXdKB5MjmB3d5SmgyVVhfLgiovcyIGih+Bd1au0ckJTs5bi4O7XFB7hYotc7ae97xX/XZ1pg+AbmHz7IAdeL0DHgCvPwN3YAde5oAHwMusd2E78HoHPABefwbuwA78deAVf5w+AGD/pQjUXKhYNu7IpUjW6saw3VdowT6e2pPCoKcfvWwtmKelaqn+FQa1D9jGVM0uBvP0YVsL6LY2lXf6AJjarcXsgB2Y6oAHwFQ7LWYHruWAB8C1zsvdfqgDr9qWB8CrnHddO/AGDkwfAOoSJ2Nq35nzSJz1gN2/TZa1VAxVX/WrcvfylFYXUzU7mNKHuneoWM6FbU7OuRd3+odeTag8pZ/7UZwulrUiVrkw9ha8mWv6AJjZnLXsgB041wEPgHP9tbod2HTglQQPgFe679p24MUOeAC8+ABc3g680oH2AOhcUMB4YQE67m4Yan43N/Ogaqk9KSxrqRiq/mwejDWUfheDeVqdmnt9DW2VC2P/UOPIzQsqT+nnvG4MVf/sXNhfsz0Aupswzw7Ygb4Dr2Z6ALz6BFzfDrzQAQ+AF5rv0nbg1Q60BwDs+55x5PvV3lyVpzCoe4KK5dzuoeW8iLu5Z/Oil+WaXW+pHc9KH6rX0MNCc8/q9qF4HUz11Mlb42S9Nd5evD0A9hZwnh2wA9qBd0A9AN7hFNyDHXiRAx4ALzLeZe3AOzjgAfAOp+Ae7MCLHGgPgHwZ0Y27+4Le5Q9UXqcG9PLUvjr6Kg9qTcVTWK6pOFD1c17EUHmwjUVuXqqPzFEx1HqKt1c/tGCsEVheXX0YtYAsVf7GKdDGitgBoLsnVaI9AFSyMTtgB67tgAfAtc/P3duBQw54AByyz8l24NoOeABc+/zc/QUdeKeWDw0A2L70OLLZ7uVG5h2pqXJh3GeHA/zJfUUMoxag5EquJB0Ao5flUlLL97dnxVMYMFyIKY7CYMwD7aPK7WBQ9VXebb/LT8XL2JJ/e86ciG/vtj6Du1xQ+4eKLXPuPR8aAPeE/c4O2IH3d8AD4P3PyB3agdMc8AA4zVoL24HqwLshHgDvdiLuxw480YH2AIB60bB1gRHv1V4Cz0vxoNbs8mDMVXm5h4i7vOAul8rrYjD2CpRUYLhUAwpnDVj2eXte427hQOnjpnnvU+kqvuJBral4Wa/DyTm3WOVm7MZdfkKv16wVMdRcGLFlrXvPoddZ7QHQETPHDtiBazngAXCt83K3F3bgHVv3AHjHU3FPduBJDngAPMlol7ED7+jAoQEA4wUFUPYIlEujQloB7l1y3Hu3Ildg2Ncb1DzVTym4AqhcGGuspBZYaRXSDwDz9GHUghqrvqDH+2m3/EDNhW2sCK0AULXyHqByVuR2w7mmEoL9fRwaAKoZY3bADlQH3hXxAHjXk3FfduAJDngAPMFkl7AD7+pAewDk7yIRz9xU6OUF9bsNVCz3kXUeibNWN4baF1RM6UHldXpWWgqDqp95qh5s54WOys0YVK3MiRh6vKg7a0GtOUs7dGJfeQWeV+ZEDLU3GLGs80jcHgCPiJprB+zAPwfe+ckD4J1Px73ZgZMd8AA42WDL24F3dsAD4J1Px73ZgZMdmD4AYPuCAkYOILcZlyCdBZRfNoJtTBWF7bxOT8FR+goLbl6w3YfSgpqXtVWstBQGVb/DO1JT6Sss11AcqP3nvIg7uYqTsUdi6PUW/W2tbt3pA6Bb2Dw7YAde74AHwOvPwB3YgZc54AHwMutd2A683gEPgNefgTv4UAeusK3pA2DrciLeK2OgXoBADwvN5VL6CoOqv9S5PavcjEHVypy1GGrurfaMT1UXxpqKo2orHoxagKI9HVP9Kwwol8iK18G6m4RezawHNQ8qlvPW4ukDYK2QcTtgB97PAQ+A9zsTd2QHnuaAB8DTrHahb3LgKnv1ALjKmQ+LqgAACElJREFUSblPO3CCA+0BAPWiQV2K5B6h5mXOWtzRV7l785RWYFkP6p4y52gcdfcsqL11dGBfXmirvQa+XLBff6lze1Y1odaAbUxp3eosP2HUWr67PSstGPOg/z88hTFX6Svs1s/WZ3sAbAn5vR2wA9dzwAPgemfmjt/cgSu15wFwpdNyr3ZgsgMeAJMNtZwduJID7QHQvWiA7UsLZZDSh1EL9OUJVB6M2JGaOVf1mjkRw9gDEHBZQPlNNBixknQQUHvImCqRORHD2CvocwruckEvDyoPKrbUXntWe1IYbOurvCMY1JpH9Dq57QHQETPHDny7A1fbvwfA1U7M/dqBiQ54AEw001J24GoOeABc7cTcrx2Y6MChAQD10iJfvhzpNWtFrPQC31oqD7b7D92cCzUvcyKO3Lyglxv5WwuerwW1Zt5jxDDy1F6ClxeMeaAvFJVexqCnBZWXtSKGkRfYcsUzjBzY33/o5QVVHyqW89biQwNgTdS4HbAD13DAA+Aa5+Qu7cApDngAnGKrRe3ANRxoDwCo3zPy97eIZ24bak3YxlQP0Vteigfb+lkn4q6W4kV+Xoq3F4PtPe3VXsvbu5+cFzHU/lVdGHkdDqBoLQz4/1/ggt/n6DcvJQa/fPj3qXgdLNeLuJMXnPYACLKXHbADn+WAB8Bnnad3YwcecsAD4CG7TLYDn+WAB8Bnnad38wIHrlyyPQDiYiEv+HeBAb/P2Qz4xeHfZ9aJOOdFHPieFbl5wb/68PustHPe7FjVhN9+4N9nrgv/3sHvc+Y8Eqs+Mga/deDfp6oB/96DflZ5CoOan/s6EquaClM1Mk9xoPYPFctaESu9jAUvL+jp57yI2wMgyF52wA58lgMeAJ91nt6NHXjIAQ+Ah+wy2Q6MDlw98gC4+gm6fztwwIHTB0C+xIhY9Qv1IgPmYVE3L9VH5qhY5UHttZur9HJuh5Nz7sVZD+b239GHWjPnRQw9XnCXC/blhQbU3OwnbHNyzr0Yqh6MWPSWl9LMnLX49AGwVti4HbADr3fAA+D1Z+AOLurAJ7TtAfAJp+g92IGdDngA7DTOaXbgExyYPgBgvLSAGivj1EVGF8t6Ki9zIobaG2xjkTtzqX5h7KPDgTEH+rHaD9R81YfKzZjKU1jOeySGsV+lrzBVo8PrcJR2YDD2CgRcVq5RCD8AUP5a8g/c+pk+AFpVTbIDF3fgU9r3APiUk/Q+7MAOBzwAdpjmFDvwKQ54AHzKSXofdmCHA+0BAPsuGvIlRsTdPqHWhIplPdjmRE70klfgWwuqftaJWOlAzVW8jMG+vKzzrDj2v1yqLszd07JePKuaRzD47ReOf3b7gLFWN6/Law+ArqB5dsAOXMcBD4DrnJU7tQPTHfAAmG6pBe3AdRxoD4D4TrVnHbGiWy/XUHmZEzGM36+g9/9xU/pQtaLGmUv1oeopXgdTWl0MRj+6eZ2+ggOjPtRY1YTKC728oPJCb7lyziPxUueVz+0B8MomXdsO2IFzHPAAOMdXq9qBSzjgAXCJY3KTduAcBzwAzvHVqh/owCduqT0AoF6KwPOxziFA7auT1+VA1VcXQEqvy1O5MzEY99DVhjEPkKl5n5J0AMz6EWc5oPW35KDyQi+vrK9iqFqK18X29NDVDl57AATZyw7Ygc9ywAPgs87Tu7EDDzngAfCQXSZ/qwOfum8PgE89We/LDjQcODQA8gXF7LjR/19KrvsXTH9AvZzJeRHDNi9Jr4ZQtaCHrYpOehF7Xa4jskud23NH78ZdfnbyggPVx6VOPAevs4KbVydPcbJOxIq3Fwu9vPZqRd6hARACXnbADlzXAQ+A656dO3+SA59cxgPgk0/Xe7MDGw54AGwY5Nd24JMdmD4AoF7OwDY20+R8SbIWq5qKC2P/HQ6g5P+oXElM4N68kAHKb8TBNha5nQVV68y80FZ+wNiH4igMxjwgSmwuYJevwKb2IwS1p27+9AHQLWyeHbiCA5/eowfAp5+w92cH7jjgAXDHHL+yA5/ugAfAp5+w92cH7jjwEQMAGC5j7ux3eAVjHug4X7JA5WXOWgz7cofG7wSq7h36w6+UvsI6wnvzOtprHOj5H/l7ltqTwpS24kHtF7Yxpa+wjxgAamPG7IAd2HbAA2DbIzPswMc64AHwsUfrjdmBbQc8ALY9MuMLHfiWLXsAnHjSUC9rVDnY5kHlQMWUvrpc6mBKS2FQ+4ARU3kKgzEP+nHek9I/gmV9FUPt90jNnKtqKiznrcUeAGvOGLcDX+CAB8AXHLK3aAfWHPAAWHPG+Nc68E0bnz4A1PeRDnbE9KwP+7+HZa2IYdQLLC8YOYDcUs5bi4FTf7kpNwdjPUD+zUWovKwVcd5XYHlBTyvndWOo+rmviJUe1FzYxkIvL6WvMKj6ijcTmz4AZjZnLTtgB851wAPgXH+tbgfe2gEPgLc+Hjf3bAe+rZ4HwLeduPdrBxYOHBoAUC8tYB626POhx3wJE7ESCDwvqP1njtLqYlD1oWJdvczLvUacORHDWDOwzgq9vDp5XU7WjribC9t7gpEDOlY1o5flUpwuttS5PXdyQfcLI97RCs6hARACXnbADlzXAQ+A656dO5/swDfKeQB846l7z3bgfw54APzPCH/YgW90oD0AbhcVr/48+5DU/jo1Vd4rMNXr3j6UlsKUvuJlrJuneK/A9vaf89bimXtaq5Hx9gDIiY7twCc58K178QD41pP3vu3AjwMeAD8m+McOfKsDHgDfevLetx34ccAD4McE/3y3A9+8ew+Abz597/3rHfAA+Pp/BWzANzvgAfDNp++9f70DHgBf/6/Adxvw7bv/PwAAAP//laFhEwAAAAZJREFUAwDk9sU7WbB4TAAAAABJRU5ErkJggg==)

扫码加入星球

查看更多优质内容

[https://wx.zsxq.com/mweb/views/joingroup/join\_group.html?group\_id=28851182188851](https://wx.zsxq.com/mweb/views/joingroup/join_group.html?group_id=28851182188851)