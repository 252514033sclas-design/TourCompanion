package com.example.tourcompanion

data class User(
    val id: Long,
    val name: String,
    val email: String,
    val language: String = "English",
    val createdAt: String = ""
)
