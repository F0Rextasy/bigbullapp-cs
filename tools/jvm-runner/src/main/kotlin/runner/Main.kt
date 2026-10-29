package runner

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.UrlContext
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder

private fun modules(): Map<String, MainAPI> = linkedMapOf(
    "AllCalidad" to com.allcalidad.AllCalidadProvider(),
    "DiziMom" to com.dizimom.DiziMomProvider(),
    "Monoschinos" to com.monoschinos.MonoschinosProvider(),
)

private suspend fun search(api: MainAPI, q: String): List<JSONObject> {
    UrlContext.base.set(api.mainUrl)
    return api.search(q).map {
        JSONObject().put("name", it.name).put("url", it.url).put("poster", it.posterUrl ?: "")
    }
}

private suspend fun load(api: MainAPI, url: String): JSONObject {
    UrlContext.base.set(api.mainUrl)
    val load = api.load(url)
    val out = JSONObject().put("name", load?.name ?: "").put("poster", load?.posterUrl ?: "")
    val episodes = JSONArray()
    when (load) {
        is MovieLoadResponse -> out.put("data", load.data)
        is TvSeriesLoadResponse -> {
            load.episodes.orEmpty().forEach { ep ->
                episodes.put(JSONObject().put("name", ep.name ?: "").put("data", ep.data))
            }
            out.put("episodes", episodes)
        }
    }
    return out
}

private suspend fun links(api: MainAPI, data: String): JSONObject {
    UrlContext.base.set(api.mainUrl)
    val out = JSONObject()
    val links = JSONArray()
    val subs = JSONArray()
    val ok = runCatching {
        api.loadLinks(data, false, { sub: SubtitleFile ->
            subs.put(JSONObject().put("lang", sub.lang).put("url", sub.url))
        }, { link: ExtractorLink ->
            links.put(JSONObject().put("url", link.url).put("type", link.type.name).put("quality", link.quality))
        })
    }.getOrDefault(false)
    return out.put("ok", ok).put("links", links).put("subs", subs)
}

private fun send(ex: HttpExchange, code: Int, body: String, type: String = "application/json") {
    val bytes = body.toByteArray()
    ex.responseHeaders.add("Content-Type", "$type; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun param(ex: HttpExchange, name: String): String =
    ex.requestURI.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

private fun serve() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 8099), 0)
    server.createContext("/") { ex ->
        runBlocking {
            val path = ex.requestURI.path
            val api = modules()[param(ex, "module")]
            try {
                when {
                    path == "/" -> send(ex, 200, HTML, "text/html")
                    api == null -> send(ex, 400, """{"error":"unknown module"}""")
                    path == "/search" -> send(ex, 200, JSONArray(search(api, param(ex, "q"))).toString())
                    path == "/load" -> send(ex, 200, load(api, param(ex, "url")).toString())
                    path == "/links" -> send(ex, 200, links(api, param(ex, "data")).toString())
                    else -> send(ex, 404, """{"error":"not found"}""")
                }
            } catch (e: Exception) {
                send(ex, 500, JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString())
            }
        }
    }
    server.start()
    println("http://127.0.0.1:8099")
}

private const val HTML = """
<!doctype html><meta charset="utf-8"><title>Provider QA</title>
<style>body{font:14px system-ui;margin:20px}input,select,button{padding:6px;margin:2px}
pre{background:#111;color:#ddd;padding:10px;white-space:pre-wrap}</style>
<h2>Provider QA</h2>
<select id=m></select> <input id=q placeholder="query"> <button onclick=doSearch()>Ara</button>
<pre id=out></pre>
<script>
const mods=["AllCalidad","DiziMom","Monoschinos"];
const m=document.getElementById("m");
mods.forEach(x=>{const o=document.createElement("option");o.textContent=x;m.appendChild(o)});
const out=document.getElementById("out");
async function j(u){const r=await fetch(u);return r.json()}
async function doSearch(){
  out.textContent="...";
  const list=await j("/search?module="+m.value+"&q="+encodeURIComponent(document.getElementById("q").value));
  out.textContent=JSON.stringify(list,null,2);
  window.__list=list;
}
</script>
"""

fun main(args: Array<String>) = runBlocking {
    if (args.contains("--serve")) {
        serve()
        return@runBlocking
    }
    modules().forEach { (name, api) ->
        val query = when (name) { "AllCalidad" -> "amor"; "DiziMom" -> "yalan"; else -> "slime" }
        println("=== $name ===")
        val results = runCatching { search(api, query) }.getOrElse { emptyList() }
        println("SEARCH ${results.size}")
        val first = results.firstOrNull() ?: return@forEach
        val detail = runCatching { load(api, first.getString("url")) }.getOrNull() ?: return@forEach
        println("LOAD ${detail.optString("name")}")
        val data = detail.optString("data").ifBlank {
            detail.optJSONArray("episodes")?.optJSONObject(0)?.optString("data").orEmpty()
        }
        val linkResult = runCatching { links(api, data) }.getOrNull()
        println("LINKS ${linkResult}")
    }
}
