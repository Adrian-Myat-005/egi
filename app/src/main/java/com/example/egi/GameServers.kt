package com.example.egi

data class GameServer(
    val name: String,
    val ip: String
)

object GameServers {
    val list = listOf(
        GameServer("Global (Cloudflare)", "1.1.1.1"),
        GameServer("PUBG Mobile (Singapore)", "52.221.160.2"),
        GameServer("Mobile Legends (Indonesia)", "103.28.54.1"),
        GameServer("Valorant (Tokyo)", "203.178.128.1"),
        GameServer("Call of Duty (Frankfurt)", "3.120.0.0"),
        GameServer("Google (US)", "8.8.8.8")
    )
}
