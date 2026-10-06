package de.bernos.sonos

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

internal object Xml {
    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        // Nicht jede Plattform (z. B. Android) kennt dieses Feature.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }

    fun parse(xml: String): Document = synchronized(factory) {
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

/** Alle Nachfahren mit dem lokalen Namen [localName], unabhängig vom Namespace. */
internal fun Node.descendants(localName: String): List<Element> {
    val list = when (this) {
        is Document -> getElementsByTagNameNS("*", localName)
        is Element -> getElementsByTagNameNS("*", localName)
        else -> return emptyList()
    }
    return (0 until list.length).map { list.item(it) as Element }
}

internal fun Node.firstText(localName: String): String? =
    descendants(localName).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

internal fun Element.childElements(): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}
