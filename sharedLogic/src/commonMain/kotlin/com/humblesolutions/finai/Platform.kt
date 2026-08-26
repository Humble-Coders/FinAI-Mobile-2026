package com.humblesolutions.finai

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform