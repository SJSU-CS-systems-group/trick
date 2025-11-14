package net.discdd.trick

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform