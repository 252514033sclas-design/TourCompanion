package com.example.tourcompanion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TravelDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "saathi_travel.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE travel_tips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                topic TEXT NOT NULL,
                keywords TEXT NOT NULL,
                answer TEXT NOT NULL
            )
            """.trimIndent()
        )

        // 1
        addTip(
            db,
            "Lost or Directions",
            "lost,direction,where,find,route",
            "If you are lost, stay in a public place, check your map, and ask a trusted local or official for directions."
        )

        // 2
        addTip(
            db,
            "Night Safety",
            "night,dark,late,safe",
            "At night, prefer well-lit public areas, use trusted transport, and keep a trusted person informed about your plans."
        )

        // 3
        addTip(
            db,
            "Transport",
            "bus,train,metro,transport,taxi,auto",
            "Use official or trusted transport services. Check the destination and fare before starting your journey."
        )

        // 4
        addTip(
            db,
            "Money",
            "money,cash,atm,card,payment",
            "Keep some emergency cash separately and use secure payment methods. Avoid displaying large amounts of money in public."
        )

        // 5
        addTip(
            db,
            "Useful Phrases",
            "phrase,phrases,language,speak,tamil,hindi",
            "Useful phrases include Hello, Thank you, Where is this place?, and Please help me."
        )

        // 6
        addTip(
            db,
            "Emergency",
            "emergency,help,police,danger,112",
            "If you are facing an emergency in India, call 112 or seek help from nearby authorities or trusted people."
        )

        // 7
        addTip(
            db,
            "Evening Plan",
            "evening,plan,tonight,visit",
            "For an evening outing, choose familiar public places, plan your transport in advance, and keep your phone charged."
        )

        // 8
        addTip(
            db,
            "Hotel Safety",
            "hotel,room,stay,lodging",
            "Choose reputable accommodation, keep your room locked, and avoid sharing your room details with strangers."
        )

        // 9
        addTip(
            db,
            "Food Safety",
            "food,restaurant,eat,meal",
            "Choose clean and reputable food places, drink safe water, and be careful with unfamiliar foods if you have allergies."
        )

        // 10
        addTip(
            db,
            "Health",
            "health,sick,ill,doctor,medicine",
            "If you feel unwell, rest and seek help from a qualified medical professional when needed."
        )

        // 11
        addTip(
            db,
            "Weather",
            "weather,rain,hot,heat,cold,temperature",
            "Check the weather before travelling and carry suitable clothing, water, or rain protection."
        )

        // 12
        addTip(
            db,
            "Documents",
            "passport,id,document,documents,license",
            "Keep important identification documents secure and maintain a safe digital or physical backup when appropriate."
        )

        // 13
        addTip(
            db,
            "Phone Battery",
            "phone,battery,charge,charger",
            "Keep your phone charged while travelling and consider carrying a reliable power bank."
        )

        // 14
        addTip(
            db,
            "Internet",
            "internet,wifi,sim,data,network",
            "Use trusted Wi-Fi networks and make sure you have a reliable mobile data option for navigation and communication."
        )

        // 15
        addTip(
            db,
            "Luggage",
            "luggage,bag,suitcase,baggage",
            "Keep your luggage close to you in crowded places and avoid leaving valuable items unattended."
        )

        // 16
        addTip(
            db,
            "Scam Awareness",
            "scam,fraud,cheat,scams,fraudulent",
            "Be cautious of unexpected offers, requests for money, or people pressuring you to make quick decisions."
        )

        // 17
        addTip(
            db,
            "Stranger Safety",
            "stranger,strangers,unknown,person",
            "Avoid sharing unnecessary personal information with strangers and meet unfamiliar people only in public places."
        )

        // 18
        addTip(
            db,
            "Meeting Someone",
            "meeting,meet,meetup,appointment",
            "If meeting someone you do not know well, choose a public location and let a trusted person know your plans."
        )

        // 19
        addTip(
            db,
            "Location Sharing",
            "location,share,sharing,gps",
            "When appropriate, share your travel plans or live location with a trusted person."
        )

        // 20
        addTip(
            db,
            "Emergency Contact",
            "family,contact,contacts,relative",
            "Keep important emergency contacts easily accessible and inform someone you trust about your travel plans."
        )

        // 21
        addTip(
            db,
            "Police Help",
            "police,station,cop,law",
            "For safety concerns, seek assistance from official police or other authorized authorities."
        )

        // 22
        addTip(
            db,
            "Tourist Places",
            "tourist,attraction,sightseeing,place",
            "Prefer established tourist locations and check opening hours and local guidelines before visiting."
        )

        // 23
        addTip(
            db,
            "Railway Station",
            "railway,station,rail,platform",
            "Arrive at the railway station with enough time, verify your platform and train details, and keep your belongings secure."
        )

        // 24
        addTip(
            db,
            "Airport",
            "airport,flight,boarding,terminal",
            "Keep your identification and booking information ready and arrive early enough for security and boarding procedures."
        )

        // 25
        addTip(
            db,
            "Bus Station",
            "busstand,busstation,terminal,busstop",
            "Confirm the bus route and destination before boarding and keep your belongings with you."
        )

        // 26
        addTip(
            db,
            "Auto Rickshaw",
            "auto,rickshaw,autorickshaw",
            "Before travelling by auto-rickshaw, confirm the destination and fare or use a trusted booking service."
        )

        // 27
        addTip(
            db,
            "Taxi",
            "taxi,cab,driver,booking",
            "Use a trusted taxi service, verify the vehicle details, and check the destination before starting the trip."
        )

        // 28
        addTip(
            db,
            "ATM Safety",
            "atm,withdraw,withdrawal,bank",
            "Use ATMs in secure and well-lit locations, protect your PIN, and collect your card and cash before leaving."
        )

        // 29
        addTip(
            db,
            "Card Payment",
            "card,debit,credit,upi,payment",
            "Keep payment information private and check the transaction amount before confirming a payment."
        )

        // 30
        addTip(
            db,
            "Crowded Places",
            "crowd,crowded,festival,market",
            "In crowded places, keep your phone and valuables secure and identify an easy meeting point if travelling with others."
        )

        // 31
        addTip(
            db,
            "Morning Travel",
            "morning,early,sunrise",
            "For early travel, plan your transport in advance and make sure you know how to reach your destination."
        )

        // 32
        addTip(
            db,
            "Public Places",
            "public,park,mall,street",
            "When travelling alone, prefer familiar and populated public areas, especially when you are unfamiliar with the location."
        )

        // 33
        addTip(
            db,
            "Navigation",
            "map,maps,navigation,directions,gps",
            "Check your route before leaving and keep an offline or backup navigation option when possible."
        )

        // 34
        addTip(
            db,
            "Travel Planning",
            "travel,trip,plan,planning",
            "Before travelling, plan your route, accommodation, transport, important contacts, and basic emergency options."
        )

        // 35
        addTip(
            db,
            "Packing",
            "pack,packing,clothes,essentials",
            "Pack identification, medication if prescribed, charging equipment, suitable clothing, and other essential items."
        )

        // 36
        addTip(
            db,
            "Water",
            "water,drink,thirst,dehydration",
            "Carry enough safe drinking water, especially during long journeys or hot weather."
        )

        // 37
        addTip(
            db,
            "Personal Information",
            "personal,privacy,address,information",
            "Avoid sharing sensitive personal information such as your home address, passwords, or financial details with strangers."
        )

        // 38
        addTip(
            db,
            "Online Safety",
            "online,internet,password,account,security",
            "Avoid entering sensitive information on suspicious websites and use strong passwords for important accounts."
        )

        // 39
        addTip(
            db,
            "Lost Phone",
            "lostphone,phone lost,missing phone",
            "If you lose your phone, use available device-location tools, secure your accounts, and contact your service provider when necessary."
        )

        // 40
        addTip(
            db,
            "Lost Wallet",
            "wallet,lost wallet,missing wallet",
            "If your wallet is lost, secure or block payment cards and report important identification documents if necessary."
        )

        // 41
        addTip(
            db,
            "Public Transport",
            "publictransport,public transport,local transport",
            "Use official public transport information and keep your belongings secure during the journey."
        )

        // 42
        addTip(
            db,
            "Language Help",
            "language,translation,translate,communication",
            "If there is a language barrier, use simple words, translation tools, or ask an official or trusted person for help."
        )

        // 43
        addTip(
            db,
            "Local Customs",
            "custom,culture,cultural,tradition",
            "Respect local customs, dress expectations, public rules, and cultural practices while travelling."
        )

        // 44
        addTip(
            db,
            "Photography",
            "photo,photography,camera,picture",
            "Respect privacy and local rules when taking photographs, especially around people, restricted locations, or official facilities."
        )

        // 45
        addTip(
            db,
            "Valuables",
            "valuable,jewelry,gold,expensive",
            "Avoid displaying expensive valuables unnecessarily and keep important items secured while travelling."
        )

        // 46
        addTip(
            db,
            "Check-in",
            "checkin,check-in,safe,update",
            "Send a quick check-in message to someone you trust when you arrive at an important destination."
        )

        // 47
        addTip(
            db,
            "Emergency Preparation",
            "prepare,prepared,emergencykit,emergency preparation",
            "Know the local emergency number, keep your phone charged, and know where to seek official help."
        )

        // 48
        addTip(
            db,
            "Evening Transport",
            "eveningtransport,nighttransport,late transport",
            "For late travel, prefer trusted transport and verify the route and destination before starting."
        )

        // 49
        addTip(
            db,
            "Solo Travel",
            "solo,alone,travelling alone,travel alone",
            "When travelling alone, keep someone informed about your plans and choose familiar public locations when possible."
        )

        // 50
        addTip(
            db,
            "General Travel Safety",
            "safety,travel safety,travelling,safe travel",
            "Stay aware of your surroundings, protect your belongings, keep communication available, and seek official help when necessary."
        )
    }

    private fun addTip(
        db: SQLiteDatabase,
        topic: String,
        keywords: String,
        answer: String
    ) {
        val values = ContentValues()

        values.put("topic", topic)
        values.put("keywords", keywords)
        values.put("answer", answer)

        db.insert("travel_tips", null, values)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        db.execSQL("DROP TABLE IF EXISTS travel_tips")
        onCreate(db)
    }
}