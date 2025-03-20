package io.deepmedia.tools.grease.sample.library.spi

class SimpleHandler : CommandHandler {
    override fun handle() {
        println("SPI simple handler")
    }
}
