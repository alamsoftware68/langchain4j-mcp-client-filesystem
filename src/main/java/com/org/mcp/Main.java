package com.org.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        System.out.println("MCP Filesystem Demo");
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        //Running MCP server local in the system and through standard input output
        // and will establishing the connection to MCP server
        //server-filesystem - this is the MCP server name and it is nodejs and
        // below are the command to run the server in local system
        //start MCP server using below
        //"cmd", "/c",
        //       "npx", "-y",
        //        "@modelcontextprotocol/server-filesystem",
        //And communicate MCP server using standard input output "StdioMcpTransport"
        McpTransport transport = StdioMcpTransport.builder()
                .command(List.of(
                        "cmd", "/c",
                        "npx", "-y",
                        "@modelcontextprotocol/server-filesystem",
                        "--read-only",
                        "D:\\Coding\\AgenticAI\\mcp-demo-files"
                ))
                .logEvents(true)
                .build();

        //Creating MCP-Client
        McpClient mcpClient = DefaultMcpClient.builder()
                .key("filesystem-server")   //our own mcp client server key, it can be anything
                .transport(transport)
                .build();

        List<ToolSpecification> tools = mcpClient.listTools();
        for(ToolSpecification tool : tools)
        {
            System.out.println("Tool: " + tool.name());
            System.out.println("Description: " + tool.description());
            System.out.println("Parameters: " + tool.parameters());
        }


        //Getting tools information available on MCP server through mcp-client
        McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(mcpClient)
                .build();

        //AiService
        FileAssistant assistant = AiServices.builder(FileAssistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .build();
        String answer=assistant.chat("Are there any .txt files in the folder? If so, read it and give me a summary of its contents.");
        System.out.println(answer);
    }
}