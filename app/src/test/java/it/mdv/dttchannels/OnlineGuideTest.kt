package it.mdv.dttchannels

import org.junit.Assert.assertEquals
import org.junit.Test

/** OnlineGuide.norm must give what companion/dtt_sync.py norm() gives, or channels match differently than on the Pi. */
class OnlineGuideTest {
    @Test fun same_as_the_pi() {
        mapOf(
            "Rai 3 TGR Marche" to "rai3", "Canale5 HD" to "canale5", "Rete4 HD" to "rete4", "Italia1 HD" to "italia1",
            "TV8 HD" to "tv8", "Rai 4" to "rai4", "NOVE" to "nove", "Rai News 24" to "rainews24",
            "Mediaset Italia2 HD" to "mediasetitalia2", "e'TV +" to "etvplus", "Canale Italia" to "canale",
            "Rai.1.it" to "rai1", "Rai 1" to "rai1", "HD" to "hd",
        ).forEach { (name, want) -> assertEquals(name, want, OnlineGuide.norm(name)) }
    }
}
