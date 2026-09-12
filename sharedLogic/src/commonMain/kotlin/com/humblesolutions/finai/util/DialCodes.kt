package com.humblesolutions.finai.util

/**
 * A country's ISO 3166-1 alpha-2 code and its E.164 calling code.
 *
 * No country *name*: names are localised, and both platforms already localise a
 * region code better than a hardcoded English table would (Android
 * `Locale("", region).displayCountry`, iOS
 * `Locale.localizedString(forRegionCode:)`). Shared owns the data that is the
 * same everywhere; the platform owns the words.
 */
data class DialCode(
    /** ISO 3166-1 alpha-2, upper case. */
    val region: String,
    /** Digits only, no leading `+`. */
    val code: String,
) {
    /** As shown in the picker and prefixed to the number: `+1`. */
    val display: String get() = "+$code"
}

/**
 * The dial-code list, and which one to pre-select.
 *
 * **This picks a dialling prefix and nothing else** (manager decision,
 * 2026-09-11). The country chosen here is never sent as the user's region: the
 * server derives that from the full verified number with libphonenumber
 * (Finance-backend#24). Canada, the US and most of the Caribbean share `+1`, so
 * a pre-selected flag the user never checked would set the wrong region
 * silently, while the number's own area code is reliable.
 */
object DialCodes {

    // region:code, packed rather than 240 lines of list literal. Parsed once.
    private const val TABLE =
        "AD:376,AE:971,AF:93,AG:1,AI:1,AL:355,AM:374,AO:244,AR:54,AS:1,AT:43,AU:61,AW:297,AX:358,AZ:994," +
            "BA:387,BB:1,BD:880,BE:32,BF:226,BG:359,BH:973,BI:257,BJ:229,BL:590,BM:1,BN:673,BO:591,BQ:599," +
            "BR:55,BS:1,BT:975,BW:267,BY:375,BZ:501,CA:1,CD:243,CF:236,CG:242,CH:41,CI:225,CK:682,CL:56," +
            "CM:237,CN:86,CO:57,CR:506,CU:53,CV:238,CW:599,CY:357,CZ:420,DE:49,DJ:253,DK:45,DM:1,DO:1," +
            "DZ:213,EC:593,EE:372,EG:20,ER:291,ES:34,ET:251,FI:358,FJ:679,FK:500,FM:691,FO:298,FR:33," +
            "GA:241,GB:44,GD:1,GE:995,GF:594,GG:44,GH:233,GI:350,GL:299,GM:220,GN:224,GP:590,GQ:240,GR:30," +
            "GT:502,GU:1,GW:245,GY:592,HK:852,HN:504,HR:385,HT:509,HU:36,ID:62,IE:353,IL:972,IM:44,IN:91," +
            "IO:246,IQ:964,IR:98,IS:354,IT:39,JE:44,JM:1,JO:962,JP:81,KE:254,KG:996,KH:855,KI:686,KM:269," +
            "KN:1,KP:850,KR:82,KW:965,KY:1,KZ:7,LA:856,LB:961,LC:1,LI:423,LK:94,LR:231,LS:266,LT:370," +
            "LU:352,LV:371,LY:218,MA:212,MC:377,MD:373,ME:382,MF:590,MG:261,MH:692,MK:389,ML:223,MM:95," +
            "MN:976,MO:853,MP:1,MQ:596,MR:222,MS:1,MT:356,MU:230,MV:960,MW:265,MX:52,MY:60,MZ:258,NA:264," +
            "NC:687,NE:227,NF:672,NG:234,NI:505,NL:31,NO:47,NP:977,NR:674,NU:683,NZ:64,OM:968,PA:507," +
            "PE:51,PF:689,PG:675,PH:63,PK:92,PL:48,PM:508,PR:1,PS:970,PT:351,PW:680,PY:595,QA:974,RE:262," +
            "RO:40,RS:381,RU:7,RW:250,SA:966,SB:677,SC:248,SD:249,SE:46,SG:65,SH:290,SI:386,SJ:47,SK:421," +
            "SL:232,SM:378,SN:221,SO:252,SR:597,SS:211,ST:239,SV:503,SX:1,SY:963,SZ:268,TC:1,TD:235," +
            "TG:228,TH:66,TJ:992,TK:690,TL:670,TM:993,TN:216,TO:676,TR:90,TT:1,TV:688,TW:886,TZ:255," +
            "UA:380,UG:256,US:1,UY:598,UZ:998,VA:39,VC:1,VE:58,VG:1,VI:1,VN:84,VU:678,WF:681,WS:685," +
            "YE:967,YT:262,ZA:27,ZM:260,ZW:263"

