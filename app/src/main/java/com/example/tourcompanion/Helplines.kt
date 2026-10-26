package com.example.tourcompanion

data class Helpline(val icon: String, val name: String, val number: String, val note: String)

/**
 * Numbers checked against Incredible India / Ministry of Tourism sources.
 * Numbers can differ by state, so 112 is always the safest first call.
 */
object Helplines {
    val all = listOf(
        Helpline("🆘", "All emergencies", "112", "Police, fire and ambulance - works across India"),
        Helpline("👮", "Police", "100", "Also reachable through 112"),
        Helpline("🚒", "Fire", "101", "Fire brigade"),
        Helpline("🚑", "Ambulance", "108", "Some states use 102 - try 112 if unsure"),
        Helpline("🧭", "Tourist helpline", "1363", "Ministry of Tourism, round the clock"),
        Helpline("👩", "Women helpline", "1091", "Women in distress (181 is another women's helpline)"),
        Helpline("🧒", "Child helpline", "1098", "Children in difficult situations"),
        Helpline("🚗", "Road accident emergency", "1073", "Highway accident help"),
        Helpline("🚆", "Railway helpline", "139", "Train enquiries and help"),
        Helpline("👴", "Senior citizen helpline", "14567", "Help for senior citizens")
    )
}
