# MCP Filesystem Demo (LangChain4j + OpenAI)

A minimal Java example that demonstrates the **Model Context Protocol (MCP)** end-to-end:
an LLM-backed Java application (the **MCP Host**) talks to a Node.js **MCP Server**
(`@modelcontextprotocol/server-filesystem`) through an **MCP Client**, so that an OpenAI
chat model can answer natural-language questions about files on your local disk — without
ever being given direct filesystem access itself.

## What is MCP?

The **Model Context Protocol (MCP)** is an open protocol that standardizes how LLM
applications connect to external tools, data sources, and systems. Instead of every app
writing bespoke integration code for every tool (one-off "file reader" code, "database
connector" code, etc.), MCP defines a single client/server contract:

- An **MCP Server** exposes a set of **tools** (and optionally resources/prompts) over a
  transport (stdio, HTTP/SSE, etc.), each tool described with a name, description, and a
  JSON Schema for its parameters.
- An **MCP Client** speaks that protocol to discover tools (`listTools`) and invoke them
  (`callTool`) on behalf of the application.
- The **LLM** never touches the filesystem/database/API directly — it only ever sees tool
  *descriptions* and decides, from the user's message, which tool to call and with what
  arguments. The actual execution happens in the MCP server; the result is fed back to the
  LLM as a tool result message.

This decoupling means the same filesystem MCP server could be reused by any MCP-compatible
host (Claude Desktop, this Java app, an IDE plugin, etc.) with zero changes.

## The four roles, and where they live in this repo

| Role | Responsibility | In this project |
|---|---|---|
| **Host application** | The end-user-facing app that owns the conversation, holds the LLM client, and wires everything together. | `Main.java` — the Java process you run. |
| **MCP Client** | Library code inside the host that speaks the MCP protocol: launches/connects to the server, lists its tools, and forwards tool-call requests/results. | `DefaultMcpClient` + `StdioMcpTransport` (from `langchain4j-mcp`), created in `Main.java`. |
| **MCP Server** | A separate process that actually owns a capability (here: reading a directory on disk) and exposes it as MCP tools. | `@modelcontextprotocol/server-filesystem`, a Node.js process launched via `npx` and talked to over **stdio**. |
| **LLM** | Reasons over the user's request and the tool catalog, decides *which* tool to call and with *what arguments*, and turns raw tool results back into a natural-language answer. | `gpt-4o-mini` via `OpenAiChatModel`, wrapped by a LangChain4j `AiServices` proxy (`FileAssistant`). |

Note that the LLM is **not** the MCP client — it has no network/stdio connection of its own.
The host's MCP client fetches the tool catalog and executes tool calls; the LLM only ever
sees JSON (tool specs in, tool results in) and text (the user's question, its own answer).

## How the pieces connect (code walkthrough)

1. **`Main.java`** starts an `OpenAiChatModel` (the LLM) using `OPENAI_API_KEY` from the
   environment.
2. It builds a `StdioMcpTransport` that spawns the filesystem MCP server as a child process:
   ```
   cmd /c npx -y @modelcontextprotocol/server-filesystem --read-only <allowed-directory>
   ```
   Communication happens over the child process's **stdin/stdout** (JSON-RPC messages), not
   a network socket — this is the "stdio" MCP transport.
3. A `DefaultMcpClient` is created on top of that transport — this is the actual **MCP
   client**. `mcpClient.listTools()` performs the MCP `tools/list` handshake and returns the
   server's tool catalog (`read_file`, `read_text_file`, `list_directory`, `search_files`,
   `get_file_info`, etc.), which is printed to the console for inspection.
4. An `McpToolProvider` wraps the client so LangChain4j's `AiServices` can expose those MCP
   tools to the LLM as if they were native `@Tool` methods.
5. `AiServices.builder(FileAssistant.class)` builds a dynamic proxy implementing the
   `FileAssistant` interface (`FileAssistant.java`), wiring together the chat model and the
   tool provider. Its `@SystemMessage` instructs the LLM to use filesystem tools when asked
   about local files, and to never invent file contents.
6. `assistant.chat("...")` sends the user's question to the LLM along with the tool catalog.
   The LLM decides which tool(s) to call (e.g. `list_directory` to find files, then
   `read_text_file` to read one), LangChain4j executes those calls **through the MCP
   client**, feeds the results back to the LLM, and the LLM produces the final natural
   language answer — all inside this one `chat()` call, over as many tool-call round trips
   as needed.

## Flow diagram

```
┌───────────────────────────────── Host application (this Java process) ─────────────────────────────────┐
│                                                                                                           │
│   User question                                                                                          │
│   "Are there any .txt files in the folder?                                                               │
│    If so, read it and summarize it."                                                                     │
│        │                                                                                                 │
│        ▼                                                                                                 │
│   ┌─────────────────────┐        1. listTools()          ┌───────────────────────────────┐              │
│   │   FileAssistant       │ ───────────────────────────▶ │        MCP Client             │              │
│   │  (AiServices proxy)   │        (tool catalog)         │  (DefaultMcpClient +          │              │
│   │                        │ ◀───────────────────────────  │   StdioMcpTransport)          │              │
│   └─────────┬──────────────┘                               └───────────────┬───────────────┘              │
│             │ 2. send user msg + tool specs                                │ stdio (JSON-RPC)              │
│             ▼                                                              ▼                               │
│   ┌─────────────────────┐                                   ┌───────────────────────────────┐             │
│   │        LLM            │   3. "call list_directory(...)" │   MCP Server (Node.js process) │             │
│   │   (OpenAI gpt-4o-mini)│ ───────────────────────────────▶│ @modelcontextprotocol/         │             │
│   │                        │                                 │ server-filesystem              │             │
│   │  decides WHICH tool    │   4. tool result (file list)    │                                 │             │
│   │  to call, with what    │ ◀───────────────────────────────│  reads the real filesystem,    │             │
│   │  arguments             │                                 │  restricted to the allowed     │             │
│   │                        │   5. "call read_text_file(...)" │  --read-only directory         │             │
│   │                        │ ───────────────────────────────▶│                                 │             │
│   │                        │   6. tool result (file content) │                                 │             │
│   │                        │ ◀───────────────────────────────│                                 │             │
│   └─────────┬──────────────┘                                 └───────────────────────────────┘             │
│             │ 7. final natural-language answer                                                              │
│             ▼                                                                                               │
│      Console output: "The folder contains a .txt file ... summary: ..."                                    │
│                                                                                                               │
└───────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Key point: steps 3–6 can repeat for as many tool calls as the LLM decides it needs
(e.g. list the directory, *then* read a specific file) before it produces the final
answer in step 7 — the LLM drives *which* tools are called and *in what order*, but never
executes them itself; the MCP client/server pair does the actual work.

## Prerequisites

- **Java 21** and **Maven**.
- **Node.js** with `npx` available on `PATH` (used to launch
  `@modelcontextprotocol/server-filesystem` on demand — no manual install needed).
- An **`OPENAI_API_KEY`** environment variable set to a valid OpenAI API key.
- A directory for the MCP server to serve, matching the path configured in `Main.java`:
  ```java
  "--read-only",
  "D:\\Coding\\Java_Practice\\TeluskoLiveCourse-AgenticAI\\mcp-demo-files"
  ```
  Update this path to point at a folder that exists on your machine, containing whatever
  files you want the assistant to be able to inspect.

## Running it

From an IDE, run `com.org.mcp.Main` directly. From the command line:

```bash
mvn compile exec:java
```

On startup you'll see:
1. The MCP server's tool catalog printed to the console (debug output from `Main.java`).
2. The LLM's final answer to the hard-coded question in `Main.java`
   (`assistant.chat("...")`), e.g. a list of matching files and/or a summary of one file's
   contents.

The filesystem server is started with `--read-only`, so this demo can only **read** files —
it cannot write, move, or delete anything in the configured directory.

## Project structure

```
src/main/java/com/org/mcp/
├── Main.java             # Host app: wires up LLM, MCP transport/client, and AiServices
└── FileAssistant.java     # LangChain4j AiServices interface (system prompt + chat method)
```
