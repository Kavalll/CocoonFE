package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.UriHosts
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object HtmlShare {
    private val urlPattern = Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    data class FormPayload(val action: String, val fields: Map<String, String>)

    fun urls(html: String): List<String> {
        val normalized = html.replace("\\/", "/")
        return urlPattern.findAll(normalized)
            .map { it.value.trim().trimEnd('.', ',', ';') }
            .distinct()
            .toList()
    }

    fun downloadForm(html: String, baseUrl: String): FormPayload? {
        val document = Jsoup.parse(html, baseUrl)
        val form = document.select("form").maxByOrNull { score(it) } ?: return null
        if (score(form) <= 0) return null
        val action = form.absUrl("action").ifBlank { baseUrl }
        val fields = linkedMapOf<String, String>()
        for (control in form.select("input, button")) {
            val name = control.attr("name")
            if (name.isBlank()) continue
            val type = control.attr("type").lowercase()
            if (type == "checkbox" && !control.hasAttr("checked")) continue
            if (type == "submit" || type == "button" || control.tagName() == "button") {
                val label = (control.attr("value").ifBlank { control.text() }).lowercase()
                if ("download" in label || name == "method_free" || name == "submit") {
                    fields.putIfAbsent(name, control.attr("value").ifBlank { control.text() })
                }
                continue
            }
            fields[name] = control.attr("value")
        }
        return FormPayload(action, fields)
    }

    fun subdomainFile(html: String, parentHost: String): String? {
        return urls(html).firstOrNull { url ->
            val host = UriHosts.hostOf(url) ?: return@firstOrNull false
            host != parentHost && host.endsWith(".$parentHost")
        }
    }

    private fun score(form: Element): Int {
        var score = 0
        val text = form.text().lowercase()
        if ("download" in text) score += 2
        if (form.select("input[name=method_free], input[name=op]").isNotEmpty()) score += 3
        if (form.hasAttr("action")) score += 1
        if (form.attr("method").equals("post", ignoreCase = true)) score += 1
        return score
    }

    fun looksLikePayload(url: String): Boolean {
        val path = UriHosts.pathOf(url).lowercase()
        val last = path.substringAfterLast('/').substringBefore('?')
        if (last.endsWith(".js") || last.endsWith(".css") || last.endsWith(".png") ||
            last.endsWith(".svg") || last.endsWith(".woff") || last.endsWith(".woff2") ||
            last.endsWith(".html") || last.endsWith(".php")
        ) {
            return false
        }
        return '.' in last || "/download" in path
    }
}
