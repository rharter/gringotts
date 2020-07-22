package xchange

import io.ktor.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

fun main2(args: Array<String>) {
  embeddedServer(
    Netty,
    8080,
    watchPaths = listOf("server"),
    module = Application::main
  ).start(wait = true)
}
