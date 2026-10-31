package org.starfall.generative


import org.starfall.generative.integrations.modelapis.account.*

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.robolectric.annotation.Config
import org.starfall.generative.shared.auth.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.starfall.generative.integrations.modelapis.openaicodex.*
import org.starfall.generative.shared.model.*
import org.starfall.generative.features.mcp.shared.ui.*
import org.starfall.generative.integrations.mcp.*
import org.starfall.generative.features.machines.shared.ui.*
import org.starfall.generative.integrations.remote.*
import org.starfall.generative.features.home.chat.*
import org.starfall.generative.integrations.modelapis.*
import org.starfall.generative.shared.files.*
import org.starfall.generative.shared.icons.*
import org.starfall.generative.shared.media.*
import org.starfall.generative.shared.networking.*
import org.starfall.generative.shared.storage.preferences.*
import java.nio.file.Files
import org.starfall.generative.shared.model.auth.AuthMethod
import org.starfall.generative.shared.model.auth.Authorization
import org.starfall.generative.shared.model.generation.GenerationEvent
import org.starfall.generative.shared.model.api.ModelApiType
import org.starfall.generative.shared.model.language.ModelConfiguration
import org.starfall.generative.shared.model.provider.ModelProvider
import org.starfall.generative.shared.model.provider.ProviderConfiguration
import org.starfall.generative.features.home.chat.logic.tool.ToolChatViewModel
import org.starfall.generative.shared.model.auth.defaultAuthorization

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class McpToolLoopTest {
    @Suppress("DEPRECATION") // Robolectric 4.17 only exposes MIME registration through this legacy shadow.
    @Before fun prepareKeystore() {
        installTestAndroidKeyStore()
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowMimeTypeMap>(android.webkit.MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("png", "image/png")
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test(timeout = 60000) fun allProviderAdaptersExecuteMcpAndReturnToolResultToModel() = runBlocking {
        for(type in ModelApiType.entries) {
            val server=MockWebServer();server.start()
            val root=Files.createTempDirectory("mcp-loop").toFile()
            val image = java.io.File(root, "sample.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            var rounds=0;var calls=0
            server.dispatcher=object:Dispatcher(){
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.method=="DELETE") return MockResponse().setResponseCode(204)
                if(request.method=="GET" && request.path=="/mcp") return MockResponse().setResponseCode(405)
                    if (request.path?.endsWith("/models") == true)
                        return MockResponse().addHeader("Content-Type", "application/json")
                            .setBody("""{"data":[{"id":"chat","supported_endpoints":["/chat/completions"],"capabilities":{"limits":{"max_output_tokens":1000,"max_prompt_tokens":10000},"supports":{"tool_calls":true}}}]}""")
                    var body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    fun json(value:JsonElement)=MockResponse().addHeader("Content-Type","application/json").setBody(value.toString())
                    if(request.path=="/mcp") {
                        val method=body["method"]!!.jsonPrimitive.content
                        if(method=="notifications/initialized")return MockResponse().setResponseCode(202)
                        val result=when(method){
                            "initialize"->buildJsonObject{put("protocolVersion","2025-06-18");put("capabilities",buildJsonObject{put("tools",buildJsonObject{})});put("serverInfo",buildJsonObject{put("name","test");put("version","1")})}
                            "tools/list"->buildJsonObject{put("tools",buildJsonArray{add(buildJsonObject{put("name","echo");put("inputSchema",buildJsonObject{put("type","object")})})})}
                            "tools/call"->{calls++;buildJsonObject{put("content",buildJsonArray{add(buildJsonObject{put("type","text");put("text","hello_from_mcp")})})}}
                            else->error(method)
                        }
                        return json(buildJsonObject{put("jsonrpc","2.0");put("id",body["id"]!!);put("result",result)})
                    }
                    if (type == ModelApiType.ANTIGRAVITY) body = body.getValue("request").jsonObject
                    assertTrue(type.name + " attachment", body.toString().contains("AQID"))
                    rounds++
                    if(rounds==2)assertTrue(type.name,body.toString().contains("hello_from_mcp"))
                    val first=rounds==1
                    val tools=body["tools"]!!.jsonArray
                    val name=when(type){
                        ModelApiType.ANTHROPIC, ModelApiType.CLAUDE_CODE -> tools[0].jsonObject["name"]!!.jsonPrimitive.content
                        ModelApiType.OPENAI_RESPONSES, ModelApiType.OPENAI_CODEX -> tools[0].jsonObject["name"]?.jsonPrimitive?.content ?: tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                        ModelApiType.GOOGLE, ModelApiType.ANTIGRAVITY->tools[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
                        else->tools[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                    }
                    val call=buildJsonObject{put("id","c1");put("type","function");put("function",buildJsonObject{put("name",name);if(type==ModelApiType.OLLAMA)put("arguments",buildJsonObject{}) else put("arguments","{}")})}
                    val message=buildJsonObject{put("role","assistant");put("content",if(first) "" else "done");if(first)put("tool_calls",buildJsonArray{add(call)})}
                    val response = when(type){
                        ModelApiType.OPENAI, ModelApiType.GITHUB_COPILOT->buildJsonObject{put("choices",buildJsonArray{add(buildJsonObject{put("message",message)})})}
                        ModelApiType.OPENAI_RESPONSES, ModelApiType.OPENAI_CODEX->buildJsonObject{
                            put("status","completed")
                            put("output",buildJsonArray{
                                add(if(first) buildJsonObject{
                                    put("type","function_call");put("call_id","c1");put("name",name);put("arguments","{}")
                                } else buildJsonObject{
                                    put("type","message");put("content",buildJsonArray{add(buildJsonObject{put("type","output_text");put("text","done")})})
                                })
                            })
                        }
                        ModelApiType.OLLAMA->buildJsonObject{put("message",message)}
                        ModelApiType.ANTHROPIC, ModelApiType.CLAUDE_CODE->buildJsonObject{put("content",buildJsonArray{add(if(first)buildJsonObject{put("type","tool_use");put("id","c1");put("name",name);put("input",buildJsonObject{})}else buildJsonObject{put("type","text");put("text","done")})})}
                        ModelApiType.GOOGLE, ModelApiType.ANTIGRAVITY->buildJsonObject{put("candidates",buildJsonArray{add(buildJsonObject{put("content",buildJsonObject{put("parts",buildJsonArray{add(if(first)buildJsonObject{put("functionCall",buildJsonObject{put("name",name);put("args",buildJsonObject{})});put("thoughtSignature","sig")}else buildJsonObject{put("text","done")})})})})})}
                        else -> error("Unsupported fixture provider: $type")
                    }
                    return json(if (type == ModelApiType.ANTIGRAVITY) obj("response" to response) else response)
                }
            }
            try {
                val auth = if (type == ModelApiType.OPENAI_CODEX) {
                    CodexTokenStore(context).save("p", CodexTokenState("test_token", "test_refresh", accountId = "acc"))
                    Authorization(AuthMethod.OAUTH, key = "test@example.com", value = OpenAICodexAdapter.AUTH_MARKER)
                } else if (type.isAccountProvider) {
                    AccountTokenStore(context, type.name.lowercase()).save("p",
                        AccountTokenState("test_token", "test_refresh", projectId = "test-project"))
                    Authorization(AuthMethod.OAUTH, value = "account:${type.name.lowercase()}:v1")
                } else type.defaultAuthorization()
                val p=ModelProvider("p","P",type,auth=auth,baseUrl=server.url(if(type==ModelApiType.GOOGLE)"/v1beta" else if(type==ModelApiType.OLLAMA)"/api" else "/v1").toString(),config=ProviderConfiguration(supportStream=false,modelConfigs=mapOf("chat" to ModelConfiguration(supportsToolCalls=true))))
                val m=McpInfo("s","MCP",McpProtocol.STREAMABLE_HTTP,server.url("/mcp").toString())
                val http=ToolHttp(ToolFiles(root))
                val events=ToolChatViewModel(http,McpService(http),AiService(context)).generate(p,"chat",listOf(StoredMessage("u",ChatRole.USER,listOf(MessageVersion("Call echo", files = listOf(image.absolutePath))))),"",listOf(m),listOf(p),{ToolSettings()}).toList()
                assertEquals(type.name,1,calls);assertEquals(type.name,2,rounds)
                assertEquals(type.name,"done",events.filterIsInstance<GenerationEvent.Text>().joinToString(""){it.text})
                assertEquals(type.name,"success",events.filterIsInstance<GenerationEvent.Tool>().last().activity.status)
            }finally{server.shutdown();root.deleteRecursively()}
        }
    }
}
