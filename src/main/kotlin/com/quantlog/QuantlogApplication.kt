package com.quantlog

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class QuantlogApplication

fun main(args: Array<String>) {
    runApplication<QuantlogApplication>(*args)
}
