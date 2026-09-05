package app.morpho.engine.layout

/**
 * A conversion kept against the death of the process holding it.
 *
 * Android reclaims an app in the background without warning, and the
 * save dialog is another app: a reader who ran a three-minute
 * recognition and then opened the dialog could come back to find the
 * conversion gone and the dialog's empty file the only trace of it. So
 * the last conversion is kept as text in the app's own files — the
 * document as it stands after the reader's corrections, with what to
 * call the file and what kind it is — and put back on the next launch,
 * converted and waiting to be saved as it was.
 *
 * The text is [DocumentJson]'s, so every field of every block comes
 * back exactly; it is read as carefully, since a file in the app's own
 * store is still a file, but with a wider bound than anything from
 * outside gets, a document of pictures being what it is.
 */
object Autosave {

    /** Which shape this is, so a later shape can be told from it. */
    const val FORMAT = 1

    /** The most text a kept conversion may be; larger is not kept, and not read. */
    const val MOST_LENGTH = 64_000_000

    /** What was kept: what to call the file, what kind it is, and the document. */
    class Kept(val name: String, val mimeType: String, val document: DocumentModel)

    /** [document] to be kept as [name] of [mimeType], as text — or null where it is too large to keep. */
    fun write(name: String, mimeType: String, document: DocumentModel): String? {
        val text = Json.write(
            mapOf(
                "morpho" to FORMAT,
                "kind" to "conversion",
                "name" to name,
                "mimeType" to mimeType,
                "document" to DocumentJson.toMap(document),
            ),
        )
        return text.takeIf { it.length <= MOST_LENGTH }
    }

    /** The conversion [json] kept, or [Json.Malformed] where it is not one this can read. */
    fun read(json: String): Kept {
        val map = Json.parse(json, MOST_LENGTH) as? Map<*, *> ?: throw Json.Malformed("not a kept conversion")
        if (map["morpho"] != FORMAT.toDouble() || map["kind"] != "conversion") throw Json.Malformed("a kept conversion in another shape")
        val name = map["name"] as? String ?: throw Json.Malformed("no name")
        val mimeType = map["mimeType"] as? String ?: throw Json.Malformed("no type")
        val document = DocumentJson.fromMap(map["document"] as? Map<*, *> ?: throw Json.Malformed("no document"))
        return Kept(name.take(255), mimeType.take(200), document)
    }
}
