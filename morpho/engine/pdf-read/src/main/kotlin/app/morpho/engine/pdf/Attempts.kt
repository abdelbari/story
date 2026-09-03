package app.morpho.engine.pdf

import app.morpho.engine.layout.Reading

/**
 * [work]'s result, or null where it failed — but never where the machine
 * ran out of room to do it in.
 *
 * A reader guards its optional passes so that one of them failing costs a
 * document its pictures rather than the reader its life. Running out of
 * memory is not that kind of failure: shrugging it off hands back a
 * document with pages quietly missing from it, and nobody is told. It is
 * raised again instead, for the caller to report honestly.
 *
 * A reading somebody stopped is the second thing that must not be
 * shrugged off, and for the same reason: swallowed here, a cancelled
 * pass would return nothing and the reading would carry on to hand back
 * a document with everything that pass would have found missing from it
 * — which is not what "stop" means.
 */
internal inline fun <T> attempt(work: () -> T): T? =
    try {
        work()
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (e: Reading.Cancelled) {
        throw e
    } catch (e: Exception) {
        null
    }
