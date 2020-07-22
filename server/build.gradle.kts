import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  application
  kotlin("jvm")
  id("com.github.johnrengelman.shadow") version "6.0.0"
  id("com.squareup.sqldelight")
}

sqldelight {
  database("Database") {
    packageName = "xchange.db"
    dialect = "hsql"
  }
}

group = "com.ryanharter.xchange"
version = "0.0.1-SNAPSHOT"

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
  kotlinOptions.jvmTarget = "1.8"
}

repositories {
  mavenCentral()
  jcenter()
}

dependencies {
  implementation(kotlin("stdlib"))

  val ktorVersion = "1.3.2"
  implementation("io.ktor:ktor-server-netty:$ktorVersion")
  implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
  implementation("io.ktor:ktor-freemarker:$ktorVersion")
  implementation("ch.qos.logback:logback-classic:1.2.3")
//  implementation("ch.qos.logback:logback-core:1.2.3")
  implementation("org.slf4j:slf4j-api:1.7.26")

  val sqldelightVersion = "1.4.0"
  implementation("com.squareup.sqldelight:runtime-jvm:$sqldelightVersion")
  implementation("com.squareup.sqldelight:jdbc-driver:$sqldelightVersion")
  implementation("com.squareup.sqldelight:coroutines-extensions:$sqldelightVersion")
  implementation("com.h2database:h2:1.4.200")
  implementation("com.mchange:c3p0:0.9.5.5")
}

application {
  mainClassName = "io.ktor.server.netty.EngineMain"
}

tasks.withType<ShadowJar> {
  val jar: Jar by tasks
  val runtimeClasspath by project.configurations

  configurations = listOf(runtimeClasspath)

  from(jar.archiveFile)

  archiveBaseName.value("server")
  classifier = null
  version = null
}
