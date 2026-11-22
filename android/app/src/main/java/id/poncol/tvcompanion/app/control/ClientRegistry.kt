package id.poncol.tvcompanion.app.control

/** Paired iPhones as hex SHA-256 of their PKCS#1 key, oldest first; at most four. Storage is injected. */
class ClientRegistry(private val load: () -> Set<String>, private val save: (Set<String>) -> Unit) {
    @Synchronized fun isPaired(hash: String) = hash in load()
    @Synchronized fun add(hash: String) {
        val next = LinkedHashSet(load()); next.remove(hash); next.add(hash)
        while (next.size > MAX) next.remove(next.first())
        save(next)
    }
    @Synchronized fun clear() = save(emptySet())
    val isEmpty: Boolean @Synchronized get() = load().isEmpty()
    private companion object { const val MAX = 4 }
}
