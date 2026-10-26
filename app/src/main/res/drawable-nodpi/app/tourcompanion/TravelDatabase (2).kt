package com.example.tourcompanion

data class TravelTip(
    val topic: String,
    val keywords: List<String>,
    val answer: String
)

class TravelDatabase {

    private val tips = listOf(

        TravelTip(
            topic = "Lost or directions",
            keywords = listOf("lost", "direction", "where", "find", "route"),
            answer = "If you are lost, stay in a public place and check your map. Ask a trusted local or official for directions."
        ),

        TravelTip(
            topic = "Night safety",
            keywords = listOf("night", "dark", "late", "safe"),
            answer = "At night, prefer well-lit public areas, use trusted transport, and keep a trusted person informed about your plans."
        ),

        TravelTip(
            topic = "Transport",
            keywords = listOf("bus", "train", "metro", "transport", "taxi", "auto"),
            answer = "Use official or trusted transport services. Check the destination and fare before starting your journey."
        ),

        TravelTip(
            topic = "Money",
            keywords = listOf("money", "cash", "atm", "card", "payment"),
            answer = "Keep some emergency cash separately and use secure payment methods. Avoid displaying large amounts of money in public."
        ),

        TravelTip(
            topic = "Useful phrases",
            keywords = listOf("phrase", "language", "speak", "tamil", "hindi"),
            answer = "Useful phrases include 'Hello', 'Thank you', 'Where is this place?', and 'Please help me.'"
        ),

        TravelTip(
            topic = "Emergency",
            keywords = listOf("emergency", "help", "police", "danger", "112"),
            answer = "If you are facing an emergency in India, call 112 or seek help from nearby authorities or trusted people."
        ),

        TravelTip(
            topic = "Evening plan",
            keywords = listOf("evening", "plan", "tonight", "visit"),
            answer = "For an evening outing, choose familiar public places, plan your transport in advance, and keep your phone charged."
        )
    )

    fun getAllTips(): List<TravelTip> {
        return tips
    }
}