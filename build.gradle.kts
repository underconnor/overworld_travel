plugins { java }

group = "io.github.underconnor.overworld"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.23.0")
    add(mockitoAgent.name, "org.mockito:mockito-core:5.23.0") { isTransitive = false }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${mockitoAgent.asPath}")
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
tasks.jar {
    manifest.attributes["Implementation-Version"] = project.version
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
dependencyLocking { lockAllConfigurations() }
