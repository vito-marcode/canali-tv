package it.mdv.dttchannels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMatchTest {
    // names as exported by the box (2026-11-24)
    private val channels = listOf(
        "1" to "Rai 1", "2" to "Rai 2", "3" to "Rai 3 TGR Marche", "4" to "Rete4 HD", "5" to "Canale5 HD",
        "6" to "Italia1 HD", "7" to "LA7 HD", "8" to "TV8 HD", "9" to "NOVE", "10" to "TV CENTRO MARCHE",
        "12" to "e'TV MARCHE", "14" to "CANALE ITALIA extra", "15" to "7 GOLD MARCHE", "20" to "20Mediaset HD",
        "21" to "Rai 4", "22" to "Iris HD", "23" to "Rai 5", "24" to "Rai Movie", "25" to "Rai Premium",
        "26" to "Cielo", "27" to "27Twentyseven HD", "28" to "TV2000", "29" to "LA7CINEMA", "30" to "La5 HD",
        "31" to "Real Time", "34" to "Cine34 HD", "36" to "RTL 102.5", "52" to "DMAX", "48" to "Rai News 24", "852" to "Rai 3",
        "701" to "Rai Radio 1", "702" to "Rai Radio 2", "703" to "Rai Radio 3",
    ).mapIndexed { i, (lcn, name) ->
        Channel(i.toLong(), lcn, name, "in", "uri", lcn.length == 3, null, 0, 0, 0, true)
    }

    private fun lcn(phrase: String) = VoiceMatch.find(phrase, channels)?.lcn

    @Test fun examples() {
        assertEquals("2", lcn("Metti Rai2"))
        assertEquals("2", lcn("Metti Rai 2"))
        assertEquals("2", lcn("metti rai due"))
        assertEquals("3", lcn("metti rai 3"))
        assertEquals("3", lcn("metti rai tre"))
        assertEquals("3", lcn("Rai 3"))                 // not 852 "Rai 3" (another region)
    }

    @Test fun glued_names_and_number_words() {
        assertEquals("5", lcn("metti canale cinque"))
        assertEquals("5", lcn("metti il canale 5"))
        assertEquals("4", lcn("metti rete quattro"))
        assertEquals("6", lcn("guarda italia uno"))
        assertEquals("7", lcn("metti la sette"))
        assertEquals("7", lcn("metti la7"))
        assertEquals("29", lcn("metti la7 cinema"))
        assertEquals("8", lcn("metti tv otto"))
        assertEquals("9", lcn("metti nove"))
        assertEquals("20", lcn("metti venti"))
        assertEquals("27", lcn("metti twentyseven"))
        assertEquals("28", lcn("metti tv duemila"))
        assertEquals("34", lcn("metti cine trentaquattro"))
    }

    @Test fun rai_numbers_do_not_collide_with_lcn() {
        assertEquals("21", lcn("metti rai 4"))
        assertEquals("21", lcn("metti rai quattro"))
        assertEquals("23", lcn("metti rai cinque"))
        assertEquals("48", lcn("metti rai news ventiquattro"))
    }

    @Test fun bare_number_is_a_channel_number() {
        assertEquals("7", lcn("metti il 7"))           // not "7 GOLD MARCHE"
        assertEquals("21", lcn("metti il canale 21"))
        assertEquals("15", lcn("metti 7 gold"))
        assertEquals("702", lcn("metti il settecentodue"))
    }

    @Test fun partial_names_and_fillers() {
        assertEquals("24", lcn("metti rai movie per favore"))
        assertEquals("24", lcn("metti movie"))
        assertEquals("31", lcn("metti real time"))
        assertEquals("12", lcn("metti etv marche"))
        assertEquals("702", lcn("metti rai radio 2"))
        assertEquals("702", lcn("metti radio due"))
        assertEquals("1", lcn("Rai Uno"))
        assertEquals("36", lcn("metti rtl"))
    }

    @Test fun spelling_slips() {
        assertEquals("52", lcn("metti di max"))
        assertEquals("31", lcn("metti real taim"))
        assertEquals("26", lcn("metti cielo"))
        assertEquals("22", lcn("metti iris"))
    }

    @Test fun no_match() {
        assertNull(lcn("metti"))
        assertNull(lcn("che tempo fa domani"))
        assertNull(lcn("metti il 999"))
        assertNull(lcn(""))
    }
}
