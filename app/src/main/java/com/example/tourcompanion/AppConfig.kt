package com.example.tourcompanion

object AppConfig {

    /**
     * HTTPS address of YOUR backend that talks to Claude (see /backend/server.js).
     *
     * NEVER put an Anthropic API key in this app - anything inside an APK can be extracted.
     * Leave this empty to run fully offline: the app then answers only from its built-in tips.
     *
     * Expected contract:  POST {"question": "..."}  ->  {"answer": "..."}
     */
    const val AI_PROXY_URL = "http://10.0.2.2/tourcompanion/api/ask.php"
}