    /** Every entry, ordered by region code. */
    val all: List<DialCode> = TABLE.split(',').map { entry ->
        val separator = entry.indexOf(':')
        DialCode(entry.substring(0, separator), entry.substring(separator + 1))
    }

    private val byRegion: Map<String, DialCode> = all.associateBy { it.region }

    /**
     * The market this app launches in, and the fallback when nothing else
     * identifies the device. Documented rather than guessed: a wrong prefix is
     * visible and one tap to fix, whereas an empty picker is a dead end.
     */
    val fallback: DialCode = byRegion.getValue("CA")

    fun forRegion(region: String?): DialCode? =
        region?.takeIf { it.isNotBlank() }?.let { byRegion[it.trim().uppercase()] }

    /**
     * Which entry to pre-select, as a UX nicety only (PRD §4.6).
     *
     * The device's own region is trusted first; a time zone is a weaker signal
     * and used only when there is no region. Neither decides anything but the
     * prefix shown before the user types.
     *
     * Both values come from the platform — `Locale` and `TimeZone` are native
     * APIs, and this module has no `expect`/`actual` (kmp-arch-v2).
     */
    fun defaultFor(deviceRegion: String?, timeZoneId: String?): DialCode =
        forRegion(deviceRegion) ?: forRegion(regionForTimeZone(timeZoneId)) ?: fallback

    /**
     * A best-effort region for an IANA time zone id.
     *
     * Deliberately small: this only ever pre-selects a prefix, so covering the
     * zones our users are actually in beats a 400-entry table that still has to
     * fall back. Anything unlisted falls through to [fallback].
     */
    private fun regionForTimeZone(id: String?): String? {
        val zone = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ZONES[zone]
    }

    private val ZONES: Map<String, String> = mapOf(
        "America/Toronto" to "CA", "America/Vancouver" to "CA", "America/Edmonton" to "CA",
        "America/Winnipeg" to "CA", "America/Halifax" to "CA", "America/St_Johns" to "CA",
        "America/Montreal" to "CA", "America/Regina" to "CA",
        "America/New_York" to "US", "America/Chicago" to "US", "America/Denver" to "US",
        "America/Los_Angeles" to "US", "America/Phoenix" to "US", "America/Anchorage" to "US",
        "Pacific/Honolulu" to "US",
        "Europe/London" to "GB", "Europe/Dublin" to "IE", "Europe/Paris" to "FR",
        "Europe/Berlin" to "DE", "Europe/Madrid" to "ES", "Europe/Rome" to "IT",
        "Europe/Amsterdam" to "NL", "Europe/Brussels" to "BE", "Europe/Zurich" to "CH",
        "Europe/Stockholm" to "SE", "Europe/Oslo" to "NO", "Europe/Copenhagen" to "DK",
        "Europe/Helsinki" to "FI", "Europe/Warsaw" to "PL", "Europe/Lisbon" to "PT",
        "Asia/Kolkata" to "IN", "Asia/Calcutta" to "IN", "Asia/Karachi" to "PK",
        "Asia/Dhaka" to "BD", "Asia/Colombo" to "LK", "Asia/Kathmandu" to "NP",
        "Asia/Dubai" to "AE", "Asia/Riyadh" to "SA", "Asia/Qatar" to "QA",
        "Asia/Singapore" to "SG", "Asia/Hong_Kong" to "HK", "Asia/Tokyo" to "JP",
        "Asia/Seoul" to "KR", "Asia/Shanghai" to "CN", "Asia/Manila" to "PH",
        "Asia/Jakarta" to "ID", "Asia/Bangkok" to "TH", "Asia/Kuala_Lumpur" to "MY",
        "Australia/Sydney" to "AU", "Australia/Melbourne" to "AU", "Australia/Perth" to "AU",
        "Australia/Brisbane" to "AU", "Pacific/Auckland" to "NZ",
        "Africa/Lagos" to "NG", "Africa/Nairobi" to "KE", "Africa/Johannesburg" to "ZA",
        "Africa/Cairo" to "EG", "Africa/Accra" to "GH",
        "America/Mexico_City" to "MX", "America/Sao_Paulo" to "BR", "America/Bogota" to "CO",
        "America/Buenos_Aires" to "AR", "America/Argentina/Buenos_Aires" to "AR",
        "America/Santiago" to "CL", "America/Lima" to "PE",
    )
}
