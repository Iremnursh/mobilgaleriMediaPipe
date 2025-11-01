//com/example/mobilgaleri/domain/usecase/ClusterFacesUseCase.kt
package com.example.mobilgaleri.domain.usecase

import kotlin.math.sqrt

class ClusterFacesUseCase(private val threshold: Float = 0.82f) {
    private val centroids = mutableListOf<FloatArray>()
    private val sizes = mutableListOf<Int>()

    /**
     * Tek bir embedding için küme (kişi) kimliği döner.
     */
    fun assign(embedding: FloatArray): Int {
        val x = l2norm(embedding)
        if (centroids.isEmpty()) {
            println("CLUSTER_DEBUG: İlk küme '0' oluşturuldu.")
            centroids.add(x.copyOf())
            sizes.add(1)
            return 0
        }

        var bestIdx = -1
        var bestSim = -1f
        for (i in centroids.indices) {
            val sim = cosineSim(x, centroids[i])
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }

        if (bestSim >= threshold) {
            // --- YENİ LOG ---
            println("CLUSTER_DEBUG: Bir yüz, mevcut küme ${bestIdx}'ye %.4f benzerlik skoruyla eklendi. (Eşik: $threshold)".format(bestSim))

            updateCentroid(bestIdx, x)
            return bestIdx
        } else {
            // --- YENİ LOG ---
            val newClusterId = centroids.size
            println("CLUSTER_DEBUG: En iyi benzerlik (%.4f) eşiği geçemedi. Yeni küme ${newClusterId} oluşturuldu.".format(bestSim))

            centroids.add(x.copyOf())
            sizes.add(1)
            return newClusterId
        }
    }

    // YENİ: Eksik olan assignAll fonksiyonunu geri ekledik.
    /**
     * Bir seferde birden fazla embedding'i atamak için yardımcı fonksiyon.
     * FacePipeline gibi sınıflar burayı çağırabilir.
     */
    fun assignAll(embeddings: List<FloatArray>): List<Int> {
        return embeddings.map { assign(it) }
    }

    private fun updateCentroid(idx: Int, x: FloatArray) {
        val nOld = sizes[idx]
        val nNew = nOld + 1
        val c = centroids[idx]
        for (k in c.indices) {
            c[k] = (c[k] * nOld + x[k]) / nNew
        }
        l2normInPlace(c)
        sizes[idx] = nNew
    }

    // --- Yardımcı Fonksiyonlar ---
    private fun cosineSim(a:FloatArray,b:FloatArray):Float{var d=0f;var na=0f;var nb=0f;for(i in a.indices){d+=a[i]*b[i];na+=a[i]*a[i];nb+=b[i]*b[i];};val den=sqrt(na)*sqrt(nb);return if(den==0f) 0f else d/den}
    private fun l2norm(v:FloatArray):FloatArray{var s=0f;for(f in v)s+=f*f;val inv=if(s==0f)1f else 1f/sqrt(s);return FloatArray(v.size){i->v[i]*inv}}
    private fun l2normInPlace(v:FloatArray){var s=0f;for(f in v)s+=f*f;val inv=if(s==0f)1f else 1f/sqrt(s);for(i in v.indices)v[i]*=inv}

    fun getCentroid(personId: Int): FloatArray? {
        return centroids.getOrNull(personId)
    }
}
