package com.rotacard.app

/**
 * Guarda os prints em que a oferta com certeza estava na tela.
 *
 * Antes havia um unico "ultimo print", que era sobrescrito a cada captura — inclusive pelas
 * capturas feitas depois de a oferta sumir (tela de navegacao, tela inicial do app). Na hora do
 * aceite o app salvava esse print, e por isso o print saia sem a oferta.
 *
 * Agora um print so entra aqui depois de confirmado (o texto da oferta ainda estava na tela,
 * ou o OCR leu a oferta naquela imagem), e capturas posteriores nunca apagam o print bom.
 *
 * Generico (T = Bitmap no app) para poder ser testado sem Android.
 */
class ShotStore<T>(private val max: Int = 4) {
    private class Entry<T>(val key: String, val app: String, val shot: T, val at: Long)

    private val entries = ArrayList<Entry<T>>()

    @Synchronized
    fun put(key: String, app: String, shot: T, at: Long) {
        // mantem um print por oferta: o mais recente substitui o anterior da mesma oferta
        entries.removeAll { it.key == key }
        entries.add(Entry(key, app, shot, at))
        while (entries.size > max) entries.removeAt(0)
    }

    /**
     * O melhor print para a oferta aceita.
     * 1) o mais recente com a mesma chave;
     * 2) senao, o mais recente do mesmo app tirado desde que a oferta apareceu (o OCR as vezes
     *    le a distancia com pequenas diferencas e a chave muda de uma leitura para outra).
     * Prints mais velhos que maxAgeMs nao valem.
     */
    @Synchronized
    fun find(key: String?, app: String?, since: Long, now: Long, maxAgeMs: Long): T? {
        val fresh = entries.filter { now - it.at <= maxAgeMs }
        if (key != null) fresh.lastOrNull { it.key == key }?.let { return it.shot }
        if (app != null) fresh.lastOrNull { it.app == app && it.at >= since }?.let { return it.shot }
        return null
    }

    @Synchronized
    fun hasFor(key: String): Boolean = entries.any { it.key == key }

    @Synchronized
    fun prune(now: Long, maxAgeMs: Long) {
        entries.removeAll { now - it.at > maxAgeMs }
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
